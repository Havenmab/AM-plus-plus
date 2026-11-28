package dev.amenhancer.module.hook

import android.content.Context
import android.os.SystemClock
import android.util.Log
import android.view.Choreographer
import android.view.View
import dev.amenhancer.host.applemusic.AppleMusicHostProfile
import dev.amenhancer.module.lyrics.LyricInterludeHold
import dev.amenhancer.module.lyrics.LyricLineShiftSkip
import dev.amenhancer.module.lyrics.LyricLineSpringPolicy
import dev.amenhancer.module.lyrics.LyricLineSpringPush
import dev.amenhancer.module.lyrics.LyricLineSpringStatus
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
 * sliding. A row already travelling when the next line change lands keeps its velocity and its
 * move: the correction re-aims the end point, so RecyclerView sees one continuous move per row even
 * when a fast song re-offers `animateMove` every 60-120 ms. The zero branch, the deferred queue, the
 * re-anchor and the duration field are all left exactly as Apple Music wrote them.
 *
 * **What it therefore owes.** Skipping the host's animation also skips the notifications that
 * animation emits — `dispatchMoveStarting(holder)` when it starts, `dispatchAnimationFinished(holder)`
 * when it ends, and the lyrics animator's own per-row announcement to its adapter
 * (`PlayerLyricsViewFragment$u#s` → `i1#N`, [LyricLineSpringRoles.rowSettle]). RecyclerView's
 * pending-move ledger accepts exactly one finish per start, so the finish is emitted from a
 * per-row flag shared by every end path (spring at rest,
 * `endAnimation`, `endAnimations`, a re-target retiring the previous move), and `isRunning()` is
 * answered `true` while any wave of that animator is live so the list is not considered idle
 * mid-flight. The row announcement is a *different* obligation and gets a different rule: it is
 * emitted only when the spring really came to rest — after `translationY = 0` and after the finish —
 * and never from a reset, where the row is leaving the window or about to be reused.
 *
 * **One tempo: AMLL's.** The spring is AMLL's own — a fixed 0.45 s settle with k≈197, c≈31, m=0.9
 * and AMLL's ζ≥1 curve — and it never reads the animator's move-duration field (`$k.e`): a line
 * change takes AMLL's time, not the time Apple Music computed for that line. That is the whole
 * point of the feature, and it is why there is no second switch and no slider beside it.
 *
 * **Every refusal is a hand-back.** A missing role, a move with a horizontal component, a layout
 * jump beyond ±1200 px (seek, track change) or a residual beyond three widths of it, or a thread
 * without a Choreographer all leave the host's own animation untouched; the hook only sets `false`
 * after every one of those checks has passed. The jump and the residual are judged separately —
 * see [LyricLineSpringPolicy.acceptsMove] for why a fast song needs the distinction.
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
    /**
     * The host's application context, for the on-device trace file (`/sdcard/Download/AMPP/…`).
     *
     * Null — a target built without one — means the diagnostics stay on logcat, nothing else
     * changes and no file is ever created.
     */
    private val context: Context? = null,
    private val settings: () -> ModuleSettings,
) : LyricLineSpringTarget {
    /**
     * The feature's switches, read live.
     *
     * One answer for both readers: the hooks decide whether a move may be taken over, and the trace
     * file decides whether it may be created or appended. A throwing settings read is "off", never a
     * crash in the lyric list.
     */
    private fun featureEnabled(): Boolean = runCatching {
        LyricLineSpringPolicy.isEnabled(settings())
    }.getOrDefault(false)

    private val diagnostics = LyricLineSpringDiagnostics(sink = context?.let { host -> traceFileSink(host) })

    /** The on-device trace file, opened lazily by the first line that is actually printed. */
    private fun traceFileSink(host: Context): (String) -> Unit {
        val file = LineSpringLogFile(
            context = host,
            packageName = profile.packageName,
            version = profile.versionName + " (" + profile.versionCode + ")",
            enabled = { featureEnabled() },
        )
        return { line -> file.write(line) }
    }

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
        // Optional: the host's own per-row "this row's move is over". A build whose animator
        // subclass no longer declares it keeps the spring and loses only that notification, so the
        // reason is printed once here and the install goes on.
        roles.optionalMissing().firstOrNull()?.let { reason -> diagnostics.rowSettleUnavailable(reason) }
        val session = SpringSession(roles, ::featureEnabled, diagnostics, profile, loader)
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
        /**
         * The feature's live switch, shared with the trace file: the same answer decides whether a
         * move may be taken over and whether the diagnosis may be written at all.
         */
        private val enabled: () -> Boolean,
        private val diagnostics: LyricLineSpringDiagnostics,
        private val profile: AppleMusicHostProfile,
        private val loader: ClassLoader,
    ) {
        private val tracks = LyricLineSpringTracks(
            writeOffset = { row, offset -> row.view.translationY = offset },
            dispatchFinished = { row -> notifyFinished(row) },
            // The host's own half of "this move is over": not RecyclerView's ledger but the
            // lyrics animator telling its adapter that the row has arrived (see notifySettled).
            settled = { row -> notifySettled(row) },
            layoutShift = { row, delta, lag -> diagnostics.layoutShift(row.holder, delta, lag) },
            layoutShiftSkipped = { row, delta, reason ->
                diagnostics.shiftIgnored(delta, reason)
            },
            skipped = { row, moved, lag, window -> diagnostics.skipped(row.holder, moved, lag, window) },
            stuck = { rows, maxLag, since -> diagnostics.stuck(rows, maxLag, since) },
        )
        private var lastFrameNanos = 0L
        private var frameScheduled = false

        /** Choreographer callbacks since the install: the heartbeat's `frame=` counter. */
        private var frames = 0L

        /**
         * How long the interlude's exit animation still owns the start of a line change.
         *
         * Armed by [AppleMusicLyricInterludeExitHook]; while it is armed every pushed row waits at
         * its start position and the AMLL stagger does not tick (see `LyricInterludeHold`).
         */
        private val exitHold = LyricInterludeHold()
        private var interludeHook: AppleMusicLyricInterludeExitHook? = null

        /**
         * 「换行打点」: the host's own "this line is current now" callback.
         *
         * Optional and independent: a build without its role leaves the spring exactly as it was and
         * only costs the `line-change` line of the log.
         */
        private var lineChangeTrace: AppleMusicLyricLineChangeTrace? = null

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
            // Optional, read-only and independent of everything above: the host's own line-change
            // callback, so the log can count the host's line changes against our pushes. It answers
            // nothing, writes nothing and never installs anything else — a build without its role
            // simply logs one reason and keeps the spring.
            lineChangeTrace = runCatching {
                AppleMusicLyricLineChangeTrace.open(
                    profile = profile,
                    loader = loader,
                    onLineChange = { start, end ->
                        // Same switch as the takeovers: with the spring off there is nothing to
                        // pair the line with, so the line is not printed either.
                        if (isEnabled()) diagnostics.lineChange(start, end)
                    },
                    onUnavailable = { reason -> diagnostics.traceUnavailable(reason) },
                )
            }.getOrNull()
            lineChangeTrace?.let { trace ->
                runCatching { trace.install(scope) }
                    .onFailure { diagnostics.failed("lineChange", it) }
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
            // Two different bounds, and the difference is the fast song: a seek moves the *layout*
            // by thousands of pixels, while a fast lyric song moves it by one line and lets the
            // residual accumulate (the host folds the row's translation into `dy`, so `-dy` is the
            // row's total lag, not this line's displacement). Judging the residual alone would hand
            // a fast song's rows back to the host mid-song, where `mc.y#m` -> `mc.y#x` -> `mc.y#e`
            // zeroes the translation and the move reads as an instant jump.
            val layoutJump = toY - fromY
            if (!LyricLineSpringPolicy.acceptsMove(layoutJump, -dy.toFloat())) {
                diagnostics.refused(
                    "layoutJump=" + layoutJump + " residual=" + dy + " beyond jump ±" +
                        LyricLineSpringPolicy.MAX_DISPLACEMENT_PX + "px / residual ±" +
                        LyricLineSpringPolicy.MAX_RESIDUAL_PX.toInt() + "px",
                )
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
            // One move per row, not one per animateMove: a correction re-aims the move RecyclerView
            // already has, so it dispatches neither a start nor a finish (see LyricLineSpringPush).
            if (!applied.moving) notifyStarted(row)
            // One line per taken-over move, whatever the wave did with it: `kind=new` a row joining
            // this line change, `kind=retarget` a travelling row whose end point moved under an
            // unchanged visual position, `kind=merge` a correction below a pixel. The distinction is
            // the whole diagnostic: only `new` owes a started notification, and only `retarget` can
            // carry the "pushed down, then bounced" velocity drop.
            diagnostics.push(
                dy = dy,
                layoutJumpPx = layoutJump,
                applied = applied,
                tempo = LyricLineSpringPolicy.tempo,
                waves = tracks.activeWaves(),
                describe = { tracks.status(animator) },
            )
        }

        /** mc.y#g, after: keep RecyclerView from concluding the list is idle mid-spring. */
        private fun runningHook() = object : ModernMethodHook() {
            override fun afterHookedMethod(param: ModernMethodHook.MethodHookParam) {
                guarded("isRunning") {
                    if (param.throwable != null) return@guarded
                    val animator = param.thisObject ?: return@guarded
                    if (!tracks.hasActive(animator)) return@guarded
                    // We never queue an animator in the host's move lists, so mc.y#g() answers
                    // false for every frame of our spring. RecyclerView reads that as "idle" and
                    // ends every pending move — the instant jump. The answer is overridden, and
                    // the fact that it had to be is logged: no `idle` line at all on the device
                    // means this hook is never asked (suspicion 2), and a `reset` line right after
                    // one means RecyclerView ended the moves anyway.
                    param.result = true
                    diagnostics.idle { tracks.describe(animator) }
                }
            }
        }

        /** mc.y#e, after: recycle / reuse / cancel must leave no offset behind, and finish once. */
        private fun endAnimationHook() = object : ModernMethodHook() {
            override fun afterHookedMethod(param: ModernMethodHook.MethodHookParam) {
                guarded("endAnimation") {
                    val animator = param.thisObject ?: return@guarded
                    val holder = param.args.getOrNull(0) ?: return@guarded
                    tracks.finish(animator, holder)?.let { diagnostics.cleaned("endAnimation", it) }
                }
            }
        }

        /** mc.y#f, after: a full reset (track change, list replacement) ends every batch. */
        private fun endAnimationsHook() = object : ModernMethodHook() {
            override fun afterHookedMethod(param: ModernMethodHook.MethodHookParam) {
                guarded("endAnimations") {
                    val animator = param.thisObject ?: return@guarded
                    tracks.finishAll(animator)?.let { diagnostics.cleaned("endAnimations", it) }
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
            frames += 1
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
            // A batch pushed since the last frame is ranked by *this* frame, i.e. the wave starts
            // here; `previous == 0` is the clock having been (re)posted for rows already in flight.
            // Either way this is the only frame at which a batch's stagger exists to be printed.
            val starting = tracks.awaitingRank() || previous == 0L
            val stillRunning = tracks.advance(step)
            if (starting) diagnostics.waveStarted { tracks.status() }
            // The heartbeat is emitted *from* the frame callback, so a gap in `tick` lines while
            // rows were still owed is the frame clock stopping — something no push-side line shows.
            diagnostics.tick(frames) { tracks.status() }
            if (!stillRunning) return false
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
         * flight. A throwing settings read is a refusal, never a crash in the lyric list. This is the
         * same answer the trace file uses to decide whether it may be created or appended at all.
         */
        private fun isEnabled(): Boolean = enabled()

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

        /**
         * The host's own row notification, replayed once when the spring comes to rest.
         *
         * 1626's lyric animator announces every row it animates to its adapter
         * (`PlayerLyricsViewFragment$u#s` → `i1#N`), so a takeover that answers `animateMove` and
         * never animates has to replay it: the host emits it from the animator's own listener, and
         * that listener never runs while we own the displacement.
         *
         * Three rules, all of them here:
         *  * once per row — the wave's own `finished` flag is what calls this (see
         *    [dev.amenhancer.module.lyrics.LyricLineSpringWave.onSettled]), so a re-target cannot
         *    duplicate it and a reset never emits it;
         *  * on the thread that owns the list — the frame callback, which is where the wave ends;
         *  * never at the animation's expense — a missing role, a wrong animator or a throwing host
         *    method costs one rate-limited log line, and the row stays where the spring put it.
         */
        private fun notifySettled(row: LyricLineSpringRow) {
            val settle = roles.rowSettle ?: return
            val ok = runCatching {
                if (!settle.declaringClass.isInstance(row.animator)) {
                    error("animator is not " + settle.declaringClass.name)
                }
                settle.invoke(row.animator, row.holder)
            }.isSuccess
            diagnostics.rowSettle(row.slot, ok)
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
 * `adb logcat -s AMPP-LineSpring` answers what a device run asks, one line per event, with a fixed
 * vocabulary so a log can be read without guessing:
 *
 *  * `line-change start=… end=…` — the host committed a new current line (the upstream fact);
 *  * `push kind=new|retarget|merge …` — a move was offered and taken over, and what the wave did;
 *  * `start rows=… delays=[…]` — a batch started stepping (or the clock was re-posted): its stagger;
 *  * `tick … frame=…` — 250 ms of real Choreographer frames: the heartbeat, printed from the
 *    callback itself, so its absence while rows are owed is the clock stopping;
 *  * `stuck rows=… maxLag=… sinceMs=…` — a pushed batch was still where it was written 400 ms on;
 *  * `skip?` — one row of that batch, 200 ms on;
 *  * `handed back: …` — a move the spring refused, with the bound it failed;
 *  * `layout-shift` / `ignore-shift` / `shift-cap` — what the per-frame layout reading did;
 *  * `reset endAnimation|endAnimations …` — the host zeroed rows, and how far they were from home;
 *  * `idle host=0 override=1 …` — the host said "list is idle" while the wave was still travelling;
 *  * `row-settle holder=… ok=1|0` — a row arrived and the host's own "this move is over" was
 *    replayed (or refused), i.e. the notification a real move would have emitted.
 *
 * The pairing that answers "the middle line did not move" is `line-change` against `push`: the
 * host's count of line changes against ours, plus `tick` to prove the clock ran between them.
 * Repeats are rate limited per class (≤2 lines a second, except the 250 ms heartbeat) and the whole
 * session stops after [MAX_LINES], so a long song cannot flood logcat.
 *
 * The same lines also go to `/sdcard/Download/AMPP/line-spring.log` through [sink]
 * ([LineSpringLogFile]), timestamped, so a device report needs no computer: that file is where the
 * whole sequence survives. Both destinations are the same funnel, so neither can drift.
 */
internal class LyricLineSpringDiagnostics(
    private val clock: () -> Long = { SystemClock.uptimeMillis() },
    /**
     * Where each line also goes when the feature writes a file — `(line) -> Unit`, already
     * timestamped by the sink. Null (a test, a target without a context) means logcat only.
     */
    private val sink: ((String) -> Unit)? = null,
) {
    private val reported = HashMap<String, Long>()
    private var lastCleanupAt = Long.MIN_VALUE
    private var emitted = 0
    private var shifts = 0
    private var ignores = 0
    private var skips = 0
    private var idles = 0
    private var lastIdleAt = Long.MIN_VALUE

    /** Per-class rate gates: when each class last printed, and how much of its own budget it spent. */
    private val gateAt = HashMap<String, Long>()
    private val gateCount = HashMap<String, Int>()

    /** True once the heartbeat's budget is spent and its one closing line has been printed. */
    private var tickCapped = false

    /**
     * One taken-over `animateMove`, whatever the wave then did with it.
     *
     * This replaces the three lines the diagnostic used to print (`takeover`, `retarget`,
     * `merge`) because a device log asks one question per move — which of the three happened, and
     * how big was it — and three separate lines could not be read as one batch:
     *
     *  * `kind=new` — the row was not travelling; it joins this line change, owes one started
     *    notification, and gets its stagger slot on the next frame (see [waveStarted]);
     *  * `kind=retarget` — the row was already travelling; the layout moved under an unchanged
     *    visual position, so only the end point moved. `kept=0` on a `v` that was not 0 is the
     *    correction having flipped the target, i.e. the "pushed down, then bounced" shape;
     *  * `kind=merge` — the correction was below a pixel and was folded into the running spring:
     *    nothing the eye could resolve, which is why `accepted=0` there.
     *
     * `dy` is the host's own displacement, `lag` the residual that was written (`-dy`),
     * `layoutJump` the layout movement behind it (`toY - fromY`), `rows` how many rows that
     * animator's wave now holds, `delayMs` every one of those rows' remaining wait in join order
     * (a just-pushed row reads 0 here — its slot is assigned on the next frame) and `accepted`
     * whether the wave took this displacement as the row's new end point.
     */
    fun push(
        dy: Int,
        layoutJumpPx: Int,
        applied: LyricLineSpringPush,
        tempo: LyricLineSpringTempo,
        waves: Int,
        describe: () -> LyricLineSpringStatus?,
    ) {
        if (!gate(PUSH, PUSH_WINDOW_MS, MAX_PUSH_LINES)) return
        val status = runCatching(describe).getOrNull()
        val kind = when {
            applied.merged -> "merge"
            applied.moving -> "retarget"
            else -> "new"
        }
        emit(
            "push kind=" + kind +
                " dy=" + dy +
                " lag=" + fixed(applied.lagPx) +
                " layoutJump=" + layoutJumpPx +
                " rows=" + (status?.rows ?: 0) +
                " delayMs=" + delays(status) +
                " accepted=" + (if (applied.merged) 0 else 1) +
                (if (kind == "new") {
                    " t=" + fixed(tempo.tempoMs) + "ms k=" + fixed(tempo.stiffness) +
                        " c=" + fixed(tempo.damping)
                } else {
                    ""
                }) +
                (if (kind == "retarget") {
                    " v=" + fixed(applied.offeredVelocity) + " kept=" + fixed(applied.keptVelocity) +
                        (if (applied.velocityDropped) " dropped=1" else "")
                } else {
                    ""
                }) +
                (if (applied.waiting) " waiting=1" else "") +
                (if (applied.delayDropped) " unstaggered=1" else "") +
                (if (applied.heldMs > 0f) " held=" + applied.heldMs.toInt() + "ms" else "") +
                " waves=" + waves,
        )
    }

    /**
     * A batch started stepping: the AMLL stagger it will run.
     *
     * Printed from the frame [LyricLineSpringWave.advance] ranks the pending pushes in — the first
     * frame at which the delays exist at all — and also from the frame after the clock had to be
     * re-posted for rows that were already in flight. A `push` line's `delayMs=[…]` therefore
     * describes the *previous* stagger for a row that has just joined; this line is the answer for
     * the batch that push belongs to.
     */
    fun waveStarted(describe: () -> LyricLineSpringStatus?) {
        if (!gate(START, START_WINDOW_MS, MAX_START_LINES)) return
        val status = runCatching(describe).getOrNull() ?: return
        if (status.rows == 0) return
        emit("start rows=" + status.rows + " delays=" + delays(status))
    }

    /**
     * The heartbeat: 250 ms of real frames, and what the wave was doing in them.
     *
     * Emitted *from* the Choreographer callback, which is the only place that can prove the callback
     * is running: a gap between two `tick` lines while rows were still owed is the frame clock
     * stopping, and nothing on the push side can show that. `frame` counts callbacks since the
     * install, so the delta across two ticks is the real frame rate — two ticks one frame apart after
     * a second of wall time are a starved clock, not an idle one.
     *
     * It is the one class with a sub-second window (the cadence the heartbeat needs); every other
     * class is held to ≤2 lines a second. Its own budget is bounded and its last line says so, which
     * is what keeps "tick stopped" from being confusable with "the budget ran out".
     */
    fun tick(frame: Long, describe: () -> LyricLineSpringStatus?) {
        if (tickCapped) return
        if (!gate(TICK, TICK_WINDOW_MS, MAX_TICK_LINES)) {
            if (spent(TICK, MAX_TICK_LINES)) {
                tickCapped = true
                emit("tick-capped frame=" + frame + " n=" + MAX_TICK_LINES)
            }
            return
        }
        val status = runCatching(describe).getOrNull() ?: return
        emit(
            "tick rows=" + status.rows + " moving=" + status.moving + " waiting=" + status.waiting +
                " maxLag=" + fixed(status.maxLagPx) + " frame=" + frame,
        )
    }

    /**
     * The host's own line-change callback: "this line is current now".
     *
     * The upstream half of the pairing the log has to be read with. Every `line-change` should be
     * followed by the pushes of the layout pass it triggers; a `line-change` with no `push` means
     * the host changed the line and no move was offered to the spring, and a `push` with no
     * `line-change` means something else moved the rows. Neither is visible in a log of the spring
     * alone — which is exactly the gap this line closes.
     */
    fun lineChange(start: Int, end: Int) {
        if (!gate(LINE_CHANGE, LINE_CHANGE_WINDOW_MS, MAX_LINE_CHANGE_LINES)) return
        emit("line-change start=" + start + " end=" + end)
    }

    /**
     * A pushed batch was still where it was written 400 ms of real spring time later.
     *
     * The batch-level answer, next to the per-row [skipped]: `rows` how many rows of that batch had
     * not moved a pixel, `maxLag` the largest residual among them (what the eye is still owed) and
     * `sinceMs` the spring time since the batch's last push. Rows parked by their own stagger slot
     * or by an interlude hold are not counted, so a hit here is not a slow start — it is a row being
     * held against its own spring.
     */
    fun stuck(rows: Int, maxLagPx: Float, sinceMs: Float) {
        if (!gate(STUCK, STUCK_WINDOW_MS, MAX_STUCK_LINES)) return
        emit("stuck rows=" + rows + " maxLag=" + fixed(maxLagPx) + " sinceMs=" + sinceMs.toInt())
    }

    /** The line-change hook did not install; one line, ever. The spring itself is unaffected. */
    fun traceUnavailable(reason: String) = once("trace", "line-change trace unavailable: " + reason)

    /**
     * The host's own per-row "this move is over", replayed when a spring came to rest.
     *
     * `row-settle holder=<slot> ok=1` once per row that arrived is the line a device run reads: in a
     * passage of short lines there is one of them after **each** line change, and what the next line
     * does is read against it. `ok=0` means the host's method itself refused (a missing adapter, a
     * row recycled in between) — the spring and the row's position are unaffected, only the host's
     * own reaction to the move is lost.
     *
     * Rate limited per class like every event line (≤2 a second), because a fast song settles rows
     * as fast as it moves them; the budget is the same size as `push`'s.
     */
    fun rowSettle(slot: Int, ok: Boolean) {
        if (!gate(ROW_SETTLE, REPEAT_WINDOW_MS, MAX_ROW_SETTLE_LINES)) return
        emit("row-settle holder=" + slot + " ok=" + (if (ok) 1 else 0))
    }

    /**
     * The optional row-notification role did not resolve on this build: one line, ever.
     *
     * The spring still runs; only the host's own per-row notification is missing, which on a device
     * looks like a correct animation with one host-side effect silently absent — exactly what this
     * line has to be distinguishable from.
     */
    fun rowSettleUnavailable(reason: String) =
        once("row-settle-unavailable", "row-settle unavailable: " + reason)

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

    /**
     * A reset path zeroed rows: who triggered it, how many, and the largest lag it threw away.
     *
     * `reset endAnimation rows=1 lag=0.4->0` is a row that had arrived. `lag=180->0` on a row that
     * is still on screen is an instant jump — which is what suspicion 1 looked like on the device,
     * and why the number is printed rather than just the fact.
     */
    fun cleaned(where: String, reset: LyricLineSpringReset) {
        val now = now()
        if (now - lastCleanupAt < REPEAT_WINDOW_MS) return
        lastCleanupAt = now
        emit("reset " + where + " rows=" + reset.rows + " lag=" + fixed(reset.maxLagPx) + "->0")
    }

    /**
     * A per-frame layout shift the wave refused to fold into a row's residual.
     *
     * Every reason means the same thing on the device: the row *followed* the layout instead of
     * being pinned to the screen. `why=block` every tracked row moved (a scroll, a re-anchor, the
     * host's own animation of the line change); `why=fast` faster than a background line can grow
     * (1626: a growing line is 4-6 px per frame, the host's own move 33-102 px); `shift-cap` this
     * row already took one line change's worth of compensation. A burst of `why=fast` during a line
     * change is the host animating the block itself — exactly the case that used to be swallowed.
     */
    fun shiftIgnored(deltaPx: Int, reason: LyricLineShiftSkip) {
        // One key per reason: the wave already reports at most one line per frame, and 500 ms of
        // silence between them keeps a fast song under the line budget.
        val key = "ignore:" + reason.name
        val now = now()
        synchronized(reported) {
            val last = reported[key]
            if (last != null && now - last < IGNORE_WINDOW_MS) return
            if (ignores >= MAX_IGNORE_LINES) return
            reported[key] = now
            ignores += 1
        }
        emit(
            when (reason) {
                LyricLineShiftSkip.BLOCK -> "ignore-shift why=block delta=" + deltaPx
                LyricLineShiftSkip.TOO_FAST -> "ignore-shift why=fast delta=" + deltaPx
                LyricLineShiftSkip.CAPPED ->
                    "shift-cap delta=" + deltaPx + " cap=" + LyricLineSpringPolicy.MAX_COMPENSATION_PX
            },
        )
    }

    /**
     * The swallowed line change, on the device: a row a takeover pushed has had 200 ms of real
     * spring time and is still within a pixel of where the push put it.
     *
     * This is the one line that answers "the middle line did not move" without a screen recording:
     * a run of them while the block is standing still means something is holding the row against
     * its layout, and the accompanying `layout-shift` lines say what.
     */
    fun skipped(holder: Any, movedPx: Float, lagPx: Float, windowMs: Float) {
        val key = "skip:" + System.identityHashCode(holder)
        val now = now()
        synchronized(reported) {
            val last = reported[key]
            if (last != null && now - last < SKIP_REPORT_WINDOW_MS) return
            if (skips >= MAX_SKIP_LINES) return
            reported[key] = now
            skips += 1
        }
        emit(
            "skip? moved=" + fixed(movedPx) + "px lag=" + fixed(lagPx) +
                " window=" + fixed(windowMs) + "ms",
        )
    }

    /**
     * `isRunning()` answered false by the host while the wave still had rows.
     *
     * We override it (RecyclerView would otherwise end every pending move), so this line is the
     * evidence that the override was necessary rather than the evidence of a bug: no `idle` line at
     * all in a busy passage means the hook is not being asked, and a `reset` line right after one
     * means RecyclerView ended the moves anyway.
     */
    fun idle(describe: () -> String?) {
        // Built only when the line is actually going to be printed: isRunning() is asked once per
        // frame, and a String per frame for a line that is rate limited away is waste on the main
        // thread. The counter is a diagnostic, so the unlocked read is fine.
        val now = now()
        if (now - lastIdleAt < IDLE_WINDOW_MS || idles >= MAX_IDLE_LINES) return
        val detail = runCatching(describe).getOrNull() ?: return
        lastIdleAt = now
        idles += 1
        emit("idle host=0 override=1 " + detail)
    }

    /** The contract or the install is unusable; one line, ever. */
    fun unavailable(reason: String) = once("unavailable", "line spring unavailable: " + reason)

    /** A hook or a frame failed; one line per call site, ever. */
    fun failed(where: String, error: Throwable) =
        once("failed:" + where, "line spring " + where + " failed: " + (error.message ?: error.javaClass.simpleName))

    /**
     * `[0,50,100]` — a wave's stagger in join order, bounded so one line stays readable.
     *
     * Only built when the line it belongs to is actually going to be printed; a lyric pane never
     * holds more rows than [MAX_DELAYS], and the bound is there so a broken rank cannot.
     */
    private fun delays(status: LyricLineSpringStatus?): String {
        val list = status?.delays ?: return "[]"
        if (list.isEmpty()) return "[]"
        return list.take(MAX_DELAYS).joinToString(",", "[", "]") { it.toInt().toString() }
    }

    /**
     * One line per class per window, plus a hard budget per class on top of [MAX_LINES].
     *
     * The 500 ms windows are what "at most two lines a second" means for every event line; [tick] is
     * the exception (a heartbeat needs its 250 ms cadence) and is bounded by its own budget plus its
     * one closing `tick-capped` line. [MAX_LINES] remains the wall underneath all of them.
     */
    private fun gate(name: String, windowMs: Long, budget: Int): Boolean {
        val now = now()
        synchronized(reported) {
            val last = gateAt[name] ?: Long.MIN_VALUE
            if (now - last < windowMs) return false
            val printed = gateCount[name] ?: 0
            if (printed >= budget) return false
            gateAt[name] = now
            gateCount[name] = printed + 1
        }
        return true
    }

    /** True when [name]'s own budget is spent. Unlocked read: a diagnostic, not a decision. */
    private fun spent(name: String, budget: Int): Boolean = (gateCount[name] ?: 0) >= budget

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
        // The file is the third destination, and the only one a user can open without a computer.
        // It gets the same line; the sink adds its own timestamp and refuses while the switch is off.
        sink?.let { write -> runCatching { write(line) } }
    }

    private fun fixed(value: Float): String = String.format(java.util.Locale.US, "%.1f", value)

    private companion object {
        const val TAG = "AMPP-LineSpring"

        /**
         * The wall: one whole session (one song, or one cold start's worth of songs) prints at most
         * this many lines, whatever the per-class budgets still allow.
         */
        const val MAX_LINES = 200

        const val REPEAT_WINDOW_MS = 500L

        /** One absorbed layout-shift line per holder inside this window. */
        const val SHIFT_WINDOW_MS = 100L

        /** Budget for the layout-shift lines, on top of [MAX_LINES]. */
        const val MAX_SHIFT_LINES = 160

        /** One refused layout shift per reason inside this window, and a bounded budget. */
        const val IGNORE_WINDOW_MS = 500L
        const val MAX_IGNORE_LINES = 60

        /** One `skip?` line per holder inside this window, and a bounded budget for the run. */
        const val SKIP_REPORT_WINDOW_MS = 2_000L
        const val MAX_SKIP_LINES = 40

        /** One `idle` line a second: it repeats for as long as a wave is travelling. */
        const val IDLE_WINDOW_MS = 1_000L
        const val MAX_IDLE_LINES = 12

        /** Gate keys: one rate limit and one budget per line class, so no class can starve another. */
        const val PUSH = "push"
        const val START = "start"
        const val TICK = "tick"
        const val LINE_CHANGE = "line-change"
        const val STUCK = "stuck"
        const val ROW_SETTLE = "row-settle"

        /**
         * ≤2 lines a second for every event class.
         *
         * `push` is the busiest (a fast song offers 4-8 moves per line change), so its budget is the
         * largest; `line-change` is the one line per host line change the pushes are counted against.
         */
        const val PUSH_WINDOW_MS = 500L
        const val MAX_PUSH_LINES = 60
        const val START_WINDOW_MS = 500L
        const val MAX_START_LINES = 20
        const val LINE_CHANGE_WINDOW_MS = 500L
        const val MAX_LINE_CHANGE_LINES = 40
        const val STUCK_WINDOW_MS = 2_000L
        const val MAX_STUCK_LINES = 20

        /**
         * The row announcement: one line per row that arrives, so the same budget as `push`.
         *
         * A line change settles 4-8 rows inside a few hundred milliseconds, and only the leading
         * ones are printed — the class shares [REPEAT_WINDOW_MS] with every other event line, which
         * is what keeps a fast song under the log budget.
         */
        const val MAX_ROW_SETTLE_LINES = 60

        /**
         * The heartbeat: 250 ms, the cadence that makes a gap meaningful.
         *
         * It only ever prints while the frame clock is running (the clock is posted only while a
         * wave is live), so over a song it averages well under two lines a second; the burst is
         * bounded by [MAX_TICK_LINES] and closed by one `tick-capped` line.
         */
        const val TICK_WINDOW_MS = 250L
        const val MAX_TICK_LINES = 80

        /** Longest `delayMs=[…]` printed; a lyric pane shows fewer rows than this. */
        const val MAX_DELAYS = 12
    }
}
