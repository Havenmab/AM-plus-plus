package dev.amenhancer.module.hook

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class TabletChromeBackdropRefreshPolicyTest {
    @Test
    fun hiddenConsumersNeverScheduleOrRecord() {
        val refresh = TabletChromeBackdropRefreshPolicy()
        refresh.requestRefresh(100L)
        assertNull(refresh.nextCaptureAt)
        assertFalse(refresh.takeCapture(100L))
        assertFalse(refresh.takeCapture(2000L))
    }

    @Test
    fun activationSchedulesBoundedCapturesWithoutWaitingForUserInput() {
        val refresh = TabletChromeBackdropRefreshPolicy()
        refresh.setVisible(true, 100L)
        for (scheduled in listOf(100L, 180L, 420L, 1060L)) {
            assertEquals(scheduled, refresh.nextCaptureAt)
            assertFalse(refresh.takeCapture(scheduled - 1L))
            assertTrue(refresh.takeCapture(scheduled))
        }
        assertNull(refresh.nextCaptureAt)
        assertFalse(refresh.takeCapture(5000L))
    }

    @Test
    fun changingTabsRestartsCaptureWhileBothBarsStayVisible() {
        val refresh = TabletChromeBackdropRefreshPolicy()
        refresh.setVisible(true, 0L)
        for (scheduled in listOf(0L, 80L, 320L, 960L)) assertTrue(refresh.takeCapture(scheduled))
        refresh.requestRefresh(2000L)
        for (scheduled in listOf(2000L, 2080L, 2320L, 2960L)) {
            assertEquals(scheduled, refresh.nextCaptureAt)
            assertTrue(refresh.takeCapture(scheduled))
        }
        assertNull(refresh.nextCaptureAt)
    }

    @Test
    fun layoutChangesBringCaptureForwardAndDoNotPostponeAnOwedFrame() {
        val refresh = TabletChromeBackdropRefreshPolicy()
        refresh.setVisible(true, 100L)
        assertTrue(refresh.takeCapture(100L))
        refresh.requestRefresh(140L)
        assertEquals(164L, refresh.nextCaptureAt)
        refresh.requestRefresh(150L)
        assertEquals(164L, refresh.nextCaptureAt)
        assertFalse(refresh.takeCapture(163L))
        assertTrue(refresh.takeCapture(164L))
        assertEquals(230L, refresh.nextCaptureAt)
    }

    @Test
    fun hiddenBarsCancelSettlingAndReturningBarsGetANewSchedule() {
        val refresh = TabletChromeBackdropRefreshPolicy()
        refresh.setVisible(true, 100L)
        assertTrue(refresh.takeCapture(100L))
        refresh.setVisible(false, 120L)
        assertNull(refresh.nextCaptureAt)
        assertFalse(refresh.takeCapture(180L))
        refresh.requestRefresh(500L)
        assertNull(refresh.nextCaptureAt)
        refresh.setVisible(true, 1000L)
        assertEquals(1000L, refresh.nextCaptureAt)
        assertTrue(refresh.takeCapture(1000L))
    }

    @Test
    fun steadyPreDrawVisibilityChecksDoNotRearmFinishedCapture() {
        val refresh = TabletChromeBackdropRefreshPolicy()
        refresh.setVisible(true, 0L)
        for (scheduled in listOf(0L, 80L, 320L, 960L)) assertTrue(refresh.takeCapture(scheduled))
        for (now in 1000L..2000L step 16L) {
            refresh.setVisible(true, now)
            assertNull(refresh.nextCaptureAt)
            assertFalse(refresh.takeCapture(now))
        }
    }

    @Test
    fun lateCallbacksCannotBusyLoopOrConsumeSeveralCapturesInOneFrame() {
        val refresh = TabletChromeBackdropRefreshPolicy()
        refresh.setVisible(true, 0L)
        assertTrue(refresh.takeCapture(1500L))
        assertEquals(1564L, refresh.nextCaptureAt)
        assertFalse(refresh.takeCapture(1500L))
        assertTrue(refresh.takeCapture(1564L))
        assertTrue(refresh.takeCapture(1628L))
        assertTrue(refresh.takeCapture(1692L))
        assertNull(refresh.nextCaptureAt)
    }

    @Test
    fun repeatedLayoutEventsAreCoalescedAndCaptureIsRateLimited() {
        val refresh = TabletChromeBackdropRefreshPolicy()
        refresh.setVisible(true, 0L)
        assertTrue(refresh.takeCapture(0L))
        for (now in 1L..63L) {
            refresh.requestRefresh(now)
            assertEquals(64L, refresh.nextCaptureAt)
            assertFalse(refresh.takeCapture(now))
        }
        assertTrue(refresh.takeCapture(64L))
        refresh.requestRefresh(65L)
        assertEquals(128L, refresh.nextCaptureAt)
    }

    @Test
    fun miniPlayerBecomingVisibleCanRefreshTheAlreadyVisibleTopBarSource() {
        val refresh = TabletChromeBackdropRefreshPolicy()
        refresh.setVisible(true, 0L)
        for (scheduled in listOf(0L, 80L, 320L, 960L)) assertTrue(refresh.takeCapture(scheduled))
        refresh.setVisible(true, 2000L)
        assertNull(refresh.nextCaptureAt)
        refresh.requestRefresh(2000L)
        assertTrue(refresh.takeCapture(2000L))
        assertEquals(2080L, refresh.nextCaptureAt)
    }
}
