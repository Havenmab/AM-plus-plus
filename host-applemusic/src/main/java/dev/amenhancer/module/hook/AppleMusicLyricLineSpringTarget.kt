package dev.amenhancer.module.hook

import android.os.SystemClock
import android.util.Log
import android.view.Choreographer
import android.view.View
import dev.amenhancer.host.applemusic.AppleMusicHostProfile
import dev.amenhancer.module.lyrics.LyricLineSpringPolicy
import dev.amenhancer.module.model.ModuleSettings

/**
 * 「换行弹簧」: take the lyric block's line-change displacement over with one shared spring.
 *
 * **What the host does (1626, measured).** One line change is 4–8 `animateMove` calls that all
 * carry the *same* `dy`; for a non-zero `dy`, `mc.y#m` writes `translationY = -dy`, queues the row
 * in its move list and returns `true` so a `ViewPropertyAnimator` runs for `$k.e` milliseconds.
 * For `dy == 0` it instead finishes the row and defers the row transition through `V0`/`s2()`.
 *
 * **What this takes.** Only the non-zero branch: the hook answers `false` (so the host's animator
 * never runs), writes the same `translationY = -dy`, and advances one scalar spring per batch from
 * the shared Choreographer clock. The zero branch, the deferred queue, the re-anchor and the
 * duration field are all left exactly as Apple Music wrote them.
 *
 * **What it therefore owes.** Skipping the host's animation also skips the two notifications that
 * animation's listener emits — `dispatchMoveStarting(holder)` when it starts and
 * `dispatchAnimationFinished(holder)` when it ends. RecyclerView's pending-move ledger accepts
 * exactly one finish per start, so the finish is emitted from a per-row flag shared by every end
 * path (spring at rest, `endAnimation`, `endAnimations`), and `isRunning()` is answered `true`
 * while any batch of that animator is live so the list is not considered idle mid-flight.
 *
 * **Tempo, and how 「歌词换行节奏」 survives.** `T` is `$k.e` read at the moment `animateMove` is
 * called. The host's line callback writes that field and the rhythm slider rewrites it in place, so
 * with both switches on the spring already inherits the user's multiplier — applying it again here
 * would square it (the probe run that this work is based on was captured at 200%, which is exactly
 * how a doubled field looks). Priority is therefore: the spring owns the displacement, the slider
 * only supplies the tempo; with the spring off, the slider goes back to changing the host's own
 * duration, exactly as before.
 *
 * **Every refusal is a hand-back.** A missing role, a move with a horizontal component, a
 * displacement beyond ±1200 px (seek, track change), an unusable duration, or a thread without a
 * Choreographer all leave the host's own animation untouched; the hook only sets `false` after
 * every one of those checks has passed.
 */
