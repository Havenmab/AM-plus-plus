package dev.amenhancer.module.lyrics

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The interlude dots' life cycle, on the JVM.
 *
 * What these tests are for: the dots must be *gone* before the line-change wave has moved the next
 * line in, they must come back unchanged for the next interlude, and neither promise may depend on
 * a device, a `View` or a frame clock.
 */
class LyricInterludeDotsPolicyTest {

    // ---- which row is which ----------------------------------------------------------------

    @Test
    fun anInterludeBehindTheCurrentLineRetires() {
        assertEquals(LyricInterludeDotsAction.RETIRE, LyricInterludeDotsPolicy.actionFor(3, 4))
        assertEquals(LyricInterludeDotsAction.RETIRE, LyricInterludeDotsPolicy.actionFor(0, 1))
    }

    @Test
    fun theInterludeThatIsTheCurrentLineIsArmed() {
        assertEquals(LyricInterludeDotsAction.ARM, LyricInterludeDotsPolicy.actionFor(7, 7))
    }

    @Test
    fun anInterludeAheadOfTheCurrentLineIsLeftAlone() {
        assertEquals(LyricInterludeDotsAction.NONE, LyricInterludeDotsPolicy.actionFor(8, 7))
    }

    @Test
    fun anUnknownPositionTouchesNothing() {
        val noPosition = LyricInterludeDotsPolicy.NO_POSITION
        assertEquals(LyricInterludeDotsAction.NONE, LyricInterludeDotsPolicy.actionFor(noPosition, 4))
        assertEquals(LyricInterludeDotsAction.NONE, LyricInterludeDotsPolicy.actionFor(3, noPosition))
        assertEquals(LyricInterludeDotsAction.NONE, LyricInterludeDotsPolicy.actionFor(noPosition, noPosition))
    }

    // ---- the fade itself -------------------------------------------------------------------

    @Test
    fun theFadeRunsFromFullToNothingAndEndsExactlyAtNothing() {
        val retire = LyricInterludeDotsPolicy.RETIRE_MS
        assertEquals(1f, LyricInterludeDotsPolicy.fadeAlpha(0L), 0f)
        assertEquals(0.5f, LyricInterludeDotsPolicy.fadeAlpha(retire / 2), 0.01f)
        assertEquals(0f, LyricInterludeDotsPolicy.fadeAlpha(retire), 0f)
        // Exactly zero, not 1e-7: the host never hides these views itself, so a remainder here is a
        // ghost on the screen for as long as the row is visible.
        assertEquals(0f, LyricInterludeDotsPolicy.fadeAlpha(retire + 5_000L), 0f)
    }

    @Test
    fun aZeroLengthFadeIsImmediateInsteadOfDividingByZero() {
        assertEquals(0f, LyricInterludeDotsPolicy.fadeAlpha(0L, 0L), 0f)
        assertFalse(LyricInterludeDotsPolicy.retiresBeforeArrival(450f, 0L))
    }

    @Test
    fun theDotsLeaveBeforeTheWaveArrives() {
        // LyricLineSpringPolicy.AMLL_SETTLE_MS is the wave's t99 (AMLL's own number). The whole
        // contract of this policy is "gone first", so the two constants are pinned together.
        assertTrue(LyricInterludeDotsPolicy.retiresBeforeArrival(LyricLineSpringPolicy.AMLL_SETTLE_MS))
        assertTrue(LyricInterludeDotsPolicy.RETIRE_MS < LyricLineSpringPolicy.AMLL_SETTLE_MS)
        // ... and it is not so short that the disappearance stops being visible.
        assertTrue(LyricInterludeDotsPolicy.RETIRE_MS >= 80L)
    }

    // ---- one row's state machine -----------------------------------------------------------

