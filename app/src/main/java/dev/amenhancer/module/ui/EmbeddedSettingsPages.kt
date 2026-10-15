package dev.amenhancer.module.ui

import dev.amenhancer.module.i18n.ModuleText

import android.app.Activity
import android.app.AlertDialog
import android.app.Dialog
import android.content.Context
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.text.Editable
import android.text.InputType
import android.text.TextWatcher
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.view.inputmethod.EditorInfo
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.PopupMenu
import android.widget.ScrollView
import android.widget.Switch
import android.widget.TextView
import android.widget.Toast
import dev.amenhancer.glass.GlassPolicy
import dev.amenhancer.module.CurrentSongDetails
import dev.amenhancer.module.model.CustomLyricsSources
import dev.amenhancer.module.model.ModuleSettings
import dev.amenhancer.module.model.OnlineLyricSources
import java.lang.ref.WeakReference

internal fun EmbeddedSettingsHost.showSettingsDialog(activity: Activity) {
        val currentDialog = dialogReference?.get()
        if (currentDialog?.isShowing == true) return

        val initialSettings = runCatching { controller.currentSettings() }.getOrElse {
            Toast.makeText(activity, localizedText(ModuleText.SETTINGS_READ_FAILED), Toast.LENGTH_SHORT).show()
            return
        }
        val draft = EmbeddedSettingsDraft(initialSettings, controller::saveOrdinarySettings)
        var page = EmbeddedSettingsPage.MAIN
        var dialogReady = false
        lateinit var dialog: AlertDialog

        val pageHost = LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
            val hostInset = dp(activity, if (isEmbeddedPhone(activity)) 0 else 8)
            setPadding(hostInset, 0, hostInset, 0)
            setEmbeddedBackgroundColor(activity) { colors -> colors.pageBackground }
        }
        val topBar = LinearLayout(activity).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            minimumHeight = embeddedTopBarHeight(activity)
            val phoneHeaderInset = dp(activity, if (isEmbeddedPhone(activity)) 8 else 0)
            setPadding(phoneHeaderInset, 0, phoneHeaderInset, 0)
        }
        val backButton = ImageView(activity).apply {
            setEmbeddedImageDrawable(activity,
                embeddedSvgDrawable(EmbeddedSvgIcon.Back, activity) ?: EmbeddedGlyphDrawable(
                    EmbeddedGlyphKind.BackArrow,
                    EmbeddedSettingsPalette.Light.primary,
                    strokeWidthFraction = 0.055f,
                ),
            )
            scaleType = ImageView.ScaleType.CENTER_INSIDE
            contentDescription = localizedText(ModuleText.BACK)
            isClickable = true
            isFocusable = true
            setPadding(dp(activity, 8), dp(activity, 8), dp(activity, 8), dp(activity, 8))
        }
        val moduleIcon = ImageView(activity).apply {
            setEmbeddedImageDrawable(activity, EmbeddedAmppBrandDrawable())
            contentDescription = "AM++"
            scaleType = ImageView.ScaleType.CENTER_INSIDE
            setPadding(0, 0, 0, 0)
        }
        val pageTitle = TextView(activity).apply {
            textSize = embeddedTextSize(activity, 19f, 18f)
            setEmbeddedTextColor(activity) { colors -> colors.onSurface }
            setTypeface(typeface, if (isEmbeddedPhone(activity)) Typeface.BOLD else Typeface.NORMAL)
            setSingleLine(false)
            maxLines = 2
            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
        }
        val saveButton = TextView(activity).apply {
            text = localizedText(ModuleText.SAVE)
            textSize = embeddedTextSize(activity, 15f, 14f)
            gravity = Gravity.CENTER
            setEmbeddedTextColor(activity) { colors -> colors.primary }
            isClickable = true
            isFocusable = true
            setPadding(dp(activity, 12), dp(activity, 8), dp(activity, 8), dp(activity, 8))
            contentDescription = localizedText(ModuleText.SAVE_SETTINGS)
        }
        topBar.addView(backButton, LinearLayout.LayoutParams(dp(activity, 44), embeddedTopBarHeight(activity)))
        topBar.addView(moduleIcon, LinearLayout.LayoutParams(embeddedHeaderIconSize(activity), embeddedHeaderIconSize(activity)).apply {
            marginStart = dp(activity, if (isEmbeddedPhone(activity)) 0 else 8)
            marginEnd = dp(activity, 8)
        })
        topBar.addView(pageTitle)
        topBar.addView(saveButton, LinearLayout.LayoutParams(dp(activity, 56), embeddedTopBarHeight(activity)))
        pageHost.addView(topBar, LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT,
            embeddedTopBarHeight(activity),
        ))
        val headerDivider = View(activity).apply {
            setEmbeddedBackgroundColor(activity) { colors -> colors.divider }
            visibility = View.GONE
        }
        pageHost.addView(headerDivider, LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT,
            dp(activity, 1),
        ))
        val pageContent = FrameLayout(activity)
        pageHost.addView(pageContent, LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT,
            0,
            1f,
        ))

        // Keep the close action inside our content tree.  AlertDialog's default
        // button panel adds theme-dependent padding that made the phone layout
        // look like it had an oversized blank footer.
        val closeBar = LinearLayout(activity).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL or Gravity.END
            setEmbeddedBackgroundColor(activity) { colors -> colors.pageBackground }
            val horizontalPadding = dp(activity, if (isEmbeddedPhone(activity)) 16 else 12)
            setPadding(horizontalPadding, 0, horizontalPadding, 0)
        }
        val closeButton = TextView(activity).apply {
            text = localizedText(ModuleText.CLOSE)
            textSize = embeddedTextSize(activity, 16f, 14f)
            gravity = Gravity.CENTER
            setEmbeddedTextColor(activity) { colors -> colors.primary }
            isClickable = true
            isFocusable = true
            contentDescription = localizedText(ModuleText.CLOSE_SETTINGS)
            setOnClickListener { dialog.dismiss() }
        }
        closeBar.addView(closeButton, LinearLayout.LayoutParams(
            dp(activity, if (isEmbeddedPhone(activity)) 64 else 56),
            dp(activity, if (isEmbeddedPhone(activity)) 56 else 48),
        ))

        val root = LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
            bindEmbeddedTheme(activity) { colors ->
                background = GradientDrawable().apply {
                    setColor(colors.pageBackground)
                    cornerRadius = embeddedCardCornerRadius(activity)
                }
            }
            clipToOutline = true
            addView(pageHost, LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                0,
                1f,
            ))
            addView(closeBar, LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                dp(activity, if (isEmbeddedPhone(activity)) 56 else 48),
            ))
        }

        fun saveDraft(close: Boolean) {
            if (draft.save()) {
                Toast.makeText(activity, localizedText(ModuleText.SETTINGS_SAVED), Toast.LENGTH_LONG).show()
                if (close) dialog.dismiss()
            } else {
                Toast.makeText(activity, localizedText(ModuleText.SETTINGS_SAVE_FAILED), Toast.LENGTH_SHORT).show()
            }
        }

        fun updateDraft(next: ModuleSettings) {
            if (!draft.update(next)) {
                Toast.makeText(activity, localizedText(ModuleText.SETTINGS_SAVE_FAILED), Toast.LENGTH_SHORT).show()
            }
        }

        fun syncBottomCloseButton() {
            closeBar.visibility = if (page == EmbeddedSettingsPage.MAIN) View.VISIBLE else View.GONE
        }

        fun syncDialogLayout() {
            if (!dialogReady || !dialog.isShowing) return
            embeddedDialogWidth(activity, page)?.let { width ->
                dialog.window?.setLayout(width, ViewGroup.LayoutParams.WRAP_CONTENT)
            }
        }

        fun renderPage() {
            root.minimumHeight = embeddedDialogContentHeight(activity, page)
            pageContent.removeAllViews()
            val scroll = ScrollView(activity).apply {
                isFillViewport = true
                isVerticalScrollBarEnabled = false
        val horizontalInset = if (page == EmbeddedSettingsPage.CUSTOM_LYRICS) 4 else 8
                setPadding(
                    dp(activity, horizontalInset),
                    0,
                    dp(activity, horizontalInset),
                    dp(activity, if (page == EmbeddedSettingsPage.CUSTOM_LYRICS) 8 else 12),
                )
            }
            val content = LinearLayout(activity).apply {
                orientation = LinearLayout.VERTICAL
            }
            scroll.addView(content)
            pageContent.addView(scroll, FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT,
            ))

            val customLyricsPage = page == EmbeddedSettingsPage.CUSTOM_LYRICS
            pageTitle.text = if (customLyricsPage) localizedText(ModuleText.CUSTOM_LYRICS) else "AM++"
            (pageTitle.layoutParams as? LinearLayout.LayoutParams)?.let { params ->
                params.marginStart = dp(activity, if (customLyricsPage && !isEmbeddedPhone(activity)) 20 else 0)
                pageTitle.layoutParams = params
            }
            backButton.visibility = if (customLyricsPage) View.VISIBLE else View.GONE
            moduleIcon.visibility = if (customLyricsPage) View.GONE else View.VISIBLE
            headerDivider.visibility = if (customLyricsPage) View.VISIBLE else View.GONE
            if (customLyricsPage) {
                renderEmbeddedCustomLyricsPage(
                    activity = activity,
                    parent = content,
                    settings = draft.settings,
                    song = controller.currentSongDetails(),
                    onSettingsChanged = ::updateDraft,
                )
            } else {
                renderEmbeddedMainPage(
                    activity = activity,
                    parent = content,
                    settings = draft.settings,
                    lyricsCount = runCatching { controller.lyricsEntries().size }.getOrDefault(0),
                    onSettingsChanged = ::updateDraft,
                    onCellularDataEntryChanged = { enabled ->
                        if (!draft.updateCellularDataEntry(enabled)) {
                            Toast.makeText(activity, localizedText(ModuleText.SETTINGS_SAVE_FAILED), Toast.LENGTH_SHORT).show()
                        }
                    },
                    onOpenCustomLyrics = {
                        page = EmbeddedSettingsPage.CUSTOM_LYRICS
                        renderPage()
                    },
                    onChooseFont = {
                        launchSafPicker(
                            activity,
                            EmbeddedSafOperation.Font,
                            "*/*",
                            EMBEDDED_FONT_MIME_TYPES,
                        )
                    },
                    onClearFont = { runAsync(activity, controller::clearFont) },
                )
            }
            syncBottomCloseButton()
            syncDialogLayout()
            refreshEmbeddedTheme(activity)
        }

        backButton.setOnClickListener {
            if (page == EmbeddedSettingsPage.CUSTOM_LYRICS) {
                page = EmbeddedSettingsPage.MAIN
                renderPage()
            } else {
                dialog.dismiss()
            }
        }
        saveButton.setOnClickListener { saveDraft(close = true) }
        dialog = embeddedDialogBuilder(activity, customPanel = true)
            .setView(root)
            .create()
        dialog.setOnShowListener {
            dialogReady = true
            dialog.window?.let { window ->
                // Apple Music's host window marks injected AlertDialogs as
                // ALT_FOCUSABLE_IM. That leaves the search EditText focused
                // while InputMethodManager keeps serving the host RecyclerView.
                // Let this dialog participate in IME focus and resize for the
                // keyboard instead of relying on the host's window policy.
                window.clearFlags(WindowManager.LayoutParams.FLAG_ALT_FOCUSABLE_IM)
                window.setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE)
                window.setBackgroundDrawable(android.graphics.drawable.ColorDrawable(Color.TRANSPARENT))
            }
            syncBottomCloseButton()
            syncDialogLayout()
        }
        val weakDialog = WeakReference<Dialog>(dialog)
        dialog.setOnDismissListener {
            if (dialogReference?.get() === weakDialog.get()) {
                dialogReference = null
                pageRefresh = null
            }
        }
        dialogReference = weakDialog
        pageRefresh = { renderPage() }
        renderPage()
        dialog.show()
    }


