package dev.amenhancer.module.hook

import dev.amenhancer.module.config.RegionMetadataConfiguration
import dev.amenhancer.module.config.RegionSelection
import io.github.proify.lyricon.amprovider.xposed.MediaMetadataCache
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The runtime is installed unconditionally now, so "every control is off" has to look exactly
 * like "the runtime was never installed" to anything reading [MediaMetadataCache] — including
 * AppleMusicAssembly's 隐藏普通话拼音 genre probe, which reads the active profile.
 *
 * Behaviour test against the real cache object; no source text, no Android runtime.
 */
class MetadataCachePublishTest {
    private val sample = MediaMetadataCache.Metadata(
        id = "1440857782",
        title = "Song",
        artist = "Artist",
        genre = "Mandopop",
        duration = 0L,
        queueId = 0L,
    )

    @After
    fun restoreDefaultProfile() {
        MediaMetadataCache.setProfile("account")
        MediaMetadataCache.clearProfile()
    }

    @Test
    fun `an all-off configuration publishes nothing and leaves the account profile alone`() {
        MediaMetadataCache.setProfile("account")
        MediaMetadataCache.put(sample)

        val allOff = RegionMetadataConfiguration(
            region = RegionSelection.NONE,
            overrideAccountLanguage = false,
            restoreCjkOriginalMetadata = false,
            localizedMetadataCache = true,
        )
        assertFalse(MetadataCachePublish.isActive(allOff))
        assertFalse(MetadataCachePublish.activate(allOff))
        // Nothing was switched, nothing was cleared: the genre probe still sees what it saw
        // before the runtime existed.
        assertEquals("account", MediaMetadataCache.profile())
        assertEquals("Mandopop", MediaMetadataCache.getMetadataById(sample.id)?.genre)
    }

    @Test
    fun `the override switch alone never activates the runtime`() {
        // HLE: 歌曲信息替换至设定地区语言 is meaningless without a region.
        val overrideOnly = RegionMetadataConfiguration(
            region = RegionSelection.NONE,
            overrideAccountLanguage = true,
        )
        assertFalse(MetadataCachePublish.isActive(overrideOnly))
        assertFalse(MetadataCachePublish.activate(overrideOnly))
        assertEquals("account", MediaMetadataCache.profile())
    }

    @Test
    fun `any effective control publishes its region profile`() {
        MediaMetadataCache.setProfile("account")
        MediaMetadataCache.put(sample)

        RegionSelection.values().filter { it != RegionSelection.NONE }.forEach { region ->
            assertTrue(MetadataCachePublish.activate(RegionMetadataConfiguration(region = region)))
            assertEquals(region.cacheNamespace, MediaMetadataCache.profile())
        }

        // 不开启 + 原名还原 is the pure-CJK configuration: it has work to do, so it publishes its
        // own namespace (and therefore drops the account entries this test put there).
        val restoreOnly = RegionMetadataConfiguration(
            region = RegionSelection.NONE,
            restoreCjkOriginalMetadata = true,
        )
        assertTrue(MetadataCachePublish.activate(restoreOnly))
        assertEquals(RegionSelection.NONE.cacheNamespace, MediaMetadataCache.profile())
        assertNull(MediaMetadataCache.getMetadataById(sample.id))
    }

    @Test
    fun `the runtime keeps publishing while the region engine still owns the storefront`() {
        // Two configurations with the same region must not clear the cache between calls:
        // setProfile is a no-op when the profile is already active.
        val japan = RegionMetadataConfiguration(region = RegionSelection.JAPAN)
        assertTrue(MetadataCachePublish.activate(japan))
        MediaMetadataCache.put(sample)
        assertTrue(MetadataCachePublish.activate(japan.copy(restoreCjkOriginalMetadata = true)))
        assertNotNull(MediaMetadataCache.getMetadataById(sample.id))
        assertEquals(RegionSelection.JAPAN.cacheNamespace, MediaMetadataCache.profile())
    }
}
