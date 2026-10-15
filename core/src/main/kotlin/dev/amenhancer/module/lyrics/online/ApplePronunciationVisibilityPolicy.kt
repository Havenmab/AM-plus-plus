package dev.amenhancer.module.lyrics.online

import java.util.Locale

/**
 * HLE's 「不显示国语歌拼音」 decision, ported verbatim from
 * `common.lyric.ApplePronunciationVisibilityPolicy`.
 *
 * HLE's `shouldHideMandarinPronunciation` resolves the song's genre and
 * pronunciation languages from its native model and the metadata cache, then
 * asks this policy whether the pinyin lane must be suppressed. That host
 * resolution cannot be ported into `core` (it reads reflection-backed caches),
 * so the document lane resolves the genre on the host, passes it in, and asks
 * the same pure policy here.
 *
 * The rule is HLE's: with the switch off nothing is hidden; a Cantonese marker
 * always keeps the pronunciation; otherwise a Mandarin pronunciation language
 * or a Mandarin genre marker hides it. Everything fails open — an unknown genre
 * hides nothing.
 */
object ApplePronunciationVisibilityPolicy {
    private val cantoneseGenreMarkers = listOf(
        "cantopop",
        "cantonese",
        "粤语",
        "粵語",
        "粤曲",
        "粵曲",
        "广东歌",
        "廣東歌",
        "港乐",
        "港樂",
    )
    private val mandarinGenreMarkers = listOf(
        "mandopop",
        "mandarin",
        "国语",
        "國語",
        "华语",
        "華語",
    )

    fun shouldHide(genre: String?, hideMandarinPinyin: Boolean): Boolean =
        shouldHide(
            genre = genre,
            pronunciationLanguages = emptyList(),
            hideMandarinPinyin = hideMandarinPinyin,
        )

    fun shouldHide(
        genre: String?,
        pronunciationLanguages: Collection<String>,
        hideMandarinPinyin: Boolean,
    ): Boolean {
        if (!hideMandarinPinyin) return false
        if (pronunciationLanguages.any(::isCantonesePronunciationLanguage)) return false
        if (pronunciationLanguages.any(::isMandarinPronunciationLanguage)) return true
        return isMandarinGenre(genre)
    }

    /** Reads the genre/language context HLE stores on the song's metadata. */
    fun shouldHide(document: NativeLyricDocument, hideMandarinPinyin: Boolean): Boolean =
        shouldHide(
            genre = document.metadata?.getString(LyricMetadataKeys.APPLE_CATALOG_GENRE),
            pronunciationLanguages = document.metadata
                ?.getString(LyricMetadataKeys.APPLE_PRONUNCIATION_LANGUAGES)
                ?.split(',')
                .orEmpty(),
            hideMandarinPinyin = hideMandarinPinyin,
        )

    fun isMandarinGenre(genre: String?): Boolean {
        val normalized = genre.orEmpty().trim().lowercase(Locale.ROOT)
        if (normalized.isEmpty()) return false
        if (cantoneseGenreMarkers.any(normalized::contains)) return false
        return mandarinGenreMarkers.any(normalized::contains)
    }

    fun isMandarinPronunciationLanguage(language: String?): Boolean {
        val normalized = language.orEmpty().trim().lowercase(Locale.ROOT)
        return normalized.contains("pinyin") ||
            normalized == "cmn-latn" ||
            normalized.startsWith("cmn-latn-")
    }

    fun isCantonesePronunciationLanguage(language: String?): Boolean {
        val normalized = language.orEmpty().trim().lowercase(Locale.ROOT)
        return normalized.contains("jyutping") ||
            normalized == "yue-latn" ||
            normalized.startsWith("yue-latn-")
    }
}