internal fun EmbeddedSettingsHost.renderEmbeddedMainPage(
        activity: Activity,
        parent: LinearLayout,
        settings: ModuleSettings,
        lyricsCount: Int,
        onSettingsChanged: (ModuleSettings) -> Unit,
        onCellularDataEntryChanged: (Boolean) -> Unit,
        onOpenCustomLyrics: () -> Unit,
        onChooseFont: () -> Unit,
        onClearFont: () -> Unit,
    ) {
        parent.addView(embeddedCard(activity, localizedText(ModuleText.FEATURES), outlined = false) {
            addView(embeddedSettingRow(
                activity,
                localizedText(ModuleText.TABLET_DUAL_PANE),
                localizedText(ModuleText.TABLET_DUAL_PANE_SUMMARY),
                settings.dualPaneEnabled,
                iconTint = EmbeddedSettingsPalette.Light.primary,
                iconDrawable = EmbeddedGlyphDrawable(
                    EmbeddedGlyphKind.TabletDualPane,
                    EmbeddedSettingsPalette.Light.primary,
                ),
            ) { onSettingsChanged(settings.copy(dualPaneEnabled = it)) })
            addView(embeddedDivider(activity))
            addView(embeddedSettingRow(
                activity,
                localizedText(ModuleText.CELLULAR_SETTINGS),
                localizedText(ModuleText.CELLULAR_SETTINGS_SUMMARY),
                settings.forceCellularDataEntryEnabled,
                iconTint = EmbeddedSettingsPalette.Light.primary,
                iconDrawable = EmbeddedGlyphDrawable(
                    EmbeddedGlyphKind.Document,
                    EmbeddedSettingsPalette.Light.primary,
                ),
            ) {
                onCellularDataEntryChanged(it)
                pageRefresh?.invoke()
            })
            // The compensation toggle only matters for the native tablet bar:
            // liquid glass owns the bottom geometry while it is on, so hide the
            // row instead of showing a switch that silently does nothing.
            if (!settings.phoneLiquidGlassEnabled) {
                addView(embeddedDivider(activity))
                addView(embeddedSettingRow(
                    activity,
                    localizedText(ModuleText.TABLET_BOTTOM_BAR_FIX),
                    localizedText(ModuleText.TABLET_BOTTOM_BAR_FIX_SUMMARY),
                    settings.navigationCompensationEnabled,
                    iconTint = EmbeddedSettingsPalette.Light.primary,
                    iconDrawable = EmbeddedGlyphDrawable(
                        EmbeddedGlyphKind.BottomBar,
                        EmbeddedSettingsPalette.Light.primary,
                    ),
                ) { onSettingsChanged(settings.copy(navigationCompensationEnabled = it)) })
            }
            addView(embeddedDivider(activity))
            addView(embeddedSettingRow(
                activity,
                localizedText(ModuleText.LIQUID_GLASS_NAVIGATION),
                localizedText(ModuleText.LIQUID_GLASS_NAVIGATION_SUMMARY),
                settings.phoneLiquidGlassEnabled,
                iconTint = EmbeddedSettingsPalette.Light.accent,
                iconDrawable = EmbeddedGlyphDrawable(
                    EmbeddedGlyphKind.Glass,
                    EmbeddedSettingsPalette.Light.accent,
                ),
            ) { onSettingsChanged(settings.copy(phoneLiquidGlassEnabled = it)); pageRefresh?.invoke() })
            if (settings.phoneLiquidGlassEnabled) {
                addView(embeddedDivider(activity))
                addView(embeddedGlassRangeRow(
                    activity = activity,
                    title = localizedText(ModuleText.BOTTOM_BAR_HEIGHT),
                    value = settings.phoneLiquidGlassBottomGapDp,
                    defaultValue = GlassPolicy.BOTTOM_DP,
                    rangeMin = ModuleSettings.MIN_PHONE_LIQUID_GLASS_BOTTOM_GAP_DP,
                    rangeMax = ModuleSettings.MAX_PHONE_LIQUID_GLASS_BOTTOM_GAP_DP,
                    suffix = localizedText(ModuleText.BOTTOM_BAR_HEIGHT_SUMMARY),
                ) { onSettingsChanged(settings.copy(phoneLiquidGlassBottomGapDp = it)) })
                addView(embeddedDivider(activity))
                addView(embeddedGlassRangeRow(
                    activity = activity,
                    title = localizedText(ModuleText.BOTTOM_BAR_BLUR),
                    value = settings.phoneLiquidGlassPanelBlurDp,
                    defaultValue = GlassPolicy.PANEL_BLUR_DP.toInt(),
                    rangeMin = ModuleSettings.MIN_PHONE_LIQUID_GLASS_PANEL_BLUR_DP,
                    rangeMax = ModuleSettings.MAX_PHONE_LIQUID_GLASS_PANEL_BLUR_DP,
                    suffix = localizedText(ModuleText.BOTTOM_BAR_BLUR_SUMMARY),
                ) { onSettingsChanged(settings.copy(phoneLiquidGlassPanelBlurDp = it)) })
            }
            addView(embeddedDivider(activity))
            addView(embeddedSettingRow(
                activity,
                localizedText(ModuleText.BIDIRECTIONAL_LYRIC_BLUR),
                localizedText(ModuleText.BIDIRECTIONAL_LYRIC_BLUR_SUMMARY),
                settings.futureBlurEnabled,
                iconTint = EmbeddedSettingsPalette.Light.primary,
                iconDrawable = EmbeddedGlyphDrawable(
                    EmbeddedGlyphKind.LyricsBlur,
                    EmbeddedSettingsPalette.Light.primary,
                ),
            ) { onSettingsChanged(settings.copy(futureBlurEnabled = it)) })
            addView(embeddedDivider(activity))
            addView(embeddedSettingRow(
                activity,
                localizedText(ModuleText.CJK_KARAOKE_ANIMATION),
                localizedText(ModuleText.CJK_KARAOKE_ANIMATION_SUMMARY),
                settings.cjkKaraokeAnimationEnabled,
                iconTint = EmbeddedSettingsPalette.Light.accent,
                iconDrawable = EmbeddedGlyphDrawable(
                    EmbeddedGlyphKind.Music,
                    EmbeddedSettingsPalette.Light.accent,
                ),
            ) { onSettingsChanged(settings.copy(cjkKaraokeAnimationEnabled = it)) })
            addView(embeddedDivider(activity))
            // HLE's page computes these two gates from its four controls; reproduce them
            // verbatim so the region picker and the two switches keep HLE's visibility.
            val regionReplacementEnabled =
                settings.regionSelection.replacesRegion && settings.overrideAccountLanguage
            val metadataLookupEnabled =
                regionReplacementEnabled || settings.restoreCjkOriginalMetadata
            // 1. HLE 将Apple Music改成其他地区 (title/summary/options verbatim, text in ModuleText).
            addView(embeddedNavigationRow(
                activity,
                localizedText(ModuleText.REGION_SELECTION),
                localizedText(ModuleText.REGION_SELECTION_SUMMARY),
                iconDrawable = EmbeddedGlyphDrawable(
                    EmbeddedGlyphKind.Translate,
                    EmbeddedSettingsPalette.Light.accent,
                ),
                trailingValue = settings.regionSelection.displayName,
            ) {
                showEmbeddedRegionSelectionPicker(
                    activity = activity,
                ) { region ->
                    onSettingsChanged(settings.copy(regionSelection = region))
                    pageRefresh?.invoke()
                }
            })
            // 2. HLE 歌曲信息替换至设定地区语言 — only shown once a region is selected.
            if (settings.regionSelection.replacesRegion) {
                addView(embeddedDivider(activity))
                addView(embeddedSettingRow(
                    activity,
                    localizedText(ModuleText.REGION_METADATA_LANGUAGE),
                    "",
                    settings.overrideAccountLanguage,
                    iconTint = EmbeddedSettingsPalette.Light.accent,
                    iconDrawable = EmbeddedGlyphDrawable(
                        EmbeddedGlyphKind.Translate,
                        EmbeddedSettingsPalette.Light.accent,
                    ),
                ) {
                    onSettingsChanged(settings.copy(overrideAccountLanguage = it))
                    pageRefresh?.invoke()
                })
            }
            // 3. HLE 替换中日韩歌曲信息为原地区原名 — always shown.
            addView(embeddedDivider(activity))
            addView(embeddedSettingRow(
                activity,
                localizedText(ModuleText.REGION_RESTORE_ORIGINAL),
                "",
                settings.restoreCjkOriginalMetadata,
                iconTint = EmbeddedSettingsPalette.Light.accent,
                iconDrawable = EmbeddedGlyphDrawable(
                    EmbeddedGlyphKind.Translate,
                    EmbeddedSettingsPalette.Light.accent,
                ),
            ) {
                onSettingsChanged(settings.copy(restoreCjkOriginalMetadata = it))
                pageRefresh?.invoke()
            })
            // 4. HLE 创建检索库以提升替换体验 — only shown while the metadata lookup runs.
            if (metadataLookupEnabled) {
                addView(embeddedDivider(activity))
                addView(embeddedSettingRow(
                    activity,
                    localizedText(ModuleText.REGION_METADATA_CACHE),
                    localizedText(ModuleText.REGION_METADATA_CACHE_SUMMARY),
                    settings.localizedMetadataCache,
                    iconTint = EmbeddedSettingsPalette.Light.accent,
                    iconDrawable = EmbeddedGlyphDrawable(
                        EmbeddedGlyphKind.Document,
                        EmbeddedSettingsPalette.Light.accent,
                    ),
                ) { onSettingsChanged(settings.copy(localizedMetadataCache = it)) })
            }
            // AM++: the 检索库 exists whenever the runtime can write it — a region
            // replacement caches localized results even without the override switch —
            // so the clear row follows HLE's four controls in every installable state.
            if (settings.regionSelection.replacesRegion || settings.restoreCjkOriginalMetadata) {
                addView(embeddedDivider(activity))
                addView(embeddedNavigationRow(
                    activity,
                    localizedText(ModuleText.REGION_CACHE_CLEAR),
                    localizedText(ModuleText.REGION_CACHE_CLEAR_SUMMARY),
                    iconDrawable = EmbeddedGlyphDrawable(
                        EmbeddedGlyphKind.DocumentSearch,
                        EmbeddedSettingsPalette.Light.accent,
                    ),
                ) {
                    confirmEmbeddedMetadataCacheClear(activity) {
                        // Read the persisted draft so an earlier toggle that did not
                        // re-render the page is not reverted by this action's copy.
                        val current = runCatching { controller.currentSettings() }
                            .getOrElse { settings }
                        onSettingsChanged(
                            current.copy(
                                metadataCacheClearGeneration =
                                    current.metadataCacheClearGeneration + 1L,
                            ),
                        )
                        // Re-render so a second tap carries the incremented value.
                        pageRefresh?.invoke()
                        Toast.makeText(
                            activity,
                            localizedText(ModuleText.REGION_CACHE_CLEAR_REQUESTED),
                            Toast.LENGTH_LONG,
                        ).show()
                    }
                })
            }
            addView(embeddedDivider(activity))
            addView(embeddedNavigationRow(
                activity,
                localizedText(ModuleText.CUSTOM_LYRICS),
                if (lyricsCount == 0) localizedText(ModuleText.CUSTOM_LYRICS_SUMMARY) else localizedText(ModuleText.CONFIGURED_LYRICS_COUNT, lyricsCount),
                iconDrawable = EmbeddedGlyphDrawable(
                    EmbeddedGlyphKind.Music,
                    EmbeddedSettingsPalette.Light.accent,
                ),
                onClick = onOpenCustomLyrics,
            ))
        })
        parent.addView(embeddedSpacer(activity, 12))
        parent.addView(embeddedCard(activity, localizedText(ModuleText.ADVANCED_SETTINGS)) {
            addView(embeddedBlurRadiusRow(activity, settings.lyricBlurRadiusOffsetPx) {
                onSettingsChanged(settings.copy(lyricBlurRadiusOffsetPx = it))
            })
            addView(embeddedDivider(activity))
            addView(embeddedDpiOverrideRow(activity, settings.appleMusicDpiOverrideDpi) {
                onSettingsChanged(settings.copy(appleMusicDpiOverrideDpi = it))
                pageRefresh?.invoke()
            })
        })
        parent.addView(embeddedSpacer(activity, 20))
        parent.addView(embeddedFontCard(
            activity = activity,
            manifest = settings.fontManifest,
            onChooseFont = onChooseFont,
            onClearFont = onClearFont,
        ))
        parent.addView(embeddedSpacer(activity, 20))
        parent.addView(embeddedSectionLabel(activity, localizedText(ModuleText.APPLICATION)))
        parent.addView(embeddedNavigationRow(activity, localizedText(ModuleText.PLUGINS), localizedText(ModuleText.PLUGINS_SUMMARY), onClick = { showPluginManagement(activity) }))
        parent.addView(embeddedInfoCard(
            activity,
            localizedText(ModuleText.PRIVATE_STORAGE_NOTICE),
        ))
        parent.addView(embeddedSpacer(activity, 16))
        parent.addView(embeddedSectionLabel(activity, localizedText(ModuleText.HELP)))
        parent.addView(embeddedInfoCard(
            activity,
            localizedText(ModuleText.RESTART_NOTICE),
            onClick = { showEmbeddedHelp(activity) },
        ))
    }


