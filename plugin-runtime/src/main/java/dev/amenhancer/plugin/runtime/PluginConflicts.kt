package dev.amenhancer.plugin.runtime

import dev.amenhancer.module.i18n.ModuleText

import dev.amenhancer.module.hook.HookAccess
import dev.amenhancer.module.hook.HookRegistrationRecord

data class PluginConflict(val owners: Set<String>, val target: String, val blocking: Boolean, val reason: String)

object PluginConflictAnalysis {
    fun analyze(records: List<HookRegistrationRecord>): List<PluginConflict> {
        val result = mutableListOf<PluginConflict>()
        val retained = records.filter { it.retained() }
        for (i in retained.indices) for (j in i + 1 until retained.size) {
            val a = retained[i]; val b = retained[j]
            if (a.owner == b.owner || (a.builtin && b.builtin)) continue
            val resource = a.resource != null && a.resource == b.resource
            val method = a.target != null && a.target == b.target
            if (!resource && !method) continue
            if (method && (a.access == HookAccess.OBSERVE || b.access == HookAccess.OBSERVE)) continue
            val blocking = a.exclusive || b.exclusive
            if (resource && !blocking) continue
            result += PluginConflict(setOf(a.owner, b.owner), a.resource ?: a.target.toString(), blocking,
                if (blocking && (a.builtin || b.builtin)) ModuleText.BUILTIN_EXCLUSIVE_CONFLICT.text() else if (blocking) ModuleText.EXCLUSIVE_CONFLICT.text() else ModuleText.SHARED_TARGET_WARNING.text())
        }
        return result.distinct()
    }
}
