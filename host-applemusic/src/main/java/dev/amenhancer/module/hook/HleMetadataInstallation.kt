package dev.amenhancer.module.hook

import io.github.proify.lyricon.amprovider.xposed.hooks.AppleHookInstallGroup
import io.github.proify.lyricon.amprovider.xposed.hooks.AppleHookRegistrar

/** Owns independently staged metadata surfaces and their aggregate capability health. */
internal class HleMetadataInstallation(private val registrar: AppleHookRegistrar) : AutoCloseable {
    private val groups = ArrayList<AppleHookInstallGroup>()

    fun install(name: String, block: () -> Unit) {
        groups += registrar.stageInstallation("hle.$name", block = block)
    }

    fun result(message: String): TargetCapabilityInstall {
        val failed = groups.flatMap(AppleHookInstallGroup::failedGroups)
        val active = groups.count { it.isActive }
        return if (failed.isEmpty()) TargetCapabilityInstall.Active(message)
        else TargetCapabilityInstall.Degraded("$message; active groups=$active/${groups.size}; unavailable: ${failed.joinToString(", ")}")
    }

    override fun close() { groups.asReversed().forEach { it.scope.close() } }
}
