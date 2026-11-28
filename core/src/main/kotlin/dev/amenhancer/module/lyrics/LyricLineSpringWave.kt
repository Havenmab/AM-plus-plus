package dev.amenhancer.module.lyrics

import kotlin.math.abs

/**
 * One animator's line-change wave: one spring per row, started in AMLL's staggered order.
 *
 * Apple Music moves the whole lyric block at once — 4-8 `animateMove` calls that share one `dy` —
 * but AMLL does not: every line owns a spring, and the lines start 50 ms apart
 * ([LyricLineStaggerPolicy]). 乙-1 replayed the block as a single scalar spring, which is why it
 * looked like a rectangle sliding; this class replays it as rows chasing each other.
 *
 * **State per row.** Each row keeps its own visual lag (pixels against its layout position), its
 * own closed-form spring, its own clock and its own remaining start delay. Nothing is shared, so a
 * re-target of one row cannot drag the others.
 *
 * **Why `-dy` is the new lag, not a delta.** The host computes `dy = toY - (fromY + translationY)`
 * (1626 DEX, `mc.y#m`), i.e. it already folds the row's current translation in, and writes
 * `translationY = -dy`. Passing `-dy` straight through therefore keeps the displayed position
 * continuous across a re-target *and* accumulates every displacement without a jump.
 *
 * **The stagger is assigned per frame.** A line change's pushes all happen inside one layout pass,
 * so "the rows pushed since the last frame" is exactly one line change. The first [advance] after
 * it ranks those rows by their post-layout top — AMLL's line order — and gives each one its delay.
 *
 * **A later move is a target correction, not a second impulse.** Apple Music lays the block out
 * more than once per line change — the re-anchor, and then every frame of the background line's own
 * height animation — and each of those layouts can offer a fresh `animateMove` for a row that is
 * already travelling. Because the host computes `dy = toY - (fromY + translationY)`, a fresh move
 * already folds the row's current translation in, so `-dy` is the *new absolute* lag: the visual
 * position stays continuous across the correction and only the *end point* moves (see
 * [LyricLineSpringPush.layoutDeltaPx]). That is iOS's behaviour — one monotone up-move whose end
 * point already contains the background line's extra height — and it is why the spring must not
 * treat the correction as a second impulse:
 *
 *  * the carried velocity is kept **only when it still points at the new target**
 *    (`velocity * lag < 0`). A correction that flips the target to the other side of the row zeroes
 *    it, so the row never overshoots its home and comes back — the "pushed down, then bounced" look;
 *  * a row still inside its stagger delay keeps its remaining delay: its layout moved, its visual did
 *    not, and it has no velocity to preserve either way.
 *
 * **Layout changes that announce nothing are read back, once per frame.** The background line's
 * growth is not a notify-driven layout, so it never reaches `animateMove` at all; [advance]
 * therefore compares each tracked row's own `top` against the expectation the last move (or the
 * previous frame) left, and folds any difference into the residual — see [absorbLayoutShifts]. This
 * is the only place the wave reads layout instead of being told about it, and it never writes it.
 *
 * **Only a background line may be folded in.** The host also moves the block's layout itself — its
 * own animation of the line change, a scroll, a re-anchor — and a row that follows that movement is
 * doing exactly what it should. Folding it into the residual instead *cancels* it: the row stays
 * where the eye left it while the layout runs away underneath, so the line change does not happen at
 * all, the residual grows by every frame the host animates, and past the takeover's own bound
 * everything is handed back and snaps at once. [absorbLayoutShifts] therefore refuses any shift that
 * every tracked row took part in (the block moved), any shift faster than a line can grow
 * ([LyricLineSpringPolicy.MAX_COMPENSABLE_SHIFT_PX]) and any row that already took one line change's
 * worth ([LyricLineSpringPolicy.MAX_COMPENSATION_PX]); the host's own movement is followed instead.
 *
 * **A row already in flight is never re-delayed and never re-started.** AMLL queues the *new target*
 * behind the delay and lets the row finish travelling to the old one meanwhile; our host has already
 * jumped the layout, so the equivalent is to re-target the moving row immediately. Freezing it
 * instead would stall a row mid-travel and let its lag grow with every fast line change.
 *
 * **A re-target re-aims one continuous move; it never ends it.** RecyclerView's pending-move ledger
 * accepts exactly one "this animation is over" per started move, and the host's own move emits that
 * pair once per taken-over `animateMove`. Because the host folds the row's translation into `dy`,
 * every later line change offers a fresh `animateMove` for a row that is still travelling — in a
 * fast song every 60-120 ms. Retiring the previous move on each of those hands RecyclerView a row it
 * has already been told is idle, dozens of times per second, and on the device that is what makes a
 * fast song's displacement disappear. The wave therefore keeps one move per row: the start is
 * dispatched when the row joins ([LyricLineSpringPush.moving] is false) and the finish when the row
 * genuinely ends — the spring arriving, `endAnimation(holder)`, `endAnimations()`, or the row moving
 * to another wave.
 *
 * Pure: it never touches a View, a clock or the host. The caller supplies [write] (pixels) and
 * [finish] (the host's `dispatchAnimationFinished`), plus the pane height a line change was pushed
 * with, which is what makes the exactly-once rule and the wave's arithmetic testable with a fake
 * row.
 */
