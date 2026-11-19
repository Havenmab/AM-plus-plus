package dev.amenhancer.module.hook

import dev.amenhancer.module.model.CustomLyricsSources
import java.nio.file.Files
import java.util.ArrayDeque
import java.util.concurrent.Executor
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

class AutoLyricsReplacementSessionTest {
    @Test
    fun `cold lookup keeps original path until a validated candidate publishes`() {
        val queued = QueuedExecutor()
        val cache = MemoryCache()
        val pointer = Pointer()
        var fetches = 0
        var published = 0
        var fresh = 0
        val session = session(
            queued = queued,
            cache = cache,
            fetch = { _, source ->
                assertEquals(CustomLyricsSources.AMLL, source)
                fetches += 1
                AutoLyricsCandidate(source, WORD_TTML)
            },
            parse = { pointer },
            onPublished = { published += 1 },
            onFresh = { fresh += 1 },
        )

        assertNull(session.replacementFor(42L))
        assertEquals(0, fetches)

        queued.runAll()

        assertSame(pointer, session.replacementFor(42L))
        assertEquals(1, fetches)
        assertEquals(1, published)
        assertEquals(1, fresh)
        assertEquals(42L, pointer.adamId)
        assertEquals(WORD_TTML, cache.values[42L to CustomLyricsSources.AMLL]?.ttml)
    }

    @Test
    fun `a persisted Word cache is prepared before any network source`() {
        val queued = QueuedExecutor()
        val cache = MemoryCache(
            mapOf(
                (42L to CustomLyricsSources.AMLL) to
                    AutoLyricsCandidate(CustomLyricsSources.AMLL, WORD_TTML),
            ),
        )
        val pointer = Pointer()
        var fetches = 0
        val session = session(
            queued = queued,
            cache = cache,
            fetch = { _, _ ->
                fetches += 1
                AutoLyricsCandidate(CustomLyricsSources.LUNABEAT, WORD_TTML)
            },
            parse = { pointer },
        )

        assertNull(session.replacementFor(42L))
        queued.runAll()

        assertSame(pointer, session.readyReplacementFor(42L))
        assertEquals(0, fetches)
    }

    @Test
    fun `stale generation cannot publish after the current song changes`() {
        val queued = QueuedExecutor()
        var fetches = 0
        var published = 0
        val session = session(
            queued = queued,
            fetch = { _, source ->
                fetches += 1
                AutoLyricsCandidate(source, WORD_TTML)
            },
            parse = { Pointer() },
            onPublished = { published += 1 },
        )

        session.onSongChanged(42L)
        assertNull(session.replacementFor(42L))
        session.onSongChanged(43L)
        queued.runAll()

        assertNull(session.readyReplacementFor(42L))
        assertEquals(0, fetches)
        assertEquals(0, published)
    }

    @Test
    fun `repeated metadata for the same song keeps the pending lookup and pointer`() {
        val queued = QueuedExecutor()
        val pointer = Pointer()
        var fetches = 0
        val session = session(
            queued = queued,
            fetch = { _, source ->
                fetches += 1
                AutoLyricsCandidate(source, WORD_TTML)
            },
            parse = { pointer },
        )

        session.onSongChanged(42L)
        session.ensureRequested(42L)
        session.onSongChanged(42L)
        queued.runAll()

        assertEquals(1, fetches)
        assertSame(pointer, session.readyReplacementFor(42L))
    }

    @Test
    fun `a manual replacement becoming ready cancels the queued automatic lookup`() {
        val queued = QueuedExecutor()
        var allowed = true
        var fetches = 0
        val session = session(
            queued = queued,
            fetch = { _, source ->
                fetches += 1
                AutoLyricsCandidate(source, WORD_TTML)
            },
            parse = { Pointer() },
            isAllowed = { allowed },
        )

        session.onSongChanged(42L)
        session.ensureRequested(42L)
        allowed = false
        queued.runAll()

        assertEquals(0, fetches)
        assertNull(session.candidateFor(42L))
        assertNull(session.readyReplacementFor(42L))
    }

