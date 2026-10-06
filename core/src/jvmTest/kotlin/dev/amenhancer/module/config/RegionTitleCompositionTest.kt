package dev.amenhancer.module.config

import dev.amenhancer.module.model.ModuleSettings
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Proves that the region picker, HLE's account-language override switch and HLE's
 * original-name restore switch are independent controls: neither the settings codec
 * nor the request-side projection folds one into another, so every combination is
 * representable, round-trips, and drives only its own decisions.
 */
class RegionTitleCompositionTest {
    private val switches = listOf(false, true)

    @Test
    fun `the settings codec keeps the three controls independent`() {
        var combinations = 0
        RegionSelection.values().forEach { region ->
            switches.forEach { override ->
                switches.forEach { correction ->
                    combinations += 1
                    val encoded = ModuleSettingsSchema.encodeOrdinarySettings(
                        ModuleSettings(
                            regionSelection = region,
                            overrideAccountLanguage = override,
                            restoreCjkOriginalMetadata = correction,
                        ),
                    )

                    // Each control is written to its own key...
                    assertEquals(region.storageValue, encoded["region_selection"])
                    assertEquals(override, encoded["override_account_language"])
                    assertEquals(correction, encoded["restore_cjk_original_metadata"])
                    // ...and the retired single picker is never emitted.
                    assertFalse(encoded.containsKey("title_correction_mode"))
                    assertFalse(encoded.containsKey("title_correction_enabled"))

                    val decoded = ModuleSettingsSchema.decode(encoded)
                    assertEquals(region, decoded.regionSelection)
                    assertEquals(override, decoded.overrideAccountLanguage)
                    assertEquals(correction, decoded.restoreCjkOriginalMetadata)
                }
            }
        }
        assertEquals(RegionSelection.values().size * switches.size * switches.size, combinations)
    }

    @Test
    fun `every combination drives only its own request decisions`() {
        var combinations = 0
        RegionSelection.values().forEach { region ->
            switches.forEach { override ->
                switches.forEach { correction ->
                    combinations += 1
                    val plan = RegionTitleRequestPolicy.plan(
                        region = region,
                        overrideAccountLanguage = override,
                        restoreCjkOriginalMetadata = correction,
                    )

                    // Every region-derived decision follows the region selection alone.
                    assertEquals(region.replacesRegion, plan.rewritesCatalogRequests)
                    assertEquals(region.contentUiLanguageSelection, plan.contentUiLanguageSelection)
                    assertEquals(region.catalogLanguage, plan.catalogLanguage)
                    assertEquals(region.cacheNamespace, plan.cacheNamespace)
                    // The per-song decision follows the restore switch alone.
                    assertEquals(correction, plan.probesOriginalMetadata)
                    // HLE's regionReplacementEnabled composes the region with the override.
                    assertEquals(
                        region.replacesRegion && override,
                        plan.overrideAccountLanguage,
                    )
                }
            }
        }
        assertEquals(RegionSelection.values().size * switches.size * switches.size, combinations)
    }

    @Test
    fun `the HLE combination rule gates the metadata lookup and only visibility`() {
        RegionSelection.values().forEach { region ->
            switches.forEach { override ->
                switches.forEach { correction ->
                    val plan = RegionTitleRequestPolicy.plan(region, override, correction)
                    val regionReplacementEnabled = region.replacesRegion && override
                    val metadataLookupEnabled = regionReplacementEnabled || correction

                    // HLE: regionReplacementEnabled = contentUiLanguage != NONE && overrideAccountLanguage
                    assertEquals(regionReplacementEnabled, plan.overrideAccountLanguage)
                    // HLE: metadataLookupEnabled = regionReplacementEnabled || restoreCjkOriginalMetadata
                    assertEquals(metadataLookupEnabled, plan.metadataLookupEnabled)

                    if (!regionReplacementEnabled) {
                        // Without the region+override pair the override does nothing.
                        assertFalse(plan.overrideAccountLanguage)
                    }
                    if (region.replacesRegion) {
                        // The picker always redirects ordinary content traffic; the two
                        // switches never turn that off.
                        assertTrue(plan.rewritesCatalogRequests)
                    }
                }
            }
        }
    }

