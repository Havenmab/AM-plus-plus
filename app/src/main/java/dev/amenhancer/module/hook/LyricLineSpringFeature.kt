package dev.amenhancer.module.hook

import dev.amenhancer.module.ModuleConstants

/**
 * 「换行弹簧」, gated by its own switch.
 *
 * Installation is what the switch decides (restart to apply, like every other lyric effect). The
 * *value* is read live inside the hooks, together with 「歌词效果优化」, so either switch being turned
 * off returns the next line change to Apple Music without a restart.
 */
internal class LyricLineSpringFeature : FeatureHook {
    override val key: String = ModuleConstants.FEATURE_LYRIC_LINE_SPRING

    override fun install(context: HookContext): FeatureInstallResult {
        if (!context.config.settings().lyricLineSpringEnabled) {
            return FeatureInstallResult.disabled("换行弹簧已关闭")
        }
        return context.target.lyricLineSpring.install().toFeatureInstallResult()
    }
}