    @Test
    fun `line timed search candidates now reach the native parser`() {
        val queued = QueuedExecutor()
        val pointer = Pointer()
        var parses = 0
        val session = session(
            queued = queued,
            fetch = { _, _ -> AutoLyricsCandidate("kuwo", LINE_TTML) },
            parse = { parses += 1; pointer },
        )

        assertNull(session.replacementFor(42L))
        queued.runAll()

        assertSame(pointer, session.readyReplacementFor(42L))
        assertEquals(1, parses)
    }

    @Test
    fun `a persisted per-source cache is prepared before any network source`() {
        val queued = QueuedExecutor()
        val cache = MemoryCache(
            mapOf((42L to CustomLyricsSources.AMLL) to AutoLyricsCandidate(CustomLyricsSources.AMLL, WORD_TTML)),
        )
        val pointer = Pointer()
        var fetches = 0
        val session = session(
            queued = queued,
            cache = cache,
            fetch = { _, _ -> fetches += 1; AutoLyricsCandidate("network", WORD_TTML) },
            parse = { pointer },
        )

        session.replacementFor(42L)
        queued.runAll()

        assertSame(pointer, session.readyReplacementFor(42L))
        assertEquals(0, fetches)
    }

    @Test
    fun `a persisted line timed candidate from its own source skips the network`() {
        val queued = QueuedExecutor()
        val cache = MemoryCache(
            mapOf(
                (42L to CustomLyricsSources.KUWO) to
                    AutoLyricsCandidate(CustomLyricsSources.KUWO, LINE_TTML),
            ),
        )
        val pointer = Pointer()
        var fetches = 0
        val session = session(
            queued = queued,
            cache = cache,
            fetch = { _, _ -> fetches += 1; AutoLyricsCandidate("network", WORD_TTML) },
            parse = { pointer },
            sources = listOf(CustomLyricsSources.KUWO),
        )

        session.replacementFor(42L)
        queued.runAll()

        assertSame(pointer, session.readyReplacementFor(42L))
        assertEquals(0, fetches)
    }

    @Test
    fun `a captured document discards the attempt that was already queued`() {
        val queued = QueuedExecutor()
        val pointer = Pointer()
        var fetches = 0
        var documentAvailable = false
        val session = session(
            queued = queued,
            fetch = { _, _ ->
                fetches += 1
                if (documentAvailable) AutoLyricsCandidate("online-translation", WORD_TTML) else null
            },
            parse = { pointer },
        )

        // Queue the attempt that runs without a displayed document.
        session.ensureRequested(42L)
        documentAvailable = true
        // The document arrives while that attempt is still queued: it must be invalidated, and
        // the retry must be the one that fetches and publishes.
        session.onDisplayedDocumentCaptured(42L)

        queued.runNext()
        assertNull("the stale attempt must not publish", session.readyReplacementFor(42L))

        queued.runAll()
        assertEquals(1, fetches)
        assertSame(pointer, session.readyReplacementFor(42L))
    }

    @Test
    fun `a line timed candidate is still prepared from the provider-less cache slot`() {
        // The merged per-source read only accepts word timing; the legacy AUTO_CACHE slot stays the
        // one place a persisted line-timed document may win, and the seam policy accepts it.
        val queued = QueuedExecutor()
        val cache = MemoryCache(
            mapOf(
                (42L to CustomLyricsSources.AUTO_CACHE) to
                    AutoLyricsCandidate(CustomLyricsSources.AUTO_CACHE, LINE_TTML),
            ),
        )
        val pointer = Pointer()
        var fetches = 0
        val session = session(
            queued = queued,
            cache = cache,
            fetch = { _, _ -> fetches += 1; null },
            parse = { pointer },
        )

        session.replacementFor(42L)
        queued.runAll()

        assertSame(pointer, session.readyReplacementFor(42L))
        assertEquals(1, fetches)
    }

