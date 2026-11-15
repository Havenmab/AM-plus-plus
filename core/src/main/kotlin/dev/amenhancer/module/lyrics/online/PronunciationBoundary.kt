package dev.amenhancer.module.lyrics.online

import org.json.JSONArray
import org.json.JSONObject

/**
 * Word-boundary planning for the romanization row: the model half of the splice in
 * [dev.amenhancer.module.hook.LyricsPronunciationWordAlignment].
 *
 * The rules already decide the boundaries they can prove: a container of nothing but marks, the
 * blank Apple appends after a word, a wrapped line, and a container carrying the whole word's
 * reading are all hard. What they cannot prove is the rest — whether マジカル is one word, whether
 * the ト of トマト heads a word or ends one, whether a hiragana particle belongs to the word before
 * it. This object asks a model for exactly those, and nothing else:
 *
 *  - [hardBoundaries] turns the provable facts into a lock set; the locks bound stretches, and only
 *    a stretch holding at least two tokens is up to the model ([uncertain]).
 *  - [buildSystemPrompt]/[buildUserPrompt] render the request: one JSON line per lyric line with the
 *    whole line text, its two neighbours and its tokens. Feeding only the tokens measurably makes
 *    the model split katakana runs wrongly and emit an extra line; the line text and its neighbours
 *    are what keeps the row count and the groupings honest.
 *  - [parsePlan] reads the answer back, [validateLine]/[validatePlan] enforce the contract, and
 *    [mergeLine] projects an accepted answer onto the lock set, so a model that steps outside it is
 *    refused whole rather than partly trusted.
 *
 * Everything here is a pure function over values: no Android type, no I/O, no clock. The network
 * call and the cache are a later layer, which is why this file is testable in core/src/jvmTest.
 */
object PronunciationBoundary {

    /**
     * Version of the rules, the prompt and the merge contract together.
     *
     * A cache entry is only reusable while this number matches, so any change to [hardBoundaries],
     * [SYSTEM_PROMPT], the validation or the merge must bump it.
     */
    const val RULES_VERSION = 1

    /** The most kana one word may hold; matches the host splice's own cap. */
    const val DEFAULT_MAX_KANA = 6

    /** A model group this many times the kana cap is refused for its stretch (geometry guard). */
    const val SOFT_MAX_KANA_FACTOR = 2

    /** One request carries at most this many lines; longer songs are sliced. */
    const val MAX_LINES_PER_REQUEST = 60

    /** Rough character budget for one request's user prompt. */
    const val MAX_INPUT_CHARS = 24_000

    /**
     * Katakana particles, as [SYSTEM_PROMPT]'s rule 4 names them: a word of their own inside a
     * katakana word, a loanword's head at the start of one. Same set the host splice uses.
     *
     * Kept here as the one place the set is written down; [hardBoundaries] never locks on it (see
     * there), because the containers cannot tell the two readings apart.
     */
    const val KATAKANA_PARTICLES = "トデハヲニガモノネヨ"

    /**
     * Marks that belong to the lyric text but never to a reading, and the blanks Apple appends after
     * a word. The same sets the host's centring correction uses; they live here so a document can be
     * stripped without reaching into the view layer.
     */
    const val PUNCTUATION = "！？!?。、，,．.：:；;…‥・「」『』（）()【】〔〕〜～〝〟＂＇“”‘’"

    /** Blanks a reading never covers either. */
    const val EDGE_BLANKS = " \u00A0\u3000\t"

    /** Whether [character] is a mark or a blank — neither is text a reading covers. */
    fun isEdgeIgnorable(character: Char): Boolean =
        character in PUNCTUATION || character in EDGE_BLANKS

    /** [text] without the marks and blanks a reading never covers at its ends. */
    fun coreText(text: String): String {
        var start = 0
        var end = text.length
        while (start < end && isEdgeIgnorable(text[start])) start++
        while (end > start && isEdgeIgnorable(text[end - 1])) end--
        return text.substring(start, end)
    }

    // ---------------------------------------------------------------------------------------
    // Model
    // ---------------------------------------------------------------------------------------

