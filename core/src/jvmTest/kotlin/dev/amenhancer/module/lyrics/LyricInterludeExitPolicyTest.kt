package dev.amenhancer.module.lyrics

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The interlude's three-dot exit: how long it is, and how a line change waits for it.
 *
 * The numbers are 1626's own (`V0#N` / `A#N`: `setDuration(750)` on the grow, `250` on the
 * shrink+fade pair), and the relation that matters is the one against the wave: the exit is *longer*
 * than the spring that follows it, so "start the rows immediately" can only ever overlap.
 */
class LyricInterludeExitPolicyTest {

    @Test
    fun theExitIsTheHostsOwnSevenHundredAndFiftyPlusTwoHundredAndFifty() {
        assertEquals(750L, LyricInterludeExitPolicy.GROW_MS)
        assertEquals(250L, LyricInterludeExitPolicy.SHRINK_MS)
        assertEquals(1_000L, LyricInterludeExitPolicy.EXIT_MS)
        assertEquals(
            LyricInterludeExitPolicy.GROW_MS + LyricInterludeExitPolicy.SHRINK_MS,
            LyricInterludeExitPolicy.EXIT_MS,
        )
    }

    @Test
    fun theExitOutlastsTheWaveWhichIsTheWholeReasonToWait() {
        // The spring settles in 450 ms (AMLL's t99); the dots take 1000. Starting the rows with the
        // exit still running is the overlap this feature removes.
        assertTrue(LyricInterludeExitPolicy.EXIT_MS > LyricLineSpringPolicy.AMLL_SETTLE_MS.toLong())
        assertTrue(LyricInterludeExitPolicy.STALE_MS > LyricInterludeExitPolicy.EXIT_MS)
    }

    @Test
    fun remainingTimeNeverGoesNegative() {
        assertEquals(1_000L, LyricInterludeExitPolicy.remainingMs(1_000L, 0L))
        assertEquals(1L, LyricInterludeExitPolicy.remainingMs(1_000L, 999L))
        assertEquals(0L, LyricInterludeExitPolicy.remainingMs(1_000L, 1_000L))
        assertEquals(0L, LyricInterludeExitPolicy.remainingMs(1_000L, 5_000L))
        assertEquals(0L, LyricInterludeExitPolicy.remainingMs(0L, 5_000L))
    }

    @Test
    fun anExitShorterThanASliverOfAFrameIsNotWorthParkingFor() {
        assertTrue(LyricInterludeExitPolicy.isWorthHolding())
        assertFalse(LyricInterludeExitPolicy.isWorthHolding(0L))
        assertFalse(LyricInterludeExitPolicy.isWorthHolding(LyricInterludeExitPolicy.MIN_HOLD_MS - 1))
        assertTrue(LyricInterludeExitPolicy.isWorthHolding(LyricInterludeExitPolicy.MIN_HOLD_MS))
    }

    @Test
    fun aHoldRunsFromTheExitStartToTheBookedDuration() {
        val hold = LyricInterludeHold()
        assertEquals(0L, hold.remainingMs(0L))
        assertFalse(hold.isHolding(0L))

        hold.start(10_000L)
        assertEquals(1_000L, hold.remainingMs(10_000L))
        assertEquals(1, hold.startCount())
        assertTrue(hold.isHolding(10_500L))
        assertEquals(500L, hold.remainingMs(10_500L))
        assertEquals(10_000L + LyricInterludeExitPolicy.EXIT_MS, hold.endsAtMs())

        // The book value expiring is the fallback: the rows go at the same moment the animation
        // would have ended.
        assertFalse(hold.isHolding(11_000L))
        assertEquals(0L, hold.remainingMs(11_001L))
    }

    @Test
    fun aSecondStartSignalInsideOneExitNeverShortensIt() {
        val hold = LyricInterludeHold()
        hold.start(1_000L)
        // A second changed row, or a second line change inside the same exit, rebuilds the host's
        // AnimatorSet: it runs again for its full duration, so the wait moves *later*, not earlier.
        hold.start(1_400L)
        assertEquals(2_400L, hold.endsAtMs())
        assertEquals(2, hold.startCount())
        // ... and an *older* signal (a callback that arrives late) cannot shorten it either.
        hold.start(1_100L)
        assertEquals(2_400L, hold.endsAtMs())
    }

    @Test
    fun theExitsOwnEndCallbackReleasesImmediately() {
        val hold = LyricInterludeHold()
        hold.start(1_000L)
        hold.finish(1_620L)
        assertFalse(hold.isHolding(1_620L))
        assertEquals(0L, hold.remainingMs(1_630L))
        // Finishing when nothing is held is harmless.
        hold.finish(9_000L)
        assertEquals(0L, hold.remainingMs(9_000L))
    }

    @Test
    fun clearingDropsTheWaitEntirely() {
        val hold = LyricInterludeHold()
        hold.start(1_000L)
        hold.clear()
        assertEquals(0L, hold.remainingMs(1_000L))
        assertEquals(0L, hold.endsAtMs())
    }

    @Test
    fun aCustomDurationIsHonouredInsteadOfTheBookValue() {
        val hold = LyricInterludeHold(exitMs = 1_250L)
        hold.start(0L)
        assertEquals(1_250L, hold.remainingMs(0L))
        assertEquals(1_250L, hold.endsAtMs())
        // The default constructor still books the host's own exit length.
        assertEquals(LyricInterludeExitPolicy.EXIT_MS, LyricInterludeHold().also { it.start(0L) }.endsAtMs())
        // A custom duration below the worth-holding threshold is ignored, not booked.
        LyricInterludeHold(exitMs = 10L).also { it.start(0L) }.let { assertEquals(0L, it.endsAtMs()) }
    }
}
