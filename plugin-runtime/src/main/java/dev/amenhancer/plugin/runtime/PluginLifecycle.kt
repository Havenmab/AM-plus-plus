package dev.amenhancer.plugin.runtime

import dev.amenhancer.module.hook.HookRegistrationScope
import dev.amenhancer.plugin.api.PluginUnsupportedException
import java.util.concurrent.atomic.AtomicBoolean

/** Tested lifecycle used by the Android manager. Dispatch of stop belongs to its caller. */
internal open class PluginLifecycle(private val dispatchStop: (() -> Unit) -> Unit) {
    val scope = HookRegistrationScope()
    @Volatile var state = PluginRunState.LOADING
        private set
    @Volatile var message = "正在加载"
        private set
    private val stopped = AtomicBoolean()
    var stopAction: () -> Unit = {}
    fun start(action: () -> Unit) {
        if (scope.isClosed) return
        scope.activate()
        action()
        if (!scope.isClosed) { state = PluginRunState.ACTIVE; message = "正在运行" }
    }
    fun fail(failure: Throwable) = close(
        if (failure is PluginUnsupportedException) PluginRunState.UNSUPPORTED else PluginRunState.FAILED,
        failure.message ?: failure.javaClass.simpleName)
    fun close(next: PluginRunState, reason: String) {
        if (scope.isClosed) return
        state = next; message = reason
        scope.close()
        if (stopped.compareAndSet(false, true)) dispatchStop(stopAction)
    }
}