    /**
     * One lyric container as the planner sees it.
     *
     * [kanaCount] is passed in rather than derived from [kana] because only the caller strips the
     * marks: a container of nothing but marks holds ！ but covers zero kana, and that zero is what
     * makes it a mark token. A container that merely has no reading of its own — the ー of a
     * loan-word, or a Latin word Apple gave no romanization — still counts its kana, so a wrong zero
     * here would lock a kana that is not a mark.
     */
    data class BoundaryToken(
        val index: Int,
        val kana: String,
        val kanaCount: Int,
        val reading: String? = null,
        /** Apple appended the whitespace that follows the word to this container's text. */
        val trailingBlank: Boolean = false,
        /** Wrapped-line index: containers with a different top sit on another visual line. */
        val lineTop: Int = 0,
        /** This container carries the reading of the whole word, so it ends the word. */
        val wholeWordReading: Boolean = false,
    ) {
        /** A container of nothing but marks: it shows no reading and is a word of its own. */
        val isMarkOnly: Boolean get() = kanaCount == 0

        /** The glyph the prompt shows for this token when it carries no reading of its own. */
        val promptReading: String get() = reading ?: kana
    }

    /** One line of the request: the whole line text, its neighbours and its tokens. */
    data class BoundaryRequestLine(
        val index: Int,
        /** The line's identity, used to find the plan from the view tree (the kana join). */
        val lineKey: String,
        /** The lyric line exactly as it is sung, punctuation included. */
        val line: String,
        val tokens: List<BoundaryToken>,
        val previousLine: String? = null,
        val nextLine: String? = null,
    )

    /** Everything one request carries. */
    data class BoundaryRequest(
        val songId: String,
        val lines: List<BoundaryRequestLine>,
        val title: String? = null,
        val artist: String? = null,
        val language: String? = null,
    )

    /** One line's answer: groups of container indices, contiguous and ascending. */
    data class BoundaryLine(val lineKey: String, val groups: List<IntRange>) {
        /** The groups as the index lists the model emits. */
        val indexGroups: List<List<Int>> get() = groups.map { group -> group.toList() }
    }

    /** A whole song's answer. */
    data class BoundaryPlan(
        val songId: String,
        val rulesVersion: Int,
        val lines: List<BoundaryLine>,
    )

    /** The provable boundaries of one line. */
    data class HardBoundaries(
        val alone: List<Boolean>,
        val start: List<Boolean>,
        val end: List<Boolean>,
    ) {
        val size: Int get() = alone.size

        /** The stretch that holds [index], as an inclusive range. */
        fun segmentOf(index: Int): IntRange {
            var first = index
            while (first > 0 && !start[first]) first--
            var last = index
            while (last < size - 1 && !start[last + 1]) last++
            return first..last
        }

        /** The stretches between two locks, in order; every token lands in exactly one. */
        fun segments(): List<IntRange> {
            if (size == 0) return emptyList()
            val found = ArrayList<IntRange>(4)
            var first = 0
            for (index in 1 until size) {
                if (start[index]) {
                    found += first..(index - 1)
                    first = index
                }
            }
            found += first..(size - 1)
            return found
        }
    }

    /** Why an answer was refused; [code] is what the log line carries. */
    sealed class BoundaryError {
        abstract val code: String

        data class Malformed(val reason: String) : BoundaryError() {
            override val code: String get() = "malformed"
        }

        data class LineCountMismatch(val expected: Int, val actual: Int) : BoundaryError() {
            override val code: String get() = "line-count"
        }

        data class MissingLine(val line: Int) : BoundaryError() {
            override val code: String get() = "missing-line"
        }

        data class UnexpectedLine(val line: Int) : BoundaryError() {
            override val code: String get() = "unexpected-line"
        }

        data class EmptyGroups(val line: Int) : BoundaryError() {
            override val code: String get() = "empty-groups"
        }

        data class UnknownIndex(val line: Int, val index: Int) : BoundaryError() {
            override val code: String get() = "unknown-index"
        }

        data class DuplicateIndex(val line: Int, val index: Int) : BoundaryError() {
            override val code: String get() = "duplicate-index"
        }

        data class MissingIndex(val line: Int, val index: Int) : BoundaryError() {
            override val code: String get() = "missing-index"
        }

