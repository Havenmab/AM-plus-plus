package dev.amenhancer.module.hook

import android.os.SystemClock
import android.util.Log
import android.view.Choreographer
import android.view.View
import dev.amenhancer.host.applemusic.AppleMusicHostProfile
import dev.amenhancer.module.lyrics.LyricLineSpringPolicy
import dev.amenhancer.module.lyrics.LyricLineSpringPush
import dev.amenhancer.module.lyrics.LyricLineSpringTempo
import dev.amenhancer.module.lyrics.LyricLineSpringWave
import dev.amenhancer.module.model.LyricLineSpringFeel
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
 * **Two feels** (`lyricLineSpringFeel`, read live):
 *
 *  * `AMLL` (the default) is AMLL's own spring: a fixed 0.45 s settle with k≈197, c≈31, m=0.9 and
 *    AMLL's ζ≥1 curve. It ignores `$k.e` for tempo, so 「歌词换行节奏」 has no effect in this mode —
 *    that is the point of the mode.
 *  * `HOST` is 乙-1 verbatim: `T = $k.e` at the moment `animateMove` is called, so the rhythm
 *    slider's multiplier flows through unchanged (it rewrites that very field; applying it again
 *    here would square it) and the block moves in Apple Music's tempo, t99 = 1.31·T.
 *
 * With the spring off the slider goes back to changing the host's own duration, exactly as before.
 *
 * **Every refusal is a hand-back.** A missing role, a move with a horizontal component, a
 * displacement beyond ±1200 px (seek, track change), an unusable duration, or a thread without a
 * Choreographer all leave the host's own animation untouched; the hook only sets `false` after
 * every one of those checks has passed.
 *
 * **The interlude dots ride along.** A wave that lands the next line on the anchor while the
 * previous row's three-dot interlude indicator is still on screen is a visible overlap, and 1626
 * never ends that indicator by itself (see [AppleMusicLyricInterludeDotsHook]). The same `animateMove`
 * hook therefore also retires the dots of any interlude row that is now behind the current line, and
 * arms the one that is the current line. It has its own profile section, its own `AMPP-Interlude`
 * log tag, and its own optional install: if any of that is missing, the spring keeps working and the
 * dots keep behaving exactly as the host wrote them.
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
        // Optional and independent of the spring's own all-or-nothing contract: a build without the
        // interlude roles still gets the wave, just with the host's own dot behaviour.
        val interludeEnabled: () -> Boolean = { LyricLineSpringPolicy.appliedFeel(settings()) != null }
        val interlude = AppleMusicLyricInterludeDotsHook.open(profile, loader, interludeEnabled)
        val session = SpringSession(roles, settings, diagnostics, interlude)
        val scope = HookRegistrationScope()
        return try {
            session.install(scope)
            scope.activate()
            registration = scope
            TargetCapabilityInstall.Active(
                "Lyric line-change spring installed; " + roles.resolved().size + " roles" +
                    (if (interlude != null) ", interlude dots on" else "") + "; " + profile.id,
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
        private val interlude: AppleMusicLyricInterludeDotsHook?,
    ) {
        private val tracks = LyricLineSpringTracks(
            writeOffset = { row, offset -> row.view.translationY = offset },
            dispatchFinished = { row -> notifyFinished(row) },
            layoutShift = { row, delta, lag -> diagnostics.layoutShift(row.holder, delta, lag) },
        )
        private var lastFrameNanos = 0L
        private var frameScheduled = false

        private val frameCallback = Choreographer.FrameCallback { frameTimeNanos -> onFrame(frameTimeNanos) }

        fun install(scope: HookRegistrationScope) {
            register(requireNotNull(roles.animateMove) { "animateMove" }, moveHook(), scope)
            register(requireNotNull(roles.isRunning) { "isRunning" }, runningHook(), scope)
            register(requireNotNull(roles.endAnimation) { "endAnimation" }, endAnimationHook(), scope)
            register(requireNotNull(roles.endAnimations) { "endAnimations" }, endAnimationsHook(), scope)
            // The dots are a nicety: their hooks failing to register must not cost the wave.
            interlude?.let { hook ->
                runCatching { hook.install(scope) }.onFailure { diagnostics.failed("interlude", it) }
            }
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
                val animator = param.thisObject
                if (animator != null && interlude != null && isLyricsAnimator(animator)) {
                    // Every visible row of a line change passes through here, so this is the
                    // earliest complete view of "which interlude row is now behind the current
                    // line". It runs before the takeover below so the dots are on their way out
                    // before any row starts moving.
                    interlude.onMove(param.args.getOrNull(0))
                }
                guarded("animateMove") { takeOver(param) }
            }
        }

        private fun takeOver(param: ModernMethodHook.MethodHookParam) {
            val animator = param.thisObject ?: return
            val feel = feelOrNull() ?: return
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
            val tempo = LyricLineSpringPolicy.tempoFor(feel, duration) ?: run {
                diagnostics.refused(LyricLineSpringPolicy.durationVerdict(duration))
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
                tracks.push(row, -dy.toFloat(), tempo)
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
                diagnostics.takeOver(dy, duration, tempo, tracks.activeWaves())
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
         * The feel to use right now, or null when this feature must do nothing.
         *
         * Read live, exactly as 「歌词换行节奏」 reads its own switches: both the two switches and the
         * mode take effect on the next line change, not on the ones already in flight.
         */
        private fun feelOrNull(): LyricLineSpringFeel? = runCatching {
            LyricLineSpringPolicy.appliedFeel(settings())
        }.getOrNull()

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
    private var retargets = 0
    private var shifts = 0

    /**
     * A row was taken over: the feel, the spring it produced, and how many waves are live.
     *
     * `T` is the host's own `$k.e` — the tempo in `HOST` mode, and in `AMLL` mode only the proof
     * that this really is the lyric animator's field (the spring then ignores it).
     */
    fun takeOver(dy: Int, durationMs: Long, tempo: LyricLineSpringTempo, waves: Int) {
        val key = dy.toString() + "/" + durationMs + "/" + tempo.feel.storageValue
        val now = now()
        if (key == lastTakeOverKey && now - lastTakeOverAt < REPEAT_WINDOW_MS) return
        lastTakeOverKey = key
        lastTakeOverAt = now
        emit(
            "takeover dy=" + dy + " T=" + durationMs + "ms feel=" + tempo.feel.storageValue +
                " t=" + fixed(tempo.tempoMs) + "ms k=" + fixed(tempo.stiffness) +
                " c=" + fixed(tempo.damping) + " waves=" + waves,
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
