package dev.amenhancer.module.lyrics

import dev.amenhancer.module.model.ModuleSettings
import kotlin.math.abs
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The arithmetic behind 「换行弹簧位移」, pinned at the JVM level.
 *
 * The interesting assertions are not "the constants exist" but the properties the host depends on:
 * the sample at time t does not depend on how the frames fell (a row is written from one number, so
 * a frame-rate-dependent value tears the motion), the spring reaches the settle time the design
 * notes promise, AMLL's curve is not the ODE's solution, and the closed form really does solve the
 * equation it claims to.
 */
class LyricLineSpringPolicyTest {
    private fun spring(dy: Float = -300f): LyricLineSpring =
        requireNotNull(LyricLineSpringPolicy.springFor(dy)) { "no spring for $dy" }

    @Test
    fun `the damping ratio is AMLL's, whatever the tempo`() {
        listOf(100f, 300f, 484f, 600f, 792f, 1_600f).forEach { tempo ->
            val stiffness = LyricLineSpringPolicy.stiffnessFor(tempo)
            val damping = LyricLineSpringPolicy.dampingFor(stiffness)
            val spring = LyricLineSpring(stiffness, damping, -300f, 0f)
            assertEquals(tempo.toString(), 1.1595f, spring.dampingRatio, 1e-3f)
        }
        assertEquals(1.1595f, spring().dampingRatio, 1e-3f)
    }

    @Test
    fun `stiffness follows 170 times 484 over T squared`() {
        assertEquals(170f, LyricLineSpringPolicy.stiffnessFor(484f), 0.01f)
        // Half the settle time is four times the stiffness — the scale that makes t99 linear in T.
        assertEquals(680f, LyricLineSpringPolicy.stiffnessFor(242f), 0.05f)
        assertEquals(28.68f, LyricLineSpringPolicy.dampingFor(170f), 0.02f)
    }

    /**
     * The shipped spring: AMLL's own, 450 ms, k = 170 (0.484/0.45)^2 = 196.7, c = 2.2 sqrt(k) = 30.9.
     *
     * The tempo is a constant — nothing a host value could change — which is what makes the feature
     * a single switch.
     */
    @Test
    fun `the one tempo is AMLL's 450 ms spring`() {
        val tempo = LyricLineSpringPolicy.tempo
        assertEquals(450f, tempo.tempoMs, 0.01f)
        assertEquals(196.7f, tempo.stiffness, 0.5f)
        assertEquals(30.9f, tempo.damping, 0.1f)
        assertEquals(LyricLineSpringDecay.AMLL, tempo.decay)

        val settle = tempo.at(-357f).settleMs()
        assertTrue(
            "settle=$settle",
            abs(settle - LyricLineSpringPolicy.AMLL_SETTLE_MS) <=
                LyricLineSpringPolicy.AMLL_SETTLE_MS * 0.05f,
        )
    }

    @Test
    fun `the spring is monotone and never overshoots the row's start`() {
        val spring = spring(-420f)
        var elapsed = 0f
        var previous = abs(spring.offsetAt(0f))
        while (elapsed <= spring.deadlineMs()) {
            val value = spring.offsetAt(elapsed)
            // Written as an offset against the layout position: it must not cross zero.
            assertTrue("overshoot at ${elapsed}ms: $value", value <= 0f)
            val magnitude = abs(value)
            assertTrue("grows at ${elapsed}ms: $value", magnitude <= previous + 1e-3f)
            previous = magnitude
            elapsed += 1f
        }
        assertTrue(
            "rest at deadline: ${spring.offsetAt(spring.deadlineMs())}",
            abs(spring.offsetAt(spring.deadlineMs())) <= 8f,
        )
    }

    @Test
    fun `a move is only ours when its displacement is plausible`() {
        assertTrue(LyricLineSpringPolicy.acceptsDisplacement(1))
        assertTrue(LyricLineSpringPolicy.acceptsDisplacement(-1_200))
        assertFalse(LyricLineSpringPolicy.acceptsDisplacement(0))
        assertFalse(LyricLineSpringPolicy.acceptsDisplacement(1_201))
        assertFalse(LyricLineSpringPolicy.acceptsDisplacement(-2_046))

        assertNotNull(LyricLineSpringPolicy.springFor(-357f))
        assertNotNull(LyricLineSpringPolicy.springFor(1_200f))
        // A zero or oversized displacement, or non-finite input, never animates.
        assertNull(LyricLineSpringPolicy.springFor(0f))
        assertNull(LyricLineSpringPolicy.springFor(1_201f))
        assertNull(LyricLineSpringPolicy.springFor(Float.NaN))
        assertNull(LyricLineSpringPolicy.springFor(-300f, Float.POSITIVE_INFINITY))
    }

