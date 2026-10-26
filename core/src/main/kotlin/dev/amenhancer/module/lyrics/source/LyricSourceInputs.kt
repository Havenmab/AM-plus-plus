package dev.amenhancer.module.lyrics.source

import dev.amenhancer.module.lyrics.online.LyricMatchPolicy
import dev.amenhancer.module.lyrics.online.SongSearchResult
import dev.amenhancer.module.model.CustomLyricsEntry
import dev.amenhancer.module.model.CustomLyricsSources

/**
 * The song the sheet was opened for. It is both the card shown at the top of the
 * sheet and the identity the online match score is computed against, so the
 * host fills it from the same capture it already uses for the online chain.
 *
 * Every field may be blank: a host that only knows the Adam ID still produces a
 * usable sheet (the card falls back to "当前歌曲" and the scorer simply loses the
 * title/artist/album channels).
 */
data class LyricSongContext(
    /** Apple Music Adam ID; the persistence identity of the selection. */
    val songId: Long,
    val title: String = "",
    val artist: String = "",
    val album: String = "",
    /** Track duration in milliseconds; 0 when unknown (the scorer treats it as neutral). */
    val durationMs: Long = 0L,
    /** Album artwork URL, when the host has one. */
    val artworkUrl: String? = null,
) {
    /** False only for the "no song" sentinel; the host must not build a snapshot for it. */
    val hasIdentity: Boolean get() = songId > 0L

    /** The credited artists, split the same way the scorer splits candidates. */
    val artists: List<String> get() = LyricMatchPolicy.splitArtists(artist)

    /** The identity the match scorer compares candidates against. */
    fun scoringIdentity(): LyricScoringIdentity = LyricScoringIdentity(
        cleanTitle = LyricMatchPolicy.cleanString(title),
        artists = artists.map { LyricMatchPolicy.cleanString(it) }.filter { it.isNotEmpty() },
        features = LyricMatchPolicy.featuresOf(title),
        durationMs = durationMs,
        cleanAlbum = LyricMatchPolicy.normalizeAlbumForComparison(album),
    )
}

/**
 * The local identity a candidate is scored against, normalized once and reused
 * for every candidate of one search so the score cannot depend on candidate
 * order.
 */
data class LyricScoringIdentity(
    val cleanTitle: String,
    val artists: List<String>,
    val features: List<String>,
    val durationMs: Long,
    val cleanAlbum: String,
)

/**
 * What Apple's own document offers for this song, per lane. The host reads it
 * from the native document it already parsed; every flag is false when the host
 * could not establish it, which the catalog treats as "Apple does not offer that
 * lane" rather than guessing.
 */
data class AppleLyricsCapability(
    /** The song has a lyric document at all. */
    val hasLyrics: Boolean = false,
    /** The document is word-timed (逐词). */
    val hasWords: Boolean = false,
    /** The document carries Apple's own translation lane. */
    val hasTranslation: Boolean = false,
    /** The document carries Apple's own romanization lane. */
    val hasRomanization: Boolean = false,
) {
    /** True when Apple can serve this lane. */
    fun hasLane(lane: LyricLane): Boolean = when (lane) {
        LyricLane.ORIGINAL -> hasLyrics || hasWords
        LyricLane.TRANSLATION -> hasTranslation
        LyricLane.ROMANIZATION -> hasRomanization
    }

    /** True when Apple serves at least one lane. */
    val isAny: Boolean get() = hasLyrics || hasWords || hasTranslation || hasRomanization
}

/**
 * What this module has already published into [dev.amenhancer.module.lyrics.online.NativeLyricOverlayStore]
 * for the song, plus the provider each lane was recorded from.
 *
 * The sheet lists it as a selectable option ("the content that is already
 * written") so a user can re-adopt it after switching away, and the catalog uses
 * it to report what is *actually displayed* when a lane has no stored override.
 */
data class OverlayLyricsCapability(
    val hasOriginal: Boolean = false,
    val hasTranslation: Boolean = false,
    val hasRomanization: Boolean = false,
    /** Word-level timing available in the stored original. */
    val hasWords: Boolean = false,
    /** Provider the stored original came from, in the module's own source vocabulary. */
    val originalSource: String? = null,
    /** Provider the stored translation came from. */
    val translationSource: String? = null,
    /** Provider the stored romanization came from. */
    val romanizationSource: String? = null,
) {
    fun hasLane(lane: LyricLane): Boolean = when (lane) {
        LyricLane.ORIGINAL -> hasOriginal
        LyricLane.TRANSLATION -> hasTranslation
        LyricLane.ROMANIZATION -> hasRomanization
    }

    fun sourceFor(lane: LyricLane): String? = when (lane) {
        LyricLane.ORIGINAL -> originalSource
        LyricLane.TRANSLATION -> translationSource
        LyricLane.ROMANIZATION -> romanizationSource
    }

    val isAny: Boolean get() = hasOriginal || hasTranslation || hasRomanization
}

