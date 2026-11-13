package dev.amenhancer.module.hook

import dev.amenhancer.module.lyrics.online.NativePronunciationSpan
import dev.amenhancer.module.model.ModuleSettings
import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Cover for the pronunciation-merge overlay POC.
 *
 * The overlay itself is view wiring, so the tests pin the two halves that can break silently:
 *
 *  1. the values it decides — pairing a line's word containers with the stored spans, the merged
 *     groups, the per-sub-token character ranges that drive the highlight, the overlay's geometry
 *     and the dimmed paint. These are the pure functions [LyricsPronunciationOverlay] exposes, and
 *     they are exercised directly;
 *  2. the structure a future edit can undo — the registration on Apple's two verified layout
 *     names, the feature gate running *before* any view is written, and the merged group's original
 *     pronunciation row being hidden **INVISIBLE** rather than GONE (GONE would collapse the
 *     `wrap_content` container and re-flow the lyric line).
 */
class LyricsPronunciationOverlayTest {

    private val source = projectFile(
        "host-applemusic/src/main/java/dev/amenhancer/module/hook/LyricsPronunciationOverlay.kt",
    )

    private val factory = projectFile(
        "host-applemusic/src/main/java/dev/amenhancer/module/hook/AppleMusicHostFactory.kt",
    )

    private fun span(text: String, begin: Long, end: Long) =
        NativePronunciationSpan(begin = begin, end = end, text = text)

    // -----------------------------------------------------------------------------------
    // Pairing a line's containers with the stored romanization spans
    // -----------------------------------------------------------------------------------

    @Test
    fun `word texts pair one to one with the stored spans in order`() {
        val tokens = LyricsPronunciationOverlay.pairTokens(
            wordTexts = listOf("マ", "ジ"),
            spans = listOf(span("ma", 0, 200), span("ji", 200, 400)),
        )

        assertNotNull(tokens)
        assertEquals(listOf("マ", "ジ"), tokens!!.map { it.text })
        // The romanization and the timing are the stored span's; the text stays Apple's word.
        assertEquals(listOf("ma", "ji"), tokens.map { it.romanization })
        assertEquals(0L, tokens[0].begin)
        assertEquals(200L, tokens[0].end)
        assertEquals(400L, tokens[1].end)
    }

    @Test
    fun `a partial span lane is refused instead of guessed`() {
        // One stored span for a two-word line: positional pairing would put one reading under the
        // wrong word, so the whole line is refused and Apple's row stays as it is.
        assertNull(
            LyricsPronunciationOverlay.pairTokens(
                wordTexts = listOf("マ", "ジ"),
                spans = listOf(span("ma", 0, 200)),
            ),
        )
        assertNull(
            LyricsPronunciationOverlay.pairTokens(
                wordTexts = listOf("マ"),
                spans = emptyList(),
            ),
        )
    }

    @Test
    fun `the line text is the concatenation of the word texts`() {
        assertEquals("マジカル", LyricsPronunciationOverlay.lineText(listOf("マ", "ジ", "カ", "ル")))
        assertEquals("", LyricsPronunciationOverlay.lineText(emptyList()))
    }

    // -----------------------------------------------------------------------------------
    // The merged groups
    // -----------------------------------------------------------------------------------

    @Test
    fun `the merged groups come from the token merger and keep every token`() {
        val groups = LyricsPronunciationOverlay.mergedGroups(
            wordTexts = listOf("マ", "ジ", "で"),
            spans = listOf(
                span("ma", 0, 200),
                span("ji", 200, 400),
                span("de", 400, 600),
            ),
        )

        assertNotNull(groups)
        assertEquals(listOf("マジ", "で"), groups!!.map { it.text })
        assertEquals(listOf("maji", "de"), groups.map { it.romanization })
        assertEquals(2, groups[0].parts.size)
        assertTrue(groups[0].isMerged)
        assertFalse(groups[1].isMerged)
        // Only the group that actually merged needs an overlay; singles stay Apple's.
        assertEquals(listOf("マジ"), LyricsPronunciationOverlay.overlayGroups(groups).map { it.text })
    }

    @Test
    fun `an unpaired line has no merged groups at all`() {
        assertNull(
            LyricsPronunciationOverlay.mergedGroups(
                wordTexts = listOf("マ", "ジ"),
                spans = emptyList(),
            ),
        )
    }

    // -----------------------------------------------------------------------------------
    // Sub-token character ranges (what the highlight paints)
    // -----------------------------------------------------------------------------------

