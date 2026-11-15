package dev.amenhancer.module.lyrics.online

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The boundary planner: what the rules can prove, what is left to the model, what an answer must
 * satisfy, and how an answer is projected back onto the locks.
 *
 * The cases are the shapes the real lyric data actually showed — マジカルトリックで, 赤いトマトを,
 * キラキラ！, ダメダメ！, 仕掛けてる, 何してるの？ — so a rule change that breaks one of them is
 * caught here rather than on a device.
 */
class PronunciationBoundaryTest {

    // ---------------------------------------------------------------------------------------
    // Hard boundaries
    // ---------------------------------------------------------------------------------------

    @Test
    fun `a mark of its own is alone and ends the stretch either side of it`() {
        val tokens = listOf(
            token(0, "ス", reading = "su"),
            token(1, "！", kanaCount = 0),
            token(2, "ア", reading = "a"),
        )

        val hard = PronunciationBoundary.hardBoundaries(tokens)

        assertEquals(listOf(false, true, false), hard.alone)
        assertEquals(listOf(true, true, true), hard.start)
        assertEquals(listOf(true, true, true), hard.end)
        assertEquals(listOf(0..0, 1..1, 2..2), hard.segments())
    }

    @Test
    fun `the blank Apple appends after a word ends it`() {
        val tokens = listOf(
            token(0, "Wake", reading = "weiku", trailingBlank = true),
            token(1, "up", reading = "appu"),
        )

        val hard = PronunciationBoundary.hardBoundaries(tokens)

        assertEquals(listOf(false, false), hard.alone)
        assertEquals(listOf(true, true), hard.start)
        assertEquals(listOf(true, true), hard.end)
        assertEquals(listOf(0..0, 1..1), hard.segments())
    }

    @Test
    fun `a wrapped line breaks the stretch`() {
        val tokens = listOf(
            token(0, "ス", reading = "su", lineTop = 0),
            token(1, "ア", reading = "a", lineTop = 40),
        )

        assertEquals(listOf(0..0, 1..1), PronunciationBoundary.hardBoundaries(tokens).segments())
    }

    @Test
    fun `a container carrying the whole word reading is alone`() {
        val tokens = listOf(
            token(0, "ス", reading = "suappu", wholeWord = true),
            token(1, "アップ", kanaCount = 2),
            token(2, "！", kanaCount = 0),
        )

        val hard = PronunciationBoundary.hardBoundaries(tokens)

        assertEquals(listOf(true, false, true), hard.alone)
        assertEquals(listOf(0..0, 1..1, 2..2), hard.segments())
    }

    @Test
    fun `a katakana particle inside a katakana word is alone but a word head is not`() {
        val midWord = PronunciationBoundary.hardBoundaries(majikaruTokens())
        // マ ジ カ ル | ト | リッ ク で
        assertEquals(listOf(false, false, false, false, true, false, false, false), midWord.alone)
        assertEquals(listOf(0..3, 4..4, 5..7), midWord.segments())

        val wordHead = PronunciationBoundary.hardBoundaries(tomatoTokens())
        // 赤いトマトを: the ト opens a word, so nothing is locked
        assertEquals(listOf(false, false, false, false, false, false), wordHead.alone)
        assertEquals(listOf(0..5), wordHead.segments())

        val loanword = PronunciationBoundary.hardBoundaries(torikkuTokens())
        assertEquals(listOf(false, false, false, false), loanword.alone)
    }

    // ---------------------------------------------------------------------------------------
    // Uncertainty
    // ---------------------------------------------------------------------------------------

    @Test
    fun `only a token inside a free stretch is up to the model`() {
        val tokens = majikaruTokens()
        val hard = PronunciationBoundary.hardBoundaries(tokens)

        (0..3).forEach { index ->
            assertTrue(
                "token " + index + " should be uncertain",
                PronunciationBoundary.uncertain(tokens, hard, index),
            )
        }
        assertFalse(PronunciationBoundary.uncertain(tokens, hard, 4))
        (5..7).forEach { index ->
            assertTrue(
                "token " + index + " should be uncertain",
                PronunciationBoundary.uncertain(tokens, hard, index),
            )
        }
    }

