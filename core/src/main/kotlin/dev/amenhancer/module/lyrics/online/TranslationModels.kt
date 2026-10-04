package dev.amenhancer.module.lyrics.online

/**
 * Pure models for the online translation layer, ported from
 * HyperLyricsEnhanced's `lyric.model` / `lyric.LyricModels` and kept free of
 * Android, `kotlinx.serialization` and host types.
 *
 * [NativeLyricLine] mirrors the subset of HLE's `RichLyricLine` the matcher
 * touches; [NativeLyricDocument] mirrors its `Song`, and [OnlineTranslationLine]
 * mirrors its `LrcLine`. Field names and their meanings are unchanged so the
 * ported matcher/selector logic reads like the original.
 */

/** One line a source returned, carrying the lanes the providers already parsed. */
data class OnlineTranslationLine(
    val startTimeMs: Long,
    val content: String,
    val translation: String? = null,
    val romanization: String? = null,
)

/**
 * Metadata attached to a native line or document. HLE's `LyricMetadata` is a
 * `Map<String, String?>`; the delegate keeps `.entries`, iteration and lookup
 * identical while adding the one accessor the translation code uses.
 */
class LyricMetadata(private val backing: Map<String, String?> = emptyMap()) :
    Map<String, String?> by backing {

    fun getString(key: String, default: String? = null): String? = backing[key] ?: default
}

fun lyricMetadataOf(vararg entries: Pair<String, String?>): LyricMetadata =
    LyricMetadata(linkedMapOf(*entries))

/** The song's own (Apple) line, before/after online enrichment. */
data class NativeLyricLine(
    val begin: Long = 0L,
    val end: Long = 0L,
    val text: String? = null,
    val secondary: String? = null,
    val translation: String? = null,
    val translationWords: List<LyricsWord>? = null,
    val roma: String? = null,
    val isAlignedRight: Boolean = false,
    val metadata: LyricMetadata? = null,
)

/** The song's own lyric document. */
data class NativeLyricDocument(
    val lyrics: List<NativeLyricLine>? = null,
    val metadata: LyricMetadata? = null,
)

/** Keys HLE stores on lyric metadata; values are part of the published contract. */
object LyricMetadataKeys {
    const val ONLINE_TRANSLATION_SOURCE = "onlineTranslationSource"
    const val ONLINE_TRANSLATION_MATCH_STATS = "onlineTranslationMatchStats"
    const val ONLINE_PRONUNCIATION_SOURCE = "onlinePronunciationSource"
    const val ONLINE_PRONUNCIATION_MATCH_STATS = "onlinePronunciationMatchStats"
    const val BACKGROUND_VOCALS_TRANSLATION = "backgroundVocalsTranslation"
}

/**
 * Normalizes translation text returned by third-party online lyric sources.
 *
 * QQ Music and NetEase can use slash-only text such as `//` or `// //` as a
 * missing-translation placeholder. It must never be treated as real content.
 *
 * Ported verbatim from HLE's `common.lyric.OnlineTranslationContentPolicy`.
 */
object OnlineTranslationContentPolicy {
    fun sanitize(text: String?): String? {
        val normalized = text
            ?.trim { Character.isWhitespace(it) || Character.isSpaceChar(it) }
            ?.takeIf(String::isNotEmpty)
            ?: return null
        val compact = normalized.filterNot {
            Character.isWhitespace(it) || Character.isSpaceChar(it)
        }
        return normalized.takeUnless {
            compact.isNotEmpty() && compact.all { character -> character == '/' }
        }
    }

    fun isMeaningful(text: String?): Boolean = sanitize(text) != null
}