        data class NotContiguous(val line: Int, val group: List<Int>) : BoundaryError() {
            override val code: String get() = "not-contiguous"
        }

        data class OutOfOrder(val line: Int, val group: List<Int>) : BoundaryError() {
            override val code: String get() = "out-of-order"
        }

        data class CrossesHardBoundary(val line: Int, val index: Int) : BoundaryError() {
            override val code: String get() = "crosses-boundary"
        }

        data class SplitsAloneToken(val line: Int, val index: Int) : BoundaryError() {
            override val code: String get() = "splits-alone"
        }

        data class LineTooLong(val line: Int, val group: List<Int>, val kana: Int) : BoundaryError() {
            override val code: String get() = "line-too-long"
        }
    }

    /** What to do with an answer: use it, or keep the rules and log why. */
    sealed class Outcome {
        data class Accepted(val plan: BoundaryPlan) : Outcome()
        data class Rejected(val error: BoundaryError) : Outcome()
    }

    /** The merged grouping of one line, plus the stretches where the rules had to win. */
    sealed class MergeOutcome {
        data class Groups(val groups: List<IntRange>, val softened: List<IntRange>) : MergeOutcome()
        data class Rejected(val error: BoundaryError) : MergeOutcome()
    }

    // ---------------------------------------------------------------------------------------
    // Hard boundaries, stretches, uncertainty
    // ---------------------------------------------------------------------------------------

    /**
     * The boundaries the rules can prove for [tokens].
     *
     * ALONE is the only mark that forbids joining at all, and it holds exactly the two facts the
     * data settles on its own: a container of nothing but marks, and a container carrying the whole
     * word's reading. START and END additionally carry the two facts that break only one side —
     * Apple's trailing blank and a wrapped line.
     *
     * A katakana particle is deliberately **not** in that set. Whether a ト is a particle or part of
     * a loanword cannot be read off the containers: マジカル ト リック has one ト after a katakana
     * and so does the ト of トマト, and a rule keyed on the neighbour would lock the second one and
     * refuse the grouping the model got right for 赤いトマトを. It is also not fixable by requiring
     * both neighbours to be katakana: トマト ト チーズ has a trailing ト surrounded by katakana. The
     * decision therefore stays with the model, where the request's own line text and the two
     * contrasting examples in [SYSTEM_PROMPT] carry it, and [uncertain] reports those tokens as open.
     */
    fun hardBoundaries(tokens: List<BoundaryToken>): HardBoundaries {
        val alone = tokens.indices.map { index ->
            val token = tokens[index]
            token.isMarkOnly || token.wholeWordReading
        }
        val start = tokens.indices.map { index ->
            val token = tokens[index]
            val previous = tokens.getOrNull(index - 1)
            alone[index] || index == 0 ||
                (previous != null &&
                    (previous.trailingBlank || previous.lineTop != token.lineTop || alone[index - 1]))
        }
        val end = tokens.indices.map { index ->
            val token = tokens[index]
            val next = tokens.getOrNull(index + 1)
            alone[index] || index == tokens.size - 1 || token.trailingBlank ||
                (next != null && (next.lineTop != token.lineTop || alone[index + 1]))
        }
        return HardBoundaries(alone, start, end)
    }

    /** The stretches between two locks; a stretch of one token is already decided. */
    fun segments(
        tokens: List<BoundaryToken>,
        hard: HardBoundaries = hardBoundaries(tokens),
    ): List<IntRange> = hard.segments()

    /**
     * Whether the model may re-decide the grouping around [index]: it sits in a stretch of at least
     * two tokens and is not a token the rules hold on their own.
     */
    fun uncertain(tokens: List<BoundaryToken>, hard: HardBoundaries, index: Int): Boolean {
        if (index !in tokens.indices) return false
        if (hard.alone[index]) return false
        val segment = hard.segmentOf(index)
        return segment.last > segment.first
    }

    // ---------------------------------------------------------------------------------------
    // Prompt
    // ---------------------------------------------------------------------------------------

