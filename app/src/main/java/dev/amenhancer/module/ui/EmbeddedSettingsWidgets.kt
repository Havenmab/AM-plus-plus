package dev.amenhancer.module.ui

import dev.amenhancer.module.i18n.ModuleText

import android.app.Activity
import android.app.AlertDialog
import android.content.Context
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.Drawable
import android.graphics.drawable.GradientDrawable
import android.text.InputType
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.PopupMenu
import android.widget.SeekBar
import android.widget.Switch
import android.widget.TextView
import dev.amenhancer.module.ModuleConstants
import dev.amenhancer.module.config.RegionSelection
import dev.amenhancer.module.CurrentSongDetails
import dev.amenhancer.module.model.ModuleSettings

internal fun EmbeddedSettingsHost.embeddedStatusCard(activity: Activity, song: CurrentSongDetails?): View =
        embeddedCard(activity, null) {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            minimumHeight = dp(activity, if (isEmbeddedPhone(activity)) 84 else 64)
            val horizontalPadding = dp(activity, if (isEmbeddedPhone(activity)) 16 else 12)
            val verticalPadding = dp(activity, if (isEmbeddedPhone(activity)) 12 else 8)
            setPadding(horizontalPadding, verticalPadding, dp(activity, if (isEmbeddedPhone(activity)) 12 else 8), verticalPadding)
            addView(embeddedMusicIcon(activity), LinearLayout.LayoutParams(
                dp(activity, if (isEmbeddedPhone(activity)) 48 else 38),
                dp(activity, if (isEmbeddedPhone(activity)) 48 else 38),
            ))
            addView(LinearLayout(activity).apply {
                orientation = LinearLayout.VERTICAL
                addView(TextView(activity).apply {
                    text = song?.let {
                        localizedText(ModuleText.CURRENT_SONG_TITLE, it.title.orEmpty().ifBlank { localizedText(ModuleText.UNKNOWN_TITLE) })
                    } ?: localizedText(ModuleText.CURRENT_SONG_NOT_CAPTURED)
                    textSize = embeddedTextSize(activity, 16f, 14f)
                    setEmbeddedTextColor(activity) { colors -> colors.onSurface }
                    setTypeface(typeface, Typeface.BOLD)
                    setSingleLine(false)
                    maxLines = 2
                }, matchWidthWrapContent())
                addView(TextView(activity).apply {
                    text = song?.let { "Apple Music ID：${it.appleMusicId}" } ?: localizedText(ModuleText.MUSIC_ID_PLAY_HINT)
                    textSize = embeddedTextSize(activity, 13f, 12f)
                    setEmbeddedTextColor(activity) { colors -> colors.onSurfaceVariant }
                    setSingleLine(false)
                    setPadding(0, dp(activity, 2), 0, 0)
                }, matchWidthWrapContent())
            }, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply {
                marginStart = dp(activity, if (isEmbeddedPhone(activity)) 14 else 12)
            })
            addView(ImageView(activity).apply {
                setEmbeddedImageDrawable(activity,
                    EmbeddedGlyphDrawable(
                        EmbeddedGlyphKind.ChevronRight,
                        EmbeddedSettingsPalette.Light.onSurfaceVariant,
                    ),
                )
                scaleType = ImageView.ScaleType.CENTER
                contentDescription = null
                setPadding(dp(activity, 5), dp(activity, 8), dp(activity, 5), dp(activity, 8))
            }, LinearLayout.LayoutParams(dp(activity, if (isEmbeddedPhone(activity)) 32 else 28), dp(activity, 44)))
        }


internal fun EmbeddedSettingsHost.embeddedMusicIcon(activity: Activity): View =
        ImageView(activity).apply {
            setEmbeddedImageDrawable(activity, EmbeddedMusicStatusDrawable())
            contentDescription = localizedText(ModuleText.CURRENT_SONG)
            scaleType = ImageView.ScaleType.CENTER_INSIDE
        }


internal fun EmbeddedSettingsHost.embeddedFontCard(
        activity: Activity,
        manifest: dev.amenhancer.module.model.LyricsFontManifest,
        onChooseFont: () -> Unit,
        onClearFont: () -> Unit,
    ): View = embeddedCard(activity, localizedText(ModuleText.LYRICS_FONT)) {
        addView(TextView(activity).apply {
            text = if (manifest.enabled) manifest.displayName else localizedText(ModuleText.ORIGINAL_FONT)
            textSize = embeddedTextSize(activity, 16f, 17f)
            setEmbeddedTextColor(activity) { colors -> colors.onSurface }
            setTypeface(typeface, Typeface.BOLD)
            setSingleLine(false)
            maxLines = 2
            setPadding(dp(activity, 16), dp(activity, 4), dp(activity, 16), 0)
        }, matchWidthWrapContent())
        addView(TextView(activity).apply {
            text = if (manifest.enabled) {
                localizedText(ModuleText.LYRICS_FONT_ENABLED_SUMMARY)
            } else {
                localizedText(ModuleText.LYRICS_FONT_IMPORT_SUMMARY)
            }
            textSize = embeddedTextSize(activity, 12.5f, 13.5f)
            setEmbeddedTextColor(activity) { colors -> colors.onSurfaceVariant }
            setSingleLine(false)
            setPadding(dp(activity, 16), dp(activity, 4), dp(activity, 16), dp(activity, 12))
        }, matchWidthWrapContent())
        val actions = LinearLayout(activity).apply {
            orientation = embeddedActionOrientation(activity)
            setPadding(dp(activity, 12), 0, dp(activity, 12), dp(activity, 12))
            addView(embeddedActionButton(activity, localizedText(ModuleText.CHOOSE_FONT), onClick = onChooseFont),
                embeddedActionButtonParams(activity))
            addView(embeddedActionSpacer(activity))
            addView(embeddedActionButton(activity, localizedText(ModuleText.RESTORE_FONT), manifest.enabled, onClearFont),
                embeddedActionButtonParams(activity))
        }
        addView(actions, matchWidthWrapContent())
    }


internal fun EmbeddedSettingsHost.embeddedCard(
        activity: Activity,
        title: String?,
        outlined: Boolean = true,
        content: LinearLayout.() -> Unit,
    ): LinearLayout = LinearLayout(activity).apply {
        orientation = LinearLayout.VERTICAL
        bindEmbeddedTheme(activity) { colors ->
            background = GradientDrawable().apply {
                setColor(if (outlined) colors.surface else Color.TRANSPARENT)
                if (outlined) setStroke(dp(activity, 1), colors.outline)
                cornerRadius = embeddedCardCornerRadius(activity)
            }
        }
        elevation = 0f
        title?.let { section -> addView(embeddedSectionLabel(activity, section)) }
        content()
    }


internal fun EmbeddedSettingsHost.embeddedSectionLabel(activity: Activity, text: String): TextView =
        TextView(activity).apply {
            this.text = text
            textSize = embeddedTextSize(activity, 14f, 14f)
            setEmbeddedTextColor(activity) { colors -> colors.accent }
            setTypeface(typeface, Typeface.BOLD)
            setSingleLine(false)
            val horizontalPadding = dp(activity, if (isEmbeddedPhone(activity)) 12 else 6)
            setPadding(
                horizontalPadding,
                dp(activity, if (isEmbeddedPhone(activity)) 10 else 8),
                horizontalPadding,
                dp(activity, if (isEmbeddedPhone(activity)) 4 else 6),
            )
        }


