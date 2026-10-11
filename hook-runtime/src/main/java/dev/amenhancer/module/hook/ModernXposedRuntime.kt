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

    fun log(message: String, error: Throwable? = null) {
        val activeModule = module
        if (activeModule != null) {
            if (error == null) {
                activeModule.log(Log.INFO, TAG, message)
            } else {
                activeModule.log(Log.ERROR, TAG, message, error)
            }
        } else if (error == null) {
            Log.i(TAG, message)
        } else {
            Log.e(TAG, message, error)
        }
    }

}