    @Test
    fun `a token with a decided neighbour is not up to the model`() {
        val tokens = listOf(
            token(0, "Wake", reading = "weiku", trailingBlank = true),
            token(1, "up", reading = "appu"),
        )
        val hard = PronunciationBoundary.hardBoundaries(tokens)

        assertFalse(PronunciationBoundary.uncertain(tokens, hard, 0))
        assertFalse(PronunciationBoundary.uncertain(tokens, hard, 1))
        assertFalse(PronunciationBoundary.uncertain(tokens, hard, -1))
        assertFalse(PronunciationBoundary.uncertain(tokens, hard, 99))
    }

    @Test
    fun `a word head inside a stretch stays open to the model`() {
        val tokens = tomatoTokens()
        val hard = PronunciationBoundary.hardBoundaries(tokens)

        (0..5).forEach { index ->
            assertTrue(
                "token " + index + " should be uncertain",
                PronunciationBoundary.uncertain(tokens, hard, index),
            )
        }
    }

    // ---------------------------------------------------------------------------------------
    // Validation V1..V9
    // ---------------------------------------------------------------------------------------

    @Test
    fun `V1 an empty answer is refused`() {
        assertEquals(
            PronunciationBoundary.BoundaryError.EmptyGroups(3),
            PronunciationBoundary.validateLine(majikaruTokens(), emptyList(), line = 3),
        )
    }

    @Test
    fun `V2 an index outside the line is refused`() {
        val error = PronunciationBoundary.validateLine(
            listOf(token(0, "ス", reading = "su"), token(1, "ア", reading = "a")),
            listOf(0..0, 5..5),
        )

        assertEquals(PronunciationBoundary.BoundaryError.UnknownIndex(0, 5), error)
    }

    @Test
    fun `V3 a repeated index is refused`() {
        val error = PronunciationBoundary.validateLine(
            listOf(token(0, "ス", reading = "su"), token(1, "ア", reading = "a")),
            listOf(0..1, 1..1),
        )

        assertEquals(PronunciationBoundary.BoundaryError.DuplicateIndex(0, 1), error)
    }

    @Test
    fun `V4 a token left out is refused`() {
        val tokens = listOf(
            token(0, "ス", reading = "su"),
            token(1, "ア", reading = "a"),
            token(2, "ッ", reading = "ppu"),
        )

        assertEquals(
            PronunciationBoundary.BoundaryError.MissingIndex(0, 2),
            PronunciationBoundary.validateLine(tokens, listOf(0..1)),
        )
    }

    @Test
    fun `V5 a group with a hole is refused while parsing`() {
        val outcome = PronunciationBoundary.parsePlan(
            "{\"lines\":[{\"i\":0,\"g\":[[0,2],[1]]}]}",
            request(line(0, "マジカルトリックで", majikaruTokens())),
        )

        val rejected = outcome as PronunciationBoundary.Outcome.Rejected
        assertEquals("not-contiguous", rejected.error.code)
    }

    @Test
    fun `V6 groups out of order are refused`() {
        val error = PronunciationBoundary.validateLine(
            listOf(token(0, "ス", reading = "su"), token(1, "ア", reading = "a")),
            listOf(1..1, 0..0),
        )

        assertEquals(PronunciationBoundary.BoundaryError.OutOfOrder(0, listOf(0)), error)
    }

    @Test
    fun `V7 a group crossing a proven boundary is refused`() {
        val tokens = listOf(
            token(0, "Wake", reading = "weiku", trailingBlank = true),
            token(1, "up", reading = "appu"),
        )

        assertEquals(
            PronunciationBoundary.BoundaryError.CrossesHardBoundary(0, 1),
            PronunciationBoundary.validateLine(tokens, listOf(0..1)),
        )
    }