internal fun EmbeddedSettingsHost.embeddedInfoCard(
        activity: Activity,
        text: String,
        onClick: (() -> Unit)? = null,
    ): View = embeddedCard(activity, null) {
            addView(TextView(activity).apply {
                this.text = text
                textSize = embeddedTextSize(activity, 12.5f, 13.5f)
                setEmbeddedTextColor(activity) { colors -> colors.onSurfaceVariant }
                setSingleLine(false)
                val horizontalPadding = dp(activity, if (isEmbeddedPhone(activity)) 12 else 16)
                val verticalPadding = dp(activity, if (isEmbeddedPhone(activity)) 8 else 12)
                setPadding(horizontalPadding, verticalPadding, horizontalPadding, verticalPadding)
            }, matchWidthWrapContent())
            onClick?.let { click ->
                isClickable = true
                isFocusable = true
                setOnClickListener { click() }
            }
        }


internal fun EmbeddedSettingsHost.showEmbeddedHelp(activity: Activity) {
        embeddedDialogBuilder(activity)
            .setTitle(localizedText(ModuleText.LSPOSED_HELP))
            .setMessage(
                localizedText(ModuleText.LSPOSED_HELP_SCOPE) +
                    localizedText(ModuleText.LSPOSED_HELP_RESTART),
            )
            .setPositiveButton(localizedText(ModuleText.GOT_IT), null)
            .show()
    }


internal fun EmbeddedSettingsHost.embeddedSettingRow(
        activity: Activity,
        title: String,
        summary: String,
        checked: Boolean,
        badge: String? = null,
        badgeAtToggle: Boolean = false,
        iconRes: Int? = null,
        iconTint: Int = EmbeddedSettingsPalette.Light.accent,
        iconDrawable: Drawable? = null,
        compactWidePadding: Boolean = false,
        enabled: Boolean = true,
        onEnableConfirmation: ((onConfirmed: () -> Unit) -> Unit)? = null,
        onChanged: (Boolean) -> Unit,
    ): View = LinearLayout(activity).apply {
        orientation = LinearLayout.HORIZONTAL
        gravity = Gravity.CENTER_VERTICAL
        minimumHeight = embeddedSettingRowHeight(activity, compactWidePadding)
        isEnabled = enabled
        alpha = if (enabled) 1f else 0.58f
        val horizontalPadding = when {
            isEmbeddedPhone(activity) -> 12
            compactWidePadding -> 8
            else -> 12
        }
        setPadding(
            dp(activity, horizontalPadding),
            dp(activity, if (isEmbeddedPhone(activity)) 6 else 4),
            dp(activity, if (isEmbeddedPhone(activity)) 8 else 8),
            dp(activity, if (isEmbeddedPhone(activity)) 6 else 4),
        )
        val resolvedIcon = iconDrawable
            ?: iconRes?.let { activity.getDrawable(it) }
            ?: EmbeddedGlyphDrawable(EmbeddedGlyphKind.Document, iconTint)
        addView(embeddedFeatureIcon(activity, resolvedIcon, iconTint), LinearLayout.LayoutParams(embeddedFeatureIconSize(activity), embeddedFeatureIconSize(activity)).apply {
            marginEnd = dp(activity, if (isEmbeddedPhone(activity)) 8 else 12)
        })
        val labels = LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
            addView(LinearLayout(activity).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                addView(TextView(activity).apply {
                    text = title
                    textSize = embeddedTextSize(activity, 16f, 14f)
                    setEmbeddedTextColor(activity) { colors -> colors.onSurface }
                    setTypeface(typeface, Typeface.NORMAL)
                    setSingleLine(false)
                    maxLines = 2
                }, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
                if (!badgeAtToggle) badge?.let { badgeText ->
                    addView(embeddedBadge(activity, badgeText))
                }
            }, matchWidthWrapContent())
            addView(TextView(activity).apply {
                text = summary
                textSize = embeddedTextSize(activity, 13f, 12f)
                setEmbeddedTextColor(activity) { colors -> colors.onSurfaceVariant }
                setSingleLine(false)
                maxLines = 4
                if (isEmbeddedPhone(activity)) {
                    ellipsize = android.text.TextUtils.TruncateAt.END
                }
                setPadding(0, dp(activity, 2), 0, 0)
            }, matchWidthWrapContent())
        }
        addView(labels, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        val toggle = Switch(activity).apply {
            isChecked = checked
            isEnabled = enabled
            minimumWidth = dp(activity, 44)
            minimumHeight = dp(activity, 44)
            bindEmbeddedSwitchTheme(activity)
        }
        var suppressToggleCallback = false
        var committedToggleValue = checked
        toggle.setOnCheckedChangeListener { _, value ->
            if (suppressToggleCallback) return@setOnCheckedChangeListener
            if (value && !committedToggleValue && onEnableConfirmation != null) {
                suppressToggleCallback = true
                toggle.isChecked = false
                suppressToggleCallback = false
                onEnableConfirmation.invoke {
                    suppressToggleCallback = true
                    toggle.isChecked = true
                    suppressToggleCallback = false
                    committedToggleValue = true
                    onChanged(true)
                }
            } else {
                committedToggleValue = value
                onChanged(value)
            }
        }
        val toggleControls = LinearLayout(activity).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            if (badgeAtToggle && badge != null) {
                addView(embeddedBadge(activity, badge), LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.WRAP_CONTENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT,
                ).apply { marginEnd = dp(activity, 4) })
            }
            addView(toggle, LinearLayout.LayoutParams(
                dp(activity, if (isEmbeddedPhone(activity)) 48 else 46),
                dp(activity, if (isEmbeddedPhone(activity)) 44 else 44),
            ))
        }
        addView(toggleControls, LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.WRAP_CONTENT,
            dp(activity, if (isEmbeddedPhone(activity)) 44 else 44),
        ))
        setOnClickListener { toggle.isChecked = !toggle.isChecked }
    }


internal fun EmbeddedSettingsHost.embeddedBadge(activity: Activity, text: String): View = TextView(activity).apply {
        this.text = text
        textSize = embeddedTextSize(activity, 12f, 11f)
        gravity = Gravity.CENTER
        setEmbeddedTextColor(activity) { colors -> colors.accent }
        setPadding(dp(activity, 6), dp(activity, 2), dp(activity, 6), dp(activity, 2))
        bindEmbeddedTheme(activity) { colors ->
            background = GradientDrawable().apply {
                setColor(colors.softSurface)
                cornerRadius = dp(activity, 99).toFloat()
            }
        }
        layoutParams = LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.WRAP_CONTENT,
            ViewGroup.LayoutParams.WRAP_CONTENT,
        ).apply { marginStart = dp(activity, 6) }
    }


internal fun EmbeddedSettingsHost.embeddedFeatureIcon(activity: Activity, iconRes: Int): View =
        embeddedFeatureIcon(activity, iconRes, EmbeddedSettingsPalette.Light.accent)


