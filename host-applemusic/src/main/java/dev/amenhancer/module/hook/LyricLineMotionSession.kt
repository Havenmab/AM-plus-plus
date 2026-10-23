package dev.amenhancer.module.hook

import android.animation.Animator
import android.animation.AnimatorListenerAdapter
import android.animation.ValueAnimator
import android.os.Handler
import android.os.Looper
import android.view.View
import android.view.ViewGroup
import android.view.animation.LinearInterpolator
import dev.amenhancer.module.lyrics.motion.LyricLineMotionPolicy
import dev.amenhancer.module.model.ModuleSettings
import java.lang.reflect.Method
import java.lang.reflect.Modifier
import java.util.Collections
import java.util.WeakHashMap
import kotlin.math.abs

/**
 * iOS-style lyric line motion: replaces Apple Music's own "instant re-anchor plus a 750 ms
 * linear row translation" with the spring the iOS player uses.
 *
 * Where the motion comes from (verified against the 1609 profile and the published
 * iPadOS `SyncedLyricsViewController.Specs`):
 *
 *  - the host re-anchors the list instantly (`scrollToPositionWithOffset`), then its
 *    `ItemAnimator` translates every moved row from the old position back to the new one.
 *    That `animateMove` callback carries the exact `fromY`/`toY` pair, so the session hooks
 *    it, refuses the host animation and drives the same displacement with
 *    [LyricLineMotionPolicy.lineChange] sampled per frame;
 *  - `vmLine` (`setCurrentHighlightedLine`) tells which row is now emphasised, so the
 *    previous row returns to Apple's `deselectedTransform` (0.98) and the new row grows to
 *    `emphasizingScaleRange` (1.14 by default) with the same spring;
 *  - neighbouring rows start [LyricLineMotionPolicy.APPLE_ROW_DELAY_MS] later per row
 *    distance (`lineDelay`), capped at [LyricLineMotionPolicy.MAX_STAGGER_ROWS].
 *
 * The feature is off by default: while `lyricLineMotionEnabled` is false every callback
 * returns immediately and Apple Music keeps its own animation untouched.
 */
