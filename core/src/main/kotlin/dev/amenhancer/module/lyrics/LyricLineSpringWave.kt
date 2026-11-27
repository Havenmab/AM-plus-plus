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
    )

    private val members = ArrayList<Member<H>>()

    /** Rows pushed since the last frame — one line change, waiting to be ranked. */
    private val unranked = ArrayList<Member<H>>()

    /** Rows currently owned by this wave, finished or not. */
    val size: Int get() = members.size

    /** True while at least one row still owes a displacement (a start delay counts). */
    val isRunning: Boolean get() = members.any { !it.finished }

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
        write(holder, lagPx)
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
        write(holder, lagPx)
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
     * Two refusals keep this from doing damage:
     *
     *  * a shift that **every** tracked row takes part in is a scroll or a re-anchor, not a row
     *    growing — compensating it would glue the block to the screen under the user's finger. Those
     *    only re-baseline;
     *  * a shift beyond [LyricLineSpringPolicy.MAX_LAYOUT_SHIFT_PX] is a recycled row, a track
     *    change or a jump, likewise re-baselined only.
     *
     * A `move` synchronises the same expectation ([Member.layoutTop] is the move's `toY`), so a
     * layout that announced itself is never counted twice.
     */
    private fun absorbLayoutShifts() {
        var live = 0
        var shifted = 0
        var first = 0
        var uniform = true
        members.forEach { member ->
            if (member.finished) return@forEach
            val top = layoutTop(member.holder) ?: return@forEach
            live += 1
            val delta = top - member.layoutTop
            if (delta == 0) return@forEach
            shifted += 1
            if (shifted == 1) first = delta else if (delta != first) uniform = false
        }
        if (shifted == 0) return
        // A shift every tracked row takes part in is a scroll or a re-anchor; a growth of one row's
        // background line moves only the rows below it, which is the case worth absorbing.
        val absorb = live <= 1 || shifted < live || !uniform
        members.forEach { member ->
            if (member.finished) return@forEach
            val top = layoutTop(member.holder) ?: return@forEach
            val delta = top - member.layoutTop
            if (delta == 0) return@forEach
            member.layoutTop = top
            if (!absorb || !LyricLineSpringPolicy.acceptsLayoutShift(delta)) return@forEach
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