internal fun EmbeddedSettingsHost.embeddedFeatureIcon(activity: Activity, iconRes: Int, iconTint: Int): View =
        embeddedFeatureIcon(activity, activity.getDrawable(iconRes), iconTint)


internal fun EmbeddedSettingsHost.embeddedFeatureIcon(activity: Activity, icon: Drawable?, iconTint: Int): View =
        FrameLayout(activity).apply {
            bindEmbeddedTheme(activity) { colors ->
                background = GradientDrawable().apply {
                    shape = GradientDrawable.RECTANGLE
                    setColor(colors.softSurface)
                    cornerRadius = dp(activity, 8).toFloat()
                }
            }
            addView(ImageView(activity).apply {
                setEmbeddedImageDrawable(activity, icon)
                bindEmbeddedIconTint(activity, iconTint)
                contentDescription = null
                val iconPadding = dp(activity, if (isEmbeddedPhone(activity)) 10 else 8)
                setPadding(iconPadding, iconPadding, iconPadding, iconPadding)
            }, FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT,
            ))
        }


internal fun EmbeddedSettingsHost.embeddedBlurRadiusRow(
        activity: Activity,
        value: Int,
        onChanged: (Int) -> Unit,
    ): View = LinearLayout(activity).apply {
        orientation = LinearLayout.VERTICAL
        val horizontalPadding = dp(activity, if (isEmbeddedPhone(activity)) 12 else 16)
        setPadding(
            horizontalPadding,
            dp(activity, if (isEmbeddedPhone(activity)) 8 else 10),
            horizontalPadding,
            dp(activity, if (isEmbeddedPhone(activity)) 6 else 8),
        )
        val title = localizedText(ModuleText.LYRIC_BLUR_OFFSET)
        val label = TextView(activity).apply {
            text = localizedText(ModuleText.SETTING_VALUE, title, value, "px")
            textSize = embeddedTextSize(activity, 14f, 15f)
            setEmbeddedTextColor(activity) { colors -> colors.onSurface }
        }
        addView(label, matchWidthWrapContent())
        addView(SeekBar(activity).apply {
            bindEmbeddedSeekBarTheme(activity)
            max = ModuleSettings.MAX_LYRIC_BLUR_RADIUS_OFFSET_PX -
                ModuleSettings.MIN_LYRIC_BLUR_RADIUS_OFFSET_PX
            progress = value - ModuleSettings.MIN_LYRIC_BLUR_RADIUS_OFFSET_PX
            setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
                override fun onProgressChanged(seekBar: SeekBar?, progress: Int, fromUser: Boolean) {
                    val next = (progress + ModuleSettings.MIN_LYRIC_BLUR_RADIUS_OFFSET_PX).coerceIn(
                        ModuleSettings.MIN_LYRIC_BLUR_RADIUS_OFFSET_PX,
                        ModuleSettings.MAX_LYRIC_BLUR_RADIUS_OFFSET_PX,
                    )
                    label.text = localizedText(ModuleText.SETTING_VALUE, title, next, "px")
                }

                override fun onStartTrackingTouch(seekBar: SeekBar?) = Unit

                override fun onStopTrackingTouch(seekBar: SeekBar?) {
                    if (seekBar != null) {
                        onChanged(
                            (seekBar.progress + ModuleSettings.MIN_LYRIC_BLUR_RADIUS_OFFSET_PX)
                                .coerceIn(
                                    ModuleSettings.MIN_LYRIC_BLUR_RADIUS_OFFSET_PX,
                                    ModuleSettings.MAX_LYRIC_BLUR_RADIUS_OFFSET_PX,
                                ),
                        )
                    }
                }
            })
        }, matchWidthWrapContent())
    }

    /** SeekBar row for the gated liquid-glass extras, with a per-row one-tap default restore. */

internal fun EmbeddedSettingsHost.embeddedGlassRangeRow(
        activity: Activity,
        title: String,
        value: Int,
        defaultValue: Int,
        rangeMin: Int,
        rangeMax: Int,
        suffix: String,
        onChanged: (Int) -> Unit,
    ): View = LinearLayout(activity).apply {
        orientation = LinearLayout.VERTICAL
        val horizontalPadding = dp(activity, if (isEmbeddedPhone(activity)) 12 else 16)
        setPadding(
            horizontalPadding,
            dp(activity, if (isEmbeddedPhone(activity)) 8 else 10),
            horizontalPadding,
            dp(activity, if (isEmbeddedPhone(activity)) 6 else 8),
        )
        val label = TextView(activity).apply {
            text = localizedText(ModuleText.SETTING_VALUE, title, value, "dp")
            textSize = embeddedTextSize(activity, 14f, 15f)
            setEmbeddedTextColor(activity) { colors -> colors.onSurface }
        }
        // Small one-tap restore at the row's top-right corner.
        val reset = ImageView(activity).apply {
            setEmbeddedImageDrawable(activity,
                embeddedSvgDrawable(EmbeddedSvgIcon.RestoreDefault, activity) ?: EmbeddedGlyphDrawable(
                    EmbeddedGlyphKind.Refresh,
                    EmbeddedSettingsPalette.Light.accent,
                ),
            )
            scaleType = ImageView.ScaleType.CENTER_INSIDE
            contentDescription = localizedText(ModuleText.RESET_DEFAULT)
            isClickable = true
            isFocusable = true
            setPadding(dp(activity, 6), dp(activity, 4), 0, dp(activity, 4))
        }
        addView(LinearLayout(activity).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            addView(label, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
            addView(reset, LinearLayout.LayoutParams(dp(activity, 28), dp(activity, 28)))
        }, matchWidthWrapContent())
        addView(TextView(activity).apply {
            text = suffix
            textSize = embeddedTextSize(activity, 13f, 12f)
            setEmbeddedTextColor(activity) { colors -> colors.onSurfaceVariant }
            setSingleLine(false)
            maxLines = 2
            setPadding(0, dp(activity, 2), 0, 0)
        }, matchWidthWrapContent())
        val seekBar = SeekBar(activity).apply {
            bindEmbeddedSeekBarTheme(activity)
            max = rangeMax - rangeMin
            progress = value - rangeMin
            setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
                override fun onProgressChanged(seekBar: SeekBar?, progress: Int, fromUser: Boolean) {
                    label.text = localizedText(ModuleText.SETTING_VALUE, title,
                        (progress + rangeMin).coerceIn(rangeMin, rangeMax), "dp")
                }

                override fun onStartTrackingTouch(seekBar: SeekBar?) = Unit

                override fun onStopTrackingTouch(seekBar: SeekBar?) {
                    if (seekBar != null) {
                        onChanged((seekBar.progress + rangeMin).coerceIn(rangeMin, rangeMax))
                    }
                }
            })
        }
        addView(seekBar, matchWidthWrapContent())
        reset.setOnClickListener {
            label.text = localizedText(ModuleText.SETTING_VALUE, title, defaultValue, "dp")
            seekBar.progress = defaultValue - rangeMin
            onChanged(defaultValue)
        }
    }


