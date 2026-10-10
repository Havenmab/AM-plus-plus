package dev.amenhancer.module.lyrics.online

/**
 * HLE's `ApplePronunciationPolicy` word-track half, ported verbatim from
 * `ApplePronunciationPolicy.kt`.
 *
 * Apple renders most lyrics word by word (`itunes:timing="Word"`): the app asks
 * the line for `getPronunciationWords()` and lays out one view per returned
 * `LyricsWord`. The line-level `getHtmlPronunciationLineText` that the earlier
 * PRs override is therefore never rendered on those songs, which is why the
 * document lane and the line getters alone produced no romanization
 * (device-confirmed on 6.5.3/1606: the log shows the language selected and the
 * line text read, yet the screen stays plain).
 *
 * [wordTrack] is HLE's exact decision:
 *
 * ```
 * val wordTrack = ApplePronunciationPolicy.wordTrack(hasValidOfficialPronunciation, hasOnlinePronunciation)
 * val resolvedWords = when (wordTrack) {
 *     OFFICIAL         -> original                              // Apple's own words
 *     MAIN_LINE_TIMING -> /* synthesize words from our online pronunciation on the line's timing */
 *     HIDDEN           -> emptyApplePronunciationWords(...)
 * }
 * ```
 *
 * The three helpers below are the whole synthesis/alignment brain: the native
 * word vector itself is Apple's own (never a synthesized `LyricsWord`, which
 * would be parentless and crash the adapter — see the host hook), and
 * [displaySegments] decides how the line's romanization is split across those
 * existing words. Keeping them here, free of Android and reflection, means the
 * device-facing rule is covered by JVM tests instead of by a device log.
 */
object ApplePronunciationPolicy {

    /** HLE's `nonNullDisplayText`: the pronunciation row never renders null. */
    fun nonNullDisplayText(text: String?): String = text.orEmpty()

    /**
     * HLE's `wordTrack`: Apple's own pronunciation words win; otherwise the
     * online romanization is rendered on the native main-line words; otherwise
     * nothing is shown.
     */
    fun wordTrack(
        hasValidOfficialPronunciation: Boolean,
        hasOnlinePronunciation: Boolean,
    ): ApplePronunciationWordTrack = when {
        hasValidOfficialPronunciation -> ApplePronunciationWordTrack.OFFICIAL
        hasOnlinePronunciation -> ApplePronunciationWordTrack.MAIN_LINE_TIMING
        else -> ApplePronunciationWordTrack.HIDDEN
    }

    /**
     * HLE's per-line word decision, with the fork's Apple-first correction.
     *
     * HLE decides `OFFICIAL` from
     * `hasValidOfficialWords = officialLineText != null && officialWordVectorText != null`
     * (`AppleLyricsSupplementPronunciationSupport.kt:258-262`), so a line whose
     * line-level `getHtmlPronunciationLineText` is empty is *never* rendered with
     * Apple's own word vector — even when that vector carries Apple's
     * romanization. On a word-timing song Apple frequently leaves the line text
     * empty and puts the pronunciation on the words, which is exactly the device
     * log's `getPronunciationWords … track=MAIN_LINE_TIMING` beside
     * `getHtmlPronunciationLineText … official=false online=true`: our online
     * lane displaced Apple's own words.
     *
     * The planner therefore treats Apple's own word vector as a first-class
     * Apple source:
     *
     *  - aligned Apple words (`officialWordVectorText != null` and
     *    [hasCompatibleOfficialWordTiming]) always win → `OFFICIAL`, whether or
     *    not the line text is present. That is HLE's condition *loosened* in
     *    Apple's favour, never in ours.
     *  - otherwise Apple's line text is preferred over ours on the main timing
     *    (HLE's `mainTimingPronunciation`), then Apple's unaligned word text,
     *    and only then the online value. Our lane can only fill what Apple
     *    leaves empty.
     *
     * `pronunciation` is the text a `MAIN_LINE_TIMING` render plan must
     * distribute; it is null for `OFFICIAL` (the native vector itself is
     * returned) and for `HIDDEN`.
     */
    fun planPronunciationWords(
        officialLineText: String?,
        officialWordVectorText: String?,
        onlineText: String?,
        officialWordsCompatible: Boolean,
    ): ApplePronunciationWordPlan {
        // Apple's own word vector, aligned to the main line, is Apple data: it
        // wins even when Apple left the line-level text empty.
        val alignedAppleWords = officialWordVectorText != null && officialWordsCompatible
        val appleText = officialLineText ?: officialWordVectorText
        val track = wordTrack(
            hasValidOfficialPronunciation = alignedAppleWords,
            hasOnlinePronunciation = appleText != null || onlineText != null,
        )
        if (track == ApplePronunciationWordTrack.OFFICIAL) {
            return ApplePronunciationWordPlan(
                track = ApplePronunciationWordTrack.OFFICIAL,
                pronunciation = null,
                source = ApplePronunciationTextSource.APPLE,
            )
        }
        val text = appleText ?: onlineText
        return ApplePronunciationWordPlan(
            track = if (text == null) {
                ApplePronunciationWordTrack.HIDDEN
            } else {
                ApplePronunciationWordTrack.MAIN_LINE_TIMING
            },
            pronunciation = if (text == null) null else text,
            source = when {
                appleText != null -> ApplePronunciationTextSource.APPLE
                text != null -> ApplePronunciationTextSource.ONLINE
                else -> ApplePronunciationTextSource.NONE
            },
        )
    }

    /**
     * Apple Music 6.5.0 uses the pronunciation word begin as an exact lookup key
     * while composing the two-line karaoke layout. A valid official text/vector
     * is therefore not enough: every visible main-line word must have a matching
     * pronunciation key.
     *
     * When this is false, callers keep the official text but render it on the
     * native main word/time vector. This preserves Apple-source precedence
     * without creating synthetic native words or inheriting an incompatible
     * pronunciation timeline.
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

/** HLE's `ApplePronunciationWordTrack`, printed verbatim in the word diagnostic. */
enum class ApplePronunciationWordTrack {
    OFFICIAL,
    MAIN_LINE_TIMING,
    HIDDEN,
}

/**
 * Which lane supplied the text a `MAIN_LINE_TIMING` render plan distributes.
 * Printed as `mainTiming=` in the `pronunciation-words` diagnostic:
 * `apple` is Apple's own line text or unaligned word text, `online` is ours,
 * `none` means neither lane had anything for the line.
 */
enum class ApplePronunciationTextSource(val token: String) {
    APPLE("apple"),
    ONLINE("online"),
    NONE("none"),
}

/**
 * One [ApplePronunciationPolicy.planPronunciationWords] result: the HLE word
 * track, the text to distribute when the track is `MAIN_LINE_TIMING`, and which
 * lane that text came from. Pure, so the Apple-first rule is JVM-tested.
 */
data class ApplePronunciationWordPlan(
    val track: ApplePronunciationWordTrack,
    val pronunciation: String?,
    val source: ApplePronunciationTextSource,
)
