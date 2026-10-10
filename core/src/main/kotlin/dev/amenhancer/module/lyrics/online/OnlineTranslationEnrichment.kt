package dev.amenhancer.module.lyrics.online

import dev.amenhancer.module.hook.TtmlTimingPolicy

/** Lane provenance tokens for the per-track publish diagnostic. */
private const val LANE_APPLE = "apple"
private const val LANE_NONE = "none"

/**
 * Fills a missing **translation** lane into the document Apple Music is already
 * showing, using lyric bodies the online chain returned.
 *
 * The decision is the ported [OnlineEnrichmentPolicy] one: a document that
 * already carries a translation lane needs no translation. Each candidate is
 * then aligned to the displayed lines with [OnlineTranslationMatcher], the
 * sources are ranked with [OnlineTranslationSelector], and the winner is written
 * back by [AppleLyricTtmlLaneInjector], which edits only the head lanes of
 * Apple's own document. The body is never regenerated, so word spans, the
 * whitespace between them and `x-bg` background markup survive byte for byte —
 * the previous round trip through [AppleLyricTtmlWriter] lost them.
 *
 * The pronunciation lane is **gone**: the Apple-only policy never publishes a
 * provider's romanization, so a provider's `roma` column is read past and
 * dropped, no `<transliterations>` track is injected, and a transliterations
 * block Apple's own document already carries is preserved untouched. When Apple
 * itself exposes no pronunciation the song therefore shows no romanization at
 * all.
 *
 * Every step fails open: a malformed document, a candidate that matches
 * nothing, or no candidate at all returns null and the caller leaves the
 * displayed document untouched.
 */
object OnlineTranslationEnrichment {

    /**
     * The merged document plus, per lane, which source supplied the published
     * text. `pronunciationSource` / `pronunciationLines` now describe **Apple's
     * own** transliterations lane (preserved, never injected): `apple` or `none`.
     */
    data class Outcome(
        val ttml: String,
        val source: Source,
        val matchedLines: Int,
        val totalLines: Int,
        /** `apple` when the lane was already present, the winner's name, both, or `none`. */
        val translationSource: String = source.name,
        /** `apple` when Apple's own transliterations lane is present, else `none`. */
        val pronunciationSource: String = LANE_NONE,
        /** Lines in the published document that carry Apple's own transliteration. */
        val pronunciationLines: Int = 0,
        /**
         * The merged per-line lanes, keyed by the displayed timing and text. The
         * native lyric-model delivery writes the translation half into the app's
         * own model through [NativeLyricOverlayStore]; Apple's own pronunciation
         * is read straight from Apple's lyric model.
         */
        val lines: List<NativeLyricLine> = emptyList(),
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
                "untimed=$untimedNative chinese=${ChineseLyricsPolicy.isFullyChinese(lines)} " +
                "appleTransliterations=${baseLines.any { !it.romanization.isNullOrBlank() }}",
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
            selectorCandidate(
                song = song,
                candidate = candidate,
                untimedNative = untimedNative,
                translationRequested = translationRequested,
            )
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

        val merged = mergeLanes(
            base = baseLines,
            merged = winner.result.song.lyrics.orEmpty(),
        )
        val addedTranslation = merged.indices.any { index ->
            !OnlineTranslationContentPolicy.isMeaningful(baseLines[index].translation) &&
                OnlineTranslationContentPolicy.isMeaningful(merged[index].translation)
        }
        // The provider's romanization is never merged, so only a translation can
        // make the merge meaningful.
        if (!addedTranslation) {
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
        val translationSource = laneSource(
            appleSupplied = document.hasTranslation,
            winnerSupplied = addedTranslation,
            winner = winner.source,
        )
        // Apple-only: the pronunciation lane can only be Apple's own, preserved.
        val pronunciationSource = laneSource(
            appleSupplied = baseLines.any { !it.romanization.isNullOrBlank() },
            winnerSupplied = false,
            winner = winner.source,
        )
        val pronunciationLines = merged.count { !it.romanization.isNullOrBlank() }
        // The device-facing per-lane provenance line: an exported log answers
        // "which side filled which lane" for this track without a rebuild.
        diagnostic(
            "online-translation publish id=$appleMusicId " +
                "translationSource=$translationSource pronunciationSource=$pronunciationSource " +
                "lines=${merged.size} pronunciationLines=$pronunciationLines " +
                "source=${winner.source} matched=${winner.matchedContentCount}/$totalLineCount",
        )
        return Outcome(
            ttml = published,
            source = winner.source,
            matchedLines = winner.matchedContentCount,
            totalLines = totalLineCount,
            translationSource = translationSource,
            pronunciationSource = pronunciationSource,
            pronunciationLines = pronunciationLines,
            lines = merged.map(::nativeLine),
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
        translationRequested: Boolean,
    ): OnlineTranslationSelector.Candidate? {
        val result = OnlineTranslationMatcher.apply(song, candidate.lines, untimedNative)
        val contributes = translationRequested &&
            OnlineTranslationMatcher.contributesTranslation(song, result)
        if (!contributes) return null
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
     * and layout of [base] stay exactly as Apple produced them. A provider's
     * romanization is never carried: Apple's own transliterations lane is left
     * exactly as the document already had it.
     */
    private fun mergeLanes(
        base: List<AppleTtmlLine>,
        merged: List<NativeLyricLine>,
    ): List<AppleTtmlLine> = base.mapIndexed { index, line ->
        val mergedLine = merged.getOrNull(index)
        val translated = mergedLine?.translation
        line.copy(
            translation = if (OnlineTranslationContentPolicy.isMeaningful(line.translation) ||
                !OnlineTranslationContentPolicy.isMeaningful(translated)
            ) {
                line.translation
            } else {
                translated
            },
        )
    }

    /** `apple`, the winner's name, `apple+<winner>`, or `none` for one lane. */
    private fun laneSource(appleSupplied: Boolean, winnerSupplied: Boolean, winner: Source): String {
        val sources = buildList {
            if (appleSupplied) add(LANE_APPLE)
            if (winnerSupplied) add(winner.name)
        }
        return sources.joinToString("+").ifEmpty { LANE_NONE }
    }
}
