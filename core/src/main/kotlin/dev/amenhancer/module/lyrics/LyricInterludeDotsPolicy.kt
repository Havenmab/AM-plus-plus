package dev.amenhancer.module.lyrics

import dev.amenhancer.module.lyrics.LyricInterludeDotsPolicy.RETIRE_MS

/**
 * 「间奏三圆点」: Apple Music's three-dot interlude indicator and the one thing this module does
 * to it — ending it at a line change instead of letting it ride the outgoing row.
 *
 * **What 1626 actually does with the dots** (DEX below; see
 * `line-transition-notes/10-interlude-dots.md`). The dots live inside the interlude row's own
 * layout (`res/layout/lyrics_line_instrumental.xml`: three `ImageView`s `dot1`/`dot2`/`dot3`,
 * bound by `q8.m9.X/Y/Z`). They have **no exit animation**: the only live path is
 * `A#Y(q8.m9,ZZ)` / `V0#U(q8.m9,Z)` — a performance of one `ValueAnimator` per dot that sweeps
 * its tint colour across the interlude's remaining duration, plus a 4 s repeating scale pulse on
 * the row root — armed from `onBindViewHolder` (`A#l` / `V0#l`) and re-synced on every settled
 * zero-displacement move (`A#H` / `V0#H`). Nothing ever hides the dots: they stay at full alpha
 * until `onViewRecycled` (`A#s` / `V0#s`) cancels the animation, which happens only once the row
 * has left the list. (The two methods that *would* have faded them — `A#N`/`V0#N`, "grow 750 ms,
 * then shrink + fade 250 ms", and `A#G`/`V0#G`, a 750 ms staggered fade-in — are dead in this
 * build: their only callers `PlayerLyricsViewFragment$u#s` / `#r` are referenced from nowhere,
 * and the device probe's `rowAnim` has never fired.) So the performance outlives the interlude and
 * is still running while the incoming line is already being moved onto the anchor.
 *
 * **What this policy decides.** Only *which* of the host's own states a row's dots should be in,
 * and how a retiring row leaves: at a line change, an instrumental row that is now **behind** the
 * current line has its dots fade out over [RETIRE_MS] — shorter than the wave's settle time, so
 * they are gone before the row arrives — and the dots of the row that *is* current are (re)armed,
 * which is what keeps the next interlude's appearance exactly as the host wrote it. Rows ahead of
 * the current line are never touched.
 *
 * Everything here is pure: no `View`, no clock of its own (callers pass `nowMs`), no host state.
 */
enum class LyricInterludeDotsAction {
    /** Nothing belongs to this row in this pass. */
    NONE,

    /** The row is the current line: its dots must be visible (this is the next interlude). */
    ARM,

    /** The interlude is over: fade the dots out now, do not wait for the row to be recycled. */
    RETIRE,
}

/** The host's own interlude states, as this module tracks them per row. */
enum class LyricInterludeDotsPhase {
    /** Nothing has been decided for this row yet. */
    IDLE,

    /** The dots are visible; the host's own performance may or may not still be running. */
    SHOWING,

    /** The dots are fading out after their interlude stopped being the current line. */
    RETIRING,

    /** The dots have no pixels left. */
    HIDDEN,
}

object LyricInterludeDotsPolicy {
    /**
     * How long the dots take to leave once their interlude stops being the current line.
     *
     * Chosen to be *far* shorter than the wave: AMLL's spring settles in
     * [LyricLineSpringPolicy.AMLL_SETTLE_MS] (450 ms), and the block does not start moving before
     * the first frame, so a [RETIRE_MS]-long fade has the row empty well before the incoming line
     * reaches the anchor. `LyricInterludeDotsPolicyTest` pins that relation, so a future change to
     * either side cannot silently break the promise. The fade exists (instead of an instant
     * `alpha = 0`) because the dots' disappearance is part of what the interlude looks like.
     */
    const val RETIRE_MS: Long = 120L

    /** `RecyclerView.NO_POSITION`; also what a holder reports before its first layout. */
    const val NO_POSITION: Int = -1

    /**
     * What a line change at [currentLine] means for one row sitting at [position].
     *
     * The lyric list is ordered by time, so "position < current line" is exactly "this interlude
     * has already been sung past" and "== current line" is "this is the interlude on screen now".
     * An unknown index on either side is a no-op: a seek, a track change and a holder that has not
     * been laid out yet all land here, and none of them may touch the dots.
     */
    fun actionFor(position: Int, currentLine: Int): LyricInterludeDotsAction {
        if (position < 0 || currentLine < 0) return LyricInterludeDotsAction.NONE
        return when {
            position < currentLine -> LyricInterludeDotsAction.RETIRE
            position == currentLine -> LyricInterludeDotsAction.ARM
            else -> LyricInterludeDotsAction.NONE
        }
    }

