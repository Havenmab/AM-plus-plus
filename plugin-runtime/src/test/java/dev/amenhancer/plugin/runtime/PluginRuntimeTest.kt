package dev.amenhancer.plugin.runtime

import dev.amenhancer.module.hook.HookAccess
import dev.amenhancer.module.hook.HookRegistrations
import dev.amenhancer.plugin.api.*
import org.junit.Assert.*
import org.junit.Test

class EntryFixture : AmppPlugin() { override fun onLoad(context: PluginContext) {} }
class PluginRuntimeTest {
    class Target { fun action(value: Int) = value; fun action(value: String) = value }
    private val target = Target::class.java.getDeclaredMethod("action", Int::class.javaPrimitiveType)
    @Test fun callbacksCommitSuccessfulChangesAndRejectPartialFailedWrites() {
        val args = arrayOf<Any?>(1); var outcome: Any? = null; var failed = 0
        PluginCallbacks.invoke(target, Target(), args, 2, null, { call -> call.arguments[0] = 3; call.returnResult(4); error("failure") },
            { call -> call.arguments.copyInto(args); outcome = call.result }, { failed++ })
        assertEquals(1, args[0]); assertNull(outcome); assertEquals(1, failed)
        PluginCallbacks.invoke(target, Target(), args, 2, null, { call -> call.arguments[0] = 3; call.returnResult(4) },
            { call -> call.arguments.copyInto(args); outcome = call.result }, { fail("Unexpected failure") })
        assertEquals(3, args[0]); assertEquals(4, outcome)
    }
    @Test fun afterFailureRetainsHostExceptionAndInvalidResultCannotCommit() {
        val hostError = IllegalStateException("host")
        var commit = 0; var failed = 0
        PluginCallbacks.invoke(target, Target(), arrayOf(1), null, hostError, { call ->
            assertSame(hostError, call.throwable); call.returnResult(2); error("plugin")
        }, { commit++ }, { failed++ })
        PluginCallbacks.invoke(target, Target(), arrayOf(1), 3, null, { it.returnResult("wrong type") }, { commit++ }, { failed++ })
        assertEquals(0, commit); assertEquals(2, failed)
    }
    @Test fun observationArrayCannotRewriteInvocation() {
        val args = arrayOf<Any?>(1)
        val call = PluginObservation(target, null, args, 2, null)
        call.arguments[0] = 9; assertEquals(1, args[0]); assertEquals(1, call.arguments[0])
        assertFalse(PluginObservation::class.java.methods.any { it.name.startsWith("set") || it.name == "returnResult" })
    }
    @Test fun lifecycleActivatesOnceClosesInReverseAndStopsOnce() {
        val events = mutableListOf<String>(); val life = PluginLifecycle { it() }
        life.stopAction = { events += "stop" }
        life.scope.onClose { events += "first" }; life.scope.onClose { events += "second" }
        assertFalse(life.scope.isActive)
        life.start { assertTrue(life.scope.isActive); events += "start" }
        assertEquals(PluginRunState.ACTIVE, life.state)
        life.fail(IllegalStateException("broken")); life.fail(IllegalStateException("again"))
        assertFalse(life.scope.isActive); assertTrue(life.scope.isClosed)
        assertEquals(listOf("start", "second", "first", "stop"), events)
        assertEquals("broken", life.message)
    }
    @Test fun unsupportedAndBlockedNeverStartAndOtherPluginCanStart() {
        val unsupported = PluginLifecycle { it() }; unsupported.fail(PluginUnsupportedException("Missing target"))
        unsupported.start { fail("Unsupported started") }; assertEquals(PluginRunState.UNSUPPORTED, unsupported.state)
        val blocked = PluginLifecycle { it() }; blocked.close(PluginRunState.BLOCKED, "Conflict")
        blocked.start { fail("Blocked started") }
        val other = PluginLifecycle { it() }; other.start {}; assertEquals(PluginRunState.ACTIVE, other.state)
    }
    @Test fun childEntrySharesSdkAndParentEntryIsRejected() {
        val name = EntryFixture::class.java.name
        val bytes = EntryFixture::class.java.getResourceAsStream("/${name.replace('.', '/')}.class")!!.readBytes()
        val child = object : ClassLoader(AmppPlugin::class.java.classLoader) {
            override fun loadClass(className: String, resolve: Boolean): Class<*> = if (className == name) {
                synchronized(this) { findLoadedClass(className) ?: defineClass(className, bytes, 0, bytes.size) }
            } else super.loadClass(className, resolve)
        }
        val plugin = PluginEntries.instantiate(child, name)
        assertSame(child, plugin.javaClass.classLoader)
        val childMethod = plugin.javaClass.getDeclaredMethod("onLoad", PluginContext::class.java)
        val parentMethod = EntryFixture::class.java.getDeclaredMethod("onLoad", PluginContext::class.java)
        assertNotEquals(parentMethod, childMethod)
        val parentRecord = HookRegistrations.register("parent", false, parentMethod, access = HookAccess.MODIFY, exclusive = true)
        val childRecord = HookRegistrations.register("child", false, childMethod, access = HookAccess.MODIFY, exclusive = true)
        try { assertTrue(PluginConflictAnalysis.analyze(HookRegistrations.snapshot()).isEmpty()) }
        finally { parentRecord.close(); childRecord.close() }
        val delegating = object : ClassLoader(EntryFixture::class.java.classLoader) {}
        try { PluginEntries.instantiate(delegating, name); fail("Parent entry allowed") } catch (_: IllegalArgumentException) {}
        try { PluginEntries.instantiate(child, String::class.java.name); fail("Wrong entry allowed") } catch (_: IllegalArgumentException) {}
    }
    @Test fun observersCoexistAndWritersWarnWhileExclusiveBlocks() {
        val handles = mutableListOf<AutoCloseable>()
        try {
            handles += HookRegistrations.register("a", false, target, access = HookAccess.OBSERVE)
            handles += HookRegistrations.register("b", false, target, access = HookAccess.OBSERVE)
            assertTrue(PluginConflictAnalysis.analyze(HookRegistrations.snapshot()).isEmpty())
            handles += HookRegistrations.register("c", false, target, access = HookAccess.MODIFY)
            handles += HookRegistrations.register("d", false, target, access = HookAccess.MODIFY)
            val warning = PluginConflictAnalysis.analyze(HookRegistrations.snapshot()).single()
            assertFalse(warning.blocking); assertEquals(setOf("c", "d"), warning.owners)
            handles += HookRegistrations.register("e", false, target, access = HookAccess.MODIFY, exclusive = true)
            val blocked = PluginConflictAnalysis.analyze(HookRegistrations.snapshot()).filter { it.blocking }
            assertEquals(2, blocked.size); assertTrue(blocked.none { "a" in it.owners || "b" in it.owners })
        } finally { handles.forEach { it.close() } }
    }
    @Test fun builtinUnknownOccupancyBlocksExclusiveAndClosingReleasesIt() {
        val builtin = HookRegistrations.register("AM++", true, target)
        val plugin = HookRegistrations.register("plugin", false, target, access = HookAccess.MODIFY, exclusive = true)
        try {
            assertTrue(PluginConflictAnalysis.analyze(HookRegistrations.snapshot()).single().blocking)
            builtin.close(); assertTrue(PluginConflictAnalysis.analyze(HookRegistrations.snapshot()).isEmpty())
        } finally { builtin.close(); plugin.close() }
    }
    @Test fun overloadsAreDistinctAndLateRegistrationNotifiesAndResourcesBlock() {
        var changes = 0; val listener = HookRegistrations.listen { changes++ }
        val a = HookRegistrations.register("a", false, target, access = HookAccess.MODIFY, exclusive = true)
        val b = HookRegistrations.register("b", false, Target::class.java.getDeclaredMethod("action", String::class.java), access = HookAccess.MODIFY)
        val r1 = HookRegistrations.register("a", false, resource = "view:menu", exclusive = true)
        val r2 = HookRegistrations.register("b", false, resource = "view:menu")
        try {
            val found = PluginConflictAnalysis.analyze(HookRegistrations.snapshot()).single()
            assertEquals("view:menu", found.target); assertTrue(found.blocking); assertEquals(4, changes)
            r2.close(); assertTrue(PluginConflictAnalysis.analyze(HookRegistrations.snapshot()).isEmpty())
        } finally { a.close(); b.close(); r1.close(); r2.close(); listener.close() }
    }
}
