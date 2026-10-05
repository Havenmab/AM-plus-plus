package dev.amenhancer.module.ui

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
    val manager = plugins ?: return Toast.makeText(activity, "插件运行时不可用", Toast.LENGTH_LONG).show()
    val content = LinearLayout(activity).apply { orientation = LinearLayout.VERTICAL; setPadding(dp(activity, 12), 0, dp(activity, 12), 0) }
    val scroll = ScrollView(activity).apply { addView(content) }
    val dialog = AlertDialog.Builder(activity).setTitle("插件").setView(scroll)
        .setPositiveButton("导入 ZIP", null).setNegativeButton("关闭", null).create()
    pluginDialogs += WeakReference(dialog)
    val refreshing = AtomicBoolean()
    fun action(block: () -> Unit) {
        manager.execute {
            val result = runCatching(block)
            mainHandler.post {
                currentActivity()?.let { Toast.makeText(it, result.exceptionOrNull()?.message ?: "已保存，重启 Apple Music 后生效", Toast.LENGTH_LONG).show() }
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
                    content.addView(embeddedInfoCard(activity, "启用、停用、更新和删除均在重启 Apple Music 后生效。"))
                    manager.startupError?.let { content.addView(embeddedInfoCard(activity, "加载错误：$it")) }
                    statuses.onFailure { content.addView(embeddedInfoCard(activity, "无法读取插件：${it.message}")) }
                    if (statuses.getOrNull()?.isEmpty() == true) content.addView(embeddedInfoCard(activity, "尚未导入插件"))
                    statuses.getOrDefault(emptyList()).forEach { status ->
                        val manifest = status.installed.manifest
                        val state = when (status.state) {
                            PluginRunState.ACTIVE -> "运行中"
                            PluginRunState.DISABLED -> "未运行"
                            PluginRunState.LOADING -> "加载中"
                            PluginRunState.BLOCKED -> "冲突阻止"
                            PluginRunState.UNSUPPORTED -> "不支持"
                            PluginRunState.FAILED -> "失败"
                        }
                        content.addView(embeddedSpacer(activity, 12))
                        content.addView(embeddedCard(activity, manifest.name) {
                            addView(embeddedInfoCard(activity, "${manifest.author} · ${manifest.versionName}\n${manifest.id}\n本次：$state${status.runningVersion?.let { "（版本号 $it）" }.orEmpty()}\n${status.message}"))
                            addView(embeddedSettingRow(activity, "下次启动启用", if (status.pendingRestart) "待重启" else "", status.installed.enabled) { enabled ->
                                action { manager.store.setEnabled(manifest.id, enabled) }
                            })
                            if (status.conflicts.isNotEmpty()) addView(embeddedNavigationRow(activity, "冲突详情", "${status.conflicts.size} 项") {
                                val details = status.conflicts.joinToString("\n\n") { "${if (it.blocking) "阻止" else "提示"}：${it.reason}\n${it.target}\n${it.owners.joinToString(" / ")}" }
                                val info = AlertDialog.Builder(activity).setTitle("冲突详情").setMessage(details).setPositiveButton("关闭", null).create()
                                pluginDialogs += WeakReference(info); info.show()
                            })
                            if (status.state == PluginRunState.ACTIVE) addView(embeddedNavigationRow(activity, "插件设置", "") { openPluginSettings(activity, manifest.id) })
                            addView(Button(activity).apply {
                                text = "删除"
                                setOnClickListener {
                                    val confirm = AlertDialog.Builder(activity).setTitle("删除 ${manifest.name}？")
                                        .setMessage("重启后删除插件及其配置和缓存。")
                                        .setPositiveButton("删除") { _, _ -> action { manager.store.delete(manifest.id) } }.setNegativeButton("取消", null).create()
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
            val input = application.contentResolver.openInputStream(uri) ?: error("无法读取文件")
            manager.store.prepare(input, Build.VERSION.SDK_INT)
        }
        mainHandler.post {
            val activity = currentActivity()
            if (activity == null || activity.isDestroyed) { manager.execute { result.getOrNull()?.close() }; return@post }
            result.onFailure { Toast.makeText(activity, "导入失败：${it.message}", Toast.LENGTH_LONG).show() }
            val prepared = result.getOrNull() ?: return@post
            // Read the installed version off the UI thread before showing replacement details.
            manager.execute {
                val oldResult = runCatching { manager.store.installed().firstOrNull { it.manifest.id == prepared.manifest.id } }
                mainHandler.post confirm@{
                    val current = currentActivity()
                    if (current == null || current.isDestroyed || oldResult.isFailure) {
                        if (current != null && oldResult.isFailure) Toast.makeText(current, "无法读取已安装版本：${oldResult.exceptionOrNull()?.message}", Toast.LENGTH_LONG).show()
                        manager.execute { prepared.close() }; return@confirm
                    }
                    val old = oldResult.getOrNull()
                    val committed = AtomicBoolean()
                    val dialog = AlertDialog.Builder(current).setTitle(if (old == null) "导入插件" else "替换插件")
                        .setMessage("${prepared.manifest.name}\n作者：${prepared.manifest.author}\n" +
                            (old?.let { "${it.manifest.versionName} → " } ?: "") + prepared.manifest.versionName +
                            "\n${prepared.manifest.description}\n" + if (old == null) "导入后默认关闭。" else "保留配置及启用状态，重启后生效。")
                        .setPositiveButton(if (old == null) "导入" else "替换") { _, _ ->
                            committed.set(true)
                            manager.execute {
                                val saved = runCatching { manager.store.commit(prepared) }
                                prepared.close()
                                mainHandler.post { currentActivity()?.let { Toast.makeText(it, saved.exceptionOrNull()?.message ?: "已导入，请在插件列表启用并重启", Toast.LENGTH_LONG).show() } }
                            }
                        }.setNegativeButton("取消", null).create()
                    dialog.setOnDismissListener { if (!committed.get()) manager.execute { prepared.close() } }
                    pluginDialogs += WeakReference(dialog); dialog.show()
                }
            }
        }
    }
}

internal fun EmbeddedSettingsHost.openPluginSettings(activity: Activity, id: String) {
    val session = plugins?.openSettings(id, activity) ?: return Toast.makeText(activity, "插件未运行或没有设置页", Toast.LENGTH_SHORT).show()
    try {
        val dialog = AlertDialog.Builder(activity).setTitle("插件设置").setView(session.view).setPositiveButton("关闭", null).create()
        dialog.setOnDismissListener { session.close() }
        pluginDialogs += WeakReference(dialog); dialog.show()
    } catch (error: Throwable) {
        session.close(); Toast.makeText(activity, "无法打开设置：${error.message}", Toast.LENGTH_LONG).show()
    }
}
