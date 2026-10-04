package dev.amenhancer.module.hook

import dev.amenhancer.module.lyrics.TtmlInputPolicy

/**
 * The single decision point for the online-search timing relaxation.
 *
 * Search-based sources such as Kuwo only provide whole-line timings in their
 * plain-LRC fallback, so the search path may accept line-level TTML while the
 * fixed providers (AMLL / LunaBeat / am-lyrics) keep their Word-only
 * requirement. Structural validation is never bypassed: every entry point still
 * runs [TtmlInputPolicy.isAcceptable].
 *
 * [SEARCH_ACCEPTS_LINE_TIMING] is the one switch the whole relaxation hangs on.
 * Flip it back to `false` to restore Word-only filtering at every seam at once
 * if on-device testing shows Apple's native parser rejects line-timed
 * documents.
 */
object AutoLyricsTimingPolicy {

    /** The single switch: search-based sources may carry whole-line timing. */
    const val SEARCH_ACCEPTS_LINE_TIMING: Boolean = true

    /**
     * Per-source decision applied by the resolver before a candidate leaves the
     * source chain. A source that opts into line timing is accepted only while
     * the switch is on; Word timing stays acceptable everywhere.
     */
    fun isAcceptable(ttml: String, sourceAcceptsLineTiming: Boolean): Boolean =
        TtmlInputPolicy.isAcceptable(ttml) &&
            (TtmlTimingPolicy.isWord(ttml) ||
                (SEARCH_ACCEPTS_LINE_TIMING && sourceAcceptsLineTiming))

    /**
     * Downstream seam decision (pointer preparation, cache write and cache
     * rehydration). Anything reaching a seam already cleared the resolver's
     * per-source decision, so the seam re-checks the structure and the one
     * switch rather than duplicating the source classification. With the switch
     * off this is exactly the previous Word-only requirement.
     */
    fun isAcceptableAtSeam(ttml: String): Boolean =
        TtmlInputPolicy.isAcceptable(ttml) &&
            (TtmlTimingPolicy.isWord(ttml) || SEARCH_ACCEPTS_LINE_TIMING)
}
