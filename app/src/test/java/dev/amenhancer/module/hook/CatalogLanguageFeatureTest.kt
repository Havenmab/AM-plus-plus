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
 * The region control is the only input to the catalog-rewrite decision: the
 * 歌曲名称修正 switch never changes it.  Uses the same in-memory
 * [ConfigurationReader] fake as the other feature tests.
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

        fun installWith(region: RegionSelection, restore: Boolean): FeatureInstallResult {
            values.clear()
            values.putAll(
                ModuleSettingsSchema.encodeOrdinarySettings(
                    ModuleSettings(
                        titleCorrectionEnabled = true,
                        regionSelection = region,
                        restoreCjkOriginalMetadata = restore,
                    ),
                ),
            )
            return CatalogLanguageFeature().install(context())
        }

        // 不开启地区替换: content follows the account regardless of the title switch.
        val noneWithCorrection = installWith(RegionSelection.NONE, restore = true)
        assertEquals(FeatureState.ACTIVE, noneWithCorrection.state)
        assertTrue(noneWithCorrection.message.contains("No region selected"))
        assertEquals(noneWithCorrection.message, installWith(RegionSelection.NONE, false).message)

        // 日本: content is rewritten regardless of the title switch.
        val japanWithCorrection = installWith(RegionSelection.JAPAN, restore = true)
        val japanWithoutCorrection = installWith(RegionSelection.JAPAN, restore = false)
        assertEquals(FeatureState.ACTIVE, japanWithCorrection.state)
        assertTrue(japanWithCorrection.message.contains("日语（日本）"))
        assertTrue(japanWithCorrection.message.contains("(jp)"))
        assertEquals(japanWithCorrection.message, japanWithoutCorrection.message)

        // The master switch still gates the whole feature.
        values.clear()
        values.putAll(
            ModuleSettingsSchema.encodeOrdinarySettings(
                ModuleSettings(
                    titleCorrectionEnabled = false,
                    regionSelection = RegionSelection.JAPAN,
                ),
            ),
        )
        assertEquals(FeatureState.DISABLED, CatalogLanguageFeature().install(context()).state)
    }
}
