package dev.amenhancer.module.hook

import android.os.Build
import dev.amenhancer.module.ModuleConstants
import dev.amenhancer.module.config.TabletChromeStyle

/**
 * Tablet iPad-style chrome.
 *
 * This is a sub-option of the liquid-glass bar rather than a sibling: the style is only offered
 * while `phoneLiquidGlassEnabled` is on, and the tablet chrome reuses that feature's host-form
 * qualification. The style gate, the glass gate and the Android version gate are all checked here
 * so the feature reports `DISABLED`/`UNSUPPORTED` instead of installing nothing silently.
 *
 * The session reports its own runtime health once the top capsule is actually mounted; this entry
 * only covers the install phase.
 */
internal class TabletChromeFeature : FeatureHook {
    override val key: String = ModuleConstants.FEATURE_TABLET_CHROME

    override fun install(context: HookContext): FeatureInstallResult {
        val settings = context.config.settings()
        if (!settings.phoneLiquidGlassEnabled) return FeatureInstallResult.disabled()
        if (settings.tabletChromeStyle != TabletChromeStyle.IPAD) return FeatureInstallResult.disabled()
        if (Build.VERSION.SDK_INT < 33) {
            return FeatureInstallResult.unsupported("iPad 风格平板界面需要 Android 13 及以上")
        }
        val target = context.target.tabletChrome
            ?: return FeatureInstallResult.degraded("平板界面目标未配置，保持原生界面")
        // Publish the command surface for the session, which is created later by the glass
        // resource hook and has no direct object path to this adapter.
        TabletChromeRuntime.commands = target
        return target.install().toFeatureInstallResult()
    }
}
