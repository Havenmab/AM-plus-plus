package dev.amenhancer.module.hook

import android.app.Activity
import android.view.Gravity
import android.view.View
import android.widget.LinearLayout
import android.widget.TextView
import dev.amenhancer.module.model.ModuleSettings
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
 * Cover for the per-word romanization centring hook.
 *
 * Apple lays one container per word out as "glyph row above, romanization below", centres the two
 * rows in the container, and moves the container itself along the karaoke row. Every number below
 * is therefore pinned on a real laid-out row (fixed widths, so nothing depends on how the test
 * runtime measures text) rather than on arithmetic this project could restate:
 *
 *  - a mark that shares the word's container pushes the reading by half the mark, behind the word
 *    as well as in front of it, and marks on both sides cancel out;
 *  - a mark is regularly followed by the blank Apple appends to the word, and the correction still
 *    lands on the kana: `up! `+`up` and `る？ `+`ru`;
 *  - a whole-word reading that sits in a one-kana container is centred on the word's kana run, and
 *    a mark container after the word changes nothing;
 *  - a per-kana reading never leaves its own container, which is what keeps `の?` and `ミラー`
 *    already-correct on the device;
 *  - the container's horizontal gravity, and the reading's translation, are the only writes.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33], manifest = Config.NONE)
class LyricsPronunciationRowCentringStructuralTest {

    private val source = projectFile(
        "host-applemusic/src/main/java/dev/amenhancer/module/hook/LyricsPronunciationRowCentring.kt",
    )

    private val factory = projectFile(
        "host-applemusic/src/main/java/dev/amenhancer/module/hook/AppleMusicHostFactory.kt",
    )

    // ---- registration, gating and the write set ---------------------------------------------------

    @Test
    fun `registers the verified per-word container on the auxiliary lyric resource path`() {
        assertEquals("lyrics_pronunciation_vertical_group", LyricsPronunciationRowCentring.LAYOUT_NAME)
        assertTrue(source.contains("LayoutInflationRegistry.register(LAYOUT_NAME)"))
        val auxiliary = factory
            .substringAfter("fun registerLyricAuxiliaryResources()")
            .substringBefore("fun installLayoutCallbacks")
        // The callback is registered unconditionally, exactly like the credits-row hook: the
        // per-inflation gate (below) decides whether it applies.
        assertTrue(auxiliary.contains("LyricCreditsRowResourceHook.install()"))
        assertTrue(auxiliary.contains("LyricsPronunciationRowCentring.install()"))
    }

    @Test
    fun `the feature switch gates the write and defaults to off`() {
        assertFalse(LyricsPronunciationRowCentring.isEnabled(ModuleSettings()))
        assertTrue(
            LyricsPronunciationRowCentring.isEnabled(
                ModuleSettings(onlineLyricsTranslationEnabled = true),
            ),
        )
        // The callback checks the gate before it writes, rather than centring unconditionally.
        assertTrue(source.contains("if (!isFeatureEnabled()) return@register"))
        assertTrue(
            source.indexOf("if (!isFeatureEnabled()) return@register") <
                source.indexOf("applyToInflatedLayout(view)"),
        )
    }

    @Test
    fun `only the horizontal gravity and the reading translation are written`() {
        // No width, padding, text-size, weight, tag or child rewrite anywhere in the hook. Walking
        // the container's children is read-only (the correction has to find the glyphs and the
        // reading), so childCount/getChildAt are allowed; every write is still forbidden.
        listOf(
            "setPadding",
            "setTextSize",
            "layoutParams",
            "setMinimumWidth",
            "setMinimumHeight",
            "setLayoutParams",
            ".tag =",
        ).forEach { token ->
            assertFalse("the hook must not write $token", source.contains(token))
        }
        // One assignment (`==` reads are not writes, hence the lookahead), and one translation.
        assertEquals(1, Regex("""\.gravity\s*=(?!=)""").findAll(source).count())
        assertEquals(1, Regex("""\.translationX\s*=(?!=)""").findAll(source).count())
        assertTrue(source.contains("val group = root as? LinearLayout ?: return"))
        assertEquals("AMPP-Centring", LyricsPronunciationRowCentring.LOG_TAG)
        assertTrue(source.contains("align reading='"))
        assertTrue(source.contains("shift=\$shift"))
    }

