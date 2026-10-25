package dev.amenhancer.module.hook

import dev.amenhancer.module.ModuleConstants

/**
 * Installs the lyrics-source sheet capability.
 *
 * The sheet itself is module UI (the "更多" menu in the player opens it through the host-api
 * lambda that [dev.amenhancer.module.ui.EmbeddedSettingsHost.install] publishes), so this
 * feature only owns the host side: a target that installs the menu hook and publishes
 * availability.
 *
 * It deliberately has **no settings key**: the sheet is a per-song action, not a mode, so it
 * adds nothing to the schema. The target decides availability per build (Unsupported on a
 * host without the menu seam) and reports Degraded when the menu hook cannot be installed,
 * which leaves Apple Music's own menu exactly as it was.
 */
internal class LyricsSourceFeature : FeatureHook {
    override val key: String = ModuleConstants.FEATURE_LYRICS_SOURCE_SHEET

    override fun install(context: HookContext): FeatureInstallResult =
        context.target.lyricsSource.install().toFeatureInstallResult()
}
