package dev.amenhancer.module.lyrics.source

import dev.amenhancer.module.hook.AutoLyricsCandidate
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * Pins the narrow line-level acceptance change: only a source that opts in via
 * [AutoLyricsSource.acceptsLineTiming] may resolve with Line timing.
 */
class AutoLyricsSourceResolverTest {

    @Test
    fun `a search source that opts in resolves with line timing`() {
        val resolver = AutoLyricsSourceResolver(
            listOf(AutoLyricsSource("kuwo", acceptsLineTiming = true) { LINE_TTML }),
        )

        assertEquals(AutoLyricsCandidate("kuwo", LINE_TTML), resolver.fetch(42L))
    }

    @Test
    fun `existing providers keep requiring word timing`() {
        val resolver = AutoLyricsSourceResolver(
            listOf(AutoLyricsSource("amll") { LINE_TTML }),
        )

        assertNull(resolver.fetch(42L))
    }

    @Test
    fun `an existing provider still accepts word timing`() {
        val resolver = AutoLyricsSourceResolver(
            listOf(AutoLyricsSource("amll") { WORD_TTML }),
        )

        assertEquals(AutoLyricsCandidate("amll", WORD_TTML), resolver.fetch(42L))
    }

    @Test
    fun `line acceptance does not skip structural validation`() {
        val resolver = AutoLyricsSourceResolver(
            listOf(
                AutoLyricsSource("kuwo", acceptsLineTiming = true) { "<tt><p>no body</p></tt>" },
            ),
        )

        assertNull(resolver.fetch(42L))
    }

    @Test
    fun `a rejected line-only source does not consume the lookup`() {
        val calls = mutableListOf<String>()
        val resolver = AutoLyricsSourceResolver(
            listOf(
                AutoLyricsSource("amll") {
                    calls += "amll"
                    LINE_TTML
                },
                AutoLyricsSource("kuwo", acceptsLineTiming = true) {
                    calls += "kuwo"
                    LINE_TTML
                },
            ),
        )

        assertEquals(AutoLyricsCandidate("kuwo", LINE_TTML), resolver.fetch(42L))
        assertEquals(listOf("amll", "kuwo"), calls)
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
