package dev.amenhancer.module.lyrics.online

import java.util.Locale
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Normalizes the lanes an online provider already returned into one shape — and
 * owns the one rule that decides what each lane may hold.
 *
 * A provider hands back a [LyricsResult] whose translated / romanization lists are
 * already aligned to original by timestamp ([LrcParser.lyricsMerge]), with each lane
 * line collapsed to a single word. [extract] flattens that into one
 * [OnlineTranslationLine] per original line and applies the
 * [OnlineTranslationContentPolicy.sanitize] HLE runs on translation text before
 * matching, so slash-only placeholders never enter the matcher as content.
 *
 * Lanes are keyed back onto the original lines by **start time**, exactly like HLE's
 * toLrcLines. That matters for providers whose lane list is not index-aligned with
 * original: Kuwo's secondary drops lines without an auxiliary entry, so an
 * index-based read shifted every translation after the first gap onto the wrong line.
 *
 * Romanization is only trimmed: HLE does not sanitize it, and a slash-only
 * pronunciation is (weirdly) still treated as content, which is preserved here
 * rather than "fixed".
 *
 * ## Why the lane rules live here
 *
 * A provider's field name does not describe its content: QQ Music's trans is the
 * **reading** (kana or romaji) for most Japanese songs — Lemon's trans is a
 * [kana:…] tag plus romaji — and a "roma" column occasionally carries a Chinese
 * translation. [classifyLanes] is that verdict: anything that is not a Chinese
 * translation leaves the translation lane (moving into an empty romanization lane
 * when it is that line's reading), so "补全歌词翻译与发音" can never publish a
 * romanization as a translation or the other way round.
 *
 * This object, and not [LyricLaneClassifier], is where the rule is implemented. On
 * device the classifier class was present in the dex but could not be loaded
 * (NoClassDefFoundError), so every call site that reached it threw: the translation
 * lane stayed empty, the manual "choose a translation from the source sheet"
 * published nothing, and the automatic enrichment neither wrote the overlay nor
 * saved its file. The rules were moved into this class — which the app already links
 * for every extraction — and [LyricLaneClassifier] is now a thin delegate kept for
 * its own callers and tests.
 *
 * Every entry point also degrades: a verdict that throws costs the re-homing, never
 * the lane a provider really supplied ([classifyLanesOrDegrade], [degradeLanes]).
 */
object OnlineTranslationExtraction {

    /** A line's script ownership: the only input of the lane verdict. */
    enum class LaneKind {
        /** Blank, punctuation or digits only: no lane takes it. */
        EMPTY,

        /** Han only, no kana: a Chinese translation (or a Chinese original). */
        CHINESE,

        /** Kana only: a Japanese reading, i.e. romanization-lane content. */
        KANA,

        /** Latin letters that spell Japanese syllables: romanization-lane content. */
        ROMANIZATION,

        /** Latin letters that do not spell Japanese: an English (or other Latin) line. */
        LATIN,

        /** Mixed scripts: kanji + kana is a Japanese original, kanji + Latin a translation. */
        MIXED,

        /** Anything else (Hangul, Cyrillic, Greek, Thai…): no lane takes it. */
        OTHER,
    }

    /**
     * Both lanes of one line after re-homing; null means the lane does not take the
     * line.
     *
     * A type of its own rather than [LyricLaneClassifier.LaneContent], so that the
     * degraded write paths never have to link a class that may be unusable on device.
     */
    data class LanePair(
        val translation: String? = null,
        val romanization: String? = null,
    )

    /**
     * Sink for the one-shot "lane-classifier-unavailable" diagnostic. Core has no log
     * channel of its own: the app installs the module logger, and a host that installs
     * nothing still gets the line on stderr (which the device log captures).
     */
    @Volatile
    var diagnostic: ((String) -> Unit)? = null

    /** At most one line per process: the failure is a property of the build, not a track. */
    private val laneRulesReported = AtomicBoolean(false)

    /**
     * [alreadyFiltered] is for callers that ran [LyricLineFilter] themselves (the
     * lyrics-source sheet does, so a failure can name the stage it happened in).
     * Filtering twice is not neutral: the opening-block sweep runs on the *filtered*
     * list and can drop lines the first pass kept, which would shift every lane after
     * the gap.
     *
     * [laneClassifier] is the lane-verdict seam: production runs [classifyLanes]; a
     * test passes a function that throws, because the device case — a classifier that
     * cannot run at all — cannot be provoked any other way from a JVM test.
     */
    fun extract(
        result: LyricsResult,
        alreadyFiltered: Boolean = false,
        laneClassifier: ((String?, String?, String?) -> LanePair)? = null,
    ): List<OnlineTranslationLine> {
        // A translation lane opens with the platform's copyright notice and fills gaps with
        // //; both would otherwise be aligned onto the first lyric line.
        val clean = if (alreadyFiltered) result else LyricLineFilter.filter(result)
        val translationsByStart = clean.translated.orEmpty().associate { line ->
            line.start to text(line)
        }
        val romanizationsByStart = clean.romanization.orEmpty().associate { line ->
            line.start to text(line)
        }
        return clean.original.map { line ->
            val content = text(line)
            val translation = OnlineTranslationContentPolicy.sanitize(translationsByStart[line.start])
            val romanization = romanizationsByStart[line.start]?.takeIf(String::isNotEmpty)
            // The provider's field names are not the content: the verdict comes from the
            // text itself. It is a presentation step, though: a verdict that cannot run may
            // cost the re-homing, never the lane a provider really supplied — that is exactly
            // what emptied this lane on device.
            val lanes = runCatching {
                laneClassifier?.invoke(translation, romanization, content)
                    ?: classifyLanes(translation, romanization, content)
            }.getOrElse { error ->
                reportLaneRulesUnavailable(error)
                degradeLanes(translation, romanization, content)
            }
            OnlineTranslationLine(
                startTimeMs = line.start,
                content = content,
                translation = lanes.translation,
                romanization = lanes.romanization,
            )
        }
    }

    // ---------------------------------------------------------------- the lane rules

    /**
     * One line's script verdict.
     *
     * - QQ's pseudo-translation tags are stripped first: [kana:…] / [roma:…] inside a
     *   QRC/LRC translation body is a **reading**, not a translation; a line that holds
     *   only such a tag is judged by the tag's content.
     * - Han + kana = [LaneKind.MIXED] (a Japanese original, neither lane's content).
     * - Latin only: a line that splits into Japanese syllables is
     *   [LaneKind.ROMANIZATION] (kimi no na wa, shitauchi, di yi); anything else is
     *   [LaneKind.LATIN] (Hello world, Second line).
     */
    fun classifyKind(text: String?): LaneKind {
        val visible = visibleLaneText(text)
        val counts = countScripts(visible) ?: return LaneKind.EMPTY
        return when {
            counts.other > 0 -> LaneKind.OTHER
            counts.han > 0 && counts.kana > 0 -> LaneKind.MIXED
            counts.han > 0 && counts.latin > 0 -> LaneKind.MIXED
            counts.han > 0 -> LaneKind.CHINESE
            counts.kana > 0 -> LaneKind.KANA
            counts.latin > 0 ->
                if (looksLikeRomanization(visible)) LaneKind.ROMANIZATION else LaneKind.LATIN
            else -> LaneKind.EMPTY
        }
    }

    /**
     * Whether this line may be published as a **Chinese translation**: it must hold Han
     * and no other script; kana is allowed only inside brackets.
     *
     * Han + Latin (感谢你曾来过 (Thanks)) is still a translation — translations with a
     * foreign word are common. Kana in the body is not: 君の名は is a Japanese original,
     * and writing it into the translation lane is the misplacement this rule exists for.
     *
     * Brackets are different: translators often append the original or a proper noun,
     * and 别回头 (振り向かないで) / 和 ミク 一起唱 have Chinese in the body. Dropping such a
     * line would leave a poem short of translations — a song made only of such lines
     * would end up with translationLines=0 and lose the whole candidate to
     * reason=no_meaningful_lane. So the rule is: Han outside the brackets still exists,
     * and no kana outside them — 夢ならば (kana in the body) and （振り向かないで） (no Han
     * outside the brackets) are still not translations.
     */
    fun isTranslationLike(text: String?): Boolean {
        val visible = visibleLaneText(text)
        val counts = countScripts(visible) ?: return false
        if (counts.han == 0 || counts.other > 0) return false
        if (counts.kana == 0) return true
        val outside = BRACKETED_SEGMENT.replace(visible, " ")
        val outsideCounts = countScripts(outside) ?: return false
        return outsideCounts.han > 0 && outsideCounts.kana == 0 && outsideCounts.other == 0
    }

    /** Whether this line may be published as **romanization**: kana only, or Latin that spells Japanese. */
    fun isRomanizationLike(text: String?): Boolean = when (classifyKind(text)) {
        LaneKind.KANA, LaneKind.ROMANIZATION -> true
        else -> false
    }

    /**
     * Romanization verdict with the original line in hand: kana/romaji must really be
     * the reading **of this line**.
     *
     * - A Latin original (an English song) always answers false: a same-script
     *   "romanization" is just a repeat of the original, which [RomanizationPolicy]
     *   also concludes, so an English song never has a pronunciation lane.
     * - Text identical to the original (punctuation-insensitive, case-insensitive) is
     *   false too: that is the original copied into the pronunciation lane, not a reading.
     */
    fun isRomanizationLike(text: String?, original: String?): Boolean {
        if (!isRomanizationLike(text)) return false
        if (original == null) return true
        if (!hasNonLatinLetter(original)) return false
        return comparableText(text) != comparableText(original)
    }

    /**
     * Strips leading [key:value] tags (QQ's [kana:…], LRC's [ti:…]) and the voice tags
     * left inside the line. Timestamps ([00:12.00]) start with a digit and are never
     * taken for a tag.
     */
    fun stripLaneTags(text: String?): String {
        val value = text ?: return ""
        return value
            .replace(LEADING_TAGS, "")
            .replace(VOICE_TAG, " ")
            .trim()
    }

    /**
     * Re-homes both lanes of one line.
     *
     * The rules apply in order, and each only moves content, never invents it:
     *
     * 1. When both lanes hold the same text (the screenshot's duplicated romanization),
     *    only one keeps it: romanization when it is a reading, else translation.
     * 2. Content in translation that is not a Chinese translation leaves the lane: when
     *    it is this line's reading it moves into an empty romanization lane, otherwise
     *    it is dropped. QQ's trans on a Japanese song goes through exactly this step.
     * 3. Content in romanization that is a Chinese translation moves back into an empty
     *    translation lane — the "romanization published as a translation" direction old
     *    overlay records may already be stored in.
     *
     * [original] is the line's own lyric, used only by [isRomanizationLike] for the
     * same-script/same-text checks; without it the verdict degrades to text alone.
     */
    fun classifyLanes(
        translation: String?,
        romanization: String?,
        original: String? = null,
    ): LanePair {
        // Both lanes are tag-stripped first: content moved into romanization must not
        // carry a [kana:…] annotation.
        var translationLane = clean(translation)
        var romanizationLane = clean(romanization)
        if (
            translationLane != null &&
            romanizationLane != null &&
            translationLane == romanizationLane
        ) {
            if (isRomanizationLike(romanizationLane, original)) {
                translationLane = null
            } else {
                romanizationLane = null
            }
        }
        if (translationLane != null && !isTranslationLike(translationLane)) {
            if (romanizationLane.isNullOrBlank() && isRomanizationLike(translationLane, original)) {
                romanizationLane = translationLane
            }
            translationLane = null
        }
        if (romanizationLane != null && isTranslationLike(romanizationLane)) {
            if (translationLane.isNullOrBlank()) translationLane = romanizationLane
            romanizationLane = null
        }
        return LanePair(translation = translationLane, romanization = romanizationLane)
    }

    /**
     * [classifyLanes] that never throws: a verdict that fails costs the re-homing, never
     * the lanes, and the failure is logged once per process.
     *
     * This is the entry point every write path uses (the enrichment merge, the restart
     * replay, the overlay publish), so the degraded rule cannot drift between them.
     */
    fun classifyLanesOrDegrade(
        translation: String?,
        romanization: String?,
        original: String? = null,
    ): LanePair = runCatching {
        classifyLanes(translation, romanization, original)
    }.getOrElse { error ->
        reportLaneRulesUnavailable(error)
        degradeLanes(translation, romanization, original)
    }

    /**
     * The minimal verdict used when [classifyLanes] itself cannot run.
     *
     * It keeps the one rule that matters — Han in the translation lane, kana or
     * Japanese-spelled Latin in the romanization lane, everything else in neither — and
     * it never passes the provider's fields through: QQ Music answers a Japanese song's
     * trans with kana or romaji, and publishing that as the translation is the bug.
     *
     * A line this approximation cannot place (an English sentence, another script,
     * whitespace or punctuation only) is put in no lane at all. Dropping a lane costs a
     * line; publishing a reading as a translation costs the song.
     */
    fun degradeLanes(
        translation: String?,
        romanization: String?,
        original: String?,
    ): LanePair {
        var translationLane = clean(translation)
        var romanizationLane = clean(romanization)
        if (translationLane != null && translationLane == romanizationLane) {
            if (isRomanizationLike(romanizationLane, original)) {
                translationLane = null
            } else {
                romanizationLane = null
            }
        }
        if (translationLane != null && !isChineseLane(translationLane)) {
            if (romanizationLane.isNullOrBlank() && isRomanizationLike(translationLane, original)) {
                romanizationLane = translationLane
            }
            translationLane = null
        }
        if (romanizationLane != null && isChineseLane(romanizationLane)) {
            if (translationLane.isNullOrBlank()) translationLane = romanizationLane
            romanizationLane = null
        }
        return LanePair(translation = translationLane, romanization = romanizationLane)
    }

    /** Han in, kana/other script out: the translation lane's minimum content rule. */
    private fun isChineseLane(text: String): Boolean {
        val counts = countScripts(visibleLaneText(text)) ?: return false
        return counts.han > 0 && counts.kana == 0 && counts.other == 0
    }

    /** One line for the whole process, naming the type and message that stopped the verdict. */
    private fun reportLaneRulesUnavailable(error: Throwable) {
        if (!laneRulesReported.compareAndSet(false, true)) return
        val line = "lane-classifier-unavailable type=" + error.javaClass.name +
            " message=" + (error.message ?: "-")
        val sink = diagnostic
        if (sink != null) {
            runCatching { sink(line) }
        } else {
            runCatching { System.err.println(line) }
        }
    }

    // ------------------------------------------------------------------ scripts

    private class ScriptCounts(val han: Int, val kana: Int, val latin: Int, val other: Int)

    /** Visible text after tag stripping; a line holding only a voice tag uses the tag's content. */
    private fun visibleLaneText(text: String?): String {
        val value = text ?: return ""
        val stripped = stripLaneTags(value)
        if (stripped.isNotEmpty()) return stripped
        val tagBody = VOICE_TAG.find(value)?.groupValues?.getOrNull(1)?.trim().orEmpty()
        return stripLaneTags(tagBody)
    }

    /** Lane content: the visible text after tag stripping; an empty string counts as none. */
    private fun clean(value: String?): String? {
        val text = value?.trim()?.takeIf(String::isNotEmpty) ?: return null
        return visibleLaneText(text).takeIf(String::isNotEmpty)
    }

    private fun countScripts(visible: String): ScriptCounts? {
        if (visible.isEmpty()) return null
        var han = 0
        var kana = 0
        var latin = 0
        var other = 0
        var index = 0
        while (index < visible.length) {
            val codePoint = visible.codePointAt(index)
            index += Character.charCount(codePoint)
            if (!Character.isLetter(codePoint)) continue
            val script = Character.UnicodeScript.of(codePoint)
            // ー ・ 々 are COMMON: they follow the preceding character's script and must not
            // become "other", or コーヒー would read as OTHER.
            if (
                script == Character.UnicodeScript.COMMON ||
                script == Character.UnicodeScript.INHERITED
            ) {
                continue
            }
            when {
                isKana(codePoint) -> kana += 1
                isHan(codePoint) -> han += 1
                script == Character.UnicodeScript.LATIN -> latin += 1
                else -> other += 1
            }
        }
        if (han + kana + latin + other == 0) return null
        return ScriptCounts(han = han, kana = kana, latin = latin, other = other)
    }

    private fun isKana(codePoint: Int): Boolean = when (codePoint) {
        in 0x3040..0x309F, // hiragana
        in 0x30A0..0x30FF, // katakana
        in 0x31F0..0x31FF, // katakana phonetic extensions
        in 0xFF66..0xFF9F, // halfwidth katakana
        in 0x1B000..0x1B16F, // kana supplement / kana extended-a
        -> true

        else -> false
    }

    private fun isHan(codePoint: Int): Boolean =
        Character.UnicodeScript.of(codePoint) == Character.UnicodeScript.HAN

    private fun hasNonLatinLetter(text: String): Boolean {
        var index = 0
        while (index < text.length) {
            val codePoint = text.codePointAt(index)
            index += Character.charCount(codePoint)
            if (!Character.isLetter(codePoint)) continue
            val script = Character.UnicodeScript.of(codePoint)
            if (
                script == Character.UnicodeScript.COMMON ||
                script == Character.UnicodeScript.INHERITED
            ) {
                continue
            }
            if (script != Character.UnicodeScript.LATIN) return true
        }
        return false
    }

    // ------------------------------------------------------------------- romaji

    /**
     * Whether the whole line splits as Japanese romaji: every word is (consonant
     * cluster) + vowel, a lone n is ん, and a doubled consonant only marks っ — no other
     * clusters and no word-final consonants.
     *
     * English words almost all violate this: world (rl), second (nd), clouds (ds),
     * thanks (nks) do not split; kimi no na wa, shitauchi, arigatou, yokatta,
     * Haro warudo do.
     */
    private fun looksLikeRomanization(text: String): Boolean {
        val tokens = text.lowercase(Locale.ROOT)
            .split(TOKEN_SEPARATOR)
            .filter(String::isNotEmpty)
        if (tokens.isEmpty()) return false
        return tokens.all { token -> isRomanizedToken(token) }
    }

    private fun isRomanizedToken(token: String): Boolean {
        val value = normalizeMacrons(token)
        if (value.isEmpty()) return false
        var index = 0
        var syllables = 0
        while (index < value.length) {
            val character = value[index]
            if (character !in 'a'..'z') return false
            if (character == 'n' && (index + 1 >= value.length || value[index + 1] !in VOWELS)) {
                index += 1
                syllables += 1
                continue
            }
            // 促音: a doubled consonant is one っ and no syllable (yokatta, kitto).
            if (
                index + 1 < value.length &&
                character != 'n' &&
                character !in VOWELS &&
                value[index + 1] == character
            ) {
                index += 1
                continue
            }
            val onset = onsetAt(value, index)
            val vowelIndex = index + onset.length
            if (vowelIndex >= value.length || value[vowelIndex] !in VOWELS) return false
            index = vowelIndex + 1
            syllables += 1
        }
        return syllables > 0
    }

    private fun onsetAt(value: String, index: Int): String {
        for (onset in ONSETS) {
            if (value.startsWith(onset, index)) return onset
        }
        return ""
    }

    private fun normalizeMacrons(value: String): String = buildString(value.length) {
        value.forEach { character -> append(MACRONS[character] ?: character) }
    }

    /** Text without punctuation, lowercased: tells "the pronunciation lane holds the original". */
    private fun comparableText(text: String?): String {
        val normalized = text.orEmpty().lowercase(Locale.ROOT)
        return buildString(normalized.length) {
            var index = 0
            while (index < normalized.length) {
                val codePoint = normalized.codePointAt(index)
                if (Character.isLetterOrDigit(codePoint)) appendCodePoint(codePoint)
                index += Character.charCount(codePoint)
            }
        }
    }

    private fun text(line: LyricsLine): String =
        line.words.joinToString("") { it.text }.trim()

    /**
     * Bracketed segments: (…), （…）, […], 【…】, {…}. Half- and full-width both, because
     * translations use either.
     */
    private val BRACKETED_SEGMENT = Regex("\\([^)]*\\)|（[^）]*）|\\[[^\\]]*]|【[^】]*】|\\{[^}]*}")

    /** Leading [key:value] tags; the key starts with a letter, so timestamps are never tags. */
    private val LEADING_TAGS = Regex("""^\s*(?:\[[A-Za-z][A-Za-z0-9_]*\s*:[^\[\]]*]\s*)+""")

    /** Voice tags anywhere: QQ's [kana:…] / [roma:…] are annotations, not body text. */
    private val VOICE_TAG = Regex(
        """\[\s*(?:kana|roma|romaji|trans|translation)\s*:([^\[\]]*)]""",
        RegexOption.IGNORE_CASE,
    )

    private val TOKEN_SEPARATOR = Regex("[^\\p{L}\\p{N}]+")

    private val VOWELS = setOf('a', 'e', 'i', 'o', 'u')

    private val MACRONS = mapOf(
        'ā' to 'a',
        'ē' to 'e',
        'ī' to 'i',
        'ō' to 'o',
        'ū' to 'u',
    )

    /** Consonant clusters before single consonants, so shi is not split as s + hi. */
    private val ONSETS = listOf(
        "ky", "gy", "sh", "ch", "ts", "ny", "hy", "by", "py", "my", "ry", "sy", "zy", "dy", "ty",
        "b", "c", "d", "f", "g", "h", "j", "k", "l", "m", "n", "p", "q", "r", "s", "t", "v", "w",
        "x", "y", "z",
    )
}
