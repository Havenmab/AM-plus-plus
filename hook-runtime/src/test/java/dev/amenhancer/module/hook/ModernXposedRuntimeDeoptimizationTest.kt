package dev.amenhancer.module.hook

import java.io.File
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * HLE deoptimizes every executable before installing an Xposed interceptor.
 * Keep the fork's common runtime primitive aligned so optimized host methods
 * cannot silently bypass pronunciation getter/render hooks.
 */
class ModernXposedRuntimeDeoptimizationTest {
    @Test
    fun `hookMethod deoptimizes before intercepting`() {
        val source = projectFile(
            "src/main/java/dev/amenhancer/module/hook/ModernXposedRuntime.kt",
        )
        val hookMethod = source.substringAfter(
            "fun hookMethod(executable: Executable, callback: ModernMethodHook, scope: HookRegistrationScope? = null): Boolean {",
        ).substringBefore("    fun hookAllMethods")

        assertTrue(hookMethod.contains("activeModule.deoptimize(executable)"))
        assertTrue(
            hookMethod.indexOf("activeModule.deoptimize(executable)") <
                hookMethod.indexOf("activeModule.hook(executable).intercept"),
        )
    }

    private fun projectFile(relativePath: String): String = sequenceOf(
        File(relativePath),
        File("../../$relativePath"),
        File("../../../$relativePath"),
    ).firstOrNull(File::isFile)?.readText()
        ?: error("$relativePath was not found from the unit-test working directory")
}
