package dev.amenhancer.module.hook

import dev.amenhancer.module.ModuleConstants

/** Shares the CJK animation setting while retaining an independent host contract and install result. */
internal class LyricWrapSweepFeature : FeatureHook {
    override val key = ModuleConstants.FEATURE_LYRIC_WRAP_SWEEP

    override fun install(context: HookContext): FeatureInstallResult {
        if (!context.config.settings().cjkKaraokeAnimationEnabled) {
            return FeatureInstallResult.disabled("跨行歌词扫光修复随 CJK 长尾歌词动画关闭")
        }
        return context.target.lyricWrapSweep.install().toFeatureInstallResult()
    }
}
