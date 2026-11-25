package dev.amenhancer.module.hook

import dev.amenhancer.module.ModuleConstants

/**
 * 「换行诊断」, gated by its own switch and by nothing else.
 *
 * Deliberately independent of 「歌词换行节奏」 and of 「歌词效果优化」: the probe has to be able to
 * measure Apple Music's own line change *as it is*, with the duration scaling off, otherwise the
 * numbers it reports describe this module instead of the host.
 *
 * The switch decides installation (restart to apply, like every other lyric effect). When it is off
 * this feature installs no hook at all — not a dormant one, not a filtered one.
 */
internal class LyricLineMotionProbeFeature : FeatureHook {
    override val key: String = ModuleConstants.FEATURE_LYRIC_LINE_MOTION_PROBE

    override fun install(context: HookContext): FeatureInstallResult {
        if (!context.config.settings().lyricLineMotionProbeEnabled) {
            return FeatureInstallResult.disabled("换行诊断已关闭")
        }
        return context.target.lyricLineMotionProbe.install().toFeatureInstallResult()
    }
}
