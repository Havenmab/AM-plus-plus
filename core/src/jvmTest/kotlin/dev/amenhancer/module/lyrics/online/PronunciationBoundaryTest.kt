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
    fun `a katakana particle is left to the model because the data cannot read it`() {
        // マ ジ カ ル ト リッ ク で: the ト is a particle, but nothing in the containers says so — it
        // sits after a katakana exactly like the trailing ト of トマト.
        val midWordTokens = majikaruTokens()
        val midWord = PronunciationBoundary.hardBoundaries(midWordTokens)
        println("[bndry-test] midWord " + describe(midWordTokens, midWord))
        assertEquals(describe(midWordTokens, midWord), List(8) { false }, midWord.alone)
        assertEquals(describe(midWordTokens, midWord), listOf(0..7), midWord.segments())

        // 赤いトマトを: locking the trailing ト here would refuse the grouping the model got right.
        val tomato = tomatoTokens()
        val wordHead = PronunciationBoundary.hardBoundaries(tomato)
        println("[bndry-test] tomato " + describe(tomato, wordHead))
        assertEquals(describe(tomato, wordHead), List(6) { false }, wordHead.alone)
        assertEquals(describe(tomato, wordHead), listOf(0..5), wordHead.segments())

        val loanwordTokens = torikkuTokens()
        val loanword = PronunciationBoundary.hardBoundaries(loanwordTokens)
        assertEquals(describe(loanwordTokens, loanword), List(4) { false }, loanword.alone)

        // トマト ト チーズ: even "katakana on both sides" would mislock the trailing ト, which is
        // why the boundary is not computed from the neighbours at all.
        val twoWords = tomatoToCheeseTokens()
        val hard = PronunciationBoundary.hardBoundaries(twoWords)
        println("[bndry-test] twoWords " + describe(twoWords, hard))
        // A reading-less ー is still one kana; only a mark covers zero.
        assertEquals(describe(twoWords, hard), listOf(1, 1, 1, 1, 1, 1, 1), twoWords.map { it.kanaCount })
        assertEquals(describe(twoWords, hard), List(7) { false }, hard.alone)
    }

    // ---------------------------------------------------------------------------------------
    // Uncertainty
    // ---------------------------------------------------------------------------------------

    @Test
    fun `every token of a free line is up to the model`() {
        val tokens = majikaruTokens()
        val hard = PronunciationBoundary.hardBoundaries(tokens)

        tokens.indices.forEach { index ->
            assertTrue(
                describe(tokens, hard) + " token=" + index,
                PronunciationBoundary.uncertain(tokens, hard, index),
            )
        }
    }

    @Test
    fun `a locked line is up to the model nowhere`() {
        // A mark of its own splits the line into three one-token stretches, so no position is open.
        val tokens = listOf(
            token(0, "ス", reading = "su"),
            token(1, "！", kanaCount = 0),
            token(2, "ア", reading = "a"),
        )
        val hard = PronunciationBoundary.hardBoundaries(tokens)

        tokens.indices.forEach { index ->
            assertFalse(
                describe(tokens, hard) + " token=" + index,
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

        tokens.indices.forEach { index ->
            assertTrue(
                describe(tokens, hard) + " token=" + index,
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
        // The lock has to come from one of the two facts the data proves — a mark of its own here.
        // A katakana particle is no longer locked, so a group may span a ト freely.
        val tokens = listOf(
            token(0, "マ", reading = "ma"),
            token(1, "！", kanaCount = 0),
            token(2, "ジ", reading = "ji"),
            token(3, "ル", reading = "ru"),
        )
        val hard = PronunciationBoundary.hardBoundaries(tokens)
        println("[bndry-test] V7 " + describe(tokens, hard))

        assertEquals(
            PronunciationBoundary.BoundaryError.CrossesHardBoundary(0, 1),
            PronunciationBoundary.validateLine(tokens, listOf(0..3), hard),
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
            describe(tokens, PronunciationBoundary.hardBoundaries(tokens)) + " merged=" + merged,
            PronunciationBoundary.MergeOutcome.Groups(listOf(0..4, 5..5), emptyList()),
            merged,
        )
    }

    @Test
    fun `the model may also split a word the rules kept`() {
        // The other direction on the same line shape: inside a free stretch the answer decides, so a
        // finer grouping than the rules' own is accepted too (it is what a dictionary-free rule
        // cannot do and the model can).
        val tokens = majikaruTokens()
        val baseline = listOf(0..3, 4..4, 5..6, 7..7)
        val plan = PronunciationBoundary.BoundaryLine("line", listOf(0..1, 2..3, 4..4, 5..6, 7..7))

        val merged = PronunciationBoundary.mergeLine(tokens, baseline, plan)

        assertEquals(
            describe(tokens, PronunciationBoundary.hardBoundaries(tokens)) + " merged=" + merged,
            PronunciationBoundary.MergeOutcome.Groups(
                listOf(0..1, 2..3, 4..4, 5..6, 7..7),
                emptyList(),
            ),
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
    fun `a whole word reading container cannot be joined by the model`() {
        // The lock that is left: ス carries the reading of the whole word, so the model may not
        // pull it into a neighbour — but it may still regroup everything after it.
        val tokens = listOf(
            token(0, "ス", reading = "suappu", wholeWord = true),
            token(1, "ア", reading = "a"),
            token(2, "ッ", reading = "ppu"),
        )
        val hard = PronunciationBoundary.hardBoundaries(tokens)
        assertEquals(describe(tokens, hard), listOf(true, false, false), hard.alone)
        assertEquals(describe(tokens, hard), listOf(0..0, 1..2), hard.segments())

        val joined = PronunciationBoundary.mergeLine(
            tokens,
            listOf(0..0, 1..2),
            PronunciationBoundary.BoundaryLine("line", listOf(0..2)),
        )
        val rejected = joined as PronunciationBoundary.MergeOutcome.Rejected
        assertEquals("crosses-boundary", rejected.error.code)

        val kept = PronunciationBoundary.mergeLine(
            tokens,
            listOf(0..0, 1..1, 2..2),
            PronunciationBoundary.BoundaryLine("line", listOf(0..0, 1..2)),
        )
        assertEquals(
            describe(tokens, hard) + " merged=" + kept,
            PronunciationBoundary.MergeOutcome.Groups(listOf(0..0, 1..2), emptyList()),
            kept,
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

    /** トマト ト チーズ: a trailing ト and a particle ト, both surrounded by katakana. */
    private fun tomatoToCheeseTokens() = tokens(
        "ト" to "to",
        "マ" to "ma",
        "ト" to "to",
        "ト" to "to",
        "チ" to "chi",
        "ー" to null,
        "ズ" to "zu",
    )

    private fun kirakiraTokens() = tokens("キ" to "ki", "ラ" to "ra", "キ" to "ki", "ラ" to "ra", "！" to null)

    private fun nanishiteruTokens() = tokens("何" to "nani", "し" to "shi", "て" to "te", "る" to "ru", "の？" to "no")

    /**
     * A line's tokens from glyph/reading pairs.
     *
     * The kana count comes from the glyphs alone: a mark counts zero, and a token that merely has
     * no reading of its own (ー) is still one kana. Deriving it from the reading would turn ー into a
     * mark-only container and lock it.
     */
    private fun tokens(vararg pairs: Pair<String, String?>): List<PronunciationBoundary.BoundaryToken> =
        pairs.mapIndexed { index, pair ->
            PronunciationBoundary.BoundaryToken(
                index = index,
                kana = pair.first,
                kanaCount = pair.first.count { it !in "！？" },
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

    /** What the rules actually decided, so a failing assertion explains itself in the log. */
    private fun describe(
        tokens: List<PronunciationBoundary.BoundaryToken>,
        hard: PronunciationBoundary.HardBoundaries,
    ): String = "glyphs=" + tokens.map { it.kana } +
        " kana=" + tokens.map { it.kanaCount } +
        " alone=" + hard.alone +
        " start=" + hard.start +
        " end=" + hard.end +
        " segments=" + hard.segments()

    private fun request(vararg lines: PronunciationBoundary.BoundaryRequestLine) =
        PronunciationBoundary.BoundaryRequest(
            songId = "1614348837",
            lines = lines.toList(),
            title = "メタモリボン",
            artist = "emon(Tes.)",
            language = "ja",
        )
}
