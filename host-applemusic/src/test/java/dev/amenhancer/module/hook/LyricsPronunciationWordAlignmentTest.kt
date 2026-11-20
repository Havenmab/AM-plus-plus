package dev.amenhancer.module.hook

import android.app.Activity
import android.view.Gravity
import android.view.View
import android.widget.LinearLayout
import android.widget.TextView
import dev.amenhancer.module.lyrics.online.NativePronunciationSpan
import dev.amenhancer.module.lyrics.online.PronunciationBoundary
import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * The rules and the geometry of the word-form splice, and the single write path it goes through.
 *
 * The fixtures are laid out with explicit widths so nothing here depends on a font's metrics: every
 * expected number is arithmetic on the fixture's own widths.
 *
 * The reading's own centring is not covered here any more — it is the host's pronunciation pair
 * alignment (see `LyricPronunciationPairAlignment`), not this module's — so a case that only asserted
 * the old per-container correction is gone. What remains is what this module still decides: which
 * containers form one word, where its fragments go, and which writer owns a group.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33], manifest = Config.NONE)
class LyricsPronunciationWordAlignmentTest {

    private val alignmentSource = projectFile(
        "host-applemusic/src/main/java/dev/amenhancer/module/hook/LyricsPronunciationWordAlignment.kt",
    )

    private val geometrySource = projectFile(
        "host-applemusic/src/main/java/dev/amenhancer/module/hook/LyricsPronunciationRowGeometry.kt",
    )

    // ---- 1. one word out of three readings --------------------------------------------------------

    @Test
    fun `three readings of one word splice flush and centre on the glyph run`() {
        val span = LyricsPronunciationWordAlignment.Span(0f, 300f)
        val fragments = listOf(0, 1, 2).map { index ->
            LyricsPronunciationWordAlignment.FragmentGeometry(
                index = index,
                leftInRow = 10f + index * 100f,
                width = 80f,
                visibleWidth = 80f,
            )
        }

        val splice = LyricsPronunciationWordAlignment.placement(span, fragments)!!

        assertEquals(240f, splice.blockWidth, 0.01f)
        val lefts = listOf(0, 1, 2).map { splice.targetLefts[it]!! }
        assertEquals(listOf(30f, 110f, 190f), lefts)
        assertTrue(lefts[1] > lefts[0])
        assertTrue(lefts[2] > lefts[1])
        assertEquals(span.centre, lefts.first() + splice.blockWidth / 2f, 0.01f)
    }


    // ---- 2. word boundaries inside a katakana line -------------------------------------------------




    // ---- 3/4. single-reading blocks are left to the host -----------------------------------------
    //
    // The case that used to live here ("a reading and its mark in one container keep the single
    // container correction") is gone on purpose: it asserted the per-container shift this module no
    // longer computes. Centring a lone reading under its kana is the host's pair alignment now, so
    // the only thing left to assert is that the splice keeps its hands off such a container.

    @Test
    fun `a whole word reading over silent containers is not spliced`() {
        val row = karaokeRow(
            word(listOf("ス"), "suappu", readingWidth = 300),
            word(listOf("アップ"), null),
            word(listOf("！"), null),
        )
        val containers = LyricsPronunciationRowGeometry.wordContainers(row)
        // Even a plan that asks for all three leaves this row alone: the whole-word reading is a hard
        // boundary of its own, so the plan is refused and each container stays a block.
        val plan = PronunciationBoundary.BoundaryLine("line", listOf(0..2))
        LyricsPronunciationWordAlignment.boundaryPlans = { plan }
        try {
            assertFalse(LyricsPronunciationWordAlignment.alignRow(row, containers[0]))
            assertEquals(0f, readingOf(row, 0).translationX, 0.001f)
        } finally {
            LyricsPronunciationWordAlignment.boundaryPlans = null
        }
    }

    // ---- 5. hiragana word with particles ----------------------------------------------------------



    // ---- 6. the gap rule, when timings are available ----------------------------------------------


    @Test
    fun `a long silence inside a word breaks it`() {
        val tokens = listOf(
            token(0, "全", "zen", begin = 44_400L, end = 45_000L),
            token(1, "身", "shin", begin = 46_500L, end = 46_900L),
        )

        assertEquals(2, LyricsPronunciationWordAlignment.group(tokens).size)
    }

    // ---- 7. Apple's trailing blanks are not part of the block -------------------------------------

