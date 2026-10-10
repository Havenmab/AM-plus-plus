package dev.amenhancer.plugin.runtime

import dev.amenhancer.plugin.api.PluginCall
import java.lang.reflect.Executable

/** Invocation-local copy: failed callbacks cannot commit their parameter/outcome changes. */
internal object PluginCallbacks {
    fun invoke(method: Executable, receiver: Any?, args: Array<Any?>, result: Any?, error: Throwable?,
        callback: (PluginCall) -> Unit, commit: (PluginCall) -> Unit, failed: (Throwable) -> Unit) {
        val call = PluginCall(method, receiver, args, result, error)
        try {
            callback(call)
            validate(method, call)
        } catch (failure: Throwable) { failed(failure); return }
        commit(call)
    }
    private fun validate(method: Executable, call: PluginCall) {
        require(call.arguments.size == method.parameterCount)
        method.parameterTypes.forEachIndexed { i, type -> require(compatible(type, call.arguments[i])) { "Invalid argument $i" } }
        if (call.isOutcomeChanged && call.throwable == null && method is java.lang.reflect.Method) {
            require(method.returnType == Void.TYPE || compatible(method.returnType, call.result)) { "Invalid result" }
        }
    }
    private fun compatible(type: Class<*>, value: Any?): Boolean {
        if (value == null) return !type.isPrimitive
        val boxed = when (type) {
            Boolean::class.javaPrimitiveType -> java.lang.Boolean::class.java
            Byte::class.javaPrimitiveType -> java.lang.Byte::class.java
            Short::class.javaPrimitiveType -> java.lang.Short::class.java
            Int::class.javaPrimitiveType -> java.lang.Integer::class.java
            Long::class.javaPrimitiveType -> java.lang.Long::class.java
            Float::class.javaPrimitiveType -> java.lang.Float::class.java
            Double::class.javaPrimitiveType -> java.lang.Double::class.java
            Char::class.javaPrimitiveType -> java.lang.Character::class.java
            else -> type
        }
        return boxed.isInstance(value)
    }
}
