package dev.amenhancer.module.hook

import dev.amenhancer.module.ModuleConstants

/** Native lifecycle repairs apply independently of optional layouts and media plugins. */
internal class PlayerRecoveryFeature : FeatureHook {
    override val key = ModuleConstants.FEATURE_PLAYER_RECOVERY

    override fun install(context: HookContext): FeatureInstallResult =
        context.target.playerRecovery.install().toFeatureInstallResult()
}
