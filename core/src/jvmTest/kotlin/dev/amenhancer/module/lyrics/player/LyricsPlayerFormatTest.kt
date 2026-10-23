package dev.amenhancer.module.lyrics.player

import org.junit.Assert.assertEquals
import org.junit.Test

class LyricsPlayerFormatTest {
    @Test
    fun `position renders minutes and seconds below an hour`() {
        assertEquals("0:00", LyricsPlayerFormat.position(0))
        assertEquals("0:05", LyricsPlayerFormat.position(5_000))
        assertEquals("1:23", LyricsPlayerFormat.position(83_000))
        assertEquals("10:00", LyricsPlayerFormat.position(600_000))
    }

    @Test
    fun `position renders hours once the track is longer than an hour`() {
        assertEquals("1:00:00", LyricsPlayerFormat.position(3_600_000))
        assertEquals("1:02:03", LyricsPlayerFormat.position(3_723_000))
    }

    @Test
    fun `negative or missing time falls back to zero`() {
        assertEquals("0:00", LyricsPlayerFormat.position(-1))
        assertEquals("0:00", LyricsPlayerFormat.position(Long.MIN_VALUE))
    }
}
