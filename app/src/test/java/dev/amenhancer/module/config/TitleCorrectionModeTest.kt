package dev.amenhancer.module.config

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class TitleCorrectionModeTest {
    @Test
    fun `profiles map to their fixed storefront and language`() {
        assertEquals("cn", TitleCorrectionMode.MAINLAND_CHINA.catalogStorefront)
        assertEquals("zh-CN", TitleCorrectionMode.MAINLAND_CHINA.catalogLanguage)
        assertEquals("jp", TitleCorrectionMode.JAPAN.catalogStorefront)
        assertEquals("ja-JP", TitleCorrectionMode.JAPAN.catalogLanguage)
        assertNull(TitleCorrectionMode.ORIGINAL_HYPER.catalogLanguage)
    }

    @Test
    fun `every region profile carries a storefront and a matching language tag`() {
        val expected = mapOf(
            TitleCorrectionMode.MAINLAND_CHINA to ("cn" to "zh-CN"),
            TitleCorrectionMode.ZH_HANS_US to ("us" to "zh-Hans"),
            TitleCorrectionMode.HONG_KONG to ("hk" to "zh-HK"),
            TitleCorrectionMode.TAIWAN to ("tw" to "zh-TW"),
            TitleCorrectionMode.KOREA to ("kr" to "ko-KR"),
            TitleCorrectionMode.JAPAN to ("jp" to "ja-JP"),
        )
        expected.forEach { (mode, pair) ->
            assertEquals(pair.first, mode.catalogStorefront)
            assertEquals(pair.second, mode.catalogLanguage)
            assertTrue(mode.replacesRegion)
        }
        // The "no region" profile must not claim to rewrite traffic.
        assertFalse(TitleCorrectionMode.ORIGINAL_HYPER.replacesRegion)
        assertNull(TitleCorrectionMode.ORIGINAL_HYPER.catalogStorefront)
    }

    @Test
    fun `cache namespaces stay unique so region profiles never share cached names`() {
        val namespaces = TitleCorrectionMode.values().map(TitleCorrectionMode::cacheNamespace)
        assertEquals(namespaces.size, namespaces.distinct().size)
    }

    @Test
    fun `storage values are unique and legacy values are preserved`() {
        val storages = TitleCorrectionMode.values().map(TitleCorrectionMode::storageValue)
        assertEquals(storages.size, storages.distinct().size)
        // Changing these would silently drop an existing user's selection.
        assertEquals("original_hyper", TitleCorrectionMode.ORIGINAL_HYPER.storageValue)
        assertEquals("mainland_china", TitleCorrectionMode.MAINLAND_CHINA.storageValue)
        assertEquals("japan", TitleCorrectionMode.JAPAN.storageValue)
    }

    @Test
    fun `decode round trips every profile and falls back to no region`() {
        TitleCorrectionMode.values().forEach { mode ->
            assertEquals(mode, TitleCorrectionMode.decode(mode.storageValue))
        }
        assertEquals(TitleCorrectionMode.ORIGINAL_HYPER, TitleCorrectionMode.decode(null))
        assertEquals(TitleCorrectionMode.ORIGINAL_HYPER, TitleCorrectionMode.decode("  "))
        assertEquals(TitleCorrectionMode.ORIGINAL_HYPER, TitleCorrectionMode.decode("unknown"))
    }

    @Test
    fun `legacy target language migration only recognizes mainland and japan`() {
        assertEquals(
            TitleCorrectionMode.MAINLAND_CHINA,
            TitleCorrectionMode.fromLegacyTargetLanguage("zh_cn"),
        )
        assertEquals(
            TitleCorrectionMode.JAPAN,
            TitleCorrectionMode.fromLegacyTargetLanguage("ja-JP"),
        )
        assertEquals(
            TitleCorrectionMode.ORIGINAL_HYPER,
            TitleCorrectionMode.fromLegacyTargetLanguage("ko-KR"),
        )
    }
}
