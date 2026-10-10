package dev.amenhancer.module.lyrics.online

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Pins the retry rule of the presentation refresh: only an attempt that
 * actually re-invoked Apple's presentation may latch the gate dedupe state, and
 * every abort clears it so a later build seam or the native-presentation
 * binding seam can ask again. The `detail=` tokens the device log prints are
 * part of the same enum, so the log and the retry rule cannot drift apart.
 */
class PresentationRefreshOutcomeTest {

    @Test
    fun `only an applied presentation latches the dedupe state`() {
        assertTrue(PresentationRefreshOutcome.REBOUND.latches)
        assertTrue(PresentationRefreshOutcome.ADAPTER_UNAVAILABLE.latches)
        assertFalse(PresentationRefreshOutcome.REBOUND.cleared)
        assertFalse(PresentationRefreshOutcome.ADAPTER_UNAVAILABLE.cleared)

        listOf(
            PresentationRefreshOutcome.NO_PRESENTATION_METHOD,
            PresentationRefreshOutcome.NOT_BOUND,
            PresentationRefreshOutcome.POINTER_DEAD,
            PresentationRefreshOutcome.SONG_CHANGED,
            PresentationRefreshOutcome.INVOKE_FAILED,
        ).forEach { outcome ->
            assertFalse(outcome.token, outcome.latches)
            assertTrue(outcome.token, outcome.cleared)
        }
    }

    @Test
    fun `the device log tokens stay stable`() {
        assertEquals("rebound", PresentationRefreshOutcome.REBOUND.token)
        assertEquals("adapter-unavailable", PresentationRefreshOutcome.ADAPTER_UNAVAILABLE.token)
        assertEquals("no-presentation-method", PresentationRefreshOutcome.NO_PRESENTATION_METHOD.token)
        assertEquals("not-bound", PresentationRefreshOutcome.NOT_BOUND.token)
        assertEquals("pointer-dead", PresentationRefreshOutcome.POINTER_DEAD.token)
        assertEquals("song-changed", PresentationRefreshOutcome.SONG_CHANGED.token)
        assertEquals("invoke-failed", PresentationRefreshOutcome.INVOKE_FAILED.token)
    }
}
