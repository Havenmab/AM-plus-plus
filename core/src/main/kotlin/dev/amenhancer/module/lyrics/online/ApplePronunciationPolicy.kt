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
 * [matchPronunciationSpans] and [hasCompatibleOfficialWordTiming] are the pure
 * halves of that alignment, so the rule stays JVM-covered by
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
     * Match each main word of the line to the document transliteration span that
     * is **its own**, by time.
     *
     * A Word-timed song has no line-level romanization to spread: the displayed
     * document's head `<transliterations>` track already carries one timed
     * `<span begin end>` per lyric word (AMLL writes them as inline `x-roman`
     * spans, which the converter migrates verbatim into the head). Each word
     * therefore shows the text of the span timed over it — never a slice of one
     * whole-line string, which is what piled the entire romanization onto the
     * last word.
     *
     * A span is attributed to a word only when it overlaps **exactly one** main
     * word: a span that crosses a word boundary (a line- or group-level slice)
     * belongs to no single word and is dropped, so a whole-line span can never be
     * duplicated across words. The document may time the romanization finer than
     * the lyric words, in which case one word owns several spans and their texts
     * are concatenated in document order. A word with no own span is absent from
     * the result and keeps its original text.
     *
     * Pure and fail-open: an empty word or span list, or blank span text, yields
     * no match for the affected word.
     */
    fun matchPronunciationSpans(
        words: List<PronunciationWordTiming>,
        spans: List<NativePronunciationSpan>,
    ): List<PronunciationWordMatch> {
        if (words.isEmpty() || spans.isEmpty()) return emptyList()
        val ownedText = Array(words.size) { mutableListOf<String>() }
        spans.forEach { span ->
            val text = span.text.normalizedPronunciationText()
            if (text.isEmpty()) return@forEach
            val owners = words.indices.filter { overlaps(words[it], span) }
            if (owners.size == 1) ownedText[owners.single()].add(text)
        }
        return words.indices.mapNotNull { index ->
            val text = ownedText[index].joinToString("")
            text.takeIf(String::isNotEmpty)?.let { PronunciationWordMatch(index, it) }
        }
    }

    /**
     * Half-open `[begin, end)` overlap; a zero-length word or span is treated as
     * a point so a degenerate slice still lands on the word that contains it.
     */
    private fun overlaps(word: PronunciationWordTiming, span: NativePronunciationSpan): Boolean {
        val wordBegin = word.begin
        val wordEnd = word.end.coerceAtLeast(word.begin)
        val spanBegin = span.begin
        val spanEnd = span.end.coerceAtLeast(span.begin)
        return if (wordBegin == wordEnd || spanBegin == spanEnd) {
            spanBegin <= wordEnd && wordBegin <= spanEnd
        } else {
            spanBegin < wordEnd && wordBegin < spanEnd
        }
    }

    private val PRONUNCIATION_WHITESPACE = Regex("[\\s\\p{Z}]+")

    /**
     * Collapses every Unicode space (including NBSP and U+3000, which `String.trim`
     * and `\s` miss) and trims the edges, so a span copied from a source with a
     * leading full-width space never renders one.
     */
    private fun String.normalizedPronunciationText(): String =
        replace(PRONUNCIATION_WHITESPACE, " ").trim(' ')
}

/**
 * The word track, printed verbatim in the word diagnostic. `MAIN_LINE_TIMING`
 * is the document lane: Apple's **main** word vector is returned and a one-shot
 * render plan carries the displayed document's own timed transliteration spans,
 * which the adapter matches to each word by time — never a synthesized word and
 * never a whole-line string spread over the words.
 */
enum class ApplePronunciationWordTrack {
    OFFICIAL,
    MAIN_LINE_TIMING,
    HIDDEN,
}

/**
 * Which lane supplied the line-level pronunciation text. Printed as
 * `mainTiming=` in the `pronunciation-words` diagnostic: `apple` is Apple's own
 * line text or unaligned word text, `online` is the module's own displayed
 * document, `none` means neither lane had anything for the line. Only the
 * `online` lane can also carry word-level spans (the document's own
 * `<transliteration>` slices).
 */
enum class ApplePronunciationTextSource(val token: String) {
    APPLE("apple"),
    ONLINE("online"),
    NONE("none"),
}

/**
 * One [ApplePronunciationPolicy.planPronunciationWords] result: the word track,
 * the **line-level** pronunciation text when one exists, and which lane it came
 * from. [pronunciation] is served by the line-lane fallback; a Word-timed song's
 * per-word text comes from the displayed document's spans instead. Pure, so the
 * Apple-first rule is JVM-tested.
 */
data class ApplePronunciationWordPlan(
    val track: ApplePronunciationWordTrack,
    val pronunciation: String?,
    val source: ApplePronunciationTextSource,
)

/** One main-line word's time range, as Apple's own word vector reports it. */
data class PronunciationWordTiming(val begin: Long, val end: Long)

/**
 * A main word that owns [text]. [wordIndex] indexes the matcher's word list, so
 * the caller maps the text back to the exact native word (its `wordId`/timing)
 * without a synthetic word ever being created.
 */
data class PronunciationWordMatch(val wordIndex: Int, val text: String)