    // ---- marks inside the word's own container -----------------------------------------------------

    @Test
    fun `a trailing mark inside the container pulls the reading back onto the kana`() {
        // の? with "no" in one container: the mark widens the container by its own width, so
        // centring in it leaves the reading half a mark to the right of の.
        val row = karaokeRow(word(listOf("の", "?"), "no"))
        val group = row.getChildAt(0) as LinearLayout
        val reading = LyricsPronunciationRowCentring.readingChild(group)!!

        val shift = LyricsPronunciationRowCentring.readingShift(row, group, reading)

        assertEquals(-50f, shift, 0.5f)
    }

    @Test
    fun `a leading mark inside the container pushes the reading onto the kana`() {
        // 「お with "o": the mark sits in front, so centring leaves the reading half a mark left.
        val row = karaokeRow(word(listOf("「", "お"), "o"))
        val group = row.getChildAt(0) as LinearLayout
        val reading = LyricsPronunciationRowCentring.readingChild(group)!!

        val shift = LyricsPronunciationRowCentring.readingShift(row, group, reading)

        assertEquals(50f, shift, 0.5f)
    }

    @Test
    fun `marks on both sides of the container cancel out`() {
        // 「お」 with "o": the two marks are the same width, so the reading is already where it
        // belongs and nothing is written.
        val row = karaokeRow(word(listOf("「", "お", "」"), "o"))
        val group = row.getChildAt(0) as LinearLayout
        val reading = LyricsPronunciationRowCentring.readingChild(group)!!

        val shift = LyricsPronunciationRowCentring.readingShift(row, group, reading)

        assertEquals(0f, shift, 0.5f)
    }

    @Test
    fun `a container of pure marks is left alone`() {
        // ！ on its own carries no core text, so there is nothing to centre its reading on.
        val row = karaokeRow(word(listOf("！"), "suappu", readingWidth = 300))
        val group = row.getChildAt(0) as LinearLayout
        val reading = LyricsPronunciationRowCentring.readingChild(group)!!

        assertEquals(0f, LyricsPronunciationRowCentring.readingShift(row, group, reading), 0.5f)
        assertNull(LyricsPronunciationRowCentring.coreSpan(row, group, reading))
    }

    @Test
    fun `a mark followed by the word's own blank still lands on the kana`() {
        // Apple appends the whitespace that follows a word to the word's own text, so `up!` reaches
        // the hook as `up! `: the mark is not the last character, and a trim that only looks at the
        // last character sees no mark at all and leaves the reading (mark + blank) / 2 to the right —
        // which is exactly the `up` of the `Wake up!` line on the device.
        val row = karaokeRow(
            chunkedWord(listOf("up" to 60, "!" to 20, " " to 10), "up", readingWidth = 40),
        )
        val group = row.getChildAt(0) as LinearLayout
        val reading = LyricsPronunciationRowCentring.readingChild(group)!!

        // The kana `up` is x=0..60 and the reading (x=25..65) sits at the container's centre.
        assertEquals(-15f, LyricsPronunciationRowCentring.readingShift(row, group, reading), 0.5f)
    }

    @Test
    fun `a mark at the very end of the word needs only half a mark`() {
        // The same word without the blank: half of the mark, not all of it.
        val row = karaokeRow(chunkedWord(listOf("up" to 60, "!" to 20), "up", readingWidth = 40))
        val group = row.getChildAt(0) as LinearLayout
        val reading = LyricsPronunciationRowCentring.readingChild(group)!!

        assertEquals(-10f, LyricsPronunciationRowCentring.readingShift(row, group, reading), 0.5f)
    }

    @Test
    fun `a lone blank at the end of the word is not part of its core`() {
        // A blank the word carries but the reading does not shifts it by half a blank; on the device
        // the reading carries the same blank, so the two halves cancel and mark-less rows stay put.
        val row = karaokeRow(chunkedWord(listOf("up" to 60, " " to 10), "up", readingWidth = 40))
        val group = row.getChildAt(0) as LinearLayout
        val reading = LyricsPronunciationRowCentring.readingChild(group)!!

        assertEquals(-5f, LyricsPronunciationRowCentring.readingShift(row, group, reading), 0.5f)
    }

