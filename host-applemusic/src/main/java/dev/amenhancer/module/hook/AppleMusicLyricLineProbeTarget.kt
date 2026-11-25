package dev.amenhancer.module.hook

import android.os.SystemClock
import android.view.View
import dev.amenhancer.host.applemusic.AppleMusicHostProfile
import java.util.concurrent.atomic.AtomicBoolean

/**
 * 「换行诊断」: install the read-only line-change probe for Apple Music 7.0.3.
 *
 * The probe answers four questions about the host's own line change before this module takes any of
 * it over (see remarks in LyricLineProbe). Five hooks, all observation-only:
 *
 * | role | hook | what it records |
 * | --- | --- | --- |
 * | methods.lineChange | K#b before/after | (start,end), the sentinel, whether it re-anchored, the visible range, V0/U0/T0/M0 |
 * | methods.scrollToOffset | LinearLayoutManager#u1 before | the position and offset of each instant re-anchor |
 * | methods.animateMove | mc.y#m before/after | fromY/toY/translationY, the host's dx/dy, its return value, the queue |
 * | methods.flushDeferred | s2 before/after | the deferred queue size, the flags, and the delay |
 * | methods.lineTransition / lineTransitionLineAdapter | A#N, V0#N before | the row, its binding, and whether it is instrumental |
 *
 * **Zero install when off.** The feature gate decides whether this class is reached at all; there is
 * no dormant hook and no global filter to pay for while the probe is disabled.
 *
 * **Per-role degradation.** [LyricLineProbeRoles] resolves each role separately, and only the roles
 * that resolved get a hook. A build that lost one member keeps every other observation and reports
 * the loss in the capability message instead of failing the install.
 *
 * Nothing here writes to the host: no field, no view property, no return value is changed, and every
 * hook body is wrapped so that a diagnostic failure costs one log line.
 */