internal class AppleMusicLyricLineSpringTarget(
    private val profile: AppleMusicHostProfile,
    private val loader: ClassLoader,
    private val settings: () -> ModuleSettings,
) : LyricLineSpringTarget {
    private val diagnostics = LyricLineSpringDiagnostics()
    private var registration: HookRegistrationScope? = null

    @Synchronized
    override fun install(): TargetCapabilityInstall {
        registration?.takeIf { it.isActive }?.let {
            return TargetCapabilityInstall.Active("Lyric line-change spring already active; " + profile.id)
        }
        val section = runCatching {
            profile.document.getJSONObject("lyricLineMotion").getJSONObject("spring")
        }.getOrNull() ?: return TargetCapabilityInstall.Unsupported(
            "Lyric line-change spring has no exact contract; " + profile.id,
        )
        if (!profile.productionEnabled) {
            return TargetCapabilityInstall.Unsupported(
                "Lyric line-change spring needs an exact verified profile; " + profile.id,
            )
        }
        val roles = LyricLineSpringRoles(loader, section)
        if (!roles.isComplete()) {
            // Half of this contract is worse than none of it: without the finish role the first
            // taken-over move would leave RecyclerView waiting forever.
            val reason = roles.missing().joinToString("; ")
            diagnostics.unavailable("contract incomplete: " + reason)
            return TargetCapabilityInstall.Degraded(
                "Lyric line-change spring not installed; " + profile.id + ": " + reason,
            )
        }
        val session = SpringSession(roles, settings, diagnostics)
        val scope = HookRegistrationScope()
        return try {
            session.install(scope)
            scope.activate()
            registration = scope
            TargetCapabilityInstall.Active(
                "Lyric line-change spring installed; " + roles.resolved().size + " roles; " + profile.id,
            )
        } catch (error: Throwable) {
            scope.close()
            registration = null
            val reason = error.message ?: error.javaClass.simpleName
            diagnostics.unavailable("install failed: " + reason)
            TargetCapabilityInstall.Degraded(
                "Lyric line-change spring install failed; " + profile.id + ": " + reason,
            )
        }
    }

    /** One installed spring takeover: the hooks, the batches, and the one shared frame clock. */
    private class SpringSession(
        private val roles: LyricLineSpringRoles,
        private val settings: () -> ModuleSettings,
        private val diagnostics: LyricLineSpringDiagnostics,
    ) {
        private val tracks = LyricLineSpringTracks(
            writeOffset = { row, offset -> row.view.translationY = offset },
            dispatchFinished = { row -> notifyFinished(row) },
        )
        private var lastFrameNanos = 0L
        private var frameScheduled = false

        private val frameCallback = Choreographer.FrameCallback { frameTimeNanos -> onFrame(frameTimeNanos) }

        fun install(scope: HookRegistrationScope) {
            register(requireNotNull(roles.animateMove) { "animateMove" }, moveHook(), scope)
            register(requireNotNull(roles.isRunning) { "isRunning" }, runningHook(), scope)
            register(requireNotNull(roles.endAnimation) { "endAnimation" }, endAnimationHook(), scope)
            register(requireNotNull(roles.endAnimations) { "endAnimations" }, endAnimationsHook(), scope)
        }

        private fun register(
            member: java.lang.reflect.Method,
            hook: ModernMethodHook,
            scope: HookRegistrationScope,
        ) {
            check(ModernXposedRuntime.hookMethod(member, hook, scope)) {
                "hook registration refused: " + member
            }
        }

        // ---- the takeover -------------------------------------------------------------------

        /**
         * mc.y#m, before.
         *
         * The deltas are recomputed exactly as the host does (its own `fromY` already has the
         * current translation folded in), and every check runs *before* the return value is
         * touched, so a refusal can never leave the row without an animation.
         */
        private fun moveHook() = object : ModernMethodHook() {
            override fun beforeHookedMethod(param: ModernMethodHook.MethodHookParam) {
                guarded("animateMove") { takeOver(param) }
            }
        }

        private fun takeOver(param: ModernMethodHook.MethodHookParam) {
            val animator = param.thisObject ?: return
            if (!switchesAllow()) return
            if (!isLyricsAnimator(animator)) return
            val holder = param.args.getOrNull(0) ?: return
            val view = itemViewOf(holder) ?: return
            val fromX = param.args.getOrNull(1) as? Int ?: return
            val fromY = param.args.getOrNull(2) as? Int ?: return
            val toX = param.args.getOrNull(3) as? Int ?: return
            val toY = param.args.getOrNull(4) as? Int ?: return
            val dx = toX - (fromX + view.translationX.toInt())
            val dy = toY - (fromY + view.translationY.toInt())
            // dy == 0 is the host's own zero-displacement branch: it finishes the row immediately
            // and queues the row transition in V0. That semantics is the host's; do not touch it.
            if (dy == 0) return
            // We only drive translationY. A horizontal component would be silently lost.
            if (dx != 0) {
                diagnostics.refused("dx=" + dx + " has a horizontal component")
                return
            }
            if (!LyricLineSpringPolicy.acceptsDisplacement(dy)) {
                diagnostics.refused("dy=" + dy + " beyond ±" + LyricLineSpringPolicy.MAX_DISPLACEMENT_PX + "px")
                return
            }
            val duration = durationOf(animator)
            if (!LyricLineSpringPolicy.isPlausibleDuration(duration)) {
                diagnostics.refused(LyricLineSpringPolicy.durationVerdict(duration))
                return
            }
            // A re-anchor mid-flight: the row keeps its velocity, so the motion has no kink.
            val carried = tracks.velocityOf(holder)
            val spring = LyricLineSpringPolicy.springFor(duration, -dy.toFloat(), carried) ?: run {
                diagnostics.refused("spring parameters rejected (dy=" + dy + " T=" + duration + "ms)")
                return
            }
            if (!ensureClock()) {
                diagnostics.refused("no Choreographer on this thread")
                return
            }
            // Past this point the host's own animation is skipped, so this row owes exactly one
            // started and exactly one finished notification.
            param.result = false
            val row = LyricLineSpringRow(animator, holder, view)
            try {
                tracks.join(row, dy, spring)
            } catch (error: Throwable) {
                // Nothing was dispatched for this row yet: give it back at rest, exactly once.
                recover(row)
                throw error
            }
            notifyStarted(row)
            diagnostics.takeOver(dy, duration, spring.stiffness, spring.damping, tracks.activeBatches())
        }

        /** mc.y#g, after: keep RecyclerView from concluding the list is idle mid-spring. */
        private fun runningHook() = object : ModernMethodHook() {
            override fun afterHookedMethod(param: ModernMethodHook.MethodHookParam) {
                guarded("isRunning") {
                    if (param.throwable != null) return@guarded
                    val animator = param.thisObject ?: return@guarded
                    if (tracks.hasActive(animator)) param.result = true
                }
            }
        }

        /** mc.y#e, after: recycle / reuse / cancel must leave no offset behind, and finish once. */
        private fun endAnimationHook() = object : ModernMethodHook() {
            override fun afterHookedMethod(param: ModernMethodHook.MethodHookParam) {
                guarded("endAnimation") {
                    val animator = param.thisObject ?: return@guarded
                    val holder = param.args.getOrNull(0) ?: return@guarded
                    if (tracks.finish(animator, holder)) diagnostics.cleaned("endAnimation")
                }
            }
        }

        /** mc.y#f, after: a full reset (track change, list replacement) ends every batch. */
        private fun endAnimationsHook() = object : ModernMethodHook() {
            override fun afterHookedMethod(param: ModernMethodHook.MethodHookParam) {
                guarded("endAnimations") {
                    val animator = param.thisObject ?: return@guarded
                    val rows = tracks.finishAll(animator)
                    if (rows > 0) diagnostics.cleaned("endAnimations rows=" + rows)
                }
            }
        }

        // ---- the shared clock ---------------------------------------------------------------

        /** Start the frame clock if it is not already running; false when this thread has none. */
        private fun ensureClock(): Boolean {
            if (frameScheduled) return true
            val posted = runCatching { Choreographer.getInstance().postFrameCallback(frameCallback) }
                .isSuccess
            if (posted) frameScheduled = true
            return posted
        }

        private fun onFrame(frameTimeNanos: Long) {
            frameScheduled = false
            val stillRunning = runCatching { advance(frameTimeNanos) }.getOrElse { error ->
                diagnostics.failed("frame", error)
                runCatching { tracks.finishAll(null) }
                false
            }
            if (!stillRunning) lastFrameNanos = 0L
        }

        private fun advance(frameTimeNanos: Long): Boolean {
            val previous = lastFrameNanos
            lastFrameNanos = frameTimeNanos
            val step = if (previous == 0L) 0f else (frameTimeNanos - previous) / 1_000_000f
            if (!tracks.advance(step)) return false
            if (ensureClock()) return true
            // The clock died under a live batch; put the rows back at rest rather than leave them
            // hanging half a line away from their layout position.
            tracks.finishAll(null)
            return false
        }

        // ---- host reads and calls ------------------------------------------------------------

        /** Both switches are read live, exactly as 「歌词换行节奏」 reads its own. */
        private fun switchesAllow(): Boolean = runCatching {
            val current = settings()
            current.lyricLineSpringEnabled && current.lyricEffectsOptimizationEnabled
        }.getOrDefault(false)

        /** Only the lyric list's animator; every other RecyclerView keeps its own animations. */
        private fun isLyricsAnimator(animator: Any): Boolean {
            val owner = runCatching { roles.animatorFragment?.declaringClass }.getOrNull() ?: return true
            return runCatching { owner.isInstance(animator) }.getOrDefault(true)
        }

        private fun itemViewOf(holder: Any): View? =
            runCatching { roles.holderItemView?.get(holder) as? View }.getOrNull()

        private fun durationOf(animator: Any): Long =
            runCatching { roles.duration?.getLong(animator) }.getOrNull() ?: -1L

        /** What the host's own move animation does in its listener's onAnimationStart. */
        private fun notifyStarted(row: LyricLineSpringRow) {
            runCatching { roles.dispatchMoveStarting?.invoke(row.animator, row.holder) }
                .onFailure { diagnostics.failed("dispatchMoveStarting", it) }
        }

        /** What the host's own move animation does in its listener's onAnimationEnd. */
        private fun notifyFinished(row: LyricLineSpringRow) {
            runCatching { roles.dispatchAnimationFinished?.invoke(row.animator, row.holder) }
                .onFailure { diagnostics.failed("dispatchAnimationFinished", it) }
        }

        private fun recover(row: LyricLineSpringRow) {
            runCatching { tracks.forget(row.holder) }
            runCatching { row.view.translationY = 0f }
            notifyFinished(row)
        }

        /** A hook must never take the lyric list down with it; the first failure is reported once. */
        private fun guarded(where: String, block: () -> Unit) {
            runCatching(block).onFailure { diagnostics.failed(where, it) }
        }
    }
}

