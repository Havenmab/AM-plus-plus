package dev.amenhancer.module.hook

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Pure coverage for the decisions the pronunciation alignment seams rely on: the row
 * geometry, the lyric-body span and the script predicates.
 */
class PronunciationAlignmentMathTest {

    @Test
    fun `the edge bias flips with the paragraph direction`() {
        // A wider leading advance shifts the body right in LTR and left in RTL.
        assertEquals(2f, PronunciationAlignmentMath.edgeBias(leading = 9f, trailing = 5f, rtl = false), 0f)
        assertEquals(-2f, PronunciationAlignmentMath.edgeBias(leading = 9f, trailing = 5f, rtl = true), 0f)
        assertEquals(0f, PronunciationAlignmentMath.edgeBias(leading = 5f, trailing = 5f, rtl = false), 0f)
    }

    @Test
    fun `the centred edge is clamped inside the row`() {
        // 100..300 -> centre 200; a correction past the end clamps to the row's right edge.
        assertEquals(200, PronunciationAlignmentMath.centeredLeft(minLeft = 100, maxLeft = 300, correction = 0f))
        assertEquals(300, PronunciationAlignmentMath.centeredLeft(minLeft = 100, maxLeft = 300, correction = 400f))
        assertEquals(100, PronunciationAlignmentMath.centeredLeft(minLeft = 100, maxLeft = 300, correction = -400f))
        assertEquals(210, PronunciationAlignmentMath.centeredLeft(minLeft = 100, maxLeft = 300, correction = 9.6f))
        // A collapsed row has no room to centre in.
        assertEquals(50, PronunciationAlignmentMath.centeredLeft(minLeft = 50, maxLeft = 50, correction = 3f))
    }

    @Test
    fun `the body span skips punctuation and keeps combining marks`() {
        assertEquals(2, PronunciationAlignmentMath.bodyStart("（（hello"))
        assertEquals(5, PronunciationAlignmentMath.bodyEnd("hello…"))
        // A decomposed final letter keeps its combining mark inside the span.
        val decomposed = "cafe\u0301."
        assertEquals(decomposed.length - 1, PronunciationAlignmentMath.bodyEnd(decomposed))
        // A symbol-only track has no body: the start runs to the end and the span is empty.
        val symbols = "♪♪"
        assertEquals(symbols.length, PronunciationAlignmentMath.bodyStart(symbols))
        assertEquals(0, PronunciationAlignmentMath.bodyEnd(symbols))
        assertTrue(PronunciationAlignmentMath.bodyStart(symbols) >= PronunciationAlignmentMath.bodyEnd(symbols))
    }

    @Test
    fun `rtl detection and the cjk fallback follow the host rules`() {
        assertTrue(PronunciationAlignmentMath.isRtlText("שלום"))
        assertFalse(PronunciationAlignmentMath.isRtlText("♪ hello"))
        assertFalse(PronunciationAlignmentMath.isRtlText("♪♪"))
        assertTrue(PronunciationAlignmentMath.isCjkLanguage("zh"))
        assertTrue(PronunciationAlignmentMath.isCjkLanguage("yue"))
        assertFalse(PronunciationAlignmentMath.isCjkLanguage("en"))
    }
}
