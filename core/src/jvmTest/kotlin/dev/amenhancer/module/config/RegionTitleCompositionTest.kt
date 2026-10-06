package dev.amenhancer.module.config

import dev.amenhancer.module.model.ModuleSettings
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Proves that 地区替换 and 歌曲名称修正 are two independent controls: neither the
 * settings codec nor the request-side projection reads both, so every combination
 * is representable and round-trips.
 */
class RegionTitleCompositionTest {
    private val corrections = listOf(false, true)

    @Test
    fun `the settings codec keeps the two controls independent`() {
        var combinations = 0
        RegionSelection.values().forEach { region ->
            corrections.forEach { correction ->
                combinations += 1
                val encoded = ModuleSettingsSchema.encodeOrdinarySettings(
                    ModuleSettings(
                        titleCorrectionEnabled = true,
                        regionSelection = region,
                        restoreCjkOriginalMetadata = correction,
                    ),
                )

                // The region half is written to its own key...
                assertEquals(region.storageValue, encoded["region_selection"])
                // ...and the correction half to the boolean key, untouched by the region.
                assertEquals(correction, encoded["restore_cjk_original_metadata"])
                assertFalse(encoded.containsKey("title_correction_mode"))

                val decoded = ModuleSettingsSchema.decode(encoded)
                assertEquals(region, decoded.regionSelection)
                assertEquals(correction, decoded.restoreCjkOriginalMetadata)
            }
        }
        assertEquals(RegionSelection.values().size * corrections.size, combinations)
    }

    @Test
    fun `every region and correction combination drives only its own request decisions`() {
        var combinations = 0
        RegionSelection.values().forEach { region ->
            corrections.forEach { correction ->
                combinations += 1
                val plan = RegionTitleRequestPolicy.plan(region, correction)

                // Every region-derived decision follows the region selection alone.
                assertEquals(region.replacesRegion, plan.rewritesCatalogRequests)
                assertEquals(region.contentUiLanguageSelection, plan.contentUiLanguageSelection)
                assertEquals(region.catalogLanguage, plan.catalogLanguage)
                assertEquals(region.cacheNamespace, plan.cacheNamespace)
                // The per-song decision follows the correction switch alone.
                assertEquals(correction, plan.probesOriginalMetadata)
            }
        }
        assertEquals(RegionSelection.values().size * corrections.size, combinations)
    }

    @Test
    fun `japan with correction off rewrites content but never probes original names`() {
        val plan = RegionTitleRequestPolicy.plan(
            region = RegionSelection.JAPAN,
            restoreCjkOriginalMetadata = false,
        )

        assertTrue(plan.rewritesCatalogRequests)
        assertEquals("ja-JP", plan.catalogLanguage)
        assertEquals("jp_v1", plan.cacheNamespace)
        assertFalse(plan.probesOriginalMetadata)
        assertEquals(
            com.juren233.hyperlyricsenhanced.common.RootConstants
                .APPLE_MUSIC_CONTENT_UI_LANGUAGE_JA_JP,
            plan.contentUiLanguageSelection,
        )
    }

    @Test
    fun `no region with correction on probes original names but never rewrites content`() {
        val plan = RegionTitleRequestPolicy.plan(
            region = RegionSelection.NONE,
            restoreCjkOriginalMetadata = true,
        )

        assertFalse(plan.rewritesCatalogRequests)
        assertEquals(null, plan.catalogLanguage)
        assertTrue(plan.probesOriginalMetadata)
        assertEquals(
            com.juren233.hyperlyricsenhanced.common.RootConstants
                .APPLE_MUSIC_CONTENT_UI_LANGUAGE_NONE,
            plan.contentUiLanguageSelection,
        )
        // 不开启地区替换 keeps the account cache profile the old no-region profile used.
        assertEquals("original_hyper_v1", plan.cacheNamespace)
    }

    @Test
    fun `flipping one control never changes the other control's decisions`() {
        RegionSelection.values().forEach { region ->
            val regionWithCorrectionOn = RegionTitleRequestPolicy.plan(region, true)
            val regionWithCorrectionOff = RegionTitleRequestPolicy.plan(region, false)

            // Flipping the correction switch leaves every region decision identical.
            assertEquals(
                regionWithCorrectionOn.copy(probesOriginalMetadata = false),
                regionWithCorrectionOff,
            )

            corrections.forEach { correction ->
                val withRegion = RegionTitleRequestPolicy.plan(region, correction)
                val withoutRegion = RegionTitleRequestPolicy.plan(RegionSelection.NONE, correction)

                // Changing the region never touches the per-song decision.
                assertEquals(withRegion.probesOriginalMetadata, correction)
                assertEquals(withoutRegion.probesOriginalMetadata, correction)
            }
        }
    }

    @Test
    fun `a legacy store keeps its region and its correction value independently`() {
        // The v19 shape: the retired picker stores the region, the boolean stores
        // the correction.  Decoding must not fold one into the other.
        val combined = ModuleSettingsSchema.decode(
            mapOf(
                "title_correction_enabled" to true,
                "title_correction_mode" to "japan",
                "restore_cjk_original_metadata" to true,
            ),
        )
        assertEquals(RegionSelection.JAPAN, combined.regionSelection)
        assertEquals(true, combined.restoreCjkOriginalMetadata)

        val regionOnly = ModuleSettingsSchema.decode(
            mapOf(
                "title_correction_enabled" to true,
                "title_correction_mode" to "japan",
                "restore_cjk_original_metadata" to false,
            ),
        )
        assertEquals(RegionSelection.JAPAN, regionOnly.regionSelection)
        assertEquals(false, regionOnly.restoreCjkOriginalMetadata)
    }
}