    @Test
    fun `the block width is the visible advance and not the views widths`() {
        val row = karaokeRow(word(listOf("up!"), "appu ", readingWidth = 200))
        val reading = readingOf(row, 0)
        // a stub that reports the visible share of the view, so no real font is involved
        val measure: (TextView, Int, Int) -> Float = { view, start, end ->
            view.width.toFloat() * (end - start) / view.text.length
        }

        assertEquals(200f, reading.width.toFloat(), 0.01f)
        // "appu " is five characters, so the four visible ones are four fifths of the view
        assertEquals(160f, LyricsPronunciationWordAlignment.fragmentAdvance(reading, measure), 0.01f)

        val span = LyricsPronunciationWordAlignment.Span(0f, 400f)
        val fragments = listOf(
            LyricsPronunciationWordAlignment.FragmentGeometry(0, 0f, 200f, 160f),
            LyricsPronunciationWordAlignment.FragmentGeometry(1, 220f, 120f, 120f),
        )
        assertEquals(280f, LyricsPronunciationWordAlignment.placement(span, fragments)!!.blockWidth, 0.01f)
    }

    // ---- 8. every guard falls back instead of guessing --------------------------------------------



    @Test
    fun `an unlaid reading is not spliced`() {
        val row = karaokeRow(
            word(listOf("ス"), "su", readingWidth = 0),
            word(listOf("ア"), "a", readingWidth = 0),
        )
        val containers = LyricsPronunciationRowGeometry.wordContainers(row)
        val plan = PronunciationBoundary.BoundaryLine("line", listOf(0..1))
        LyricsPronunciationWordAlignment.boundaryPlans = { plan }
        try {
            assertFalse(LyricsPronunciationWordAlignment.alignRow(row, containers[0]))
            assertEquals(0f, readingOf(row, 0).translationX, 0.001f)
        } finally {
            LyricsPronunciationWordAlignment.boundaryPlans = null
        }
    }

    // ---- 9. one write site for each shift mode ----------------------------------------------------

    @Test
    fun `the reading position has one write site and the mode decides it`() {
        // Every write lives in the row geometry's single helper; the splice itself writes nothing.
        assertEquals(1, Regex("""\.translationX\s*=(?!=)""").findAll(geometrySource).count())
        assertEquals(1, Regex("""\.offsetLeftAndRight\(""").findAll(geometrySource).count())
        assertEquals(0, Regex("""\.translationX\s*=(?!=)""").findAll(alignmentSource).count())
        assertEquals(0, Regex("""\.offsetLeftAndRight\(""").findAll(alignmentSource).count())
        // The helper switches on the mode, and both arms sit inside it.
        val helper = geometrySource.substringAfter("fun applyReadingOffset(").substringBefore("fun leftIn(")
        assertTrue(helper.contains("ShiftMode.TRANSLATION"))
        assertTrue(helper.contains("ShiftMode.LAYOUT"))
        assertTrue(helper.indexOf("ShiftMode.TRANSLATION") < helper.indexOf(".translationX"))
        assertTrue(helper.indexOf("ShiftMode.LAYOUT") < helper.indexOf(".offsetLeftAndRight("))
        // The default mode is the draw-time offset, and no setting exposes either value.
        assertTrue(alignmentSource.contains("var shiftMode: ShiftMode = ShiftMode.TRANSLATION"))
        assertFalse(alignmentSource.contains("ModuleSettings"))
    }

    @Test
    fun `the hook never writes a view tag`() {
        // DataBinding owns the container's tag and rejects a foreign one, which crashed the lyrics
        // screen once already; the bookkeeping is keyed off the views themselves instead.
        listOf(alignmentSource, geometrySource).forEach { source ->
            assertFalse(source.contains(".tag ="))
            assertFalse(source.contains("setTag("))
        }
        assertTrue(alignmentSource.contains("WeakHashMap<ViewGroup, Unit>"))
    }

    // ---- 10. idempotency --------------------------------------------------------------------------


    // ---- the model's plan -------------------------------------------------------------------------





    @Test
    fun `the row asks the plan provider for the line key`() {
        val row = karaokeRow(word(listOf("ア"), "a"), word(listOf("イ"), "i"))
        val containers = LyricsPronunciationRowGeometry.wordContainers(row)
        val asked = ArrayList<String>()
        LyricsPronunciationWordAlignment.boundaryPlans = { key ->
            asked += key
            null
        }
        try {
            LyricsPronunciationWordAlignment.alignRow(row, containers[0])
        } finally {
            LyricsPronunciationWordAlignment.boundaryPlans = null
        }

        println("[plan-test] lineKey asked=" + asked)
        assertEquals(listOf("アイ"), asked)
    }