internal fun EmbeddedSettingsHost.renderEmbeddedCustomLyricsPage(
        activity: Activity,
        parent: LinearLayout,
        settings: ModuleSettings,
        song: CurrentSongDetails?,
        onSettingsChanged: (ModuleSettings) -> Unit,
    ) {
        val entries = runCatching { controller.lyricsEntries() }.getOrDefault(emptyList())
        customLyricsListState.update(entries, customLyricsSearchQuery)

        parent.addView(embeddedSettingRow(
            activity,
            localizedText(ModuleText.CUSTOM_LYRICS_REPLACEMENT),
            localizedText(ModuleText.CUSTOM_LYRICS_REPLACEMENT_SUMMARY),
            settings.customLyricsEnabled,
            iconDrawable = EmbeddedGlyphDrawable(
                EmbeddedGlyphKind.Exchange,
                EmbeddedSettingsPalette.Light.accent,
            ),
            compactWidePadding = true,
        ) {
            onSettingsChanged(settings.copy(customLyricsEnabled = it))
            // Re-render so the dependent automatic-lyrics switch changes its
            // enabled state without leaving the custom-lyrics page.
            pageRefresh?.invoke()
        })
        parent.addView(embeddedSpacer(activity, if (isEmbeddedPhone(activity)) 10 else 14))
        parent.addView(embeddedSettingRow(
            activity,
            localizedText(ModuleText.AUTO_LYRICS),
            localizedText(ModuleText.AUTO_LYRICS_SUMMARY),
            settings.automaticLyricsEnabled,
            enabled = settings.customLyricsEnabled,
            iconDrawable = EmbeddedGlyphDrawable(
                EmbeddedGlyphKind.Exchange,
                EmbeddedSettingsPalette.Light.accent,
            ),
            compactWidePadding = true,
        ) { onSettingsChanged(settings.copy(automaticLyricsEnabled = it)) })
        parent.addView(embeddedSpacer(activity, if (isEmbeddedPhone(activity)) 10 else 14))
        parent.addView(embeddedSettingRow(
            activity,
            localizedText(ModuleText.ONLINE_LYRICS_SUPPLEMENT),
            localizedText(ModuleText.ONLINE_LYRICS_SUPPLEMENT_SUMMARY),
            settings.onlineLyricsSupplementEnabled,
            enabled = settings.customLyricsEnabled,
            iconDrawable = EmbeddedGlyphDrawable(
                EmbeddedGlyphKind.Exchange,
                EmbeddedSettingsPalette.Light.accent,
            ),
            compactWidePadding = true,
        ) {
            onSettingsChanged(settings.copy(onlineLyricsSupplementEnabled = it))
            // Re-render so the source rows below enable/disable with the master.
            pageRefresh?.invoke()
        })
        parent.addView(embeddedSpacer(activity, if (isEmbeddedPhone(activity)) 10 else 14))
        parent.addView(embeddedSettingRow(
            activity,
            localizedText(ModuleText.ONLINE_LYRICS_TRANSLATION),
            localizedText(ModuleText.ONLINE_LYRICS_TRANSLATION_SUMMARY),
            settings.onlineLyricsTranslationEnabled,
            enabled = settings.customLyricsEnabled && settings.automaticLyricsEnabled,
            iconDrawable = EmbeddedGlyphDrawable(
                EmbeddedGlyphKind.Exchange,
                EmbeddedSettingsPalette.Light.accent,
            ),
            compactWidePadding = true,
        ) { onSettingsChanged(settings.copy(onlineLyricsTranslationEnabled = it)) })
        parent.addView(embeddedSpacer(activity, if (isEmbeddedPhone(activity)) 10 else 14))
        parent.addView(embeddedSettingRow(
            activity,
            localizedText(ModuleText.ONLINE_LYRICS_HIDE_MANDARIN_PINYIN),
            localizedText(ModuleText.ONLINE_LYRICS_HIDE_MANDARIN_PINYIN_SUMMARY),
            settings.onlineLyricsHideMandarinPinyinEnabled,
            enabled = settings.customLyricsEnabled &&
                settings.automaticLyricsEnabled &&
                settings.onlineLyricsTranslationEnabled,
            iconDrawable = EmbeddedGlyphDrawable(
                EmbeddedGlyphKind.Exchange,
                EmbeddedSettingsPalette.Light.accent,
            ),
            compactWidePadding = true,
        ) {
            onSettingsChanged(settings.copy(onlineLyricsHideMandarinPinyinEnabled = it))
        })

        // The translation/pronunciation pass borrows the same provider chain as
        // the supplement, so the chain rows stay usable while either opt-in is on.
        val chainEnabled = settings.customLyricsEnabled &&
            (settings.onlineLyricsSupplementEnabled || settings.onlineLyricsTranslationEnabled)
        parent.addView(embeddedSpacer(activity, if (isEmbeddedPhone(activity)) 10 else 14))
        parent.addView(embeddedSettingRow(
            activity,
            localizedText(ModuleText.ONLINE_LYRICS_AUTOMATIC_ORDER),
            localizedText(ModuleText.ONLINE_LYRICS_AUTOMATIC_ORDER_SUMMARY),
            settings.onlineLyricsAutomaticOrderEnabled,
            enabled = chainEnabled,
            iconDrawable = EmbeddedGlyphDrawable(
                EmbeddedGlyphKind.Exchange,
                EmbeddedSettingsPalette.Light.accent,
            ),
            compactWidePadding = true,
        ) { onSettingsChanged(settings.copy(onlineLyricsAutomaticOrderEnabled = it)) })
        parent.addView(embeddedSpacer(activity, if (isEmbeddedPhone(activity)) 10 else 14))
        parent.addView(embeddedSettingRow(
            activity,
            localizedText(ModuleText.ONLINE_LYRICS_GLOBAL_BEST),
            localizedText(ModuleText.ONLINE_LYRICS_GLOBAL_BEST_SUMMARY),
            settings.onlineLyricsGlobalBestEnabled,
            enabled = chainEnabled,
            iconDrawable = EmbeddedGlyphDrawable(
                EmbeddedGlyphKind.Exchange,
                EmbeddedSettingsPalette.Light.accent,
            ),
            compactWidePadding = true,
        ) { onSettingsChanged(settings.copy(onlineLyricsGlobalBestEnabled = it)) })

        OnlineLyricSources.DEFAULT_ORDER.forEach { sourceId ->
            parent.addView(embeddedSpacer(activity, if (isEmbeddedPhone(activity)) 10 else 14))
            parent.addView(embeddedSettingRow(
                activity,
                embeddedOnlineLyricsSourceLabel(sourceId),
                localizedText(ModuleText.ONLINE_LYRICS_SOURCE_SEARCH_SUMMARY),
                settings.onlineLyricsSourceEnabled(sourceId),
                enabled = chainEnabled,
                iconDrawable = EmbeddedGlyphDrawable(
                    EmbeddedGlyphKind.DocumentSearch,
                    EmbeddedSettingsPalette.Light.accent,
                ),
                compactWidePadding = true,
            ) {
                onSettingsChanged(settings.withOnlineLyricsSourceEnabled(sourceId, it))
            })
        }
        parent.addView(embeddedSpacer(activity, if (isEmbeddedPhone(activity)) 10 else 14))

        val lyricsContent = LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL

            addView(
                embeddedCompactLyricsActionBar(
                    activity = activity,
                    onAdd = { showLyricsEditor(activity, null as CustomLyricsUiGroup?, song) },
                    onTtml = {
                        launchSafPicker(
                            activity,
                            EmbeddedSafOperation.Ttml,
                            "application/xml",
                            arrayOf("application/ttml+xml", "application/xml", "text/xml", "text/plain"),
                        )
                    },
                    onUpdate = { updateEmbeddedLyrics(activity) },
                    onBackup = { anchor -> showEmbeddedBackupRestoreMenu(activity, anchor) },
                ),
                LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    dp(activity, 76),
                ).apply {
                    marginStart = dp(activity, if (isEmbeddedPhone(activity)) 4 else 5)
                    marginEnd = dp(activity, if (isEmbeddedPhone(activity)) 4 else 3)
                    bottomMargin = dp(activity, if (isEmbeddedPhone(activity)) 12 else 14)
                },
            )

            val search = EditText(activity).apply {

                bindEmbeddedInputTheme(activity)
                hint = localizedText(ModuleText.SEARCH_LYRICS)
                textSize = embeddedTextSize(activity, 14f, 13f)
                inputType = InputType.TYPE_CLASS_TEXT
                imeOptions = EditorInfo.IME_ACTION_SEARCH
                showSoftInputOnFocus = true
                isSingleLine = true
                includeFontPadding = false
                setText(customLyricsSearchQuery)
                setPadding(dp(activity, 12), 0, dp(activity, 12), 0)
                setEmbeddedTextColor(activity) { colors -> colors.onSurface }
                setEmbeddedHintColor(activity) { colors -> colors.onSurfaceVariant }
                background = null
            }
            addView(FrameLayout(activity).apply {
                bindEmbeddedTheme(activity) { colors ->
                    background = GradientDrawable().apply {
                        setColor(colors.softBackground)
                        cornerRadius = dp(activity, 8).toFloat()
                    }
                }
                addView(ImageView(activity).apply {
                    setEmbeddedImageDrawable(activity,
                        EmbeddedGlyphDrawable(
                            EmbeddedGlyphKind.Search,
                            EmbeddedSettingsPalette.Light.onSurfaceVariant,
                        ),
                    )
                    contentDescription = null
                    setPadding(dp(activity, 12), dp(activity, 12), dp(activity, 8), dp(activity, 12))
                }, FrameLayout.LayoutParams(embeddedSearchFieldHeight(activity), embeddedSearchFieldHeight(activity)))
                search.setPadding(embeddedSearchFieldHeight(activity), 0, dp(activity, 12), 0)
                addView(search, FrameLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    embeddedSearchFieldHeight(activity),
                ))
            }, LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                embeddedSearchFieldHeight(activity),
            ).apply {
                marginStart = dp(activity, if (isEmbeddedPhone(activity)) 4 else 8)
                marginEnd = dp(activity, 4)
                topMargin = dp(activity, 4)
                bottomMargin = dp(activity, if (isEmbeddedPhone(activity)) 8 else 16)
            })
            addView(LinearLayout(activity).apply {
                orientation = LinearLayout.HORIZONTAL
                setPadding(
                    dp(activity, if (isEmbeddedPhone(activity)) 0 else 6),
                    0,
                    dp(activity, if (isEmbeddedPhone(activity)) 0 else 6),
                    dp(activity, 6),
                )
                addView(TextView(activity).apply {
                    text = localizedText(ModuleText.CONFIGURED)
                    textSize = embeddedTextSize(activity, 14f, 13f)
                    setEmbeddedTextColor(activity) { colors -> colors.accent }
                    setTypeface(typeface, Typeface.BOLD)
                }, LinearLayout.LayoutParams(0, dp(activity, 28), 1f))
                addView(TextView(activity).apply {
                    text = localizedText(ModuleText.SONG_COUNT, entries.size)
                    textSize = embeddedTextSize(activity, 13f, 13f)
                    gravity = Gravity.CENTER_VERTICAL or Gravity.END
                    setEmbeddedTextColor(activity) { colors -> colors.accent }
                    setTypeface(typeface, Typeface.BOLD)
                }, LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, dp(activity, 28)))
            }, matchWidthWrapContent())
            val entriesRegion = LinearLayout(activity).apply {
                orientation = LinearLayout.VERTICAL
            }
            addView(entriesRegion, matchWidthWrapContent())

            fun renderEntries() {
                entriesRegion.removeAllViews()
                val state = customLyricsListState
                if (state.totalCount == 0) {
                    entriesRegion.addView(LinearLayout(activity).apply {
                        orientation = LinearLayout.VERTICAL
                        gravity = Gravity.CENTER_HORIZONTAL
                        setPadding(dp(activity, 16), dp(activity, 42), dp(activity, 16), dp(activity, 48))
                        addView(ImageView(activity).apply {
                            setEmbeddedImageDrawable(activity,
                                EmbeddedGlyphDrawable(
                                    EmbeddedGlyphKind.DocumentSearch,
                                    EmbeddedSettingsPalette.Light.disabledText,
                                ),
                            )
                            contentDescription = null
                            setPadding(dp(activity, 8), dp(activity, 8), dp(activity, 8), dp(activity, 8))
                        }, LinearLayout.LayoutParams(dp(activity, 56), dp(activity, 56)))
                        addView(TextView(activity).apply {
                            text = if (entries.isEmpty()) localizedText(ModuleText.NO_CUSTOM_LYRICS) else localizedText(ModuleText.NO_SEARCH_RESULTS)
                            textSize = 15f
                            gravity = Gravity.CENTER
                            setEmbeddedTextColor(activity) { colors -> colors.onSurfaceVariant }
                            setSingleLine(false)
                            setPadding(0, dp(activity, 8), 0, 0)
                        }, matchWidthWrapContent())
                        addView(TextView(activity).apply {
                            text = if (entries.isEmpty()) localizedText(ModuleText.ADD_LYRICS_EMPTY_HINT) else localizedText(ModuleText.SEARCH_LYRICS_EMPTY_HINT)
                            textSize = 12.5f
                            gravity = Gravity.CENTER
                            setEmbeddedTextColor(activity) { colors -> colors.mutedText }
                            setSingleLine(false)
                            setPadding(0, dp(activity, 4), 0, 0)
                        }, matchWidthWrapContent())
                    }, matchWidthWrapContent())
                    return
                }
                val visibleGroups = state.visibleGroups
                visibleGroups.forEachIndexed { index, group ->
                    entriesRegion.addView(embeddedCustomLyricsEntryRow(activity, group, song))
                    if (index < visibleGroups.lastIndex) entriesRegion.addView(embeddedDivider(activity))
                }
                entriesRegion.addView(TextView(activity).apply {
                    text = localizedText(ModuleText.LYRICS_VISIBLE_COUNT, state.visibleCount, state.totalCount)
                    textSize = 13f
                    setEmbeddedTextColor(activity) { colors -> colors.onSurfaceVariant }
                    setPadding(dp(activity, 16), dp(activity, 8), dp(activity, 16), dp(activity, 12))
                })
                if (state.hasMore) {
                    entriesRegion.addView(LinearLayout(activity).apply {
                        orientation = embeddedActionOrientation(activity)
                        setPadding(dp(activity, 12), 0, dp(activity, 12), dp(activity, 12))
                        addView(embeddedActionButton(activity, localizedText(ModuleText.LOAD_MORE)) {
                            customLyricsListState.loadMore()
                            renderEntries()
                        }, embeddedActionButtonParams(activity))
                    }, matchWidthWrapContent())
                }
            }

            search.addTextChangedListener(object : TextWatcher {
                override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) = Unit
                override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) = Unit
                override fun afterTextChanged(s: Editable?) {
                    customLyricsSearchQuery = s?.toString().orEmpty()
                    customLyricsListState.setQuery(customLyricsSearchQuery)
                    renderEntries()
                }
            })
            renderEntries()
        }
        parent.addView(lyricsContent)

        // The one-shot clear-all action, mirroring the 「清空检索库」 precedent: a
        // navigation row, a count-carrying confirmation, then [runAsync]'s toast
        // and page re-render.
        parent.addView(embeddedSpacer(activity, if (isEmbeddedPhone(activity)) 12 else 16))
        parent.addView(embeddedDivider(activity))
        parent.addView(embeddedNavigationRow(
            activity,
            localizedText(ModuleText.CLEAR_CUSTOM_LYRICS),
            if (entries.isEmpty()) {
                localizedText(ModuleText.CLEAR_CUSTOM_LYRICS_SUMMARY_ALL)
            } else {
                localizedText(ModuleText.CLEAR_CUSTOM_LYRICS_SUMMARY_COUNT, entries.size)
            },
            iconDrawable = EmbeddedGlyphDrawable(
                EmbeddedGlyphKind.Delete,
                EmbeddedSettingsPalette.Light.accent,
            ),
        ) {
            confirmEmbeddedCustomLyricsClear(activity, entries.size) {
                runAsync(activity) { controller.clearLyrics() }
            }
        })
    }


