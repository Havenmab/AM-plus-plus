package dev.amenhancer.module.hook

import dev.amenhancer.module.ModuleConstants

/**
 * Installs the LyricsBlossom-style full-page lyrics player.
 *
 * One capability, one switch: while [dev.amenhancer.module.model.ModuleSettings.lyricsPlayerEnabled]
 * is off nothing in the host is touched and Apple Music's own lyrics page stays exactly as it is.
 * When it is on the target adapter owns every version/symbol check and the mount decision, so an
 * unsupported or unreadable host degrades (no overlay at all) instead of showing a blank page —
 * that Degraded/Failed state is reported through [FeatureInstallResult].
 *
 * The four motion/typography values are read live by the host side through
 * `TargetConfigClient.currentSettings()`, exactly like the lyric blur offset, so a slider change
 * needs no reinstall.
 */
internal class LyricsPlayerFeature : FeatureHook {
    override val key: String = ModuleConstants.FEATURE_LYRICS_PLAYER

    override fun install(context: HookContext): FeatureInstallResult {
        if (!context.config.settings().lyricsPlayerEnabled) {
            return FeatureInstallResult.disabled("LyricsBlossom 风格歌词播放器已关闭")
        }
        return context.target.lyricsPlayer.install().toFeatureInstallResult()
    }
}
