package dev.amenhancer.module.hook

import androidx.fragment.app.TabletChromeMenuFragmentFixture
import dev.amenhancer.module.ModuleConstants
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class TabletChromeSongMenuSymbolsTest {
    @Test
    fun verified653CurrentPaneAccessorResolvesWithoutInventoryScan() {
        val resolver = resolver("6.5.3", 1599L, VerifiedTabletMenuControllerFixture::class.java)
        val resolution = resolver.resolve(AppleMusicSymbols.TabletPlayerControllerCurrentFragment)
        assertTrue(resolution is TargetResolution.Found)
        resolution as TargetResolution.Found
        assertEquals("z1", resolution.value.name)
        assertEquals(SymbolMatch.VERSION_PROFILE, resolution.match)
        assertEquals("apple-music-6.5.3-1599", resolution.profileId)
    }

    @Test
    fun stale653AccessorDoesNotBindAnotherNoArgumentMethod() {
        val resolver = resolver("6.5.3", 1599L, StaleTabletMenuControllerFixture::class.java)
        assertTrue(resolver.resolve(AppleMusicSymbols.TabletPlayerControllerCurrentFragment) is TargetResolution.Missing)
    }

    @Test
    fun incompatible653ReturnTypeIsRejected() {
        val resolver = resolver("6.5.3", 1599L, IncompatibleTabletMenuControllerFixture::class.java)
        assertTrue(resolver.resolve(AppleMusicSymbols.TabletPlayerControllerCurrentFragment) is TargetResolution.Missing)
    }

    @Test
    fun otherVersionsDoNotReuseThe653PaneAccessor() {
        for ((version, code) in listOf("6.5.2" to 1586L, "6.5.3" to 1600L, "" to -1L)) {
            val resolver = resolver(version, code, VerifiedTabletMenuControllerFixture::class.java)
            assertTrue(resolver.resolve(AppleMusicSymbols.TabletPlayerControllerCurrentFragment) is TargetResolution.Missing)
        }
    }

    private fun resolver(version: String, code: Long, controllerType: Class<*>): IndexedTargetSymbolResolver =
        IndexedTargetSymbolResolver(
            TargetBuild(ModuleConstants.TARGET_PACKAGE, version, code),
            object : TargetClassSource {
                override fun classNames(): List<String> = emptyList()

                override fun loadClass(name: String): Class<*>? =
                    if (name == "com.apple.android.music.player.fragment.v0" ||
                        name == "com.apple.android.music.player.fragment.t0"
                    ) controllerType else null
            },
        )
}

private class VerifiedTabletMenuControllerFixture {
    fun z1(): TabletChromeMenuFragmentFixture? = null
}

private class StaleTabletMenuControllerFixture {
    fun other(): TabletChromeMenuFragmentFixture? = null
}

private class IncompatibleTabletMenuControllerFixture {
    fun z1(): Any? = null
}