    @Test
    fun aBlockKeepsASilentContainerOutOfItsFragments() {
        // マ ジ ー ル where the ー carries no reading: the plan may still join all four, the splice
        // places three fragments, and the block is not refused for the silent one.
        val tokens = listOf(
            token(0, "マ", "ma"),
            token(1, "ジ", "ji"),
            token(2, "ー", null),
            token(3, "ル", "ru"),
        )
        val plan = PronunciationBoundary.BoundaryLine("line", listOf(0..3))

        val blocks = LyricsPronunciationWordAlignment.group(tokens, plan = plan)
        println("[plan-test] silent blocks=" + ranges(blocks) + " fragments=" + blocks.first().fragments.size)

        assertEquals(listOf("0..3"), ranges(blocks))
        assertEquals(3, blocks.first().fragments.size)
        assertEquals(listOf(0, 1, 3), blocks.first().fragments.map { it.index })
    }


    @Test
    fun aBlockTwiceAsWideAsItsRunIsStillPlaced() {
        // A romanization is routinely wider than the kana under it; the placement is centred, so the
        // overflow is harmless and must not be refused (it used to be, silently).
        val span = LyricsPronunciationWordAlignment.Span(0f, 300f)
        val fragments = listOf(
            LyricsPronunciationWordAlignment.FragmentGeometry(0, 0f, 300f, 300f),
            LyricsPronunciationWordAlignment.FragmentGeometry(1, 300f, 300f, 300f),
        )

        val splice = LyricsPronunciationWordAlignment.placement(span, fragments)
        println("[plan-test] wide splice=" + splice)

        assertTrue(splice != null)
        assertEquals(600f, splice!!.blockWidth, 0.01f)
        assertEquals(2, splice.targetLefts.size)
        assertEquals(-150f, splice.targetLefts.getValue(0), 0.01f)
        assertEquals(150f, splice.targetLefts.getValue(1), 0.01f)
    }

    @Test
    fun noPlanMeansNoSplice() {
        val tokens = tomatoContainers()
        val blocks = LyricsPronunciationWordAlignment.group(tokens)
        println("[plan-test] noPlan blocks=" + ranges(blocks))
        assertEquals(tokens.size, blocks.size)
        assertTrue(blocks.all { it.firstIndex == it.lastIndex })
    }

    @Test
    fun aRejectedPlanLeavesEveryContainerOnItsOwn() {
        val tokens = listOf(token(0, "マ", "ma"), token(1, "！", null), token(2, "ジ", "ji"), token(3, "ル", "ru"))
        val crossing = PronunciationBoundary.BoundaryLine("line", listOf(0..2, 3..3))
        val blocks = LyricsPronunciationWordAlignment.group(tokens, plan = crossing)
        println("[plan-test] rejected blocks=" + ranges(blocks))
        assertEquals(tokens.size, blocks.size)
        assertTrue(blocks.all { it.firstIndex == it.lastIndex })
    }

    @Test
    fun aPlanDrivesTheSpliceAcrossThreeReadings() {
        val row = karaokeRow(word(listOf("ス"), "su"), word(listOf("ア"), "a"), word(listOf("ッ"), "ppu"))
        val containers = LyricsPronunciationRowGeometry.wordContainers(row)
        val key = containers.joinToString("") { "" }
        val plan = PronunciationBoundary.BoundaryLine(key, listOf(0..2))
        LyricsPronunciationWordAlignment.boundaryPlans = { plan }
        try {
            assertTrue(LyricsPronunciationWordAlignment.alignRow(row, containers[0]))
        } finally {
            LyricsPronunciationWordAlignment.boundaryPlans = null
        }
        println("[plan-test] planned row shifts=" + (0..2).joinToString(",") { "" + readingOf(row, it).translationX })
        assertEquals(20f, readingOf(row, 0).translationX, 0.5f)
        assertEquals(0f, readingOf(row, 1).translationX, 0.5f)
        assertEquals(-20f, readingOf(row, 2).translationX, 0.5f)
    }

    @Test
    fun aSecondPassWritesTheSameOffsets() {
        val row = karaokeRow(word(listOf("ス"), "su"), word(listOf("ア"), "a"), word(listOf("ッ"), "ppu"))
        val containers = LyricsPronunciationRowGeometry.wordContainers(row)
        val plan = PronunciationBoundary.BoundaryLine("line", listOf(0..2))
        LyricsPronunciationWordAlignment.boundaryPlans = { plan }
        try {
            assertTrue(LyricsPronunciationWordAlignment.alignRow(row, containers[0]))
            val first = (0..2).map { readingOf(row, it).translationX }
            assertTrue(LyricsPronunciationWordAlignment.alignRow(row, containers[2]))
            val second = (0..2).map { readingOf(row, it).translationX }
            println("[plan-test] idempotent first=" + first + " second=" + second)
            assertEquals(first, second)
        } finally {
            LyricsPronunciationWordAlignment.boundaryPlans = null
        }
    }