internal fun EmbeddedSettingsHost.embeddedDpiOverrideRow(
        activity: Activity,
        value: Int,
        onChanged: (Int) -> Unit,
    ): View = embeddedNavigationRow(
        activity = activity,
        title = localizedText(ModuleText.MUSIC_DPI),
        summary = if (value == ModuleSettings.FOLLOW_SYSTEM_APPLE_MUSIC_DPI) {
            localizedText(ModuleText.MUSIC_DPI_DEFAULT_SUMMARY)
        } else {
            localizedText(ModuleText.MUSIC_DPI_VALUE_SUMMARY, value)
        },
        iconDrawable = EmbeddedGlyphDrawable(
            EmbeddedGlyphKind.VideoDisplay,
            EmbeddedSettingsPalette.Light.accent,
        ),
        inlineSummary = true,
        onClick = { showEmbeddedDpiOverrideDialog(activity, value, onChanged) },
    )


internal fun EmbeddedSettingsHost.showEmbeddedDpiOverrideDialog(
        activity: Activity,
        currentValue: Int,
        onSelected: (Int) -> Unit,
    ) {
        val input = embeddedLyricsEditorInput(
            activity = activity,
            hint = localizedText(ModuleText.MUSIC_DPI_INPUT_HINT),
            initial = currentValue.toString(),
            numeric = true,
        )
        val content = LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(
                dp(activity, if (isEmbeddedPhone(activity)) 4 else 8),
                0,
                dp(activity, if (isEmbeddedPhone(activity)) 4 else 8),
                0,
            )
            addView(input, matchWidthWrapContent())
        }
        val dialog = embeddedDialogBuilder(activity)
            .setTitle(localizedText(ModuleText.MUSIC_DPI))
            .setMessage(localizedText(ModuleText.MUSIC_DPI_NOTICE))
            .setView(content)
            .setNegativeButton(localizedText(ModuleText.CANCEL), null)
            .setPositiveButton(localizedText(ModuleText.SAVE), null)
            .create()
        dialog.setOnShowListener {
            dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
                val parsed = input.text?.toString()?.trim()?.toIntOrNull()
                if (parsed == null || !ModuleSettings.isValidAppleMusicDpi(parsed)) {
                    input.error = localizedText(ModuleText.MUSIC_DPI_INVALID)
                    return@setOnClickListener
                }
                onSelected(parsed)
                dialog.dismiss()
            }
        }
        dialog.show()
    }


internal fun EmbeddedSettingsHost.showEmbeddedRegionSelectionPicker(
        activity: Activity,
        onSelected: (RegionSelection) -> Unit,
    ) {
        val regions = RegionSelection.values()
        val current = controller.currentSettings().regionSelection
        val labels = regions.map(RegionSelection::displayName).toTypedArray()
        embeddedDialogBuilder(activity)
            // HLE title_apple_music_content_ui_language
            .setTitle(localizedText(ModuleText.REGION_SELECTION))
            .setSingleChoiceItems(labels, regions.indexOf(current)) { dialog, which ->
                regions.getOrNull(which)?.let(onSelected)
                dialog.dismiss()
            }
            .setNegativeButton(localizedText(ModuleText.CANCEL), null)
            .show()
    }


internal fun EmbeddedSettingsHost.embeddedNavigationRow(
        activity: Activity,
        title: String,
        summary: String,
        iconRes: Int? = null,
        iconTint: Int = EmbeddedSettingsPalette.Light.accent,
        iconDrawable: Drawable? = null,
        clickable: Boolean = true,
        inlineSummary: Boolean = false,
        // Selected value rendered on the right while [summary] still shows below the
        // title; used by the HLE region picker, whose title/summary come from HLE and
        // whose current option must stay visible.
        trailingValue: String? = null,
        compactWidePadding: Boolean = false,
        onClick: () -> Unit,
    ): View = LinearLayout(activity).apply {
        orientation = LinearLayout.HORIZONTAL
        gravity = Gravity.CENTER_VERTICAL
        minimumHeight = embeddedNavigationRowHeight(activity, compactWidePadding)
        val horizontalPadding = when {
            isEmbeddedPhone(activity) -> 12
            compactWidePadding -> 8
            else -> 12
        }
        isClickable = clickable
        isFocusable = clickable
        contentDescription = title
        setPadding(
            dp(activity, horizontalPadding),
            dp(activity, if (isEmbeddedPhone(activity)) 6 else 4),
            dp(activity, if (isEmbeddedPhone(activity)) 8 else 8),
            dp(activity, if (isEmbeddedPhone(activity)) 6 else 4),
        )
        if (clickable) setOnClickListener { onClick() }
        val resolvedIcon = iconDrawable
            ?: iconRes?.let { activity.getDrawable(it) }
            ?: EmbeddedGlyphDrawable(EmbeddedGlyphKind.Document, iconTint)
        addView(embeddedFeatureIcon(activity, resolvedIcon, iconTint), LinearLayout.LayoutParams(embeddedFeatureIconSize(activity), embeddedFeatureIconSize(activity)).apply {
            marginEnd = dp(activity, if (isEmbeddedPhone(activity)) 8 else 12)
        })
        addView(LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
            addView(TextView(activity).apply {
                text = title
                textSize = embeddedTextSize(activity, 16f, 14f)
                setEmbeddedTextColor(activity) { colors -> colors.onSurface }
                setTypeface(typeface, Typeface.NORMAL)
                setSingleLine(false)
                maxLines = 2
            }, matchWidthWrapContent())
            if (!inlineSummary) {
                addView(TextView(activity).apply {
                    text = summary
                    textSize = embeddedTextSize(activity, 13f, 12f)
                    setEmbeddedTextColor(activity) { colors -> colors.onSurfaceVariant }
                    setSingleLine(false)
                    maxLines = if (isEmbeddedPhone(activity)) 2 else 2
                    if (isEmbeddedPhone(activity)) {
                        ellipsize = android.text.TextUtils.TruncateAt.END
                    }
                    setPadding(0, dp(activity, 2), 0, 0)
                }, matchWidthWrapContent())
            }
        }, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        val valueText = if (inlineSummary) summary else trailingValue
        if (valueText != null) {
            addView(TextView(activity).apply {
                text = valueText
                textSize = embeddedTextSize(activity, 13f, 12f)
                setEmbeddedTextColor(activity) { colors -> colors.onSurfaceVariant }
                gravity = Gravity.CENTER_VERTICAL or Gravity.END
                setSingleLine(true)
                ellipsize = android.text.TextUtils.TruncateAt.END
            }, LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT,
                dp(activity, 44),
            ).apply { marginStart = dp(activity, 8) })
        }
        addView(ImageView(activity).apply {
            setEmbeddedImageDrawable(activity,
                EmbeddedGlyphDrawable(
                    EmbeddedGlyphKind.ChevronRight,
                    EmbeddedSettingsPalette.Light.onSurfaceVariant,
                ),
            )
            scaleType = ImageView.ScaleType.CENTER
            contentDescription = null
            minimumHeight = dp(activity, 44)
            setPadding(dp(activity, 8), dp(activity, 8), dp(activity, 8), dp(activity, 8))
        }, LinearLayout.LayoutParams(dp(activity, if (isEmbeddedPhone(activity)) 44 else 40), dp(activity, 44)))
    }