    /** The measured-good system prompt: constraints only, JSON out, the hard cases as examples. */
    val SYSTEM_PROMPT: String = """
Judge word boundaries in romanized lyrics. You never rewrite, translate, merge or split any reading
text; you only decide which adjacent containers belong to the same word.

Input: one JSON object {"lines":[{...}]}. Every line has:
  i     the line index
  key   the line identifier (do not change it)
  line  the whole lyric line, exactly as sung
  prev  the previous line's text, or null
  next  the following line's text, or null
  tokens [{i,k,r}] - i is the token index inside that line, k the glyph, r its romanization
Use line, prev and next to understand the words; the tokens are only the units you group.

Hard constraints:
1. Emit exactly one output line per input line, in the same order and with the same "i".
   Never add, drop, merge or reorder lines.
2. Inside one line every token index appears exactly once. A group is a contiguous ascending list
   of token indices, the groups are contiguous and ascending, no group is empty, no group spans
   lines.
3. A token that is only punctuation is a group of its own.
4. Consecutive tokens of the same script are ONE word by default - when in doubt, merge.
   A katakana particle (ト デ ハ ヲ ニ ガ モ ノ ネ ヨ) standing inside a katakana word is a word of
   its own, but at the start of a word it is the head of a loanword (トリック, トマト).
5. A hiragana particle joins the word in front of it when that reads naturally (いつも, だね,
   だめだめ); it stands alone when it links two phrases (全身|が).
6. Never leave a token out and never repeat one. If unsure, keep the plain default grouping.

Output: STRICT JSON only, no prose and no code fences:
{"lines":[{"i":0,"g":[[0,1,2,3],[4],[5,6],[7]]}]}
"g" is the list of groups; each group lists its token indices.

Examples (line text | tokens | groups):
マジカルトリックで | マ(ma) ジ(ji) カ(ka) ル(ru) ト(to) リッ(rik) ク(ku) で(de) => [[0,1,2,3],[4],[5,6],[7]]
赤いトマトを | 赤(aka) い(i) ト(to) マ(ma) ト(to) を(o) => [[0,1,2,3,4],[5]]
キラキラ！ | キ(ki) ラ(ra) キ(ki) ラ(ra) ！(!) => [[0,1,2,3],[4]]
ダメダメ！ | ダ(da) メ(me) ダ(da) メ(me) ！(!) => [[0,1,2,3],[4]]
仕掛けてる | 仕(shika) 掛(kake) け(ke) て(te) る(ru) => [[0,1,2,3,4]]
何してるの？ | 何(nani) し(shi) て(te) る(ru) の？(no) => [[0,1,2,3],[4]]
""".trimIndent()

    /** The system message one request sends; kept as a function so a version can vary it. */
    fun buildSystemPrompt(): String = SYSTEM_PROMPT

    /**
     * The user message for [request]: the JSON input the model groups, then the output shape.
     *
     * The line text and both neighbours are part of the payload on purpose — with tokens alone the
     * model mis-splits katakana runs and invents an extra line.
     */
    fun buildUserPrompt(request: BoundaryRequest): String {
        val prompt = StringBuilder(request.lines.size * 96 + 256)
        prompt.append("song=").append(quote(request.title ?: "-"))
        prompt.append(" artist=").append(quote(request.artist ?: "-"))
        prompt.append(" language=").append(quote(request.language ?: "und"))
        prompt.append('\n')
        prompt.append("{\"lines\":[")
        request.lines.forEachIndexed { position, line ->
            if (position > 0) prompt.append(',')
            prompt.append("{\"i\":").append(line.index)
            prompt.append(",\"key\":").append(quote(line.lineKey))
            prompt.append(",\"line\":").append(quote(line.line))
            prompt.append(",\"prev\":").append(line.previousLine?.let(::quote) ?: "null")
            prompt.append(",\"next\":").append(line.nextLine?.let(::quote) ?: "null")
            prompt.append(",\"tokens\":[")
            line.tokens.forEachIndexed { tokenPosition, token ->
                if (tokenPosition > 0) prompt.append(',')
                prompt.append("{\"i\":").append(token.index)
                prompt.append(",\"k\":").append(quote(token.kana))
                prompt.append(",\"r\":").append(quote(token.promptReading))
                prompt.append('}')
            }
            prompt.append("]}")
        }
        prompt.append("]}")
        prompt.append('\n')
        prompt.append("Return the same number of lines, in this order, as ")
        prompt.append("{\"lines\":[{\"i\":<line index>,\"g\":[[<token index>,...],...]}]}.")
        return prompt.toString()
    }