    @Test
    fun `a structurally invalid candidate fails open and never reaches the native parser`() {
        val queued = QueuedExecutor()
        var parses = 0
        val session = session(
            queued = queued,
            fetch = { _, source -> AutoLyricsCandidate("kuwo", MALFORMED_TTML) },
            parse = { parses += 1; Pointer() },
        )

        assertNull(session.replacementFor(42L))
        queued.runAll()

        assertNull(session.readyReplacementFor(42L))
        assertEquals(0, parses)
        assertTrue(session.isTracking(42L).not())
    }

    @Test
    fun `a newly captured displayed document retries an attempt that ran without one`() {
        val queued = QueuedExecutor()
        val pointer = Pointer()
        var fetches = 0
        var documentAvailable = false
        val session = session(
            queued = queued,
            fetch = { _, _ ->
                fetches += 1
                if (documentAvailable) AutoLyricsCandidate("online-translation", WORD_TTML) else null
            },
            parse = { pointer },
        )

        session.onSongChanged(42L)
        session.ensureRequested(42L)
        queued.runAll()

        assertEquals(1, fetches)
        assertNull(session.readyReplacementFor(42L))

        documentAvailable = true
        session.onDisplayedDocumentCaptured(42L)
        queued.runAll()

        assertEquals(2, fetches)
        assertSame(pointer, session.readyReplacementFor(42L))
    }

    @Test
    fun `a captured document for a replaced track is ignored`() {
        val queued = QueuedExecutor()
        var allowed = true
        var fetches = 0
        val session = session(
            queued = queued,
            fetch = { _, _ ->
                fetches += 1
                AutoLyricsCandidate("online-translation", WORD_TTML)
            },
            parse = { Pointer() },
            isAllowed = { allowed },
        )

        session.onSongChanged(42L)
        allowed = false
        session.onDisplayedDocumentCaptured(42L)
        queued.runAll()

        assertEquals(0, fetches)
    }

    @Test
    fun `file cache persists word and line TTML and reloads it by Adam ID`() {
        val directory = Files.createTempDirectory("ampp-auto-lyrics-test").toFile()
        try {
            val cache = FileAutoLyricsCache(directory, maxEntries = 2)
            assertTrue(cache.write(42L, WORD_TTML))
            assertEquals(WORD_TTML, FileAutoLyricsCache(directory).read(42L))
            assertTrue(cache.write(43L, LINE_TTML))
            assertEquals(LINE_TTML, FileAutoLyricsCache(directory).read(43L))
        } finally {
            directory.deleteRecursively()
        }
    }

    @Test
    fun `file cache still rejects structurally invalid TTML`() {
        val directory = Files.createTempDirectory("ampp-auto-lyrics-test").toFile()
        try {
            val cache = FileAutoLyricsCache(directory, maxEntries = 2)
            assertTrue(!cache.write(42L, MALFORMED_TTML))
            assertEquals(null, cache.read(42L))
        } finally {
            directory.deleteRecursively()
        }
    }

    private fun session(
        queued: QueuedExecutor,
        cache: AutoLyricsCache = MemoryCache(),
        fetch: (Long, String) -> AutoLyricsCandidate?,
        parse: (String) -> Any?,
        onPublished: (Long) -> Unit = {},
        onFresh: (Long) -> Unit = {},
        isAllowed: (Long) -> Boolean = { true },
        sources: List<String> = listOf(CustomLyricsSources.AMLL),
    ): AutoLyricsReplacementSession {
        return AutoLyricsReplacementSession(
            fetchCandidate = fetch,
            cache = cache,
            parseTtml = parse,
            isAlive = { it is Pointer && it.live },
            verifyPtr = { it is Pointer && it.live },
            readAdamId = { (it as Pointer).adamId },
            bindAdamId = { value, id ->
                (value as Pointer).adamId = id
                true
            },
            onReplacementPublished = onPublished,
            onFreshCandidate = onFresh,
            isAllowed = isAllowed,
            executor = queued,
            logger = {},
        ).also { session ->
            session.onSongChanged(42L)
            session.configureSources(42L, sources)
        }
    }