    @Test
    fun `V7 a group reaching over a locked token is refused`() {
        assertEquals(
            PronunciationBoundary.BoundaryError.CrossesHardBoundary(0, 4),
            PronunciationBoundary.validateLine(majikaruTokens(), listOf(0..5, 6..7)),
        )
    }

    @Test
    fun `V8 a missing or unexpected line is refused`() {
        val request = request(
            line(0, "マジカルトリックで", majikaruTokens()),
            line(1, "キラキラ！", kirakiraTokens()),
        )

        assertEquals(
            PronunciationBoundary.BoundaryError.MissingLine(1),
            PronunciationBoundary.validatePlan(request, mapOf(0 to listOf(0..7))),
        )
        assertEquals(
            PronunciationBoundary.BoundaryError.UnexpectedLine(2),
            PronunciationBoundary.validatePlan(
                request,
                mapOf(0 to listOf(0..7), 1 to listOf(0..4), 2 to listOf(0..0)),
            ),
        )
    }

    @Test
    fun `V9 a group too long for the row is caught by the length guard only`() {
        val tokens = (0 until 5).map { index -> token(index, "あ", kanaCount = 3, reading = "a") }
        val error = PronunciationBoundary.validateLength(tokens, listOf(0..4))

        val tooLong = error as PronunciationBoundary.BoundaryError.LineTooLong
        assertEquals(listOf(0, 1, 2, 3, 4), tooLong.group)
        assertEquals(15, tooLong.kana)
        // The length is deliberately not part of validateLine: the merge softens the stretch.
        assertNull(PronunciationBoundary.validateLine(tokens, listOf(0..4)))
    }

    // ---------------------------------------------------------------------------------------
    // Merge
    // ---------------------------------------------------------------------------------------

    @Test
    fun `the model may join a word the rules had to split`() {
        val tokens = tomatoTokens()
        val baseline = listOf(0..1, 2..2, 3..4, 5..5)
        val plan = PronunciationBoundary.BoundaryLine("line", listOf(0..4, 5..5))

        val merged = PronunciationBoundary.mergeLine(tokens, baseline, plan)

        assertEquals(
            PronunciationBoundary.MergeOutcome.Groups(listOf(0..4, 5..5), emptyList()),
            merged,
        )
    }

    @Test
    fun `an answer that crosses a proven boundary is refused whole`() {
        val tokens = listOf(
            token(0, "Wake", reading = "weiku", trailingBlank = true),
            token(1, "up", reading = "appu"),
        )
        val plan = PronunciationBoundary.BoundaryLine("line", listOf(0..1))

        val merged = PronunciationBoundary.mergeLine(tokens, listOf(0..0, 1..1), plan)

        val rejected = merged as PronunciationBoundary.MergeOutcome.Rejected
        assertEquals("crosses-boundary", rejected.error.code)
    }

    @Test
    fun `the model may regroup the stretch after a locked token`() {
        val plan = PronunciationBoundary.BoundaryLine("line", listOf(0..3, 4..4, 5..7))

        val merged = PronunciationBoundary.mergeLine(
            majikaruTokens(),
            listOf(0..3, 4..4, 5..6, 7..7),
            plan,
        )

        assertEquals(
            PronunciationBoundary.MergeOutcome.Groups(listOf(0..3, 4..4, 5..7), emptyList()),
            merged,
        )
    }

    @Test
    fun `a group too long for the row falls back to the rules for that stretch`() {
        val tokens = (0 until 5).map { index -> token(index, "あ", kanaCount = 3, reading = "a") }
        val plan = PronunciationBoundary.BoundaryLine("line", listOf(0..4))

        val merged = PronunciationBoundary.mergeLine(tokens, listOf(0..1, 2..4), plan)

        assertEquals(
            PronunciationBoundary.MergeOutcome.Groups(listOf(0..1, 2..4), listOf(0..4)),
            merged,
        )
    }

