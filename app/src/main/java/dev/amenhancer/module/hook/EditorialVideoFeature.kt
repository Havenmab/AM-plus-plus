package dev.amenhancer.module.hook

import dev.amenhancer.module.ModuleConstants

/** Keeps Apple's Editorial Video path intact for the tablet player. */
internal class EditorialVideoFeature : FeatureHook {
    override val key: String = ModuleConstants.FEATURE_EDITORIAL_VIDEO

    override fun install(context: HookContext): FeatureInstallResult {
        if (!context.config.settings().dualPaneEnabled) {
            return FeatureInstallResult.disabled()
        }
        return context.target.editorialVideo.install().toFeatureInstallResult()
    }
}