internal fun EmbeddedSettingsHost.embeddedActionButton(
        activity: Activity,
        label: String,
        enabled: Boolean = true,
        onClick: () -> Unit,
    ): Button = Button(activity).apply {
        bindEmbeddedButtonTheme(activity, tintBackground = false)
        text = label
        isAllCaps = false
        isEnabled = enabled
        alpha = if (enabled) 1f else 0.55f
        minHeight = dp(activity, 48)
        minimumHeight = dp(activity, 48)
        setEmbeddedTextColor(activity) { colors -> if (enabled) colors.accent else colors.disabledText }
        bindEmbeddedTheme(activity) { colors ->
            background = GradientDrawable().apply {
                setColor(if (enabled) colors.softSurface else colors.disabledSurface)
                cornerRadius = dp(activity, 12).toFloat()
            }
        }
        setOnClickListener { if (enabled) onClick() }
    }


internal fun EmbeddedSettingsHost.embeddedLyricsEditorButton(
        activity: Activity,
        label: String,
        onClick: () -> Unit,
        compact: Boolean = false,
    ): TextView = TextView(activity).apply {
        text = label
        textSize = embeddedTextSize(activity, 14f, 13f)
        includeFontPadding = false
        setEmbeddedTextColor(activity) { colors -> colors.accent }
        val horizontalPadding = dp(activity, if (compact) 2 else 6)
        setPadding(horizontalPadding, 0, horizontalPadding, 0)
        gravity = Gravity.CENTER
        isClickable = true
        isFocusable = true
        setSingleLine(true)
        if (compact) {
            setAutoSizeTextTypeUniformWithConfiguration(
                embeddedTextSize(activity, 10f, 10f).toInt(),
                embeddedTextSize(activity, 14f, 13f).toInt(),
                1,
                android.util.TypedValue.COMPLEX_UNIT_SP,
            )
        }
        setOnClickListener { onClick() }
    }


internal fun EmbeddedSettingsHost.embeddedLyricsEditorButtonRow(
        activity: Activity,
        actions: List<EmbeddedLyricsEditorAction>,
        useCompactLabels: Boolean = false,
    ): View = LinearLayout(activity).apply {
        orientation = LinearLayout.HORIZONTAL
        gravity = Gravity.CENTER
        setPadding(0, dp(activity, 2), 0, dp(activity, 2))
        val compactRow = actions.size >= 3
        actions.forEachIndexed { index, action ->
            val renderedLabel = if (useCompactLabels) action.compactLabel else action.label
            val button = embeddedLyricsEditorButton(
                activity,
                renderedLabel,
                action.onClick,
                compactRow,
            ).apply { contentDescription = action.label }
            addView(
                button,
                if (compactRow) {
                    LinearLayout.LayoutParams(0, dp(activity, 32), 1f)
                } else {
                    LinearLayout.LayoutParams(
                        ViewGroup.LayoutParams.WRAP_CONTENT,
                        dp(activity, 32),
                    )
                },
            )
            if (index < actions.lastIndex) {
                addView(TextView(activity).apply {
                    text = "|"
                    textSize = embeddedTextSize(activity, 14f, 13f)
                    includeFontPadding = false
                    gravity = Gravity.CENTER
                    setEmbeddedTextColor(activity) { colors -> colors.onSurfaceVariant }
                    setPadding(dp(activity, 2), 0, dp(activity, 2), 0)
                    isClickable = false
                }, LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.WRAP_CONTENT,
                    dp(activity, 32),
                ))
            }
        }
    }


internal fun EmbeddedSettingsHost.embeddedLyricsEditorActionRows(
        activity: Activity,
        actions: List<EmbeddedLyricsEditorAction>,
    ): View = LinearLayout(activity).apply {
        orientation = LinearLayout.VERTICAL
        val density = activity.resources.displayMetrics.density.coerceAtLeast(1f)
        val fontScale = activity.resources.configuration.fontScale.coerceAtLeast(1f)
        val dialogWidthDp = embeddedLyricsEditorDialogWidth(activity) / density
        val horizontalInsetDp = if (isEmbeddedPhone(activity)) 40f else 48f
        val usableWidthDp = (dialogWidthDp - horizontalInsetDp).coerceAtLeast(1f)
        val rows = if (
            actions.size <= 2 || usableWidthDp / fontScale >= 560f
        ) {
            listOf(actions)
        } else {
            listOf(actions.take(2), actions.drop(2))
        }
        rows.forEachIndexed { rowIndex, rowActions ->
            addView(
                embeddedLyricsEditorButtonRow(
                    activity = activity,
                    actions = rowActions,
                    useCompactLabels = rows.size > 1 && rowIndex == rows.lastIndex,
                ),
                matchWidthWrapContent(),
            )
        }
    }


internal fun EmbeddedSettingsHost.embeddedLyricsEditorInput(
        activity: Activity,
        hint: String,
        initial: String = "",
        numeric: Boolean = false,
        multiline: Boolean = false,
    ): EditText = EditText(activity).apply {
        bindEmbeddedInputTheme(activity)
        this.hint = hint
        setText(initial)
        textSize = embeddedTextSize(activity, 17f, 16f)
        includeFontPadding = false
        setEmbeddedTextColor(activity) { colors -> colors.onSurface }
        setEmbeddedHintColor(activity) { colors -> colors.onSurfaceVariant }

        inputType = when {
            numeric -> InputType.TYPE_CLASS_NUMBER
            multiline -> InputType.TYPE_CLASS_TEXT or
                InputType.TYPE_TEXT_FLAG_MULTI_LINE or InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS
            else -> InputType.TYPE_CLASS_TEXT
        }
        if (multiline) {
            minLines = if (isEmbeddedPhone(activity)) 9 else 11
            maxLines = if (isEmbeddedPhone(activity)) 14 else 18
            gravity = Gravity.TOP or Gravity.START
            typeface = Typeface.MONOSPACE
            isVerticalScrollBarEnabled = true
            scrollBarStyle = View.SCROLLBARS_INSIDE_INSET
            setHorizontallyScrolling(false)
            setPadding(0, dp(activity, 8), 0, dp(activity, 8))
        } else {
            isSingleLine = true
            minHeight = dp(activity, 56)
            minimumHeight = dp(activity, 56)
            setPadding(0, dp(activity, 4), 0, dp(activity, 2))
        }
    }




internal fun EmbeddedSettingsHost.embeddedIconActionButton(
        activity: Activity,
        label: String,
        iconRes: Int,
        onClick: () -> Unit,
    ): View = embeddedIconActionButton(activity, label, activity.getDrawable(iconRes), onClick)


