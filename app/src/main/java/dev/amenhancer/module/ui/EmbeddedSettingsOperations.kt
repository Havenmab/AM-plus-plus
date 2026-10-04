package dev.amenhancer.module.ui

import android.app.Activity
import android.app.AlertDialog
import android.content.Intent
import android.net.Uri
import android.widget.TextView
import android.widget.Toast
import dev.amenhancer.module.lyrics.CustomLyricsRestorePolicy
import dev.amenhancer.module.lyrics.CustomLyricsUpdateResult
import java.util.concurrent.atomic.AtomicBoolean

internal fun EmbeddedSettingsHost.updateEmbeddedLyrics(activity: Activity) {
        val cancelled = AtomicBoolean(false)
        val progress = TextView(activity).apply {
            text = "正在检查歌词…"
            textSize = 15f
            setTextColor(EmbeddedSettingsPalette.onSurface)
            setSingleLine(false)
            setPadding(dp(activity, 24), dp(activity, 8), dp(activity, 24), dp(activity, 8))
        }
        val dialog = AlertDialog.Builder(activity)
            .setTitle("歌词更新")
            .setView(progress)
            .setNegativeButton("取消") { _, _ -> cancelled.set(true) }
            .create()
        dialog.setCanceledOnTouchOutside(false)
        dialog.setOnCancelListener { cancelled.set(true) }
        dialog.show()
        worker.execute {
            val result = runCatching {
                controller.updateLyrics(
                    isCancelled = cancelled::get,
                    onProgress = { update ->
                        mainHandler.post {
                            if (dialog.isShowing) {
                                progress.text =
                                    "正在检查 ${update.checkedEntries}/${update.totalEntries} 条歌词…\n" +
                                        "更新 ${update.updatedEntries} · 无变化 ${update.unchangedEntries} · " +
                                        "跳过 ${update.skippedEntries} · 失败 ${update.failedEntries}"
                            }
                        }
                    },
                )
            }.getOrElse { error ->
                CustomLyricsUpdateResult.Failed(
                    "歌词更新失败：${error.message.orEmpty()}",
                )
            }
            mainHandler.post {
                if (dialog.isShowing) dialog.dismiss()
                val current = currentActivity() ?: return@post
                when (result) {
                    is CustomLyricsUpdateResult.Updated -> Toast.makeText(
                        current,
                        "歌词更新完成：检查 ${result.checked} 条，更新 ${result.updated} 条，" +
                            "无变化 ${result.unchanged} 条，跳过 ${result.skipped} 条，失败 ${result.failed} 条",
                        Toast.LENGTH_LONG,
                    ).show()
                    CustomLyricsUpdateResult.Cancelled -> Toast.makeText(
                        current,
                        "歌词更新已取消",
                        Toast.LENGTH_SHORT,
                    ).show()
                    is CustomLyricsUpdateResult.Failed -> Toast.makeText(
                        current,
                        result.message,
                        Toast.LENGTH_LONG,
                    ).show()
                }
                if (result is CustomLyricsUpdateResult.Updated) pageRefresh?.invoke()
            }
        }
    }


internal fun EmbeddedSettingsHost.launchSafPicker(
        activity: Activity,
        operation: EmbeddedSafOperation,
        mimeType: String,
        extraMimeTypes: Array<String>? = null,
    ) {
        val requestCode = safRouter.begin(operation)
        val intent = Intent(
            if (operation == EmbeddedSafOperation.Backup) {
                Intent.ACTION_CREATE_DOCUMENT
            } else {
                Intent.ACTION_OPEN_DOCUMENT
            },
        )
            .addCategory(Intent.CATEGORY_OPENABLE)
            .setType(mimeType)
        extraMimeTypes?.let { intent.putExtra(Intent.EXTRA_MIME_TYPES, it) }
        if (operation == EmbeddedSafOperation.Backup) {
            intent.putExtra(Intent.EXTRA_TITLE, "AMPP-lyrics-backup.zip")
        }
        runCatching { activity.startActivityForResult(intent, requestCode) }
            .onFailure {
                safRouter.route(requestCode, EmbeddedSafResult.RESULT_CANCELED, null)
                Toast.makeText(activity, "无法打开文件选择器", Toast.LENGTH_SHORT).show()
            }
    }


