package dev.amenhancer.module.hook

import java.lang.reflect.Executable
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicLong

enum class HookAccess { OBSERVE, MODIFY, UNKNOWN }
data class HookRegistrationRecord(
    val token: Long,
    val owner: String,
    val builtin: Boolean,
    val target: Executable?,
    val resource: String?,
    val access: HookAccess,
    val exclusive: Boolean,
    val retained: () -> Boolean,
)

/** Generic metadata only. Never changes framework interception or registration order. */
object HookRegistrations {
    private val sequence = AtomicLong()
    private val records = CopyOnWriteArrayList<HookRegistrationRecord>()
    private val listeners = CopyOnWriteArrayList<() -> Unit>()
    fun snapshot(): List<HookRegistrationRecord> = records.filter { it.retained() }
    fun listen(listener: () -> Unit): AutoCloseable {
        listeners += listener
        return AutoCloseable { listeners -= listener }
    }
    fun register(owner: String, builtin: Boolean, target: Executable? = null, resource: String? = null,
        access: HookAccess = HookAccess.UNKNOWN, exclusive: Boolean = false, retained: () -> Boolean = { true }): AutoCloseable {
        val record = HookRegistrationRecord(sequence.incrementAndGet(), owner, builtin, target, resource, access, exclusive, retained)
        records += record
        changed()
        return AutoCloseable { records.remove(record); changed() }
    }
    fun changed() { listeners.forEach { runCatching(it) } }
}
