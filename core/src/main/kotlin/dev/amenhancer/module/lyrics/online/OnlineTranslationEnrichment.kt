package dev.amenhancer.module.lyrics.online

import dev.amenhancer.module.hook.TtmlTimingPolicy
import kotlin.math.max

/**
 * Fills a missing translation lane into the document Apple Music is already
 * showing, using lyric bodies the online chain returned.
 *
 * The decision is the ported [OnlineEnrichmentPolicy] one: a document that
 * already carries a translation lane, or a fully-Chinese song, is left alone.
 * Each candidate is then aligned to the displayed lines with
 * [OnlineTranslationMatcher], the sources are ranked with
 * [OnlineTranslationSelector], and the winner is written back through
 * [AppleLyricTtmlWriter] so word timing and layout survive while only the
 * translation lane changes.
 *
 * Every step fails open: a malformed document, a candidate that matches
 * nothing, or no candidate at all returns null and the caller leaves the
 * displayed document untouched.
 */
object OnlineTranslationEnrichment {

    /** The merged document plus which source supplied the winning translation. */
    data class Outcome(
        val ttml: String,
        val source: Source,
        val matchedLines: Int,
        val totalLines: Int,
    )

    fun enrich(
        ttml: String,
        candidates: List<OnlineTranslationCandidate>,
        translationRequested: Boolean = true,
        durationMs: Long = 0L,
    ): Outcome? = runCatching {
        enrichOrNull(
            ttml = ttml,
            candidates = candidates,
            translationRequested = translationRequested,
            durationMs = durationMs,
        )
    }.getOrNull()

    private fun enrichOrNull(
        ttml: String,
        candidates: List<OnlineTranslationCandidate>,
        translationRequested: Boolean,
        durationMs: Long,
    ): Outcome? {
        if (candidates.isEmpty()) return null
        val baseLines = AppleLyricTtmlReader.read(ttml)
        if (baseLines.isEmpty()) return null
        val song = NativeLyricDocument(lyrics = baseLines.map(::nativeLine))
        if (!OnlineEnrichmentPolicy.needsOnlineEnrichment(
                document = TtmlTimingPolicy.metadataOf(ttml),
                lines = song.lyrics.orEmpty(),
                translationRequested = translationRequested,
                pronunciationRequested = false,
            )
        ) {
            return null
        }

        val totalLineCount = song.lyrics?.size ?: 0
        val ranked = candidates.mapNotNull { candidate ->
            selectorCandidate(song, candidate)
        }
        val winner = OnlineTranslationSelector
            .rank(ranked, totalLineCount, candidates.map(OnlineTranslationCandidate::source).distinct())
            .firstOrNull()
            ?: return null

        val merged = mergeTranslation(baseLines, winner.result.song.lyrics.orEmpty())
        if (merged.none { OnlineTranslationContentPolicy.isMeaningful(it.translation) }) return null
        return Outcome(
            ttml = AppleLyricTtmlWriter.build(
                lines = merged,
                durationMs = max(durationMs, merged.lastOrNull()?.end ?: 0L),
            ),
            source = winner.source,
            matchedLines = winner.matchedContentCount,
            totalLines = totalLineCount,
        )
    }

    private fun nativeLine(line: AppleTtmlLine): NativeLyricLine = NativeLyricLine(
        begin = line.begin,
        end = line.end,
        text = line.text,
        translation = line.translation,
        roma = line.romanization,
    )

    /** Turns one candidate into a selector entry, or null when it adds no translation. */
    private fun selectorCandidate(
        song: NativeLyricDocument,
        candidate: OnlineTranslationCandidate,
    ): OnlineTranslationSelector.Candidate? {
        val result = OnlineTranslationMatcher.apply(song, candidate.lines)
        if (!OnlineTranslationMatcher.contributesTranslation(song, result)) return null
        return OnlineTranslationSelector.Candidate(
            source = candidate.source,
            onlineLineCount = candidate.lines.size,
            translatedLineCount = candidate.lines.count {
                OnlineTranslationContentPolicy.isMeaningful(it.translation)
            },
            result = result,
            romanizedLineCount = candidate.lines.count { !it.romanization.isNullOrBlank() },
            matchedContentCount = result.matchedCount,
            onlineLines = candidate.lines,
        )
    }

    /**
     * Copies only a translation the line did not already have, keyed by index:
     * the matcher returns the base song with lanes filled, so word spans, timing
     * and layout of [base] stay exactly as Apple produced them.
     */
    private fun mergeTranslation(
        base: List<AppleTtmlLine>,
        merged: List<NativeLyricLine>,
    ): List<AppleTtmlLine> = base.mapIndexed { index, line ->
        val translated = merged.getOrNull(index)?.translation
        if (OnlineTranslationContentPolicy.isMeaningful(line.translation) ||
            !OnlineTranslationContentPolicy.isMeaningful(translated)
        ) {
            line
        } else {
            line.copy(translation = translated)
        }
    }
}
