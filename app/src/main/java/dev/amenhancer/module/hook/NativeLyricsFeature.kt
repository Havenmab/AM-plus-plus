package dev.amenhancer.module.hook

import dev.amenhancer.module.ModuleConstants

/** Installs Apple's native lyrics lane hooks independently of custom replacements. */
internal class NativeLyricsFeature : FeatureHook {
    override val key: String = ModuleConstants.FEATURE_NATIVE_LYRICS

    override fun install(context: HookContext): FeatureInstallResult {
        if (!context.config.settings().onlineLyricsTranslationEnabled) {
            return FeatureInstallResult.disabled("Native pronunciation/translation lanes are off")
        }
        return context.target.nativeLyrics.install().toFeatureInstallResult()
    }
}
