package dev.amenhancer.module.ui

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
            Toast.makeText(activity, "无法读取 AM++ 设置", Toast.LENGTH_SHORT).show()
            return
        }
        val draft = EmbeddedSettingsDraft(initialSettings, controller::saveOrdinarySettings)
        var page = EmbeddedSettingsPage.MAIN
        var dialogReady = false
        lateinit var dialog: AlertDialog

        val panelBackground = GradientDrawable().apply {
            setColor(EmbeddedSettingsPalette.pageBackground)
            cornerRadius = embeddedCardCornerRadius(activity)
        }
        val pageHost = LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
            val hostInset = dp(activity, if (isEmbeddedPhone(activity)) 0 else 8)
            setPadding(hostInset, 0, hostInset, 0)
            setBackgroundColor(EmbeddedSettingsPalette.pageBackground)
        }
        val topBar = LinearLayout(activity).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            minimumHeight = embeddedTopBarHeight(activity)
            val phoneHeaderInset = dp(activity, if (isEmbeddedPhone(activity)) 8 else 0)
            setPadding(phoneHeaderInset, 0, phoneHeaderInset, 0)
        }
        val backButton = ImageView(activity).apply {
            setImageDrawable(
                embeddedSvgDrawable(EmbeddedSvgIcon.Back) ?: EmbeddedGlyphDrawable(
                    EmbeddedGlyphKind.BackArrow,
                    EmbeddedSettingsPalette.primary,
                    strokeWidthFraction = 0.055f,
                ),
            )
            scaleType = ImageView.ScaleType.CENTER_INSIDE
            contentDescription = "返回"
            isClickable = true
            isFocusable = true
            setPadding(dp(activity, 8), dp(activity, 8), dp(activity, 8), dp(activity, 8))
        }
        val moduleIcon = ImageView(activity).apply {
            setImageDrawable(EmbeddedAmppBrandDrawable())
            contentDescription = "AM++"
            scaleType = ImageView.ScaleType.CENTER_INSIDE
            setPadding(0, 0, 0, 0)
        }
        val pageTitle = TextView(activity).apply {
            textSize = embeddedTextSize(activity, 19f, 18f)
            setTextColor(EmbeddedSettingsPalette.onSurface)
            setTypeface(typeface, if (isEmbeddedPhone(activity)) Typeface.BOLD else Typeface.NORMAL)
            setSingleLine(false)
            maxLines = 2
            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
        }
        val saveButton = TextView(activity).apply {
            text = "保存"
            textSize = embeddedTextSize(activity, 15f, 14f)
            gravity = Gravity.CENTER
            setTextColor(EmbeddedSettingsPalette.primary)
            isClickable = true
            isFocusable = true
            setPadding(dp(activity, 12), dp(activity, 8), dp(activity, 8), dp(activity, 8))
            contentDescription = "保存 AM++ 设置"
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
            setBackgroundColor(EmbeddedSettingsPalette.divider)
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
            setBackgroundColor(EmbeddedSettingsPalette.pageBackground)
            val horizontalPadding = dp(activity, if (isEmbeddedPhone(activity)) 16 else 12)
            setPadding(horizontalPadding, 0, horizontalPadding, 0)
        }
        val closeButton = TextView(activity).apply {
            text = "关闭"
            textSize = embeddedTextSize(activity, 16f, 14f)
            gravity = Gravity.CENTER
            setTextColor(EmbeddedSettingsPalette.primary)
            isClickable = true
            isFocusable = true
            contentDescription = "关闭 AM++ 设置"
            setOnClickListener { dialog.dismiss() }
        }
        closeBar.addView(closeButton, LinearLayout.LayoutParams(
            dp(activity, if (isEmbeddedPhone(activity)) 64 else 56),
            dp(activity, if (isEmbeddedPhone(activity)) 56 else 48),
        ))

        val root = LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
            background = panelBackground
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
                Toast.makeText(activity, "已保存；需要重启的设置请重开 Apple Music。", Toast.LENGTH_LONG).show()
                if (close) dialog.dismiss()
            } else {
                Toast.makeText(activity, "保存 AM++ 设置失败", Toast.LENGTH_SHORT).show()
            }
        }

        fun updateDraft(next: ModuleSettings) {
            if (!draft.update(next)) {
                Toast.makeText(activity, "保存 AM++ 设置失败", Toast.LENGTH_SHORT).show()
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
            pageTitle.text = if (customLyricsPage) "自定义歌词" else "AM++"
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
                            Toast.makeText(activity, "保存 AM++ 设置失败", Toast.LENGTH_SHORT).show()
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
        dialog = AlertDialog.Builder(activity)
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
        parent.addView(embeddedCard(activity, "功能", outlined = false) {
            addView(embeddedSettingRow(
                activity,
                "平板双栏播放器",
                "平板横屏启用双栏",
                settings.dualPaneEnabled,
                iconTint = EmbeddedSettingsPalette.primary,
                iconDrawable = EmbeddedGlyphDrawable(
                    EmbeddedGlyphKind.TabletDualPane,
                    EmbeddedSettingsPalette.primary,
                ),
            ) { onSettingsChanged(settings.copy(dualPaneEnabled = it)) })
            addView(embeddedDivider(activity))
            addView(embeddedSettingRow(
                activity,
                "强制显示蜂窝数据入口",
                "恢复原生蜂窝数据设置 · 重开应用后显示",
                settings.forceCellularDataEntryEnabled,
                iconTint = EmbeddedSettingsPalette.primary,
                iconDrawable = EmbeddedGlyphDrawable(
                    EmbeddedGlyphKind.Document,
                    EmbeddedSettingsPalette.primary,
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
                    "平板底栏补偿",
                    "如果底栏显示异常开启该选项",
                    settings.navigationCompensationEnabled,
                    iconTint = EmbeddedSettingsPalette.primary,
                    iconDrawable = EmbeddedGlyphDrawable(
                        EmbeddedGlyphKind.BottomBar,
                        EmbeddedSettingsPalette.primary,
                    ),
                ) { onSettingsChanged(settings.copy(navigationCompensationEnabled = it)) })
            }
            addView(embeddedDivider(activity))
            addView(embeddedSettingRow(
                activity,
                "液态玻璃底栏",
                "为手机与开启平板双栏播放器的平板横屏的底栏和迷你播放器启用液态玻璃效果，需重开应用",
                settings.phoneLiquidGlassEnabled,
                iconTint = EmbeddedSettingsPalette.accent,
                iconDrawable = EmbeddedGlyphDrawable(
                    EmbeddedGlyphKind.Glass,
                    EmbeddedSettingsPalette.accent,
                ),
            ) { onSettingsChanged(settings.copy(phoneLiquidGlassEnabled = it)); pageRefresh?.invoke() })
            if (settings.phoneLiquidGlassEnabled) {
                addView(embeddedDivider(activity))
                addView(embeddedGlassRangeRow(
                    activity = activity,
                    title = "底栏高度",
                    value = settings.phoneLiquidGlassBottomGapDp,
                    defaultValue = GlassPolicy.BOTTOM_DP,
                    rangeMin = ModuleSettings.MIN_PHONE_LIQUID_GLASS_BOTTOM_GAP_DP,
                    rangeMax = ModuleSettings.MAX_PHONE_LIQUID_GLASS_BOTTOM_GAP_DP,
                    suffix = "底栏距屏幕底部的距离",
                ) { onSettingsChanged(settings.copy(phoneLiquidGlassBottomGapDp = it)) })
                addView(embeddedDivider(activity))
                addView(embeddedGlassRangeRow(
                    activity = activity,
                    title = "底栏背景模糊强度",
                    value = settings.phoneLiquidGlassPanelBlurDp,
                    defaultValue = GlassPolicy.PANEL_BLUR_DP.toInt(),
                    rangeMin = ModuleSettings.MIN_PHONE_LIQUID_GLASS_PANEL_BLUR_DP,
                    rangeMax = ModuleSettings.MAX_PHONE_LIQUID_GLASS_PANEL_BLUR_DP,
                    suffix = "作用于底栏与迷你播放器",
                ) { onSettingsChanged(settings.copy(phoneLiquidGlassPanelBlurDp = it)) })
            }
            addView(embeddedDivider(activity))
            addView(embeddedSettingRow(
                activity,
                "双向歌词模糊",
                "手动滚动停止 1 秒后恢复",
                settings.futureBlurEnabled,
                iconTint = EmbeddedSettingsPalette.primary,
                iconDrawable = EmbeddedGlyphDrawable(
                    EmbeddedGlyphKind.LyricsBlur,
                    EmbeddedSettingsPalette.primary,
                ),
            ) { onSettingsChanged(settings.copy(futureBlurEnabled = it)) })
            addView(embeddedDivider(activity))
            addView(embeddedSettingRow(
                activity,
                "CJK 长尾歌词动画",
                "CJK 歌词启用原生 rush-gradient 动画 · 重开 Apple Music 后生效",
                settings.cjkKaraokeAnimationEnabled,
                iconTint = EmbeddedSettingsPalette.accent,
                iconDrawable = EmbeddedGlyphDrawable(
                    EmbeddedGlyphKind.Music,
                    EmbeddedSettingsPalette.accent,
                ),
            ) { onSettingsChanged(settings.copy(cjkKaraokeAnimationEnabled = it)) })
            addView(embeddedDivider(activity))
            // HLE's page computes these two gates from its four controls; reproduce them
            // verbatim so the region picker and the two switches keep HLE's visibility.
            val regionReplacementEnabled =
                settings.regionSelection.replacesRegion && settings.overrideAccountLanguage
            val metadataLookupEnabled =
                regionReplacementEnabled || settings.restoreCjkOriginalMetadata
            // 1. HLE 将Apple Music改成其他地区 (title/summary/options verbatim).
            addView(embeddedNavigationRow(
                activity,
                "将Apple Music改成其他地区",
                "可能影响加载速度，仅用于内容UI语言切换，无法播放/使用账号所在地区没有的歌曲/服务，此类歌曲/服务只能浏览",
                iconDrawable = EmbeddedGlyphDrawable(
                    EmbeddedGlyphKind.Translate,
                    EmbeddedSettingsPalette.accent,
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
                    "歌曲信息替换至设定地区语言",
                    "",
                    settings.overrideAccountLanguage,
                    iconTint = EmbeddedSettingsPalette.accent,
                    iconDrawable = EmbeddedGlyphDrawable(
                        EmbeddedGlyphKind.Translate,
                        EmbeddedSettingsPalette.accent,
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
                "替换中日韩歌曲信息为原地区原名",
                "",
                settings.restoreCjkOriginalMetadata,
                iconTint = EmbeddedSettingsPalette.accent,
                iconDrawable = EmbeddedGlyphDrawable(
                    EmbeddedGlyphKind.Translate,
                    EmbeddedSettingsPalette.accent,
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
                    "创建检索库以提升替换体验",
                    "极少量空间占用换取高效率性能，当关闭时冷启动后需重新抓取歌曲信息，推荐开启",
                    settings.localizedMetadataCache,
                    iconTint = EmbeddedSettingsPalette.accent,
                    iconDrawable = EmbeddedGlyphDrawable(
                        EmbeddedGlyphKind.Document,
                        EmbeddedSettingsPalette.accent,
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
                    "清空检索库",
                    "删除已缓存的所有地区歌曲信息与原地区原名结果，下次读取时重新向 Apple Music 获取",
                    iconDrawable = EmbeddedGlyphDrawable(
                        EmbeddedGlyphKind.DocumentSearch,
                        EmbeddedSettingsPalette.accent,
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
                            "已请求清空检索库，将在下次读取歌曲信息时生效",
                            Toast.LENGTH_LONG,
                        ).show()
                    }
                })
            }
            addView(embeddedDivider(activity))
            addView(embeddedNavigationRow(
                activity,
                "自定义歌词",
                if (lyricsCount == 0) "添加和管理 Apple Music ID 歌词映射" else "已配置 $lyricsCount 首歌词",
                iconDrawable = EmbeddedGlyphDrawable(
                    EmbeddedGlyphKind.Music,
                    EmbeddedSettingsPalette.accent,
                ),
                onClick = onOpenCustomLyrics,
            ))
        })
        parent.addView(embeddedSpacer(activity, 12))
        parent.addView(embeddedCard(activity, "高级设置") {
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
        parent.addView(embeddedSectionLabel(activity, "应用"))
        parent.addView(embeddedInfoCard(
            activity,
            "配置保存在 Apple Music 私有目录中",
        ))
        parent.addView(embeddedSpacer(activity, 16))
        parent.addView(embeddedSectionLabel(activity, "帮助"))
        parent.addView(embeddedInfoCard(
            activity,
            "重启提示\n字体、双栏播放器以及标记“需重启”的设置，需要完全停止并重新打开 Apple Music 后生效。",
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
            "自定义歌词替换",
            "按 Apple Music ID 注入，更改后重开 Apple Music 生效",
            settings.customLyricsEnabled,
            iconDrawable = EmbeddedGlyphDrawable(
                EmbeddedGlyphKind.Exchange,
                EmbeddedSettingsPalette.accent,
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
            "自动实时补全",
            "非逐字歌词自动查找 AMLL、Lunabeat 和我的仓库，关闭后仅使用已配置歌词",
            settings.automaticLyricsEnabled,
            enabled = settings.customLyricsEnabled,
            iconDrawable = EmbeddedGlyphDrawable(
                EmbeddedGlyphKind.Exchange,
                EmbeddedSettingsPalette.accent,
            ),
            compactWidePadding = true,
        ) { onSettingsChanged(settings.copy(automaticLyricsEnabled = it)) })
        parent.addView(embeddedSpacer(activity, if (isEmbeddedPhone(activity)) 10 else 14))
        parent.addView(embeddedSettingRow(
            activity,
            "在线补充无歌词歌曲",
            "无歌词时按歌名、歌手和时长在线搜索；需要开启“自动实时补全”，更改后重开 Apple Music 生效",
            settings.onlineLyricsSupplementEnabled,
            enabled = settings.customLyricsEnabled,
            iconDrawable = EmbeddedGlyphDrawable(
                EmbeddedGlyphKind.Exchange,
                EmbeddedSettingsPalette.accent,
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
            "补全歌词翻译与发音",
            "优先使用Apple源，三方在线源兜底；缺少翻译或发音时补齐，需要开启“自动实时补全”，更改后重开 Apple Music 生效",
            settings.onlineLyricsTranslationEnabled,
            enabled = settings.customLyricsEnabled && settings.automaticLyricsEnabled,
            iconDrawable = EmbeddedGlyphDrawable(
                EmbeddedGlyphKind.Exchange,
                EmbeddedSettingsPalette.accent,
            ),
            compactWidePadding = true,
        ) { onSettingsChanged(settings.copy(onlineLyricsTranslationEnabled = it)) })
        parent.addView(embeddedSpacer(activity, if (isEmbeddedPhone(activity)) 10 else 14))
        parent.addView(embeddedSettingRow(
            activity,
            "不显示国语歌拼音",
            "国语（普通话）歌曲不补充在线拼音；粤语歌不受影响",
            settings.onlineLyricsHideMandarinPinyinEnabled,
            enabled = settings.customLyricsEnabled &&
                settings.automaticLyricsEnabled &&
                settings.onlineLyricsTranslationEnabled,
            iconDrawable = EmbeddedGlyphDrawable(
                EmbeddedGlyphKind.Exchange,
                EmbeddedSettingsPalette.accent,
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
            "自动选择来源顺序",
            "按内置顺序（网易云、QQ、酷我、酷狗）依次尝试；关闭后使用自定义顺序",
            settings.onlineLyricsAutomaticOrderEnabled,
            enabled = chainEnabled,
            iconDrawable = EmbeddedGlyphDrawable(
                EmbeddedGlyphKind.Exchange,
                EmbeddedSettingsPalette.accent,
            ),
            compactWidePadding = true,
        ) { onSettingsChanged(settings.copy(onlineLyricsAutomaticOrderEnabled = it)) })
        parent.addView(embeddedSpacer(activity, if (isEmbeddedPhone(activity)) 10 else 14))
        parent.addView(embeddedSettingRow(
            activity,
            "全局最优匹配",
            "开启后为命中的来源候选评分并取最高分；关闭则采用首个达到匹配分数的候选",
            settings.onlineLyricsGlobalBestEnabled,
            enabled = chainEnabled,
            iconDrawable = EmbeddedGlyphDrawable(
                EmbeddedGlyphKind.Exchange,
                EmbeddedSettingsPalette.accent,
            ),
            compactWidePadding = true,
        ) { onSettingsChanged(settings.copy(onlineLyricsGlobalBestEnabled = it)) })

        OnlineLyricSources.DEFAULT_ORDER.forEach { sourceId ->
            parent.addView(embeddedSpacer(activity, if (isEmbeddedPhone(activity)) 10 else 14))
            parent.addView(embeddedSettingRow(
                activity,
                embeddedOnlineLyricsSourceLabel(sourceId),
                "无歌词时搜索该来源",
                settings.onlineLyricsSourceEnabled(sourceId),
                enabled = chainEnabled,
                iconDrawable = EmbeddedGlyphDrawable(
                    EmbeddedGlyphKind.DocumentSearch,
                    EmbeddedSettingsPalette.accent,
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
                hint = "搜索名称或 Apple Music ID"
                textSize = embeddedTextSize(activity, 14f, 13f)
                inputType = InputType.TYPE_CLASS_TEXT
                imeOptions = EditorInfo.IME_ACTION_SEARCH
                showSoftInputOnFocus = true
                isSingleLine = true
                includeFontPadding = false
                setText(customLyricsSearchQuery)
                setPadding(dp(activity, 12), 0, dp(activity, 12), 0)
                setTextColor(EmbeddedSettingsPalette.onSurface)
                setHintTextColor(EmbeddedSettingsPalette.onSurfaceVariant)
                background = null
            }
            addView(FrameLayout(activity).apply {
                background = GradientDrawable().apply {
                    setColor(EmbeddedSettingsPalette.softBackground)
                    cornerRadius = dp(activity, 8).toFloat()
                }
                addView(ImageView(activity).apply {
                    setImageDrawable(
                        EmbeddedGlyphDrawable(
                            EmbeddedGlyphKind.Search,
                            EmbeddedSettingsPalette.onSurfaceVariant,
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
                    text = "已配置"
                    textSize = embeddedTextSize(activity, 14f, 13f)
                    setTextColor(EmbeddedSettingsPalette.accent)
                    setTypeface(typeface, Typeface.BOLD)
                }, LinearLayout.LayoutParams(0, dp(activity, 28), 1f))
                addView(TextView(activity).apply {
                    text = "${entries.size} 首"
                    textSize = embeddedTextSize(activity, 13f, 13f)
                    gravity = Gravity.CENTER_VERTICAL or Gravity.END
                    setTextColor(EmbeddedSettingsPalette.accent)
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
                            setImageDrawable(
                                EmbeddedGlyphDrawable(
                                    EmbeddedGlyphKind.DocumentSearch,
                                    EmbeddedSettingsPalette.disabledText,
                                ),
                            )
                            contentDescription = null
                            setPadding(dp(activity, 8), dp(activity, 8), dp(activity, 8), dp(activity, 8))
                        }, LinearLayout.LayoutParams(dp(activity, 56), dp(activity, 56)))
                        addView(TextView(activity).apply {
                            text = if (entries.isEmpty()) "暂无自定义歌词" else "未找到匹配结果"
                            textSize = 15f
                            gravity = Gravity.CENTER
                            setTextColor(EmbeddedSettingsPalette.onSurfaceVariant)
                            setSingleLine(false)
                            setPadding(0, dp(activity, 8), 0, 0)
                        }, matchWidthWrapContent())
                        addView(TextView(activity).apply {
                            text = if (entries.isEmpty()) "添加歌词后会显示在这里" else "尝试更换关键词或检查 ID 是否正确"
                            textSize = 12.5f
                            gravity = Gravity.CENTER
                            setTextColor(EmbeddedSettingsPalette.disabledText)
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
                    text = "已显示 ${state.visibleCount} / 共 ${state.totalCount} 首"
                    textSize = 13f
                    setTextColor(EmbeddedSettingsPalette.onSurfaceVariant)
                    setPadding(dp(activity, 16), dp(activity, 8), dp(activity, 16), dp(activity, 12))
                })
                if (state.hasMore) {
                    entriesRegion.addView(LinearLayout(activity).apply {
                        orientation = embeddedActionOrientation(activity)
                        setPadding(dp(activity, 12), 0, dp(activity, 12), dp(activity, 12))
                        addView(embeddedActionButton(activity, "加载更多") {
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
        background = GradientDrawable().apply {
            setColor(Color.WHITE)
            setStroke(dp(activity, 1), EmbeddedSettingsPalette.outline)
            cornerRadius = dp(activity, 8).toFloat()
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
                append(" · 共 ")
                append(group.entries.size)
                append(" 个 ID")
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
                    setTextColor(EmbeddedSettingsPalette.onSurface)
                    typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
                    setSingleLine(false)
                    maxLines = 2
                }, matchWidthWrapContent())
                addView(TextView(activity).apply {
                    text = secondary
                    textSize = embeddedTextSize(activity, 12.5f, 12f)
                    setTextColor(EmbeddedSettingsPalette.onSurfaceVariant)
                    setPadding(0, dp(activity, 2), 0, 0)
                    setSingleLine(false)
                    maxLines = 3
                }, matchWidthWrapContent())
            }, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply {
                marginEnd = dp(activity, 4)
            })
            addView(Switch(activity).apply {
                isChecked = group.allEnabled
                contentDescription = "${entry.displayName} 自定义歌词开关"
                thumbTintList = embeddedSwitchThumbColors()
                trackTintList = embeddedSwitchTrackColors()
                setOnCheckedChangeListener { _, checked ->
                    runAsync(activity) { controller.setLyricsEnabled(group.appleMusicIds, checked) }
                }
            }, LinearLayout.LayoutParams(dp(activity, if (isEmbeddedPhone(activity)) 56 else 48), dp(activity, 44)))
            addView(ImageView(activity).apply {
                setImageDrawable(
                    EmbeddedGlyphDrawable(
                        EmbeddedGlyphKind.MoreVertical,
                        EmbeddedSettingsPalette.onSurfaceVariant,
                    ),
                )
                scaleType = ImageView.ScaleType.CENTER
                contentDescription = "更多歌词操作"
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
    AlertDialog.Builder(activity)
        .setTitle("清空检索库")
        .setMessage(
            "将删除所有地区已缓存的歌曲信息与原地区原名结果，下次读取时重新向 Apple Music 获取。" +
                "歌词、字体等自定义内容不受影响。",
        )
        .setNegativeButton("取消", null)
        .setPositiveButton("清空") { _, _ -> onConfirmed() }
        .show()
}


internal fun EmbeddedSettingsHost.confirmEmbeddedLyricsDelete(activity: Activity, group: CustomLyricsUiGroup) {
        val entry = group.primary
        AlertDialog.Builder(activity)
            .setMessage(
                "删除“${entry.displayName.ifBlank { entry.appleMusicId.toString() }}”及其 " +
                    "${group.entries.size} 个 Apple Music ID 的 TTML 映射？",
            )
            .setNegativeButton("取消", null)
            .setPositiveButton("删除") { _, _ ->
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
        PopupMenu(activity, anchor).apply {
            menu.add("编辑").setOnMenuItemClickListener {
                showLyricsEditor(activity, group, song)
                true
            }
            menu.add("删除").setOnMenuItemClickListener {
                confirmEmbeddedLyricsDelete(activity, group)
                true
            }
            menu.add("复制 Apple Music ID").setOnMenuItemClickListener {
                val clipboard = activity.getSystemService(Context.CLIPBOARD_SERVICE) as? android.content.ClipboardManager
                clipboard?.setPrimaryClip(
                    android.content.ClipData.newPlainText(
                        "Apple Music ID",
                        group.appleMusicIds.joinToString(","),
                    ),
                )
                Toast.makeText(activity, "已复制 Apple Music ID", Toast.LENGTH_SHORT).show()
                true
            }
            show()
        }
    }


internal fun EmbeddedSettingsHost.embeddedCustomLyricsSourceName(source: String): String = when (source) {
        CustomLyricsSources.AUTO_CACHE -> "自动缓存"
        CustomLyricsSources.AMLL -> "AMLL"
        CustomLyricsSources.AM_LYRICS -> "AM-Lyrics 仓库"
        CustomLyricsSources.LUNABEAT -> "Lunabeat"
        CustomLyricsSources.KUWO -> "酷我"
        CustomLyricsSources.NETEASE -> "网易云"
        CustomLyricsSources.QQ -> "QQ 音乐"
        CustomLyricsSources.KUGOU -> "酷狗"
        else -> "手动 TTML"
    }

/** Display name for one selectable online chain source id. */
internal fun embeddedOnlineLyricsSourceLabel(sourceId: String): String = when (sourceId) {
    CustomLyricsSources.NETEASE -> "网易云音乐"
    CustomLyricsSources.QQ -> "QQ 音乐"
    CustomLyricsSources.KUWO -> "酷我音乐"
    CustomLyricsSources.KUGOU -> "酷狗音乐"
    else -> sourceId
}