class LyricLineSpringWave<H : Any>(
    private val write: (H, Float) -> Unit,
    private val finish: (H) -> Unit,
    /**
     * The row's current layout top, or null when it cannot be read (detached, recycled).
     *
     * Only used by the per-frame layout tracking (see [absorbLayoutShifts]); the wave never writes
     * it, so the host stays the only writer of layout.
     */
    private val layoutTop: (H) -> Int? = { null },
    /** Called for every layout shift that was absorbed, for the one diagnostic line. */
    private val onLayoutShift: ((H, Int, Float) -> Unit)? = null,
    /** Called for every layout shift that was *not* absorbed, with the reason. */
    private val onShiftSkipped: ((H, Int, LyricLineShiftSkip) -> Unit)? = null,
    /**
     * Called when a row a [push] took over has still not moved a pixel after [SKIP_WINDOW_MS] of
     * actual spring time — the one question a device log has to answer.
     *
     * `(holder, movedPx, lagPx, windowMs)`. Only frames in which the row was really stepping count,
     * so a row parked by the interlude or by its own stagger slot is never reported.
     */
    private val onSkipped: ((H, Float, Float, Float) -> Unit)? = null,
    /**
     * Called when a pushed batch has had [STUCK_WINDOW_MS] of real spring time and rows of it are
     * still within a pixel of the `translationY` the push wrote — the row is being held.
     *
     * `(rows, maxLagPx, sinceMs)`: how many rows of that batch are stuck, the largest residual
     * among them, and the spring time since the batch's last push. This is the batch-level twin of
     * [onSkipped], which asks the same question per row after 200 ms; the two answer different
     * questions ("this row never moved" vs "this line change never moved") and are logged apart.
     */
    private val onStuck: ((Int, Float, Float) -> Unit)? = null,
) {
    /**
     * The height of the list the pending line change belongs to, as reported by [push].
     *
     * Only the stagger's focus line reads it (see `LyricLineStaggerPolicy.focusRank`); 0 means
     * "unknown", which staggers every row by the same 50 ms.
     */
    var paneHeightPx: Int = 0

    /**
     * The hold a row pushed right now would join, in ms.
     *
     * Set by the session for the duration of an interlude exit ([hold] does the same for rows that
     * are already in the wave); every push during that window inherits it, so a row that only shows
     * up in a later layout pass still waits.
     */
    var pendingHoldMs: Float = 0f

    private class Member<H : Any>(
        val holder: H,
        var layoutTop: Int,
        var spring: LyricLineSpring,
        var deadlineMs: Float,
        var elapsedMs: Float,
        var delayMs: Float,
        var finished: Boolean,
        /**
         * Extra wait *before* the stagger starts counting, in ms.
         *
         * This is the interlude's exit animation (see `LyricInterludeHold`): while it is running the
         * row must not move at all, and the AMLL stair must not start ticking either — the line
         * change begins *after* the dots have finished shrinking away, which is what the host's own
         * look does.
         */
        var holdMs: Float = 0f,
        /**
         * How much of this row's travel the *current* line change has already cancelled, in px.
         *
         * Reset by every [push] (a new line change) and bounded by
         * [LyricLineSpringPolicy.MAX_COMPENSATION_PX], so no run of layout shifts can hold a row
         * still for longer than one background line's worth.
         */
        var compensatedPx: Float = 0f,
        /** True while this row's post-push movement is being watched (see [onSkipped]). */
        var watching: Boolean = false,
        /** The layout top / lag the watched push left the row at. */
        var watchTop: Int = 0,
        var watchLag: Float = 0f,
        /** Spring time actually spent since the watched push, in ms. */
        var watchedMs: Float = 0f,
        /** True while this row belongs to the batch being watched for [onStuck]. */
        var batchWatching: Boolean = false,
        /** The `translationY` the batch's last push left this row at. */
        var batchLag: Float = 0f,
    )

    private val members = ArrayList<Member<H>>()

    /** Rows pushed since the last frame — one line change, waiting to be ranked. */
    private val unranked = ArrayList<Member<H>>()

    /** True while a pushed batch is owed the [STUCK_WINDOW_MS] movement check. */
    private var watchingBatch = false

    /** Spring time since that batch's last push, in ms. */
    private var batchMs = 0f

    /** Rows currently owned by this wave, finished or not. */
    val size: Int get() = members.size

    /** True while at least one row still owes a displacement (a start delay counts). */
    val isRunning: Boolean get() = members.any { !it.finished }

    /**
     * True when the next [advance] will rank a batch that has been pushed since the last frame.
     *
     * That frame *is* the wave's start — it is the one that assigns the AMLL stagger — so the host
     * reads this before advancing to know whether to print its `start` line. Read-only: it neither
     * ranks nor prunes.
     */
    fun awaitingRank(): Boolean = unranked.isNotEmpty()

    /** The rows this wave currently holds, in the order they joined. */
    fun holders(): List<H> = members.map { it.holder }

    /** True when [holder] is one of this wave's rows (finished or not). */
    fun contains(holder: H): Boolean = members.any { it.holder === holder }

    /** True when [holder] belongs to this wave and has not been finished yet. */
    fun isPending(holder: H): Boolean = members.any { it.holder === holder && !it.finished }

    /** The lag [holder] is displaying right now, in pixels (0 when it is not in this wave). */
    fun lagOf(holder: H): Float = members.firstOrNull { it.holder === holder }?.let {
        if (it.finished) 0f else it.spring.offsetAt(it.elapsedMs)
    } ?: 0f

    /** How fast [holder] is travelling right now, in pixels per second. */
    fun velocityOf(holder: H): Float = members.firstOrNull { it.holder === holder }?.let {
        if (it.finished) 0f else it.spring.velocityAt(it.elapsedMs)
    } ?: 0f

    /** The total wait still left for [holder], in milliseconds (0 once it is moving). */
    fun delayOf(holder: H): Float = members.firstOrNull { it.holder === holder }?.let {
        if (it.finished) 0f else it.holdMs + it.delayMs
    } ?: 0f

    /** How much of [holder]'s travel the current line change has already cancelled, in pixels. */
    fun compensatedOf(holder: H): Float =
        members.firstOrNull { it.holder === holder }?.compensatedPx ?: 0f

    /**
     * A read-only snapshot of what these rows are doing, for the host's frame heartbeat.
     *
     * `rows` counts the rows this wave still owns, `moving` the ones actually stepping a spring,
     * `waiting` the ones parked on a stagger slot or an interlude hold, `maxLagPx` the largest
     * residual still owed and `delays` the per-row wait in join order. Nothing here is written and
     * no view is read: the answer is exactly the state the next [advance] will step, which is what
     * makes it usable as evidence that the clock is ticking and how much it is ticking for.
     */
    fun status(): LyricLineSpringStatus {
        if (members.isEmpty()) return LyricLineSpringStatus.EMPTY
        var rows = 0
        var moving = 0
        var waiting = 0
        var maxLag = 0f
        val delays = ArrayList<Float>(members.size)
        members.forEach { member ->
            if (member.finished) return@forEach
            rows += 1
            delays += member.holdMs + member.delayMs
            if (member.holdMs > 0f || member.delayMs > 0f) waiting += 1 else moving += 1
            val lag = abs(member.spring.offsetAt(member.elapsedMs))
            if (lag > maxLag) maxLag = lag
        }
        return if (rows == 0) {
            LyricLineSpringStatus.EMPTY
        } else {
            LyricLineSpringStatus(rows, moving, waiting, maxLag, delays)
        }
    }

    /**
     * Park every unfinished row for [holdMs] more: no writes, no motion, and no stagger either.
     *
     * A row that is already travelling freezes where it is (its offset is not touched, so nothing
     * jumps) and resumes with the same spring once the hold is over. Rows pushed later join the
     * same window through [pendingHoldMs].
     */
    fun hold(holdMs: Float) {
        if (holdMs <= 0f) return
        pendingHoldMs = maxOf(pendingHoldMs, holdMs)
        members.forEach { member ->
            if (!member.finished) member.holdMs = maxOf(member.holdMs, holdMs)
        }
    }

    /** The exit finished (or the feature was turned off): let the stagger run now. */
    fun release() {
        pendingHoldMs = 0f
        members.forEach { member ->
            if (!member.finished) member.holdMs = 0f
        }
    }

    /**
     * Take [holder]'s move over at [lagPx] (the host's `-dy`) and write it immediately.
     *
     * A row at rest (or a new one) joins this frame's line change, gets its one move and receives a
     * stagger delay when the batch is ranked. A row *already in flight* is re-aimed instead: the
     * layout already jumped, [lagPx] is written in this same host call, and the existing move simply
     * gets a new end point — the motion is not re-impulsed and the move is not retired (see the
     * class doc and [LyricLineSpringPush]).
     *
     * Two corrections are special-cased:
     *
     *  * a correction smaller than [LyricLineSpringPolicy.MIN_VISIBLE_DELTA_PX] changes nothing the
     *    eye could see, so it is *merged* — the running spring keeps its clock and its velocity and
     *    the caller only logs it;
     *  * a correction that puts the end point on the *other* side of a row that is still waiting for
     *    its stagger slot is a reversal, not a correction: its slot no longer describes the move, so
     *    the slot is dropped and the row leaves at once.
     */
    fun push(holder: H, lagPx: Float, layoutTop: Int, tempo: LyricLineSpringTempo): LyricLineSpringPush {
        val existing = members.firstOrNull { it.holder === holder }
        val moving = existing != null && !existing.finished
        if (!moving) return launch(existing, holder, lagPx, layoutTop, tempo)

        val member = requireNotNull(existing)
        val previousLag = member.spring.offsetAt(member.elapsedMs)
        val offered = member.spring.velocityAt(member.elapsedMs)
        // A row that has not left its stagger slot keeps the slot: its layout moved, its visual did
        // not, and it has no velocity yet either way.
        val waiting = member.delayMs > 0f
        // ... unless the new end point is on the other side of the row: then the queued slot belongs
        // to a move that no longer exists, and keeping it would pin the row while it visibly travels
        // the wrong way.
        val reversed = waiting && previousLag * lagPx < 0f
        member.layoutTop = layoutTop
        if (pendingHoldMs > 0f) member.holdMs = maxOf(member.holdMs, pendingHoldMs)
        if (abs(lagPx - previousLag) < LyricLineSpringPolicy.MIN_VISIBLE_DELTA_PX) {
            // Sub-pixel: the layout moved by less than one pixel. Keep the spring, keep its clock,
            // keep its velocity; only the displayed lag is synchronised (see [LyricLineSpringPush.merged]).
            write(holder, lagPx)
            armWatch(member, lagPx)
            armBatch(member, lagPx)
            return LyricLineSpringPush(
                lagPx = lagPx,
                previousLagPx = previousLag,
                offeredVelocity = offered,
                keptVelocity = offered,
                moving = true,
                waiting = waiting,
                heldMs = member.holdMs,
                merged = true,
            )
        }
        val kept = if (waiting && !reversed) 0f else towardTarget(offered, lagPx)
        val spring = tempo.at(lagPx, kept)
        member.spring = spring
        member.deadlineMs = spring.deadlineMs()
        member.elapsedMs = 0f
        if (reversed) member.delayMs = 0f
        // A new end point is a new line change: the compensation budget starts over, because the
        // row is about to travel that distance. (A merged sub-pixel correction does not reset it:
        // it did not move the layout.)
        member.compensatedPx = 0f
        write(holder, lagPx)
        armWatch(member, lagPx)
        armBatch(member, lagPx)
        return LyricLineSpringPush(
            lagPx = lagPx,
            previousLagPx = previousLag,
            offeredVelocity = offered,
            keptVelocity = kept,
            moving = true,
            waiting = waiting,
            heldMs = member.holdMs,
            merged = false,
            delayDropped = reversed,
        )
    }

    /**
     * [holder]'s first move, or a new one after it came to rest: the row joins the pending line
     * change and owes exactly one start ([LyricLineSpringPush.moving] false) and one finish.
     */
    private fun launch(
        existing: Member<H>?,
        holder: H,
        lagPx: Float,
        layoutTop: Int,
        tempo: LyricLineSpringTempo,
    ): LyricLineSpringPush {
        val spring = tempo.at(lagPx, 0f)
        val member = existing ?: Member(
            holder = holder,
            layoutTop = layoutTop,
            spring = spring,
            deadlineMs = spring.deadlineMs(),
            elapsedMs = 0f,
            delayMs = 0f,
            finished = false,
        ).also { members += it }
        member.layoutTop = layoutTop
        member.finished = false
        member.spring = spring
        member.deadlineMs = spring.deadlineMs()
        member.elapsedMs = 0f
        member.delayMs = 0f
        // An interlude exit that is still running owns the start of this batch: the row waits for it
        // before its stagger even begins (LyricInterludeHold -> LyricLineSpringWave.hold). A rest
        // between two line changes must not carry an older wait over, so this assigns, not maxes.
        member.holdMs = pendingHoldMs
        // A fresh (or re-launched from rest) row joins this frame's line change; a row that was
        // already travelling keeps going from where it is and is not staggered again.
        if (unranked.none { it === member }) unranked += member
        member.compensatedPx = 0f
        write(holder, lagPx)
        armWatch(member, lagPx)
        armBatch(member, lagPx)
        return LyricLineSpringPush(lagPx, 0f, 0f, 0f, moving = false, waiting = false, heldMs = member.holdMs)
    }

    /**
     * Advance every row by [frameStepMs] and write its new lag.
     *
     * Rows still inside their start delay hold their position; the rest step their own spring.
     * Returns true while any row still owes something. A row that has arrived is snapped to 0 and
     * finished exactly once, and never written again.
     */
    fun advance(frameStepMs: Float): Boolean {
        if (unranked.isNotEmpty()) rank()
        absorbLayoutShifts()
        val step = LyricLineSpringPolicy.frameStep(frameStepMs)
        var running = false
        members.forEach { member ->
            if (member.finished) return@forEach
            if (member.holdMs > 0f) {
                // The interlude is still coming to rest: the row keeps the position the layout jump
                // left it at, and the stagger does not start ticking until this reaches 0.
                member.holdMs = (member.holdMs - step).coerceAtLeast(0f)
                running = true
                return@forEach
            }
            if (member.delayMs > 0f) {
                // AMLL's queue: the row has not started yet, so it keeps the position the layout
                // jump left it at. Nothing is written — [push] already wrote it.
                member.delayMs = (member.delayMs - step).coerceAtLeast(0f)
                if (member.delayMs > 0f) {
                    running = true
                    return@forEach
                }
            }
            member.elapsedMs += step
            val offset = member.spring.offsetAt(member.elapsedMs)
            val velocity = member.spring.velocityAt(member.elapsedMs)
            watch(member, offset, step)
            if (
                LyricLineSpringPolicy.isAtRest(offset, velocity) ||
                member.elapsedMs >= member.deadlineMs
            ) {
                member.finished = true
                write(member.holder, 0f)
                finish(member.holder)
            } else {
                write(member.holder, offset)
                running = true
            }
        }
        watchBatch(step)
        return running
    }

    /**
     * End [holder]'s animation now, exactly once.
     *
     * [resetOffset] writes 0 first — what a recycled row, a cancelled animation and the end of an
     * arrived spring all need. A re-target passes false: the row's layout already jumped, and the
     * new lag is written by [push] in the same host call, so zeroing here would flash the old
     * position.
     */
    fun finishHolder(holder: H, resetOffset: Boolean = true): Boolean {
        val member = members.firstOrNull { it.holder === holder } ?: return false
        if (member.finished) return false
        member.finished = true
        if (resetOffset) write(holder, 0f)
        finish(holder)
        return true
    }

    /** End every row of this wave, each exactly once, at 0. */
    fun finishAll() {
        members.forEach { member ->
            if (!member.finished) {
                member.finished = true
                write(member.holder, 0f)
                finish(member.holder)
            }
        }
    }

    /** Drop finished rows so a long-lived wave does not grow. */
    fun prune() {
        members.removeAll { it.finished }
        unranked.removeAll { it.finished }
    }

    /**
     * Take in every layout change that no `animateMove` announced.
     *
     * Apple Music grows a row's background-vocal line from a `ValueAnimator` that rewrites
     * `layoutParams.height` once per frame. Those layouts carry no adapter notification, so
     * RecyclerView never asks the item animator about them: there is no `dy` and [push] is never
     * called. Reading each tracked row's own `top` once per frame is the only way to see it.
     *
     * For a row whose layout moved by `delta` its visual position is `top + translationY`, so
     * keeping the visual where it is means `lag -= delta`; the spring then carries the row to its
     * new home, which *is* the background line's extra height. The whole line change stays one
     * monotone up move whose end point already contains the grow.
     *
     * Three refusals keep this from cancelling the host instead of helping it — each of them is a
     * "follow the layout, do not fight it", never a "hold the row where it is":
     *
     *  * a shift **every** tracked row takes part in is the *block* moving (a scroll, a re-anchor,
     *    the host's own animation of the line change). Compensating it glues the rows to the screen
     *    while the layout runs away underneath — the line change then does not happen at all, and
     *    the residual grows by every frame the host animates;
     *  * a shift faster than [LyricLineSpringPolicy.MAX_COMPENSABLE_SHIFT_PX] is not a line growing
     *    (1626: a growing line is 4-6 px per frame, the host's own animation 33-102 px), so it is
     *    followed;
     *  * a row that already took [LyricLineSpringPolicy.MAX_COMPENSATION_PX] worth in this line
     *    change has had its background line; anything more is the host moving the block.
     *
     * A `move` synchronises the same expectation ([Member.layoutTop] is the move's `toY`), so a
     * layout that announced itself is never counted twice.
     */
    private fun absorbLayoutShifts() {
        if (members.isEmpty()) return
        val live = ArrayList<Member<H>>()
        val deltas = ArrayList<Int>()
        members.forEach { member ->
            if (member.finished) return@forEach
            val top = layoutTop(member.holder) ?: return@forEach
            live += member
            deltas += top - member.layoutTop
            // The baseline moves whether or not this frame is folded in: the layout really is there,
            // and a row we refuse to compensate must not see the same delta again next frame.
            member.layoutTop = top
        }
        var shifted = 0
        deltas.forEach { delta -> if (delta != 0) shifted += 1 }
        if (shifted == 0) return
        // Every tracked row moved: the *block* moved (a scroll, a re-anchor, the host's own animation
        // of the line change). A background line growing moves only the rows below it, so at least
        // one tracked row — the one above it — stays put. A single tracked row cannot tell the two
        // apart and keeps the growth reading.
        val blockMoved = live.size >= 2 && shifted == live.size
        // One line per frame, not one per row: ten rows refusing the same shift are one fact about
        // the frame, and the device log's line budget is needed for the takeovers.
        var announced = false
        live.forEachIndexed { index, member ->
            val delta = deltas[index]
            if (delta == 0) return@forEachIndexed
            val skip = when {
                blockMoved -> LyricLineShiftSkip.BLOCK
                !LyricLineSpringPolicy.acceptsLayoutShift(delta) -> LyricLineShiftSkip.TOO_FAST
                !LyricLineSpringPolicy.acceptsCompensation(member.compensatedPx, delta) ->
                    LyricLineShiftSkip.CAPPED
                else -> null
            }
            if (skip != null) {
                if (!announced) {
                    onShiftSkipped?.invoke(member.holder, delta, skip)
                    announced = true
                }
                return@forEachIndexed
            }
            member.compensatedPx += abs(delta).toFloat()
            val lag = member.spring.offsetAt(member.elapsedMs) - delta.toFloat()
            val velocity = towardTarget(member.spring.velocityAt(member.elapsedMs), lag)
            member.spring = member.spring.relaunch(lag, velocity)
            member.deadlineMs = member.spring.deadlineMs()
            member.elapsedMs = 0f
            write(member.holder, lag)
            onLayoutShift?.invoke(member.holder, delta, lag)
        }
    }

    /**
     * The part of [velocity] that still points at a residual of [lagPx].
     *
     * A spring travelling home has `velocity * lag < 0` (a positive lag has to shrink, and the other
     * way round). Anything else — a correction that moved the end point past the row, a sign flip —
     * would keep the row moving *away* from its new home and overshoot it, which is exactly the
     * "pushed down and then bounced back" look. Such a velocity is dropped instead of carried.
     */
    private fun towardTarget(velocity: Float, lagPx: Float): Float =
        if (velocity.isFinite() && velocity * lagPx < 0f) velocity else 0f

    /**
     * Start watching whether the push that just landed actually moves [member].
     *
     * A push writes the row's start position, so the question the device asks — "this line change
     * did nothing, why?" — is refused at the only moment it can be answered honestly: after the
     * row's own stagger slot has expired and it has had [SKIP_WINDOW_MS] of real spring time. A
     * sub-pixel push is not watched: it is not supposed to move anything.
     */
    private fun armWatch(member: Member<H>, lagPx: Float) {
        if (onSkipped == null) return
        member.watching = abs(lagPx) >= SKIP_MIN_PX
        member.watchTop = member.layoutTop
        member.watchLag = lagPx
        member.watchedMs = 0f
    }

    /** Count real spring time for [member] and report a line change that never moved it. */
    private fun watch(member: Member<H>, offsetPx: Float, step: Float) {
        val probe = onSkipped ?: return
        if (!member.watching) return
        member.watchedMs += step
        if (member.watchedMs < SKIP_WINDOW_MS) return
        member.watching = false
        val top = layoutTop(member.holder) ?: return
        // The displayed position the wave itself asked for: its layout top plus the lag it wrote.
        val movedPx = abs((top - member.watchTop).toFloat() + (offsetPx - member.watchLag))
        if (movedPx >= SKIP_MIN_PX) return
        probe(member.holder, movedPx, offsetPx, member.watchedMs)
    }

    /**
     * (Re)start the [STUCK_WINDOW_MS] window that asks whether a pushed batch moved at all.
     *
     * The window is spring time, not wall time: it only advances while frames arrive, so a clock
     * that stops ticking reports nothing here (that is the host's `tick` line's job) instead of
     * calling every row stuck. A sub-pixel push is not watched — nothing was asked to move.
     */
    private fun armBatch(member: Member<H>, lagPx: Float) {
        if (onStuck == null) return
        if (abs(lagPx) < SKIP_MIN_PX) return
        member.batchWatching = true
        member.batchLag = lagPx
        watchingBatch = true
        batchMs = 0f
    }

    /**
     * Ask, once per pushed batch, whether the rows it wrote have actually moved.
     *
     * [STUCK_WINDOW_MS] of real spring time after that batch's last push, every row of it whose
     * `translationY` is still within a pixel of what the push wrote is reported in one line: the
     * row is being held against its own spring. Rows still inside a stagger slot or an interlude
     * hold are not counted (they were never asked to move yet), and rows that arrived are already
     * out of the wave.
     */
    private fun watchBatch(step: Float) {
        val probe = onStuck ?: return
        if (!watchingBatch) return
        batchMs += step
        if (batchMs < STUCK_WINDOW_MS) return
        watchingBatch = false
        var stuck = 0
        var maxLag = 0f
        members.forEach { member ->
            if (!member.batchWatching) return@forEach
            member.batchWatching = false
            if (member.finished) return@forEach
            if (member.holdMs > 0f || member.delayMs > 0f) return@forEach
            val lag = member.spring.offsetAt(member.elapsedMs)
            if (abs(lag - member.batchLag) >= SKIP_MIN_PX) return@forEach
            stuck += 1
            if (abs(lag) > maxLag) maxLag = abs(lag)
        }
        if (stuck > 0) probe(stuck, maxLag, batchMs)
    }

    /**
     * Give this frame's line change its AMLL delays.
     *
     * Ranked by post-layout top, so the wave always starts at the visual top of the batch and
     * tightens from the focus line down. A row whose delay is 0 starts on the next frame's step,
     * exactly as 乙-1's rows did.
     */
    private fun rank() {
        val tops = unranked.map { it.layoutTop }.sorted()
        val focus = LyricLineStaggerPolicy.focusRank(tops, paneHeightPx)
        unranked.forEach { member ->
            val rank = tops.indexOf(member.layoutTop).coerceAtLeast(0)
            member.delayMs = LyricLineStaggerPolicy.delayMs(rank, focus)
            member.elapsedMs = 0f
        }
        unranked.clear()
    }

    private companion object {
        /**
         * How much real spring time a pushed row gets to show movement before it is reported.
         *
         * 200 ms is twelve frames at 60 Hz: a line change whose spring has not moved the row a
         * single pixel by then is not a slow start, it is a swallowed move.
         */
        const val SKIP_WINDOW_MS: Float = 200f

        /** Below one physical pixel there is nothing to see. */
        const val SKIP_MIN_PX: Float = 1f

        /**
         * How much real spring time a pushed batch gets before it is asked whether it moved.
         *
         * 400 ms is longer than the whole AMLL stagger (8 rows × 50 ms) plus a fast song's retarget,
         * so every row of the batch has had its slot and a visible stretch of its own spring: a row
         * still sitting on the value the push wrote is being held, not merely queued.
         */
        const val STUCK_WINDOW_MS: Float = 400f
    }
}