internal fun EmbeddedSettingsHost.embeddedCompactLyricsActionBar(
        activity: Activity,
        onAdd: () -> Unit,
        onTtml: () -> Unit,
        onUpdate: () -> Unit,
        onBackup: (View) -> Unit,
    ): View {
        val container = LinearLayout(activity).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(activity, 3), dp(activity, 4), dp(activity, 3), dp(activity, 4))
            bindEmbeddedTheme(activity) { colors ->
                background = GradientDrawable().apply {
                    setColor(colors.softSurface)
                    cornerRadius = dp(activity, 10).toFloat()
                    setStroke(
                        dp(activity, 1),
                        colors.actionOutline,
                    )
                }
            }
            contentDescription = localizedText(ModuleText.LYRICS_ACTIONS)
        }

        fun addAction(
            label: String,
            description: String,
            icon: Drawable?,
            onClick: (View) -> Unit,
        ) {
            val item = embeddedCompactLyricsActionItem(
                activity = activity,
                label = label,
                description = description,
                icon = icon,
                onClick = onClick,
            )
            container.addView(item, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.MATCH_PARENT, 1f))
        }

        addAction(
            label = localizedText(ModuleText.ADD),
            description = localizedText(ModuleText.ADD_LYRICS),
            icon = embeddedSvgDrawable(EmbeddedSvgIcon.AddLyrics, activity) ?: EmbeddedGlyphDrawable(
                EmbeddedGlyphKind.AddCircle,
                EmbeddedSettingsPalette.Light.accent,
                strokeWidthFraction = 0.055f,
            ),
            onClick = { onAdd() },
        )
        container.addView(embeddedCompactLyricsActionDivider(activity))
        addAction(
            label = "TTML",
            description = localizedText(ModuleText.IMPORT_TTML),
            icon = embeddedSvgDrawable(EmbeddedSvgIcon.ImportTtml, activity) ?: EmbeddedGlyphDrawable(
                EmbeddedGlyphKind.TtmlDocument,
                EmbeddedSettingsPalette.Light.accent,
                strokeWidthFraction = 0.055f,
            ),
            onClick = { onTtml() },
        )
        container.addView(embeddedCompactLyricsActionDivider(activity))
        addAction(
            label = localizedText(ModuleText.UPDATE),
            description = localizedText(ModuleText.LYRICS_UPDATE),
            icon = EmbeddedGlyphDrawable(
                EmbeddedGlyphKind.Refresh,
                EmbeddedSettingsPalette.Light.accent,
            ),
            onClick = { onUpdate() },
        )
        container.addView(embeddedCompactLyricsActionDivider(activity))
        addAction(
            label = localizedText(ModuleText.BACKUP),
            description = localizedText(ModuleText.BACKUP_AND_RESTORE),
            icon = embeddedSvgDrawable(EmbeddedSvgIcon.BackupRestore, activity) ?: EmbeddedGlyphDrawable(
                EmbeddedGlyphKind.CloudBackup,
                EmbeddedSettingsPalette.Light.accent,
                strokeWidthFraction = 0.055f,
            ),
            onClick = { anchor -> onBackup(anchor) },
        )
        return container
    }


internal fun EmbeddedSettingsHost.embeddedCompactLyricsActionItem(
        activity: Activity,
        label: String,
        description: String,
        icon: Drawable?,
        onClick: (View) -> Unit,
    ): View = LinearLayout(activity).apply {
        orientation = LinearLayout.VERTICAL
        gravity = Gravity.CENTER
        isClickable = true
        isFocusable = true
        contentDescription = description
        val glyphSize = dp(activity, 22)
        addView(ImageView(activity).apply {
            setEmbeddedImageDrawable(activity, icon)
            scaleType = ImageView.ScaleType.CENTER_INSIDE
            bindEmbeddedIconTint(activity, EmbeddedSettingsPalette.Light.accent)
            contentDescription = null
        }, LinearLayout.LayoutParams(glyphSize, glyphSize))
        addView(TextView(activity).apply {
            text = label
            textSize = embeddedTextSize(activity, 11.5f, 11f)
            includeFontPadding = false
            gravity = Gravity.CENTER
            setEmbeddedTextColor(activity) { colors -> colors.accent }
            setTypeface(typeface, Typeface.NORMAL)
            setSingleLine(true)
            ellipsize = android.text.TextUtils.TruncateAt.END
        }, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(activity, 20)))
        setOnClickListener { onClick(this) }
    }


internal fun EmbeddedSettingsHost.embeddedCompactLyricsActionDivider(activity: Activity): View = View(activity).apply {
        setEmbeddedBackgroundColor(activity) { it.actionDivider }
        layoutParams = LinearLayout.LayoutParams(
            dp(activity, 1),
            dp(activity, 42),
        ).apply { gravity = Gravity.CENTER_VERTICAL }
        contentDescription = null
    }


internal fun EmbeddedSettingsHost.embeddedIconActionButton(
        activity: Activity,
        label: String,
        icon: Drawable?,
        onClick: () -> Unit,
    ): View = LinearLayout(activity).apply {
        orientation = LinearLayout.VERTICAL
        gravity = Gravity.CENTER
        isClickable = true
        isFocusable = true
        minimumHeight = dp(activity, if (isEmbeddedPhone(activity)) 64 else 56)
        contentDescription = label
        bindEmbeddedTheme(activity) { colors ->
            background = GradientDrawable().apply {
                setColor(colors.softSurface)
                cornerRadius = dp(activity, if (isEmbeddedPhone(activity)) 8 else 7).toFloat()
            }
        }
        val glyphSize = embeddedActionGlyphSize(activity)
        addView(ImageView(activity).apply {
            setEmbeddedImageDrawable(activity, icon)
            bindEmbeddedIconTint(activity, EmbeddedSettingsPalette.Light.accent)
            contentDescription = null
            setPadding(dp(activity, 2), dp(activity, 2), dp(activity, 2), dp(activity, 2))
        }, LinearLayout.LayoutParams(
            glyphSize,
            glyphSize,
        ))
        addView(TextView(activity).apply {
            text = label
            textSize = embeddedTextSize(activity, 14f, 12.25f)
            gravity = Gravity.CENTER
            setEmbeddedTextColor(activity) { colors -> colors.accent }
            setTypeface(typeface, Typeface.NORMAL)
            setSingleLine(false)
            maxLines = 2
            setPadding(0, dp(activity, if (isEmbeddedPhone(activity)) 2 else 3), 0, 0)
            translationY = if (isEmbeddedPhone(activity)) 0f else -dp(activity, 3).toFloat()
        }, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        setOnClickListener { onClick() }
    }


internal fun EmbeddedSettingsHost.embeddedEntryActionButton(
        activity: Activity,
        label: String,
        icon: Drawable?,
        tint: Int,
        onClick: () -> Unit,
    ): View = LinearLayout(activity).apply {
        orientation = LinearLayout.HORIZONTAL
        gravity = Gravity.CENTER
        isClickable = true
        isFocusable = true
        contentDescription = label
        bindEmbeddedTheme(activity) { colors ->
            background = GradientDrawable().apply {
                setColor(colors.softSurface)
                cornerRadius = dp(activity, 8).toFloat()
            }
        }
        addView(ImageView(activity).apply {
            setEmbeddedImageDrawable(activity, icon)
            bindEmbeddedIconTint(activity, tint)
            contentDescription = null
            setPadding(dp(activity, 2), dp(activity, 2), dp(activity, 2), dp(activity, 2))
        }, LinearLayout.LayoutParams(dp(activity, 24), dp(activity, 24)).apply {
            marginEnd = dp(activity, 8)
        })
        addView(TextView(activity).apply {
            text = label
            textSize = embeddedTextSize(activity, 14f, 13f)
            setEmbeddedTextColor(activity) { it.iconColor(tint) }
            setTypeface(typeface, Typeface.NORMAL)
            gravity = Gravity.CENTER_VERTICAL
        }, LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, dp(activity, 48)))
        setOnClickListener { onClick() }
    }


