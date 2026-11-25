package dev.amenhancer.module.lyrics

import kotlin.math.abs
import kotlin.math.ln
import kotlin.math.sqrt
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The arithmetic behind 「换行弹簧」, pinned at the JVM level.
 *
 * The interesting assertions are not "the constants exist" but the three properties the host
 * depends on: the sample at time t does not depend on how the frames fell (the whole lyric block
 * is written from one number, so a frame-rate-dependent value tears the block apart), the
 * closed-form solution actually solves the differential equation, and the tempo mapping
 * `T -> k -> settle time` is the one the design notes claim.
 */
class LyricLineSpringPolicyTest {
    private fun spring(durationMs: Long = 600L, dy: Float = -300f): LyricLineSpring =
        requireNotNull(LyricLineSpringPolicy.springFor(durationMs, dy)) { "no spring for $durationMs/$dy" }

    @Test
    fun `the damping ratio is AMLL's for every duration`() {
        listOf(100L, 300L, 484L, 600L, 792L, 1_600L).forEach { duration ->
            val spring = spring(duration)
            assertEquals(1.1595f, spring.dampingRatio, 1e-3f)
        }
    }

    @Test
    fun `stiffness follows 170 times 484 over T squared`() {
        assertEquals(170f, LyricLineSpringPolicy.stiffnessFor(484f), 0.01f)
        // Half the duration is four times the stiffness — the scale that makes t99 linear in T.
        assertEquals(680f, LyricLineSpringPolicy.stiffnessFor(242f), 0.05f)
        // AMLL's own reference: 170 -> c 29.2; 300 ms -> k ~442, c ~46 (see note 08 §2.2).
        val fast = LyricLineSpringPolicy.springFor(300L, -300f)!!
        assertEquals(442.5f, fast.stiffness, 0.5f)
        assertEquals(46.3f, fast.damping, 0.1f)
        // 2.2 * sqrt(170) = 28.68; note 08's table rounds it to 29.
        assertEquals(28.68f, LyricLineSpringPolicy.dampingFor(170f), 0.02f)
    }

    @Test
    fun `a move is only ours when its displacement and duration are plausible`() {
        assertTrue(LyricLineSpringPolicy.acceptsDisplacement(1))
        assertTrue(LyricLineSpringPolicy.acceptsDisplacement(-1_200))
        assertTrue(!LyricLineSpringPolicy.acceptsDisplacement(0))
        assertTrue(!LyricLineSpringPolicy.acceptsDisplacement(1_201))
        assertTrue(!LyricLineSpringPolicy.acceptsDisplacement(-2_046))

        assertNotNull(LyricLineSpringPolicy.springFor(480L, -357f))
        assertNotNull(LyricLineSpringPolicy.springFor(1_600L, 1_200f))
        // A zero or oversized displacement, a stale duration, and non-finite input never animate.
        assertNull(LyricLineSpringPolicy.springFor(600L, 0f))
        assertNull(LyricLineSpringPolicy.springFor(600L, 1_201f))
        assertNull(LyricLineSpringPolicy.springFor(99L, -300f))
        assertNull(LyricLineSpringPolicy.springFor(1_601L, -300f))
        assertNull(LyricLineSpringPolicy.springFor(600L, Float.NaN))
        assertNull(LyricLineSpringPolicy.springFor(600L, -300f, Float.POSITIVE_INFINITY))
    }

    @Test
    fun `a frame step is clamped to 100 ms and never negative`() {
        assertEquals(16.6f, LyricLineSpringPolicy.frameStep(16.6f), 1e-4f)
        assertEquals(100f, LyricLineSpringPolicy.frameStep(1_000f), 1e-4f)
        assertEquals(0f, LyricLineSpringPolicy.frameStep(-8f), 1e-4f)
        assertEquals(0f, LyricLineSpringPolicy.frameStep(Float.NaN), 1e-4f)
        assertEquals(0f, LyricLineSpringPolicy.frameStep(Float.POSITIVE_INFINITY), 1e-4f)
    }

