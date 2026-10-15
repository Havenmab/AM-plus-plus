package dev.amenhancer.module.hook

import java.lang.reflect.Method
import java.lang.reflect.Modifier
import java.util.concurrent.ConcurrentHashMap

/** Process member handles; cache keys never retain argument values or view owners. */
object CachedMethods {
    private data class Key(val type: Class<*>, val name: String, val parameters: List<Class<*>?>)
    private data class Lookup(val method: Method?)
    private val exact = ConcurrentHashMap<Key, Lookup>()
    private val legacy = ConcurrentHashMap<Key, Lookup>()

    fun clear() { exact.clear(); legacy.clear() }

    /** Stable instance methods are resolved once; ambiguous declarations are rejected. */
    fun exact(type: Class<*>, name: String, vararg parameters: Class<*>): Method {
        val key = Key(type, name, parameters.toList())
        return exact.computeIfAbsent(key) {
            val found = generateSequence(type as Class<*>?) { it.superclass }.map { owner ->
                val matches = owner.declaredMethods.filter { method ->
                    method.name == name && method.parameterTypes.contentEquals(parameters) &&
                        !Modifier.isStatic(method.modifiers) && !method.isBridge && !method.isSynthetic
                }
                check(matches.size <= 1) { "Ambiguous instance method: ${owner.name}#$name" }
                matches.singleOrNull()
            }.firstOrNull { it != null }
            Lookup(found?.apply { isAccessible = true })
        }.method ?: throw NoSuchMethodException("${type.name}#$name(${parameters.joinToString { it.name }})")
    }

    /** Only historical adapters use first-compatible matching; their candidate order is retained. */
    fun legacyCompatible(type: Class<*>, name: String, vararg args: Any?): Method {
        val key = Key(type, name, args.map { it?.javaClass })
        return legacy.computeIfAbsent(key) {
            val found = generateSequence(type as Class<*>?) { it.superclass }.map { owner ->
                owner.declaredMethods.firstOrNull { method ->
                    method.name == name && method.parameterTypes.size == args.size &&
                        method.parameterTypes.indices.all { compatible(method.parameterTypes[it], args[it]) }
                }
            }.firstOrNull { it != null }
            Lookup(found?.apply { isAccessible = true })
        }.method ?: throw NoSuchMethodException("${type.name}#$name(${args.size})")
    }

    fun callLegacy(receiver: Any, name: String, vararg args: Any?): Any? =
        legacyCompatible(receiver.javaClass, name, *args).invoke(receiver, *args)

    private fun compatible(parameter: Class<*>, value: Any?): Boolean {
        if (value == null) return !parameter.isPrimitive
        val boxed = when (parameter) {
            java.lang.Boolean.TYPE -> Boolean::class.javaObjectType
            java.lang.Byte.TYPE -> Byte::class.javaObjectType
            java.lang.Character.TYPE -> Char::class.javaObjectType
            java.lang.Short.TYPE -> Short::class.javaObjectType
            java.lang.Integer.TYPE -> Int::class.javaObjectType
            java.lang.Long.TYPE -> Long::class.javaObjectType
            java.lang.Float.TYPE -> Float::class.javaObjectType
            java.lang.Double.TYPE -> Double::class.javaObjectType
            else -> parameter
        }
        return boxed.isInstance(value)
    }
}