    /**
     * The request split into payloads of at most [maxLinesPerRequest] lines and [maxChars] of user
     * prompt, so a long song is answered in several verified pieces instead of a truncated one.
     */
    fun slices(
        request: BoundaryRequest,
        maxLinesPerRequest: Int = MAX_LINES_PER_REQUEST,
        maxChars: Int = MAX_INPUT_CHARS,
    ): List<BoundaryRequest> {
        if (request.lines.isEmpty()) return emptyList()
        val limit = maxLinesPerRequest.coerceAtLeast(1)
        val slices = ArrayList<BoundaryRequest>(2)
        var current = ArrayList<BoundaryRequestLine>(limit)
        fun flush() {
            if (current.isEmpty()) return
            slices += request.copy(lines = current.toList())
            current = ArrayList(limit)
        }
        for (line in request.lines) {
            val candidate = current + line
            val tooMany = candidate.size > limit
            val tooLong = buildUserPrompt(request.copy(lines = candidate)).length > maxChars
            if (current.isNotEmpty() && (tooMany || tooLong)) flush()
            current += line
        }
        flush()
        return slices
    }

    private fun quote(value: String): String {
        val builder = StringBuilder(value.length + 2)
        builder.append('"')
        for (character in value) {
            when (character) {
                '"' -> builder.append("\\\"")
                '\\' -> builder.append("\\\\")
                '\n' -> builder.append("\\n")
                '\r' -> builder.append("\\r")
                '\t' -> builder.append("\\t")
                else -> if (character < ' ') builder.append(' ') else builder.append(character)
            }
        }
        builder.append('"')
        return builder.toString()
    }

    // ---------------------------------------------------------------------------------------
    // Parsing
    // ---------------------------------------------------------------------------------------

    /**
     * Reads the model's answer against [request].
     *
     * Only shape is normalised here — a code fence around the object, a numeric string where a
     * number belongs, unordered indices inside a group. Everything the contract depends on is
     * checked by [validatePlan], and one violation drops the whole answer rather than a part of it.
     */
    fun parsePlan(
        content: String,
        request: BoundaryRequest,
        rulesVersion: Int = RULES_VERSION,
    ): Outcome {
        val payload = payloadOf(content)
            ?: return Outcome.Rejected(BoundaryError.Malformed("no-json-object"))
        val entries = LinkedHashMap<Int, JSONObject>()
        val failure = runCatching {
            val lines: JSONArray = JSONObject(payload).optJSONArray("lines")
                ?: return@runCatching BoundaryError.Malformed("no-lines")
            for (position in 0 until lines.length()) {
                val entry = lines.optJSONObject(position)
                    ?: return@runCatching BoundaryError.Malformed("line-not-object")
                val raw = entry.opt("i")
                val index = when (raw) {
                    is Number -> raw.toInt()
                    is String -> raw.trim().toIntOrNull()
                    else -> null
                } ?: return@runCatching BoundaryError.Malformed("line-without-i")
                if (entries.put(index, entry) != null) {
                    return@runCatching BoundaryError.Malformed("duplicate-line")
                }
            }
            null
        }.getOrElse { return Outcome.Rejected(BoundaryError.Malformed("unreadable")) }
        if (failure != null) return Outcome.Rejected(failure)

        if (entries.size != request.lines.size) {
            return Outcome.Rejected(
                BoundaryError.LineCountMismatch(request.lines.size, entries.size),
            )
        }
        val groups = LinkedHashMap<Int, List<IntRange>>(entries.size)
        for (line in request.lines) {
            val entry = entries[line.index]
                ?: return Outcome.Rejected(BoundaryError.MissingLine(line.index))
            val raw = entry.optJSONArray("g")
                ?: return Outcome.Rejected(BoundaryError.EmptyGroups(line.index))
            if (raw.length() == 0) {
                return Outcome.Rejected(BoundaryError.EmptyGroups(line.index))
            }
            val ranges = ArrayList<IntRange>(raw.length())
            for (position in 0 until raw.length()) {
                val group = raw.optJSONArray(position)
                    ?: return Outcome.Rejected(BoundaryError.Malformed("group-not-array"))
                val indices = ArrayList<Int>(group.length())
                for (slot in 0 until group.length()) {
                    val value = group.opt(slot)
                    val index = when (value) {
                        is Number -> value.toInt()
                        is String -> value.trim().toIntOrNull()
                        else -> null
                    } ?: return Outcome.Rejected(BoundaryError.Malformed("index-not-number"))
                    indices += index
                }
                if (indices.isEmpty()) {
                    return Outcome.Rejected(BoundaryError.EmptyGroups(line.index))
                }
                // A range cannot carry a hole, so the hole has to be refused here, before the list
                // becomes one: [0,2] is not a group, and repairing it would silently claim 1.
                indices.sort()
                if (indices.last() - indices.first() + 1 != indices.size) {
                    return Outcome.Rejected(BoundaryError.NotContiguous(line.index, indices))
                }
                ranges += indices.first()..indices.last()
            }
            ranges.sortBy { range -> range.first }
            groups[line.index] = ranges
        }
        val error = validatePlan(request, groups)
        if (error != null) return Outcome.Rejected(error)
        return Outcome.Accepted(
            BoundaryPlan(
                songId = request.songId,
                rulesVersion = rulesVersion,
                lines = request.lines.map { line ->
                    BoundaryLine(line.lineKey, groups.getValue(line.index))
                },
            ),
        )
    }

