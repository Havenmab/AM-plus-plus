package dev.amenhancer.module.hook

import android.app.Activity
import android.text.TextPaint
import android.view.Gravity
import android.view.View
import android.widget.FrameLayout
import android.widget.LinearLayout
import dev.amenhancer.module.model.ModuleSettings
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
 * Cover for the per-word romanization centring hook.
 *
 * The hook itself is one write — the inflated `lyrics_pronunciation_vertical_group` container's
 * horizontal gravity — so the tests pin the three things a future change can break:
 *
 *  1. the registration exists for Apple's verified layout name and rides the auxiliary lyric
 *     resource path, so it is installed at the resource stage like the credits-row hook;
 *  2. the callback actually writes `CENTER_HORIZONTAL` on the container and leaves every other
 *     attribute of the container and of Apple's two children alone (a real `LinearLayout`, since
 *     the write is a platform gravity bit and not arithmetic this project can restate);
 *  3. the feature switch gates the write, so a module with the pronunciation/translation feature
 *     off leaves Apple's layout untouched.
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

    @Test
    fun `registers the verified per-word container on the auxiliary lyric resource path`() {
        assertEquals(
            "lyrics_pronunciation_vertical_group",
            LyricsPronunciationRowCentring.LAYOUT_NAME,
        )
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
    fun `the callback sets centre horizontal and leaves the children and container alone`() {
        val container = appleContainer()
        val word = container.getChildAt(0)
        val pronunciation = container.getChildAt(1)

        LyricsPronunciationRowCentring.applyToInflatedLayout(container)

        // Compare the horizontal bits only: the framework may carry an unrelated bit alongside.
        assertEquals(
            Gravity.CENTER_HORIZONTAL,
            container.gravity and Gravity.HORIZONTAL_GRAVITY_MASK,
        )
        // Apple's own geometry is untouched: only the container's horizontal gravity moved.
        assertEquals(LinearLayout.VERTICAL, container.orientation)
        assertFalse(container.isBaselineAligned)
        val wordParams = word.layoutParams as LinearLayout.LayoutParams
        assertEquals(LinearLayout.LayoutParams.WRAP_CONTENT, wordParams.width)
        assertEquals(LinearLayout.LayoutParams.WRAP_CONTENT, wordParams.height)
        val pronunciationParams = pronunciation.layoutParams as LinearLayout.LayoutParams
        assertEquals(LinearLayout.LayoutParams.WRAP_CONTENT, pronunciationParams.width)
        assertEquals(LinearLayout.LayoutParams.MATCH_PARENT, pronunciationParams.height)
        assertEquals(0, container.paddingLeft)
        assertEquals(0, container.paddingTop)
        assertEquals(0, container.paddingRight)
        assertEquals(0, container.paddingBottom)
        // Re-applying (a rebound row, a second dispatch) writes nothing new.
        LyricsPronunciationRowCentring.applyToInflatedLayout(container)
        assertEquals(
            Gravity.CENTER_HORIZONTAL,
            container.gravity and Gravity.HORIZONTAL_GRAVITY_MASK,
        )
    }

    @Test
    fun `a root that is not the vertical container is left untouched`() {
        val activity = Robolectric.buildActivity(Activity::class.java).setup().get()
        val notTheContainer = FrameLayout(activity)
        val unrelated = View(activity)

        LyricsPronunciationRowCentring.applyToInflatedLayout(notTheContainer)
        LyricsPronunciationRowCentring.applyToInflatedLayout(unrelated)

        // The guard is a cast, so nothing was written anywhere on a non-LinearLayout root.
        assertTrue(source.contains("val group = root as? LinearLayout ?: return"))
        assertEquals(0, notTheContainer.childCount)
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
    fun `only the horizontal gravity is written`() {
        // No width, padding, text-size, weight or child rewrite anywhere in the hook. Walking the
        // container's children is read-only (the correction has to find the word and the reading),
        // so childCount/getChildAt are allowed; every write is still forbidden.
        listOf(
            "setPadding",
            "setTextSize",
            "layoutParams",
            "setMinimumWidth",
            "setMinimumHeight",
            "setLayoutParams",
        ).forEach { token ->
            assertFalse("the hook must not write $token", source.contains(token))
        }
        // One assignment (`==` reads are not writes, hence the lookahead).
        assertEquals(1, Regex("""\.gravity\s*=(?!=)""").findAll(source).count())
    }

    @Test
    fun `a whole-word reading is centred on its kana run, not on one kana`() {
        // Apple gives each kana its own container and puts the reading of the whole word in the
        // first one: ス holds suappu, ア/ッ/プ hold nothing. Centring there is one kana wide, so the
        // reading has to be moved onto the centre of the ス..プ run instead.
        val row = karaokeRow(listOf("ス" to "suappu", "ア" to "", "ッ" to "", "プ" to "", "！" to ""))
        val first = row.getChildAt(0) as ViewGroup
        val reading = LyricsPronunciationRowCentring.readingChild(first)!!

        LyricsPronunciationRowCentring.alignToCoreText(first)

        val runCentre = (row.getChildAt(0).left + row.getChildAt(3).left + row.getChildAt(3).width) / 2f
        val readingCentre = first.left + reading.left + reading.width / 2f
        assertEquals(runCentre - readingCentre, reading.translationX, 0.5f)
    }

    @Test
    fun `a trailing punctuation container is not part of the run`() {
        val withMark = karaokeRow(listOf("ス" to "suappu", "ア" to "", "！" to ""))
        val withoutMark = karaokeRow(listOf("ス" to "suappu", "ア" to ""))

        LyricsPronunciationRowCentring.alignToCoreText(withMark.getChildAt(0) as ViewGroup)
        LyricsPronunciationRowCentring.alignToCoreText(withoutMark.getChildAt(0) as ViewGroup)

        val a = LyricsPronunciationRowCentring.readingChild(withMark.getChildAt(0) as ViewGroup)!!
        val b = LyricsPronunciationRowCentring.readingChild(withoutMark.getChildAt(0) as ViewGroup)!!
        // The mark's container must not pull the reading towards it.
        assertEquals(b.translationX, a.translationX, 0.5f)
    }

    @Test
    fun `a reading with no kana run is left alone`() {
        val row = karaokeRow(listOf("！" to "!"))
        val only = row.getChildAt(0) as ViewGroup

        LyricsPronunciationRowCentring.alignToCoreText(only)

        val reading = LyricsPronunciationRowCentring.readingChild(only)!!
        assertEquals(0f, reading.translationX, 0.5f)
    }

    @Test
    fun `trailing punctuation shifts the reading back under the kana`() {
        val paint = TextPaint()
        // キラキラ！ is wider than its reading because of the full-width mark: the reading must move
        // left by half the mark's width, which is exactly what centring in the roomy container loses.
        val trailing = LyricsPronunciationRowCentring.coreShift("キラキラ！", paint)
        assertTrue("a trailing mark must pull the reading left", trailing < 0f)
        assertEquals(-paint.measureText("！") / 2f, trailing, 0.01f)
        // A leading mark pushes it the other way.
        val leading = LyricsPronunciationRowCentring.coreShift("（だめ", paint)
        assertTrue("a leading mark must push the reading right", leading > 0f)
        assertEquals(paint.measureText("（") / 2f, leading, 0.01f)
    }

    @Test
    fun `plain text and symmetrical marks need no correction`() {
        val paint = TextPaint()
        assertEquals(0f, LyricsPronunciationRowCentring.coreShift("キラキラ", paint), 0.01f)
        // Marks on both sides cancel out.
        val balanced = LyricsPronunciationRowCentring.coreShift("（だめ）", paint)
        assertEquals(0f, balanced, 0.01f)
        // A word that is nothing but marks is not shifted at all.
        assertEquals(0f, LyricsPronunciationRowCentring.coreShift("！？", paint), 0.01f)
        assertEquals(0f, LyricsPronunciationRowCentring.coreShift("", paint), 0.01f)
    }

    /** A laid-out karaoke row: one container per kana, the reading only in the first one. */
    private fun karaokeRow(entries: List<Pair<String, String>>): LinearLayout {
        val activity = Robolectric.buildActivity(Activity::class.java).setup().get()
        val row = LinearLayout(activity).apply { orientation = LinearLayout.HORIZONTAL }
        entries.forEach { (kana, reading) ->
            row.addView(wordAndReading(kana, reading).first)
        }
        val width = 800
        val height = 160
        row.measure(
            View.MeasureSpec.makeMeasureSpec(width, View.MeasureSpec.EXACTLY),
            View.MeasureSpec.makeMeasureSpec(height, View.MeasureSpec.EXACTLY),
        )
        row.layout(0, 0, width, height)
        return row
    }

    /** A laid-out word container: the shape Apple inflates, with the given texts already in place. */
    private fun wordAndReading(
        wordText: String,
        readingText: String,
    ): Triple<LinearLayout, android.widget.TextView, android.widget.TextView> {
        val activity = Robolectric.buildActivity(Activity::class.java).setup().get()
        val container = LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
            isBaselineAligned = false
            gravity = Gravity.CENTER_HORIZONTAL
        }
        val word = android.widget.TextView(activity).apply {
            text = wordText
            setTextSize(android.util.TypedValue.COMPLEX_UNIT_PX, 40f)
        }
        val reading = android.widget.TextView(activity).apply {
            text = readingText
            setTextSize(android.util.TypedValue.COMPLEX_UNIT_PX, 24f)
        }
        container.addView(
            word,
            LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT,
                LinearLayout.LayoutParams.WRAP_CONTENT,
            ),
        )
        container.addView(
            reading,
            LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT,
                LinearLayout.LayoutParams.MATCH_PARENT,
            ),
        )
        val width = 600
        val height = 120
        container.measure(
            View.MeasureSpec.makeMeasureSpec(width, View.MeasureSpec.EXACTLY),
            View.MeasureSpec.makeMeasureSpec(height, View.MeasureSpec.EXACTLY),
        )
        container.layout(0, 0, width, height)
        return Triple(container, word, reading)
    }

    /** Apple's verified shape: vertical, wrap_content wide, fill_parent tall, baselineAligned off. */
    private fun appleContainer(): LinearLayout {
        val activity = Robolectric.buildActivity(Activity::class.java).setup().get()
        val container = LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
            isBaselineAligned = false
        }
        container.addView(
            View(activity),
            LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT,
                LinearLayout.LayoutParams.WRAP_CONTENT,
            ),
        )
        container.addView(
            View(activity),
            LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT,
                LinearLayout.LayoutParams.MATCH_PARENT,
            ),
        )
        return container
    }

    private fun projectFile(relativePath: String): String = sequenceOf(
        File(relativePath),
        File("../$relativePath"),
    ).firstOrNull(File::isFile)?.readText()
        ?: error("$relativePath was not found from the unit-test working directory")
}
