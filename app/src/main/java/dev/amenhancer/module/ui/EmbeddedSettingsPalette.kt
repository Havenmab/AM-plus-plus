package dev.amenhancer.module.ui

/** Immutable, resource-independent colours usable inside the host process. */
internal data class EmbeddedSettingsColors(
    val isDark: Boolean,
    val pageBackground: Int,
    val surface: Int,
    val softBackground: Int,
    val softSurface: Int,
    val primary: Int,
    val primaryPressed: Int,
    val onPrimary: Int,
    val accent: Int,
    val accentPressed: Int,
    val onSurface: Int,
    val onSurfaceVariant: Int,
    val mutedText: Int,
    val outline: Int,
    val disabledSurface: Int,
    val disabledText: Int,
    val divider: Int,
    val inputUnderline: Int,
    val actionOutline: Int,
    val actionDivider: Int,
    val switchTrackOn: Int,
    val switchTrackOff: Int,
    val switchThumbOff: Int,
) {
    /** Icon factories accept the original light token (or a caller's custom colour). */
    fun iconColor(original: Int): Int = when (original) {
        EmbeddedSettingsPalette.Light.primary -> primary
        EmbeddedSettingsPalette.Light.primaryPressed -> primaryPressed
        EmbeddedSettingsPalette.Light.accent -> accent
        EmbeddedSettingsPalette.Light.accentPressed -> accentPressed
        EmbeddedSettingsPalette.Light.onSurface -> onSurface
        EmbeddedSettingsPalette.Light.onSurfaceVariant -> onSurfaceVariant
        EmbeddedSettingsPalette.Light.disabledText -> disabledText
        else -> original
    }
}

internal object EmbeddedSettingsPalette {
    val Light = EmbeddedSettingsColors(
        isDark = false,
        pageBackground = 0xFFFBFAFB.toInt(), surface = 0xFFFFFFFF.toInt(),
        softBackground = 0xFFFBF4F6.toInt(), softSurface = 0xFFFAF3F5.toInt(),
        primary = 0xFFEE3B4F.toInt(), primaryPressed = 0xFFF65A6B.toInt(),
        onPrimary = 0xFFFFFFFF.toInt(),
        accent = 0xFFA6537C.toInt(), accentPressed = 0xFF9D466E.toInt(),
        onSurface = 0xFF30232A.toInt(), onSurfaceVariant = 0xFF705965.toInt(),
        mutedText = 0xFF9E8C95.toInt(), outline = 0xFFEEE9EA.toInt(),
        disabledSurface = 0xFFF4EDF0.toInt(), disabledText = 0xFF9E8C95.toInt(),
        divider = 0xFFEEE9EA.toInt(), inputUnderline = 0xFF969195.toInt(),
        actionOutline = 0x26A6537C, actionDivider = 0x22A6537C,
        switchTrackOn = 0xFFF497A1.toInt(), switchTrackOff = 0xFFD5D5D5.toInt(),
        switchThumbOff = 0xFF9E8C95.toInt(),
    )

    val Dark = EmbeddedSettingsColors(
        isDark = true,
        pageBackground = 0xFF000000.toInt(), surface = 0xFF171717.toInt(),
        softBackground = 0xFF242424.toInt(), softSurface = 0xFF242424.toInt(),
        primary = 0xFFFF6577.toInt(), primaryPressed = 0xFFFF8492.toInt(),
        onPrimary = 0xFF2B0710.toInt(),
        accent = 0xFFDEA0C4.toInt(), accentPressed = 0xFFEDB2CE.toInt(),
        onSurface = 0xFFF3EFF2.toInt(), onSurfaceVariant = 0xFFBCB6BE.toInt(),
        mutedText = 0xFFABA4AD.toInt(), outline = 0xFF363636.toInt(),
        disabledSurface = 0xFF232323.toInt(), disabledText = 0xFF817A84.toInt(),
        divider = 0xFF363636.toInt(), inputUnderline = 0xFF686872.toInt(),
        actionOutline = 0xFF363636.toInt(), actionDivider = 0xFF363636.toInt(),
        switchTrackOn = 0xFFA33F51.toInt(), switchTrackOff = 0xFF52525A.toInt(),
        switchThumbOff = 0xFFB8B8C1.toInt(),
    )

    /** Only the host Activity's effective configuration should be passed here. */
    fun forUiMode(uiMode: Int): EmbeddedSettingsColors =
        if (uiMode and 0x30 == 0x20) Dark else Light
}

internal fun embeddedSvgColor(icon: EmbeddedSvgIcon, colors: EmbeddedSettingsColors): Int =
    when (icon) {
        EmbeddedSvgIcon.Back, EmbeddedSvgIcon.RestoreDefault -> colors.primary
        else -> colors.accent
    }
