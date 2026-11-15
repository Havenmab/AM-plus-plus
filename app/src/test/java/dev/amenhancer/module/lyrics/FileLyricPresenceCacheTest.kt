package dev.amenhancer.module.lyrics

import dev.amenhancer.module.lyrics.source.LyricHttpResponse
import dev.amenhancer.module.lyrics.source.LyricHttpTransport
import java.io.File
import java.net.HttpURLConnection
import java.util.concurrent.Executor
import org.json.JSONObject
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/**
 * The visible contract of the presence cache: a lookup is answered from memory or disk, a stale
 * copy is answered *and* refreshed in the background, a conditional 304 never rewrites the body,
 * a corrupt copy is discarded instead of served, a failed refresh keeps the old copy while
 * backing off, and without a self-hosted mirror the cache never leaves the "unknown" state.
 *
 * The negative answer is the point of the whole file, so it is tested from both sides: nothing
 * loaded is null (ask every repository), while a loaded index that does not name the ID is an
 * empty set (skip every repository).
 */
class FileLyricPresenceCacheTest {

    @get:Rule
    val temporary = TemporaryFolder()

    @Test
    fun `a stored index answers from memory and then from disk without a request`() {
        val directory = temporary.newFolder()
        val transport = FakeTransport { _, _ -> error("the cache must not need the network") }
        val cache = cache(directory, transport, QueuedExecutor())

        assertTrue(cache.write(presenceJson(42L to "al"), "\"etag-1\"", NOW))
        assertEquals(setOf("a", "l"), cache.sourcesFor(42L))

        // A fresh instance is a new process: the copy on disk must still answer.
        val reopened = cache(directory, transport, QueuedExecutor())
        assertEquals(setOf("a", "l"), reopened.sourcesFor(42L))
        assertTrue(transport.calls.isEmpty())
    }

    @Test
    fun `a loaded index answers empty for an id it does not name`() {
        val directory = temporary.newFolder()
        val cache = cache(directory, FakeTransport { _, _ -> null }, QueuedExecutor())

        // Nothing loaded: unknown, so the resolver keeps asking every repository.
        assertNull(cache.sourcesFor(42L))
        assertTrue(cache.write(presenceJson(42L to "al"), null, NOW))

        // Loaded: the index itself is the answer. "Not listed" is empty, not null - that is the
        // difference between "no repository carries this" and "we do not know yet".
        assertEquals(setOf("a", "l"), cache.sourcesFor(42L))
        assertEquals(emptySet<String>(), cache.sourcesFor(99L))
    }

    @Test
    fun `a stale copy is served at once and refreshed in the background`() {
        val directory = temporary.newFolder()
        val executor = QueuedExecutor()
        val transport = FakeTransport { _, _ -> ok(presenceJson(43L to "n"), "\"etag-2\"") }
        val cache = cache(directory, transport, executor)

        assertTrue(cache.write(presenceJson(42L to "al"), "\"etag-1\"", NOW - THIRTEEN_HOURS_MS))

        // The lookup itself is answered from disk and pays no network cost...
        assertEquals(setOf("a", "l"), cache.sourcesFor(42L))
        assertTrue(transport.calls.isEmpty())
        // ...while exactly one refresh is queued for the executor's thread.
        assertEquals(1, executor.tasks.size)

        executor.runAll()

        // The new payload replaces the old one: IDs it does not name are now "absent".
        assertEquals(emptySet<String>(), cache.sourcesFor(42L))
        assertEquals(setOf("n"), cache.sourcesFor(43L))
        assertEquals(1, transport.calls.size)
        // And the refreshed copy is fresh, so nothing else is scheduled.
        assertEquals(0, executor.tasks.size)
    }

