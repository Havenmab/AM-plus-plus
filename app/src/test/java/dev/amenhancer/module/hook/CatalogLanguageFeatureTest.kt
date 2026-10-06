package dev.amenhancer.module.hook

import dev.amenhancer.module.config.ConfigurationReader
import dev.amenhancer.module.config.ModuleSettingsSchema
import dev.amenhancer.module.config.RegionSelection
import dev.amenhancer.module.config.TargetConfigClient
import dev.amenhancer.module.model.FeatureState
import dev.amenhancer.module.model.ModuleSettings
import java.io.InputStream
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The region picker is the only input to the catalog-rewrite decision: neither the
 * account-language override nor the original-name restore switch changes it, and the
 * retired fork-only master switch no longer gates the report.  Uses the same
 * in-memory [ConfigurationReader] fake as the other feature tests.
 */
class CatalogLanguageFeatureTest {
    @Test
    fun `the region control alone decides catalog rewriting`() {
        val values = mutableMapOf<String, Any>()
        val config = TargetConfigClient(object : ConfigurationReader {
            override fun values(): Map<String, *> = values.toMap()
            override fun openFile(name: String): InputStream? = null
        })
        fun context() = HookContext(
            config,
            TargetAdaptation(
                identity = "test",
                dualPane = DualPaneTarget { error("unrelated dual pane") },
                editorialVideo = EditorialVideoTarget { error("unrelated editorial video") },
                bidirectionalLyricBlur = BidirectionalLyricBlurTarget { error("unrelated blur") },
            ),
        )

        fun installWith(
            region: RegionSelection,
            override: Boolean = false,
            restore: Boolean = false,
        ): FeatureInstallResult {
            values.clear()
            values.putAll(
                ModuleSettingsSchema.encodeOrdinarySettings(
                    ModuleSettings(
                        regionSelection = region,
                        overrideAccountLanguage = override,
                        restoreCjkOriginalMetadata = restore,
                    ),
                ),
            )
            return CatalogLanguageFeature().install(context())
        }

        // 不开启: content follows the account regardless of the two switches.
        val noneWithCorrection = installWith(RegionSelection.NONE, restore = true)
        assertEquals(FeatureState.ACTIVE, noneWithCorrection.state)
        assertTrue(noneWithCorrection.message.contains("No region selected"))
        assertEquals(noneWithCorrection.message, installWith(RegionSelection.NONE).message)
        assertEquals(
            noneWithCorrection.message,
            installWith(RegionSelection.NONE, override = true, restore = true).message,
        )

        // 日本: content is rewritten regardless of the two switches.
        val japanWithCorrection = installWith(RegionSelection.JAPAN, restore = true)
        val japanWithoutCorrection = installWith(RegionSelection.JAPAN)
        val japanWithOverrideOnly = installWith(RegionSelection.JAPAN, override = true)
        assertEquals(FeatureState.ACTIVE, japanWithCorrection.state)
        assertTrue(japanWithCorrection.message.contains("日语（日本）"))
        assertTrue(japanWithCorrection.message.contains("(jp)"))
        assertEquals(japanWithCorrection.message, japanWithoutCorrection.message)
        assertEquals(japanWithCorrection.message, japanWithOverrideOnly.message)

        // No master switch remains: even with every switch off the report is active.
        assertEquals(
            FeatureState.ACTIVE,
            installWith(RegionSelection.JAPAN).state,
        )
    }
}