    @Test
    fun `an answer identical to the rules changes nothing`() {
        val tokens = majikaruTokens()
        val baseline = listOf(0..3, 4..4, 5..6, 7..7)
        val plan = PronunciationBoundary.BoundaryLine("line", baseline)

        assertEquals(
            PronunciationBoundary.MergeOutcome.Groups(baseline, emptyList()),
            PronunciationBoundary.mergeLine(tokens, baseline, plan),
        )
    }

    // ---------------------------------------------------------------------------------------
    // Parsing
    // ---------------------------------------------------------------------------------------

    @Test
    fun `a plain answer is read back`() {
        val outcome = PronunciationBoundary.parsePlan(
            "{\"lines\":[{\"i\":0,\"g\":[[0,1,2,3],[4],[5,6],[7]]}]}",
            request(line(0, "マジカルトリックで", majikaruTokens())),
        )

        val accepted = outcome as PronunciationBoundary.Outcome.Accepted
        assertEquals(listOf(0..3, 4..4, 5..6, 7..7), accepted.plan.lines.single().groups)
        assertEquals(PronunciationBoundary.RULES_VERSION, accepted.plan.rulesVersion)
    }

    @Test
    fun `a fenced answer and numeric strings are accepted`() {
        val fence = "\u0060\u0060\u0060"
        val content = fence + "json\n" +
            "{\"lines\":[{\"i\":\"0\",\"g\":[[\"0\",\"1\",\"2\",\"3\"],[4],[5,6],[7]]}]}\n" +
            fence

        val outcome = PronunciationBoundary.parsePlan(
            content,
            request(line(0, "マジカルトリックで", majikaruTokens())),
        )

        val accepted = outcome as PronunciationBoundary.Outcome.Accepted
        assertEquals(listOf(0..3, 4..4, 5..6, 7..7), accepted.plan.lines.single().groups)
    }

    @Test
    fun `extra fields are ignored`() {
        val outcome = PronunciationBoundary.parsePlan(
            "{\"note\":\"x\",\"lines\":[{\"i\":0,\"why\":\"word\",\"g\":[[0,1,2,3],[4],[5,6],[7]]}]}",
            request(line(0, "マジカルトリックで", majikaruTokens())),
        )

        assertTrue(outcome is PronunciationBoundary.Outcome.Accepted)
    }

    @Test
    fun `an answer that is not JSON is refused`() {
        val outcome = PronunciationBoundary.parsePlan(
            "I cannot help with that.",
            request(line(0, "マジカルトリックで", majikaruTokens())),
        )

        val rejected = outcome as PronunciationBoundary.Outcome.Rejected
        assertEquals("malformed", rejected.error.code)
    }

    @Test
    fun `an answer with the wrong number of lines is refused`() {
        val outcome = PronunciationBoundary.parsePlan(
            "{\"lines\":[{\"i\":0,\"g\":[[0,1,2,3],[4],[5,6],[7]]},{\"i\":1,\"g\":[[0]]}]}",
            request(line(0, "マジカルトリックで", majikaruTokens())),
        )

        val rejected = outcome as PronunciationBoundary.Outcome.Rejected
        assertEquals("line-count", rejected.error.code)
    }

    @Test
    fun `an answer with a wrong line index is refused`() {
        val request = request(
            line(0, "マジカルトリックで", majikaruTokens()),
            line(1, "キラキラ！", kirakiraTokens()),
        )
        val outcome = PronunciationBoundary.parsePlan(
            "{\"lines\":[{\"i\":0,\"g\":[[0,1,2,3],[4],[5,6],[7]]},{\"i\":9,\"g\":[[0,1,2,3],[4]]}]}",
            request,
        )

        val rejected = outcome as PronunciationBoundary.Outcome.Rejected
        assertEquals("missing-line", rejected.error.code)
    }

