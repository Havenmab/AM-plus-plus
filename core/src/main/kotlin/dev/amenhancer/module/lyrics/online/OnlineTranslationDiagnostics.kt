package dev.amenhancer.module.lyrics.online

import dev.amenhancer.module.hook.TtmlDocumentMetadata

/**
 * Stable, greppable tokens for where the translation pass stopped. They are the
 * output contract of the device-facing log, so an exported LSPosed log answers
 * "where did it stop" without a rebuild.
 */
enum class OnlineTranslationReason(val token: String) {
    PROCEED("proceed"),
    NO_CANDIDATES("no_candidates"),
    EMPTY_APPLE_DOCUMENT("empty_apple_document"),
    APPLE_ALREADY_TRANSLATED("apple_already_translated"),
    FULLY_CHINESE("fully_chinese"),
    NO_LINE_NEEDS_TRANSLATION("no_line_needs_translation"),
    NO_CONTRIBUTING_CANDIDATE("no_contributing_candidate"),
    NO_MEANINGFUL_MERGED_LINE("no_meaningful_merged_line"),
    FAILED("failed"),
}

/**
 * The one pure explanation of the pre-decision, in the exact order the
 * enrichment checks it. Returns [OnlineTranslationReason.PROCEED] when the pass
 * may continue to matching; every other value is the sub-condition that
 * rejected the document. Pure over plain data, so the reason tokens are covered
 * by JVM tests instead of by reading a device log, and the caller's decision
 * and its diagnostic can never disagree.
 */
object OnlineTranslationGate {

    fun firstBlocker(
        candidateCount: Int,
        baseLineCount: Int,
        document: TtmlDocumentMetadata,
        lines: List<NativeLyricLine>,
        translationRequested: Boolean = true,
    ): OnlineTranslationReason = when {
        candidateCount == 0 -> OnlineTranslationReason.NO_CANDIDATES
        baseLineCount == 0 -> OnlineTranslationReason.EMPTY_APPLE_DOCUMENT
        !translationRequested -> OnlineTranslationReason.NO_LINE_NEEDS_TRANSLATION
        document.hasTranslation -> OnlineTranslationReason.APPLE_ALREADY_TRANSLATED
        ChineseLyricsPolicy.isFullyChinese(lines) -> OnlineTranslationReason.FULLY_CHINESE
        !lines.any { line ->
            !line.text.isNullOrBlank() &&
                !OnlineTranslationContentPolicy.isMeaningful(line.translation)
        } -> OnlineTranslationReason.NO_LINE_NEEDS_TRANSLATION
        else -> OnlineTranslationReason.PROCEED
    }
}

/**
 * Bounds the per-track diagnostics: a track change resets the budget, and at
 * most [maxLinesPerTrack] lines are emitted for the same track however many
 * times the enricher is retried. Keeps a retry loop from spamming the exported
 * LSPosed log while still recording one full decision pass per song.
 */
class TrackScopedDiagnostics(
    private val logger: (String) -> Unit,
    private val maxLinesPerTrack: Int = DEFAULT_MAX_LINES_PER_TRACK,
) {
    private var currentId: Long? = null
    private var emitted = 0

    fun log(appleMusicId: Long, line: String) {
        if (currentId != appleMusicId) {
            currentId = appleMusicId
            emitted = 0
        }
        if (emitted >= maxLinesPerTrack) return
        emitted++
        runCatching { logger(line) }
    }

    companion object {
        /**
         * Enough for one full pass: the parse capture and its I2 association,
         * the per-track capture verdict, the search query and local identity,
         * one line per provider search, the per-candidate score breakdowns (up
         * to [OnlineMatchDiagnostics.MAX_LOGGED_CANDIDATES] per provider), the
         * per-candidate fetch verdict, the gate decision, the selector outcome
         * and the publish verdict — still bounded per track.
         */
        const val DEFAULT_MAX_LINES_PER_TRACK = 24
    }
}