internal fun EmbeddedSettingsHost.handleSafSelection(operation: EmbeddedSafOperation, uri: Uri) {
        val activity = currentActivity() ?: return
        when (operation) {
            EmbeddedSafOperation.PluginZip -> importPluginZip(uri)
            EmbeddedSafOperation.Font -> runAsync(activity) { controller.importFont(uri) }
            EmbeddedSafOperation.Ttml -> {
                val editorImport = pendingTtmlImport
                pendingTtmlImport = null
                if (editorImport != null) {
                    worker.execute {
                        val imported = controller.readTtml(uri)
                        mainHandler.post {
                            val current = currentActivity() ?: return@post
                            if (imported == null) {
                                Toast.makeText(
                                    current,
                                    "所选文件不是有效且不超过 512 KiB 的 TTML",
                                    Toast.LENGTH_SHORT,
                                ).show()
                            } else {
                                editorImport(imported)
                                Toast.makeText(current, "TTML 已导入，请确认后保存", Toast.LENGTH_SHORT).show()
                            }
                        }
                    }
                } else {
                    val song = controller.currentSongDetails()
                    if (song == null) {
                        Toast.makeText(activity, "尚未捕获当前歌曲", Toast.LENGTH_SHORT).show()
                    } else {
                        runAsync(activity) {
                            val replacing = controller.lyricsEntries()
                                .firstOrNull { it.appleMusicId == song.appleMusicId }
                                ?.appleMusicId
                            controller.importTtml(
                                uri,
                                song.appleMusicId,
                                song.title.orEmpty().ifBlank { song.appleMusicId.toString() },
                                replacing,
                            )
                        }
                    }
                }
            }
            EmbeddedSafOperation.Backup -> runAsync(activity) { controller.backupLyrics(uri) }
            EmbeddedSafOperation.RestoreOverwrite -> confirmEmbeddedRestore(activity, uri)
            EmbeddedSafOperation.RestoreKeepExisting -> runAsync(activity) {
                controller.restoreLyrics(uri, CustomLyricsRestorePolicy.KEEP_EXISTING)
            }
        }
    }


internal fun EmbeddedSettingsHost.confirmEmbeddedRestore(activity: Activity, uri: Uri) {
        AlertDialog.Builder(activity)
            .setTitle("恢复歌词备份")
            .setMessage("覆盖：冲突歌词使用备份版本；不覆盖：冲突歌词保留当前版本。")
            .setNegativeButton("取消", null)
            .setNeutralButton("不覆盖") { _, _ ->
                runAsync(activity) {
                    controller.restoreLyrics(uri, CustomLyricsRestorePolicy.KEEP_EXISTING)
                }
            }
            .setPositiveButton("覆盖") { _, _ ->
                runAsync(activity) {
                    controller.restoreLyrics(uri, CustomLyricsRestorePolicy.OVERWRITE)
                }
            }
            .show()
    }


internal fun EmbeddedSettingsHost.runAsync(activity: Activity, action: () -> EmbeddedActionResult) {
        Toast.makeText(activity, "处理中…", Toast.LENGTH_SHORT).show()
        worker.execute {
            val result = runCatching(action).getOrElse {
                EmbeddedActionResult.Failed(it.message.orEmpty().ifBlank { "操作失败" })
            }
            mainHandler.post {
                val current = currentActivity() ?: return@post
                val message = when (result) {
                    is EmbeddedActionResult.Done -> result.message
                    is EmbeddedActionResult.Failed -> result.message
                }
                Toast.makeText(
                    current,
                    message,
                    if (result is EmbeddedActionResult.Done) Toast.LENGTH_LONG else Toast.LENGTH_SHORT,
                ).show()
                if (result is EmbeddedActionResult.Done) {
                    pageRefresh?.invoke() ?: dismissDialog()
                }
            }
        }
    }