    @Test
    fun `an empty group is refused`() {
        val outcome = PronunciationBoundary.parsePlan(
            "{\"lines\":[{\"i\":0,\"g\":[[0,1,2,3],[],[4,5,6,7]]}]}",
            request(line(0, "マジカルトリックで", majikaruTokens())),
        )

        val rejected = outcome as PronunciationBoundary.Outcome.Rejected
        assertEquals("empty-groups", rejected.error.code)
    }

    @Test
    fun `a duplicated line index is refused`() {
        val outcome = PronunciationBoundary.parsePlan(
            "{\"lines\":[{\"i\":0,\"g\":[[0,1,2,3],[4],[5,6],[7]]},{\"i\":0,\"g\":[[0,1,2,3],[4],[5,6],[7]]}]}",
            request(line(0, "マジカルトリックで", majikaruTokens())),
        )

        val rejected = outcome as PronunciationBoundary.Outcome.Rejected
        assertEquals("malformed", rejected.error.code)
    }

    // ---------------------------------------------------------------------------------------
    // Prompt
    // ---------------------------------------------------------------------------------------

    @Test
    fun `the system prompt carries the constraints and the measured examples`() {
        val prompt = PronunciationBoundary.buildSystemPrompt()

        assertTrue(prompt.contains("Emit exactly one output line per input line"))
        assertTrue(prompt.contains("appears exactly once"))
        assertTrue(prompt.contains("only punctuation is a group of its own"))
        assertTrue(prompt.contains("when in doubt, merge"))
        assertTrue(prompt.contains("ト デ ハ ヲ ニ ガ モ ノ ネ ヨ"))
        assertTrue(prompt.contains("STRICT JSON only"))
        assertTrue(
            prompt.contains(
                "マジカルトリックで | マ(ma) ジ(ji) カ(ka) ル(ru) ト(to) リッ(rik) ク(ku) で(de) => [[0,1,2,3],[4],[5,6],[7]]",
            ),
        )
        assertTrue(
            prompt.contains(
                "赤いトマトを | 赤(aka) い(i) ト(to) マ(ma) ト(to) を(o) => [[0,1,2,3,4],[5]]",
            ),
        )
        assertTrue(
            prompt.contains("キラキラ！ | キ(ki) ラ(ra) キ(ki) ラ(ra) ！(!) => [[0,1,2,3],[4]]"),
        )
        assertTrue(
            prompt.contains("ダメダメ！ | ダ(da) メ(me) ダ(da) メ(me) ！(!) => [[0,1,2,3],[4]]"),
        )
        assertTrue(prompt.contains("仕掛けてる | 仕(shika) 掛(kake) け(ke) て(te) る(ru) => [[0,1,2,3,4]]"))
        assertTrue(
            prompt.contains("何してるの？ | 何(nani) し(shi) て(te) る(ru) の？(no) => [[0,1,2,3],[4]]"),
        )
    }

    @Test
    fun `the user prompt carries the whole line its neighbours and the tokens`() {
        val request = request(
            line(0, "何してるの？", nanishiteruTokens(), previous = "前の行", next = "次の行"),
        )

        val prompt = PronunciationBoundary.buildUserPrompt(request)

        assertTrue(prompt.contains("\"line\":\"何してるの？\""))
        assertTrue(prompt.contains("\"prev\":\"前の行\""))
        assertTrue(prompt.contains("\"next\":\"次の行\""))
        assertTrue(prompt.contains("\"key\":\"何してるの？\""))
        assertTrue(prompt.contains("{\"i\":0,\"k\":\"何\",\"r\":\"nani\"}"))
        assertTrue(prompt.contains("{\"i\":4,\"k\":\"の？\",\"r\":\"no\"}"))
        assertTrue(prompt.contains("Return the same number of lines"))
        assertTrue(prompt.contains("song=\"メタモリボン\""))
    }

