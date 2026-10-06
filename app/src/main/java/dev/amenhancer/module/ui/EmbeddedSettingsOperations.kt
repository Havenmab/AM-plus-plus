package dev.amenhancer.module.ui

import dev.amenhancer.module.i18n.ModuleText

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
            text = localizedText(ModuleText.CHECKING_LYRICS)
            textSize = 15f
            setTextColor(EmbeddedSettingsPalette.onSurface)
            setSingleLine(false)
            setPadding(dp(activity, 24), dp(activity, 8), dp(activity, 24), dp(activity, 8))
        }
        val dialog = AlertDialog.Builder(activity)
            .setTitle(localizedText(ModuleText.LYRICS_UPDATE))
            .setView(progress)
            .setNegativeButton(localizedText(ModuleText.CANCEL)) { _, _ -> cancelled.set(true) }
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
                                    localizedText(ModuleText.LYRICS_UPDATE_PROGRESS,
                                        update.checkedEntries, update.totalEntries, update.updatedEntries,
                                        update.unchangedEntries, update.skippedEntries, update.failedEntries)
                            }
                        }
                    },
                )
            }.getOrElse { error ->
                CustomLyricsUpdateResult.Failed(
                    localizedText(ModuleText.LYRICS_UPDATE_FAILED, error.message.orEmpty()),
                )
            }
            mainHandler.post {
                if (dialog.isShowing) dialog.dismiss()
                val current = currentActivity() ?: return@post
                when (result) {
                    is CustomLyricsUpdateResult.Updated -> Toast.makeText(
                        current,
                        localizedText(ModuleText.LYRICS_UPDATE_COMPLETE, result.checked, result.updated,
                            result.unchanged, result.skipped, result.failed),
                        Toast.LENGTH_LONG,
                    ).show()
                    CustomLyricsUpdateResult.Cancelled -> Toast.makeText(
                        current,
                        localizedText(ModuleText.LYRICS_UPDATE_CANCELLED),
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
                Toast.makeText(activity, localizedText(ModuleText.PICKER_OPEN_FAILED), Toast.LENGTH_SHORT).show()
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
                                    localizedText(ModuleText.SELECTED_TTML_INVALID),
                                    Toast.LENGTH_SHORT,
                                ).show()
                            } else {
                                editorImport(imported)
                                Toast.makeText(current, localizedText(ModuleText.TTML_IMPORTED), Toast.LENGTH_SHORT).show()
                            }
                        }
                    }
                } else {
                    val song = controller.currentSongDetails()
                    if (song == null) {
                        Toast.makeText(activity, localizedText(ModuleText.NO_CURRENT_SONG), Toast.LENGTH_SHORT).show()
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
            .setTitle(localizedText(ModuleText.RESTORE_LYRICS_BACKUP))
            .setMessage(localizedText(ModuleText.RESTORE_CONFLICT_NOTICE))
            .setNegativeButton(localizedText(ModuleText.CANCEL), null)
            .setNeutralButton(localizedText(ModuleText.KEEP_EXISTING)) { _, _ ->
                runAsync(activity) {
                    controller.restoreLyrics(uri, CustomLyricsRestorePolicy.KEEP_EXISTING)
                }
            }
            .setPositiveButton(localizedText(ModuleText.OVERWRITE)) { _, _ ->
                runAsync(activity) {
                    controller.restoreLyrics(uri, CustomLyricsRestorePolicy.OVERWRITE)
                }
            }
            .show()
    }


internal fun EmbeddedSettingsHost.runAsync(activity: Activity, action: () -> EmbeddedActionResult) {
        Toast.makeText(activity, localizedText(ModuleText.PROCESSING), Toast.LENGTH_SHORT).show()
        worker.execute {
            val result = runCatching(action).getOrElse {
                EmbeddedActionResult.Failed(it.message.orEmpty().ifBlank { localizedText(ModuleText.OPERATION_FAILED) })
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