internal class LyricLineMotionSession(
    private val contract: LyricLineMotionContract,
    private val settings: () -> ModuleSettings,
) {
    private val handler = Handler(Looper.getMainLooper())
    private val recyclers = Collections.synchronizedMap(WeakHashMap<ViewGroup, Boolean>())
    private val hookedMethods = Collections.synchronizedSet(HashSet<Method>())
    private val rowAnimators = Collections.synchronizedMap(WeakHashMap<View, ValueAnimator>())
    private val scaleAnimators = Collections.synchronizedMap(WeakHashMap<View, ValueAnimator>())
    private val positionMethods = Collections.synchronizedMap(HashMap<String, Method?>())

    @Volatile
    private var highlightedLine = NO_LINE

    @Volatile
    private var closed = false

    /** Registers every callback through the caller's hook scope. */
    fun install(register: (Method, ModernMethodHook) -> Unit) {
        register(contract.createView, object : ModernMethodHook() {
            override fun afterHookedMethod(param: MethodHookParam) {
                if (param.throwable != null) return
                val fragment = param.thisObject ?: return
                val recycler = runCatching { contract.recyclerOf(fragment) }.getOrNull() ?: return
                onMain {
                    remember(recycler, register)
                    // The host installs its ItemAnimator inside onCreateView and a reused
                    // session can still be null there, so retry once on the next message.
                    handler.post { if (!closed) remember(recycler, register) }
                }
            }
        })
        register(contract.vmLine, object : ModernMethodHook() {
            override fun beforeHookedMethod(param: MethodHookParam) {
                if (param.throwable != null) return
                val line = (param.args.firstOrNull() as? Number)?.toInt() ?: return
                highlightedLine = line
            }

            override fun afterHookedMethod(param: MethodHookParam) {
                if (param.throwable != null) return
                val current = highlightedLine
                if (current < 0) return
                onMain { emphasise(current) }
            }
        })
    }

    fun close() {
        closed = true
        handler.removeCallbacksAndMessages(null)
        resetAll(rowAnimators) { it.translationY = 0f }
        resetAll(scaleAnimators) {
            it.scaleX = 1f
            it.scaleY = 1f
        }
        synchronized(recyclers) { recyclers.clear() }
        synchronized(hookedMethods) { hookedMethods.clear() }
        synchronized(positionMethods) { positionMethods.clear() }
        highlightedLine = NO_LINE
    }

    private fun remember(recycler: ViewGroup, register: (Method, ModernMethodHook) -> Unit) {
        if (closed) return
        synchronized(recyclers) { recyclers[recycler] = true }
        val animator = itemAnimatorOf(recycler) ?: return
        val move = moveMethodOf(animator.javaClass) ?: return
        if (!synchronized(hookedMethods) { hookedMethods.add(move) }) return
        register(move, object : ModernMethodHook() {
            override fun beforeHookedMethod(param: MethodHookParam) {
                if (param.throwable != null) return
                val policy = runCatching(settings).getOrNull() ?: return
                if (!LyricLineMotionPolicy.isEnabled(policy)) return
                val holder = param.args.firstOrNull() ?: return
                val view = holderView(holder) ?: return
                val fromY = (param.args.getOrNull(2) as? Number)?.toInt() ?: return
                val toY = (param.args.getOrNull(4) as? Number)?.toInt() ?: return
                val dy = (fromY - toY).toFloat()
                if (abs(dy) < MIN_MOVE_PX) return
                // Apple Music would translate the row over its own duration; the spring
                // below owns the same displacement instead.
                param.result = false
                val position = adapterPosition(holder)
                val line = highlightedLine
                val distance = if (position >= 0 && line >= 0) abs(position - line) else 0
                onMain { slide(view, dy, distance, policy) }
            }
        })
    }

    private fun slide(view: View, dy: Float, distance: Int, policy: ModuleSettings) {
        if (closed) return
        rowAnimators.remove(view)?.cancel()
        view.translationY = dy
        val duration = LyricLineMotionPolicy.durationMillis(policy)
        val animator = springAnimator(duration, LyricLineMotionPolicy.rowDelayMillis(policy, distance), policy) { progress ->
            view.translationY = dy * (1f - progress)
        }
        animator.addListener(object : AnimatorListenerAdapter() {
            override fun onAnimationEnd(animation: Animator) {
                if (rowAnimators[view] !== animation) return
                rowAnimators.remove(view)
                if (!closed) view.translationY = 0f
            }

            override fun onAnimationCancel(animation: Animator) {
                if (rowAnimators[view] === animation) rowAnimators.remove(view)
            }
        })
        rowAnimators[view] = animator
        animator.start()
    }

    /**
     * The emphasised row grows like iOS and every other visible row falls back to Apple's
     * `deselectedTransform`. Normalising all rows (not just the previous one) keeps a seek
     * that skips several lines from leaving a stale emphasised row behind.
     */
    private fun emphasise(current: Int) {
        if (closed) return
        val policy = runCatching(settings).getOrNull() ?: return
        val recycler = activeRecycler() ?: return
        if (!LyricLineMotionPolicy.isEnabled(policy)) {
            resetRows(recycler)
            return
        }
        val emphasis = LyricLineMotionPolicy.emphasisScale(policy)
        for (index in 0 until recycler.childCount) {
            val child = recycler.getChildAt(index) ?: continue
            val position = childPosition(recycler, child)
            if (position == NO_POSITION) continue
            val target = if (position == current) emphasis else LyricLineMotionPolicy.DESELECTED_SCALE
            scaleTo(child, target, policy)
        }
    }

    /** Puts every visible row back to Apple's own scale, e.g. after the feature is switched off. */
    private fun resetRows(recycler: ViewGroup) {
        for (index in 0 until recycler.childCount) {
            val child = recycler.getChildAt(index) ?: continue
            scaleAnimators.remove(child)?.cancel()
            rowAnimators.remove(child)?.cancel()
            runCatching {
                child.scaleX = 1f
                child.scaleY = 1f
                child.translationY = 0f
            }
        }
    }

    private fun scaleTo(view: View, target: Float, policy: ModuleSettings) {
        if (closed) return
        val from = view.scaleX
        if (abs(from - target) < SCALE_EPSILON) return
        scaleAnimators.remove(view)?.cancel()
        val duration = LyricLineMotionPolicy.durationMillis(policy)
        val animator = springAnimator(duration, 0L, policy) { progress ->
            val value = from + (target - from) * progress
            view.scaleX = value
            view.scaleY = value
        }
        animator.addListener(object : AnimatorListenerAdapter() {
            override fun onAnimationEnd(animation: Animator) {
                if (scaleAnimators[view] !== animation) return
                scaleAnimators.remove(view)
                if (!closed) {
                    view.scaleX = target
                    view.scaleY = target
                }
            }

            override fun onAnimationCancel(animation: Animator) {
                if (scaleAnimators[view] === animation) scaleAnimators.remove(view)
            }
        })
        scaleAnimators[view] = animator
        animator.start()
    }

    /**
     * A linearly-driven animator whose value is the closed-form spring sample, so the curve
     * is identical on every device and frame rate.
     */
    private fun springAnimator(
        duration: Long,
        startDelay: Long,
        policy: ModuleSettings,
        apply: (Float) -> Unit,
    ): ValueAnimator = ValueAnimator.ofFloat(0f, 1f).apply {
        this.duration = duration
        this.startDelay = startDelay
        interpolator = LinearInterpolator()
        addUpdateListener { animator ->
            val elapsed = ((animator.animatedValue as Float) * duration).toLong()
            apply(LyricLineMotionPolicy.progress(policy, elapsed).coerceIn(0f, 1.2f))
        }
    }

    private fun activeRecycler(): ViewGroup? {
        val snapshot = synchronized(recyclers) { recyclers.keys.toList() }
        return snapshot.firstOrNull { it.isAttachedToWindow && it.childCount > 0 }
    }

    private fun itemAnimatorOf(recycler: ViewGroup): Any? = runCatching {
        recycler.javaClass.getMethod("getItemAnimator").invoke(recycler)
    }.getOrNull()

    /**
     * `RecyclerView.ItemAnimator.animateMove(ViewHolder, int, int, int, int): Boolean`. The
     * host's ItemAnimator is fully renamed, so the method is found by shape; `animateChange`
     * has six parameters and cannot collide.
     */
    private fun moveMethodOf(type: Class<*>): Method? = generateSequence(type) { it.superclass }
        .flatMap { current -> current.declaredMethods.asSequence() }
        .firstOrNull { method ->
            !Modifier.isStatic(method.modifiers) &&
                method.returnType == java.lang.Boolean.TYPE &&
                method.parameterTypes.size == MOVE_PARAMETERS &&
                method.parameterTypes[0].name.endsWith("RecyclerView\$ViewHolder") &&
                (1 until MOVE_PARAMETERS).all { method.parameterTypes[it] == Integer.TYPE }
        }
        ?.apply { isAccessible = true }

    private fun holderView(holder: Any): View? = runCatching {
        holder.javaClass.getMethod("getItemView").invoke(holder) as? View
    }.getOrNull()

    private fun adapterPosition(holder: Any): Int {
        val type = holder.javaClass
        val method = positionMethods.getOrPut(type.name) {
            runCatching { type.getMethod("getAbsoluteAdapterPosition") }.getOrNull()
                ?: runCatching { type.getMethod("getAdapterPosition") }.getOrNull()
        } ?: return NO_POSITION
        return (runCatching { method.invoke(holder) as? Number }.getOrNull())?.toInt() ?: NO_POSITION
    }

    private fun childPosition(recycler: ViewGroup, child: View): Int {
        val method = positionMethods.getOrPut(CHILD_POSITION_KEY) {
            runCatching { recycler.javaClass.getMethod("getChildAdapterPosition", View::class.java) }.getOrNull()
        } ?: return NO_POSITION
        return (runCatching { method.invoke(recycler, child) as? Number }.getOrNull())?.toInt() ?: NO_POSITION
    }

    private fun resetAll(map: MutableMap<View, ValueAnimator>, reset: (View) -> Unit) {
        val snapshot = synchronized(map) { map.keys.toList() }
        snapshot.forEach { view ->
            map.remove(view)?.cancel()
            runCatching { reset(view) }
        }
        synchronized(map) { map.clear() }
    }

    private fun onMain(action: () -> Unit) {
        if (closed) return
        if (Looper.myLooper() == Looper.getMainLooper()) {
            action()
        } else {
            handler.post { if (!closed) runCatching(action) }
        }
    }

    private companion object {
        const val NO_LINE = -1
        const val NO_POSITION = -1
        const val MOVE_PARAMETERS = 5
        const val MIN_MOVE_PX = 1f
        const val SCALE_EPSILON = 0.001f
        const val CHILD_POSITION_KEY = "RecyclerView#getChildAdapterPosition"
    }
}
