package dev.amenhancer.module.config

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class RegionSelectionTest {
    @Test
    fun `regions map to their fixed storefront and language`() {
        assertEquals("cn", RegionSelection.MAINLAND_CHINA.catalogStorefront)
        assertEquals("zh-CN", RegionSelection.MAINLAND_CHINA.catalogLanguage)
        assertEquals("jp", RegionSelection.JAPAN.catalogStorefront)
        assertEquals("ja-JP", RegionSelection.JAPAN.catalogLanguage)
        assertNull(RegionSelection.NONE.catalogLanguage)
    }

    @Test
    fun `every region carries a storefront and a matching language tag`() {
        val expected = mapOf(
            RegionSelection.MAINLAND_CHINA to ("cn" to "zh-CN"),
            RegionSelection.ZH_HANS_US to ("us" to "zh-Hans"),
            RegionSelection.HONG_KONG to ("hk" to "zh-HK"),
            RegionSelection.TAIWAN to ("tw" to "zh-TW"),
            RegionSelection.KOREA to ("kr" to "ko-KR"),
            RegionSelection.JAPAN to ("jp" to "ja-JP"),
        )
        expected.forEach { (region, pair) ->
            assertEquals(pair.first, region.catalogStorefront)
            assertEquals(pair.second, region.catalogLanguage)
            assertTrue(region.replacesRegion)
        }
        // 不开启 must not claim to rewrite traffic.
        assertFalse(RegionSelection.NONE.replacesRegion)
        assertNull(RegionSelection.NONE.catalogStorefront)
    }

    @Test
    fun `every option uses HLE's exact picker label`() {
        // Mirrors HLE's option_apple_music_content_ui_language_* strings verbatim.
        assertEquals("不开启", RegionSelection.NONE.displayName)
        assertEquals("简体中文（中国）", RegionSelection.MAINLAND_CHINA.displayName)
        assertEquals("简体中文（美国）", RegionSelection.ZH_HANS_US.displayName)
        assertEquals("繁体中文（香港）", RegionSelection.HONG_KONG.displayName)
        assertEquals("繁体中文（台湾）", RegionSelection.TAIWAN.displayName)
        assertEquals("韩语（韩国）", RegionSelection.KOREA.displayName)
        assertEquals("日语（日本）", RegionSelection.JAPAN.displayName)
    }

    @Test
    fun `cache namespaces stay unique so regions never share cached names`() {
        val namespaces = RegionSelection.values().map(RegionSelection::cacheNamespace)
        assertEquals(namespaces.size, namespaces.distinct().size)
    }

    @Test
    fun `region storage values are unique and keep the existing region values`() {
        val storages = RegionSelection.values().map(RegionSelection::storageValue)
        assertEquals(storages.size, storages.distinct().size)
        // Changing a region's storage value would silently drop a user's selection.
        assertEquals("mainland_china", RegionSelection.MAINLAND_CHINA.storageValue)
        assertEquals("japan", RegionSelection.JAPAN.storageValue)
        assertEquals("none", RegionSelection.NONE.storageValue)
    }

    @Test
    fun `the no-region cache namespace keeps the persisted original-metadata database`() {
        assertEquals("original_hyper_v1", RegionSelection.NONE.cacheNamespace)
    }

    @Test
    fun `decode round trips every region and falls back to no region`() {
        RegionSelection.values().forEach { region ->
            assertEquals(region, RegionSelection.decode(region.storageValue))
        }
        assertEquals(RegionSelection.NONE, RegionSelection.decode(null))
        assertEquals(RegionSelection.NONE, RegionSelection.decode("  "))
        assertEquals(RegionSelection.NONE, RegionSelection.decode("unknown"))
    }

    @Test
    fun `the retired picker value maps onto the region control`() {
        // 不开启 used to be stored as "original_hyper" on the single picker.
        assertEquals(
            RegionSelection.NONE,
            RegionSelection.fromLegacyTitleCorrectionMode("original_hyper"),
        )
        assertEquals(
            RegionSelection.NONE,
            RegionSelection.fromLegacyTitleCorrectionMode(null),
        )
        RegionSelection.values().forEach { region ->
            assertEquals(region, RegionSelection.fromLegacyTitleCorrectionMode(region.storageValue))
        }
    }

    @Test
    fun `legacy target language migration only recognizes mainland and japan`() {
        assertEquals(
            RegionSelection.MAINLAND_CHINA,
            RegionSelection.fromLegacyTargetLanguage("zh_cn"),
        )
        assertEquals(
            RegionSelection.JAPAN,
            RegionSelection.fromLegacyTargetLanguage("ja-JP"),
        )
        assertEquals(
            RegionSelection.NONE,
            RegionSelection.fromLegacyTargetLanguage("ko-KR"),
        )
    }
}
