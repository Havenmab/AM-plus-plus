package dev.amenhancer.module.ui

import android.app.Activity
import android.app.AlertDialog
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.view.Gravity
import android.view.ViewGroup
import android.view.WindowManager
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import dev.amenhancer.module.CurrentSongDetails
import dev.amenhancer.module.lyrics.CustomLyricsMultiIdDraft
import dev.amenhancer.module.lyrics.CustomLyricsOnlineImportResult
import dev.amenhancer.module.lyrics.CustomLyricsOnlineImporter
import dev.amenhancer.module.model.CustomLyricsEntry
import dev.amenhancer.module.model.CustomLyricsSources

internal fun EmbeddedSettingsHost.showLyricsEditor(
        activity: Activity,
        group: CustomLyricsUiGroup?,
        song: CurrentSongDetails?,
    ) {
        val entry = group?.primary
        var source = entry?.source ?: CustomLyricsSources.MANUAL
        val fields = LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(activity, 20), dp(activity, 4), dp(activity, 20), dp(activity, 4))
        }
        val idInput = embeddedLyricsEditorInput(
            activity = activity,
            hint = "Apple Music ID",
            initial = group?.appleMusicIds?.let(CustomLyricsIdParser::format)
                ?: song?.appleMusicId?.toString().orEmpty(),
        )
        val nameInput = embeddedLyricsEditorInput(
            activity = activity,
            hint = "显示名称",
            initial = entry?.displayName ?: song?.title.orEmpty(),
        )
        val ttmlInput = embeddedLyricsEditorInput(
            activity = activity,
            hint = "TTML 内容",
            initial = entry?.let { controller.readLyrics(it.appleMusicId) }.orEmpty(),
            multiline = true,
        )
        val sourceLabel = TextView(activity).apply {
            textSize = embeddedTextSize(activity, 14f, 13f)
            setTextColor(EmbeddedSettingsPalette.onSurfaceVariant)
            setSingleLine(false)
            setPadding(0, dp(activity, 8), 0, dp(activity, 8))
        }
        fun updateSourceLabel() {
            sourceLabel.text = "当前来源：${embeddedLyricsSourceName(source)}"
        }
        fun importOnline(sourceToImport: EmbeddedOnlineSource) {
            importEmbeddedOnlineLyrics(
                activity = activity,
                source = sourceToImport,
                appleMusicIdInput = idInput,
                ttmlInput = ttmlInput,
            ) { importedSource ->
                source = importedSource
                updateSourceLabel()
            }
        }
        updateSourceLabel()
        fields.addView(idInput, LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT,
            dp(activity, 56),
        ).apply { bottomMargin = dp(activity, 2) })
        fields.addView(nameInput, LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT,
            dp(activity, 56),
        ).apply { bottomMargin = dp(activity, 2) })
        fields.addView(sourceLabel, LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT,
            ViewGroup.LayoutParams.WRAP_CONTENT,
        ))
        fields.addView(
            embeddedLyricsEditorActionRows(
                activity,
                listOf(
                    EmbeddedLyricsEditorAction("导入 TTML") {
                        pendingTtmlImport = { imported ->
                            ttmlInput.setText(imported)
                            source = CustomLyricsSources.MANUAL
                            updateSourceLabel()
                        }
                        launchSafPicker(
                            activity,
                            EmbeddedSafOperation.Ttml,
                            "application/xml",
                            arrayOf("application/ttml+xml", "application/xml", "text/xml", "text/plain"),
                        )
                    },
                    EmbeddedLyricsEditorAction("获取 ID") {
                        requestCurrentSongId(activity, idInput, nameInput)
                    },
                    EmbeddedLyricsEditorAction(
                        label = "从 AMLL 导入",
                        compactLabel = "AMLL 导入",
                    ) {
                        importOnline(EmbeddedOnlineSource.AMLL)
                    },
                    EmbeddedLyricsEditorAction(
                        label = "从 Lunabeat 导入",
                        compactLabel = "Lunabeat 导入",
                    ) {
                        importOnline(EmbeddedOnlineSource.LUNABEAT)
                    },
                    EmbeddedLyricsEditorAction(
                        label = "从 GitHub 导入",
                        compactLabel = "GitHub 导入",
                    ) {
                        importOnline(EmbeddedOnlineSource.AM_LYRICS)
                    },
                ),
            ),
            matchWidthWrapContent(),
        )
        fields.addView(ttmlInput, LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT,
            ViewGroup.LayoutParams.WRAP_CONTENT,
        ).apply { topMargin = dp(activity, 8) })
        val scroll = ScrollView(activity).apply {
            isFillViewport = true
            isVerticalScrollBarEnabled = false
            addView(fields)
        }
        lateinit var dialog: AlertDialog
        fun saveLyrics() {
                val ids = CustomLyricsIdParser.parse(idInput.text.toString())
                if (ids == null) {
                    idInput.error = "请输入一个或多个正整数 Apple Music ID（用逗号分隔）"
                } else if (ttmlInput.text.toString().isBlank()) {
                    ttmlInput.error = "请输入或导入 TTML"
                } else {
                    runAsync(activity) {
                        saveMany(
                            CustomLyricsMultiIdDraft(
                                appleMusicIds = ids,
                                displayName = nameInput.text.toString(),
                                ttml = ttmlInput.text.toString(),
                                source = source,
                                enabled = entry?.enabled ?: true,
                            ),
                            group?.appleMusicIds.orEmpty(),
                        )
                    }
                    dialog.dismiss()
                }
        }
        val titleBar = LinearLayout(activity).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            val horizontalPadding = dp(activity, if (isEmbeddedPhone(activity)) 16 else 20)
            setPadding(horizontalPadding, 0, horizontalPadding, 0)
            addView(TextView(activity).apply {
                text = if (group == null) "新增歌词" else "编辑歌词"
                textSize = embeddedTextSize(activity, 19f, 18f)
                setTextColor(EmbeddedSettingsPalette.onSurface)
                setTypeface(typeface, if (isEmbeddedPhone(activity)) Typeface.BOLD else Typeface.NORMAL)
                gravity = Gravity.CENTER_VERTICAL
            }, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.MATCH_PARENT, 1f))
        }
        val editorActions = LinearLayout(activity).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL or Gravity.END
            val horizontalPadding = dp(activity, if (isEmbeddedPhone(activity)) 16 else 12)
            setPadding(horizontalPadding, 0, horizontalPadding, 0)
        }
        fun editorAction(label: String, onClick: () -> Unit): TextView = TextView(activity).apply {
            text = label
            textSize = embeddedTextSize(activity, 16f, 14f)
            setTextColor(EmbeddedSettingsPalette.primary)
            gravity = Gravity.CENTER
            isClickable = true
            isFocusable = true
            setOnClickListener { onClick() }
        }
        editorActions.addView(
            editorAction("取消") { dialog.dismiss() },
            LinearLayout.LayoutParams(
                dp(activity, if (isEmbeddedPhone(activity)) 64 else 56),
                dp(activity, if (isEmbeddedPhone(activity)) 56 else 48),
            ),
        )
        editorActions.addView(
            editorAction("保存", ::saveLyrics),
            LinearLayout.LayoutParams(
                dp(activity, if (isEmbeddedPhone(activity)) 64 else 56),
                dp(activity, if (isEmbeddedPhone(activity)) 56 else 48),
            ),
        )
        val editorRoot = LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
            minimumHeight = embeddedLyricsEditorDialogHeight(activity)
            background = GradientDrawable().apply {
                setColor(EmbeddedSettingsPalette.pageBackground)
                cornerRadius = embeddedCardCornerRadius(activity)
            }
            clipToOutline = true
            addView(titleBar, LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                embeddedTopBarHeight(activity),
            ))
            addView(scroll, LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                0,
                1f,
            ))
            addView(editorActions, LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                dp(activity, if (isEmbeddedPhone(activity)) 56 else 48),
            ))
        }
        dialog = AlertDialog.Builder(activity)
            .setView(editorRoot)
            .create()
        dialog.setOnDismissListener {
            if (pendingTtmlImport != null) pendingTtmlImport = null
        }
        dialog.setOnShowListener {
            dialog.window?.setBackgroundDrawable(android.graphics.drawable.ColorDrawable(Color.TRANSPARENT))
            dialog.window?.setLayout(
                embeddedLyricsEditorDialogWidth(activity),
                android.view.WindowManager.LayoutParams.WRAP_CONTENT,
            )
        }
        dialog.show()
    }

    /** Compatibility overload used by the legacy embedded dialog path. */

