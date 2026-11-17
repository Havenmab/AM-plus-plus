package dev.amenhancer.module.hook

import dev.amenhancer.module.ModuleConstants

/**
 * Installs the HLE metadata runtime.
 *
 * HLE has no master switch on its page: the runtime is installed unconditionally and
 * [dev.amenhancer.module.config.RegionTitleRequestPolicy]'s equivalent is evaluated inside
 * every callback, so a control that is off only makes those callbacks yield.  AM++ used to
 * fold that gate into the install decision (`if (!plan.featureActive) return disabled()`),
 * which is what made opening a switch require stopping and reopening Apple Music: the seam
 * installation is a one-shot session and nothing re-runs it.
 *
 * The install is therefore unconditional, and the runtime reads
 * [dev.amenhancer.module.config.RegionMetadataConfiguration] on its request seams instead;
 * an inactive configuration resolves nothing, exactly like HLE's yielded callbacks.  The cost
 * is the same one any single enabled control already paid: one DexKit target resolution and
 * one hook per seam.  It buys the property the report asked for — flipping any of the three
 * controls takes effect in the running app instead of after a restart.
 *
 * Failure reporting is unchanged: a partially installed runtime still reports
 * [TargetCapabilityInstall.Degraded] with the names of the seams it could not install, and a
 * thrown construction still surfaces as `FeatureInstallResult.failed`.
 */
internal class TitleCorrectionFeature : FeatureHook {
    override val key: String = ModuleConstants.FEATURE_TITLE_CORRECTION

    override fun install(context: HookContext): FeatureInstallResult =
        context.target.hleMetadata.install().toFeatureInstallResult()
}