    @Test
    fun aSplicedWordKeepsItsFragmentsFlush() {
        val row = karaokeRow(word(listOf("お"), "o"), word(listOf("は"), "ha"), word(listOf("よ"), "yo"), word(listOf("う"), "u"))
        val containers = LyricsPronunciationRowGeometry.wordContainers(row)
        val plan = PronunciationBoundary.BoundaryLine("line", listOf(0..3))
        LyricsPronunciationWordAlignment.boundaryPlans = { plan }
        try {
            assertTrue(LyricsPronunciationWordAlignment.alignRow(row, containers[0]))
            val lefts = (0..3).map { index ->
                LyricsPronunciationRowGeometry.leftIn(row, readingOf(row, index)) + readingOf(row, index).translationX
            }
            println("[plan-test] flush lefts=" + lefts)
            (1 until lefts.size).forEach { index ->
                assertEquals(80f, lefts[index] - lefts[index - 1], 0.5f)
            }
        } finally {
            LyricsPronunciationWordAlignment.boundaryPlans = null
        }
    }

    // ---- who owns a group -------------------------------------------------------------------------

    @Test
    fun `a plan owns every group it splices and the host keeps the rest`() {
        val row = karaokeRow(word(listOf("ス"), "su"), word(listOf("ア"), "a"), word(listOf("ッ"), "ppu"))
        val containers = LyricsPronunciationRowGeometry.wordContainers(row)

        // No provider: nothing here writes and nothing here is ours, so the host centres all three.
        assertFalse(LyricsPronunciationWordAlignment.alignRow(row, containers[0]))
        (0..2).forEach { index -> assertEquals(0f, readingOf(row, index).translationX, 0.001f) }
        assertFalse(LyricsPronunciationWordAlignment.ownsGroup(containers[0]))
        assertFalse(LyricsPronunciationWordAlignment.ownsGroup(containers[1]))
        assertFalse(LyricsPronunciationWordAlignment.ownsGroup(containers[2]))

        // A plan that keeps ス on its own: one reading cannot be spliced, so that group stays the
        // host's and only the other two become ours.
        val split = PronunciationBoundary.BoundaryLine("line", listOf(0..0, 1..2))
        LyricsPronunciationWordAlignment.boundaryPlans = { split }
        try {
            assertFalse(LyricsPronunciationWordAlignment.ownsGroup(containers[0]))
            assertTrue(LyricsPronunciationWordAlignment.ownsGroup(containers[1]))
            assertTrue(LyricsPronunciationWordAlignment.ownsGroup(containers[2]))
        } finally {
            LyricsPronunciationWordAlignment.boundaryPlans = null
        }

        // A plan that joins all three hands the whole run to the splice.
        val whole = PronunciationBoundary.BoundaryLine("line", listOf(0..2))
        LyricsPronunciationWordAlignment.boundaryPlans = { whole }
        try {
            assertTrue(LyricsPronunciationWordAlignment.ownsGroup(containers[0]))
        } finally {
            LyricsPronunciationWordAlignment.boundaryPlans = null
        }
    }

    @Test
    fun `the host compensation leaves a group the splice owns untouched`() {
        // One reading, one writer: the host's edge compensation asks ownsGroup before it writes and
        // returns for a spliced group, so that group's children keep the frames the splice placed.
        val row = karaokeRow(word(listOf("ス"), "su"), word(listOf("ア"), "a"), word(listOf("ッ"), "ppu"))
        val containers = LyricsPronunciationRowGeometry.wordContainers(row)
        val plan = PronunciationBoundary.BoundaryLine("line", listOf(0..2))
        LyricsPronunciationWordAlignment.boundaryPlans = { plan }
        try {
            assertTrue(LyricsPronunciationWordAlignment.alignRow(row, containers[0]))
            val group = containers[0] as LinearLayout
            val before = (0 until group.childCount).map { group.getChildAt(it).left }
            compensateLyricPronunciationEdges(group)
            val after = (0 until group.childCount).map { group.getChildAt(it).left }
            assertEquals(before, after)
        } finally {
            LyricsPronunciationWordAlignment.boundaryPlans = null
        }
    }

    // ---- fixtures ---------------------------------------------------------------------------------

