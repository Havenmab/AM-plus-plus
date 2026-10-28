package dev.amenhancer.module.hook.lyricsource

import dev.amenhancer.module.lyrics.source.OverlayLyricsCapability

/**
 * What the in-memory overlay store holds for one song, before the on-disk copy is
 * merged in. [holdsSong] is false when the store is serving a different track: its
 * lanes then belong to that track and are ignored, while the stored record — which
 * is per song — still answers.
 */
internal data class OverlayLaneSnapshot(
    val holdsSong: Boolean = false,
    val hasTranslation: Boolean = false,
    val translationSource: String? = null,
    val hasPronunciation: Boolean = false,
    val pronunciationSource: String? = null,
    val originalSource: String? = null,
)

/**
 * The overlay lane state the sheet reports: the store's own view, completed by the
 * record on disk ([LyricsSourceOverlayPersistence], the single source of truth for
 * which provider supplies which lane).
 *
 * The two halves answer different questions and neither is optional:
 *
 *  - the store is what the page is served from right now, but it holds **one song**
 *    and is replaced by every publish that follows, so it can legitimately have
 *    forgotten lanes the user can still see in the panel;
 *  - the record is written by every publish path and survives both a track change
 *    and a process restart.
 *
 * Reading only the store is what made the sheet say 「不显示」 for a lane the
 * automatic completion had just published: that write is on disk, and the store may
 * already have been replaced by the refresh that followed it.
 *
 * A lane counts as published when either half holds it, and its provider is read
 * from whichever half names one — the store first, because it is the newer write.
 * A provider is only reported for a lane that exists, so a name kept beside a lane
 * that is gone can never claim content the page is not showing.
 */
internal fun overlayCapabilityOf(
    snapshot: OverlayLaneSnapshot,
    persisted: LyricsSourceOverlayPersistence.LaneSources?,
    hasTiming: Boolean,
): OverlayLyricsCapability {
    val translation = (snapshot.holdsSong && snapshot.hasTranslation) ||
        persisted?.hasTranslations == true
    val pronunciation = (snapshot.holdsSong && snapshot.hasPronunciation) ||
        persisted?.hasRomanizations == true
    val originalSource = if (snapshot.holdsSong) snapshot.originalSource else null
    return OverlayLyricsCapability(
        // The overlay never carries the original document: it holds the lanes the page
        // shows *for* Apple's document. Reporting it as an original source made the
        // sheet label Apple's own lyrics as 已写入歌词 / 自定义文件.
        hasOriginal = false,
        hasTranslation = translation,
        hasRomanization = pronunciation,
        hasWords = false,
        hasTiming = hasTiming,
        // The provider that published the displayed document (auto completion, a sheet
        // pick, or the custom-document lane). Null when neither half names one, which
        // the sheet shows as "已写入歌词".
        originalSource = originalSource ?: persisted?.originalSource,
        // The provider named by the store or the record, never inferred from the text:
        // a lane without a name is content the module republished from the displayed
        // document, which the sheet shows as 已写入歌词 rather than as a platform.
        translationSource = (snapshot.translationSource ?: persisted?.translationSource)
            ?.takeIf { translation },
        romanizationSource = (snapshot.pronunciationSource ?: persisted?.pronunciationSource)
            ?.takeIf { pronunciation },
    )
}