    @Test
    fun `a reading-less mark shows its own glyph as the reading`() {
        val prompt = PronunciationBoundary.buildUserPrompt(
            request(line(0, "キラキラ！", kirakiraTokens())),
        )

        assertTrue(prompt.contains("{\"i\":4,\"k\":\"！\",\"r\":\"！\"}"))
    }

    @Test
    fun `lines are sliced by the request cap`() {
        val lines = (0 until 5).map { index -> line(index, "キラキラ！", kirakiraTokens()) }
        val request = PronunciationBoundary.BoundaryRequest("song", lines)

        val slices = PronunciationBoundary.slices(request, maxLinesPerRequest = 2)

        assertEquals(listOf(2, 2, 1), slices.map { it.lines.size })
        assertEquals(listOf(0, 2, 4), slices.map { it.lines.first().index })
    }

    @Test
    fun `a single line is never dropped for its size`() {
        val request = request(line(0, "キラキラ！", kirakiraTokens()))

        val slices = PronunciationBoundary.slices(request, maxChars = 10)

        assertEquals(1, slices.size)
        assertEquals(1, slices.single().lines.size)
    }

    // ---------------------------------------------------------------------------------------
    // Fixtures — the real lines the measured request used
    // ---------------------------------------------------------------------------------------

    private fun majikaruTokens() = tokens(
        "マ" to "ma",
        "ジ" to "ji",
        "カ" to "ka",
        "ル" to "ru",
        "ト" to "to",
        "リッ" to "rik",
        "ク" to "ku",
        "で" to "de",
    )

    private fun tomatoTokens() = tokens(
        "赤" to "aka",
        "い" to "i",
        "ト" to "to",
        "マ" to "ma",
        "ト" to "to",
        "を" to "o",
    )

    private fun torikkuTokens() = tokens("ト" to "to", "リッ" to "rik", "ク" to "ku", "を" to "o")

    private fun kirakiraTokens() = tokens("キ" to "ki", "ラ" to "ra", "キ" to "ki", "ラ" to "ra", "！" to null)

    private fun nanishiteruTokens() = tokens("何" to "nani", "し" to "shi", "て" to "te", "る" to "ru", "の？" to "no")

    private fun tokens(vararg pairs: Pair<String, String?>): List<PronunciationBoundary.BoundaryToken> =
        pairs.mapIndexed { index, pair ->
            PronunciationBoundary.BoundaryToken(
                index = index,
                kana = pair.first,
                kanaCount = if (pair.second == null) 0 else pair.first.count { it !in "！？" },
                reading = pair.second,
            )
        }

    private fun token(
        index: Int,
        kana: String,
        kanaCount: Int = kana.length,
        reading: String? = null,
        trailingBlank: Boolean = false,
        lineTop: Int = 0,
        wholeWord: Boolean = false,
    ): PronunciationBoundary.BoundaryToken = PronunciationBoundary.BoundaryToken(
        index = index,
        kana = kana,
        kanaCount = kanaCount,
        reading = reading,
        trailingBlank = trailingBlank,
        lineTop = lineTop,
        wholeWordReading = wholeWord,
    )

    private fun line(
        index: Int,
        text: String,
        tokens: List<PronunciationBoundary.BoundaryToken>,
        previous: String? = null,
        next: String? = null,
    ): PronunciationBoundary.BoundaryRequestLine = PronunciationBoundary.BoundaryRequestLine(
        index = index,
        lineKey = tokens.joinToString("") { it.kana },
        line = text,
        tokens = tokens,
        previousLine = previous,
        nextLine = next,
    )

    private fun request(vararg lines: PronunciationBoundary.BoundaryRequestLine) =
        PronunciationBoundary.BoundaryRequest(
            songId = "1614348837",
            lines = lines.toList(),
            title = "メタモリボン",
            artist = "emon(Tes.)",
            language = "ja",
        )
}