    @Test
    fun `the spring is only applied when both switches that own lyric effects are on`() {
        val off = ModuleSettings()
        assertFalse(LyricLineSpringPolicy.isEnabled(off))
        assertFalse(
            LyricLineSpringPolicy.isEnabled(
                off.copy(lyricLineSpringEnabled = true, lyricEffectsOptimizationEnabled = false),
            ),
        )
        assertFalse(
            LyricLineSpringPolicy.isEnabled(
                off.copy(lyricLineSpringEnabled = false, lyricEffectsOptimizationEnabled = true),
            ),
        )
        // 「歌词效果优化」 owns every lyric effect, the spring included.
        assertTrue(
            LyricLineSpringPolicy.isEnabled(
                off.copy(lyricLineSpringEnabled = true, lyricEffectsOptimizationEnabled = true),
            ),
        )
    }

    @Test
    fun `a frame step is clamped to 100 ms and never negative`() {
        assertEquals(16.6f, LyricLineSpringPolicy.frameStep(16.6f), 1e-4f)
        assertEquals(100f, LyricLineSpringPolicy.frameStep(1_000f), 1e-4f)
        assertEquals(0f, LyricLineSpringPolicy.frameStep(-8f), 1e-4f)
        assertEquals(0f, LyricLineSpringPolicy.frameStep(Float.NaN), 1e-4f)
        assertEquals(0f, LyricLineSpringPolicy.frameStep(Float.POSITIVE_INFINITY), 1e-4f)
    }

    @Test
    fun `a row is at rest only when it is both sub pixel and slow`() {
        assertTrue(LyricLineSpringPolicy.isAtRest(0f, 0f))
        // One sub-pixel per 60 Hz frame: 0.5 px / (1/60 s) ~ 30 px/s.
        assertEquals(30f, LyricLineSpringPolicy.SETTLE_EPSILON_VELOCITY, 0.01f)
        assertTrue(LyricLineSpringPolicy.isAtRest(0.5f, -29f))
        assertFalse(LyricLineSpringPolicy.isAtRest(0.5f, -31f))
        assertFalse(LyricLineSpringPolicy.isAtRest(0.51f, 0f))
        // Sub-pixel but still travelling: a line change that landed while this row was moving.
        assertFalse(LyricLineSpringPolicy.isAtRest(0.1f, 120f))
    }

    /**
     * The closed form against an independent 0.1 ms RK4 integration of the same ODE.
     *
     * This is the "解算正确" test. It needs the exact solution, which is why [LyricLineSpringDecay]
     * still carries it — as the reference curve the shipped AMLL shape is measured against, never
     * as something the hook selects.
     */
    @Test
    fun `the exact closed form solves the same equation as an independent integrator`() {
        val tempo = LyricLineSpringPolicy.tempo
        val spring = LyricLineSpring(
            tempo.stiffness,
            tempo.damping,
            -300f,
            0f,
            LyricLineSpringDecay.SOLUTION,
        )
        val samples = listOf(0f, 25f, 50f, 100f, 200f, 400f, 800f, 1_200f)
        val reference = integrate(spring, 1_200f)
        samples.forEach { t ->
            val closed = spring.offsetAt(t)
            val numeric = reference(t)
            val drift = abs(closed - numeric)
            assertTrue(
                "t=${t}ms closed=$closed numeric=$numeric",
                drift <= abs(spring.offset) * 0.01f,
            )
        }
    }

    /**
     * AMLL's shape is not the ODE solution: it is the critically damped curve at omega0, which
     * decays as (1 + u) e^-u and therefore arrives 1.31x sooner at the same stiffness. Pinning the
     * ratio documents why the shipped spring is faster than the exact solution at an equal k — it
     * is the same 1.31 the old HOST-timed feel used to measure against Apple Music's own duration.
     */
    @Test
    fun `the shipped shape is the critically damped curve and not the exact solution`() {
        val amll = spring(-357f)
        val exact = LyricLineSpring(
            amll.stiffness,
            amll.damping,
            -357f,
            0f,
            LyricLineSpringDecay.SOLUTION,
        )
        val ratio = amll.settleMs() / exact.settleMs()
        assertTrue("ratio=$ratio", abs(ratio - 1f / 1.31f) <= 0.02f)
        // And it still starts at rest and decays monotonically into the target.
        assertEquals(-357f, amll.offsetAt(0f), 1e-3f)
        assertEquals(0f, amll.velocityAt(0f), 1e-3f)
        assertTrue(abs(amll.offsetAt(amll.settleMs() * 0.5f)) < 357f * 0.5f)
    }

