/*
 * Copyright 2026 juren233
 * Licensed under the Apache License, Version 2.0
 * http://www.apache.org/licenses/LICENSE-2.0
 */

package io.github.proify.lyricon.amprovider.xposed.hooks

import com.juren233.hyperlyricsenhanced.BuildConfig
import io.github.libxposed.api.XposedInterface.Chain
import io.github.libxposed.api.XposedInterface.Hooker
import io.github.libxposed.api.XposedModule
import io.github.proify.lyricon.amprovider.xposed.ProviderLogger
import io.github.proify.lyricon.amprovider.xposed.AppleMusicDexKitWatchdog
import io.github.proify.lyricon.amprovider.xposed.internal.ArgumentRewriteHook
import io.github.proify.lyricon.amprovider.xposed.internal.CallbackHook
import io.github.proify.lyricon.amprovider.xposed.internal.ConditionalVoidSkipHook
import io.github.proify.lyricon.amprovider.xposed.internal.ResultOverrideHook
import io.github.proify.lyricon.amprovider.xposed.internal.ScopedCallbackHook
import java.lang.reflect.Executable
import java.lang.reflect.Method
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicInteger
import dev.amenhancer.module.hook.HookRegistrationScope

internal class AppleHookRegistrar(
    private val module: XposedModule,
) {
    private val activeModuleId = ThreadLocal<String?>()
    private val installation = ThreadLocal<AppleHookInstallGroup?>()
    private val callbackTracer = AppleHookCallbackTracer(
        withModule = { moduleId, block -> withModule(moduleId, block) },
        onFirstCallback = { moduleId, executable ->
            ProviderLogger.diagnostic(
                "Apple Music Hook 首次回调: module=$moduleId, target=$executable"
            )
        },
    )

    fun <T> withModule(moduleId: String, block: () -> T): T {
        val previous = activeModuleId.get()
        activeModuleId.set(moduleId)
        return try {
            block()
        } finally {
            if (previous == null) activeModuleId.remove() else activeModuleId.set(previous)
        }
    }

    /** Specialized Hookers keep their semantics; only activation is staged here. */
    fun stageInstallation(moduleId: String, parent: AppleHookInstallGroup? = installation.get(), block: () -> Unit): AppleHookInstallGroup {
        val group = AppleHookInstallGroup(moduleId, parent)
        parent?.children?.add(group)
        parent?.scope?.onClose(group.scope::close)
        if (group.scope.isClosed) {
            group.failures += "Installation owner is closed"
            return group
        }
        val previous = installation.get()
        installation.set(group)
        try {
            withModule(moduleId) { block() }
        } catch (error: Throwable) {
            group.fail(error)
            ProviderLogger.error("Apple Music Hook group unavailable: $moduleId", error)
        } finally {
            if (previous == null) installation.remove() else installation.set(previous)
        }
        if (group.failures.isEmpty() && group.installedCount == 0) {
            group.failures += "No usable hook target was installed"
        }
        if (group.failures.isEmpty()) group.scope.activate() else group.scope.close()
        return group
    }

    /** An installer that handles its own exception still invalidates its staged group. */
    fun reportInstallationFailure(error: Throwable) { installation.get()?.fail(error) }

    fun onInstallationClose(action: () -> Unit) { installation.get()?.scope?.onClose(action) }

    fun currentInstallation(): AppleHookInstallGroup? = installation.get()

    fun installHook(
        executable: Executable,
        before: ((Chain) -> Unit)? = null,
        after: ((Chain, Any?) -> Unit)? = null,
    ) = installHooker(executable, CallbackHook(before, after))

    fun installScopedHook(
        executable: Executable,
        enter: (Chain) -> Boolean,
        after: (Chain, Any?) -> Unit,
        exit: () -> Unit,
    ) = installHooker(executable, ScopedCallbackHook(enter, after, exit))

    fun installConditionalVoidSkipHook(
        executable: Executable,
        shouldSkip: (Chain) -> Boolean,
    ) {
        require(executable is Method && executable.returnType == Void.TYPE) {
            "Conditional skip hooks require a void method: $executable"
        }
        installHooker(executable, ConditionalVoidSkipHook(shouldSkip))
    }

    fun installResultOverrideHook(
        executable: Executable,
        override: (Chain, Any?) -> Any?,
    ) = installHooker(executable, ResultOverrideHook(override))

    fun installArgumentRewriteHook(
        executable: Executable,
        rewrite: (Chain) -> Array<Any?>?,
    ) = installHooker(executable, ArgumentRewriteHook(rewrite))

    private fun installHooker(executable: Executable, hooker: Hooker) {
        runCatching { module.deoptimize(executable) }
        val moduleId = activeModuleId.get() ?: "unscoped"
        val group = installation.get()
        val delegate = if (BuildConfig.DEBUG) callbackTracer.wrap(moduleId, executable, hooker) else hooker
        module.hook(executable).intercept { chain ->
            if (group != null && !group.isActive) chain.proceed() else delegate.intercept(chain)
        }
        group?.recordHook()
        AppleMusicDexKitWatchdog.hookInstalled(executable)
        val record = dev.amenhancer.module.hook.HookRegistrations.register("AM++ HLE: $moduleId", true,
            target = executable, retained = { group == null || !group.scope.isClosed })
        group?.scope?.onClose(record::close)
    }
}

/** Child helpers can degrade independently; a failed parent revokes all of its children. */
internal class AppleHookInstallGroup(val name: String, private val parent: AppleHookInstallGroup?) {
    val scope = HookRegistrationScope()
    val failures = CopyOnWriteArrayList<String>()
    val children = CopyOnWriteArrayList<AppleHookInstallGroup>()
    private val directHookCount = AtomicInteger()
    fun recordHook() { directHookCount.incrementAndGet() }
    val installedCount: Int get() = directHookCount.get() + children.filter { !it.scope.isClosed }.sumOf { it.installedCount }
    val isActive: Boolean get() = scope.isActive && (parent?.isActive != false)
    fun fail(error: Throwable) { failures += "${error.javaClass.simpleName}: ${error.message.orEmpty()}" }
    fun failedGroups(): List<String> =
        (if (failures.isEmpty()) emptyList() else listOf("$name (${failures.distinct().joinToString("; ")})")) +
            children.flatMap(AppleHookInstallGroup::failedGroups)
}

internal class AppleHookCallbackTracer(
    private val withModule: (String, () -> Any?) -> Any?,
    private val onFirstCallback: (String, Executable) -> Unit,
) {
    private val firstCallbackModuleIds = ConcurrentHashMap.newKeySet<String>()

    fun wrap(
        moduleId: String,
        executable: Executable,
        delegate: Hooker,
    ): Hooker = object : Hooker {
        override fun intercept(chain: Chain): Any? = withModule(moduleId) {
            AppleMusicDexKitWatchdog.callback(executable)
            if (firstCallbackModuleIds.add(moduleId)) {
                onFirstCallback(moduleId, executable)
            }
            runCatching { delegate.intercept(chain) }
                .onSuccess {
                    AppleMusicDexKitWatchdog.validation(
                        executable = executable,
                        valid = true,
                        detail = "module=$moduleId callback_completed",
                    )
                }
                .onFailure { error ->
                    AppleMusicDexKitWatchdog.validation(
                        executable = executable,
                        valid = false,
                        detail = "module=$moduleId ${error::class.java.simpleName}: ${error.message}",
                    )
                }
                .getOrThrow()
        }
    }
}