    @Test
    fun `providerless cache is used after the requested providers miss`() {
        val queued = QueuedExecutor()
        val pointer = Pointer()
        val requested = mutableListOf<String>()
        val candidate = AutoLyricsCandidate(CustomLyricsSources.AUTO_CACHE, WORD_TTML)
        val cache = MemoryCache(mapOf((42L to CustomLyricsSources.AUTO_CACHE) to candidate))
        var fresh = 0
        val session = session(
            queued = queued,
            cache = cache,
            fetch = { _, source ->
                requested += source
                null
            },
            parse = { pointer },
            onFresh = { fresh += 1 },
            sources = listOf(CustomLyricsSources.AMLL, CustomLyricsSources.LUNABEAT),
        )

        assertNull(session.replacementFor(42L))
        queued.runAll()

        assertEquals(listOf(CustomLyricsSources.AMLL, CustomLyricsSources.LUNABEAT), requested)
        assertEquals(candidate, session.candidateFor(42L))
        assertSame(pointer, session.readyReplacementFor(42L))
        assertEquals(0, fresh)
    }

    @Test
    fun `cached candidate is not marked as a fresh download`() {
        val queued = QueuedExecutor()
        val pointer = Pointer()
        val candidate = AutoLyricsCandidate(CustomLyricsSources.LUNABEAT, WORD_TTML, "Cached song")
        val cache = MemoryCache(
            mapOf((42L to CustomLyricsSources.LUNABEAT) to candidate),
        )
        val requested = mutableListOf<String>()
        var published = 0
        var fresh = 0
        val session = AutoLyricsReplacementSession(
            fetchCandidate = { _, source ->
                requested += source
                null
            }, cache = cache,
            parseTtml = { pointer }, isAlive = { true }, verifyPtr = { true },
            readAdamId = { (it as Pointer).adamId },
            bindAdamId = { pointer, id ->
                (pointer as Pointer).adamId = id
                true
            },
            onReplacementPublished = { published += 1 },
            onFreshCandidate = { fresh += 1 },
            executor = queued, logger = {},
        )
        session.onSongChanged(42)
        session.configureSources(
            42,
            listOf(CustomLyricsSources.AMLL, CustomLyricsSources.LUNABEAT),
        )
        session.ensureRequested(42)
        queued.runAll()
        assertEquals(listOf(CustomLyricsSources.AMLL), requested)
        assertEquals(candidate, session.candidateFor(42))
        assertSame(pointer, session.readyReplacementFor(42))
        assertEquals(1, published)
        assertEquals(0, fresh)
    }

    private class Pointer(
        var adamId: Long = 0L,
        var live: Boolean = true,
    )

    private class MemoryCache(
        initial: Map<Pair<Long, String>, AutoLyricsCandidate> = emptyMap(),
    ) : AutoLyricsCache {
        val values = initial.toMutableMap()
        private val currentGeneration = 0L
        override fun readCandidate(appleMusicId: Long, source: String): AutoLyricsCandidate? =
            values[appleMusicId to source]
        override fun generation(): Long = currentGeneration
        override fun writeCandidateIfCurrent(
            appleMusicId: Long,
            candidate: AutoLyricsCandidate,
            generation: Long,
        ): Boolean {
            if (generation != currentGeneration) return false
            values[appleMusicId to candidate.source] = candidate
            return true
        }
    }

    private class QueuedExecutor : Executor {
        private val tasks = ArrayDeque<() -> Unit>()
        override fun execute(command: Runnable) {
            tasks.addLast { command.run() }
        }
        fun runAll() {
            while (tasks.isNotEmpty()) tasks.removeFirst().invoke()
        }

        /** Runs exactly one queued task, so a stale one can be observed on its own. */
        fun runNext() {
            if (tasks.isNotEmpty()) tasks.removeFirst().invoke()
        }
    }

    private companion object {
        const val WORD_TTML =
            "<tt xmlns:itunes=\"urn\" itunes:timing=\"Word\"><body>" +
                "<p><span begin=\"0s\" end=\"1s\">hello</span></p>" +
                "</body></tt>"
        const val LINE_TTML =
            "<tt xmlns:itunes=\"urn\" itunes:timing=\"Line\"><body>" +
                "<p>hello</p></body></tt>"
        const val MALFORMED_TTML = "<tt><p>no body</p></tt>"
    }
}
