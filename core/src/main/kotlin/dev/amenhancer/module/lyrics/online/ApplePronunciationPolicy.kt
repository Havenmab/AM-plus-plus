package dev.amenhancer.module.lyrics.online

/**
 * Apple's own pronunciation word track, ported from HLE's
 * `ApplePronunciationPolicy`.
 *
 * Apple renders most lyrics word by word (`itunes:timing="Word"`): the app asks
 * the line for `getPronunciationWords()` and lays out one view per returned
 * `LyricsWord`. The line-level `getHtmlPronunciationLineText` that the earlier
 * PRs override is therefore never rendered on those songs.
 *
 * This policy is **Apple-only**. The third-party romanization lane has been
 * removed from the product, so the word decision has exactly two outcomes:
 *
 *  - `OFFICIAL` — Apple's own word vector carries a sanitized romanization whose
 *    word begins align with the main line; the native vector itself is returned;
 *  - `HIDDEN` — Apple published nothing usable for the line; an empty container
 *    is returned so no third-party text can ever be rendered on Apple's words.
 *
 * HLE's `MAIN_LINE_TIMING` track (Apple's words carrying a text lane) can no
 * longer be produced. [displaySegments] and [hasCompatibleOfficialWordTiming] are
 * retained as the pure halves of the (now unreachable) main-timing render-plan
 * plumbing, so the Apple-only rule stays JVM-covered by
 * [ApplePronunciationPolicyTest].
 */
object ApplePronunciationPolicy {

    /** HLE's `nonNullDisplayText`: the pronunciation row never renders null. */
    fun nonNullDisplayText(text: String?): String = text.orEmpty()

    /**
     * Apple's own aligned word vector is `OFFICIAL`; everything else is `HIDDEN`.
     * There is no third-party branch: `hasOnlinePronunciation` is gone.
     */
    fun wordTrack(hasValidOfficialPronunciation: Boolean): ApplePronunciationWordTrack =
        if (hasValidOfficialPronunciation) {
            ApplePronunciationWordTrack.OFFICIAL
        } else {
            ApplePronunciationWordTrack.HIDDEN
        }

    /**
     * The word getter's decision, Apple-only.
     *
     * [officialWordVectorText] is Apple's own romanization read from the word
     * vector (HLE's `nativeRawWordVectorText`) and [officialWordsCompatible] is
     * [hasCompatibleOfficialWordTiming]. Both must hold for `OFFICIAL`; otherwise
     * the line is `HIDDEN`, never filled from a provider.
     */
    fun planPronunciationWords(
        officialWordVectorText: String?,
        officialWordsCompatible: Boolean,
    ): ApplePronunciationWordTrack = wordTrack(
        hasValidOfficialPronunciation = officialWordVectorText != null && officialWordsCompatible,
    )

    /**
     * Apple Music 6.5.0 uses the pronunciation word begin as an exact lookup key
     * while composing the two-line karaoke layout. A valid official text/vector
     * is therefore not enough: every visible main-line word must have a matching
     * pronunciation key.
     *
     * When this is false, the line is `HIDDEN`: the official timeline is
     * incompatible with the main line, and no third-party text may be substituted.
     */
    fun hasCompatibleOfficialWordTiming(
        mainWordBegins: List<Int>,
        pronunciationWordBegins: List<Int>,
    ): Boolean {
        val main = mainWordBegins.filter { it >= 0 }.distinct()
        val pronunciation = pronunciationWordBegins.filter { it >= 0 }.toHashSet()
        return main.isNotEmpty() && pronunciation.isNotEmpty() &&
            main.all(pronunciation::contains)
    }

    /**
     * 将整行发音文本按 Apple 原生主句 word 的实际文本权重分配。
     *
     * Apple 可能把多个汉字合并为一个 word，例如“潇洒 / 的 / 放 / 屁”只有 4 个
     * native word，但需要容纳 5 个粤拼音节。只按 word 数均分会使后续音节错位，
     * 因此东亚文字按字符数计权，其他非空 word 按一个发音单位计权。
     *
     * 这里只生成显示片段，不创建新的 native LyricsWord。实际渲染继续复用主句 word，
     * 因而每个片段都保留 Apple 原生父歌词行、lineId、wordId 与时间轴。
     */
    fun displaySegments(
        pronunciation: String,
        mainWordTexts: List<String>,
    ): List<String> {
        if (mainWordTexts.isEmpty()) return emptyList()
        val tokens = pronunciation.trim().split(Regex("\\s+")).filter(String::isNotEmpty)
        if (tokens.isEmpty()) return emptyList()

        val measuredWeights = mainWordTexts.map(::pronunciationUnitCount)
        val weights = if (measuredWeights.any { it > 0 }) {
            measuredWeights
        } else {
            List(mainWordTexts.size) { 1 }
        }
        val totalWeight = weights.sum()
        var consumedWeight = 0
        return weights.map { weight ->
            val tokenStart = consumedWeight * tokens.size / totalWeight
            consumedWeight += weight
            val tokenEnd = consumedWeight * tokens.size / totalWeight
            tokens.subList(tokenStart, tokenEnd).joinToString(" ")
        }
    }

    private fun pronunciationUnitCount(text: String): Int {
        val visibleText = text.replace(HTML_TAG, " ").trim()
        if (visibleText.isEmpty()) return 0

        var eastAsianCharacters = 0
        var hasLetterOrDigit = false
        var index = 0
        while (index < visibleText.length) {
            val codePoint = visibleText.codePointAt(index)
            if (Character.isLetterOrDigit(codePoint)) {
                hasLetterOrDigit = true
                when (Character.UnicodeScript.of(codePoint)) {
                    Character.UnicodeScript.HAN,
                    Character.UnicodeScript.HIRAGANA,
                    Character.UnicodeScript.KATAKANA,
                    Character.UnicodeScript.HANGUL -> eastAsianCharacters++
                    else -> Unit
                }
            }
            index += Character.charCount(codePoint)
        }
        return eastAsianCharacters.takeIf { it > 0 } ?: if (hasLetterOrDigit) 1 else 0
    }

    private val HTML_TAG = Regex("<[^>]*>")
}

/**
 * The Apple-only word track, printed verbatim in the word diagnostic. HLE's
 * `MAIN_LINE_TIMING` is deliberately absent: the third-party text lane it
 * carried no longer exists.
 */
enum class ApplePronunciationWordTrack {
    OFFICIAL,
    HIDDEN,
}
