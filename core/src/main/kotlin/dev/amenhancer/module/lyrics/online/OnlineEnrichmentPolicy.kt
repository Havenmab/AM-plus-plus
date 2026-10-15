package dev.amenhancer.module.lyrics.online

import dev.amenhancer.module.hook.TtmlDocumentMetadata
import dev.amenhancer.module.hook.TtmlTimingPolicy

/**
 * Pure answer to "does this lyric document still need an online pass?".
 *
 * HLE's `LyriconSource.needsOnlineEnrichment` is the source of truth, reduced to
 * the translation half the Apple-only policy keeps: a song needs enrichment when
 * some non-blank line has no meaningful translation and the song is not fully
 * Chinese. The pronunciation half is gone — the fork no longer adds a
 * third-party romanization lane, so a document is never enriched for
 * pronunciation alone. One target-side input is folded in:
 *
 * - [TtmlDocumentMetadata.hasTranslation] — an Apple document that already
 *   carries a translation lane needs no translation pass regardless of what the
 *   parsed lines look like.
 *
 * `fullyChinese` comes from [ChineseLyricsPolicy] exactly as HLE used it: a
 * fully-Chinese song never gets a translation lane. Pure over plain data — call
 * the raw-TTML overload to derive the document metadata from raw TTML with the
 * existing [TtmlTimingPolicy].
 */
object OnlineEnrichmentPolicy {

    fun needsOnlineEnrichment(
        document: TtmlDocumentMetadata,
        lines: List<NativeLyricLine>,
        translationRequested: Boolean = true,
    ): Boolean {
        if (lines.isEmpty()) return false
        // 全中文歌词不需要在线翻译：语气词（whoa/oh/ayy 等，含符号连接）不改变判定，
        // 避免「所有行都缺翻译」的中文歌被当成外文歌触发在线抓取。
        val fullyChinese = ChineseLyricsPolicy.isFullyChinese(lines)
        return translationRequested &&
            !document.hasTranslation &&
            !fullyChinese &&
            lines.any { line ->
                !line.text.isNullOrBlank() &&
                    !OnlineTranslationContentPolicy.isMeaningful(line.translation)
            }
    }

    /** Derives the document metadata from raw TTML, then applies the decision. */
    fun needsOnlineEnrichment(
        ttml: String,
        lines: List<NativeLyricLine>,
        translationRequested: Boolean = true,
    ): Boolean = needsOnlineEnrichment(
        document = TtmlTimingPolicy.metadataOf(ttml),
        lines = lines,
        translationRequested = translationRequested,
    )
}
