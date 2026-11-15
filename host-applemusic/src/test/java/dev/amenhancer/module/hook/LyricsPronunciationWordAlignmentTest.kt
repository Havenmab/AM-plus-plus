package dev.amenhancer.module.hook

import android.app.Activity
import android.view.Gravity
import android.view.View
import android.widget.LinearLayout
import android.widget.TextView
import dev.amenhancer.module.lyrics.online.NativePronunciationSpan
import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * The rules and the geometry of the word-form splice, plus the single write path it shares with
 * [LyricsPronunciationRowCentring].
 *
 * The fixtures are laid out with explicit widths, like the centring tests, so nothing here depends on
 * a font's metrics: every expected number is arithmetic on the fixture's own widths.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33], manifest = Config.NONE)
class LyricsPronunciationWordAlignmentTest {

    private val alignmentSource = projectFile(
        "host-applemusic/src/main/java/dev/amenhancer/module/hook/LyricsPronunciationWordAlignment.kt",
    )

    private val centringSource = projectFile(
        "host-applemusic/src/main/java/dev/amenhancer/module/hook/LyricsPronunciationRowCentring.kt",
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

    @Test
    fun `a laid out row of three per mora readings moves each view by its own offset`() {
        val row = karaokeRow(
            word(listOf("ス"), "su"),
            word(listOf("ア"), "a"),
            word(listOf("ッ"), "ppu"),
        )
        val containers = LyricsPronunciationRowCentring.wordContainers(row)
        assertEquals(3, containers.size)

        assertTrue(LyricsPronunciationWordAlignment.alignRow(row, containers[0]))

        // 100-wide containers with 80-wide readings centred in them: lefts 10/110/210, block 30..270
        assertEquals(20f, readingOf(row, 0).translationX, 0.5f)
        assertEquals(0f, readingOf(row, 1).translationX, 0.5f)
        assertEquals(-20f, readingOf(row, 2).translationX, 0.5f)
        assertEquals(30f, LyricsPronunciationRowCentring.leftIn(row, readingOf(row, 0)) + 20f, 0.5f)
    }

    // ---- 2. word boundaries inside a katakana line -------------------------------------------------

    @Test
    fun `a katakana particle ends the word before it`() {
        val tokens = listOf(
            token(0, "マ", "ma"),
            token(1, "ジ", "ji"),
            token(2, "カ", "ka"),
            token(3, "ル", "ru"),
            token(4, "ト", "to"),
            token(5, "リッ", "rik"),
            token(6, "ク", "ku"),
            token(7, "で", "de"),
        )

        val blocks = LyricsPronunciationWordAlignment.group(tokens)

        assertEquals(listOf("0..3", "4", "5..6", "7"), blocks.map { block -> block.firstIndex.toString() + ".." + block.lastIndex })
        assertEquals(4, blocks[0].kanaCount)
    }

    @Test
    fun `a word level line is never spliced into longer words`() {
        // The AMLL line 12 shape: every container already holds a whole word, so joining them would
        // invent words the source kept apart.
        val tokens = listOf(
            token(0, "メイ", "mei"),
            token(1, "キャップ", "kyappu"),
            token(2, "ドレ", "dore"),
            token(3, "スアップ", "suappu"),
        )
        assertTrue(LyricsPronunciationWordAlignment.isWordLevelLine(tokens))

        val row = karaokeRow(word(listOf("メイ"), "mei"), word(listOf("キャップ"), "kyappu"))
        val containers = LyricsPronunciationRowCentring.wordContainers(row)
        assertFalse(LyricsPronunciationWordAlignment.alignRow(row, containers[0]))
    }

    // ---- 3/4. single-reading blocks fall back to the old correction --------------------------------

    @Test
    fun `a reading and its mark in one container keep the single container correction`() {
        val row = karaokeRow(word(listOf("の", "?"), "no"))
        val group = row.getChildAt(0) as LinearLayout
        val reading = LyricsPronunciationRowCentring.readingChild(group)!!

        assertFalse(LyricsPronunciationWordAlignment.alignRow(row, group))
        assertEquals(0f, reading.translationX, 0.001f)
        // the fallback still pulls the reading off the mark, exactly as before the splice existed:
        // half of the mark the container carries, which is 50px wide here
        assertEquals(-50f, LyricsPronunciationRowCentring.readingShift(row, group, reading), 0.5f)
    }

    @Test
    fun `a whole word reading over silent containers is not spliced`() {
        val row = karaokeRow(
            word(listOf("ス"), "suappu", readingWidth = 300),
            word(listOf("アップ"), null),
            word(listOf("！"), null),
        )
        val containers = LyricsPronunciationRowCentring.wordContainers(row)

        assertFalse(LyricsPronunciationWordAlignment.alignRow(row, containers[0]))
        assertEquals(0f, readingOf(row, 0).translationX, 0.001f)
    }

    // ---- 5. hiragana word with particles ----------------------------------------------------------

    @Test
    fun `ohayou in five containers becomes one block of four fragments`() {
        val tokens = listOf(
            token(0, "「", null),
            token(1, "お", "o"),
            token(2, "は", "ha"),
            token(3, "よ", "yo"),
            token(4, "う", "u"),
        )

        val blocks = LyricsPronunciationWordAlignment.group(tokens)

        assertEquals(1, blocks.size)
        assertEquals("1..4", blocks[0].firstIndex.toString() + ".." + blocks[0].lastIndex)
        assertEquals(4, blocks[0].fragments.size)
    }

    @Test
    fun `a spliced hiragana word keeps the fragments flush with each other`() {
        val row = karaokeRow(
            word(listOf("「"), null),
            word(listOf("お"), "o"),
            word(listOf("は"), "ha"),
            word(listOf("よ"), "yo"),
            word(listOf("う"), "u"),
        )
        val containers = LyricsPronunciationRowCentring.wordContainers(row)

        assertTrue(LyricsPronunciationWordAlignment.alignRow(row, containers[1]))

        val lefts = (1..4).map { index ->
            val reading = readingOf(row, index)
            LyricsPronunciationRowCentring.leftIn(row, reading) + reading.translationX
        }
        (1 until lefts.size).forEach { index ->
            assertEquals(80f, lefts[index] - lefts[index - 1], 0.5f)
        }
    }

    // ---- 6. the gap rule, when timings are available ----------------------------------------------

    @Test
    fun `a short pause inside a word does not break it`() {
        val tokens = LyricsPronunciationWordAlignment.attachTimings(
            listOf(token(0, "全", "zen"), token(1, "身", "shin")),
            listOf(
                NativePronunciationSpan(44_400L, 45_875L, "zen"),
                NativePronunciationSpan(46_166L, 46_636L, "shin"),
            ),
        )

        val blocks = LyricsPronunciationWordAlignment.group(tokens)

        assertEquals(1, blocks.size)
        assertEquals(2, blocks[0].fragments.size)
    }

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
    fun `a block wider than the run it sits under is not spliced`() {
        val span = LyricsPronunciationWordAlignment.Span(0f, 300f)
        val fragments = listOf(
            LyricsPronunciationWordAlignment.FragmentGeometry(0, 0f, 300f, 300f),
            LyricsPronunciationWordAlignment.FragmentGeometry(1, 300f, 300f, 300f),
        )

        assertNull(LyricsPronunciationWordAlignment.placement(span, fragments))
    }

    @Test
    fun `a silent container inside the block stops the splice`() {
        val row = karaokeRow(
            word(listOf("ス"), "su"),
            word(listOf("ア"), null),
            word(listOf("ッ"), "ppu"),
        )
        val containers = LyricsPronunciationRowCentring.wordContainers(row)

        // ス and ッ carry readings, ア does not: splicing them would slide ッ one kana to the left.
        assertFalse(LyricsPronunciationWordAlignment.alignRow(row, containers[0]))
        assertEquals(0f, readingOf(row, 0).translationX, 0.001f)
    }

    @Test
    fun `an unlaid reading is not spliced`() {
        val row = karaokeRow(
            word(listOf("ス"), "su", readingWidth = 0),
            word(listOf("ア"), "a", readingWidth = 0),
        )
        val containers = LyricsPronunciationRowCentring.wordContainers(row)

        assertFalse(LyricsPronunciationWordAlignment.alignRow(row, containers[0]))
    }

    // ---- 9. one write site for each shift mode ----------------------------------------------------

    @Test
    fun `the reading position has one write site and the mode decides it`() {
        // Every write lives in the centring hook's single helper; the alignment object writes nothing.
        assertEquals(1, Regex("""\.translationX\s*=(?!=)""").findAll(centringSource).count())
        assertEquals(1, Regex("""\.offsetLeftAndRight\(""").findAll(centringSource).count())
        assertEquals(0, Regex("""\.translationX\s*=(?!=)""").findAll(alignmentSource).count())
        assertEquals(0, Regex("""\.offsetLeftAndRight\(""").findAll(alignmentSource).count())
        // The helper switches on the mode, and both arms sit inside it.
        val helper = centringSource.substringAfter("fun applyReadingOffset(").substringBefore("fun advanceFromStart(")
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
        listOf(alignmentSource, centringSource).forEach { source ->
            assertFalse(source.contains(".tag ="))
            assertFalse(source.contains("setTag("))
        }
        assertTrue(alignmentSource.contains("WeakHashMap<ViewGroup, Unit>"))
    }

    // ---- 10. idempotency --------------------------------------------------------------------------

    @Test
    fun `a second pass writes the same offsets and never accumulates`() {
        val row = karaokeRow(
            word(listOf("ス"), "su"),
            word(listOf("ア"), "a"),
            word(listOf("ッ"), "ppu"),
        )
        val containers = LyricsPronunciationRowCentring.wordContainers(row)

        assertTrue(LyricsPronunciationWordAlignment.alignRow(row, containers[0]))
        val first = (0..2).map { index -> readingOf(row, index).translationX }

        assertTrue(LyricsPronunciationWordAlignment.alignRow(row, containers[2]))
        val second = (0..2).map { index -> readingOf(row, index).translationX }

        assertEquals(first, second)
    }

    // ---- fixtures ---------------------------------------------------------------------------------

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
        LyricsPronunciationRowCentring.readingChild(row.getChildAt(index) as LinearLayout)!!

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