    private fun mergedGroup(
        texts: List<String>,
        romanizations: List<String?>,
        ends: List<Long>,
    ) = LyricsPronunciationOverlay.mergedGroups(
        wordTexts = texts,
        spans = romanizations.mapIndexed { index, romanization ->
            span(romanization.orEmpty(), if (index == 0) 0L else ends[index - 1], ends[index])
        },
    )!!.first()

    @Test
    fun `each sub-token owns the character slice of its own romanization`() {
        val group = mergedGroup(
            texts = listOf("マ", "ジ", "カ"),
            romanizations = listOf("ma", "ji", "ka"),
            ends = listOf(200, 400, 600),
        )
        assertEquals("majika", group.romanization)

        val ranges = LyricsPronunciationOverlay.characterRanges(group, positionMs = null)

        // Start-inclusive, end-exclusive, so the slices concatenate back to the merged string.
        assertEquals(listOf(0 to 2, 2 to 4, 4 to 6), ranges.map { it.start to it.end })
        assertEquals(
            group.romanization,
            ranges.joinToString("") { group.romanization.substring(it.start, it.end) },
        )
    }

    @Test
    fun `a finished sub-token is sung and the rest stay dimmed`() {
        val group = mergedGroup(
            texts = listOf("マ", "ジ", "カ"),
            romanizations = listOf("ma", "ji", "ka"),
            ends = listOf(200, 400, 600),
        )

        // Between the second and third token: only the first has elapsed.
        assertEquals(
            listOf(true, false, false),
            LyricsPronunciationOverlay.characterRanges(group, positionMs = 250).map { it.sung },
        )
        // The boundary counts as sung: the token's own interval has ended.
        assertEquals(
            listOf(true, true, false),
            LyricsPronunciationOverlay.characterRanges(group, positionMs = 400).map { it.sung },
        )
        assertEquals(
            listOf(true, true, true),
            LyricsPronunciationOverlay.characterRanges(group, positionMs = 900).map { it.sung },
        )
    }

    @Test
    fun `an unknown position leaves every sub-token unsung`() {
        val group = mergedGroup(
            texts = listOf("マ", "ジ"),
            romanizations = listOf("ma", "ji"),
            ends = listOf(200, 400),
        )

        assertEquals(
            listOf(false, false),
            LyricsPronunciationOverlay.characterRanges(group, positionMs = null).map { it.sung },
        )
    }

    @Test
    fun `a range is clipped to the rendered text`() {
        val range = LyricsPronunciationOverlay.CharacterRange(start = 4, end = 8, sung = true)

        assertEquals(
            LyricsPronunciationOverlay.CharacterRange(start = 4, end = 6, sung = true),
            LyricsPronunciationOverlay.clip(range, length = 6),
        )
        // Nothing of the range survives a shorter text, so nothing is painted.
        assertNull(LyricsPronunciationOverlay.clip(range, length = 4))
    }

    // -----------------------------------------------------------------------------------
    // Overlay geometry and paint
    // -----------------------------------------------------------------------------------

    @Test
    fun `the overlay spans the merged group horizontally on the pronunciation row`() {
        val first = LyricsPronunciationOverlay.Extent(left = 10, top = 100, right = 40, bottom = 140)
        val second = LyricsPronunciationOverlay.Extent(left = 40, top = 100, right = 90, bottom = 140)
        // The original pronunciation row of the group's first container.
        val row = LyricsPronunciationOverlay.Extent(left = 14, top = 80, right = 30, bottom = 100)

        val placement = LyricsPronunciationOverlay.placement(listOf(first, second), row)

        assertNotNull(placement)
        // Left of the first container to right of the last one; vertically the old row's band.
        assertEquals(10, placement!!.left)
        assertEquals(90, placement.right)
        assertEquals(80, placement.top)
        assertEquals(100, placement.bottom)
        assertEquals(80, placement.width)
        assertEquals(20, placement.height)
    }

    @Test
    fun `a group with no container has no placement`() {
        val row = LyricsPronunciationOverlay.Extent(left = 0, top = 0, right = 10, bottom = 10)
        assertNull(LyricsPronunciationOverlay.placement(emptyList(), row))
        assertNull(LyricsPronunciationOverlay.unionExtent(emptyList()))
    }