    /**
     * A re-anchor while a row is still moving: the new target is a new absolute offset (the layout
     * jumped by exactly the difference), the velocity is carried, and the result still converges.
     */
    @Test
    fun `a re-target inherits the velocity and still settles`() {
        val first = spring(-300f)
        val elapsed = 100f
        val carried = first.velocityAt(elapsed)
        assertTrue("carried=$carried", carried > 0f)

        val second = first.relaunch(-120f, carried)
        assertEquals(-120f, second.offsetAt(0f), 1e-3f)
        assertEquals(carried, second.velocityAt(0f), abs(carried) * 0.01f)

        var elapsedMs = 0f
        while (elapsedMs < second.deadlineMs()) {
            elapsedMs += 16.6f
            val value = abs(second.offsetAt(elapsedMs))
            assertTrue("value=$value at $elapsedMs", value <= 120f * 1.35f)
        }
        assertTrue(
            "final=${second.offsetAt(second.deadlineMs())}",
            second.offsetAt(second.deadlineMs()) <= 1f,
        )
        assertTrue("settled", second.settleMs() <= second.deadlineMs())
    }

    /**
     * The compensation gates: what one per-frame layout shift must look like to be a growing line.
     *
     * 1626 measured a growing background line at 4-6 px per frame and the host's *own* movement of
     * the block at 33/102/81/64 px on four consecutive frames. The bound sits between the two, and
     * one line change may not take more than a background line's worth out of a row's travel — past
     * that the row is being held still while the layout runs away, which is the reported "this line
     * does not move, and then it jumps straight to the one after next".
     */
    @Test
    fun `only a background line's per frame growth is compensable`() {
        assertTrue(LyricLineSpringPolicy.acceptsLayoutShift(4))
        assertTrue(LyricLineSpringPolicy.acceptsLayoutShift(-6))
        assertTrue(LyricLineSpringPolicy.acceptsLayoutShift(LyricLineSpringPolicy.MAX_COMPENSABLE_SHIFT_PX))
        assertFalse(LyricLineSpringPolicy.acceptsLayoutShift(0))
        listOf(-33, -102, -81, -64, -10).forEach {
            assertFalse(
                "compensated the host's own " + it + "px frame",
                LyricLineSpringPolicy.acceptsLayoutShift(it),
            )
        }
        assertEquals(8, LyricLineSpringPolicy.MAX_COMPENSABLE_SHIFT_PX)

        assertTrue(LyricLineSpringPolicy.acceptsCompensation(0f, 8))
        assertTrue(LyricLineSpringPolicy.acceptsCompensation(88f, 8))
        assertFalse(LyricLineSpringPolicy.acceptsCompensation(92f, 8))
        assertEquals(96, LyricLineSpringPolicy.MAX_COMPENSATION_PX)
        // One background line is tens of pixels and one line change is about 120.
        assertTrue(LyricLineSpringPolicy.MAX_COMPENSATION_PX < 120)
    }

    /** Plain RK4 on x'' = (-k x - c x') / m, sampled at 0.1 ms, for the cross-check above. */
    private fun integrate(spring: LyricLineSpring, untilMs: Float): (Float) -> Float {
        val stepSeconds = 0.0001
        val mass = LyricLineSpringPolicy.MASS.toDouble()
        val k = spring.stiffness.toDouble()
        val c = spring.damping.toDouble()
        val samples = Math.round(untilMs / 0.1f).toInt()
        val values = FloatArray(samples + 1)
        var x = spring.offset.toDouble()
        var v = spring.velocity.toDouble()
        values[0] = x.toFloat()
        fun acceleration(position: Double, velocity: Double) = (-k * position - c * velocity) / mass
        for (index in 1..samples) {
            val k1x = v
            val k1v = acceleration(x, v)
            val k2x = v + 0.5 * stepSeconds * k1v
            val k2v = acceleration(x + 0.5 * stepSeconds * k1x, v + 0.5 * stepSeconds * k1v)
            val k3x = v + 0.5 * stepSeconds * k2v
            val k3v = acceleration(x + 0.5 * stepSeconds * k2x, v + 0.5 * stepSeconds * k2v)
            val k4x = v + stepSeconds * k3v
            val k4v = acceleration(x + stepSeconds * k3x, v + stepSeconds * k3v)
            x += stepSeconds / 6.0 * (k1x + 2 * k2x + 2 * k3x + k4x)
            v += stepSeconds / 6.0 * (k1v + 2 * k2v + 2 * k3v + k4v)
            values[index] = x.toFloat()
        }
        return { t -> values[(Math.round(t / 0.1f).toInt()).coerceIn(0, samples)] }
    }
}
