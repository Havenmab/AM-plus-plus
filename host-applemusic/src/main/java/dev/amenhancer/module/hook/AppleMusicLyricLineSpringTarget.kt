package dev.amenhancer.module.hook

import android.os.SystemClock
import android.util.Log
import android.view.Choreographer
import android.view.View
import dev.amenhancer.host.applemusic.AppleMusicHostProfile
import dev.amenhancer.module.lyrics.LyricInterludeHold
import dev.amenhancer.module.lyrics.LyricLineSpringPolicy
import dev.amenhancer.module.lyrics.LyricLineSpringPush
import dev.amenhancer.module.lyrics.LyricLineSpringTempo
import dev.amenhancer.module.lyrics.LyricLineSpringWave
import dev.amenhancer.module.model.ModuleSettings

/**
 * 「换行弹簧」: take the lyric block's line-change displacement over with one spring per row.
 *
 * **What the host does (1626, measured).** One line change is 4–8 `animateMove` calls that all
 * carry the *same* `dy`; for a non-zero `dy`, `mc.y#m` writes `translationY = -dy`, queues the row
 * in its move list and returns `true` so a `ViewPropertyAnimator` runs for `$k.e` milliseconds.
 * For `dy == 0` it instead finishes the row and defers the row transition through `V0`/`s2()`.
 *
 * **What this takes.** Only the non-zero branch: the hook answers `false` (so the host's animator
 * never runs), writes the same `translationY = -dy`, and advances a *wave* of per-row springs from
 * the shared Choreographer clock ([LyricLineSpringWave]). Rows start 50 ms apart in AMLL's
 * staggered order — the one below the top leaves next, and past the focus line the spacing tightens
 * by 5 % per row — so a line change reads as rows chasing each other rather than one rectangle
 * sliding. A row already travelling when the next line change lands keeps its velocity. The zero
 * branch, the deferred queue, the re-anchor and the duration field are all left exactly as Apple
 * Music wrote them.
 *
 * **What it therefore owes.** Skipping the host's animation also skips the two notifications that
 * animation's listener emits — `dispatchMoveStarting(holder)` when it starts and
 * `dispatchAnimationFinished(holder)` when it ends. RecyclerView's pending-move ledger accepts
 * exactly one finish per start, so the finish is emitted from a per-row flag shared by every end
 * path (spring at rest, `endAnimation`, `endAnimations`, a re-target retiring the previous move),
 * and `isRunning()` is answered `true` while any wave of that animator is live so the list is not
 * considered idle mid-flight.
 *
 * **One tempo: AMLL's.** The spring is AMLL's own — a fixed 0.45 s settle with k≈197, c≈31, m=0.9
 * and AMLL's ζ≥1 curve — and it never reads the animator's move-duration field (`$k.e`): a line
 * change takes AMLL's time, not the time Apple Music computed for that line. That is the whole
 * point of the feature, and it is why there is no second switch and no slider beside it.
 *
 * **Every refusal is a hand-back.** A missing role, a move with a horizontal component, a
 * displacement beyond ±1200 px (seek, track change), or a thread without a Choreographer all leave
 * the host's own animation untouched; the hook only sets `false` after every one of those checks
 * has passed.
 *
 * **The interlude's three-dot exit comes first.** When an interlude ends, Apple Music plays one more
 * animation on the dots — they grow, shrink and fade out — and only then is the interlude over
 * (`V0#N`/`A#N`, 750 ms + 250 ms). That animation is started from RecyclerView's *change* pass, one
 * frame after this hook was asked to move the rows, so a wave that starts immediately is a third of
 * the way home while the dots are still growing. [AppleMusicLyricInterludeExitHook] reports that
 * moment, and this session parks every row of the batch at its start position until the exit's own
 * `onAnimationEnd` (or, when that callback is unavailable, its 750 + 250 ms book duration) — the
 * AMLL stagger only starts ticking after that. The dots are never touched: their scale, alpha and
 * tint stay the host's property, and a build without the interlude roles simply never waits.
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
        val session = SpringSession(roles, settings, diagnostics, profile, loader)
        val scope = HookRegistrationScope()
        return try {
            session.install(scope)
            scope.activate()
            registration = scope
            TargetCapabilityInstall.Active(
                "Lyric line-change spring installed; " + roles.resolved().size + " roles" +
                    (if (session.holdsForInterlude()) ", interlude exit aligned" else "") + "; " + profile.id,
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
        private val profile: AppleMusicHostProfile,
        private val loader: ClassLoader,
    ) {
        private val tracks = LyricLineSpringTracks(
            writeOffset = { row, offset -> row.view.translationY = offset },
            dispatchFinished = { row -> notifyFinished(row) },
            layoutShift = { row, delta, lag -> diagnostics.layoutShift(row.holder, delta, lag) },
        )
        private var lastFrameNanos = 0L
        private var frameScheduled = false

        /**
         * How long the interlude's exit animation still owns the start of a line change.
         *
         * Armed by [AppleMusicLyricInterludeExitHook]; while it is armed every pushed row waits at
         * its start position and the AMLL stagger does not tick (see `LyricInterludeHold`).
         */
        private val exitHold = LyricInterludeHold()
        private var interludeHook: AppleMusicLyricInterludeExitHook? = null

        private val interludeListener = object : AppleMusicLyricInterludeExitHook.Listener {
            override fun onInterludeExitStarted() {
                // The book duration is the cap; the exit's own end callback (when it resolves)
                // releases the rows sooner.
                exitHold.start(now())
                tracks.hold(exitHold.remainingMs(now()).toFloat())
            }

            override fun onInterludeExitFinished() {
                exitHold.finish(now())
                tracks.release()
            }
        }

        private val frameCallback = Choreographer.FrameCallback { frameTimeNanos -> onFrame(frameTimeNanos) }

        /** True when the interlude exit is being aligned on this build. */
        fun holdsForInterlude(): Boolean = interludeHook != null

        fun install(scope: HookRegistrationScope) {
            register(requireNotNull(roles.animateMove) { "animateMove" }, moveHook(), scope)
            register(requireNotNull(roles.isRunning) { "isRunning" }, runningHook(), scope)
            register(requireNotNull(roles.endAnimation) { "endAnimation" }, endAnimationHook(), scope)
            register(requireNotNull(roles.endAnimations) { "endAnimations" }, endAnimationsHook(), scope)
            // Optional: the interlude exit only shifts *when* rows start, so a build without its
            // roles keeps the wave and simply does not wait.
            interludeHook = runCatching {
                AppleMusicLyricInterludeExitHook.open(profile, loader, interludeListener, ::now)
            }.getOrNull()
            interludeHook?.let { hook ->
                runCatching { hook.install(scope) }.onFailure { diagnostics.failed("interlude", it) }
            }
        }

        private fun now(): Long = runCatching { SystemClock.uptimeMillis() }.getOrDefault(0L)

        /** The wait a row pushed right now owes the interlude's exit animation, in ms. */
        private fun pendingHoldMs(): Float =
            runCatching { exitHold.remainingMs(now()).toFloat() }.getOrDefault(0f)

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
            if (!isEnabled()) return
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
            if (!ensureClock()) {
                diagnostics.refused("no Choreographer on this thread")
                return
            }
            // Past this point the host's own animation is skipped, so this row owes exactly one
            // started and exactly one finished notification.
            param.result = false
            // The host's dy already folds in this row's current translation, so -dy *is* the new
            // lag: writing it here keeps the displayed position continuous. A row that is still
            // travelling gets its end point corrected, and keeps its velocity only while that
            // velocity still points at the new end point (see LyricLineSpringPush).
            val row = LyricLineSpringRow(animator, holder, view, toY)
            val applied = try {
                // A row pushed during an interlude exit is written at its new translation (so the
                // layout jump is invisible) and then parked there until the dots have finished
                // growing, shrinking and fading — the stagger only starts after that.
                tracks.push(row, -dy.toFloat(), LyricLineSpringPolicy.tempo, pendingHoldMs())
            } catch (error: Throwable) {
                // Nothing was dispatched for this row yet: give it back at rest, exactly once.
                recover(row)
                throw error
            }
            notifyStarted(row)
            // A repeat move for a row that is already travelling is a *target correction*: the
            // layout moved by applied.layoutDeltaPx under an unchanged visual position, and the
            // row's velocity is kept only while it still aims home. Printing both lets the device
            // log answer "correction or impulse" without pasting a whole per-frame trajectory.
            if (applied.moving) {
                diagnostics.retargeted(applied)
            } else {
                diagnostics.takeOver(dy, LyricLineSpringPolicy.tempo, tracks.activeWaves(), applied.heldMs.toInt())
            }
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

        /**
         * True when this feature must take the next line change over.
         *
         * Read live: both switches take effect on the next line change, not on the ones already in
         * flight. A throwing settings read is a refusal, never a crash in the lyric list.
         */
        private fun isEnabled(): Boolean = runCatching {
            LyricLineSpringPolicy.isEnabled(settings())
        }.getOrDefault(false)

        /** Only the lyric list's animator; every other RecyclerView keeps its own animations. */
        private fun isLyricsAnimator(animator: Any): Boolean {
            val owner = runCatching { roles.animatorFragment?.declaringClass }.getOrNull() ?: return true
            return runCatching { owner.isInstance(animator) }.getOrDefault(true)
        }

        private fun itemViewOf(holder: Any): View? =
            runCatching { roles.holderItemView?.get(holder) as? View }.getOrNull()

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
    private var retargets = 0
    private var shifts = 0

    /**
     * A row was taken over: the spring it resolved to, and how many waves are live.
     *
     * The tempo is the constant AMLL one, so it is printed once per distinct displacement rather
     * than per line: `t` is the settle time the spring aims at, `k`/`c` its constants.
     */
    fun takeOver(dy: Int, tempo: LyricLineSpringTempo, waves: Int, heldMs: Int = 0) {
        val key = dy.toString() + "/" + heldMs
        val now = now()
        if (key == lastTakeOverKey && now - lastTakeOverAt < REPEAT_WINDOW_MS) return
        lastTakeOverKey = key
        lastTakeOverAt = now
        emit(
            "takeover dy=" + dy + " t=" + fixed(tempo.tempoMs) + "ms k=" + fixed(tempo.stiffness) +
                " c=" + fixed(tempo.damping) + " waves=" + waves +
                (if (heldMs > 0) " held=" + heldMs + "ms" else ""),
        )
    }

    /**
     * A row that was already travelling got its end point corrected instead of a second impulse.
     *
     * `layoutDelta` is how far that row's layout moved under it — the background line's own height
     * animation, a second re-anchor, a decoration pass. The host's `dy` already folded the row's
     * translation in, so the visual position does not move: the *end point* does, by this amount.
     * `v` is the velocity offered to the new spring and `kept` the one it started with; `kept=0`
     * on a drop means the correction put the end point on the other side of the row and the old
     * velocity would have overshot it, which is exactly the "pushed down, then bounced" look.
     */
    fun retargeted(applied: LyricLineSpringPush) {
        val key = fixed(applied.lagPx) + "/" + fixed(applied.layoutDeltaPx) + "/" + fixed(applied.keptVelocity)
        val now = now()
        synchronized(reported) {
            val last = reported[key]
            if (last != null && now - last < RETARGET_WINDOW_MS) return
            if (retargets >= MAX_RETARGET_LINES) return
            reported[key] = now
            retargets += 1
        }
        emit(
            "retarget lag=" + fixed(applied.lagPx) + " layoutDelta=" + fixed(applied.layoutDeltaPx) +
                " v=" + fixed(applied.offeredVelocity) + " kept=" + fixed(applied.keptVelocity) +
                (if (applied.velocityDropped) " dropped=1" else "") +
                (if (applied.waiting) " waiting=1" else ""),
        )
    }

    /**
     * One frame of a layout change that announced nothing — the background line growing or
     * collapsing.
     *
     * `delta` is how far that row's layout moved in this frame and `residual` the translation the
     * wave left on it afterwards (the visual position did not move). A device run reads this as:
     * `delta=+4` repeated a dozen times is one background line growing; a single huge `delta` is a
     * seek or a recycled row, which the wave refuses to absorb. Rate limited per holder, so a long
     * song cannot flood logcat.
     */
    fun layoutShift(holder: Any, deltaPx: Int, lagPx: Float) {
        val key = "shift:" + System.identityHashCode(holder)
        val now = now()
        synchronized(reported) {
            val last = reported[key]
            if (last != null && now - last < SHIFT_WINDOW_MS) return
            if (shifts >= MAX_SHIFT_LINES) return
            reported[key] = now
            shifts += 1
        }
        emit("layout-shift delta=" + deltaPx + " residual=" + fixed(lagPx))
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

        /** Every line is bounded; retarget lines get their own budget on top of this one. */
        const val MAX_LINES = 240

        /** How many "the end point moved" lines one session may print, so a song cannot flood. */
        const val MAX_RETARGET_LINES = 160

        const val REPEAT_WINDOW_MS = 500L

        /** A corrected row prints once per distinct key inside this window. */
        const val RETARGET_WINDOW_MS = 300L

        /** One absorbed layout-shift line per holder inside this window. */
        const val SHIFT_WINDOW_MS = 100L

        /** Budget for the layout-shift lines, on top of [MAX_LINES]. */
        const val MAX_SHIFT_LINES = 160
    }
}
