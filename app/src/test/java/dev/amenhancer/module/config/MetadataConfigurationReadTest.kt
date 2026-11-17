package dev.amenhancer.module.config

import java.io.InputStream
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The metadata runtime reads its four controls through [TargetConfigClient.metadataConfiguration]
 * so a settings change needs no restart.  This test drives that reader against a mutable store:
 * it is the "call time" half of the contract, complementing the pure truth table in
 * RegionMetadataConfigurationTest.
 */
class MetadataConfigurationReadTest {
    @Test
    fun `the reader follows the store instead of a construction-time snapshot`() {
        val values = mutableMapOf<String, Any>("region_selection" to RegionSelection.JAPAN.storageValue)
        val config = TargetConfigClient(object : ConfigurationReader {
            override fun values(): Map<String, *> = values.toMap()
            override fun openFile(name: String): InputStream? = null
        })

        val installed = config.metadataConfiguration()
        assertEquals(RegionSelection.JAPAN, installed.region)
        assertFalse(installed.overrideAccountLanguage)
        assertFalse(installed.restoreCjkOriginalMetadata)
        // 日语 selected, override off: the region rewrites traffic but nothing is replaced.
        assertTrue(installed.plan.rewritesCatalogRequests)
        assertFalse(installed.plan.overrideAccountLanguage)

        // The user moves the picker and turns both switches on while Apple Music is running.
        values["region_selection"] = RegionSelection.KOREA.storageValue
        values["override_account_language"] = true
        values["restore_cjk_original_metadata"] = true

        val live = config.metadataConfiguration()
        assertEquals(RegionSelection.KOREA, live.region)
        assertTrue(live.overrideAccountLanguage)
        assertTrue(live.restoreCjkOriginalMetadata)
        assertEquals("kr", live.region.catalogStorefront)
        assertTrue(live.plan.overrideAccountLanguage)
        assertTrue(live.plan.probesOriginalMetadata)
        // HLE's shouldOverrideAccountLanguage(selection): 不开启 never overrides.
        assertTrue(live.shouldOverrideAccountLanguage(live.region.contentUiLanguageSelection))
        assertFalse(live.shouldOverrideAccountLanguage(RegionSelection.NONE.contentUiLanguageSelection))

        // Turning the region off leaves the restore switch doing its own work.
        values["region_selection"] = RegionSelection.NONE.storageValue
        val restored = config.metadataConfiguration()
        assertFalse(restored.plan.rewritesCatalogRequests)
        assertFalse(restored.shouldOverrideAccountLanguage(restored.region.contentUiLanguageSelection))
        assertTrue(restored.plan.probesOriginalMetadata)
    }
}