    /**
     * The alpha of a retiring row [elapsedMs] into its fade: 1 → 0, linear, exactly 0 at the end.
     *
     * "Exactly 0" is the contract that keeps a ghost off the screen: the host never fades these
     * views itself, so whatever this returns is the whole story.
     */
    fun fadeAlpha(elapsedMs: Long, retireMs: Long = RETIRE_MS): Float {
        if (retireMs <= 0L) return 0f
        if (elapsedMs <= 0L) return 1f
        if (elapsedMs >= retireMs) return 0f
        return 1f - elapsedMs.toFloat() / retireMs.toFloat()
    }

    /**
     * True when a [RETIRE_MS] fade finishes before a wave that settles in [waveSettleMs].
     *
     * The whole point of the retire is ordering: the dots are gone *before* the incoming line
     * arrives. Both numbers are this repository's own constants, so the test can hold the relation
     * without a device.
     */
    fun retiresBeforeArrival(waveSettleMs: Float, retireMs: Long = RETIRE_MS): Boolean =
        retireMs > 0L && retireMs.toFloat() < waveSettleMs
}

/**
 * One row's dots through arm → retire → gone.
 *
 * The transitions are deliberately boring, because the cases that matter are the ones where a
 * naive implementation gets it wrong:
 *
 *  * **arm while retiring** (the user seeks back into an interlude, or a burst of line changes
 *    lands inside one animation frame): the fade is cancelled and the dots are back at full alpha
 *    immediately — a half-faded interlude that is the current line again must not stay half-faded;
 *  * **retire twice**: the second call keeps the *first* deadline. Extending it would make the
 *    dots outlive the row, which is the bug this class exists to remove;
 *  * **retire from IDLE** (a row this track has never seen, whose dots are therefore at whatever
 *    the host bound them with — full alpha): it starts a real fade, because those dots are on
 *    screen right now;
 *  * **retire from HIDDEN**: nothing to fade, and nothing to report.
 */
class LyricInterludeDots(
    private val retireMs: Long = RETIRE_MS,
) {
    var phase: LyricInterludeDotsPhase = LyricInterludeDotsPhase.IDLE
        private set

    private var retireStartedMs: Long = 0L
    private var retireEndsMs: Long = 0L

    /** Make the dots visible now; cancels a fade in progress. Returns the action to perform. */
    fun arm(nowMs: Long): LyricInterludeDotsAction {
        phase = LyricInterludeDotsPhase.SHOWING
        retireStartedMs = 0L
        retireEndsMs = 0L
        return LyricInterludeDotsAction.ARM
    }

    /** Start (or keep) the fade. Returns [LyricInterludeDotsAction.RETIRE], or NONE when hidden. */
    fun retire(nowMs: Long): LyricInterludeDotsAction {
        when (phase) {
            LyricInterludeDotsPhase.SHOWING, LyricInterludeDotsPhase.IDLE -> {
                phase = LyricInterludeDotsPhase.RETIRING
                retireStartedMs = nowMs
                retireEndsMs = nowMs + retireMs
                return LyricInterludeDotsAction.RETIRE
            }
            LyricInterludeDotsPhase.RETIRING -> return LyricInterludeDotsAction.NONE
            LyricInterludeDotsPhase.HIDDEN -> return LyricInterludeDotsAction.NONE
        }
    }

    /** Skip the rest of the fade and report the dots as gone (used when a fade cannot run). */
    fun retireImmediately(): LyricInterludeDotsAction {
        val wasRetiring = phase == LyricInterludeDotsPhase.RETIRING
        phase = LyricInterludeDotsPhase.HIDDEN
        retireStartedMs = 0L
        retireEndsMs = 0L
        return if (wasRetiring) LyricInterludeDotsAction.RETIRE else LyricInterludeDotsAction.NONE
    }

    /** Advance the bookkeeping; returns true when this call moved the row into HIDDEN. */
    fun settle(nowMs: Long): Boolean {
        if (phase != LyricInterludeDotsPhase.RETIRING) return false
        if (nowMs < retireEndsMs) return false
        phase = LyricInterludeDotsPhase.HIDDEN
        retireStartedMs = 0L
        retireEndsMs = 0L
        return true
    }

    /** The alpha the dots should have at [nowMs]; exactly 0 once hidden. */
    fun alphaAt(nowMs: Long): Float = when (phase) {
        LyricInterludeDotsPhase.SHOWING -> 1f
        LyricInterludeDotsPhase.RETIRING -> LyricInterludeDotsPolicy.fadeAlpha(nowMs - retireStartedMs, retireMs)
        LyricInterludeDotsPhase.IDLE, LyricInterludeDotsPhase.HIDDEN -> 0f
    }

    /** When the current fade ends, or 0 when no fade is in flight. */
    fun retireEndsAtMs(): Long = if (phase == LyricInterludeDotsPhase.RETIRING) retireEndsMs else 0L
}