    @Test
    fun armShowsTheDotsAndRetireStartsTheFade() {
        val dots = LyricInterludeDots()
        assertEquals(LyricInterludeDotsAction.ARM, dots.arm(1_000L))
        assertEquals(LyricInterludeDotsPhase.SHOWING, dots.phase)
        assertEquals(1f, dots.alphaAt(1_000L), 0f)

        assertEquals(LyricInterludeDotsAction.RETIRE, dots.retire(2_000L))
        assertEquals(LyricInterludeDotsPhase.RETIRING, dots.phase)
        assertEquals(1f, dots.alphaAt(2_000L), 0f)
        assertEquals(0.5f, dots.alphaAt(2_000L + LyricInterludeDotsPolicy.RETIRE_MS / 2), 0.02f)
        assertEquals(0f, dots.alphaAt(2_000L + LyricInterludeDotsPolicy.RETIRE_MS), 0f)
        assertEquals(2_000L + LyricInterludeDotsPolicy.RETIRE_MS, dots.retireEndsAtMs())
    }

    @Test
    fun aSecondRetireDoesNotExtendTheFade() {
        val dots = LyricInterludeDots()
        dots.arm(0L)
        dots.retire(1_000L)
        val deadline = dots.retireEndsAtMs()
        // A second line change inside the same fade must not move the end point: extending it is
        // exactly how the dots end up on screen next to the incoming line.
        assertEquals(LyricInterludeDotsAction.NONE, dots.retire(1_050L))
        assertEquals(deadline, dots.retireEndsAtMs())
        assertEquals(0f, dots.alphaAt(deadline), 0f)
    }

    @Test
    fun armingDuringTheFadeBringsTheDotsBackImmediately() {
        val dots = LyricInterludeDots()
        dots.arm(0L)
        dots.retire(1_000L)
        assertEquals(0.5f, dots.alphaAt(1_000L + LyricInterludeDotsPolicy.RETIRE_MS / 2), 0.02f)
        // Seek back into the interlude, or two line changes inside one frame.
        assertEquals(LyricInterludeDotsAction.ARM, dots.arm(1_000L + LyricInterludeDotsPolicy.RETIRE_MS / 2))
        assertEquals(LyricInterludeDotsPhase.SHOWING, dots.phase)
        assertEquals(1f, dots.alphaAt(1_000L + LyricInterludeDotsPolicy.RETIRE_MS / 2), 0f)
        assertEquals(0L, dots.retireEndsAtMs())
    }

    @Test
    fun aRowNeverSeenBeforeStillFadesBecauseItsDotsAreOnScreen() {
        val dots = LyricInterludeDots()
        // A row bound before this track existed: its dots are at whatever the host set, i.e.
        // visible. Retiring must still fade them instead of assuming they are already gone.
        assertEquals(LyricInterludeDotsAction.RETIRE, dots.retire(500L))
        assertEquals(LyricInterludeDotsPhase.RETIRING, dots.phase)
        assertEquals(1f, dots.alphaAt(500L), 0f)
        assertEquals(0f, dots.alphaAt(500L + LyricInterludeDotsPolicy.RETIRE_MS), 0f)
    }

    @Test
    fun retiringAnAlreadyHiddenRowDoesNothing() {
        val dots = LyricInterludeDots()
        dots.arm(0L)
        dots.retire(100L)
        dots.settle(100L + LyricInterludeDotsPolicy.RETIRE_MS)
        assertEquals(LyricInterludeDotsPhase.HIDDEN, dots.phase)
        assertEquals(LyricInterludeDotsAction.NONE, dots.retire(9_000L))
        assertEquals(0L, dots.retireEndsAtMs())
    }

    @Test
    fun settleClosesTheFadeExactlyOnce() {
        val dots = LyricInterludeDots()
        dots.arm(0L)
        dots.retire(100L)
        val end = dots.retireEndsAtMs()
        assertFalse(dots.settle(end - 1))
        assertTrue(dots.settle(end))
        assertEquals(LyricInterludeDotsPhase.HIDDEN, dots.phase)
        assertFalse(dots.settle(end + 10_000L))
        assertEquals(0f, dots.alphaAt(end + 10_000L), 0f)
    }