internal fun EmbeddedSettingsHost.confirmEmbeddedCustomLyricsClear(
    activity: Activity,
    entryCount: Int,
    onConfirmed: () -> Unit,
) {
    embeddedDialogBuilder(activity)
        .setTitle(localizedText(ModuleText.CLEAR_CUSTOM_LYRICS))
        .setMessage(localizedText(ModuleText.CLEAR_CUSTOM_LYRICS_CONFIRM, entryCount))
        .setNegativeButton(localizedText(ModuleText.CANCEL), null)
        .setPositiveButton(localizedText(ModuleText.CLEAR)) { _, _ -> onConfirmed() }
        .show()
}


internal fun EmbeddedSettingsHost.embeddedCustomLyricsEntryRow(
        activity: Activity,
        group: CustomLyricsUiGroup,
        song: CurrentSongDetails?,
    ): View = LinearLayout(activity).apply {
        val entry = group.primary
        orientation = LinearLayout.VERTICAL
        setPadding(
            dp(activity, if (isEmbeddedPhone(activity)) 12 else 10),
            dp(activity, if (isEmbeddedPhone(activity)) 8 else 6),
            dp(activity, if (isEmbeddedPhone(activity)) 8 else 8),
            dp(activity, if (isEmbeddedPhone(activity)) 8 else 6),
        )
        // The inline edit/delete row was removed; the remaining 44dp control row
        // plus symmetric card padding is the complete intrinsic height.
        minimumHeight = dp(activity, if (isEmbeddedPhone(activity)) 60 else 56)
        bindEmbeddedTheme(activity) { colors ->
            background = GradientDrawable().apply {
                setColor(colors.surface)
                setStroke(dp(activity, 1), colors.outline)
                cornerRadius = dp(activity, 8).toFloat()
            }
        }
        val artist = song
            ?.takeIf { it.appleMusicId == entry.appleMusicId }
            ?.artist
            ?.takeIf(String::isNotBlank)
        // Keep the legacy source contract (`主 ID：${entry.appleMusicId} · 共 ${group.entries.size} 个 ID`) while the rendered row uses the more
        // useful artist/Apple Music ID summary below.
        val secondary = buildString {
            artist?.let {
                append(it)
                append(" · ")
            }
            append("AM ID: ")
            append(entry.appleMusicId)
            if (group.entries.size > 1) {
                append(localizedText(ModuleText.LYRICS_ID_COUNT, group.entries.size))
            }
        }
        addView(LinearLayout(activity).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            addView(LinearLayout(activity).apply {
                orientation = LinearLayout.VERTICAL
                addView(TextView(activity).apply {
                    text = entry.displayName.ifBlank { entry.appleMusicId.toString() }
                    textSize = embeddedTextSize(activity, 16f, 14f)
                    setEmbeddedTextColor(activity) { colors -> colors.onSurface }
                    typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
                    setSingleLine(false)
                    maxLines = 2
                }, matchWidthWrapContent())
                addView(TextView(activity).apply {
                    text = secondary
                    textSize = embeddedTextSize(activity, 12.5f, 12f)
                    setEmbeddedTextColor(activity) { colors -> colors.onSurfaceVariant }
                    setPadding(0, dp(activity, 2), 0, 0)
                    setSingleLine(false)
                    maxLines = 3
                }, matchWidthWrapContent())
            }, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply {
                marginEnd = dp(activity, 4)
            })
            addView(Switch(activity).apply {
                isChecked = group.allEnabled
                contentDescription = localizedText(ModuleText.LYRICS_TOGGLE_ACCESSIBILITY, entry.displayName)
                bindEmbeddedSwitchTheme(activity)
                setOnCheckedChangeListener { _, checked ->
                    runAsync(activity) { controller.setLyricsEnabled(group.appleMusicIds, checked) }
                }
            }, LinearLayout.LayoutParams(dp(activity, if (isEmbeddedPhone(activity)) 56 else 48), dp(activity, 44)))
            addView(ImageView(activity).apply {
                setEmbeddedImageDrawable(activity,
                    EmbeddedGlyphDrawable(
                        EmbeddedGlyphKind.MoreVertical,
                        EmbeddedSettingsPalette.Light.onSurfaceVariant,
                    ),
                )
                scaleType = ImageView.ScaleType.CENTER
                contentDescription = localizedText(ModuleText.MORE_LYRICS_ACTIONS)
                isClickable = true
                isFocusable = true
                setPadding(dp(activity, 6), dp(activity, 8), dp(activity, 6), dp(activity, 8))
                setOnClickListener { showEmbeddedLyricsOverflowMenu(activity, group, song, this) }
            }, LinearLayout.LayoutParams(dp(activity, if (isEmbeddedPhone(activity)) 36 else 32), dp(activity, 44)))
        }, matchWidthWrapContent())
    }