    @Test
    fun `a not modified response only bumps the fetch time`() {
        val directory = temporary.newFolder()
        val executor = QueuedExecutor()
        val transport = FakeTransport { _, ifNoneMatch ->
            if (ifNoneMatch == "\"etag-1\"") {
                LyricHttpResponse(HttpURLConnection.HTTP_NOT_MODIFIED, body = null, etag = "\"etag-1\"")
            } else {
                error("the cached validator must be sent")
            }
        }
        val cache = cache(directory, transport, executor)
        cache.write(presenceJson(42L to "al"), "\"etag-1\"", NOW - THIRTEEN_HOURS_MS)

        val presenceFile = File(directory, FileLyricPresenceCache.PRESENCE_FILE_NAME)
        val originalBytes = presenceFile.readBytes()
        // A fixed stamp makes "the body was not rewritten" observable, not a same-second guess.
        assertTrue(presenceFile.setLastModified(FIXED_STAMP_MS))

        assertEquals(setOf("a", "l"), cache.sourcesFor(42L))
        executor.runAll()

        assertEquals(listOf<String?>("\"etag-1\""), transport.conditionals)
        assertArrayEquals(originalBytes, presenceFile.readBytes())
        assertEquals(FIXED_STAMP_MS, presenceFile.lastModified())
        assertEquals(
            NOW,
            JSONObject(File(directory, FileLyricPresenceCache.META_FILE_NAME).readText())
                .getLong("fetchedAtMs"),
        )
        // The 304 revalidated the copy, so the TTL starts again instead of refreshing per lookup.
        cache.sourcesFor(42L)
        assertEquals(0, executor.tasks.size)
    }

    @Test
    fun `a blank custom root keeps the cache disabled`() {
        val directory = temporary.newFolder()
        val executor = QueuedExecutor()
        val transport = FakeTransport { _, _ -> ok(presenceJson(42L to "al"), "\"etag\"") }
        val cache = cache(directory, transport, executor, customRoot = "  ")

        assertFalse(cache.enabled)
        // No mirror, no index: the resolver keeps the retry-everything behaviour it had before.
        assertNull(cache.sourcesFor(42L))
        cache.refreshIfStale()

        assertTrue(executor.tasks.isEmpty())
        assertTrue(transport.calls.isEmpty())
        assertFalse(File(directory, FileLyricPresenceCache.PRESENCE_FILE_NAME).exists())

        // The public hosts publish no presence file, so a blank root has no endpoint at all.
        assertNull(FileLyricPresenceCache.presenceUrlFor(null))
        assertNull(FileLyricPresenceCache.presenceUrlFor(""))
        assertNull(FileLyricPresenceCache.presenceUrlFor("   "))
        assertEquals("$MIRROR/presence.json", FileLyricPresenceCache.presenceUrlFor(MIRROR))
        assertEquals(
            "$MIRROR/presence.json",
            FileLyricPresenceCache.presenceUrlFor("$MIRROR/"),
        )
    }

    @Test
    fun `a corrupt copy on disk is discarded and fetched again`() {
        val directory = temporary.newFolder()
        val presenceFile = File(directory, FileLyricPresenceCache.PRESENCE_FILE_NAME)
        val metaFile = File(directory, FileLyricPresenceCache.META_FILE_NAME)
        presenceFile.writeText("{ not a presence index")
        metaFile.writeText("""{"version":1,"etag":"\"etag-1\"","fetchedAtMs":$NOW}""")
        val executor = QueuedExecutor()
        val transport = FakeTransport { _, _ -> ok(presenceJson(42L to "al"), "\"etag-2\"") }
        val cache = cache(directory, transport, executor)

        // A payload this build cannot use must never be served - and must not stay on disk.
        assertNull(cache.sourcesFor(42L))
        assertFalse(presenceFile.exists())
        assertFalse(metaFile.exists())

        executor.runAll()

        assertEquals(setOf("a", "l"), cache.sourcesFor(42L))
        assertTrue(presenceFile.isFile)
    }

