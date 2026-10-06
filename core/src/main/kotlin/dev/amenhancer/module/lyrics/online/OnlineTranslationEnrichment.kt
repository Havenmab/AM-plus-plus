package dev.amenhancer.module.lyrics.online

import dev.amenhancer.module.hook.TtmlTimingPolicy

/**
 * Fills a missing translation lane into the document Apple Music is already
 * showing, using lyric bodies the online chain returned.
 *
 * The decision is the ported [OnlineEnrichmentPolicy] one: a document that
 * already carries a translation lane, or a fully-Chinese song, is left alone.
 * Each candidate is then aligned to the displayed lines with
 * [OnlineTranslationMatcher], the sources are ranked with
 * [OnlineTranslationSelector], and the winner is written back by
 * [AppleLyricTtmlLaneInjector], which edits only the head lanes of Apple's own
 * document. The body is never regenerated, so word spans, the whitespace
 * between them and `x-bg` background markup survive byte for byte — the
 * previous round trip through [AppleLyricTtmlWriter] lost them.
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
        appleMusicId: Long = 0L,
        diagnostic: (String) -> Unit = {},
    ): Outcome? = runCatching {
        enrichOrNull(
            ttml = ttml,
            candidates = candidates,
            translationRequested = translationRequested,
            durationMs = durationMs,
            appleMusicId = appleMusicId,
            diagnostic = diagnostic,
        )
    }.getOrElse { error ->
        runCatching {
            diagnostic(
                "online-translation failed id=$appleMusicId " +
                    "error=${error.javaClass.simpleName}",
            )
        }
        null
    }

    /** [durationMs] is kept for the public API; the injected lane needs no body timing. */
    @Suppress("UNUSED_PARAMETER")
    private fun enrichOrNull(
        ttml: String,
        candidates: List<OnlineTranslationCandidate>,
        translationRequested: Boolean,
        durationMs: Long,
        appleMusicId: Long = 0L,
        diagnostic: (String) -> Unit = {},
    ): Outcome? {
        val baseLines = AppleLyricTtmlReader.read(ttml)
        val song = NativeLyricDocument(lyrics = baseLines.map(::nativeLine))
        val lines = song.lyrics.orEmpty()
        val document = TtmlTimingPolicy.metadataOf(ttml)
        // A plain-text Apple document has no usable per-line timing; the matcher
        // must then align on text alone instead of the Word-timing window. The
        // shared predicate keeps this in step with the online-search block.
        val untimedNative = !TtmlTimingPolicy.hasTiming(ttml)
        diagnostic(
            "online-translation track id=$appleMusicId timing=${document.timingMode} " +
                "lang=${document.language ?: "-"} appleTranslation=${document.hasTranslation} " +
                "needsFallback=${document.needsTranslationFallback} lines=${lines.size} " +
                "untimed=$untimedNative chinese=${ChineseLyricsPolicy.isFullyChinese(lines)}",
        )
        val blocker = OnlineTranslationGate.firstBlocker(
            candidateCount = candidates.size,
            baseLineCount = baseLines.size,
            document = document,
            lines = lines,
            translationRequested = translationRequested,
        )
        diagnostic(
            "online-translation decision id=$appleMusicId " +
                "candidates=${candidates.size} reason=${blocker.token}",
        )
        if (blocker != OnlineTranslationReason.PROCEED) return null

        val totalLineCount = lines.size
        val ranked = candidates.mapNotNull { candidate ->
            selectorCandidate(song, candidate, untimedNative)
        }
        diagnostic(
            "online-translation candidates id=$appleMusicId accepted=${ranked.size} " +
                "rejected=${candidates.size - ranked.size}",
        )
        val winner = OnlineTranslationSelector
            .rank(ranked, totalLineCount, candidates.map(OnlineTranslationCandidate::source).distinct())
            .firstOrNull()
        if (winner == null) {
            diagnostic(
                "online-translation blocked id=$appleMusicId " +
                    "reason=${OnlineTranslationReason.NO_CONTRIBUTING_CANDIDATE.token}",
            )
            return null
        }
        diagnostic(
            "online-translation select id=$appleMusicId source=${winner.source} " +
                "matched=${winner.matchedContentCount}/$totalLineCount",
        )

        val merged = mergeLanes(baseLines, winner.result.song.lyrics.orEmpty())
        if (merged.none { OnlineTranslationContentPolicy.isMeaningful(it.translation) }) {
            diagnostic(
                "online-translation blocked id=$appleMusicId " +
                    "reason=${OnlineTranslationReason.NO_MEANINGFUL_MERGED_LINE.token}",
            )
            return null
        }
        // Apple's document is only edited in its head: the body -- word spans,
        // the whitespace between them, x-bg groups, agents and namespaces --
        // must survive byte for byte, so the TTML writer is not used here.
        val published = AppleLyricTtmlLaneInjector.inject(ttml, merged)
        if (published == null) {
            diagnostic(
                "online-translation blocked id=$appleMusicId " +
                    "reason=${OnlineTranslationReason.FAILED.token}",
            )
            return null
        }
        diagnostic(
            "online-translation publish id=$appleMusicId source=${winner.source} " +
                "matched=${winner.matchedContentCount}/$totalLineCount lines=${merged.size}",
        )
        return Outcome(
            ttml = published,
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
        untimedNative: Boolean,
    ): OnlineTranslationSelector.Candidate? {
        val result = OnlineTranslationMatcher.apply(song, candidate.lines, untimedNative)
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
     * and layout of [base] stay exactly as Apple produced them. A romanization
     * Apple's own transliterations lane did not supply is carried too, for the
     * injected lane on a document that has none.
     */
    private fun mergeLanes(
        base: List<AppleTtmlLine>,
        merged: List<NativeLyricLine>,
    ): List<AppleTtmlLine> = base.mapIndexed { index, line ->
        val mergedLine = merged.getOrNull(index)
        val translated = mergedLine?.translation
        val romanization = mergedLine?.roma?.trim()?.takeIf(String::isNotEmpty)
        line.copy(
            translation = if (OnlineTranslationContentPolicy.isMeaningful(line.translation) ||
                !OnlineTranslationContentPolicy.isMeaningful(translated)
            ) {
                line.translation
            } else {
                translated
            },
            romanization = line.romanization ?: romanization,
        )
    }
}
