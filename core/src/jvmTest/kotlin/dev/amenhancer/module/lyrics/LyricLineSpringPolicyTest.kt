package dev.amenhancer.module.lyrics

import dev.amenhancer.module.model.LyricLineSpringFeel
import dev.amenhancer.module.model.ModuleSettings
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
 * The interesting assertions are not "the constants exist" but the properties the host depends on:
 * the sample at time t does not depend on how the frames fell (a row is written from one number, so
 * a frame-rate-dependent value tears the motion), the closed form actually solves the differential
 * equation it claims to, and each feel mode reaches the settle time its design notes promise.
 */
class LyricLineSpringPolicyTest {
    private fun host(durationMs: Long = 600L, dy: Float = -300f): LyricLineSpring =
        requireNotNull(
            LyricLineSpringPolicy.springFor(LyricLineSpringFeel.HOST, durationMs, dy),
        ) { "no spring for $durationMs/$dy" }

    private fun amll(dy: Float = -300f): LyricLineSpring =
        requireNotNull(LyricLineSpringPolicy.springFor(LyricLineSpringFeel.AMLL, 600L, dy))

    @Test
    fun `the damping ratio is AMLL's in both feels and for every duration`() {
        listOf(100L, 300L, 484L, 600L, 792L, 1_600L).forEach { duration ->
            assertEquals(1.1595f, host(duration).dampingRatio, 1e-3f)
            assertEquals(1.1595f, amll().dampingRatio, 1e-3f)
        }
    }

    @Test
    fun `stiffness follows 170 times 484 over T squared`() {
        assertEquals(170f, LyricLineSpringPolicy.stiffnessFor(484f), 0.01f)
        // Half the settle time is four times the stiffness — the scale that makes t99 linear in T.
        assertEquals(680f, LyricLineSpringPolicy.stiffnessFor(242f), 0.05f)
        // AMLL's own reference: 170 -> c 28.68; 300 ms -> k ~442, c ~46 (see note 08 §2.2).
        val fast = LyricLineSpringPolicy.springFor(LyricLineSpringFeel.HOST, 300L, -300f)!!
        assertEquals(442.5f, fast.stiffness, 0.5f)
        assertEquals(46.3f, fast.damping, 0.1f)
        assertEquals(28.68f, LyricLineSpringPolicy.dampingFor(170f), 0.02f)
    }

    /** The shipped default: AMLL's spring, 0.45 s, whatever Apple Music computed for the line. */
    @Test
    fun `the AMLL feel settles in 450 ms no matter what the host duration was`() {
        listOf(480L, 600L, 792L, 1_584L).forEach { duration ->
            val tempo = requireNotNull(
                LyricLineSpringPolicy.tempoFor(LyricLineSpringFeel.AMLL, duration),
            )
            assertEquals(450f, tempo.tempoMs, 0.01f)
            // k = 170 * (0.484 / 0.45)^2, c = 2.2 sqrt(k): the fast end of AMLL's normal mode.
            assertEquals(196.8f, tempo.stiffness, 0.5f)
            assertEquals(30.9f, tempo.damping, 0.1f)
            assertEquals(LyricLineSpringDecay.AMLL, tempo.decay)

            val settle = tempo.at(-357f).settleMs()
            assertTrue(
                "duration=$duration settle=$settle",
                abs(settle - LyricLineSpringPolicy.AMLL_SETTLE_MS) <=
                    LyricLineSpringPolicy.AMLL_SETTLE_MS * 0.05f,
            )
        }
    }

    /**
     * The HOST feel is 乙-1 unchanged: its 1 % settle time is 1.31 T for every T.
     *
     * 1.31 rather than 1.0 is the ratio between the exact over-damped solution and the critically
     * damped shape AMLL uses for the same parameters; it is constant, so the mapping is still a
     * tempo knob. The AMLL-feel test above is the other half of the same fact.
     */
    @Test
    fun `the HOST feel settles in 1 point 31 T and the ratio never drifts`() {
        val ratios = ArrayList<Float>()
        listOf(300L, 480L, 600L, 792L).forEach { duration ->
            val spring = host(duration, -357f)
            assertEquals(LyricLineSpringDecay.SOLUTION, spring.decay)
            val measured = spring.settleMs()
            val analytic = analyticSettleMs(spring)
            assertEquals(
                "T=${duration}ms measured=$measured analytic=$analytic",
                analytic,
                measured,
                analytic * 0.03f,
            )
            ratios += measured / duration.toFloat()
        }
        ratios.forEach { ratio ->
            assertTrue("ratios=$ratios", abs(ratio - 1.31f) <= 1.31f * 0.05f)
        }
    }

