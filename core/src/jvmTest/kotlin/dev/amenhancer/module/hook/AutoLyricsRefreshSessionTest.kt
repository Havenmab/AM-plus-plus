package dev.amenhancer.module.hook

import dev.amenhancer.module.model.CustomLyricsEntry
import dev.amenhancer.module.model.CustomLyricsSources
import java.util.ArrayDeque
import java.util.concurrent.Executor
import java.util.concurrent.RejectedExecutionException
import org.junit.Assert.*
import org.junit.Test

class AutoLyricsRefreshSessionTest {
    private class Queue : Executor {
        val tasks = ArrayDeque<Runnable>()
        override fun execute(command: Runnable) { tasks.add(command) }
        fun runAll() { while (tasks.isNotEmpty()) tasks.remove().run() }
    }

    @Test fun `callbacks do no IO and each playback visit checks only once`() {
        val queue = Queue()
        val checked = mutableListOf<Long>()
        val session = AutoLyricsRefreshSession(queue, { id, _ -> checked += id; null }, { true }, { _, _ -> })
        session.onSongChanged(42)
        repeat(4) { session.onSongChanged(42) }
        assertTrue(checked.isEmpty())
        queue.runAll()
        assertEquals(listOf(42L), checked)
        session.onSongChanged(43)
        queue.runAll()
        session.onSongChanged(42)
        queue.runAll()
        assertEquals(listOf(42L, 43L, 42L), checked)
    }

    @Test fun `remote checks wait for the cached pointer without repeating after readiness`() {
        val queue = Queue()
        var checks = 0
        val session = AutoLyricsRefreshSession(queue, { _, _ -> checks++; null }, { true }, { _, _ -> })
        session.onSongChanged(42, cacheReady = false)
        queue.runAll()
        assertEquals(0, checks)
        session.onSongChanged(42, cacheReady = true)
        session.onSongChanged(42, cacheReady = true)
        queue.runAll()
        assertEquals(1, checks)
        session.onSongChanged(43, cacheReady = false)
        session.markDownloaded(43)
        session.onSongChanged(43, cacheReady = true)
        queue.runAll()
        assertEquals(1, checks)
    }

    @Test fun `late network result cannot update another playback generation`() {
        val queue = Queue()
        val applied = mutableListOf<Long>()
        lateinit var session: AutoLyricsRefreshSession
        session = AutoLyricsRefreshSession(queue, { id, cancelled ->
            if (id == 42L) {
                session.onSongChanged(43)
                assertTrue(cancelled())
            }
            entry(id)
        }, { true }, { entry, _ -> applied += entry.appleMusicId })
        session.onSongChanged(42)
        queue.runAll()
        assertEquals(listOf(43L), applied)
    }

    @Test fun `switching off or closing cancels an in flight check`() {
        for (close in listOf(false, true)) {
            val queue = Queue()
            var enabled = true
            var applied = false
            lateinit var session: AutoLyricsRefreshSession
            session = AutoLyricsRefreshSession(queue, { id, cancelled ->
                if (close) session.close() else enabled = false
                assertTrue(cancelled())
                entry(id)
            }, { enabled }, { _, _ -> applied = true })
            session.onSongChanged(42)
            queue.runAll()
            assertFalse(applied)
        }
    }

    @Test fun `freshly downloaded lyrics skip the queued remote check`() {
        val queue = Queue()
        var checks = 0
        val session = AutoLyricsRefreshSession(queue, { _, _ -> checks++; null }, { true }, { _, _ -> })
        session.onSongChanged(42)
        session.markDownloaded(42)
        queue.runAll()
        assertEquals(0, checks)
        session.onSongChanged(43)
        queue.runAll()
        assertEquals(1, checks)
    }

    @Test fun `rejected task can be scheduled again without a stuck pending state`() {
        var reject = true
        var checks = 0
        val session = AutoLyricsRefreshSession(Executor {
            if (reject) throw RejectedExecutionException()
            it.run()
        }, { _, _ -> checks++; null }, { true }, { _, _ -> })
        session.onSongChanged(42)
        reject = false
        session.onSongChanged(42)
        assertEquals(1, checks)
    }

    private fun entry(id: Long) = CustomLyricsEntry(id, "Song", "lyrics_$id", 1, "a".repeat(64), CustomLyricsSources.AMLL, true)
}