internal fun EmbeddedSettingsHost.embeddedIconActionButtonParams(
        activity: Activity,
        wideWeight: Float = 1f,
    ): LinearLayout.LayoutParams =
        if (isEmbeddedPhone(activity)) {
            LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(activity, 80))
        } else {
            LinearLayout.LayoutParams(0, dp(activity, 75), wideWeight)
        }


internal fun EmbeddedSettingsHost.embeddedActionGlyphSize(activity: Activity): Int =
        dp(activity, if (isEmbeddedPhone(activity)) 28 else 36)


internal fun EmbeddedSettingsHost.showEmbeddedBackupRestoreMenu(activity: Activity, anchor: View) {
        refreshEmbeddedTheme(activity)
        PopupMenu(embeddedThemedContext(activity), anchor).apply {
            menu.add(localizedText(ModuleText.BACKUP_LYRICS)).setOnMenuItemClickListener {
                launchSafPicker(activity, EmbeddedSafOperation.Backup, "application/zip")
                true
            }
            menu.add(localizedText(ModuleText.RESTORE_BACKUP)).setOnMenuItemClickListener {
                launchSafPicker(
                    activity,
                    EmbeddedSafOperation.RestoreOverwrite,
                    "*/*",
                    arrayOf(
                        "application/zip",
                        "application/x-zip-compressed",
                        "application/octet-stream",
                    ),
                )
                true
            }
            show()
        }
    }


internal fun EmbeddedSettingsHost.embeddedActionSpacer(activity: Activity): View = View(activity).apply {
        layoutParams = if (isEmbeddedPhone(activity)) {
            LinearLayout.LayoutParams(dp(activity, 1), dp(activity, 8))
        } else {
            LinearLayout.LayoutParams(dp(activity, 8), dp(activity, 1))
        }
    }


internal fun EmbeddedSettingsHost.embeddedSpacer(activity: Activity, height: Int): View = View(activity).apply {
        layoutParams = LinearLayout.LayoutParams(1, dp(activity, height))
    }


internal fun EmbeddedSettingsHost.embeddedDivider(activity: Activity): View = View(activity).apply {
        setEmbeddedBackgroundColor(activity) { colors -> colors.divider }
        layoutParams = LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT,
            dp(activity, 1),
        ).apply {
            marginStart = dp(activity, 16)
            marginEnd = dp(activity, 16)
        }
    }

    /** Divider for the compact lyrics header: aligns to the reference row edge. */

internal fun EmbeddedSettingsHost.embeddedCustomLyricsDivider(activity: Activity): View = View(activity).apply {
        setEmbeddedBackgroundColor(activity) { colors -> colors.divider }
        layoutParams = LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT,
            dp(activity, 1),
        ).apply {
            val inset = dp(activity, if (isEmbeddedPhone(activity)) 16 else 6)
            marginStart = inset
            marginEnd = inset
        }
    }


internal fun EmbeddedSettingsHost.addSwitch(
        parent: LinearLayout,
        activity: Activity,
        label: String,
        checked: Boolean,
    ): Switch = Switch(activity).apply {
        text = label
        isChecked = checked
        minimumHeight = dp(activity, 48)
        bindEmbeddedSwitchTheme(activity)
        setPadding(0, dp(activity, 5), 0, dp(activity, 5))
        parent.addView(this, matchWidthWrapContent())
    }


internal fun EmbeddedSettingsHost.addFileButton(
        parent: LinearLayout,
        activity: Activity,
        label: String,
        operation: EmbeddedSafOperation,
        mimeType: String,
    ) {
        Button(activity).apply {
            bindEmbeddedButtonTheme(activity, tintBackground = false)
            text = label
            isAllCaps = false
            minHeight = dp(activity, 48)
            minimumHeight = dp(activity, 48)
            setEmbeddedTextColor(activity) { colors -> colors.accent }
            bindEmbeddedTheme(activity) { colors ->
                background = GradientDrawable().apply {
                    setColor(colors.softSurface)
                    cornerRadius = dp(activity, 12).toFloat()
                }
            }
            setOnClickListener { launchSafPicker(activity, operation, mimeType) }
            parent.addView(this, matchWidthWrapContent())
        }
    }


internal fun EmbeddedSettingsHost.addLyricsManagement(
        parent: LinearLayout,
        activity: Activity,
        song: CurrentSongDetails?,
    ) {
        parent.addView(TextView(activity).apply {
            text = localizedText(ModuleText.CUSTOM_LYRICS_MANAGEMENT)
            textSize = embeddedTextSize(activity, 18f, 17f)
            setEmbeddedTextColor(activity) { colors -> colors.onSurface }
            setSingleLine(false)
            setPadding(0, dp(activity, 18), 0, dp(activity, 6))
        }, matchWidthWrapContent())

        Button(activity).apply {

            bindEmbeddedButtonTheme(activity)
            text = localizedText(ModuleText.ADD_LYRICS_MANUALLY)
            setOnClickListener { showLyricsEditor(activity, null as CustomLyricsUiGroup?, song) }
            parent.addView(this, matchWidthWrapContent())
        }
        if (song != null) {
            val onlineRow = LinearLayout(activity).apply {
                orientation = embeddedActionOrientation(activity)
            }
            listOf(
                "AMLL" to EmbeddedOnlineSource.AMLL,
                "AM Lyrics" to EmbeddedOnlineSource.AM_LYRICS,
                "Lunabeat" to EmbeddedOnlineSource.LUNABEAT,
            ).forEach { (label, source) ->
                onlineRow.addView(Button(activity).apply {
                    bindEmbeddedButtonTheme(activity)
                    text = label
                    setOnClickListener {
                        runAsync(activity) {
                            controller.importOnlineLyrics(
                                source,
                                song.appleMusicId,
                                song.title.orEmpty().ifBlank { song.appleMusicId.toString() },
                            )
                        }
                    }
                }, embeddedActionButtonParams(activity))
            }
            parent.addView(onlineRow, matchWidthWrapContent())
        }

        val entries = runCatching(controller::lyricsEntries).getOrDefault(emptyList())
        if (entries.isEmpty()) {
            parent.addView(TextView(activity).apply {
                text = localizedText(ModuleText.NO_CUSTOM_LYRICS)
                setEmbeddedTextColor(activity) { colors -> colors.onSurfaceVariant }
            }, matchWidthWrapContent())
        }
        entries.forEach { entry ->
            val row = LinearLayout(activity).apply {
                orientation = LinearLayout.VERTICAL
                setPadding(0, dp(activity, 6), 0, dp(activity, 6))
            }
            row.addView(TextView(activity).apply {
                text = "${entry.displayName.ifBlank { entry.appleMusicId.toString() }}  ·  ${entry.appleMusicId}"
                setEmbeddedTextColor(activity) { colors -> colors.onSurface }
                setSingleLine(false)
            }, matchWidthWrapContent())
            val actions = LinearLayout(activity).apply {
                orientation = embeddedActionOrientation(activity)
            }
            actions.addView(Button(activity).apply {
                bindEmbeddedButtonTheme(activity)
                text = localizedText(ModuleText.EDIT)
                minHeight = dp(activity, 48)
                setOnClickListener { showLyricsEditor(activity, entry, song) }
            }, embeddedActionButtonParams(activity))
            actions.addView(Button(activity).apply {
                bindEmbeddedButtonTheme(activity)
                text = if (entry.enabled) localizedText(ModuleText.DISABLE) else localizedText(ModuleText.ENABLE)
                minHeight = dp(activity, 48)
                setOnClickListener {
                    runAsync(activity) { controller.setLyricsEnabled(entry.appleMusicId, !entry.enabled) }
                }
            }, embeddedActionButtonParams(activity))
            actions.addView(Button(activity).apply {
                bindEmbeddedButtonTheme(activity)
                text = localizedText(ModuleText.DELETE)
                minHeight = dp(activity, 48)
                setOnClickListener {
                    embeddedDialogBuilder(activity)
                        .setMessage(localizedText(ModuleText.DELETE_NAMED_ITEM, entry.displayName.ifBlank { entry.appleMusicId.toString() }))
                        .setNegativeButton(localizedText(ModuleText.CANCEL), null)
                        .setPositiveButton(localizedText(ModuleText.DELETE)) { _, _ ->
                            runAsync(activity) { controller.deleteLyrics(entry.appleMusicId) }
                        }
                        .show()
                }
            }, embeddedActionButtonParams(activity))
            row.addView(actions, matchWidthWrapContent())
            parent.addView(row, matchWidthWrapContent())
        }
    }


