package dev.amenhancer.module.hook

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Pins the relaxed downstream decision: a search-based source may hand the
 * pointer/cache seams line-timed TTML, while the fixed providers keep their
 * Word-only requirement and structural validation is never skipped.
 * [AutoLyricsTimingPolicy.SEARCH_ACCEPTS_LINE_TIMING] has to stay the single
 * switch so the whole change can be reverted in one edit.
 */
class AutoLyricsTimingPolicyTest {

    @Test
    fun `the search relaxation is the single switch and is on`() {
        assertTrue(AutoLyricsTimingPolicy.SEARCH_ACCEPTS_LINE_TIMING)
    }

    @Test
    fun `a line timed document passes the relaxed seam gate`() {
        assertTrue(AutoLyricsTimingPolicy.isAcceptableAtSeam(LINE_TTML))
    }

    @Test
    fun `a word timed document still passes the seam gate`() {
        assertTrue(AutoLyricsTimingPolicy.isAcceptableAtSeam(WORD_TTML))
    }

    @Test
    fun `a malformed document is still rejected at the seam gate`() {
        assertFalse(AutoLyricsTimingPolicy.isAcceptableAtSeam("<tt><p>no body</p></tt>"))
        assertFalse(AutoLyricsTimingPolicy.isAcceptableAtSeam(""))
    }

    @Test
    fun `a fixed provider still rejects line timing per source`() {
        assertFalse(AutoLyricsTimingPolicy.isAcceptable(LINE_TTML, sourceAcceptsLineTiming = false))
    }

    @Test
    fun `a fixed provider still accepts word timing per source`() {
        assertTrue(AutoLyricsTimingPolicy.isAcceptable(WORD_TTML, sourceAcceptsLineTiming = false))
    }

    @Test
    fun `a search source accepts line timing per source`() {
        assertTrue(AutoLyricsTimingPolicy.isAcceptable(LINE_TTML, sourceAcceptsLineTiming = true))
    }

    @Test
    fun `line acceptance does not skip structural validation`() {
        assertFalse(
            AutoLyricsTimingPolicy.isAcceptable(
                "<tt><p>no body</p></tt>",
                sourceAcceptsLineTiming = true,
            ),
        )
    }

    private companion object {
        const val WORD_TTML =
            "<tt xmlns:itunes=\"urn\" itunes:timing=\"Word\"><body>" +
                "<p><span begin=\"0s\" end=\"1s\">hello</span></p>" +
                "</body></tt>"
        const val LINE_TTML =
            "<tt xmlns:itunes=\"urn\" itunes:timing=\"Line\"><body>" +
                "<p>hello</p></body></tt>"
    }
}
