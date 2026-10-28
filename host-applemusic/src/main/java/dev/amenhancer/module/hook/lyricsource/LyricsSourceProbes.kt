package dev.amenhancer.module.hook.lyricsource

import dev.amenhancer.module.config.TargetConfigClient
import dev.amenhancer.module.hook.CurrentSongIdentityCache
import dev.amenhancer.module.hook.TtmlTimingPolicy
import dev.amenhancer.module.hook.TtmlTimingObservationRegistry
import dev.amenhancer.module.lyrics.online.AppleLyricTtmlReader
import dev.amenhancer.module.lyrics.online.NativeLyricOverlayStore
import dev.amenhancer.module.lyrics.source.AppleLyricsCapability
import dev.amenhancer.module.lyrics.source.CustomLyricsSourceRecord
import dev.amenhancer.module.lyrics.source.LyricSongContext
import dev.amenhancer.module.lyrics.source.LyricSourceInput
import dev.amenhancer.module.lyrics.source.OverlayLyricsCapability

/**
 * Turns the host's own state into the catalog's input.
 *
 * Everything the sheet shows is derived here, and every probe fails open: an
 * unreadable manifest or a missing native document downgrades one lane to its
 * empty state instead of hiding the sheet, because "we could not tell" and "the
 * song has nothing" must not be confused in the UI.
 *
 * - Apple's lanes come from the raw TTML the model hooks already recorded for
 *   this Adam ID ([TtmlTimingObservationRegistry]), parsed with the same reader
 *   the replace path uses — so the flags agree with what the page renders.
 * - The overlay lanes come from [NativeLyricOverlayStore] **and** from
 *   [LyricsSourceOverlayPersistence], the record every publish path writes. The
 *   store is one song in process memory, the record is per song on disk: a lane (and
 *   its provider) that auto completion published survives both a later refresh and a
 *   restart, and the sheet must report it either way.
 * - Custom documents come from the module's index through
 *   [TargetConfigClient.customLyricsManifest]; the entry that is currently
 *   enabled is what the page is showing, and that is what `activeFileId` reports.
 */