    @Test
    fun retireImmediatelySkipsTheRestOfAFadeAlreadyInFlight() {
        val dots = LyricInterludeDots()
        dots.arm(0L)
        dots.retire(100L)
        assertEquals(LyricInterludeDotsAction.RETIRE, dots.retireImmediately())
        assertEquals(LyricInterludeDotsPhase.HIDDEN, dots.phase)
        assertEquals(0f, dots.alphaAt(150L), 0f)
        assertEquals(LyricInterludeDotsAction.NONE, dots.retireImmediately())
    }

    @Test
    fun repeatedInterludesSettleBackToTheSameStates() {
        val dots = LyricInterludeDots()
        var now = 0L
        repeat(5) {
            dots.arm(now)
            assertEquals(LyricInterludeDotsPhase.SHOWING, dots.phase)
            assertEquals(1f, dots.alphaAt(now), 0f)
            now += 4_000L
            dots.retire(now)
            assertEquals(LyricInterludeDotsPhase.RETIRING, dots.phase)
            now += LyricInterludeDotsPolicy.RETIRE_MS
            assertEquals(0f, dots.alphaAt(now), 0f)
            assertTrue(dots.settle(now))
            assertEquals(LyricInterludeDotsPhase.HIDDEN, dots.phase)
        }
    }

    // ---- the per-holder track --------------------------------------------------------------

    @Test
    fun oneLineChangeRetiresThePastInterludeAndArmsTheCurrentOne() {
        val track = LyricInterludeDotsTrack()
        val past = Any()
        val current = Any()
        val future = Any()
        val now = 10_000L

        val first = track.observe(
            listOf(
                LyricInterludeDotsRow(past, 3),
                LyricInterludeDotsRow(current, 4),
                LyricInterludeDotsRow(future, 5),
            ),
            currentLine = 4,
            nowMs = now,
        )
        assertEquals(
            listOf(LyricInterludeDotsAction.RETIRE, LyricInterludeDotsAction.ARM),
            first.map { it.action },
        )
        assertEquals(1f, first[0].alpha, 0f)
        assertEquals(1f, first[1].alpha, 0f)
        assertEquals(LyricInterludeDotsPhase.RETIRING, track.phaseOf(past))
        assertEquals(LyricInterludeDotsPhase.SHOWING, track.phaseOf(current))
        assertNull(track.phaseOf(future))
        // The past interlude is empty by the end of its fade — before the wave has moved the new
        // line in — and the row ahead of the current line was never touched at all.
        val done = track.observe(
            listOf(LyricInterludeDotsRow(past, 3), LyricInterludeDotsRow(current, 4)),
            currentLine = 4,
            nowMs = now + LyricInterludeDotsPolicy.RETIRE_MS,
        )
        assertEquals(listOf(LyricInterludeDotsAction.RETIRE), done.map { it.action })
        assertEquals(0f, done.single().alpha, 0f)
        assertEquals(LyricInterludeDotsPhase.HIDDEN, track.phaseOf(past))
    }

    @Test
    fun observingTheSameVerdictAgainReportsNothing() {
        val track = LyricInterludeDotsTrack()
        val row = Any()
        track.observe(listOf(LyricInterludeDotsRow(row, 9)), currentLine = 9, nowMs = 0L)
        val again = track.observe(listOf(LyricInterludeDotsRow(row, 9)), currentLine = 9, nowMs = 16L)
        assertTrue(again.isEmpty())
    }