    @Test
    fun `dimming keeps the colour and lowers the alpha`() {
        // Apple's own pronunciation colour at 40%: same RGB, so the row still reads as one colour.
        assertEquals(0x66FFFFFF.toInt(), LyricsPronunciationOverlay.dimmedColor(0xFFFFFFFF.toInt()))
        assertEquals(0x66ABCDEF.toInt(), LyricsPronunciationOverlay.dimmedColor(0xFFABCDEF.toInt()))
    }

    // -----------------------------------------------------------------------------------
    // Structure: registration, gate, INVISIBLE
    // -----------------------------------------------------------------------------------

    @Test
    fun `registers Apple's verified word container and line layouts`() {
        assertEquals(
            "lyrics_pronunciation_vertical_group",
            LyricsPronunciationOverlay.WORD_CONTAINER_LAYOUT_NAME,
        )
        assertEquals(
            "karaoke_lyrics_line_container",
            LyricsPronunciationOverlay.LINE_LAYOUT_NAME,
        )
        assertTrue(LyricsPronunciationOverlay.LINE_LAYOUT_NAMES.contains(LyricsPronunciationOverlay.LINE_LAYOUT_NAME))
        assertTrue(source.contains("LayoutInflationRegistry.register(WORD_CONTAINER_LAYOUT_NAME)"))
        assertTrue(source.contains("LayoutInflationRegistry.register(layoutName)"))
        assertTrue(source.contains("tagWordContainer(view)"))
        assertTrue(source.contains("observeLine(view)"))
        // The line gets a layout listener, which is the only point with real geometry.
        assertTrue(source.contains("addOnLayoutChangeListener"))
    }

    @Test
    fun `the feature switch gates every write and defaults to off`() {
        assertFalse(LyricsPronunciationOverlay.isEnabled(ModuleSettings()))
        assertTrue(
            LyricsPronunciationOverlay.isEnabled(ModuleSettings(onlineLyricsTranslationEnabled = true)),
        )

        // Both callbacks check the gate first; no view is tagged or observed with the feature off.
        val gate = source.indexOf("if (!isFeatureEnabled()) return@register")
        assertTrue(gate >= 0)
        assertTrue(source.indexOf("if (!isFeatureEnabled()) return@register", gate + 1) > gate)
        assertTrue(gate < source.indexOf("tagWordContainer(view)"))
        assertTrue(gate < source.indexOf("observeLine(view)"))
        assertTrue(source.contains("settings.onlineLyricsTranslationEnabled"))
    }

    @Test
    fun `a hidden pronunciation row is invisible and never gone`() {
        // INVISIBLE keeps the container's wrap_content width, which the overlay's geometry needs.
        assertTrue(source.contains("slot.pronunciation.visibility = View.INVISIBLE"))
        assertFalse(source.contains("View.GONE"))
        // The view is given back when the line is recycled.
        assertTrue(source.contains("if (view.visibility == View.INVISIBLE) view.visibility = View.VISIBLE"))
    }

    @Test
    fun `the factory installs the overlay and binds the live store`() {
        val auxiliary = factory
            .substringAfter("fun registerLyricAuxiliaryResources()")
            .substringBefore("fun installLayoutCallbacks")
        assertTrue(auxiliary.contains("LyricsPronunciationOverlay.install()"))

        val assembly = factory.substringAfter("fun appleMusic(")
        assertTrue(assembly.contains("LyricsPronunciationOverlay.bindStore(nativeLyricOverlay)"))
        assertTrue(assembly.indexOf("bindStore(") > assembly.indexOf("val settings = config.settings()"))
    }

    @Test
    fun `the store row is looked up by the line text and its spans drive the overlay`() {
        assertTrue(source.contains("rowForText(currentStore.currentSongId(), lineText(wordTexts))"))
        assertTrue(source.contains("content.pronunciationSpans"))
        assertTrue(source.contains("LyricTokenMerger::merge"))
    }

    @Test
    fun `the playback position comes from the existing reflection helper`() {
        // The POC must not invent a clock: it reuses PlaybackPositionSource#readPosition.
        assertTrue(source.contains("resolvePlaybackPositionSource(player)?.readPosition()"))
        assertTrue(source.contains("ForegroundColorSpan"))
        assertTrue(source.contains("characterRanges(overlay.group, positionMs)"))
    }

    private fun projectFile(relativePath: String): String = sequenceOf(
        File(relativePath),
        File("../$relativePath"),
    ).firstOrNull(File::isFile)?.readText()
        ?: error("$relativePath was not found from the unit-test working directory")
}
