package dev.amenhancer.module.hook

import android.util.Log
import io.github.libxposed.api.XposedModule
import java.lang.reflect.Executable

abstract class ModernMethodHook {
    open fun beforeHookedMethod(param: MethodHookParam) = Unit
    open fun afterHookedMethod(param: MethodHookParam) = Unit

    class MethodHookParam internal constructor(
        val method: Executable,
        val thisObject: Any?,
        val args: Array<Any?>,
    ) {
        val extras = mutableMapOf<String, Any?>()
        private var returnEarly = false
        private var resultValue: Any? = null

        var result: Any?
            get() = resultValue
            set(value) {
                resultValue = value
                throwable = null
                returnEarly = true
            }

        var throwable: Throwable? = null
            set(value) {
                field = value
                if (value != null) returnEarly = true
            }

        internal fun shouldReturnEarly(): Boolean = returnEarly
        internal fun setInvocationResult(value: Any?) {
            resultValue = value
        }
    }
}

object ModernXposedRuntime {
    private const val TAG = "AppleMusicEnhancer"

    @Volatile
    private var module: XposedModule? = null

    fun attach(module: XposedModule) {
        CachedMethods.clear()
        this.module = module
    }

    fun activeModule(): XposedModule? = module

    fun hookMethod(executable: Executable, callback: ModernMethodHook, scope: HookRegistrationScope? = null, recordBuiltin: Boolean = true): Boolean {
        val activeModule = module ?: error("Modern Xposed runtime is not attached")
        // ART may already have compiled the lyrics word getter/render adapter by
        // the time the feature session is installed. HLE deoptimizes every
        // executable before intercepting it; without the same step, a hook can
        // report as installed while optimized calls bypass its callback.
        runCatching { activeModule.deoptimize(executable) }
        activeModule.hook(executable).intercept { chain ->
            if (scope != null && !scope.isActive) return@intercept chain.proceed()
            val param = ModernMethodHook.MethodHookParam(
                method = executable,
                thisObject = chain.thisObject,
                args = chain.args.toTypedArray(),
            )
            callback.beforeHookedMethod(param)
            if (!param.shouldReturnEarly()) {
                try {
                    param.setInvocationResult(chain.proceed(param.args))
                } catch (error: Throwable) {
                    param.throwable = error
                }
            }
            callback.afterHookedMethod(param)
            param.throwable?.let { throw it }
            param.result
        }
        if (recordBuiltin) {
            val record = HookRegistrations.register("AM++: ${callback.javaClass.name}", true, target = executable,
                retained = { scope == null || !scope.isClosed })
            scope?.onClose { record.close() }
        }
        return true
    }

    fun hookAllMethods(type: Class<*>, name: String, callback: ModernMethodHook, scope: HookRegistrationScope? = null): Set<Executable> =
        type.declaredMethods
            .filter { it.name == name }
            .onEach { hookMethod(it, callback, scope) }
            .toSet()

    /** Compatibility entry point; new native adapters resolve explicit member contracts. */
    fun callMethod(receiver: Any, name: String, vararg args: Any?): Any? {
        return CachedMethods.callLegacy(receiver, name, *args)
    }

    /**
     * Every diagnostic line, mirrored to logcat.
     *
     * The module's own channel only reaches LSPosed's log viewer
     * (`activeModule.log` -> libxposed), so `adb logcat -s AppleMusicEnhancer`
     * used to show nothing of what the hooks report, and device-time diagnosis of
     * a lyrics regression meant exporting LSPosed's log instead. AGENTS.md 4.6
     * requires the mirror, so it runs unconditionally while [MIRROR_TO_LOGCAT] is
     * set; the module channel stays the primary one.
     */
    fun log(message: String, error: Throwable? = null) {
        val activeModule = module
        if (activeModule != null) {
            if (error == null) {
                activeModule.log(Log.INFO, TAG, message)
            } else {
                activeModule.log(Log.ERROR, TAG, message, error)
            }
        }
        mirrorToLogcat(message, error)
    }

    /**
     * Logcat side of [log]: same tag, same text, throttled per message key.
     *
     * A hot hook path (a per-line getter, the word render adapter) repeats the
     * same line hundreds of times per song and none of those repetitions carries
     * new information within the window, so the first one is printed and the rest
     * are counted. The count is printed with the next line of that key, which keeps
     * a burst visible as a number instead of letting it disappear.
     */
    private fun mirrorToLogcat(message: String, error: Throwable?) {
        if (!MIRROR_TO_LOGCAT) return
        val line = throttledLine(message)
        if (error == null) Log.i(TAG, line) else Log.e(TAG, line, error)
    }

    private fun throttledLine(message: String): String {
        val key = message.take(MIRROR_KEY_LENGTH)
        val now = System.currentTimeMillis()
        var suppressed = 0L
        synchronized(mirrorWindows) {
            val last = mirrorWindows[key]
            if (last != null && now - last.at < MIRROR_WINDOW_MS) {
                last.suppressed += 1L
                return "$message (mirror suppressed ${last.suppressed})"
            }
            suppressed = last?.suppressed ?: 0L
            if (last == null && mirrorWindows.size >= MIRROR_MAX_KEYS) {
                mirrorWindows.minByOrNull { it.value.at }?.let { mirrorWindows.remove(it.key) }
            }
            mirrorWindows[key] = MirrorWindow(now)
        }
        return if (suppressed > 0L) "$message (mirror suppressed $suppressed)" else message
    }

    private class MirrorWindow(val at: Long) {
        var suppressed: Long = 0L
    }

    private val mirrorWindows = java.util.concurrent.ConcurrentHashMap<String, MirrorWindow>()

    /** Off only when logcat noise outweighs diagnosability; on is the requirement. */
    private const val MIRROR_TO_LOGCAT = true

    /** A hot path repeats its line with its own arguments; the prefix is the key. */
    private const val MIRROR_KEY_LENGTH = 96

    /** At most one mirrored line per key per window. */
    private const val MIRROR_WINDOW_MS = 1_000L

    /** Bounded, so an unbounded set of distinct lines cannot grow the map. */
    private const val MIRROR_MAX_KEYS = 256

}
