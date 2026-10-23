package dev.amenhancer.module.hook

import dev.amenhancer.module.ModuleConstants

/** iOS-style spring lyric line motion; replaces Apple Music's own row re-anchor animation. */
internal class LyricLineMotionFeature : FeatureHook {
    override val key = ModuleConstants.FEATURE_LYRIC_LINE_MOTION
    override fun install(context: HookContext): FeatureInstallResult =
        context.target.lyricLineMotion.install().toFeatureInstallResult()
}