internal fun EmbeddedSettingsHost.confirmEmbeddedMetadataCacheClear(
    activity: Activity,
    onConfirmed: () -> Unit,
) {
    embeddedDialogBuilder(activity)
        .setTitle(localizedText(ModuleText.REGION_CACHE_CLEAR))
        .setMessage(localizedText(ModuleText.REGION_CACHE_CLEAR_CONFIRM))
        .setNegativeButton(localizedText(ModuleText.CANCEL), null)
        .setPositiveButton(localizedText(ModuleText.CLEAR)) { _, _ -> onConfirmed() }
        .show()
}


internal fun EmbeddedSettingsHost.confirmEmbeddedLyricsDelete(activity: Activity, group: CustomLyricsUiGroup) {
        val entry = group.primary
        embeddedDialogBuilder(activity)
            .setMessage(
                localizedText(ModuleText.DELETE_LYRICS_CONFIRM,
                    entry.displayName.ifBlank { entry.appleMusicId.toString() }, group.entries.size),
            )
            .setNegativeButton(localizedText(ModuleText.CANCEL), null)
            .setPositiveButton(localizedText(ModuleText.DELETE)) { _, _ ->
                runAsync(activity) { controller.deleteLyrics(group.appleMusicIds) }
            }
            .show()
    }


