package dev.amenhancer.module.hook

import dev.amenhancer.module.ModuleConstants
import dev.amenhancer.module.config.RegionTitleRequestPolicy

/**
 * Installs the HLE metadata runtime.
 *
 * HLE's page has no master switch: the runtime only does work when the region picker
 * selects a region (content-UI language / storefront) or the original-name restore is
 * on, so those two controls are the install gate.  The account-language override alone
 * is meaningless without a region and never turns the runtime on by itself.
 */
internal class TitleCorrectionFeature : FeatureHook {
    override val key: String = ModuleConstants.FEATURE_TITLE_CORRECTION

    override fun install(context: HookContext): FeatureInstallResult {
        val settings = context.config.settings()
        val plan = RegionTitleRequestPolicy.plan(
            region = settings.regionSelection,
            overrideAccountLanguage = settings.overrideAccountLanguage,
            restoreCjkOriginalMetadata = settings.restoreCjkOriginalMetadata,
        )
        if (!plan.featureActive) {
            return FeatureInstallResult.disabled()
        }
        return context.target.hleMetadata.install().toFeatureInstallResult()
    }
}