    /** What the grouping saw and produced, so a failed assertion explains itself in the CI log. */
    private fun describe(
        blocks: List<LyricsPronunciationWordAlignment.Block>,
        tokens: List<LyricsPronunciationWordAlignment.ContainerToken>,
    ): String = "blocks=" + blocks.joinToString(prefix = "[", postfix = "]") { block ->
        block.firstIndex.toString() + ".." + block.lastIndex + "=" +
            block.tokens.fold("") { text, token -> text + token.core }
    } + " cores=" + tokens.map { it.core } +
        " scripts=" + tokens.map { it.script } +
        " katakanaParticle=" + tokens.map { it.isKatakanaParticle } +
        " kana=" + tokens.map { it.kanaCount }

    /** A grouping as `first..last` labels. */
    private fun ranges(blocks: List<LyricsPronunciationWordAlignment.Block>): List<String> =
        blocks.map { block -> block.firstIndex.toString() + ".." + block.lastIndex }

    /** The rules' grouping of [tokens], optionally through a plan. */
    private fun blockRanges(
        tokens: List<LyricsPronunciationWordAlignment.ContainerToken>,
        plan: PronunciationBoundary.BoundaryLine? = null,
    ): List<String> = ranges(LyricsPronunciationWordAlignment.group(tokens, plan = plan))

    /** 赤いトマトを, one container per mora, the way the AMLL source splits it. */
    private fun tomatoContainers(): List<LyricsPronunciationWordAlignment.ContainerToken> = listOf(
        token(0, "赤", "aka"),
        token(1, "い", "i"),
        token(2, "ト", "to"),
        token(3, "マ", "ma"),
        token(4, "ト", "to"),
        token(5, "を", "o"),
    )

    private fun token(
        index: Int,
        kana: String,
        reading: String?,
        top: Int = 0,
        begin: Long? = null,
        end: Long? = null,
        wholeWord: Boolean = false,
    ): LyricsPronunciationWordAlignment.ContainerToken =
        LyricsPronunciationWordAlignment.ContainerToken(
            index = index,
            kana = kana,
            reading = reading,
            lineTop = top,
            begin = begin,
            end = end,
            readingIsWholeWord = wholeWord,
        )

    private class Word(
        val kana: List<String>,
        val reading: String?,
        val readingWidth: Int,
        val widths: List<Int>,
    )

    private fun word(
        kana: List<String>,
        reading: String?,
        glyphWidth: Int = 100,
        readingWidth: Int = 80,
    ): Word = Word(kana, reading, readingWidth, kana.map { glyphWidth })

    private fun karaokeRow(vararg words: Word): LinearLayout {
        val activity = Robolectric.buildActivity(Activity::class.java).setup().get()
        val row = LinearLayout(activity).apply { orientation = LinearLayout.HORIZONTAL }
        words.forEach { row.addView(wordCell(activity, it)) }
        row.measure(
            View.MeasureSpec.makeMeasureSpec(1600, View.MeasureSpec.EXACTLY),
            View.MeasureSpec.makeMeasureSpec(200, View.MeasureSpec.EXACTLY),
        )
        row.layout(0, 0, 1600, 200)
        return row
    }

    private fun wordCell(activity: Activity, word: Word): LinearLayout {
        val group = LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
            isBaselineAligned = false
            gravity = Gravity.CENTER_HORIZONTAL
            clipChildren = false
            clipToPadding = false
        }
        val glyphs = LinearLayout(activity).apply { orientation = LinearLayout.HORIZONTAL }
        word.kana.forEachIndexed { index, text ->
            glyphs.addView(
                textView(activity, text),
                LinearLayout.LayoutParams(word.widths[index], 40),
            )
        }
        group.addView(
            glyphs,
            LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT, 40),
        )
        if (word.reading != null) {
            group.addView(
                textView(activity, word.reading),
                LinearLayout.LayoutParams(word.readingWidth, 40),
            )
        }
        return group
    }

    private fun readingOf(row: LinearLayout, index: Int): TextView =
        LyricsPronunciationRowGeometry.readingChild(row.getChildAt(index) as LinearLayout)!!

    private fun textView(activity: Activity, text: String): TextView = TextView(activity).apply {
        this.text = text
        setTextSize(android.util.TypedValue.COMPLEX_UNIT_PX, 40f)
    }

    private fun projectFile(relativePath: String): String = sequenceOf(
        File(relativePath),
        File("../$relativePath"),
    ).firstOrNull(File::isFile)?.readText()
        ?: error(relativePath + " was not found from the unit-test working directory")
}
