package dev.amenhancer.module.hook

import android.app.Activity
import android.os.Bundle
import android.os.SystemClock
import android.view.Menu
import android.view.View
import android.view.ViewGroup
import java.io.Serializable

/** A bounded startup task; no retained global page cache, native tab changes or back-stack writes. */
internal class StartupPagePrewarmSession(
    private val owner: Any,
    val activity: Activity,
    private val root: ViewGroup,
    private val native: StartupPagePrewarmContract,
    private val enabled: () -> Boolean,
    private val onPrepared: (Any) -> Unit,
    private val onFinished: () -> Unit,
    private val onFailure: (Throwable) -> Unit,
) : AutoCloseable, View.OnAttachStateChangeListener {
    private data class Tab(val kind: Enum<*>, val title: String)
    private val hostId = resource(native.names.getString("hostResource"))
    private val navigationId = resource(native.names.getString("navigationResource"))
    private val manager = checkNotNull(native.childManager.invoke(owner))
    private val deadline = SystemClock.uptimeMillis() + 10_000L
    private var closed = false
    private var originalPrimary: Any? = null
    private var tabNames: List<String>? = null
    private var waitingForCommit = false
    private var measureNext: Any? = null
    private val step = Runnable {
        if (!closed) runCatching(::advance).onFailure { close(); onFailure(it) }
    }

    fun start() {
        check(hostId != 0 && navigationId != 0) { "Native tab host resources are missing" }
        root.addOnAttachStateChangeListener(this)
        root.post(step)
    }

    private fun resource(name: String) = root.resources.getIdentifier(name, "id", activity.packageName)
    private fun schedule(delay: Long = 0L) { if (!closed) root.postDelayed(step, delay) }

    private fun advance() {
        if (closed || waitingForCommit) return
        if (!enabled() || SystemClock.uptimeMillis() >= deadline || activity.isFinishing || activity.isDestroyed ||
            native.view.invoke(owner) !== root || native.stateSaved.invoke(manager) == true
        ) { close(); return }
        if (!root.isAttachedToWindow || !root.isShown || native.resumed.invoke(owner) != true) {
            if (tabNames != null) close() else schedule(50L)
            return
        }
        val host = root.findViewById<ViewGroup>(hostId)
        val navigation = root.findViewById<View>(navigationId)
        val primary = native.primary.get(manager)
        if (host == null || host.width <= 0 || host.height <= 0 || navigation == null || primary == null) {
            schedule(50L); return
        }
        val model = native.viewModel.invoke(owner)
        val selected = native.selectedTab.invoke(model) as? Enum<*>
        val menu = native.menu.invoke(navigation) as Menu
        val tabs = (native.value.invoke(native.tabs.invoke(model)) as? List<*>)?.mapNotNull { item ->
            val kind = item?.let { native.modelKind.get(it) } as? Enum<*> ?: return@mapNotNull null
            val menuItem = menu.findItem(native.kindId.invoke(kind) as Int)
            if (menuItem?.isEnabled != true || !menuItem.isVisible) null
            else Tab(kind, native.kindLabel.invoke(kind) as String)
        }?.distinctBy { it.kind.name }
        if (tabs.isNullOrEmpty() || tabs.none { it.kind == selected } ||
            native.tag.invoke(primary) != selected?.name ||
            native.selectedId.invoke(navigation) != native.kindId.invoke(selected)
        ) { schedule(50L); return }
        val names = tabs.map { it.kind.name }
        if (tabNames == null) { tabNames = names; originalPrimary = primary }
        if (tabNames != names || originalPrimary !== primary) { close(); return }

        measureNext?.let { fragment ->
            measureNext = null
            if (native.hidden.invoke(fragment) == true) {
                (native.view.invoke(fragment) as? View)?.let { view ->
                    view.measure(View.MeasureSpec.makeMeasureSpec(host.width, View.MeasureSpec.EXACTLY),
                        View.MeasureSpec.makeMeasureSpec(host.height, View.MeasureSpec.EXACTLY))
                    view.layout(0, 0, host.width, host.height)
                }
            }
        }
        val next = tabs.sortedBy { if (it.kind.name == native.names.getString("priorityTab")) 0 else 1 }
            .firstOrNull { native.find.invoke(manager, it.kind.name) == null }
        if (next == null) { close(); return }
        val fragment = native.container.newInstance()
        native.arguments.invoke(fragment, Bundle().apply {
            putSerializable(native.names.getString("tabArgumentKey"), next.kind as Serializable)
            putString(native.names.getString("titleArgumentKey"), next.title)
        })
        val transaction = native.transaction.newInstance(manager)
        native.reordering.setBoolean(transaction, true)
        native.add.invoke(transaction, hostId, fragment, next.kind.name, 1)
        native.hide.invoke(transaction, fragment)
        onPrepared(fragment)
        native.onCommit.invoke(transaction, Runnable {
            waitingForCommit = false
            if (!closed) {
                measureNext = fragment
                ModernXposedRuntime.log("Startup page prewarmed: ${next.kind.name}")
                schedule()
            }
        })
        waitingForCommit = true
        native.commit.invoke(transaction, false)
    }

    override fun onViewAttachedToWindow(view: View) = Unit
    override fun onViewDetachedFromWindow(view: View) = close()
    override fun close() {
        if (closed) return
        closed = true
        root.removeCallbacks(step)
        root.removeOnAttachStateChangeListener(this)
        measureNext = null
        onFinished()
    }
}