internal fun EmbeddedSettingsHost.embeddedSvgDrawable(icon: EmbeddedSvgIcon, activity: Activity): Drawable? {
    val colors = embeddedPalette(activity)
    return EmbeddedSettingsSvgAssets.drawable(icon, if (colors.isDark) embeddedSvgColor(icon, colors) else null)
}


internal fun EmbeddedSettingsHost.loadEmbeddedArrowIcon(context: Context): Drawable = sequenceOf(
        ModuleConstants.MODULE_PACKAGE,
        "${ModuleConstants.MODULE_PACKAGE}.debug",
    ).mapNotNull { packageName ->
        runCatching {
            val moduleContext = context.createPackageContext(
                packageName,
                Context.CONTEXT_IGNORE_SECURITY,
            )
            moduleContext.resources.getDrawable(
                dev.amenhancer.module.R.drawable.ic_arrow_back,
                moduleContext.theme,
            )
        }.getOrNull()
    }.firstOrNull() ?: EmbeddedArrowFallbackDrawable()


internal fun EmbeddedSettingsHost.embeddedWidthDp(activity: Activity): Float {
        val widthDp = activity.resources.configuration.screenWidthDp
        if (widthDp > 0) return widthDp.toFloat()
        val density = activity.resources.displayMetrics.density.coerceAtLeast(1f)
        return activity.resources.displayMetrics.widthPixels / density
    }


internal fun EmbeddedSettingsHost.isEmbeddedPhone(activity: Activity): Boolean = embeddedWidthDp(activity) < 600f


internal fun EmbeddedSettingsHost.embeddedTextSize(activity: Activity, phone: Float, wide: Float): Float =
        if (isEmbeddedPhone(activity)) phone else wide

    /**
     * The supplied reference is a 1280dp-wide tablet composition: 626dp for
     * the main panel and 503dp for the lyrics page.  Phones keep Android's
     * normal dialog sizing so text and touch targets remain usable.
     */

internal fun EmbeddedSettingsHost.embeddedDialogWidth(activity: Activity, page: EmbeddedSettingsPage): Int? {
        if (isEmbeddedPhone(activity)) return null
        // AlertDialog applies a 16dp inset on each side. These fractions target
        // the visible white panel after that inset, not the outer window.
        val fraction = if (page == EmbeddedSettingsPage.CUSTOM_LYRICS) 0.418f else 0.514f
        return (activity.resources.displayMetrics.widthPixels * fraction).toInt()
    }


internal fun EmbeddedSettingsHost.embeddedDialogContentHeight(activity: Activity, page: EmbeddedSettingsPage): Int {
        val height = activity.resources.displayMetrics.heightPixels
        if (isEmbeddedPhone(activity)) return (height * 0.70f).toInt()
        val fraction = if (page == EmbeddedSettingsPage.CUSTOM_LYRICS) 0.888f else 0.837f
        return (height * fraction).toInt()
    }


internal fun EmbeddedSettingsHost.embeddedLyricsEditorDialogWidth(activity: Activity): Int {
        val targetWidthDp = embeddedWidthDp(activity) *
            if (isEmbeddedPhone(activity)) 0.88f else 0.62f
        return dp(activity, targetWidthDp.toInt())
    }


internal fun EmbeddedSettingsHost.embeddedLyricsEditorDialogHeight(activity: Activity): Int =
        embeddedDialogContentHeight(activity, EmbeddedSettingsPage.CUSTOM_LYRICS)


internal fun EmbeddedSettingsHost.embeddedTopBarHeight(activity: Activity): Int =
        dp(activity, if (isEmbeddedPhone(activity)) 60 else 52)


internal fun EmbeddedSettingsHost.embeddedHeaderIconSize(activity: Activity): Int =
        dp(activity, if (isEmbeddedPhone(activity)) 40 else 34)


internal fun EmbeddedSettingsHost.embeddedFeatureIconSize(activity: Activity): Int =
        dp(activity, if (isEmbeddedPhone(activity)) 40 else 36)


internal fun EmbeddedSettingsHost.embeddedSettingRowHeight(activity: Activity, compactWide: Boolean = false): Int =
        dp(activity, if (isEmbeddedPhone(activity)) 72 else if (compactWide) 68 else 56)


internal fun EmbeddedSettingsHost.embeddedNavigationRowHeight(activity: Activity, compactWide: Boolean = false): Int =
        dp(activity, if (isEmbeddedPhone(activity)) 72 else if (compactWide) 60 else 52)


internal fun EmbeddedSettingsHost.embeddedSearchFieldHeight(activity: Activity): Int =
        dp(activity, if (isEmbeddedPhone(activity)) 48 else 44)


internal fun EmbeddedSettingsHost.embeddedActionOrientation(activity: Activity): Int =
        if (isEmbeddedPhone(activity)) LinearLayout.VERTICAL else LinearLayout.HORIZONTAL


internal fun EmbeddedSettingsHost.embeddedActionButtonParams(activity: Activity): LinearLayout.LayoutParams =
        if (isEmbeddedPhone(activity)) {
            LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(activity, 48))
        } else {
            LinearLayout.LayoutParams(0, dp(activity, 48), 1f)
        }






internal fun EmbeddedSettingsHost.dp(activity: Activity, value: Int): Int =
        (value * activity.resources.displayMetrics.density).toInt()


internal fun EmbeddedSettingsHost.embeddedCardCornerRadius(activity: Activity): Float =
        dp(activity, 8).toFloat()


internal fun EmbeddedSettingsHost.matchWidthWrapContent(): LinearLayout.LayoutParams =
        LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT,
            ViewGroup.LayoutParams.WRAP_CONTENT,
        )