    @Test
    fun `the reading of a kana plus a mark and a blank sits on the kana`() {
        // The `聴こえる? 魔法の…` line on the device: `る？ ` with `ru`. The mark is full width, so
        // leaving it in puts the reading 37px right of る at this size.
        val row = karaokeRow(
            chunkedWord(listOf("る" to 100, "?" to 50, " " to 25), "ru", readingWidth = 60),
        )
        val group = row.getChildAt(0) as LinearLayout
        val reading = LyricsPronunciationRowCentring.readingChild(group)!!

        assertEquals(-37f, LyricsPronunciationRowCentring.readingShift(row, group, reading), 0.5f)
    }

    // ---- a whole-word reading over a split kana run -----------------------------------------------

    @Test
    fun `a whole-word reading is centred on its kana run, not on one kana`() {
        // Apple puts the reading of a whole word in the word's first container and leaves the rest
        // empty: ス holds "suappu", ア/ッ/プ hold nothing. The reading is three characters wide and
        // the container one, so its centre has to travel to the middle of ス..プ.
        val row = karaokeRow(
            word(listOf("ス"), "suappu", readingWidth = 300),
            word(listOf("ア"), null),
            word(listOf("ッ"), null),
            word(listOf("プ"), null),
        )
        val group = row.getChildAt(0) as LinearLayout
        val reading = LyricsPronunciationRowCentring.readingChild(group)!!

        val shift = LyricsPronunciationRowCentring.readingShift(row, group, reading)

        // The run spans the glyphs of ス..プ (x=100..600 once each container is one glyph wide and
        // the reading widened the first one to three), and the reading starts at the container's
        // left edge (x=0..300).
        assertEquals(200f, shift, 0.5f)
    }

    @Test
    fun `a mark container after the word does not change the run`() {
        // スアップ！ and スアップ must land in the same place: the ！ is a container of its own and
        // is not text the reading covers.
        val withoutMark = karaokeRow(
            word(listOf("ス"), "suappu", readingWidth = 300),
            word(listOf("ア"), null),
            word(listOf("ッ"), null),
            word(listOf("プ"), null),
        )
        val withMark = karaokeRow(
            word(listOf("ス"), "suappu", readingWidth = 300),
            word(listOf("ア"), null),
            word(listOf("ッ"), null),
            word(listOf("プ"), null),
            word(listOf("！"), null),
        )

        val a = LyricsPronunciationRowCentring.readingChild(withoutMark.getChildAt(0) as LinearLayout)!!
        val b = LyricsPronunciationRowCentring.readingChild(withMark.getChildAt(0) as LinearLayout)!!
        val withoutShift = LyricsPronunciationRowCentring.readingShift(
            withoutMark,
            withoutMark.getChildAt(0) as LinearLayout,
            a,
        )
        val withShift = LyricsPronunciationRowCentring.readingShift(
            withMark,
            withMark.getChildAt(0) as LinearLayout,
            b,
        )

        assertEquals(withoutShift, withShift, 0.5f)
        assertEquals(200f, withShift, 0.5f)
    }

    @Test
    fun `a whole-word run stops at the next container that shows a reading`() {
        // ス(suappu)/ア()/が(ga): the run is ス..ア and must not swallow が, whose own reading would
        // otherwise be dragged left by the same correction.
        val row = karaokeRow(
            word(listOf("ス"), "suappu", readingWidth = 300),
            word(listOf("ア"), null),
            word(listOf("が"), "ga"),
        )
        val first = row.getChildAt(0) as LinearLayout
        val third = row.getChildAt(2) as LinearLayout
        val reading = LyricsPronunciationRowCentring.readingChild(first)!!
        val other = LyricsPronunciationRowCentring.readingChild(third)!!

        val shift = LyricsPronunciationRowCentring.readingShift(row, first, reading)
        val otherShift = LyricsPronunciationRowCentring.readingShift(row, third, other)

        // ス's glyph sits at x=100..200 and ア's at x=300..400; the reading (x=0..300) must land in
        // the middle of that stretch.
        assertEquals(100f, shift, 0.5f)
        // が is a per-kana reading in its own container: it is already centred and stays put.
        assertEquals(0f, otherShift, 0.5f)
    }