    @Test
    fun `an unparseable download is discarded and fetched again on the next lookup`() {
        val directory = temporary.newFolder()
        val executor = QueuedExecutor()
        var payload = "{\"42\":"
        val transport = FakeTransport { _, _ -> ok(payload, "\"etag-1\"") }
        val cache = cache(directory, transport, executor)
        cache.write(presenceJson(42L to "al"), "\"etag-1\"", NOW - THIRTEEN_HOURS_MS)

        // The stale copy answers once, then the background refresh serves something unusable.
        assertEquals(setOf("a", "l"), cache.sourcesFor(42L))
        executor.runAll()
        assertEquals(1, transport.calls.size)

        // Nothing usable is served and nothing is kept, so the next lookup asks again at once -
        // a publisher-side problem is not the network failure the backoff is for.
        assertNull(cache.sourcesFor(42L))
        assertFalse(File(directory, FileLyricPresenceCache.PRESENCE_FILE_NAME).exists())
        assertEquals(1, executor.tasks.size)

        payload = presenceJson(42L to "a")
        executor.runAll()

        // The ETag of the discarded payload went with it, so the retry asked for the body again.
        assertEquals(listOf<String?>("\"etag-1\"", null), transport.conditionals)
        assertEquals(setOf("a"), cache.sourcesFor(42L))
    }

    @Test
    fun `a failed refresh keeps the old copy and backs off for an hour`() {
        val directory = temporary.newFolder()
        val executor = QueuedExecutor()
        var now = NOW
        val transport = FakeTransport { _, _ -> null }
        val cache = cache(directory, transport, executor, clock = { now })
        cache.write(presenceJson(42L to "al"), "\"etag-1\"", NOW - THIRTEEN_HOURS_MS)

        assertEquals(setOf("a", "l"), cache.sourcesFor(42L))
        executor.runAll()

        // The old copy survives the failure, and a lookup inside the backoff window does not
        // start another refresh (a dead mirror must not cost one request per song).
        assertEquals(setOf("a", "l"), cache.sourcesFor(42L))
        assertEquals(0, executor.tasks.size)

        now += FileLyricPresenceCache.RETRY_BACKOFF_MS
        assertEquals(setOf("a", "l"), cache.sourcesFor(42L))
        assertEquals(1, executor.tasks.size)
    }

    private fun cache(
        directory: File,
        transport: LyricHttpTransport,
        executor: Executor,
        customRoot: String? = MIRROR,
        clock: () -> Long = { NOW },
    ): FileLyricPresenceCache = FileLyricPresenceCache(
        directory = directory,
        transport = transport,
        customRoot = customRoot,
        executor = executor,
        clock = clock,
    )

    private fun ok(json: String, etag: String?): LyricHttpResponse =
        LyricHttpResponse(HttpURLConnection.HTTP_OK, json.toByteArray(Charsets.UTF_8), etag)

    private class FakeTransport(
        private val handler: (String, String?) -> LyricHttpResponse?,
    ) : LyricHttpTransport {
        val calls = mutableListOf<String>()
        val conditionals = mutableListOf<String?>()

        override fun get(url: String): String? = throw UnsupportedOperationException("use getResponse")

        override fun getResponse(url: String, ifNoneMatch: String?): LyricHttpResponse? {
            calls += url
            conditionals += ifNoneMatch
            return handler(url, ifNoneMatch)
        }
    }

    /** Records what the cache schedules; the test decides when the background work runs. */
    private class QueuedExecutor : Executor {
        val tasks = mutableListOf<Runnable>()

        override fun execute(command: Runnable) {
            tasks += command
        }

        fun runAll() {
            while (tasks.isNotEmpty()) tasks.removeAt(0).run()
        }
    }

    private companion object {
        const val MIRROR = "https://havenmab.cc.cd:8443/lyrics"
        const val NOW = 1_700_000_000_000L
        const val FIXED_STAMP_MS = 1_000_000L
        const val THIRTEEN_HOURS_MS = 13L * 60L * 60L * 1000L

        /** The published shape: an Apple Music ID mapped to the letters of its repositories. */
        fun presenceJson(vararg entries: Pair<Long, String>): String =
            entries.fold(JSONObject()) { json, (appleMusicId, sources) ->
                json.put(appleMusicId.toString(), sources)
            }.toString()
    }
}
