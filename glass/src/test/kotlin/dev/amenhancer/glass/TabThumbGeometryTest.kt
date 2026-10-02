package dev.amenhancer.glass

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test

class TabThumbGeometryTest {
    private fun bounds(
        index: Float = 0f,
        isLtr: Boolean = true,
        scaleX: Float = 1f,
        scaleY: Float = 1f,
    ) = requireNotNull(TabThumbGeometry.bounds(508f, 100f, 36f, 4f, index, isLtr, scaleX, scaleY))

    @Test
    fun neutralMaskMatchesTheInsetAndThumbSize() {
        val mask = bounds()
        assertEquals(4f, mask.left, 0f)
        assertEquals(0f, mask.top, 0f)
        assertEquals(100f, mask.width, 0f)
        assertEquals(36f, mask.height, 0f)
        assertEquals(18f, mask.radiusX, 0f)
        assertEquals(18f, mask.radiusY, 0f)
        assertEquals(404f, bounds(index = 4f).left, 0f)
    }

    @Test
    fun draggingBetweenCellsMovesTheMaskContinuously() {
        assertEquals(54f, bounds(index = 0.5f).left, 0f)
        assertEquals(179f, bounds(index = 1.75f).left, 0f)
        assertEquals(-16f, bounds(index = -0.2f).left, 0.0001f)
        assertEquals(424f, bounds(index = 4.2f).left, 0.0001f)
    }

    @Test
    fun rtlMirrorsTheMaskWithoutChangingItsShape() {
        assertEquals(404f, bounds(isLtr = false).left, 0f)
        assertEquals(4f, bounds(index = 4f, isLtr = false).left, 0f)
        for (step in 0..40) {
            val index = step / 10f
            val ltr = bounds(index, true, 1.4f, 1.2f)
            val rtl = bounds(index, false, 1.4f, 1.2f)
            assertEquals(508f, ltr.left + rtl.left + ltr.width, 0.0001f)
            assertEquals(ltr.top, rtl.top, 0f)
            assertEquals(ltr.radiusX, rtl.radiusX, 0f)
        }
    }

    @Test
    fun pressBloomAndVelocityShearScaleAboutTheThumbCentre() {
        val mask = bounds(index = 2f, scaleX = 1.5f, scaleY = 1.25f)
        assertEquals(254f, mask.left + mask.width / 2f, 0f)
        assertEquals(18f, mask.top + mask.height / 2f, 0f)
        assertEquals(150f, mask.width, 0f)
        assertEquals(45f, mask.height, 0f)
        assertEquals(27f, mask.radiusX, 0f)
        assertEquals(22.5f, mask.radiusY, 0f)
    }

    @Test
    fun sharedScaleMathPreservesTheExistingThumbBloom() {
        for (pressedScale in listOf(1f, 1.2f, 78f / 56f)) {
            for (velocity in listOf(-30f, -1f, 0f, 1f, 30f)) {
                val normalizedVelocity = velocity / 10f
                assertEquals(
                    pressedScale / (1f - (normalizedVelocity * 0.75f).coerceIn(-0.2f, 0.2f)),
                    TabThumbGeometry.scaleX(pressedScale, velocity),
                    0f,
                )
                assertEquals(
                    pressedScale * (1f - (normalizedVelocity * 0.25f).coerceIn(-0.2f, 0.2f)),
                    TabThumbGeometry.scaleY(pressedScale, velocity),
                    0f,
                )
            }
        }
    }

    @Test
    fun narrowCellsUseTheSameCapsuleCornerAsTheThumb() {
        val mask = requireNotNull(TabThumbGeometry.bounds(100f, 20f, 36f, 4f, 0f, true, 1.2f, 1.4f))
        assertEquals(12f, mask.radiusX, 0f)
        assertEquals(14f, mask.radiusY, 0f)
    }

    @Test
    fun invalidLayoutOrAnimationValuesCannotProduceAnInvalidMask() {
        assertNull(TabThumbGeometry.bounds(0f, 100f, 36f, 4f, 0f, true, 1f, 1f))
        assertNull(TabThumbGeometry.bounds(508f, 0f, 36f, 4f, 0f, true, 1f, 1f))
        assertNull(TabThumbGeometry.bounds(508f, 100f, 0f, 4f, 0f, true, 1f, 1f))
        assertNull(TabThumbGeometry.bounds(508f, 100f, 36f, 4f, Float.NaN, true, 1f, 1f))
        assertNull(TabThumbGeometry.bounds(508f, 100f, 36f, 4f, 0f, true, Float.POSITIVE_INFINITY, 1f))
        assertNull(TabThumbGeometry.bounds(508f, 100f, 36f, 4f, 0f, true, 1f, -1f))
        assertNotNull(TabThumbGeometry.bounds(508f, 100f, 36f, 4f, 0f, true, 1f, 1f))
    }
}
