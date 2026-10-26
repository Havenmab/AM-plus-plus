package dev.amenhancer.glass

import dev.amenhancer.backdrop.TextureBlurKernel
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** Pure math of the experimental texture blur; no Android classes involved. */
class TextureBlurKernelTest {

    @Test
    fun `the sampling grid follows the radius`() {
        assertEquals(1, TextureBlurKernel.downscaleFor(0f))
        assertEquals(1, TextureBlurKernel.downscaleFor(8f))
        assertEquals(2, TextureBlurKernel.downscaleFor(12f))
        assertEquals(2, TextureBlurKernel.downscaleFor(30f))
        assertEquals(4, TextureBlurKernel.downscaleFor(48f))
        assertEquals(4, TextureBlurKernel.downscaleFor(120f))
    }

    @Test
    fun `weights always sum to one and the center keeps the largest share`() {
        listOf(2f, 8f, 16f, 32f, 64f, 120f).forEach { radius ->
            val taps = TextureBlurKernel.taps(radius)
            val sum = taps.weights.sum()
            assertEquals("radius=$radius", 1f, sum, 1e-4f)
            assertEquals(taps.pairCount + 1, taps.weights.size)
            // Pair weights fall off monotonically; the centre is a single tap, a pair is two.
            for (index in 2 until taps.weights.size) {
                assertTrue("pair weights fall off", taps.weights[index] <= taps.weights[index - 1] + 1e-6f)
            }
            taps.offsetsPx.forEachIndexed { index, offset ->
                assertTrue("offsets ascend", index == 0 || offset > taps.offsetsPx[index - 1])
            }
        }
    }

    @Test
    fun `a short kernel is cut where the weight stops mattering`() {
        val small = TextureBlurKernel.taps(2f)
        val large = TextureBlurKernel.taps(120f)
        assertTrue("small radius uses few taps", small.pairCount in 1..4)
        assertTrue("large radius uses the cap", large.pairCount in 1..TextureBlurKernel.MAX_PAIRS)
        assertTrue("pairs never exceed the cap", large.pairCount <= TextureBlurKernel.MAX_PAIRS)
    }

    @Test
    fun `a zero radius collapses to the center tap`() {
        val taps = TextureBlurKernel.taps(0f)
        assertEquals(0, taps.pairCount)
        assertEquals(1, taps.weights.size)
        assertEquals(1f, taps.weights[0], 0f)
    }

    @Test
    fun `a coarser grid shrinks the kernel in coarse pixels`() {
        val fine = TextureBlurKernel.taps(12f, downscale = 1)
        val coarse = TextureBlurKernel.taps(12f, downscale = 4)
        assertEquals(4, coarse.downscale)
        assertTrue("coarse grid needs fewer taps", coarse.pairCount < fine.pairCount)
    }
}