/** One row as a hook sees it: the holder it belongs to, and that holder's layout position. */
class LyricInterludeDotsRow(val key: Any, val position: Int)

/** What the hook must do to one row's dots after a line change. */
class LyricInterludeDotsChange(
    val key: Any,
    val action: LyricInterludeDotsAction,
    val phase: LyricInterludeDotsPhase,
    val alpha: Float,
)

/**
 * The rows this module has seen, each with its own [LyricInterludeDots].
 *
 * A hook never gets the whole list, it gets one holder at a time (`animateMove`, the adapters'
 * bind and settle callbacks), so the track is keyed by holder identity and bounded: a long song
 * recycles holders, and an unbounded map here would be a leak that grows with playback.
 */
class LyricInterludeDotsTrack(
    private val retireMs: Long = RETIRE_MS,
    private val capacity: Int = 32,
) {
    private val rows = LinkedHashMap<Any, LyricInterludeDots>()

    val size: Int get() = rows.size

    /**
     * Apply one line change verdict to [observed].
     *
     * Returns only the rows whose *visible* state changes, so a caller never touches a view it does
     * not have to. Rows that are still fading are settled here as well, which is what turns a
     * finished fade into [LyricInterludeDotsPhase.HIDDEN] even if nothing else ever looks at it.
     */
    fun observe(
        observed: List<LyricInterludeDotsRow>,
        currentLine: Int,
        nowMs: Long,
    ): List<LyricInterludeDotsChange> {
        if (observed.isEmpty()) return emptyList()
        val changes = ArrayList<LyricInterludeDotsChange>(observed.size)
        for (row in observed) {
            val decision = LyricInterludeDotsPolicy.actionFor(row.position, currentLine)
            val tracked = rows[row.key]
            // A row ahead of the current line has nothing to do here: do not even allocate state
            // for it, so a long song only ever tracks the interludes it actually acted on.
            if (tracked == null && decision == LyricInterludeDotsAction.NONE) continue
            val dots = tracked ?: LyricInterludeDots(retireMs).also { remember(row.key, it) }
            val before = dots.phase
            val alphaBefore = dots.alphaAt(nowMs)
            val applied = when (decision) {
                LyricInterludeDotsAction.ARM -> dots.arm(nowMs)
                LyricInterludeDotsAction.RETIRE -> dots.retire(nowMs)
                LyricInterludeDotsAction.NONE -> LyricInterludeDotsAction.NONE
            }
            dots.settle(nowMs)
            val alphaAfter = dots.alphaAt(nowMs)
            if (before != dots.phase || alphaBefore != alphaAfter) {
                // A row that just became HIDDEN is reported as RETIRE so the caller can put the
                // last alpha = 0 write in, even when the fade that got it there has ended.
                val action = if (dots.phase == LyricInterludeDotsPhase.HIDDEN) {
                    LyricInterludeDotsAction.RETIRE
                } else {
                    applied
                }
                changes += LyricInterludeDotsChange(row.key, action, dots.phase, alphaAfter)
            }
        }
        return changes
    }

    /**
     * Arm one row unconditionally and report the change, if the visible state changes.
     *
     * This is the bind path: a row that was just bound must show its dots no matter where it sits
     * in the list, because that is the row the adapter decided to show. Reported only when
     * something changes, so a fresh row costs nothing.
     */
    fun arm(key: Any, nowMs: Long): LyricInterludeDotsChange? {
        val dots = rows[key] ?: LyricInterludeDots(retireMs).also { remember(key, it) }
        val before = dots.phase
        val alphaBefore = dots.alphaAt(nowMs)
        dots.arm(nowMs)
        val alphaAfter = dots.alphaAt(nowMs)
        if (before == dots.phase && alphaBefore == alphaAfter) return null
        return LyricInterludeDotsChange(key, LyricInterludeDotsAction.ARM, dots.phase, alphaAfter)
    }

    /** Forget one holder — recycled, replaced, or a list that was torn down. */
    fun forget(key: Any) {
        rows.remove(key)
    }

    fun clear() {
        rows.clear()
    }

    /** Test/debug view: the phase this track believes a row is in. */
    fun phaseOf(key: Any): LyricInterludeDotsPhase? = rows[key]?.phase

    private fun remember(key: Any, dots: LyricInterludeDots) {
        while (rows.size >= capacity && rows.isNotEmpty()) {
            val eldest = rows.keys.firstOrNull() ?: break
            rows.remove(eldest)
        }
        rows[key] = dots
    }
}
