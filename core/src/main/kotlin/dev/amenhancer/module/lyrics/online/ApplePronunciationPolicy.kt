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
 * Apple's own lanes always win. The module's own displayed document — a custom
 * (supplement) track this app itself parsed — is the one other source, and it is
 * only ever used to fill a line Apple left empty, so the word decision has three
 * outcomes:
 *
 *  - `OFFICIAL` — Apple's own word vector carries a sanitized romanization whose
 *    word begins align with the main line; the native vector itself is returned;
 *  - `MAIN_LINE_TIMING` — Apple published no usable word vector for the line but
 *    a text lane exists (Apple's own line text, or the supplement document's
 *    romanization): Apple's **main** word vector is returned and the text is
 *    distributed over it by a one-shot render plan, so every rendered word is a
 *    native `LyricsWord` of the line and no synthetic word is ever created;
 *  - `HIDDEN` — neither lane has anything usable; an empty container is returned
 *    so no text can be rendered on Apple's words.
 *
 * [displaySegments] and [hasCompatibleOfficialWordTiming] are the pure halves of
 * that alignment, so the rule stays JVM-covered by
 * [ApplePronunciationPolicyTest].
 */
object ApplePronunciationPolicy {

    /** HLE's `nonNullDisplayText`: the pronunciation row never renders null. */
    fun nonNullDisplayText(text: String?): String = text.orEmpty()

    /**
     * HLE's `wordTrack`: Apple's own aligned word vector wins; otherwise an
     * available text lane is rendered on the native main words; otherwise
     * nothing is shown.
     */
    fun wordTrack(
        hasValidOfficialPronunciation: Boolean,
        hasOnlinePronunciation: Boolean = false,
    ): ApplePronunciationWordTrack = when {
        hasValidOfficialPronunciation -> ApplePronunciationWordTrack.OFFICIAL
        hasOnlinePronunciation -> ApplePronunciationWordTrack.MAIN_LINE_TIMING
        else -> ApplePronunciationWordTrack.HIDDEN
    }

    /**
     * The Apple-first planner.
     *
     * An aligned Apple word vector is Apple data on its own and always wins
     * (`OFFICIAL`), whether or not Apple also left a line-level text. Otherwise
     * Apple's own line text is preferred over the supplied supplement text for
     * the main-timing render plan, so the module's romanization can only fill
     * what Apple leaves empty. An Apple word vector whose timeline does **not**
     * align is not usable as a text lane and stays `HIDDEN` rather than being
     * re-timed onto the main words — Apple's own lane is never displaced by a
     * guess.
     *
     * [pronunciation] is the text a `MAIN_LINE_TIMING` render plan must
     * distribute; it is null for `OFFICIAL` (the native vector itself is
     * returned) and `HIDDEN`.
     */
    fun planPronunciationWords(
        officialLineText: String?,
        officialWordVectorText: String?,
        onlineText: String?,
        officialWordsCompatible: Boolean,
    ): ApplePronunciationWordPlan {
        val alignedAppleWords = officialWordVectorText != null && officialWordsCompatible
        if (alignedAppleWords) {
            return ApplePronunciationWordPlan(
                track = ApplePronunciationWordTrack.OFFICIAL,
                pronunciation = null,
                source = ApplePronunciationTextSource.APPLE,
            )
        }
        val appleText = officialLineText
        val text = appleText ?: onlineText
        return ApplePronunciationWordPlan(
            track = if (text == null) {
                ApplePronunciationWordTrack.HIDDEN
            } else {
                ApplePronunciationWordTrack.MAIN_LINE_TIMING
            },
            pronunciation = text,
            source = when {
                appleText != null -> ApplePronunciationTextSource.APPLE
                text != null -> ApplePronunciationTextSource.ONLINE
                else -> ApplePronunciationTextSource.NONE
            },
        )
    }

    /**
     * The word getter's track for a line whose only possible source is Apple's
     * own aligned word vector (no line/supplement text lane). Kept so the
     * Apple-only callers and their JVM coverage stay valid.
     */
    fun planPronunciationWords(
        officialWordVectorText: String?,
        officialWordsCompatible: Boolean,
    ): ApplePronunciationWordTrack = planPronunciationWords(
        officialLineText = null,
        officialWordVectorText = officialWordVectorText,
        onlineText = null,
        officialWordsCompatible = officialWordsCompatible,
    ).track

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
 * The word track, printed verbatim in the word diagnostic. `MAIN_LINE_TIMING`
 * is the fallback lane: a text lane (Apple's own or the supplement document's)
 * rendered on Apple's **main** word vector, never a synthesized word.
 */
enum class ApplePronunciationWordTrack {
    OFFICIAL,
    MAIN_LINE_TIMING,
    HIDDEN,
}

/**
 * Which lane supplied the text a `MAIN_LINE_TIMING` render plan distributes.
 * Printed as `mainTiming=` in the `pronunciation-words` diagnostic: `apple` is
 * Apple's own line text or unaligned word text, `online` is the module's own
 * displayed document, `none` means neither lane had anything for the line.
 */
enum class ApplePronunciationTextSource(val token: String) {
    APPLE("apple"),
    ONLINE("online"),
    NONE("none"),
}

/**
 * One [ApplePronunciationPolicy.planPronunciationWords] result: the word track,
 * the text to distribute when the track is `MAIN_LINE_TIMING`, and which lane
 * that text came from. Pure, so the Apple-first rule is JVM-tested.
 */
data class ApplePronunciationWordPlan(
    val track: ApplePronunciationWordTrack,
    val pronunciation: String?,
    val source: ApplePronunciationTextSource,
)
