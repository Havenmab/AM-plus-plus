package dev.amenhancer.module.hook

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class AppleMusicTabletChromeTargetTest {
    @Test
    fun `missing controller capture keeps the target unavailable and every command inert`() {
        val target = AppleMusicTabletChromeTarget(MissingSymbolResolver, TargetBuild.UNKNOWN)

        val installed = target.install()

        assertTrue(installed is TargetCapabilityInstall.Degraded)
        assertFalse(target.available)
        assertFalse(target.shuffleAvailable)
        assertFalse(target.repeatAvailable)
        assertFalse(target.moreAvailable)
        assertEquals(0, target.repeatMode())
        assertFalse(target.isPlaying())
        assertFalse(target.shuffleEnabled())
        assertNull(target.currentTitle())
        assertNull(target.currentArtist())
        assertTrue(target.resolutionSummary.contains("missing="))
        assertTrue(target.resolutionSummary.contains("controller=pending"))

        // None of these may throw into the Compose/render path without a captured controller.
        target.play()
        target.pause()
        target.skipToNext()
        target.skipToPrevious()
        target.setShuffleEnabled(true)
        target.setShuffleEnabled(false)
        target.cycleRepeatMode()
        target.addListener { }.close()
    }

    @Test
    fun `repeat mode cycles off to all to one to off`() {
        assertEquals(2, nextTabletRepeatMode(0))
        assertEquals(1, nextTabletRepeatMode(2))
        assertEquals(0, nextTabletRepeatMode(1))
    }

    @Test
    fun `unknown repeat modes restart at all`() {
        assertEquals(2, nextTabletRepeatMode(-1))
        assertEquals(2, nextTabletRepeatMode(99))
    }
}

private object MissingSymbolResolver : TargetSymbolResolver {
    override fun <T : Any> resolve(symbol: TargetSymbolKey<T>): TargetResolution<T> =
        TargetResolution.Missing(symbol.id, null)
}