    // ---- per-kana readings, and words without marks -----------------------------------------------

    @Test
    fun `per-character readings are already centred and stay put`() {
        // マ(ma)/ジ(ji): a reading about as wide as its own kana covers exactly its own container.
        val row = karaokeRow(word(listOf("マ"), "ma"), word(listOf("ジ"), "ji"))
        val ma = row.getChildAt(0) as LinearLayout
        val ji = row.getChildAt(1) as LinearLayout

        val maShift = LyricsPronunciationRowCentring.readingShift(
            row,
            ma,
            LyricsPronunciationRowCentring.readingChild(ma)!!,
        )
        val jiShift = LyricsPronunciationRowCentring.readingShift(
            row,
            ji,
            LyricsPronunciationRowCentring.readingChild(ji)!!,
        )

        assertEquals(0f, maShift, 0.5f)
        assertEquals(0f, jiShift, 0.5f)
    }

    @Test
    fun `a mark in its own container leaves a per-kana reading where it is`() {
        // The の? of the device: Apple emitted the ? as a separate span, so the reading is already
        // centred on の and the following mark container must not drag it right.
        val row = karaokeRow(word(listOf("の"), "no"), word(listOf("?"), null))
        val group = row.getChildAt(0) as LinearLayout
        val reading = LyricsPronunciationRowCentring.readingChild(group)!!

        assertEquals(0f, LyricsPronunciationRowCentring.readingShift(row, group, reading), 0.5f)
    }

    @Test
    fun `an unpunctuated single kana reading stays centred`() {
        // ス with "suappu" and nothing else: the kana is the whole word, so no correction.
        val row = karaokeRow(word(listOf("ス"), "suappu", readingWidth = 300))
        val group = row.getChildAt(0) as LinearLayout
        val reading = LyricsPronunciationRowCentring.readingChild(group)!!

        assertEquals(0f, LyricsPronunciationRowCentring.readingShift(row, group, reading), 0.5f)
    }

    // ---- the writes the hook actually performs -----------------------------------------------------

    @Test
    fun `the hook centres the container and moves the reading exactly once`() {
        val row = karaokeRow(word(listOf("の", "?"), "no"))
        val group = row.getChildAt(0) as LinearLayout
        val reading = LyricsPronunciationRowCentring.readingChild(group)!!

        LyricsPronunciationRowCentring.applyToInflatedLayout(group)

        assertEquals(Gravity.CENTER_HORIZONTAL, group.gravity and Gravity.HORIZONTAL_GRAVITY_MASK)
        assertEquals(-50f, reading.translationX, 0.5f)
        // Apple's geometry is untouched: the container keeps its orientation and baseline flag,
        // and the children keep the widths they were inflated with.
        assertEquals(LinearLayout.VERTICAL, group.orientation)
        assertFalse(group.isBaselineAligned)
        assertEquals(200, group.width)
        assertEquals(80, reading.width)
        // Re-applying (a rebound row, a second dispatch) writes nothing new.
        LyricsPronunciationRowCentring.applyToInflatedLayout(group)
        assertEquals(-50f, reading.translationX, 0.5f)
        // A view tag is DataBinding's; writing one crashes the lyrics screen.
        assertNull(group.tag)
        assertNull(reading.tag)
        // Computing again after the write must not accumulate the shift.
        assertEquals(
            -50f,
            LyricsPronunciationRowCentring.readingShift(row, group, reading),
            0.5f,
        )
    }

    @Test
    fun `the correction is re-run when the reading gets its text, not only when the container lays out`() {
        // Apple sets the romanization through a text future. A reading narrower than its word does
        // not move the container when that text lands, so a correction that only listened to the
        // container's layout would never see the text and would leave the reading half a mark right.
        assertTrue(source.contains("watchReading(group, reading)"))
        assertTrue(source.contains("reading.addTextChangedListener("))
        assertTrue(source.contains("reading.addOnLayoutChangeListener("))
        assertTrue(source.contains("watchedReadings.put(reading, Unit) != null"))
    }

