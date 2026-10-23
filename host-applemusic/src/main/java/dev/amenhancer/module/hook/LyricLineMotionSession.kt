package dev.amenhancer.module.hook

import android.animation.Animator
import android.animation.AnimatorListenerAdapter
import android.animation.ValueAnimator
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
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
    private val scaleAnimators = Collections.synchronizedMap(WeakHashMap<View, ValueAnimator>())
    private val glides = Collections.synchronizedMap(WeakHashMap<ViewGroup, GlideState>())
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
        val live = synchronized(glides) { glides.keys.toList() }
        live.forEach { recycler ->
            cancelGlide(recycler)
            runCatching { translateRows(recycler, 0f) }
        }
        synchronized(glides) { glides.clear() }
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
        val change = changeMethodOf(animator.javaClass)
        if (change != null && synchronized(hookedMethods) { hookedMethods.add(change) }) {
            register(change, object : ModernMethodHook() {
                override fun beforeHookedMethod(param: MethodHookParam) {
                    val holder = param.args.firstOrNull() ?: return
                    val view = holderView(holder) ?: return
                    if (!belongsToLyricRecycler(view)) return
                    val policy = runCatching(settings).getOrNull() ?: return
                    val enabled = LyricLineMotionPolicy.isEnabled(policy)
                    trace("animateChange row=" + adapterPosition(holder) + " enabled=" + enabled)
                    if (!enabled) return
                    // Apple Music cross-fades the outgoing row into the incoming one in place.
                    // On top of the block offset that showed the same line twice, so the offset
                    // alone carries the motion and the cross-fade is refused.
                    param.result = false
                }
            })
        }
        trace(
            "remember animator=" + animator.javaClass.name +
                " move=" + move.declaringClass.name + "#" + move.name +
                " change=" + (change?.let { it.declaringClass.name + "#" + it.name } ?: "none") +
                " enabled=" + runCatching(settings).getOrNull()?.let { LyricLineMotionPolicy.isEnabled(it) },
        )
        register(move, object : ModernMethodHook() {
            override fun beforeHookedMethod(param: MethodHookParam) {
                if (param.throwable != null) return
                val policy = runCatching(settings).getOrNull() ?: return
                val holder = param.args.firstOrNull() ?: return
                val view = holderView(holder) ?: return
                val recycler = lyricRecyclerOf(view)
                val fromY = (param.args.getOrNull(2) as? Number)?.toInt() ?: return
                val toY = (param.args.getOrNull(4) as? Number)?.toInt() ?: return
                val enabled = LyricLineMotionPolicy.isEnabled(policy)
                trace(
                    "animateMove enabled=" + enabled + " dy=" + fromY + "/" + toY +
                        " row=" + adapterPosition(holder) + " list=" + (recycler != null),
                )
                if (!enabled || recycler == null) return
                // Apple Music slides every moved row with its own 750 ms linear tween. The row
                // is refused here and the whole block is offset instead: one uniform spring is
                // what makes the change read as the list scrolling rather than a stack of rows
                // sliding past each other.
                param.result = false
                val dy = (fromY - toY).toFloat()
                if (abs(dy) < MIN_MOVE_PX) return
                startGlide(recycler, dy, policy)
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
        if (recyclerType == null) trace("probe androidx RecyclerView missing")
        if (recyclerType != null) {
            for (name in SCROLL_PROBES) {
                val method = runCatching {
                    recyclerType.getMethod(name, Int::class.javaPrimitiveType, Int::class.javaPrimitiveType)
                }.getOrNull() ?: run {
                    trace("probe missing " + name)
                    continue
                }
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
        }.getOrNull() ?: run {
            trace("probe LinearLayoutManager missing")
            return
        }
        val anchorScroll = runCatching {
            managerType.getMethod(
                "scrollToPositionWithOffset",
                Int::class.javaPrimitiveType,
                Int::class.javaPrimitiveType,
            )
        }.getOrNull() ?: run {
            trace("probe scrollToPositionWithOffset missing")
            return
        }
        trace("probe scrollToPositionWithOffset registered")
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
                // Diagnostics only: the block offset started from animateMove owns the motion.
                trace("anchor pos=" + position + " offset=" + offset + " list=" + (view != null))
            }
        })
    }
    /** Nearest ancestor that is the lyric list itself, or null for any other row. */
    private fun lyricRecyclerOf(view: View): ViewGroup? {
        var parent: View? = view
        var depth = 0
        while (parent != null && depth < BELONGING_DEPTH) {
            if (isLyricRecycler(parent)) return parent as? ViewGroup
            parent = parent.parent as? View
            depth++
        }
        return null
    }

    private fun belongsToLyricRecycler(view: View): Boolean {
        var parent: View? = view
        var depth = 0
        while (parent != null && depth < BELONGING_DEPTH) {
            if (isLyricRecycler(parent)) return true
            parent = parent.parent as? View
            depth++
        }
        return false
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

    /**
     * One uniform offset for the whole block, driven by the iOS line-change spring.
     *
     * Every row of a single layout pass reports the same distance, so only the first call of a
     * pass starts the animator; the rest are already refused by the move hook. Rows are offset
     * with `translationY` (androidx member names are renamed in this build, so
     * `offsetChildrenVertical` cannot be reached reliably) and the value is written
     * absolutely on every frame so a row that gets rebound mid-glide cannot drift.
     */
    private fun startGlide(recycler: ViewGroup, delta: Float, policy: ModuleSettings) {
        if (closed) return
        val now = SystemClock.uptimeMillis()
        val state = synchronized(glides) { glides.getOrPut(recycler) { GlideState() } }
        if (state.animator != null && now - state.at < BATCH_WINDOW_MS && abs(state.delta - delta) < MIN_MOVE_PX) {
            state.at = now
            return
        }
        state.animator?.cancel()
        state.delta = delta
        state.at = now
        val duration = LyricLineMotionPolicy.durationMillis(policy)
        // Rows are already laid out at the new positions; pushing them back by the same
        // distance restores the previous frame, and the spring releases that offset.
        translateRows(recycler, delta)
        val animator = springAnimator(duration, 0L, policy) { progress ->
            translateRows(recycler, delta * (1f - progress))
        }
        animator.addListener(object : AnimatorListenerAdapter() {
            override fun onAnimationEnd(animation: Animator) {
                if (state.animator !== animation) return
                state.animator = null
                if (!closed) runCatching { translateRows(recycler, 0f) }
            }

            override fun onAnimationCancel(animation: Animator) {
                if (state.animator === animation) state.animator = null
            }
        })
        state.animator = animator
        animator.start()
        trace("glide delta=" + delta.toInt() + " rows=" + recycler.childCount)
    }

    private fun cancelGlide(recycler: ViewGroup) {
        val state = synchronized(glides) { glides[recycler] } ?: return
        state.animator?.cancel()
        state.animator = null
    }

    private fun translateRows(recycler: ViewGroup, offset: Float) {
        for (index in 0 until recycler.childCount) {
            val child = recycler.getChildAt(index) ?: continue
            child.translationY = offset
        }
    }

    private class GlideState {
        var animator: ValueAnimator? = null
        var delta = 0f
        var at = 0L
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
        val enabled = LyricLineMotionPolicy.isEnabled(policy)
        trace("emphasise line=" + current + " children=" + recycler.childCount + " enabled=" + enabled)
        if (!enabled) {
            resetRows(recycler)
            return
        }
        val emphasis = LyricLineMotionPolicy.emphasisScale(policy)
        for (index in 0 until recycler.childCount) {
            val child = recycler.getChildAt(index) ?: continue
            val position = childPosition(recycler, child)
            if (position == NO_POSITION) continue
            val selected = position == current
            scaleTo(child, if (selected) emphasis else LyricLineMotionPolicy.DESELECTED_SCALE, policy, selected)
        }
    }

    /** Puts every visible row back to Apple's own scale, e.g. after the feature is switched off. */
    private fun resetRows(recycler: ViewGroup) {
        cancelGlide(recycler)
        for (index in 0 until recycler.childCount) {
            val child = recycler.getChildAt(index) ?: continue
            scaleAnimators.remove(child)?.cancel()
            runCatching {
                child.scaleX = 1f
                child.scaleY = 1f
                child.translationY = 0f
            }
        }
    }

    private fun scaleTo(view: View, target: Float, policy: ModuleSettings, anchorLeft: Boolean) {
        if (closed) return
        val from = view.scaleX
        if (abs(from - target) < SCALE_EPSILON) return
        trace("scale " + from + " -> " + target + " pivotLeft=" + anchorLeft)
        // Apple Music scales the whole row about its centre. A full-width row then grows past
        // the left edge of the lyrics pane, which clips the first glyph and breaks the block's
        // left alignment; anchoring the emphasised row at its leading edge keeps the text
        // inside the pane. Rows that are not emphasised keep the host's centred pivot.
        view.pivotX = if (anchorLeft) 0f else view.width / 2f
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
                method.parameterTypes[0].name.startsWith(VIEW_HOLDER_PREFIX) &&
                (1 until MOVE_PARAMETERS).all { method.parameterTypes[it] == Integer.TYPE }
        }
        ?.apply { isAccessible = true }

    /** `ItemAnimator.animateChange(ViewHolder, ViewHolder, int, int, int, int): Boolean`. */
    private fun changeMethodOf(type: Class<*>): Method? = generateSequence<Class<*>>(type) { it.superclass }
        .flatMap { current -> current.declaredMethods.asSequence() }
        .firstOrNull { method ->
            !Modifier.isStatic(method.modifiers) &&
                method.returnType == java.lang.Boolean.TYPE &&
                method.parameterTypes.size == CHANGE_PARAMETERS &&
                method.parameterTypes[0].name.startsWith(VIEW_HOLDER_PREFIX) &&
                method.parameterTypes[1].name.startsWith(VIEW_HOLDER_PREFIX) &&
                (2 until CHANGE_PARAMETERS).all { method.parameterTypes[it] == Integer.TYPE }
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
                ?: generateSequence<Class<*>>(type) { it.superclass }
                    .flatMap { it.declaredMethods.asSequence() }
                    .firstOrNull {
                        it.returnType == Integer.TYPE &&
                            it.parameterTypes.isEmpty() &&
                            !Modifier.isStatic(it.modifiers)
                    }
                    ?.apply { isAccessible = true }
                    ?.also { trace("holderPositionMethod=" + it.declaringClass.name + "#" + it.name) }
        } ?: return NO_POSITION
        return (runCatching { method.invoke(holder) as? Number }.getOrNull())?.toInt() ?: NO_POSITION
    }

    /**
     * Adapter position of a visible row. `getChildAdapterPosition` is renamed in this build,
     * so the fallback asks the recycler for the child's ViewHolder (no other method takes a
     * View and returns a `RecyclerView$…` member) and reads the position off that holder.
     */
    private fun childPosition(recycler: ViewGroup, child: View): Int {
        val direct = positionMethods.getOrPut(CHILD_POSITION_KEY) {
            runCatching { recycler.javaClass.getMethod("getChildAdapterPosition", View::class.java) }.getOrNull()
                ?: generateSequence<Class<*>>(recycler.javaClass) { it.superclass }
                    .flatMap { it.declaredMethods.asSequence() }
                    .firstOrNull {
                        it.returnType == Integer.TYPE &&
                            it.parameterTypes.size == 1 &&
                            View::class.java.isAssignableFrom(it.parameterTypes[0]) &&
                            it.declaringClass.name.startsWith(RECYCLER_PACKAGE) &&
                            !Modifier.isStatic(it.modifiers)
                    }
                    ?.apply { isAccessible = true }
                    ?.also { trace("childPositionMethod=" + it.declaringClass.name + "#" + it.name) }
        }
        if (direct != null) {
            val value = runCatching { direct.invoke(recycler, child) as? Number }.getOrNull()
            if (value != null) return value.toInt()
        }
        val holderMethod = positionMethods.getOrPut(HOLDER_OF_CHILD_KEY) {
            generateSequence<Class<*>>(recycler.javaClass) { it.superclass }
                .flatMap { it.declaredMethods.asSequence() }
                .firstOrNull {
                    it.parameterTypes.size == 1 &&
                        View::class.java.isAssignableFrom(it.parameterTypes[0]) &&
                        it.returnType.name.startsWith(VIEW_HOLDER_PREFIX) &&
                        !Modifier.isStatic(it.modifiers)
                }
                ?.apply { isAccessible = true }
                ?.also { trace("childViewHolderMethod=" + it.declaringClass.name + "#" + it.name) }
        } ?: return NO_POSITION
        val holder = runCatching { holderMethod.invoke(recycler, child) }.getOrNull() ?: return NO_POSITION
        return adapterPosition(holder)
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
        const val CHANGE_PARAMETERS = 6

        /**
         * The host obfuscates androidx's inner classes, so `RecyclerView.ViewHolder` is
         * `androidx.recyclerview.widget.RecyclerView$D` in this build. The outer class name
         * survives, the member name does not: match the outer prefix, not `$ViewHolder`.
         */
        const val VIEW_HOLDER_PREFIX = "androidx.recyclerview.widget.RecyclerView\$"
        const val MIN_MOVE_PX = 1f
        const val BATCH_WINDOW_MS = 250L
        const val SCALE_EPSILON = 0.001f
        const val CHILD_POSITION_KEY = "RecyclerView#getChildAdapterPosition"
        const val BELONGING_DEPTH = 8
        const val HOLDER_OF_CHILD_KEY = "RecyclerView#getChildViewHolder"

        /** androidx is obfuscated member-wise but keeps its package prefix. */
        const val RECYCLER_PACKAGE = "androidx.recyclerview.widget."
        val SCROLL_PROBES = listOf("scrollBy", "smoothScrollBy")
    }
}