internal class LyricsSourceProbes(
    private val config: TargetConfigClient,
    private val currentSong: CurrentSongIdentityCache,
    private val overlay: NativeLyricOverlayStore,
    /**
     * The on-disk copy of the overlay lanes. It is the single source of truth for
     * *which provider supplies which lane*: the in-memory store is only this
     * process's view of it, and after an automatic completion that view may already
     * have been replaced by a later refresh (or be empty, after a restart). The
     * sheet must still name the provider, so the probe reads both and the stored
     * record is what answers when the store cannot.
     */
    private val persistence: LyricsSourceOverlayPersistence,
    private val timing: TtmlTimingObservationRegistry,
    private val metadataTitle: (Long) -> String? = { null },
    private val metadataArtist: (Long) -> String? = { null },
    private val metadataAlbum: (Long) -> String? = { null },
    private val metadataArtworkUrl: (Long) -> String? = { null },
) {

    /** One probe result: the catalog input plus the document the page is showing. */
    internal data class Probed(
        val input: LyricSourceInput,
        /** The displayed document when the host knows it: Apple's, or the custom one. */
        val displayedTtml: String?,
        /** True when [displayedTtml] is the module's custom document, not Apple's. */
        val displayedIsCustom: Boolean,
    )

    fun probe(): Probed? {
        val details = currentSong.current()?.details ?: return null
        val songId = details.appleMusicId
        if (songId <= 0L) return null
        val entries = runCatching { config.customLyricsManifest().entries }
            .getOrDefault(emptyList())
            .filter { it.appleMusicId == songId }
        val activeFileId = entries.firstOrNull { it.enabled }?.fileId
        val appleTtml = runCatching { timing.rawTtmlOfAppleMusicId(songId) }.getOrNull()
        val customTtml = activeFileId?.let { fileId ->
            runCatching { config.openFile(fileId)?.use { it.readBytes().toString(Charsets.UTF_8) } }
                .getOrNull()
                ?.takeIf(String::isNotBlank)
        }
        val displayedTtml = customTtml ?: appleTtml
        val song = LyricSongContext(
            songId = songId,
            title = details.title ?: metadataTitle(songId).orEmpty(),
            artist = details.artist ?: metadataArtist(songId).orEmpty(),
            album = metadataAlbum(songId).orEmpty(),
            durationMs = details.durationMs,
            artworkUrl = metadataArtworkUrl(songId)?.takeIf { it.isNotBlank() },
        )
        // Precision and lane availability are read from the documents we can
        // actually see: the one being displayed is already in memory, and up to two
        // index entries are read so a song with one or two downloads still reports
        // 逐词/逐行 truthfully instead of assuming the best case.
        val customFiles = entries.mapIndexed { index, entry ->
            val document = when {
                entry.fileId == activeFileId -> customTtml
                entries.size <= MAX_PROBED_DOCUMENTS && index < MAX_PROBED_DOCUMENTS -> readDocument(entry.fileId)
                else -> null
            }
            val lines = document?.let { ttml -> runCatching { AppleLyricTtmlReader.read(ttml) }.getOrNull() }
            CustomLyricsSourceRecord.of(
                entry,
                hasWords = lines?.let { parsed -> parsed.any { it.words.size >= 2 } } ?: false,
                hasTranslation = lines?.let { parsed -> parsed.any { !it.translation.isNullOrBlank() } }
                    ?: false,
                // A custom document never offers the romanization lane: under the
                // Apple-only policy that lane is Apple's own, and a document-based
                // transliteration is published into the overlay by the displayed-document
                // path instead. Offering it here produced a "自定义文件 / 使用 Apple 官方"
                // pair on a lane Apple already serves.
                hasRomanization = false,
                // Unknown (no document read) keeps core's "assume a timeline" default:
                // only an observed static document may report false.
                hasTiming = document?.let { ttml ->
                    runCatching { TtmlTimingPolicy.hasTiming(ttml) }.getOrDefault(true)
                } ?: true,
            )
        }
        val input = LyricSourceInput(
            song = song,
            apple = appleCapability(appleTtml),
            overlay = overlayCapability(songId, displayedTtml),
            customFiles = customFiles,
            activeFileId = activeFileId,
        )
        return Probed(input = input, displayedTtml = displayedTtml, displayedIsCustom = customTtml != null)
    }

    /** Raw Apple document captured by the native timing seam, even while a custom file is displayed. */
    internal fun rawAppleTtml(songId: Long): String? = runCatching {
        timing.rawTtmlOfAppleMusicId(songId)
    }.getOrNull()

    /** One custom-lyrics body by index file id, bounded by the caller's read budget. */
    private fun readDocument(fileId: String): String? = runCatching {
        config.openFile(fileId)?.use { input -> input.readBytes().toString(Charsets.UTF_8) }
    }.getOrNull()?.takeIf { it.isNotBlank() }

    /** Reads Apple's own lane availability out of the document the page showed. */
    fun appleCapability(ttml: String?): AppleLyricsCapability {
        if (ttml.isNullOrBlank()) return AppleLyricsCapability()
        return runCatching {
            val lines = AppleLyricTtmlReader.read(ttml)
            AppleLyricsCapability(
                hasLyrics = lines.isNotEmpty(),
                hasWords = lines.any { it.words.isNotEmpty() },
                hasTranslation = lines.any { !it.translation.isNullOrBlank() },
                hasRomanization = lines.any { !it.romanization.isNullOrBlank() } ||
                    AppleLyricTtmlReader.transliterationLanguages(ttml).isNotEmpty(),
                // Static lyrics have no <p begin/end> anywhere: the sheet must not
                // present them as scroll-synced.
                hasTiming = TtmlTimingPolicy.hasTiming(ttml),
            )
        }.getOrDefault(AppleLyricsCapability())
    }

    /**
     * What this module published for the song, with the provider of each lane.
     *
     * The lane classifier decides what the store may *contain*; this decides what
     * the sheet may *claim*, and the two questions are not the same:
     *
     *  - the translation lane is claimed when the store holds one, and it is named
     *    after the recorded [OverlayLyricsCapability.translationSource] — the
     *    provider whose Chinese was published;
     *  - the romanization lane is claimed only when the store really holds one
     *    ([NativeLyricOverlayStore.hasPronunciation]), and it is named after the
     *    recorded pronunciation source. An empty overlay lane is **not** "Apple has
     *    none": Apple's own lane is measured separately ([appleCapability]) and
     *    still serves the lane through its own option, so the lane falls back to
     *    Apple instead of disappearing from the sheet.
     *
     * Both halves are read: the in-memory store is this process's view, while
     * [LyricsSourceOverlayPersistence] is the record every publish path writes and
     * the only thing that survives a restart (see [overlayCapabilityOf]). Reading
     * only the store is what made the sheet say 「不显示」 for a lane auto completion
     * had just published — the completion's write is on disk, and the store may
     * already have been replaced by the refresh that followed it.
     */
    fun overlayCapability(songId: Long, displayedTtml: String? = null): OverlayLyricsCapability {
        if (songId <= 0L) return OverlayLyricsCapability()
        val key = songId.toString()
        return runCatching {
            val current = overlay.currentSongId()
            // The store holds one song at a time. A store that belongs to another track
            // says nothing about this one — but the stored record still does, so the
            // lanes are reported as "not in memory" rather than as "nothing published".
            val holdsSong = current == null || current == key
            val stored = runCatching { persistence.sources(songId) }.getOrNull()
            overlayCapabilityOf(
                snapshot = OverlayLaneSnapshot(
                    holdsSong = holdsSong,
                    hasTranslation = overlay.hasTranslation(key),
                    translationSource = overlay.translationSource(key),
                    hasPronunciation = overlay.hasPronunciation(key),
                    pronunciationSource = overlay.pronunciationSource(key),
                    originalSource = overlay.originalSource(key),
                ),
                persisted = stored,
                // The stored lanes belong to the document the page is showing, so its
                // timeline is the overlay's timeline.
                hasTiming = displayedTtml?.let { ttml ->
                    runCatching { TtmlTimingPolicy.hasTiming(ttml) }.getOrDefault(true)
                } ?: true,
            )
        }.getOrDefault(OverlayLyricsCapability())
    }

    private companion object {
        /**
         * How many index documents one snapshot may read to report 逐词/逐行.
         * A song almost always has one entry; the bound keeps a somebody's large
         * library from turning a snapshot into a file-system walk.
         */
        const val MAX_PROBED_DOCUMENTS = 2
    }
}
