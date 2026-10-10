package dev.amenhancer.module.ui

import dev.amenhancer.module.i18n.ModuleText

import android.app.Activity
import android.app.AlertDialog
import android.net.Uri
import android.os.Build
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.Toast
import dev.amenhancer.plugin.runtime.PluginRunState
import java.lang.ref.WeakReference
import java.util.concurrent.atomic.AtomicBoolean

internal fun EmbeddedSettingsHost.showPluginManagement(activity: Activity) {
    val manager = plugins ?: return Toast.makeText(activity, localizedText(ModuleText.PLUGIN_RUNTIME_UNAVAILABLE), Toast.LENGTH_LONG).show()
    val content = LinearLayout(activity).apply { orientation = LinearLayout.VERTICAL; setPadding(dp(activity, 12), 0, dp(activity, 12), 0) }
    val scroll = ScrollView(activity).apply { addView(content) }
    val dialog = embeddedDialogBuilder(activity).setTitle(localizedText(ModuleText.PLUGINS)).setView(scroll)
        .setPositiveButton(localizedText(ModuleText.IMPORT_ZIP), null).setNegativeButton(localizedText(ModuleText.CLOSE), null).create()
    pluginDialogs += WeakReference(dialog)
    val refreshing = AtomicBoolean()
    fun action(block: () -> Unit) {
        manager.execute {
            val result = runCatching(block)
            mainHandler.post {
                currentActivity()?.let { Toast.makeText(it, result.exceptionOrNull()?.message ?: localizedText(ModuleText.SAVED_RESTART), Toast.LENGTH_LONG).show() }
            }
        }
    }
    val refresh = object : Runnable {
        override fun run() {
            if (!dialog.isShowing || !refreshing.compareAndSet(false, true)) return
            manager.execute {
                val statuses = runCatching { manager.statuses() }
                mainHandler.post {
                    refreshing.set(false)
                    if (!dialog.isShowing || activity.isDestroyed) return@post
                    val scrollPosition = scroll.scrollY
                    content.removeAllViews()
                    content.addView(embeddedInfoCard(activity, localizedText(ModuleText.PLUGIN_RESTART_NOTICE)))
                    manager.startupError?.let { content.addView(embeddedInfoCard(activity, localizedText(ModuleText.LOAD_ERROR, it))) }
                    statuses.onFailure { content.addView(embeddedInfoCard(activity, localizedText(ModuleText.PLUGINS_READ_FAILED, it.message))) }
                    if (statuses.getOrNull()?.isEmpty() == true) content.addView(embeddedInfoCard(activity, localizedText(ModuleText.NO_PLUGINS)))
                    statuses.getOrDefault(emptyList()).forEach { status ->
                        val manifest = status.installed.manifest
                        val state = when (status.state) {
                            PluginRunState.ACTIVE -> localizedText(ModuleText.PLUGIN_ACTIVE)
                            PluginRunState.DISABLED -> localizedText(ModuleText.PLUGIN_DISABLED)
                            PluginRunState.LOADING -> localizedText(ModuleText.PLUGIN_LOADING)
                            PluginRunState.BLOCKED -> localizedText(ModuleText.PLUGIN_BLOCKED)
                            PluginRunState.UNSUPPORTED -> localizedText(ModuleText.PLUGIN_UNSUPPORTED)
                            PluginRunState.FAILED -> localizedText(ModuleText.PLUGIN_FAILED)
                        }
                        content.addView(embeddedSpacer(activity, 12))
                        content.addView(embeddedCard(activity, manifest.name) {
                            addView(embeddedInfoCard(activity, localizedText(ModuleText.PLUGIN_STATUS_DETAILS, manifest.author, manifest.versionName, manifest.id, state, status.runningVersion?.let { localizedText(ModuleText.PLUGIN_RUNNING_VERSION, it) }.orEmpty(), status.message)))
                            addView(embeddedSettingRow(activity, localizedText(ModuleText.ENABLE_NEXT_START), if (status.pendingRestart) localizedText(ModuleText.RESTART_PENDING) else "", status.installed.enabled) { enabled ->
                                action { manager.store.setEnabled(manifest.id, enabled) }
                            })
                            if (status.conflicts.isNotEmpty()) addView(embeddedNavigationRow(activity, localizedText(ModuleText.CONFLICT_DETAILS), localizedText(ModuleText.ITEM_COUNT, status.conflicts.size)) {
                                val details = status.conflicts.joinToString("\n\n") {
                                    localizedText(ModuleText.PLUGIN_CONFLICT_DETAILS,
                                        localizedText(if (it.blocking) ModuleText.CONFLICT_BLOCKING else ModuleText.CONFLICT_WARNING),
                                        it.reason, it.target, it.owners.joinToString(" / "))
                                }
                                val info = embeddedDialogBuilder(activity).setTitle(localizedText(ModuleText.CONFLICT_DETAILS)).setMessage(details).setPositiveButton(localizedText(ModuleText.CLOSE), null).create()
                                pluginDialogs += WeakReference(info); info.show()
                            })
                            if (status.state == PluginRunState.ACTIVE) addView(embeddedNavigationRow(activity, localizedText(ModuleText.PLUGIN_SETTINGS), "") { openPluginSettings(activity, manifest.id) })
                            addView(Button(activity).apply {
                                bindEmbeddedButtonTheme(activity)
                                text = localizedText(ModuleText.DELETE)
                                setOnClickListener {
                                    val confirm = embeddedDialogBuilder(activity).setTitle(localizedText(ModuleText.DELETE_NAMED_ITEM, manifest.name))
                                        .setMessage(localizedText(ModuleText.PLUGIN_DELETE_NOTICE))
                                        .setPositiveButton(localizedText(ModuleText.DELETE)) { _, _ -> action { manager.store.delete(manifest.id) } }.setNegativeButton(localizedText(ModuleText.CANCEL), null).create()
                                    pluginDialogs += WeakReference(confirm); confirm.show()
                                }
                            })
                        })
                    }
                    mainHandler.postDelayed(this, 1500)
                    scroll.post { if (dialog.isShowing) scroll.scrollTo(0, scrollPosition) }
                }
            }
        }
    }
    dialog.setOnDismissListener { mainHandler.removeCallbacks(refresh); pluginDialogs.removeAll { it.get() == null || it.get() === dialog } }
    dialog.show()
    dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
        launchSafPicker(activity, EmbeddedSafOperation.PluginZip, "*/*", arrayOf("application/zip", "application/x-zip-compressed", "application/octet-stream"))
    }
    mainHandler.post(refresh)
}

