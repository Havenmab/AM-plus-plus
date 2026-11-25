package dev.amenhancer.module.lyrics

/**
 * One batch of rows moved by one shared scalar spring, with RecyclerView's finish contract.
 *
 * Apple Music animates a line change by moving 4–8 visible rows by the *same* `dy`; the whole
 * block has to stay rigid, so the batch advances one scalar and writes it to every row. The
 * bookkeeping that matters is [finish]: RecyclerView's pending-move ledger accepts exactly one
 * "this animation is over" per start, and a second one throws. Every path that can end a row's
 * animation (the spring coming to rest, `endAnimation(holder)`, `endAnimations()`) therefore goes
 * through the row's own `finished` flag.
 *
 * Pure: it never touches a View, a clock, or the host. The caller supplies [write] (pixels) and
 * [finish] (the host's `dispatchAnimationFinished`), which is what makes the exactly-once rule
 * testable with a fake holder.
 */
class LyricLineSpringBatch<H : Any>(
    spring: LyricLineSpring,
    private val write: (H, Float) -> Unit,
    private val finish: (H) -> Unit,
) {
    private class Member<H>(val holder: H) {
        var finished = false
    }

    private val members = ArrayList<Member<H>>()
    private var elapsedMs = 0f

    /** The shared spring, one per `dy`; the whole batch is written from it. */
    val spring: LyricLineSpring = spring

    /** Milliseconds of clamped frame time this batch has been advanced by. */
    val elapsed: Float get() = elapsedMs

    /** Rows still owned by this batch, finished or not. */
    val size: Int get() = members.size

    /** True while at least one row still owns a displacement. */
    val isRunning: Boolean get() = members.any { !it.finished }

    /** The rows this batch currently holds, in the order they joined. */
    fun holders(): List<H> = members.map { it.holder }

    /** True when [holder] is one of this batch's rows (finished or not). */
    fun contains(holder: H): Boolean = members.any { it.holder === holder }

    /** True when [holder] belongs to this batch and has not been finished yet. */
    fun isPending(holder: H): Boolean = members.any { it.holder === holder && !it.finished }

    /** The offset most recently computed for this batch. */
    fun currentOffset(): Float = spring.offsetAt(elapsedMs)

    /** The velocity the batch is currently travelling at, in pixels per second. */
    fun currentVelocity(): Float = spring.velocityAt(elapsedMs)

    /**
     * Add [holder] and write its starting offset.
     *
     * A row that is already in this batch is simply re-written; it never loses its one-and-only
     * [finish] to a duplicate join.
     */
    fun join(holder: H) {
        val existing = members.firstOrNull { it.holder === holder }
        if (existing != null) {
            // A re-join is a new move for that row (the host started a new animation for it), so it
            // owns a fresh "finished" from here: revive the flag and re-write the offset.
            existing.finished = false
            write(holder, spring.offsetAt(elapsedMs))
            return
        }
        members += Member(holder)
        write(holder, spring.offsetAt(elapsedMs))
    }

    /**
     * Advance the shared clock by [frameStepMs] and write the new offset to every row.
     *
     * Returns true while rows remain. When the spring is at rest the batch snaps every row to 0 and
     * emits each row's single [finish] before returning false.
     */
    fun advance(frameStepMs: Float): Boolean {
        elapsedMs += LyricLineSpringPolicy.frameStep(frameStepMs)
        val offset = spring.offsetAt(elapsedMs)
        if (spring.isAtRest(elapsedMs, offset)) {
            finishAll()
            return false
        }
        members.forEach { member ->
            if (!member.finished) write(member.holder, offset)
        }
        return isRunning
    }

    /**
     * End [holder]'s animation now, exactly once.
     *
     * [resetOffset] writes 0 first — which is what a recycled row, a cancelled animation and the
     * end of a settled spring all need. A re-target passes false: the row's layout already jumped,
     * and the new batch writes the new offset in the same host call, so zeroing here would flash
     * the old position.
     */
    fun finishHolder(holder: H, resetOffset: Boolean = true): Boolean {
        val member = members.firstOrNull { it.holder === holder } ?: return false
        if (member.finished) return false
        member.finished = true
        if (resetOffset) write(holder, 0f)
        finish(holder)
        return true
    }

    /** End every row of this batch, each exactly once, at offset 0. */
    fun finishAll() {
        members.forEach { member ->
            if (!member.finished) {
                member.finished = true
                write(member.holder, 0f)
                finish(member.holder)
            }
        }
    }

    /** Drop finished rows so a long-lived batch does not grow. */
    fun prune() {
        members.removeAll { it.finished }
    }
}
