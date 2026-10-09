package dev.amenhancer.module.hook

import java.util.concurrent.Executor
import java.util.concurrent.RejectedExecutionException

/**
 * The translation/pronunciation completion feed for a **custom** lyrics
 * document — a manual import or a module-mapped remote file.
 *
 * The automatic path never covers these tracks. [CustomLyricsReplacementSession]
 * owns the document that is displayed, while the automatic session's `isAllowed`
 * deliberately excludes every manual/mapped id. That exclusion has to stay: when
 * enrichment yields nothing the automatic path falls through to `resolverFetch`,
 * so simply opening the gate would let a searched document replace the user's own
 * body — trading a missing romanization for a wrong song.
 *
 * This feed adds the missing half instead. It calls the exact function the
 * automatic path calls for its lane merge: the runtime's `translationEnricher`,
 * which merges a missing translation/pronunciation lane into the **given**
 * document and writes the merged per-line lanes into the native lyric-model
 * overlay that [dev.amenhancer.module.lyrics.online.NativeLyricOverlayStore]
 * exposes to the host hooks. Two deliberate properties:
 *
 *  - **The custom body always wins.** The merged document the enricher returns is
 *    discarded here. Nothing in this class can parse, publish or display it, so a
 *    searched source can never replace the custom document; only the overlay side
 *    effect is kept, and the native hooks deliver both lanes from it. That is
 *    also sufficient on 1606, where the document lane (an injected
 *    `<transliterations>` head track) is not rendered at all.
 *  - **Keyed by track and document revision.** [remember] stores the raw document
 *    the session just verified and read; [onDisplayed] runs once per
 *    (track, revision), so a repeated lyrics presentation or a re-entrant I2
 *    cannot re-run the network pass, while an edited file (a new sha256 from the
 *    manifest) is enriched again. Because the native overlay holds one track at a
 *    time, a change of displayed track clears that guard so a revisit enriches
 *    again.
 *
 * Every existing gate is preserved because the feed reuses the very same
 * enricher: the translation/pronunciation toggle (a null enricher means no feed
 * at all), HLE's Mandarin visibility rule and the 「原文档优先」 merge inside
 * `OnlineTranslationEnrichment` (a lane the custom document already carries is
 * never overwritten), and the Apple-timed-lyrics replacement block — the
 * enricher only augments lanes through `fetchTranslationCandidates` and never
 * runs the resolver's replacement `fetch`, so it cannot swap the body.
 *
 * Every path fails open: a blank id, a missing remembered document, a rejecting
 * executor or a throwing enricher leaves the custom document displayed exactly as
 * it was.
 *
 * [onOverlayUpdated] is the other half of a successful completion: the overlay
 * now holds the merged lanes, so the host asks for the presentation refresh that
 * re-reads them (HLE's supplement path does the same from its store update,
 * `AppleSupplementDataReceive` → `refreshAppleLyricsSupplementPresentation`).
 * It is a plain per-instance callback, never a global, wired by the host to the
 * native lyric delivery it already owns; its default makes the feed a no-op for
 * every caller that does not need it, and a throwing callback fails open.
 */
class CustomLyricsCompletionFeed(
    private val enrich: (Long, String) -> String?,
    private val executor: Executor,
    private val log: (Long, String) -> Unit = { _, _ -> },
    /**
     * Invoked once per successful completion, after the enricher wrote the
     * native overlay for the track. The host re-checks the overlay revision, so a
     * completion that changed nothing is a no-op there.
     */
    private val onOverlayUpdated: (Long) -> Unit = {},
    private val maxTracks: Int = MAX_TRACKS,
) {
    private data class Document(val revision: String, val ttml: String)

    /** The raw custom body last read for a track, keyed by Adam ID. */
    private val documents = object : LinkedHashMap<Long, Document>(
        maxTracks.coerceAtLeast(1),
        0.75f,
        true,
    ) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<Long, Document>?): Boolean =
            size > maxTracks.coerceAtLeast(1)
    }

    /**
     * The revision already scheduled for a track. Marked when the task is
     * queued, not when it finishes, so the burst of I2 presentations that follow
     * one lyrics display still produces a single pass. Cleared when a different
     * track is displayed, because the native overlay holds one track at a time:
     * returning to a track must enrich it again even for an unchanged revision.
     */
    private val scheduled = object : LinkedHashMap<Long, String>(
        maxTracks.coerceAtLeast(1),
        0.75f,
        true,
    ) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<Long, String>?): Boolean =
            size > maxTracks.coerceAtLeast(1)
    }

    @Volatile
    private var displayedTrack: Long = 0L

    /**
     * Remembers the raw custom document the session just verified against the
     * manifest (its [revision] is that manifest entry's sha256).
     */
    fun remember(appleMusicId: Long, revision: String, ttml: String) {
        if (appleMusicId <= 0L || ttml.isBlank()) return
        synchronized(documents) {
            documents[appleMusicId] = Document(revision, ttml)
        }
    }

    /**
     * The custom document is now the one Apple is being asked to display. Queues
     * the lane completion off the hook thread, once per (track, revision) and
     * again whenever the active track changed since the last pass.
     */
    fun onDisplayed(appleMusicId: Long) {
        if (appleMusicId <= 0L) return
        val document = synchronized(documents) { documents[appleMusicId] } ?: return
        synchronized(scheduled) {
            if (displayedTrack != appleMusicId) {
                displayedTrack = appleMusicId
                scheduled.clear()
            }
            if (scheduled[appleMusicId] == document.revision) return
            scheduled[appleMusicId] = document.revision
        }
        try {
            executor.execute { complete(appleMusicId, document) }
        } catch (_: RejectedExecutionException) {
            // The document was never enriched, so let a later presentation retry.
            synchronized(scheduled) { scheduled.remove(appleMusicId) }
            log(appleMusicId, injectLine(appleMusicId, published = false))
        }
    }

    private fun complete(appleMusicId: Long, document: Document) {
        val metadata = TtmlTimingPolicy.metadataOf(document.ttml)
        // The visible capture line the automatic path emits from its parse seam,
        // reported here for the custom document so an exported device log shows
        // this path running at all (it used to be silent).
        log(
            appleMusicId,
            "online-translation capture id=$appleMusicId rawTtml=custom " +
                "bytes=${document.ttml.length} revision=${document.revision.take(SHORT_REVISION)} " +
                "timing=${metadata.timingMode} appleTranslation=${metadata.hasTranslation}",
        )
        val merged = runCatching { enrich(appleMusicId, document.ttml) }.getOrNull()
        // `publish` and the per-lane `translationSource`/`pronunciationSource`
        // line come from the enricher itself; this is the inject counterpart.
        log(appleMusicId, injectLine(appleMusicId, published = merged != null))
        // Only a successful enrichment wrote the overlay. The refresh request is
        // fail-open: a host callback that throws never fails the completion.
        if (merged != null) {
            runCatching { onOverlayUpdated(appleMusicId) }
        }
    }

    private fun injectLine(appleMusicId: Long, published: Boolean): String =
        "online-translation inject id=$appleMusicId source=online-translation published=$published"

    private companion object {
        /**
         * At most this many tracks keep a remembered body. The custom session's
         * own pointer cache is the same size, so a body that falls out here is
         * re-read (and re-remembered) the next time its pointer is prepared.
         */
        const val MAX_TRACKS = 32

        const val SHORT_REVISION = 12
    }
}