    /** The JSON object inside an answer, tolerating a code fence around it. */
    private fun payloadOf(content: String): String? {
        val start = content.indexOf('{')
        val end = content.lastIndexOf('}')
        if (start < 0 || end <= start) return null
        return content.substring(start, end + 1)
    }

    // ---------------------------------------------------------------------------------------
    // Validation
    // ---------------------------------------------------------------------------------------

    /**
     * Checks one line's answer against the containers it claims to partition.
     *
     * Returns null when the answer may be used, or the first violation found. The answer is never
     * repaired here: a broken one is dropped whole.
     */
    fun validateLine(
        tokens: List<BoundaryToken>,
        groups: List<IntRange>,
        hard: HardBoundaries = hardBoundaries(tokens),
        line: Int = 0,
    ): BoundaryError? {
        if (groups.isEmpty()) return BoundaryError.EmptyGroups(line)
        val seen = HashSet<Int>(tokens.size)
        var previousLast = Int.MIN_VALUE
        for (group in groups) {
            if (group.first > group.last) return BoundaryError.EmptyGroups(line)
            if (group.first !in tokens.indices || group.last !in tokens.indices) {
                return BoundaryError.UnknownIndex(line, group.first)
            }
            val indices = group.toList()
            for (index in indices) {
                if (index !in tokens.indices) return BoundaryError.UnknownIndex(line, index)
                if (!seen.add(index)) return BoundaryError.DuplicateIndex(line, index)
            }
            if (indices.last() - indices.first() + 1 != indices.size) {
                return BoundaryError.NotContiguous(line, indices)
            }
            if (group.first <= previousLast) return BoundaryError.OutOfOrder(line, indices)
            previousLast = group.last()
            for (index in (group.first + 1)..group.last) {
                if (hard.start[index]) return BoundaryError.CrossesHardBoundary(line, index)
            }
            for (index in group.first until group.last) {
                if (hard.end[index]) return BoundaryError.CrossesHardBoundary(line, index)
            }
            if (indices.size > 1) {
                for (index in indices) {
                    if (hard.alone[index]) return BoundaryError.SplitsAloneToken(line, index)
                }
            }
        }
        if (seen.size != tokens.size) {
            val missing = tokens.indices.firstOrNull { it !in seen }
            if (missing != null) return BoundaryError.MissingIndex(line, missing)
        }
        return null
    }