/**
 * Bounded, text-only observability for 「换行弹簧」.
 *
 * `adb logcat -s AMPP-LineSpring` answers the three questions a device run asks: did a move get
 * taken over (and with what tempo), did something get handed back and why, and did a reset path
 * clean up. Repeats are rate-limited and the session stops after [MAX_LINES], so a long song cannot
 * flood logcat.
 */
internal class LyricLineSpringDiagnostics(
    private val clock: () -> Long = { SystemClock.uptimeMillis() },
) {
    private val reported = HashMap<String, Long>()
    private var lastTakeOverAt = Long.MIN_VALUE
    private var lastTakeOverKey = ""
    private var lastCleanupAt = Long.MIN_VALUE
    private var emitted = 0

    /** A move was taken over: the tempo, the stiffness it produced, and the batch count. */
    fun takeOver(dy: Int, durationMs: Long, stiffness: Float, damping: Float, batches: Int) {
        val key = dy.toString() + "/" + durationMs
        val now = now()
        if (key == lastTakeOverKey && now - lastTakeOverAt < REPEAT_WINDOW_MS) return
        lastTakeOverKey = key
        lastTakeOverAt = now
        emit(
            "takeover dy=" + dy + " T=" + durationMs + "ms k=" + fixed(stiffness) +
                " c=" + fixed(damping) + " batches=" + batches,
        )
    }

    /** Something was handed back to the host, with the reason; each reason is printed once. */
    fun refused(reason: String) = once("refused:" + reason, "handed back: " + reason)

    /** A reset path zeroed rows. */
    fun cleaned(where: String) {
        val now = now()
        if (now - lastCleanupAt < REPEAT_WINDOW_MS) return
        lastCleanupAt = now
        emit("reset " + where)
    }

    /** The contract or the install is unusable; one line, ever. */
    fun unavailable(reason: String) = once("unavailable", "line spring unavailable: " + reason)

    /** A hook or a frame failed; one line per call site, ever. */
    fun failed(where: String, error: Throwable) =
        once("failed:" + where, "line spring " + where + " failed: " + (error.message ?: error.javaClass.simpleName))

    private fun once(key: String, line: String) {
        synchronized(reported) {
            if (reported.containsKey(key)) return
            reported[key] = now()
        }
        emit(line)
    }

    private fun now(): Long = runCatching(clock).getOrDefault(0L)

    private fun emit(line: String) {
        synchronized(reported) {
            if (emitted >= MAX_LINES) return
            emitted += 1
        }
        runCatching { Log.i(TAG, line) }
        runCatching { ModernXposedRuntime.log(TAG + " " + line) }
    }

    private fun fixed(value: Float): String = String.format(java.util.Locale.US, "%.1f", value)

    private companion object {
        const val TAG = "AMPP-LineSpring"
        const val MAX_LINES = 60
        const val REPEAT_WINDOW_MS = 500L
    }
}
