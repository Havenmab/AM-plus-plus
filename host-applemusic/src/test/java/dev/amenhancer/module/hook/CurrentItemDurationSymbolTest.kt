package dev.amenhancer.module.hook

import com.apple.android.music.model.BasePlaybackItem
import com.apple.android.music.player.fragment.PlayerLyricsViewFragment
import com.apple.android.music.player.fragment.e
import com.apple.android.music.player.fragment.l
import dev.amenhancer.module.CurrentSongDetails
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The 7.0 duration accessor moved from the declared `BaseContentItem` field type
 * onto the concrete `BasePlaybackItem` hierarchy. `Class#getMethod` can only
 * see supertypes, so the verified profile identity is what makes the duration
 * available; these tests pin both the profile resolution and the seam wiring.
 */
class CurrentItemDurationSymbolTest {
    private val build700 = TargetBuild("com.apple.android.music", "7.0.0-beta", 1606)
    private val build650 = TargetBuild("com.apple.android.music", "6.5.0", 1580)
    private val classes = mapOf(
        PlayerLyricsViewFragment::class.java.name to PlayerLyricsViewFragment::class.java,
        e::class.java.name to e::class.java,
        l::class.java.name to l::class.java,
        BasePlaybackItem::class.java.name to BasePlaybackItem::class.java,
    )

    private fun source(values: Map<String, Class<*>> = classes) = object : TargetClassSource {
        override fun classNames() = values.keys.toList()
        override fun loadClass(name: String) = values[name]
    }

    @Test
    fun `1606 resolves the pinned playback duration accessor exactly`() {
        val symbols = IndexedTargetSymbolResolver(build700, source())
        val resolved = symbols.resolve(AppleMusicSymbols.CurrentItemDurationMethod)

        assertTrue(resolved is TargetResolution.Found)
        val found = resolved as TargetResolution.Found
        assertEquals(SymbolMatch.VERSION_PROFILE, found.match)
        assertEquals("getPlaybackDuration", found.value.name)
        assertEquals(BasePlaybackItem::class.java, found.value.declaringClass)
        assertEquals(0, found.value.parameterCount)
        assertEquals(Long::class.javaPrimitiveType, found.value.returnType)
    }

    @Test
    fun `the seam reads the concrete duration on 1606 without reporting it missing`() {
        val symbols = IndexedTargetSymbolResolver(build700, source())
        val install = symbols.resolve(AppleMusicSymbols.LyricsInstallMethod).valueOrNull()!!
        val seam = CurrentItemIdentitySeam(symbols)
        assertNull(seam.resolve(install))

        assertNotNull(seam.durationSummary)
        assertTrue(seam.durationSummary!!.contains("BasePlaybackItem"))
        assertTrue(seam.metadataSummary?.contains("duration") != true)

        val item = BasePlaybackItem("67890", "Song", "Artist", 215_000L)
        assertEquals(
            CurrentSongDetails(67890L, "Song", "Artist", 215_000L),
            seam.detailsOfItem(item),
        )
        assertEquals(67890L, seam.currentItemAdamIdOf(PlayerLyricsViewFragment().also { it.c = item }))
    }

    @Test
    fun `a build without the pinned contract still resolves the reviewed accessor name`() {
        val symbols = IndexedTargetSymbolResolver(build650, source())
        val resolved = symbols.resolve(AppleMusicSymbols.CurrentItemDurationMethod)

        assertTrue(resolved is TargetResolution.Found)
        val found = resolved as TargetResolution.Found
        assertEquals(SymbolMatch.STRUCTURAL_FALLBACK, found.match)
        assertEquals("getPlaybackDuration", found.value.name)
    }
}
