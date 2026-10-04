package dev.amenhancer.module.lyrics.online

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The per-track diagnostics budget: one bounded pass per song, reset on a track
 * change, and a logger failure never escapes into the enrichment call.
 */
class TrackScopedDiagnosticsTest {

    @Test
    fun `a track emits at most its bounded line budget`() {
        val lines = mutableListOf<String>()
        val diagnostics = TrackScopedDiagnostics(lines::add, maxLinesPerTrack = 3)

        repeat(5) { index -> diagnostics.log(7L, "line$index") }

        assertEquals(listOf("line0", "line1", "line2"), lines)
    }

    @Test
    fun `a track change resets the budget`() {
        val lines = mutableListOf<String>()
        val diagnostics = TrackScopedDiagnostics(lines::add, maxLinesPerTrack = 2)

        repeat(4) { diagnostics.log(1L, "one") }
        repeat(4) { diagnostics.log(2L, "two") }

        assertEquals(listOf("one", "one", "two", "two"), lines)
    }

    @Test
    fun `a throwing logger does not escape`() {
        val diagnostics = TrackScopedDiagnostics({ error("log sink unavailable") })

        diagnostics.log(7L, "line")

        assertTrue(true)
    }
}
