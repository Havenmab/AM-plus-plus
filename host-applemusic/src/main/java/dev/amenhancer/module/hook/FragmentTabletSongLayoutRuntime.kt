package dev.amenhancer.module.hook

import android.view.View
import android.view.ViewGroup
import java.util.WeakHashMap

/** Owns hook registration and tracks only live song-control bindings. */
internal class FragmentTabletSongLayoutRuntime(val contract: FragmentTabletSongLayoutContract) : AutoCloseable {
    private val activeControls = WeakHashMap<View, Float>()
    private val sessions = LinkedHashSet<FragmentTabletSongLayoutSession>()
    private var closed = false

    fun install(scope: HookRegistrationScope) {
        scope.onClose(::close)
        check(ModernXposedRuntime.hookMethod(contract.setSeekbarPadding, object : ModernMethodHook() {
            override fun beforeHookedMethod(param: MethodHookParam) {
                val root = contract.bindingRoot.get(param.thisObject) as? View ?: return
                if (!FragmentTabletDualPaneCoordinator.eligible(root.context)) return
                activeControls[root]?.let { param.args[0] = it }
            }
        }, scope)) { "Song-column seekbar binding hook failed: ${contract.setSeekbarPadding}" }
    }

    fun bind(container: ViewGroup): FragmentTabletSongLayoutSession {
        check(!closed)
        val session = FragmentTabletSongLayoutSession(contract, container, ::registerControls, ::unregisterControls) {
            sessions.remove(it)
        }
        try {
            session.apply()
            sessions += session
            return session
        } catch (error: Throwable) {
            session.close()
            throw error
        }
    }

    private fun registerControls(view: View, padding: Float) { activeControls[view] = padding }
    private fun unregisterControls(view: View) { activeControls.remove(view) }

    override fun close() {
        if (closed) return
        closed = true
        sessions.toList().forEach { it.close() }
        activeControls.clear()
    }
}