internal fun EmbeddedSettingsHost.showLyricsEditor(
        activity: Activity,
        entry: CustomLyricsEntry?,
        song: CurrentSongDetails?,
    ) = showLyricsEditor(
        activity,
        entry?.let { CustomLyricsUiGroup(listOf(it)) },
        song,
    )


internal fun EmbeddedSettingsHost.requestCurrentSongId(
        activity: Activity,
        appleMusicId: EditText,
        displayName: EditText,
    ) {
        val currentSong = controller.currentSongDetails()
        if (currentSong == null) {
            Toast.makeText(
                activity,
                "未获取到当前歌曲信息，请先在 Apple Music 播放一首歌",
                Toast.LENGTH_SHORT,
            ).show()
            return
        }
        appleMusicId.setText(currentSong.appleMusicId.toString())
        appleMusicId.setSelection(appleMusicId.length())
        listOfNotNull(
            currentSong.title?.takeIf(String::isNotBlank),
            currentSong.artist?.takeIf(String::isNotBlank),
        ).joinToString(" - ").takeIf(String::isNotBlank)?.let { value ->
            displayName.setText(value)
            displayName.setSelection(displayName.length())
        }
        Toast.makeText(activity, "已获取当前歌曲信息", Toast.LENGTH_SHORT).show()
    }

    /** Keeps the editor's multi-ID operation explicit at the host boundary. */

