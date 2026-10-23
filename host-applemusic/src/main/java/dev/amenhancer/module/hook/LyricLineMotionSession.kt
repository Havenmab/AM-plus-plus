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
    private val scrollAnimators = Collections.synchronizedMap(WeakHashMap<View, ValueAnimator>())
    private val positionMethods = Collections.synchronizedMap(HashMap<String, Method?>())
    private val traceCounts = HashMap<String, Int>()

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
        installScrollProbes(register)
    }

    fun close() {
        closed = true
        handler.removeCallbacksAndMessages(null)
        resetAll(rowAnimators) { it.translationY = 0f }
        resetAll(scaleAnimators) {
            it.scaleX = 1f
            it.scaleY = 1f
        }
        scrollAnimators.keys.toList().forEach { scrollAnimators.remove(it)?.cancel() }
        synchronized(recyclers) { recyclers.clear() }
        synchronized(hookedMethods) { hookedMethods.clear() }
        synchronized(positionMethods) { positionMethods.clear() }
        highlightedLine = NO_LINE
    }

    private fun remember(recycler: ViewGroup, register: (Method, ModernMethodHook) -> Unit) {
        if (closed) return
        synchronized(recyclers) { recyclers[recycler] = true }
        val animator = itemAnimatorOf(recycler)
        if (animator == null) {
            trace("remember animator=none")
            return
        }
        val move = moveMethodOf(animator.javaClass)
        if (move == null) {
            trace("remember animator=" + animator.javaClass.name + " move=none")
            return
        }
        if (!synchronized(hookedMethods) { hookedMethods.add(move) }) return
        trace(
            "remember animator=" + animator.javaClass.name +
                " move=" + move.declaringClass.name + "#" + move.name +
                " enabled=" + runCatching(settings).getOrNull()?.let { LyricLineMotionPolicy.isEnabled(it) },
        )
        register(move, object : ModernMethodHook() {
            override fun beforeHookedMethod(param: MethodHookParam) {
                if (param.throwable != null) return
                val policy = runCatching(settings).getOrNull() ?: return
                trace("animateMove enabled=" + LyricLineMotionPolicy.isEnabled(policy) + " dy=" +
                    param.args.getOrNull(2) + "/" + param.args.getOrNull(4))
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

    /**
     * Diagnostics only: records which host call actually moves the list on a line change.
     * `scrollToPositionWithOffset` is the instant re-anchor; `animateMove` on the host's item
     * animator is the path this feature replaces. Seeing which of them fires tells whether a
     * build without visible motion is missing a hook or has the switch off.
     */
    private fun installScrollProbes(register: (Method, ModernMethodHook) -> Unit) {
        val recyclerType = runCatching {
            Class.forName("androidx.recyclerview.widget.RecyclerView", false, contract.loader)
        }.getOrNull()
        if (recyclerType != null) {
            for (name in SCROLL_PROBES) {
                val method = runCatching {
                    recyclerType.getMethod(name, Int::class.javaPrimitiveType, Int::class.javaPrimitiveType)
                }.getOrNull() ?: continue
                register(method, object : ModernMethodHook() {
                    override fun beforeHookedMethod(param: MethodHookParam) {
                        val view = param.thisObject as? View ?: return
                        if (!isLyricRecycler(view)) return
                        trace(name + " dy=" + param.args.getOrNull(1))
                    }
                })
            }
        }
        val managerType = runCatching {
            Class.forName("androidx.recyclerview.widget.LinearLayoutManager", false, contract.loader)
        }.getOrNull() ?: return
        val anchorScroll = runCatching {
            managerType.getMethod(
                "scrollToPositionWithOffset",
                Int::class.javaPrimitiveType,
                Int::class.javaPrimitiveType,
            )
        }.getOrNull() ?: return
        register(anchorScroll, object : ModernMethodHook() {
            override fun beforeHookedMethod(param: MethodHookParam) {
                val manager = param.thisObject ?: return
                val view = layoutManagerRecycler(manager) ?: return
                if (!isLyricRecycler(view)) return
                val position = (param.args.getOrNull(0) as? Number)?.toInt() ?: return
                val offset = (param.args.getOrNull(1) as? Number)?.toInt() ?: return
                trace(
                    "scrollToPositionWithOffset pos=" + position +
                        " offset=" + offset +
                        " top=" + firstVisibleTop(manager),
                )
                val recycler = view as? ViewGroup ?: return
                val policy = runCatching(settings).getOrNull() ?: return
                if (!LyricLineMotionPolicy.isEnabled(policy)) return
                if (isComputingLayout(recycler)) return
                val delta = anchoredDelta(recycler, position, offset) ?: return
                if (abs(delta) < MIN_GLIDE_PX) return
                // Apple Music re-anchors the list instantly; gliding the same distance with
                // the iOS spring is what makes a line change look like iOS instead of a cut.
                param.result = null
                trace("glide pos=" + position + " delta=" + delta)
                glide(recycler, delta, policy) {
                    runCatching { anchorScroll.invoke(manager, position, offset) }
                }
            }
        })
    }
    private fun isComputingLayout(recycler: View): Boolean = runCatching {
        recycler.javaClass.getMethod("isComputingLayout").invoke(recycler) as? Boolean
    }.getOrNull() == true

    /** How far the list has to move to put [position] at [offset] from the padded top. */
    private fun anchoredDelta(recycler: ViewGroup, position: Int, offset: Int): Float? {
        val manager = runCatching {
            recycler.javaClass.getMethod("getLayoutManager").invoke(recycler)
        }.getOrNull() ?: return null
        val find = runCatching {
            manager.javaClass.getMethod("findViewByPosition", Int::class.javaPrimitiveType)
        }.getOrNull() ?: return null
        val child = runCatching { find.invoke(manager, position) as? View }.getOrNull() ?: return null
        val targetTop = recycler.paddingTop + offset
        return (child.top - targetTop).toFloat()
    }

    /** Drives [recycler] over [delta] pixels with the iOS spring, then snaps exactly. */
    private fun glide(recycler: ViewGroup, delta: Float, policy: ModuleSettings, snap: () -> Unit) {
        if (closed) return
        scrollAnimators.remove(recycler)?.cancel()
        val duration = LyricLineMotionPolicy.durationMillis(policy)
        var applied = 0
        val animator = springAnimator(duration, 0L, policy) { progress ->
            val target = (delta * progress).toInt()
            val step = target - applied
            if (step != 0) {
                applied = target
                runCatching { recycler.scrollBy(0, step) }
            }
        }
        animator.addListener(object : AnimatorListenerAdapter() {
            override fun onAnimationEnd(animation: Animator) {
                if (scrollAnimators[recycler] !== animation) return
                scrollAnimators.remove(recycler)
                if (!closed) runCatching(snap)
            }

            override fun onAnimationCancel(animation: Animator) {
                if (scrollAnimators[recycler] === animation) scrollAnimators.remove(recycler)
            }
        })
        scrollAnimators[recycler] = animator
        animator.start()
    }
    private fun isLyricRecycler(view: View): Boolean =
        synchronized(recyclers) { recyclers.containsKey(view) }

    private fun layoutManagerRecycler(manager: Any): View? {
        val field = layoutManagerField ?: runCatching {
            generateSequence(manager.javaClass) { it.superclass }
                .flatMap { it.declaredFields.asSequence() }
                .firstOrNull { it.name == "mRecyclerView" }
                ?.apply { isAccessible = true }
        }.getOrNull()?.also { layoutManagerField = it }
        return runCatching { field?.get(manager) as? View }.getOrNull()
    }

    private fun firstVisibleTop(manager: Any): Int = runCatching {
        val childAt = manager.javaClass.getMethod("getChildAt", Int::class.javaPrimitiveType)
        (childAt.invoke(manager, 0) as? View)?.top ?: -1
    }.getOrDefault(-1)

    /** Logs a diagnostic line at most [limit] times per key. */
    private fun trace(message: String, limit: Int = 12) {
        val key = message.substringBefore(' ')
        val seen = synchronized(traceCounts) {
            val next = (traceCounts[key] ?: 0) + 1
            traceCounts[key] = next
            next
        }
        if (seen <= limit) {
            ModernXposedRuntime.log("lyric_line_motion: " + message + " (" + seen + ")")
        }
    }

    private var layoutManagerField: java.lang.reflect.Field? = null
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
        trace("emphasise line=" + current + " children=" + recycler.childCount + " scale=" + emphasis)
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
        const val MIN_GLIDE_PX = 2f
        const val SCALE_EPSILON = 0.001f
        const val CHILD_POSITION_KEY = "RecyclerView#getChildAdapterPosition"
        val SCROLL_PROBES = listOf("scrollBy", "smoothScrollBy")
    }
}
