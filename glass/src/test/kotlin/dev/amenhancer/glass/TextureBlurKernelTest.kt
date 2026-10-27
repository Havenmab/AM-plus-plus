package dev.amenhancer.glass

import dev.amenhancer.backdrop.TextureBlurKernel
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** Pure math of the experimental texture blur; no Android classes involved. */
class TextureBlurKernelTest {

    @Test
    fun `the tap spacing grows with the radius so three sigma still fits`() {
        assertEquals(1, TextureBlurKernel.stepFor(0f))
        assertEquals(1, TextureBlurKernel.stepFor(4f))
        assertTrue("8px widens the sampling", TextureBlurKernel.stepFor(8f) > 1)
        assertTrue("60px widens it further", TextureBlurKernel.stepFor(60f) > TextureBlurKernel.stepFor(8f))
        assertTrue("spacing stays bounded", TextureBlurKernel.stepFor(400f) <= 64)
    }

    @Test
    fun `the kernel reaches about three sigma inside the tap budget`() {
        listOf(2f, 8f, 16f, 32f, 60f, 120f).forEach { radius ->
            val taps = TextureBlurKernel.taps(radius)
            val sigma = radius * TextureBlurKernel.RADIUS_TO_SIGMA
            val furthest = taps.offsetsPx.lastOrNull() ?: 0f
            assertTrue("radius=$radius reach", furthest >= sigma * 2f)
            assertTrue("radius=$radius budget", taps.pairCount <= TextureBlurKernel.MAX_PAIRS)
        }
    }

    @Test
    fun `weights always sum to one and fall off monotonically`() {
        listOf(2f, 8f, 16f, 32f, 64f, 120f).forEach { radius ->
            val taps = TextureBlurKernel.taps(radius)
            assertEquals("radius=$radius", 1f, taps.weights.sum(), 1e-4f)
            assertEquals(taps.pairCount + 1, taps.weights.size)
            for (index in 2 until taps.weights.size) {
                assertTrue("pair weights fall off", taps.weights[index] <= taps.weights[index - 1] + 1e-6f)
            }
            taps.offsetsPx.forEachIndexed { index, offset ->
                assertTrue("offsets ascend", index == 0 || offset > taps.offsetsPx[index - 1])
            }
        }
    }

    @Test
    fun `a zero radius collapses to the center tap`() {
        val taps = TextureBlurKernel.taps(0f)
        assertEquals(0, taps.pairCount)
        assertEquals(1, taps.weights.size)
        assertEquals(1f, taps.weights[0], 0f)
    }

    @Test
    fun `a wider spacing needs fewer taps for the same radius`() {
        val fine = TextureBlurKernel.taps(60f, stepPx = 1)
        val wide = TextureBlurKernel.taps(60f, stepPx = 8)
        assertEquals(8, wide.stepPx)
        assertTrue("wide spacing uses fewer taps", wide.pairCount <= fine.pairCount)
    }
}