/**
 * One record of the module's custom-lyrics index, reduced to what the sheet
 * needs. Two records with the same file id are the same document; the catalog
 * lists each once per lane.
 */
data class CustomLyricsSourceRecord(
    /** Index file id; part of the persisted option key, so it must stay stable. */
    val fileId: String,
    /** The manifest's display name (already user-facing). */
    val displayName: String,
    /** Where the document came from, in the module's own source vocabulary. */
    val originSource: String,
    /** True when the user wrote or edited the document themselves. */
    val userEdited: Boolean = false,
    val hasWords: Boolean = true,
    val hasTranslation: Boolean = false,
    val hasRomanization: Boolean = false,
    val sizeBytes: Long = 0L,
) {
    /** True for the automatic-completion cache; those records are the "缓存" group. */
    val isAutoCache: Boolean get() = originSource == CustomLyricsSources.AUTO_CACHE

    /**
     * The provider id this record is attributed to.
     *
     * The stored source tag wins. When the index downgraded it to "manual" (the
     * manifest only keeps a tag for the providers it accepts, so a document saved
     * from a search provider loses its origin there), the display name the sheet
     * wrote is used to recover the brand: an entry named "QQ 音乐" is still a QQ
     * document, not a user-written file.
     */
    val providerId: String
        get() {
            val mapped = LyricSourceProviders.fromCustomLyricsSource(originSource)
            if (mapped != LyricSourceProviders.FILE) return mapped
            return LyricSourceProviders.providerIdForLabel(displayName) ?: LyricSourceProviders.FILE
        }

    /**
     * True for a document downloaded from an online provider and kept in the
     * index: it lives in a local file, but calling it "自定义文件" would hide where
     * the lyrics actually came from.
     */
    val isSavedOnline: Boolean
        get() = !isAutoCache && providerId != LyricSourceProviders.FILE &&
            providerId != LyricSourceProviders.CACHE

    companion object {
        /**
         * Projects a manifest entry. The lane capabilities are not stored in the
         * manifest (they would have to be parsed), so the host passes what it
         * knows; a document is word-timed by default because that is what every
         * import path in this module requires.
         */
        fun of(
            entry: CustomLyricsEntry,
            hasWords: Boolean = true,
            hasTranslation: Boolean = false,
            hasRomanization: Boolean = false,
        ): CustomLyricsSourceRecord = CustomLyricsSourceRecord(
            fileId = entry.fileId,
            displayName = entry.displayName,
            originSource = entry.source,
            userEdited = entry.userEdited,
            hasWords = hasWords,
            hasTranslation = hasTranslation,
            hasRomanization = hasRomanization,
            sizeBytes = entry.sizeBytes,
        )
    }
}

/**
 * One provider search hit for this song, with the lane capabilities the provider
 * is known to offer. The catalog turns it into an option and scores it against
 * the song identity; the host keeps the identity for the later fetch, because
 * [SongSearchResult] is the only thing a provider client accepts back.
 */
data class OnlineLyricsCandidate(
    /** Provider id: "qm" / "ne" / "kuwo" / "kugou" / "lb" / "amll". */
    val providerId: String,
    val result: SongSearchResult,
    /**
     * The provider declares this candidate's document is word-timed (逐词).
     *
     * It is the only precision evidence a search hit has: the body has not been
     * fetched, so a provider that does not claim word timing is reported with an
     * unknown precision ([LyricSourceOption.precisionKnown] = false) instead of being
     * labelled 逐行. A declared-but-wrong claim is the provider's problem; the preview
     * reports what the fetched document actually is.
     */
    val hasWords: Boolean = false,
    val hasTranslation: Boolean = false,
    val hasRomanization: Boolean = false,
) {
    /** The candidate's own identity, for a stable option key. */
    val remoteId: String get() = result.id
}

/**
 * Everything the catalog may read. The host fills it from its own probes; the
 * catalog never reaches for a store, a client or a platform API, so a whole
 * snapshot is a pure function of this value plus the stored selection.
 */
data class LyricSourceInput(
    val song: LyricSongContext,
    val apple: AppleLyricsCapability = AppleLyricsCapability(),
    val overlay: OverlayLyricsCapability = OverlayLyricsCapability(),
    /** The custom-lyrics index records for this song (any source, including the auto cache). */
    val customFiles: List<CustomLyricsSourceRecord> = emptyList(),
    /** Online candidates found for this song (a search result, or a cached candidate list). */
    val onlineCandidates: List<OnlineLyricsCandidate> = emptyList(),
    /**
     * The custom-lyrics index file id the host has installed (displayed) for this
     * song, or null when Apple's own document is showing. It is what makes the
     * original lane default to "the file that is already there" instead of
     * "Apple", which matters on a supplement track.
     */
    val activeFileId: String? = null,
) {
    val songId: Long get() = song.songId

    /** True when the host found nothing at all for any lane. */
    val isEmpty: Boolean
        get() = !apple.isAny && !overlay.isAny && customFiles.isEmpty() && onlineCandidates.isEmpty()
}