internal fun EmbeddedSettingsHost.showEmbeddedLyricsOverflowMenu(
        activity: Activity,
        group: CustomLyricsUiGroup,
        song: CurrentSongDetails?,
        anchor: View,
    ) {
        refreshEmbeddedTheme(activity)
        PopupMenu(embeddedThemedContext(activity), anchor).apply {
            menu.add(localizedText(ModuleText.EDIT)).setOnMenuItemClickListener {
                showLyricsEditor(activity, group, song)
                true
            }
            menu.add(localizedText(ModuleText.DELETE)).setOnMenuItemClickListener {
                confirmEmbeddedLyricsDelete(activity, group)
                true
            }
            menu.add(localizedText(ModuleText.COPY_MUSIC_ID)).setOnMenuItemClickListener {
                val clipboard = activity.getSystemService(Context.CLIPBOARD_SERVICE) as? android.content.ClipboardManager
                clipboard?.setPrimaryClip(
                    android.content.ClipData.newPlainText(
                        "Apple Music ID",
                        group.appleMusicIds.joinToString(","),
                    ),
                )
                Toast.makeText(activity, localizedText(ModuleText.MUSIC_ID_COPIED), Toast.LENGTH_SHORT).show()
                true
            }
            show()
        }
    }


internal fun EmbeddedSettingsHost.embeddedCustomLyricsSourceName(source: String): String = when (source) {
        CustomLyricsSources.AUTO_CACHE -> localizedText(ModuleText.AUTO_CACHE)
        CustomLyricsSources.AMLL -> "AMLL"
        CustomLyricsSources.AM_LYRICS -> localizedText(ModuleText.AM_LYRICS_REPOSITORY)
        CustomLyricsSources.LUNABEAT -> "Lunabeat"
        CustomLyricsSources.KUWO -> "酷我"
        CustomLyricsSources.NETEASE -> "网易云"
        CustomLyricsSources.QQ -> "QQ 音乐"
        CustomLyricsSources.KUGOU -> "酷狗"
        else -> localizedText(ModuleText.MANUAL_TTML)
    }

/** Display name for one selectable online chain source id. */
internal fun embeddedOnlineLyricsSourceLabel(sourceId: String): String = when (sourceId) {
    CustomLyricsSources.NETEASE -> "网易云音乐"
    CustomLyricsSources.QQ -> "QQ 音乐"
    CustomLyricsSources.KUWO -> "酷我音乐"
    CustomLyricsSources.KUGOU -> "酷狗音乐"
    else -> sourceId
}