internal fun EmbeddedSettingsHost.importPluginZip(uri: Uri) {
    val manager = plugins ?: return
    manager.execute {
        val result = runCatching {
            val input = application.contentResolver.openInputStream(uri) ?: error(localizedText(ModuleText.FILE_UNREADABLE))
            manager.store.prepare(input, Build.VERSION.SDK_INT)
        }
        mainHandler.post {
            val activity = currentActivity()
            if (activity == null || activity.isDestroyed) { manager.execute { result.getOrNull()?.close() }; return@post }
            result.onFailure { Toast.makeText(activity, localizedText(ModuleText.IMPORT_FAILED, it.message), Toast.LENGTH_LONG).show() }
            val prepared = result.getOrNull() ?: return@post
            // Read the installed version off the UI thread before showing replacement details.
            manager.execute {
                val oldResult = runCatching { manager.store.installed().firstOrNull { it.manifest.id == prepared.manifest.id } }
                mainHandler.post confirm@{
                    val current = currentActivity()
                    if (current == null || current.isDestroyed || oldResult.isFailure) {
                        if (current != null && oldResult.isFailure) Toast.makeText(current, localizedText(ModuleText.INSTALLED_PLUGIN_READ_FAILED, oldResult.exceptionOrNull()?.message), Toast.LENGTH_LONG).show()
                        manager.execute { prepared.close() }; return@confirm
                    }
                    val old = oldResult.getOrNull()
                    val committed = AtomicBoolean()
                    val dialog = embeddedDialogBuilder(current).setTitle(if (old == null) localizedText(ModuleText.IMPORT_PLUGIN) else localizedText(ModuleText.REPLACE_PLUGIN))
                        .setMessage(localizedText(ModuleText.PLUGIN_AUTHOR_DETAILS, prepared.manifest.name, prepared.manifest.author) +
                            (old?.let { "${it.manifest.versionName} → " } ?: "") + prepared.manifest.versionName +
                            "\n${prepared.manifest.description}\n" + if (old == null) localizedText(ModuleText.PLUGIN_IMPORTED_DISABLED) else localizedText(ModuleText.PLUGIN_REPLACEMENT_NOTICE))
                        .setPositiveButton(if (old == null) localizedText(ModuleText.IMPORT) else localizedText(ModuleText.REPLACE)) { _, _ ->
                            committed.set(true)
                            manager.execute {
                                val saved = runCatching { manager.store.commit(prepared) }
                                prepared.close()
                                mainHandler.post { currentActivity()?.let { Toast.makeText(it, saved.exceptionOrNull()?.message ?: localizedText(ModuleText.PLUGIN_IMPORTED), Toast.LENGTH_LONG).show() } }
                            }
                        }.setNegativeButton(localizedText(ModuleText.CANCEL), null).create()
                    dialog.setOnDismissListener { if (!committed.get()) manager.execute { prepared.close() } }
                    pluginDialogs += WeakReference(dialog); dialog.show()
                }
            }
        }
    }
}

internal fun EmbeddedSettingsHost.openPluginSettings(activity: Activity, id: String) {
    val session = plugins?.openSettings(id, embeddedThemedContext(activity)) ?: return Toast.makeText(activity, localizedText(ModuleText.PLUGIN_NO_SETTINGS), Toast.LENGTH_SHORT).show()
    try {
        excludeEmbeddedTheme(session.view)
        val dialog = embeddedDialogBuilder(activity).setTitle(localizedText(ModuleText.PLUGIN_SETTINGS)).setView(session.view).setPositiveButton(localizedText(ModuleText.CLOSE), null).create()
        dialog.setOnDismissListener { session.close() }
        pluginDialogs += WeakReference(dialog); dialog.show()
    } catch (error: Throwable) {
        session.close(); Toast.makeText(activity, localizedText(ModuleText.SETTINGS_OPEN_FAILED, error.message), Toast.LENGTH_LONG).show()
    }
}
