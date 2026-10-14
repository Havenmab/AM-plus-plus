package dev.amenhancer.module.hook

import dev.amenhancer.module.ModuleConstants

/** Native rendering correction; independent of the optional CJK glow effect. */
internal class LyricWrapSweepFeature : FeatureHook {
    override val key = ModuleConstants.FEATURE_LYRIC_WRAP_SWEEP
    override fun install(context: HookContext): FeatureInstallResult =
        context.target.lyricWrapSweep.install().toFeatureInstallResult()
}
