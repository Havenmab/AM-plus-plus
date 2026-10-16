package dev.amenhancer.module.ui

import android.app.Activity
import android.widget.LinearLayout
import dev.amenhancer.module.i18n.ModuleText
import dev.amenhancer.module.model.EnhancementDefaults
import dev.amenhancer.module.model.ModuleSettings

/** Uses the dialog's current draft so tuning one range cannot undo another range's edit. */
internal fun EmbeddedSettingsHost.renderEmbeddedAnimationSettingsPage(
    activity: Activity,
    parent: LinearLayout,
    currentSettings: () -> ModuleSettings,
    onSettingsChanged: (ModuleSettings) -> Unit,
) {
    val settings = currentSettings()
    parent.addView(embeddedCard(activity, null) {
        addView(embeddedGlassRangeRow(
            activity = activity,
            title = localizedText(ModuleText.BOTTOM_BAR_COLLAPSE_REBOUND),
            value = settings.phoneLiquidGlassCollapseReboundPercent,
            defaultValue = EnhancementDefaults.GLASS_COLLAPSE_REBOUND_PERCENT,
            rangeMin = ModuleSettings.MIN_PHONE_LIQUID_GLASS_COLLAPSE_REBOUND_PERCENT,
            rangeMax = ModuleSettings.MAX_PHONE_LIQUID_GLASS_COLLAPSE_REBOUND_PERCENT,
            suffix = localizedText(ModuleText.BOTTOM_BAR_COLLAPSE_REBOUND_SUMMARY),
            unit = "%",
        ) { onSettingsChanged(currentSettings().copy(phoneLiquidGlassCollapseReboundPercent = it)) })
        addView(embeddedDivider(activity))
        addView(embeddedGlassRangeRow(
            activity = activity,
            title = localizedText(ModuleText.BOTTOM_BAR_MINI_REBOUND),
            value = settings.phoneLiquidGlassMiniReboundPercent,
            defaultValue = EnhancementDefaults.GLASS_MINI_REBOUND_PERCENT,
            rangeMin = ModuleSettings.MIN_PHONE_LIQUID_GLASS_MINI_REBOUND_PERCENT,
            rangeMax = ModuleSettings.MAX_PHONE_LIQUID_GLASS_MINI_REBOUND_PERCENT,
            suffix = localizedText(ModuleText.BOTTOM_BAR_MINI_REBOUND_SUMMARY),
            unit = "%",
        ) { onSettingsChanged(currentSettings().copy(phoneLiquidGlassMiniReboundPercent = it)) })
        addView(embeddedDivider(activity))
        addView(embeddedGlassRangeRow(
            activity = activity,
            title = localizedText(ModuleText.BOTTOM_BAR_MINI_SINK_BOUNCE),
            value = settings.phoneLiquidGlassMiniSinkBounceDp,
            defaultValue = EnhancementDefaults.GLASS_MINI_SINK_BOUNCE_DP,
            rangeMin = ModuleSettings.MIN_PHONE_LIQUID_GLASS_MINI_SINK_BOUNCE_DP,
            rangeMax = ModuleSettings.MAX_PHONE_LIQUID_GLASS_MINI_SINK_BOUNCE_DP,
            suffix = localizedText(ModuleText.BOTTOM_BAR_MINI_SINK_BOUNCE_SUMMARY),
        ) { onSettingsChanged(currentSettings().copy(phoneLiquidGlassMiniSinkBounceDp = it)) })
        addView(embeddedDivider(activity))
        addView(embeddedGlassRangeRow(
            activity = activity,
            title = localizedText(ModuleText.BOTTOM_BAR_EXPAND_REBOUND),
            value = settings.phoneLiquidGlassExpandReboundPercent,
            defaultValue = EnhancementDefaults.GLASS_EXPAND_REBOUND_PERCENT,
            rangeMin = ModuleSettings.MIN_PHONE_LIQUID_GLASS_EXPAND_REBOUND_PERCENT,
            rangeMax = ModuleSettings.MAX_PHONE_LIQUID_GLASS_EXPAND_REBOUND_PERCENT,
            suffix = localizedText(ModuleText.BOTTOM_BAR_EXPAND_REBOUND_SUMMARY),
            unit = "%",
        ) { onSettingsChanged(currentSettings().copy(phoneLiquidGlassExpandReboundPercent = it)) })
    })
}
