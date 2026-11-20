package dev.amenhancer.module.hook

import dev.amenhancer.module.ModuleConstants

/** Installs Apple's native lyrics lane hooks independently of custom replacements. */
internal class NativeLyricsFeature : FeatureHook {
    override val key: String = ModuleConstants.FEATURE_NATIVE_LYRICS

    override fun install(context: HookContext): FeatureInstallResult {
        val settings = context.config.settings()
        // The delivery is shared by two independent opt-ins: the translated lane and
        // Apple's pronunciation lane. Gating it on the translation switch alone meant a
        // user who only wants "lyrics pronunciation" got no hooks at all - no lane, no
        // payload replay and not a single `online-translation native-write` line.
        if (!settings.onlineLyricsTranslationEnabled && !settings.lyricsPronunciationEnabled) {
            return FeatureInstallResult.disabled("Native pronunciation/translation lanes are off")
        }
        return context.target.nativeLyrics.install().toFeatureInstallResult()
    }
}