    @Test
    fun `the two requested features can be on at the same time`() {
        // The user's requirement: region replacement for content pages together with
        // the original-region name restore for song info.
        val plan = RegionTitleRequestPolicy.plan(
            region = RegionSelection.JAPAN,
            overrideAccountLanguage = true,
            restoreCjkOriginalMetadata = true,
        )

        assertTrue("content traffic must still be localized", plan.rewritesCatalogRequests)
        assertEquals("ja-JP", plan.catalogLanguage)
        assertTrue("account language must be replaced", plan.overrideAccountLanguage)
        assertTrue("original names must still be probed", plan.probesOriginalMetadata)
        assertTrue(plan.metadataLookupEnabled)
        assertTrue(plan.featureActive)
    }

    @Test
    fun `a region without the override keeps content localization but no metadata lookup`() {
        val plan = RegionTitleRequestPolicy.plan(
            region = RegionSelection.JAPAN,
            overrideAccountLanguage = false,
            restoreCjkOriginalMetadata = false,
        )

        assertTrue(plan.rewritesCatalogRequests)
        assertFalse(plan.overrideAccountLanguage)
        assertFalse(plan.probesOriginalMetadata)
        assertFalse(plan.metadataLookupEnabled)
    }

    @Test
    fun `no region with correction on probes original names but never rewrites content`() {
        val plan = RegionTitleRequestPolicy.plan(
            region = RegionSelection.NONE,
            overrideAccountLanguage = true,
            restoreCjkOriginalMetadata = true,
        )

        assertFalse(plan.rewritesCatalogRequests)
        assertEquals(null, plan.catalogLanguage)
        assertFalse(plan.overrideAccountLanguage)
        assertTrue(plan.probesOriginalMetadata)
        assertTrue(plan.metadataLookupEnabled)
        assertEquals(
            com.juren233.hyperlyricsenhanced.common.RootConstants
                .APPLE_MUSIC_CONTENT_UI_LANGUAGE_NONE,
            plan.contentUiLanguageSelection,
        )
        // 不开启 keeps the account cache profile the old no-region profile used.
        assertEquals("original_hyper_v1", plan.cacheNamespace)
    }

    @Test
    fun `flipping one control never changes the other controls decisions`() {
        RegionSelection.values().forEach { region ->
            val overrideOn = RegionTitleRequestPolicy.plan(region, true, false)
            val overrideOff = RegionTitleRequestPolicy.plan(region, false, false)
            val restoreOn = RegionTitleRequestPolicy.plan(region, false, true)

            // Flipping the override switch leaves the restore decision alone.
            assertEquals(overrideOn.probesOriginalMetadata, overrideOff.probesOriginalMetadata)
            assertEquals(restoreOn.probesOriginalMetadata, overrideOff.probesOriginalMetadata)
            // Flipping the restore switch leaves the region decisions alone.
            assertEquals(overrideOff.rewritesCatalogRequests, restoreOn.rewritesCatalogRequests)
            assertEquals(overrideOff.catalogLanguage, restoreOn.catalogLanguage)
            assertEquals(overrideOff.cacheNamespace, restoreOn.cacheNamespace)
            assertEquals(overrideOff.overrideAccountLanguage, restoreOn.overrideAccountLanguage)
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
        assertEquals(true, combined.overrideAccountLanguage)
        assertEquals(true, combined.restoreCjkOriginalMetadata)

        val regionOnly = ModuleSettingsSchema.decode(
            mapOf(
                "title_correction_enabled" to true,
                "title_correction_mode" to "japan",
                "restore_cjk_original_metadata" to false,
            ),
        )
        assertEquals(RegionSelection.JAPAN, regionOnly.regionSelection)
        assertEquals(true, regionOnly.overrideAccountLanguage)
        assertEquals(false, regionOnly.restoreCjkOriginalMetadata)
    }
}