    /**
     * The length guard, kept apart from [validateLine] on purpose.
     *
     * A group longer than [maxKana] is a row that cannot hold it, not a broken answer: [mergeLine]
     * keeps the rules' grouping for that stretch instead of dropping the whole song, which is what
     * the contract allows. [SOFT_MAX_KANA_FACTOR] is the tolerance before that happens.
     */
    fun validateLength(
        tokens: List<BoundaryToken>,
        groups: List<IntRange>,
        maxKana: Int = DEFAULT_MAX_KANA,
        line: Int = 0,
    ): BoundaryError? {
        for (group in groups) {
            val indices = group.toList()
            val kana = indices.sumOf { tokens[it].kanaCount }
            if (kana > maxKana * SOFT_MAX_KANA_FACTOR) {
                return BoundaryError.LineTooLong(line, indices, kana)
            }
        }
        return null
    }

    /**
     * Checks a whole answer: one entry per requested line, none missing, none unexpected, and each
     * line valid on its own.
     */
    fun validatePlan(
        request: BoundaryRequest,
        groupsByLine: Map<Int, List<IntRange>>,
    ): BoundaryError? {
        val requested = request.lines.map { it.index }.toSet()
        for (line in request.lines) {
            if (line.index !in groupsByLine) return BoundaryError.MissingLine(line.index)
        }
        for (index in groupsByLine.keys) {
            if (index !in requested) return BoundaryError.UnexpectedLine(index)
        }
        if (groupsByLine.size != request.lines.size) {
            return BoundaryError.LineCountMismatch(request.lines.size, groupsByLine.size)
        }
        for (line in request.lines) {
            val error = validateLine(
                tokens = line.tokens,
                groups = groupsByLine.getValue(line.index),
                line = line.index,
            )
            if (error != null) return error
        }
        return null
    }

    // ---------------------------------------------------------------------------------------
    // Merge
    // ---------------------------------------------------------------------------------------

    /**
     * Projects an accepted answer onto the lock set: inside a stretch the answer wins, across a
     * lock it cannot, and a stretch whose answer is geometrically impossible keeps the rules' own
     * grouping ([baseline]).
     *
     * An answer that steps outside the locks is refused whole ([MergeOutcome.Rejected]); one that
     * merely asks for a group too long for the row is softened stretch by stretch, which is the
     * only repair this contract allows.
     */
    fun mergeLine(
        tokens: List<BoundaryToken>,
        baseline: List<IntRange>,
        plan: BoundaryLine,
        hard: HardBoundaries = hardBoundaries(tokens),
        maxKana: Int = DEFAULT_MAX_KANA,
    ): MergeOutcome {
        val error = validateLine(tokens, plan.groups, hard)
        if (error != null) return MergeOutcome.Rejected(error)

        val softened = ArrayList<IntRange>(2)
        val merged = ArrayList<IntRange>(plan.groups.size)
        for (segment in hard.segments()) {
            if (segment.last == segment.first) {
                merged += segment
                continue
            }
            val inside = plan.groups.filter { it.first >= segment.first && it.last <= segment.last }
            val tooLong = inside.any { group ->
                group.sumOf { tokens[it].kanaCount } > maxKana * SOFT_MAX_KANA_FACTOR
            }
            if (inside.isEmpty() || tooLong) {
                softened += segment
                merged += clipBaseline(baseline, segment)
                continue
            }
            merged += inside
        }
        merged.sortBy { it.first }
        val recheck = validateLine(tokens, merged, hard)
            ?: validateLength(tokens, merged, maxKana * SOFT_MAX_KANA_FACTOR)
        if (recheck != null) return MergeOutcome.Rejected(recheck)
        return MergeOutcome.Groups(merged, softened)
    }

    /** [baseline] restricted to [segment], so a softened stretch still covers it exactly. */
    private fun clipBaseline(baseline: List<IntRange>, segment: IntRange): List<IntRange> {
        val clipped = ArrayList<IntRange>(2)
        var cursor = segment.first
        for (range in baseline.sortedBy { it.first }) {
            if (range.last < segment.first || range.first > segment.last) continue
            val first = range.first.coerceAtLeast(cursor)
            val last = range.last.coerceAtMost(segment.last)
            if (first > last) continue
            if (first > cursor) clipped += cursor..(first - 1)
            clipped += first..last
            cursor = last + 1
        }
        if (cursor <= segment.last) clipped += cursor..segment.last
        return clipped
    }
}