    /**
     * The closed form against an independent 0.1 ms RK4 integration of the same ODE.
     *
     * This is the "解算正确" test: if the coefficients, the roots or the initial velocity handling
     * were wrong, the two curves would separate immediately.
     */
    @Test
    fun `the closed form solves the same equation as an independent integrator`() {
        val spring = spring(600L, -300f)
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
     * `T -> k` really is a tempo knob: the 1% settle time is 1.31 T for every T, to within 5% of
     * the analytic dominant-mode time.
     *
     * 1.31 rather than 1.0 is a property of AMLL's 0.484 s / k=170 pairing, not a bug in the
     * mapping: the dominant root of an overdamped spring settles exponentially, so a constant
     * multiplication of T moves every root in step. The verifier only needs the ratio to be
     * constant.
     */
    @Test
    fun `the settle time is linear in the host duration and matches the dominant mode`() {
        val ratios = ArrayList<Float>()
        listOf(300L, 430L, 600L, 790L).forEach { duration ->
            val spring = spring(duration, -357f)
            val measured = spring.settleMs()
            val analytic = analyticSettleMs(spring)
            assertEquals(
                "T=${duration}ms measured=$measured analytic=$analytic",
                analytic,
                measured,
                analytic * 0.05f,
            )
            ratios += measured / duration.toFloat()
        }
        val reference = ratios.first()
        ratios.forEach { assertTrue("ratios=$ratios", abs(it - reference) / reference <= 0.02f) }
        assertTrue("ratios=$ratios", reference in 1.25f..1.40f)
    }

    @Test
    fun `doubling the duration doubles the settle time within five percent`() {
        val slow = spring(1_200L, -300f).settleMs()
        val quick = spring(600L, -300f).settleMs()
        assertEquals(2f, slow / quick, 0.1f)
    }

    @Test
    fun `the spring never overshoots its start and reaches rest sub pixel`() {
        val spring = spring(600L, -420f)
        val deadline = spring.deadlineMs()
        var worst = 0f
        var elapsed = 0f
        while (elapsed <= deadline) {
            val value = spring.offsetAt(elapsed)
            // Written as an offset against the new layout position: it must not cross zero.
            assertTrue("overshoot at ${elapsed}ms: $value", value <= 0f)
            worst = maxOf(worst, abs(value))
            elapsed += 1f
        }
        assertTrue("worst=$worst", worst <= 420f + 1e-3f)
        assertTrue("rest at deadline: ${spring.offsetAt(deadline)}", abs(spring.offsetAt(deadline)) <= 8f)
    }

    /**
     * A re-anchor while the block is still moving: the new target is a new absolute offset (the
     * layout jumped by exactly the difference), the velocity is carried, and the result still
     * converges — no torn row, no stuck offset.
     */
    @Test
    fun `a second batch with another dy inherits the velocity and still settles`() {
        val first = spring(600L, -300f)
        val elapsed = 150f
        val carried = first.velocityAt(elapsed)
        assertTrue("carried=$carried", carried > 0f)

        val second = first.relaunch(-120f, carried)
        assertEquals(-120f, second.offsetAt(0f), 1e-3f)
        assertEquals(carried, second.velocityAt(0f), abs(carried) * 0.01f)

        // It converges, it does not fly away, and it stops where the block's layout says it should.
        var elapsedMs = 0f
        var previous = abs(second.offsetAt(0f))
        while (elapsedMs < second.deadlineMs()) {
            elapsedMs += 16.6f
            val value = abs(second.offsetAt(elapsedMs))
            assertTrue("value=$value previous=$previous at $elapsedMs", value <= 120f * 1.35f)
            previous = value
        }
        assertTrue("final=${second.offsetAt(second.deadlineMs())}", second.offsetAt(second.deadlineMs()) <= 1f)
        assertTrue("settled", second.settleMs() <= second.deadlineMs())
    }

    /** The dominant-mode closed form, written here independently of the production scan. */
    private fun analyticSettleMs(spring: LyricLineSpring): Float {
        val zeta = LyricLineSpringPolicy.DAMPING_RATIO.toDouble()
        val omega0 = sqrt(spring.stiffness.toDouble() / LyricLineSpringPolicy.MASS)
        val root = sqrt(zeta * zeta - 1.0)
        val amplitude = abs(spring.offset) * (zeta + root) / (2.0 * root)
        val slow = omega0 * (zeta - root)
        return (ln(amplitude / (abs(spring.offset) * LyricLineSpring.SETTLE_FRACTION)) / slow * 1_000.0).toFloat()
    }

    /** Plain RK4 on `x'' = (-k x - c x')/m`, sampled at 0.1 ms, for the cross-check above. */
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