/**
 * What a wave's rows are doing right now — the numbers behind the host's frame heartbeat.
 *
 * Read-only and pure data: no clock, no view, no host. [rows] is what the wave still owns,
 * [moving] the rows stepping a spring this frame, [waiting] the rows parked on a stagger slot or an
 * interlude hold, [maxLagPx] the largest residual still owed and [delays] every unfinished row's
 * remaining wait in join order.
 *
 * A tablet's two panes own two waves; [plus] folds them into the one line a device log has room
 * for, and [EMPTY] is its identity.
 */
class LyricLineSpringStatus(
    /** Rows the wave still owns; a row that has arrived is not counted. */
    val rows: Int,
    /** Rows actually stepping their spring right now. */
    val moving: Int,
    /** Rows parked: a stagger slot or an interlude hold has not expired yet. */
    val waiting: Int,
    /** The largest residual any of those rows is still showing, in pixels. */
    val maxLagPx: Float,
    /** Every unfinished row's remaining wait, in ms, in the order the rows joined the wave. */
    val delays: List<Float>,
) {
    /** Fold two waves (or a tablet's two panes) into one snapshot. */
    operator fun plus(other: LyricLineSpringStatus): LyricLineSpringStatus = LyricLineSpringStatus(
        rows = rows + other.rows,
        moving = moving + other.moving,
        waiting = waiting + other.waiting,
        maxLagPx = maxOf(maxLagPx, other.maxLagPx),
        delays = delays + other.delays,
    )

    companion object {
        /** "Nothing is held anywhere", the identity of [plus]. */
        val EMPTY: LyricLineSpringStatus = LyricLineSpringStatus(0, 0, 0, 0f, emptyList())
    }
}