    @Test
    fun aFadingRowReportedAgainMovesToHiddenWhenTheFadeIsDue() {
        val track = LyricInterludeDotsTrack()
        val row = Any()
        track.observe(listOf(LyricInterludeDotsRow(row, 1)), currentLine = 1, nowMs = 0L)
        val retiring = track.observe(listOf(LyricInterludeDotsRow(row, 1)), currentLine = 2, nowMs = 100L)
        assertEquals(LyricInterludeDotsAction.RETIRE, retiring.single().action)
        // Same verdict 16 ms later: still fading, nothing new to do on screen.
        assertTrue(track.observe(listOf(LyricInterludeDotsRow(row, 1)), currentLine = 2, nowMs = 116L).isEmpty())
        // After the fade's deadline the track closes it; the caller is told once.
        val closed = track.observe(
            listOf(LyricInterludeDotsRow(row, 1)),
            currentLine = 2,
            nowMs = 100L + LyricInterludeDotsPolicy.RETIRE_MS,
        )
        assertEquals(LyricInterludeDotsPhase.HIDDEN, closed.single().phase)
        assertEquals(LyricInterludeDotsAction.RETIRE, closed.single().action)
        assertEquals(0f, closed.single().alpha, 0f)
    }

    @Test
    fun seekingBackIntoAnInterludeShowsItsDotsAgain() {
        val track = LyricInterludeDotsTrack()
        val row = Any()
        track.observe(listOf(LyricInterludeDotsRow(row, 2)), currentLine = 2, nowMs = 0L)
        track.observe(listOf(LyricInterludeDotsRow(row, 2)), currentLine = 6, nowMs = 1_000L)
        assertEquals(LyricInterludeDotsPhase.RETIRING, track.phaseOf(row))
        // ... and it closes on its own deadline if nothing else looks at the row.
        track.observe(listOf(LyricInterludeDotsRow(row, 2)), currentLine = 6, nowMs = 1_000L + LyricInterludeDotsPolicy.RETIRE_MS)
        assertEquals(LyricInterludeDotsPhase.HIDDEN, track.phaseOf(row))
        val back = track.observe(listOf(LyricInterludeDotsRow(row, 2)), currentLine = 2, nowMs = 2_000L)
        assertEquals(LyricInterludeDotsAction.ARM, back.single().action)
        assertEquals(1f, back.single().alpha, 0f)
    }

    @Test
    fun aFreshBindArmsTheRowWithoutAskingWhereItSits() {
        val track = LyricInterludeDotsTrack()
        val row = Any()
        // currentLine is unknown (before the first line change of a song): a bind must still show.
        val armed = track.arm(row, 0L)
        assertEquals(LyricInterludeDotsAction.ARM, armed?.action)
        assertEquals(LyricInterludeDotsPhase.SHOWING, track.phaseOf(row))
        // Arming an already showing row is not a second change: nothing is written twice.
        assertNull(track.arm(row, 16L))
        // A recycled holder that comes back after a fade is shown again immediately.
        track.observe(listOf(LyricInterludeDotsRow(row, 3)), currentLine = 5, nowMs = 1_000L)
        assertEquals(LyricInterludeDotsPhase.RETIRING, track.phaseOf(row))
        val again = track.arm(row, 1_050L)
        assertEquals(LyricInterludeDotsAction.ARM, again?.action)
        assertEquals(1f, again?.alpha ?: -1f, 0f)
    }

    @Test
    fun theTrackCannotGrowWithPlayback() {
        val track = LyricInterludeDotsTrack(capacity = 4)
        repeat(50) { index ->
            track.observe(listOf(LyricInterludeDotsRow("row-" + index, index)), currentLine = index, nowMs = index.toLong())
        }
        assertEquals(4, track.size)
        assertNull(track.phaseOf("row-0"))
        assertEquals(LyricInterludeDotsPhase.SHOWING, track.phaseOf("row-49"))
    }

    @Test
    fun forgettingARowDropsOnlyThatRow() {
        val track = LyricInterludeDotsTrack()
        val first = Any()
        val second = Any()
        track.observe(
            listOf(LyricInterludeDotsRow(first, 1), LyricInterludeDotsRow(second, 2)),
            currentLine = 2,
            nowMs = 0L,
        )
        track.forget(first)
        assertNull(track.phaseOf(first))
        assertEquals(LyricInterludeDotsPhase.SHOWING, track.phaseOf(second))
        track.clear()
        assertEquals(0, track.size)
    }
}