internal class AppleMusicLyricLineProbeTarget(
    private val profile: AppleMusicHostProfile,
    private val loader: ClassLoader,
) : LyricLineMotionProbeTarget {
    private var registration: HookRegistrationScope? = null

    @Synchronized
    override fun install(): TargetCapabilityInstall {
        registration?.takeIf { it.isActive }?.let {
            return TargetCapabilityInstall.Active("Line-change probe already active; " + profile.id)
        }
        val section = runCatching {
            profile.document.getJSONObject("lyricLineMotion").getJSONObject("probe")
        }.getOrNull() ?: return TargetCapabilityInstall.Unsupported(
            "Line-change probe has no exact contract; " + profile.id,
        )
        if (!profile.productionEnabled) {
            return TargetCapabilityInstall.Unsupported(
                "Line-change probe needs an exact verified profile; " + profile.id,
            )
        }
        val roles = LyricLineProbeRoles(loader, section)
        val session = ProbeSession(roles)
        if (session.hookPoints == 0) {
            return TargetCapabilityInstall.Degraded(
                "Line-change probe resolved no hook point (" + profile.id + "): " +
                    roles.missing().joinToString("; "),
            )
        }
        val scope = HookRegistrationScope()
        return try {
            session.install(scope)
            scope.activate()
            registration = scope
            val message = "Line-change probe " + session.hookPoints + " hook(s), " +
                roles.resolved().size + " role(s); " + profile.id
            if (roles.missing().isEmpty()) {
                TargetCapabilityInstall.Active(message)
            } else {
                TargetCapabilityInstall.Degraded(message + "; missing " + roles.missing().joinToString("; "))
            }
        } catch (error: Throwable) {
            scope.close()
            registration = null
            val reason = error.message ?: error.javaClass.simpleName
            TargetCapabilityInstall.Degraded("Line-change probe install failed; " + profile.id + ": " + reason)
        }
    }

    /** One installed probe: every hook it resolved, and the reads they share. */
    private class ProbeSession(private val roles: LyricLineProbeRoles) {
        private val probe = LyricLineProbe()
        private val reported = AtomicBoolean(false)

        val hookPoints: Int
            get() = listOfNotNull(
                roles.lineChange,
                roles.scrollToOffset,
                roles.animateMove,
                roles.flushDeferred,
                roles.lineTransition,
                roles.lineTransitionLineAdapter,
            ).size

        fun install(scope: HookRegistrationScope) {
            roles.lineChange?.let { register(it, changeHook(), scope) }
            roles.scrollToOffset?.let { register(it, anchorHook(), scope) }
            roles.animateMove?.let { register(it, moveHook(), scope) }
            roles.flushDeferred?.let { register(it, flushHook(), scope) }
            roles.lineTransition?.let { register(it, rowHook("A"), scope) }
            roles.lineTransitionLineAdapter?.let { register(it, rowHook("V0"), scope) }
        }

        private fun register(member: java.lang.reflect.Method, hook: ModernMethodHook, scope: HookRegistrationScope) {
            check(ModernXposedRuntime.hookMethod(member, hook, scope)) {
                "hook registration refused: " + member
            }
        }

        // ---- hooks -------------------------------------------------------------------------

        /** K#b: the line-change callback the adapter calls with (start,end). */
        private fun changeHook() = object : ModernMethodHook() {
            override fun beforeHookedMethod(param: ModernMethodHook.MethodHookParam) {
                guarded { probe.lineChangeStarted() }
            }

            override fun afterHookedMethod(param: ModernMethodHook.MethodHookParam) {
                guarded {
                    val start = param.args.getOrNull(0) as? Int ?: return@guarded
                    val end = param.args.getOrNull(1) as? Int ?: return@guarded
                    val fragment = fragmentOfCallback(param.thisObject)
                    val range = visibleRange(fragment)
                    probe.lineChange(
                        start = start,
                        end = end,
                        firstVisible = range.first,
                        lastVisible = range.second,
                        state = stateOf(fragment),
                        layoutManager = layoutManagerOf(fragment),
                    )
                }
            }
        }

        /** LinearLayoutManager#u1: scrollToPositionWithOffset, the instant re-anchor. */
        private fun anchorHook() = object : ModernMethodHook() {
            override fun beforeHookedMethod(param: ModernMethodHook.MethodHookParam) {
                guarded {
                    val manager = param.thisObject ?: return@guarded
                    if (!probe.isLyricsLayoutManager(manager, roles.layoutManagerClass)) return@guarded
                    val position = param.args.getOrNull(0) as? Int ?: return@guarded
                    val offset = param.args.getOrNull(1) as? Int ?: return@guarded
                    probe.anchor(position, offset)
                }
            }
        }

        /**
         * mc.y#m: animateMove.
         *
         * The deltas are recomputed in the before hook, exactly as the host does, because the
         * non-zero branch rewrites the view's translation before it returns — reading it afterwards
         * would describe the animation, not the request.
         */
        private fun moveHook() = object : ModernMethodHook() {
            override fun beforeHookedMethod(param: ModernMethodHook.MethodHookParam) {
                guarded {
                    val animator = param.thisObject ?: return@guarded
                    if (!isLyricsAnimator(animator)) return@guarded
                    val view = itemViewOf(param.args.getOrNull(0)) ?: return@guarded
                    val fromX = param.args.getOrNull(1) as? Int ?: return@guarded
                    val fromY = param.args.getOrNull(2) as? Int ?: return@guarded
                    val toX = param.args.getOrNull(3) as? Int ?: return@guarded
                    val toY = param.args.getOrNull(4) as? Int ?: return@guarded
                    val translationX = view.translationX.toInt()
                    val translationY = view.translationY.toInt()
                    param.extras[KEY_DX] = toX - (fromX + translationX)
                    param.extras[KEY_DY] = toY - (fromY + translationY)
                    param.extras[KEY_FROM_Y] = fromY
                    param.extras[KEY_TO_Y] = toY
                    param.extras[KEY_TRANSLATION_Y] = translationY
                }
            }

            override fun afterHookedMethod(param: ModernMethodHook.MethodHookParam) {
                guarded {
                    if (param.throwable != null) return@guarded
                    val dx = param.extras[KEY_DX] as? Int ?: return@guarded
                    val dy = param.extras[KEY_DY] as? Int ?: return@guarded
                    val fromY = param.extras[KEY_FROM_Y] as? Int ?: return@guarded
                    val toY = param.extras[KEY_TO_Y] as? Int ?: return@guarded
                    val translationY = param.extras[KEY_TRANSLATION_Y] as? Int ?: return@guarded
                    val holder = param.args.getOrNull(0)
                    probe.animateMove(
                        position = positionOf(holder),
                        fromY = fromY,
                        toY = toY,
                        translationY = translationY,
                        dx = dx,
                        dy = dy,
                        state = stateOf(fragmentOfAnimator(param.thisObject)),
                        animated = param.result as? Boolean,
                    )
                }
            }
        }

        /** s2: the deferred-queue flush, i.e. when the row transitions actually run. */
        private fun flushHook() = object : ModernMethodHook() {
            override fun beforeHookedMethod(param: ModernMethodHook.MethodHookParam) {
                guarded {
                    val fragment = param.thisObject ?: return@guarded
                    val state = stateOf(fragment)
                    param.extras[KEY_QUEUE_BEFORE] = state.queue
                    param.extras[KEY_FLUSH_AT] = SystemClock.uptimeMillis()
                    probe.flushStarted(state)
                }
            }

            override fun afterHookedMethod(param: ModernMethodHook.MethodHookParam) {
                guarded {
                    val fragment = param.thisObject ?: return@guarded
                    val queueBefore = param.extras[KEY_QUEUE_BEFORE] as? Int ?: -1
                    val startedAt = param.extras[KEY_FLUSH_AT] as? Long ?: -1L
                    val state = stateOf(fragment)
                    val ran = if (queueBefore < 0 || state.queue < 0) -1 else queueBefore - state.queue
                    val duration = if (startedAt < 0L) -1L
                    else (SystemClock.uptimeMillis() - startedAt).coerceAtLeast(0L)
                    probe.flushFinished(state, ran.coerceAtLeast(-1), duration)
                }
            }
        }

        /** A#N / V0#N: the row transition entry point (scale 1.2, then 0.5 + alpha 0). */
        private fun rowHook(adapter: String) = object : ModernMethodHook() {
            override fun beforeHookedMethod(param: ModernMethodHook.MethodHookParam) {
                guarded {
                    val holder = param.args.getOrNull(0) ?: return@guarded
                    val binding = bindingOf(holder)
                    val view = itemViewOf(holder)
                    probe.rowTransition(
                        adapter = adapter,
                        position = positionOf(holder),
                        binding = binding?.javaClass?.name,
                        instrumental = if (binding == null) null
                        else roles.instrumentalBinding?.isInstance(binding),
                        scaleX = view?.scaleX ?: Float.NaN,
                        scaleY = view?.scaleY ?: Float.NaN,
                        alpha = view?.alpha ?: Float.NaN,
                    )
                }
            }
        }

        // ---- reads -------------------------------------------------------------------------

        private fun fragmentOfCallback(callback: Any?): Any? =
            callback?.let { runCatching { roles.lineChangeFragment?.get(it) }.getOrNull() }

        private fun fragmentOfAnimator(animator: Any?): Any? =
            animator?.let { runCatching { roles.animatorFragment?.get(it) }.getOrNull() }

        /** True when this animator is the lyrics one (the only constructor of that subclass). */
        private fun isLyricsAnimator(animator: Any): Boolean {
            val owner = roles.animatorFragment?.declaringClass ?: return true
            return runCatching { owner.isInstance(animator) }.getOrDefault(true)
        }

        private fun layoutManagerOf(fragment: Any?): Any? =
            fragment?.let { runCatching { roles.layoutManager?.get(it) }.getOrNull() }

        private fun visibleRange(fragment: Any?): Pair<Int?, Int?> {
            val manager = layoutManagerOf(fragment) ?: return null to null
            return intOf(manager, roles.firstVisible) to intOf(manager, roles.lastVisible)
        }

        private fun stateOf(fragment: Any?): LyricLineProbeState {
            if (fragment == null) return LyricLineProbeState()
            return LyricLineProbeState(
                queue = queueOf(fragment),
                scrolled = flagOf(fragment, roles.scrollOwner),
                deferred = flagOf(fragment, roles.deferredDrain),
                anchored = flagOf(fragment, roles.anchored),
            )
        }

        private fun queueOf(fragment: Any): Int =
            runCatching { (roles.deferredQueue?.get(fragment) as? Collection<*>)?.size }
                .getOrNull() ?: LyricLineProbeState.UNKNOWN

        private fun flagOf(target: Any, field: java.lang.reflect.Field?): Int {
            if (field == null) return LyricLineProbeState.UNKNOWN
            return runCatching { if (field.getBoolean(target)) 1 else 0 }
                .getOrDefault(LyricLineProbeState.UNKNOWN)
        }

        private fun intOf(target: Any, method: java.lang.reflect.Method?): Int? {
            if (method == null) return null
            return runCatching { method.invoke(target) as? Int }.getOrNull()
        }

        private fun positionOf(holder: Any?): Int? =
            holder?.let { intOf(it, roles.holderPosition) }

        private fun bindingOf(holder: Any?): Any? =
            holder?.let { runCatching { roles.holderBinding?.get(it) }.getOrNull() }

        private fun itemViewOf(holder: Any?): View? =
            holder?.let { runCatching { roles.holderItemView?.get(it) as? View }.getOrNull() }

        /** A probe must never take a line change down with it; the first failure is reported once. */
        private fun guarded(block: () -> Unit) {
            runCatching(block).onFailure { error ->
                if (reported.compareAndSet(false, true)) {
                    runCatching { ModernXposedRuntime.log(LyricLineProbe.TAG + " hook failed", error) }
                }
            }
        }

        private companion object {
            const val KEY_DX = "probe.dx"
            const val KEY_DY = "probe.dy"
            const val KEY_FROM_Y = "probe.fromY"
            const val KEY_TO_Y = "probe.toY"
            const val KEY_TRANSLATION_Y = "probe.translationY"
            const val KEY_QUEUE_BEFORE = "probe.queueBefore"
            const val KEY_FLUSH_AT = "probe.flushAt"
        }
    }
}