    @Test
    fun `doubling the duration doubles the HOST settle time within five percent`() {
        val slow = host(1_200L).settleMs()
        val quick = host(600L).settleMs()
        assertEquals(2f, slow / quick, 0.1f)
    }

    @Test
    fun `both feels are monotone and never overshoot the row's start`() {
        listOf(amll(-420f), host(600L, -420f)).forEach { spring ->
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
    }

    @Test
    fun `a move is only ours when its displacement and duration are plausible`() {
        assertTrue(LyricLineSpringPolicy.acceptsDisplacement(1))
        assertTrue(LyricLineSpringPolicy.acceptsDisplacement(-1_200))
        assertTrue(!LyricLineSpringPolicy.acceptsDisplacement(0))
        assertTrue(!LyricLineSpringPolicy.acceptsDisplacement(1_201))
        assertTrue(!LyricLineSpringPolicy.acceptsDisplacement(-2_046))

        listOf(LyricLineSpringFeel.AMLL, LyricLineSpringFeel.HOST).forEach { feel ->
            assertNotNull(LyricLineSpringPolicy.springFor(feel, 480L, -357f))
            assertNotNull(LyricLineSpringPolicy.springFor(feel, 1_600L, 1_200f))
            // A zero or oversized displacement, a stale duration and non-finite input never animate.
            assertNull(LyricLineSpringPolicy.springFor(feel, 600L, 0f))
            assertNull(LyricLineSpringPolicy.springFor(feel, 600L, 1_201f))
            assertNull(LyricLineSpringPolicy.springFor(feel, 99L, -300f))
            assertNull(LyricLineSpringPolicy.springFor(feel, 1_601L, -300f))
            assertNull(LyricLineSpringPolicy.springFor(feel, 600L, Float.NaN))
            assertNull(LyricLineSpringPolicy.springFor(feel, 600L, -300f, Float.POSITIVE_INFINITY))
            assertNull(LyricLineSpringPolicy.tempoFor(feel, 0L))
        }
    }

    @Test
    fun `the feel is only applied when both switches that own lyric effects are on`() {
        val off = ModuleSettings()
        assertNull(LyricLineSpringPolicy.appliedFeel(off))

        val on = off.copy(
            lyricLineSpringEnabled = true,
            lyricEffectsOptimizationEnabled = true,
        )
        assertEquals(LyricLineSpringFeel.AMLL, LyricLineSpringPolicy.appliedFeel(on))
        assertEquals(
            LyricLineSpringFeel.HOST,
            LyricLineSpringPolicy.appliedFeel(on.copy(lyricLineSpringFeel = LyricLineSpringFeel.HOST)),
        )
        // 「歌词效果优化」 owns every lyric effect, the spring included.
        assertNull(LyricLineSpringPolicy.appliedFeel(on.copy(lyricEffectsOptimizationEnabled = false)))
        assertNull(LyricLineSpringPolicy.appliedFeel(on.copy(lyricLineSpringEnabled = false)))
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
        assertTrue(!LyricLineSpringPolicy.isAtRest(0.5f, -31f))
        assertTrue(!LyricLineSpringPolicy.isAtRest(0.51f, 0f))
        // Sub-pixel but still travelling: a line change that landed while this row was moving.
        assertTrue(!LyricLineSpringPolicy.isAtRest(0.1f, 120f))
    }

    /**
     * The closed form against an independent 0.1 ms RK4 integration of the same ODE.
     *
     * This is the "解算正确" test, and it only holds for the exact solution — which is why the HOST
     * feel keeps it and AMLL's shape (documented as not solving the ODE) is checked by the ratio
     * test below instead.
     */
    @Test
    fun `the HOST closed form solves the same equation as an independent integrator`() {
        val spring = host(600L, -300f)
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
     * ratio documents why the default feel is faster than 乙-1 at an even higher stiffness.
     */
    @Test
    fun `the AMLL shape is the critically damped curve and not the exact solution`() {
        val amll = amll(-357f)
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
        val first = amll(-300f)
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

    /** The dominant-mode closed form, written here independently of the production scan. */
    private fun analyticSettleMs(spring: LyricLineSpring): Float {
        val zeta = LyricLineSpringPolicy.DAMPING_RATIO.toDouble()
        val omega0 = sqrt(spring.stiffness.toDouble() / LyricLineSpringPolicy.MASS)
        val root = sqrt(zeta * zeta - 1.0)
        val amplitude = abs(spring.offset) * (zeta + root) / (2.0 * root)
        val slow = omega0 * (zeta - root)
        val target = abs(spring.offset) * LyricLineSpring.SETTLE_FRACTION
        return (ln(amplitude / target) / slow * 1_000.0).toFloat()
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
