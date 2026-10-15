package dev.amenhancer.module.config

import dev.amenhancer.module.ModuleConstants
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test

/**
 * The v20 migration must preserve the exact behaviour of the retired single picker:
 * its region half moves to `region_selection` and its title-correction half stays on
 * the `restore_cjk_original_metadata` boolean.  Both stored shapes are covered with
 * the boolean on and off.
 */
class RegionSelectionMigrationTest {
    private fun upgrade(
        schemaVersion: Int,
        legacyMode: String,
        restore: Boolean,
    ): Map<String, Any> = ModuleSettingsSchema.upgrade(
        storedValues = mapOf(
            "schema_version" to schemaVersion,
            "title_correction_enabled" to true,
            "title_correction_mode" to legacyMode,
            "restore_cjk_original_metadata" to restore,
        ),
        legacyValues = emptyMap<String, Any?>(),
    )!!

    @Test
    fun `the old no-region value maps to no region keeping the correction boolean`() {
        listOf(16, 19).forEach { version ->
            listOf(false, true).forEach { restore ->
                val upgraded = upgrade(version, "original_hyper", restore)

                assertEquals("none", upgraded["region_selection"])
                assertEquals(restore, upgraded["restore_cjk_original_metadata"])
                assertEquals(false, upgraded["override_account_language"])
                assertFalse(upgraded.containsKey("title_correction_mode"))
                assertEquals(ModuleConstants.CONFIG_SCHEMA_VERSION, upgraded["schema_version"])

                val decoded = ModuleSettingsSchema.decode(upgraded)
                assertEquals(RegionSelection.NONE, decoded.regionSelection)
                assertEquals(restore, decoded.restoreCjkOriginalMetadata)
                assertEquals(false, decoded.overrideAccountLanguage)
            }
        }
    }

    @Test
    fun `an old region value maps to that region keeping the correction boolean`() {
        listOf(16, 19).forEach { version ->
            listOf(false, true).forEach { restore ->
                val upgraded = upgrade(version, "japan", restore)

                assertEquals("japan", upgraded["region_selection"])
                assertEquals(restore, upgraded["restore_cjk_original_metadata"])
                assertEquals(true, upgraded["override_account_language"])
                assertFalse(upgraded.containsKey("title_correction_mode"))
                assertEquals(ModuleConstants.CONFIG_SCHEMA_VERSION, upgraded["schema_version"])

                val decoded = ModuleSettingsSchema.decode(upgraded)
                assertEquals(RegionSelection.JAPAN, decoded.regionSelection)
                assertEquals(restore, decoded.restoreCjkOriginalMetadata)
                assertEquals(true, decoded.overrideAccountLanguage)
            }
        }
    }

    @Test
    fun `every region value survives the migration with the boolean on and off`() {
        RegionSelection.values().forEach { region ->
            listOf(false, true).forEach { restore ->
                val legacyMode = if (region == RegionSelection.NONE) {
                    RegionSelection.LEGACY_NO_REGION_STORAGE_VALUE
                } else {
                    region.storageValue
                }
                val upgraded = upgrade(19, legacyMode, restore)

                assertEquals(region.storageValue, upgraded["region_selection"])
                assertEquals(restore, upgraded["restore_cjk_original_metadata"])
                assertEquals(region.replacesRegion, upgraded["override_account_language"])
                val decoded = ModuleSettingsSchema.decode(upgraded)
                assertEquals(region, decoded.regionSelection)
                assertEquals(restore, decoded.restoreCjkOriginalMetadata)
                assertEquals(region.replacesRegion, decoded.overrideAccountLanguage)
            }
        }
    }

    @Test
    fun `a store that predates the boolean keeps the retired picker's implied value`() {
        // Pre-v16 shapes never stored restore_cjk_original_metadata: the no-region
        // value implied "restore names" and every region implied "do not".
        val noRegion = ModuleSettingsSchema.upgrade(
            storedValues = mapOf(
                "schema_version" to 15,
                "title_correction_enabled" to true,
                "title_correction_mode" to "original_hyper",
            ),
            legacyValues = emptyMap<String, Any?>(),
        )!!
        assertEquals("none", noRegion["region_selection"])
        assertEquals(true, noRegion["restore_cjk_original_metadata"])
        assertEquals(false, noRegion["override_account_language"])

        val region = ModuleSettingsSchema.upgrade(
            storedValues = mapOf(
                "schema_version" to 15,
                "title_correction_enabled" to true,
                "title_correction_mode" to "japan",
            ),
            legacyValues = emptyMap<String, Any?>(),
        )!!
        assertEquals("japan", region["region_selection"])
        assertEquals(false, region["restore_cjk_original_metadata"])
        assertEquals(true, region["override_account_language"])
    }

    @Test
    fun `the v11 target language still migrates its region and implied correction`() {
        val japan = ModuleSettingsSchema.upgrade(
            storedValues = mapOf(
                "schema_version" to 11,
                "title_correction_enabled" to true,
                "title_correction_target_language" to "ja_JP",
            ),
            legacyValues = emptyMap<String, Any?>(),
        )!!
        assertEquals("japan", japan["region_selection"])
        assertEquals(false, japan["restore_cjk_original_metadata"])
        assertEquals(true, japan["override_account_language"])
        assertFalse(japan.containsKey("title_correction_target_language"))

        val unsupported = ModuleSettingsSchema.upgrade(
            storedValues = mapOf(
                "schema_version" to 11,
                "title_correction_enabled" to true,
                "title_correction_target_language" to "ko-KR",
            ),
            legacyValues = emptyMap<String, Any?>(),
        )!!
        assertEquals("none", unsupported["region_selection"])
        assertEquals(true, unsupported["restore_cjk_original_metadata"])
    }

    @Test
    fun `an already split store is never rewritten or re-derived`() {
        val stored = mapOf<String, Any>(
            "schema_version" to 19,
            "title_correction_enabled" to true,
            "region_selection" to "japan",
            "restore_cjk_original_metadata" to false,
        )
        // The current key wins even if the retired key lingers next to it.
        val decoded = ModuleSettingsSchema.decode(stored + ("title_correction_mode" to "korea"))
        assertEquals(RegionSelection.JAPAN, decoded.regionSelection)
        assertEquals(false, decoded.restoreCjkOriginalMetadata)
    }
}
