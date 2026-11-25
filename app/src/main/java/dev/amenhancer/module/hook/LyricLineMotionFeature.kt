package dev.amenhancer.module.hook

import dev.amenhancer.module.ModuleConstants

/**
 * 「歌词换行节奏」, gated by its own switch.
 *
 * Installation is what the switch decides (restart to apply, like the other lyric effects); the
 * *value* of the slider and the 「歌词效果优化」 gate are read live inside the hook, so moving the
 * slider changes the next line change without a restart.
 */
internal class LyricLineMotionFeature : FeatureHook {
    override val key: String = ModuleConstants.FEATURE_LYRIC_LINE_MOTION

    override fun install(context: HookContext): FeatureInstallResult {
        if (!context.config.settings().lyricLineMotionEnabled) {
            return FeatureInstallResult.disabled("歌词换行节奏已关闭")
        }
        return context.target.lyricLineMotion.install().toFeatureInstallResult()
    }
}