    @Test
    fun `a hidden reading is not moved`() {
        // Apple also inflates a per-kana reading it then hides; only the shown one is positioned.
        val row = karaokeRow(word(listOf("の", "?"), "no"))
        val group = row.getChildAt(0) as LinearLayout
        val reading = LyricsPronunciationRowCentring.readingChild(group)!!
        reading.visibility = View.GONE

        LyricsPronunciationRowCentring.alignToCoreText(group)

        assertEquals(0f, reading.translationX, 0.001f)
    }

    @Test
    fun `a root that is not the vertical container is left untouched`() {
        val activity = Robolectric.buildActivity(Activity::class.java).setup().get()
        val notTheContainer = android.widget.FrameLayout(activity)

        LyricsPronunciationRowCentring.applyToInflatedLayout(notTheContainer)

        // The guard is a cast, so nothing was written anywhere on a non-LinearLayout root.
        assertTrue(source.contains("val group = root as? LinearLayout ?: return"))
        assertEquals(0, notTheContainer.childCount)
        assertNull(notTheContainer.tag)
    }

    @Test
    fun `the row is preferred over the line so the background row cannot leak in`() {
        // Both karaoke rows are inflated from one word list, so a run that escaped its row would
        // walk into the other row's containers. The hook resolves the row by Apple's flexbox ids.
        assertEquals(
            listOf("karaoke_line_flexbox", "bg_line_flexbox"),
            LyricsPronunciationRowCentring.ROW_ID_NAMES,
        )
        assertEquals(
            "karaoke_lyrics_line_container",
            LyricsPronunciationRowCentring.LINE_CONTAINER_ID_NAME,
        )
        assertTrue(source.contains("ROW_ID_NAMES.any { hasEntryName(parent, it) }"))
    }

    @Test
    fun `containers are found by the id Apple puts on them`() {
        // The layout is named `lyrics_pronunciation_vertical_group`, but the container view carries
        // the id `lyrics_pronunciation_vertical_container`; looking the container up by the layout
        // name can never match on the device.
        assertEquals(
            "lyrics_pronunciation_vertical_container",
            LyricsPronunciationRowCentring.CONTAINER_ID_NAME,
        )
        assertTrue(source.contains("isContainer(view)"))
        assertTrue(source.contains("hasEntryName(view, CONTAINER_ID_NAME)"))
    }

    // ---- fixtures ---------------------------------------------------------------------------------

    /** One laid-out word: its glyphs, its reading, and the widths the row measures them at. */
    private fun word(
        kana: List<String>,
        reading: String?,
        glyphWidth: Int = 100,
        readingWidth: Int = 80,
    ): Word = Word(kana, reading, readingWidth, kana.map { glyphWidth })

    /** A word whose glyphs have widths of their own, so a mark and the blank after it are chunks. */
    private fun chunkedWord(
        chunks: List<Pair<String, Int>>,
        reading: String?,
        readingWidth: Int = 80,
    ): Word = Word(chunks.map { it.first }, reading, readingWidth, chunks.map { it.second })

    private class Word(
        val kana: List<String>,
        val reading: String?,
        val readingWidth: Int,
        val widths: List<Int>,
    )

    /**
     * A laid-out karaoke row holding one container per word, exactly the shape Apple inflates:
     * a vertical container, the word's glyphs in a row above, the reading below.
     */
    private fun karaokeRow(vararg words: Word): LinearLayout {
        val activity = Robolectric.buildActivity(Activity::class.java).setup().get()
        val row = LinearLayout(activity).apply { orientation = LinearLayout.HORIZONTAL }
        words.forEach { word -> row.addView(wordCell(activity, word)) }
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

    /** A glyph or reading view with a fixed width, so the fixture does not depend on measurement. */
    private fun textView(activity: Activity, text: String): TextView = TextView(activity).apply {
        this.text = text
        setTextSize(android.util.TypedValue.COMPLEX_UNIT_PX, 40f)
    }

    private fun projectFile(relativePath: String): String = sequenceOf(
        File(relativePath),
        File("../$relativePath"),
    ).firstOrNull(File::isFile)?.readText()
        ?: error("$relativePath was not found from the unit-test working directory")
}
