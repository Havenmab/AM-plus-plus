package dev.amenhancer.module.lyrics.online

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Pins the render-probe contract the device log depends on: only reads after an
 * armed refresh attempt are emitted, the line format is stable and grep-able,
 * duplicates inside one attempt are collapsed, a new attempt re-emits, and the
 * per-track budget bounds the whole thing. A throwing logger never escapes.
 */
class LyricsRenderProbeTest {

    private val lines = mutableListOf<String>()

    private fun probe(max: Int = 8) = LyricsRenderProbe(lines::add, maxLinesPerTrack = max)

    @Test
    fun `an armed track records the exact device line`() {
        val probe = probe()
        probe.arm(1787114805L)

        probe.record(
            phase = LyricsRenderProbe.Phase.GETTER,
            songId = 1787114805L,
            getter = "getHtmlPronunciationLineText",
            line = 12345L,
            official = true,
            online = false,
            result = "official",
        )

        assertEquals(
            listOf(
                "online-translation render-probe phase=getter " +
                    "getter=getHtmlPronunciationLineText id=1787114805 line=12345 " +
                    "official=true online=false result=official afterRefresh=true attempt=1",
            ),
            lines,
        )
    }

    @Test
    fun `an adapter bind without a line prints the none placeholder`() {
        val probe = probe()
        probe.arm(7L)

        probe.record(
            phase = LyricsRenderProbe.Phase.ADAPTER_BIND,
            songId = 7L,
            getter = "a",
            line = null,
            official = false,
            online = true,
            result = "render-plan",
        )

        assertTrue(lines.single().contains("phase=adapter-bind"))
        assertTrue(lines.single().contains("line=none"))
        assertTrue(lines.single().contains("result=render-plan"))
    }

    @Test
    fun `the same observation is deduped within an attempt`() {
        val probe = probe()
        probe.arm(7L)

        repeat(4) {
            probe.record(
                LyricsRenderProbe.Phase.GETTER,
                7L,
                "getHtmlPronunciationLineText",
                1L,
                true,
                false,
                "official",
            )
        }

        assertEquals(1, lines.size)
    }

    @Test
    fun `a new attempt re-emits the same getter`() {
        val probe = probe()
        probe.arm(7L)
        probe.record(LyricsRenderProbe.Phase.GETTER, 7L, "g", 1L, true, false, "official")
        probe.arm(7L)
        probe.record(LyricsRenderProbe.Phase.GETTER, 7L, "g", 1L, true, false, "official")

        assertEquals(2, lines.size)
        assertTrue(lines[0].contains("attempt=1"))
        assertTrue(lines[1].contains("attempt=2"))
    }

    @Test
    fun `a different result in the same attempt is emitted`() {
        val probe = probe()
        probe.arm(7L)
        probe.record(LyricsRenderProbe.Phase.GETTER, 7L, "g", 1L, false, true, "online")
        probe.record(LyricsRenderProbe.Phase.GETTER, 7L, "g", 1L, true, false, "official")

        assertEquals(2, lines.size)
    }

    @Test
    fun `reads before an arm are ignored`() {
        val probe = probe()

        probe.record(LyricsRenderProbe.Phase.GETTER, 7L, "g", 1L, true, false, "official")

        assertTrue(lines.isEmpty())
    }

    @Test
    fun `a read for another track is ignored`() {
        val probe = probe()
        probe.arm(7L)

        probe.record(LyricsRenderProbe.Phase.GETTER, 8L, "g", 1L, true, false, "official")

        assertTrue(lines.isEmpty())
    }

    @Test
    fun `the per-track budget bounds the output`() {
        val probe = probe(max = 3)
        probe.arm(7L)

        repeat(10) { index ->
            probe.record(
                LyricsRenderProbe.Phase.GETTER,
                7L,
                "getter$index",
                1L,
                true,
                false,
                "official",
            )
        }

        assertEquals(3, lines.size)
    }

    @Test
    fun `a track change resets the budget and the attempt`() {
        val probe = probe(max = 1)
        probe.arm(1L)
        probe.record(LyricsRenderProbe.Phase.GETTER, 1L, "g", 1L, true, false, "official")

        probe.arm(2L)
        probe.record(LyricsRenderProbe.Phase.GETTER, 2L, "g", 2L, true, false, "official")

        assertEquals(2, lines.size)
        assertTrue(lines[1].contains("id=2"))
        assertTrue(lines[1].contains("attempt=1"))
    }

    @Test
    fun `a throwing logger does not escape`() {
        val probe = LyricsRenderProbe({ error("log sink unavailable") })
        probe.arm(7L)

        probe.record(LyricsRenderProbe.Phase.GETTER, 7L, "g", 1L, true, false, "official")

        assertTrue(true)
    }

    @Test
    fun `isArmed is true only for the armed track with an attempt in flight`() {
        val probe = probe()

        assertFalse(probe.isArmed(7L))

        probe.arm(7L)
        assertTrue(probe.isArmed(7L))
        assertFalse(probe.isArmed(8L))

        probe.arm(8L)
        assertTrue(probe.isArmed(8L))
        assertFalse(probe.isArmed(7L))
        assertFalse(probe.isArmed(0L))
    }
}