internal fun EmbeddedSettingsHost.saveMany(
        draft: CustomLyricsMultiIdDraft,
        replacingAppleMusicIds: List<Long>,
    ): EmbeddedActionResult = controller.saveLyrics(draft, replacingAppleMusicIds)


internal fun EmbeddedSettingsHost.importEmbeddedOnlineLyrics(
        activity: Activity,
        source: EmbeddedOnlineSource,
        appleMusicIdInput: EditText,
        ttmlInput: EditText,
        onImported: (String) -> Unit,
    ) {
        val appleMusicId = appleMusicIdInput.text.toString().toLongOrNull()
        when (source) {
            EmbeddedOnlineSource.AMLL,
            EmbeddedOnlineSource.AM_LYRICS,
            EmbeddedOnlineSource.LUNABEAT,
            -> if (appleMusicId == null || appleMusicId <= 0L) {
                appleMusicIdInput.error = "请输入正整数 Apple Music ID"
                return
            }
        }

        Toast.makeText(activity, "正在获取歌词…", Toast.LENGTH_SHORT).show()
        worker.execute {
            val result = runCatching {
                val importer = embeddedOnlineLyricsImporter()
                when (source) {
                    EmbeddedOnlineSource.AMLL -> importer.importAmll(requireNotNull(appleMusicId))
                    EmbeddedOnlineSource.AM_LYRICS -> importer.importAmLyrics(requireNotNull(appleMusicId))
                    EmbeddedOnlineSource.LUNABEAT -> importer.importLunabeat(requireNotNull(appleMusicId))
                }
            }.getOrElse {
                CustomLyricsOnlineImportResult.Failed(
                    it.message.orEmpty().ifBlank { "在线导入失败" },
                )
            }
            mainHandler.post {
                if (currentActivity() !== activity) return@post
                when (result) {
                    is CustomLyricsOnlineImportResult.Imported -> {
                        ttmlInput.setText(result.ttml)
                        onImported(result.source)
                        val reformatNote = if (result.reformatted) "，已自动转为 Apple Music 格式" else ""
                        Toast.makeText(
                            activity,
                            "已导入 ${embeddedLyricsSourceName(result.source)} 歌词$reformatNote，请确认后保存",
                            Toast.LENGTH_LONG,
                        ).show()
                    }
                    is CustomLyricsOnlineImportResult.Failed -> {
                        Toast.makeText(activity, result.message, Toast.LENGTH_LONG).show()
                    }
                }
            }
        }
    }


internal fun EmbeddedSettingsHost.embeddedOnlineLyricsImporter(): CustomLyricsOnlineImporter =
        dev.amenhancer.module.lyrics.createEmbeddedOnlineLyricsImporter(application)


internal fun EmbeddedSettingsHost.embeddedLyricsSourceName(source: String): String = when (source) {
        CustomLyricsSources.AUTO_CACHE -> "自动缓存"
        CustomLyricsSources.AMLL -> "AMLL"
        CustomLyricsSources.AM_LYRICS -> "AM-Lyrics 仓库"
        CustomLyricsSources.LUNABEAT -> "Lunabeat"
        CustomLyricsSources.KUWO -> "酷我"
        else -> "手动 TTML"
    }

