package dev.amenhancer.module.lyrics

/**
 * 「间奏三圆点」's exit animation, and how a line change waits for it.
 *
 * **What 1626 really does when an interlude ends** (DEX, see
 * `line-transition-notes/10-interlude-dots.md`). The three dots of
 * `res/layout/lyrics_line_instrumental.xml` are animated once more on the way out: the row root
 * grows to 1.2x over 750 ms, then shrinks to 0.5x while its alpha goes to 0 over 250 ms — i.e.
 * **they grow, shrink and disappear, and only then is the interlude over**. The animation is
 * rebuilt from scratch at every line change by `V0#N(ViewHolder)` / `A#N(ViewHolder)`:
 *
 *     grow     = ObjectAnimator(SCALE_X/Y [current, 1.2])   PathInterpolator(.25,.1,.25,1)   750 ms
 *     shrink   = ObjectAnimator(SCALE_X/Y -> 0.5)                                          250 ms
 *     fade     = ObjectAnimator(ALPHA   -> 0)                                              250 ms
 *     set.play(shrink).with(fade).after(grow)          plus .after(V0.B) when the dot loop is live
 *     set.addListener(V0$b)                            restores scale 1, alpha 1, dot tint
 *
 * `V0$b#onAnimationEnd` / `A$s#onAnimationEnd` is therefore an exact "the interlude is over"
 * signal, and [LyricInterludeExitPolicy.EXIT_MS] is the total when that signal cannot be hooked:
 * 750 + 250. The `after(V0.B)` link does not make it longer — `V0.B` is the last dot's own
 * 750 ms animator, which starts in parallel with `grow`.
 *
 * **Why that matters to the spring.** The exit is started by the *change* animation RecyclerView
 * runs on the row that stopped being current (see `mc.z#onAnimationStart` -> `F#s` ->
 * `$u#s` -> `i1#N`), i.e. one frame **after** the item animator was asked to move the rows.
 * Our wave would otherwise be a third of the way home while the dots are still growing — the
 * overlap this whole exercise is about. [LyricInterludeHold] turns that into a deliberate wait:
 * while it is armed, the wave parks every row of the batch at its start position (visual still,
 * layout already applied) and only then lets AMLL's 50 ms stagger run.
 *
 * Everything here is pure: the caller passes `nowMs`, nothing reads a view or a clock.
 */
object LyricInterludeExitPolicy {
    /** `V0#N` offsets 185-187: the grow is a constant `750` ms. */
    const val GROW_MS: Long = 750L

    /** `V0#N` offsets 228-230: shrink and fade are a constant `250` ms, run together. */
    const val SHRINK_MS: Long = 250L

    /**
     * The whole exit: grow, then shrink + fade.
     *
     * Used when the exit's end callback cannot be installed, and as the upper bound when it can: a
     * hold that somehow never gets its end callback still releases after this.
     */
    const val EXIT_MS: Long = GROW_MS + SHRINK_MS

    /**
     * A start signal older than this is not trusted.
     *
     * The exit is started from a host animation callback that can arrive a frame or two after the
     * layout pass; anything beyond [EXIT_MS] plus half a second is a stale signal from an earlier
     * interlude and must not park a fresh line change.
     */
    const val STALE_MS: Long = EXIT_MS + 500L

    /** How much of a hold that ends at [endsAtMs] is left at [nowMs]; never negative. */
    fun remainingMs(endsAtMs: Long, nowMs: Long): Long =
        if (endsAtMs <= 0L) 0L else (endsAtMs - nowMs).coerceAtLeast(0L)

    /**
     * True when the exit is long enough to be worth waiting for.
     *
     * A three-dot exit shorter than a frame would only add jitter; the 50 ms stair already covers
     * that. Pinned by `LyricInterludeExitPolicyTest`.
     */
    fun isWorthHolding(exitMs: Long = EXIT_MS): Boolean = exitMs >= MIN_HOLD_MS

    /** Below this the exit is a frame's worth of jitter, not something to park a line change for. */
    const val MIN_HOLD_MS: Long = 100L
}

/**
 * The one link between "the interlude's exit animation is running" and "the wave may start".
 *
 * The three events are deliberately asymmetric:
 *
 *  * **start** may arrive more than once (a second line change inside the same exit, two changed
 *    rows, the same row announced again). It never shortens a running hold — only `max` is
 *    applied — so a repeat cannot make the rows start before the dots are gone;
 *  * **finish** is the exit's own `onAnimationEnd`. It releases the hold *immediately*, because
 *    the animation is the truth and [LyricInterludeExitPolicy.EXIT_MS] is only its book value;
 *  * the book value is the fallback: when no end callback ever comes (a role that did not resolve,
 *    an animator cancelled without a listener call), the hold simply expires at the same deadline.
 */
class LyricInterludeHold(
    private val exitMs: Long = LyricInterludeExitPolicy.EXIT_MS,
) {
    private var endsAtMs: Long = 0L
    private var starts = 0

    /** True between [start] and the release. */
    fun isHolding(nowMs: Long): Boolean = remainingMs(nowMs) > 0L

    /** The exit began at [nowMs]; a hold already running is extended, never shortened. */
    fun start(nowMs: Long, durationMs: Long = exitMs) {
        if (!LyricInterludeExitPolicy.isWorthHolding(durationMs)) return
        val candidate = nowMs + durationMs
        if (candidate > endsAtMs) endsAtMs = candidate
        starts += 1
    }

    /** The exit's own end callback: the rows may go now. */
    fun finish(nowMs: Long) {
        if (endsAtMs != 0L) endsAtMs = nowMs
    }

    /** No exit is running (or the feature is off): the wave must not wait at all. */
    fun clear() {
        endsAtMs = 0L
    }

    /** Milliseconds the wave still has to park for. */
    fun remainingMs(nowMs: Long): Long = LyricInterludeExitPolicy.remainingMs(endsAtMs, nowMs)

    /** When the current hold expires; 0 when nothing is held. */
    fun endsAtMs(): Long = endsAtMs

    /** How many start signals this hold has seen; used by diagnostics and tests. */
    fun startCount(): Int = starts
}