/**
 * Why one per-frame layout shift was **not** folded into a row's residual.
 *
 * Every one of these means "the host moved the layout and the row must follow it". The reason is
 * carried out to the host's diagnostics so a device log can tell a growing background line (which
 * must be folded in) from the host's own movement (which must not).
 */
enum class LyricLineShiftSkip {
    /** Every tracked row moved: a scroll, a re-anchor, or the host animating the line change. */
    BLOCK,

    /** Faster than a background line can grow — the host's own movement of the block. */
    TOO_FAST,

    /** This row already had one line change's worth of compensation. */
    CAPPED,
}

/**
 * What one [LyricLineSpringWave.push] did, for the hook's diagnostics and for tests.
 *
 * [layoutDeltaPx] is the one number that separates "target correction" from "second impulse": the
 * host's move already folded the row's own translation into `dy`, so the layout moved by exactly
 * [previousLagPx] - [lagPx] and the visual position did not move at all. The end point moved by the
 * same amount, which is what makes the whole line change one monotone travel instead of a push
 * followed by a bounce.
 */
class LyricLineSpringPush(
    /** The residual that was written, i.e. the row's `translationY` right after this move. */
    val lagPx: Float,
    /** The residual the row was showing before this move (0 for a row this wave did not hold). */
    val previousLagPx: Float,
    /** The velocity the row was travelling at, as offered to the new spring. */
    val offeredVelocity: Float,
    /** The velocity the new spring actually started with; 0 when it was dropped by the gate. */
    val keptVelocity: Float,
    /**
     * True when this push corrected a row that was already travelling.
     *
     * This is also the start/finish gate: a row is *started* (the host's `dispatchMoveStarting`,
     * i.e. what RecyclerView's move listener counts) only when [moving] is false, and the single
     * [finish] it then owes is dispatched when the row really comes to rest. A correction re-aims
     * that one move, so it dispatches neither.
     */
    val moving: Boolean,
    /** True when the row was still inside its stagger slot and kept it. */
    val waiting: Boolean,
    /** How long the row has to wait for an interlude exit before its stagger starts, in ms. */
    val heldMs: Float = 0f,
    /**
     * True when the correction was smaller than [LyricLineSpringPolicy.MIN_VISIBLE_DELTA_PX] and was
     * merged into the running spring instead of re-aiming it: nothing the eye could resolve, so the
     * row keeps its clock and its velocity and the caller only logs the merge.
     */
    val merged: Boolean = false,
    /** True when a reversal dropped a stagger slot the row was still waiting in. */
    val delayDropped: Boolean = false,
) {
    /** How far the row's layout moved under this push; the visual position does not move. */
    val layoutDeltaPx: Float get() = previousLagPx - lagPx

    /** True when a travelling row had its velocity dropped because it no longer aimed home. */
    val velocityDropped: Boolean get() = moving && !merged && offeredVelocity != 0f && keptVelocity == 0f
}
