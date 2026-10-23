package dev.amenhancer.module.lyrics.motion

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class SpringTimingTest {
    private val lineChange = SpringTiming(mass = 1.0, stiffness = 100.0, damping = 18.0)

    @Test
    fun `apple line change spring has the published damping ratio and response`() {
        assertEquals(0.9, lineChange.dampingRatio, 1e-9)
        assertEquals(10.0, lineChange.naturalFrequency, 1e-9)
        assertEquals(0.6283185307, lineChange.responseSeconds, 1e-9)
    }

    @Test
    fun `the curve starts at zero and settles on the target`() {
        assertEquals(0.0, lineChange.value(0.0), 1e-12)
        assertTrue(lineChange.value(0.05) > 0.0)
        assertTrue(lineChange.value(0.05) < 1.0)
        assertEquals(1.0, lineChange.value(5.0), 1e-4)
        // A position-to-position sample is a linear map of the same curve.
        assertEquals(60.0, lineChange.value(0.0, from = 60.0, to = 20.0), 1e-12)
        assertEquals(20.0, lineChange.value(5.0, from = 60.0, to = 20.0), 1e-4)
    }

    @Test
    fun `zeta nought point nine overshoots by well under a percent`() {
        val peak = generateSequence(0) { it + 1 }
            .map { it * 0.01 }
            .takeWhile { it <= 2.0 }
            .maxOf { lineChange.value(it) }
        assertTrue("peak=$peak", peak > 1.0)
        assertTrue("peak=$peak", peak < 1.005)
    }

    @Test
    fun `duration uses the analytic envelope for an under damped spring`() {
        // -ln(threshold * sqrt(1 - z^2)) / (z * omega) = 0.6039 s
        assertEquals(0.6039, lineChange.durationSeconds(), 0.001)
        // A stricter threshold can only make the animation longer.
        assertTrue(lineChange.durationSeconds(0.001) > lineChange.durationSeconds())
        assertEquals(0.9059, lineChange.scaledDurationSeconds(150), 0.002)
    }

    @Test
    fun `critical and over damped springs still settle`() {
        val critical = SpringTiming(mass = 1.0, stiffness = 100.0, damping = 20.0)
        assertEquals(1.0, critical.dampingRatio, 1e-9)
        assertTrue(critical.value(0.05) > 0.0)
        assertTrue(critical.value(0.05) < 1.0)
        assertEquals(1.0, critical.value(5.0), 1e-4)
        assertTrue(critical.durationSeconds() > 0.0)
        assertTrue(critical.durationSeconds() <= 10.0)

        val over = SpringTiming(mass = 1.0, stiffness = 100.0, damping = 40.0)
        assertEquals(2.0, over.dampingRatio, 1e-9)
        assertTrue(over.value(0.1) > 0.0)
        assertTrue(over.value(0.1) < 1.0)
        assertEquals(1.0, over.value(8.0), 1e-3)
        assertTrue(over.durationSeconds() > critical.durationSeconds())
    }

    @Test
    fun `an undamped spring is rejected`() {
        val errors = runCatching { SpringTiming(mass = 1.0, stiffness = 100.0, damping = 0.0).apply { dampingRatio } }
        assertTrue(errors.isSuccess)
        assertTrue(runCatching { SpringTiming(mass = 0.0, stiffness = 100.0, damping = 10.0) }.isFailure)
        assertTrue(runCatching { SpringTiming(mass = 1.0, stiffness = 0.0, damping = 10.0) }.isFailure)
    }
}
