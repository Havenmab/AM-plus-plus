package dev.amenhancer.module.lyrics

/**
 * The romanization language set HLE requests for a song's pronunciation lane,
 * owned by the AMLL format converter.
 *
 * The AMLL converter writes a `<transliteration xml:lang>` tag derived from the
 * lyrics language, so it needs the mapping on its own: the tag is the lane's
 * **name** in the app's own lyric model, and a neutral tag names no language.
 * Kept pure and free of any host or overlay dependency so the converter builds
 * and runs standalone.
 */
internal object AppleRomanizationLanguage {

    /**
     * The neutral Latin tag used when no lyrics language can be determined. It
     * is only ever written into a document; it names no language, so a document
     * that carries romanization under it parses the text but advertises no lane.
     */
    const val NEUTRAL_LATIN_TAG = "und-Latn"

    /**
     * The `<transliteration xml:lang>` tag for a song whose lyrics language is
     * [lyricsLanguage], mirroring the set HLE requests: `ja-Latn`, `ko-Latn`,
     * `zh-Latn`. A language outside that set — an English song, or one that
     * names its language only as `und` — keeps [NEUTRAL_LATIN_TAG].
     */
    fun transliterationLanguageTag(lyricsLanguage: String?): String =
        transliterationScriptLanguage(lyricsLanguage)
            ?.let { "$it-Latn" }
            ?: NEUTRAL_LATIN_TAG

    /**
     * The primary language a supported romanization tag can be built from, or
     * null when [lyricsLanguage] names none of them. Split out so a caller that
     * has more than one candidate language (a declared `xml:lang` and a script
     * inferred from the lyrics) can tell "unsupported" from "undetermined"
     * without comparing against [NEUTRAL_LATIN_TAG].
     */
    fun transliterationScriptLanguage(lyricsLanguage: String?): String? {
        val primary = lyricsLanguage
            ?.trim()
            ?.replace('_', '-')
            ?.lowercase()
            ?.substringBefore('-')
            ?.takeIf(String::isNotEmpty)
        return when (primary) {
            "ja", "jpn" -> "ja"
            "ko", "kor" -> "ko"
            "zh", "zho", "chi", "cmn", "yue" -> "zh"
            else -> null
        }
    }
}
