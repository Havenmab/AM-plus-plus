package dev.amenhancer.plugin.runtime

import dev.amenhancer.module.hook.HookAccess
import dev.amenhancer.module.hook.HookRegistrations
import org.junit.Assert.*
import org.junit.Test
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference

class PluginConcurrencyTest {
    @Test fun blockedPluginDoesNotPreventEarlierOrLaterPluginsStarting() {
        val entered = CountDownLatch(1); val release = CountDownLatch(1); val started = CountDownLatch(2)
        val failure = AtomicReference<Throwable>()
        val lives = List(3) { PluginLifecycle { it() } }
        PluginTasks().use { tasks ->
            try {
                tasks.prepareAndLoad(
                    prepare = { lives },
                    load = { snapshot ->
                        snapshot.forEachIndexed { index, life ->
                            tasks.loadPlugin(
                                load = {
                                    assertFalse(life.scope.isActive)
                                    if (index == 1) { entered.countDown(); release.await() }
                                },
                                complete = { life.start {}; if (index != 1) started.countDown() },
                                failed = { failure.set(it); started.countDown(); entered.countDown() }
                            )
                        }
                    },
                    failed = { failure.set(it); entered.countDown() }
                )
                assertTrue(entered.await(5, TimeUnit.SECONDS))
                assertTrue("Healthy plugins waited for blocked onLoad", started.await(5, TimeUnit.SECONDS))
                failure.get()?.let { throw AssertionError("Loading failed", it) }
                assertEquals(PluginRunState.ACTIVE, lives[0].state)
                assertEquals(PluginRunState.LOADING, lives[1].state)
                assertFalse(lives[1].scope.isActive)
                assertEquals(PluginRunState.ACTIVE, lives[2].state)
            } finally { lives.forEach { it.close(PluginRunState.DISABLED, "test") }; release.countDown() }
        }
    }

    @Test fun concurrentRegistrationAfterSnapshotGetsAnotherConflictCheck() {
        val taken = CountDownLatch(1); val release = CountDownLatch(1); val added = CountDownLatch(1)
        val done = CountDownLatch(1); val failure = AtomicReference<Throwable>()
        val observed = AtomicReference<List<PluginConflict>>(emptyList())
        val lives = mapOf("one" to PluginLifecycle { it() }, "two" to PluginLifecycle { it() })
        lives.values.forEach { it.start {} }
        var pause = true
        val checks = PluginConflictChecks {
            val snapshot = HookRegistrations.snapshot()
            if (pause) { pause = false; taken.countDown(); check(release.await(5, TimeUnit.SECONDS)) }
            val found = PluginConflictAnalysis.analyze(snapshot)
            observed.set(found)
            found.filter { it.blocking }.flatMap { it.owners }.forEach { lives[it]?.close(PluginRunState.BLOCKED, "conflict") }
        }
        val first = HookRegistrations.register("one", false, resource = "test:concurrency", access = HookAccess.MODIFY, exclusive = true)
        // Registry listeners run in order: signal that the new record exists before its check waits.
        val signal = HookRegistrations.listen { added.countDown() }
        val listener = HookRegistrations.listen { checks.check() }
        val second = AtomicReference<AutoCloseable>()
        val analysis = Thread { try { checks.check() } catch (error: Throwable) { failure.set(error) } }
        val registration = Thread {
            try { second.set(HookRegistrations.register("two", false, resource = "test:concurrency", access = HookAccess.MODIFY)) }
            catch (error: Throwable) { failure.set(error) }
            finally { done.countDown() }
        }
        try {
            analysis.start()
            assertTrue(taken.await(5, TimeUnit.SECONDS))
            registration.start()
            assertTrue(added.await(5, TimeUnit.SECONDS))
            release.countDown()
            assertTrue(done.await(5, TimeUnit.SECONDS)); analysis.join(5000)
            failure.get()?.let { throw AssertionError("Concurrent check failed", it) }
            assertTrue(observed.get().any { it.blocking && it.owners == setOf("one", "two") })
            assertTrue(lives.values.all { it.state == PluginRunState.BLOCKED })
        } finally {
            release.countDown(); analysis.join(5000)
            if (registration.isAlive) registration.join(5000)
            listener.close(); signal.close(); first.close(); second.get()?.close()
        }
    }

    @Test fun completedLoadChecksConflictsWithStillLoadingPlugin() {
        val entered = CountDownLatch(1); val release = CountDownLatch(1); val completed = CountDownLatch(2)
        val failure = AtomicReference<Throwable>()
        val lives = mapOf("one" to PluginLifecycle { it() }, "two" to PluginLifecycle { it() }, "other" to PluginLifecycle { it() })
        val checks = PluginConflictChecks {
            PluginConflictAnalysis.analyze(HookRegistrations.snapshot()).filter { it.blocking }
                .flatMap { it.owners }.forEach { lives[it]?.close(PluginRunState.BLOCKED, "conflict") }
        }
        val listener = HookRegistrations.listen { checks.check() }
        PluginTasks().use { tasks ->
            try {
                lives.forEach { (id, life) ->
                    tasks.loadPlugin(
                        load = {
                            if (id != "one") check(entered.await(5, TimeUnit.SECONDS))
                            if (id != "other") {
                                val record = HookRegistrations.register(id, false, resource = "test:loading-conflict", exclusive = id == "one",
                                    retained = { !life.scope.isClosed })
                                life.scope.onClose { record.close() }
                            }
                            if (id == "one") { entered.countDown(); release.await() }
                        },
                        complete = { checks.check(); life.start {}; if (id != "one") completed.countDown() },
                        failed = { failure.set(it); entered.countDown(); completed.countDown() }
                    )
                }
                assertTrue(completed.await(5, TimeUnit.SECONDS))
                failure.get()?.let { throw AssertionError("Loading/conflict failed", it) }
                assertEquals(PluginRunState.BLOCKED, lives.getValue("one").state)
                assertEquals(PluginRunState.BLOCKED, lives.getValue("two").state)
                assertEquals(PluginRunState.ACTIVE, lives.getValue("other").state)
            } finally {
                listener.close(); lives.values.forEach { it.close(PluginRunState.DISABLED, "test") }; release.countDown()
            }
        }
    }

    @Test fun registrationsDuringCheckTriggerReentrantPassWithoutRecursing() {
        var passes = 0; var depth = 0; var maxDepth = 0
        val found = AtomicReference<List<PluginConflict>>(emptyList())
        var second: AutoCloseable? = null
        val checks = PluginConflictChecks {
            depth++; maxDepth = maxOf(maxDepth, depth)
            val snapshot = HookRegistrations.snapshot()
            if (++passes == 1) second = HookRegistrations.register("two", false, resource = "test:reentrant")
            found.set(PluginConflictAnalysis.analyze(snapshot)); depth--
        }
        val first = HookRegistrations.register("one", false, resource = "test:reentrant", exclusive = true)
        val listener = HookRegistrations.listen { checks.check() }
        try {
            checks.check()
            assertEquals(2, passes); assertEquals(1, maxDepth)
            assertTrue(found.get().single().blocking)
        } finally { listener.close(); first.close(); second?.close() }
    }
}
