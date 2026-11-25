package dev.amenhancer.module.lyrics

/**
 * Arithmetic behind the read-only 「换行诊断」 probe's periodic summary line.
 *
 * The probe exists to answer four questions about Apple Music 7.0.3's own lyric line change
 * before anything in this module tries to take it over:
 *
 *  1. does every line change produce at least one `animateMove` (`movesPerChange` 1–2)?
 *  2. is that move always a **zero-displacement** one ([zeroDelta]), i.e. is the row animation
 *     really only the host's delayed transition and never a real slide?
 *  3. how long does the host keep the row transition deferred ([flushMeanMs])?
 *  4. does the row-transition entry point also see text rows ([textRowTransitions])?
 *
 * Deliberately a plain accumulator with no formatting and no Android import: the host probe owns
 * the log line, this object owns the numbers, and both halves are unit-testable in :core.
 *
 * One instance covers one summary window; the host reads [snapshot] and calls [reset] when it
 * emits. Counts are monotonic within a window and never negative, so a window with no data at
 * all still prints zeros instead of failing.
 */
class LyricLineProbeSummary {
    private var changes = 0
    private var sentinelChanges = 0
    private var moves = 0
    private var zeroDeltaMoves = 0
    private var rowTransitions = 0
    private var textRowTransitions = 0
    private var flushSamples = 0
    private var flushTotalMs = 0L
    private var flushMaxMs = 0L

    /**
     * One line-change callback.
     *
     * [sentinel] is the host's own "no current line" argument (-1): it suppresses the re-anchor,
     * so it is counted apart from real changes and never enters `movesPerChange`.
     */
    fun lineChange(sentinel: Boolean) {
        if (sentinel) sentinelChanges += 1 else changes += 1
    }

    /** One `animateMove` call, whether the host animated it or finished it immediately. */
    fun move(zeroDelta: Boolean) {
        moves += 1
        if (zeroDelta) zeroDeltaMoves += 1
    }

    /** One row-transition entry point hit; [instrumental] is whether it passed the host's own gate. */
    fun rowTransition(instrumental: Boolean) {
        rowTransitions += 1
        if (!instrumental) textRowTransitions += 1
    }

    /** One deferred-queue flush, delayed by [delayMs] after the line change that queued it. */
    fun flush(delayMs: Long) {
        if (delayMs < 0L) return
        flushSamples += 1
        flushTotalMs += delayMs
        if (delayMs > flushMaxMs) flushMaxMs = delayMs
    }

    fun snapshot(): Snapshot = Snapshot(
        changes = changes,
        sentinelChanges = sentinelChanges,
        moves = moves,
        zeroDeltaMoves = zeroDeltaMoves,
        rowTransitions = rowTransitions,
        textRowTransitions = textRowTransitions,
        flushSamples = flushSamples,
        flushMeanMs = if (flushSamples == 0) null else flushTotalMs / flushSamples,
        flushMaxMs = if (flushSamples == 0) null else flushMaxMs,
    )

    fun reset() {
        changes = 0
        sentinelChanges = 0
        moves = 0
        zeroDeltaMoves = 0
        rowTransitions = 0
        textRowTransitions = 0
        flushSamples = 0
        flushTotalMs = 0L
        flushMaxMs = 0L
    }

    /** One window's totals. Every derived value is defined for an empty window too. */
    data class Snapshot(
        val changes: Int,
        val sentinelChanges: Int,
        val moves: Int,
        val zeroDeltaMoves: Int,
        val rowTransitions: Int,
        val textRowTransitions: Int,
        val flushSamples: Int,
        val flushMeanMs: Long?,
        val flushMaxMs: Long?,
    ) {
        /** Moves per real line change: Apple's own list animates the outgoing and the new row. */
        val movesPerChange: Float = if (changes == 0) 0f else moves.toFloat() / changes

        /**
         * True only when every observed move was a zero-displacement one.
         *
         * A window with no move at all is not evidence either way, so it reports false and the
         * readme asks for a window with `moves>0` before drawing a conclusion.
         */
        val zeroDelta: Boolean = moves > 0 && zeroDeltaMoves == moves

        /** True when the row-transition entry point only ever served instrumental rows. */
        val instrumentalOnly: Boolean = rowTransitions > 0 && textRowTransitions == 0
    }
}
