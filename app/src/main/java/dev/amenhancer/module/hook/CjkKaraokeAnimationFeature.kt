package dev.amenhancer.module.hook

import dev.amenhancer.module.ModuleConstants

/**
 * Installs CJK glow animation and the supported host's terminal sweep compensation together.
 *
 * The target adapter owns all version and symbol checks; unsupported hosts
 * therefore report a degraded health result without touching existing lyric
 * layout or blur paths.
 */
internal class CjkKaraokeAnimationFeature : FeatureHook {
    override val key: String = ModuleConstants.FEATURE_CJK_KARAOKE_ANIMATION

    override fun install(context: HookContext): FeatureInstallResult {
        if (!context.config.settings().cjkKaraokeAnimationEnabled) {
            return FeatureInstallResult.disabled("CJK 长尾歌词动画已关闭")
        }
        return context.target.cjkKaraokeAnimation.install().toFeatureInstallResult()
    }
}
