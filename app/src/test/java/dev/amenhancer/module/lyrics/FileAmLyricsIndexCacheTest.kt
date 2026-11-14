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
 * The visible contract of the AM-Lyrics index cache: a lookup is answered from memory or
 * disk, a stale copy is answered *and* refreshed in the background, a conditional 304
 * never rewrites the body, a corrupt copy is discarded instead of served, and a failed
 * refresh keeps the old copy while backing off.
 */
class FileAmLyricsIndexCacheTest {

    @get:Rule
    val temporary = TemporaryFolder()

    @Test
    fun `a stored index answers from memory and then from disk without a request`() {
        val directory = temporary.newFolder()
        val transport = FakeTransport { _, _ -> error("the cache must not need the network") }
        val cache = cache(directory, transport, QueuedExecutor())

        assertTrue(cache.write(indexJson(42L, "First"), "\"etag-1\"", NOW))
        assertEquals(42L, cache.index()!!.entries.single().appleMusicId)

        // A fresh instance is a new process: the copy on disk must still answer.
        val reopened = cache(directory, transport, QueuedExecutor())
        assertEquals(42L, reopened.index()!!.entryFor(42L)!!.appleMusicId)
        assertTrue(transport.calls.isEmpty())
    }

    @Test
    fun `a stale copy is served at once and refreshed in the background`() {
        val directory = temporary.newFolder()
        val executor = QueuedExecutor()
        val transport = FakeTransport { _, _ -> ok(indexJson(77L, "Second"), "\"etag-2\"") }
        val cache = cache(directory, transport, executor)

        cache.write(indexJson(42L, "First"), "\"etag-1\"", NOW - THIRTEEN_HOURS_MS)

        // The lookup itself is answered from disk and pays no network cost...
        assertEquals(42L, cache.index()!!.entries.single().appleMusicId)
        assertTrue(transport.calls.isEmpty())
        // ...while exactly one refresh is now queued for the executor's thread.
        assertEquals(1, executor.tasks.size)

        executor.runAll()

        assertEquals(77L, cache.index()!!.entries.single().appleMusicId)
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
        cache.write(indexJson(42L, "First"), "\"etag-1\"", NOW - THIRTEEN_HOURS_MS)

        val indexFile = File(directory, FileAmLyricsIndexCache.INDEX_FILE_NAME)
        val originalBytes = indexFile.readBytes()
        // A fixed stamp makes "the body was not rewritten" observable, not a same-second guess.
        assertTrue(indexFile.setLastModified(FIXED_STAMP_MS))

        assertEquals(42L, cache.index()!!.entries.single().appleMusicId)
        executor.runAll()

        assertEquals(listOf<String?>("\"etag-1\""), transport.conditionals)
        assertArrayEquals(originalBytes, indexFile.readBytes())
        assertEquals(FIXED_STAMP_MS, indexFile.lastModified())
        assertEquals(
            NOW,
            JSONObject(File(directory, FileAmLyricsIndexCache.META_FILE_NAME).readText())
                .getLong("fetchedAtMs"),
        )
        // The 304 revalidated the copy, so the TTL starts again instead of refreshing per lookup.
        cache.index()
        assertEquals(0, executor.tasks.size)
    }

    @Test
    fun `a corrupt copy on disk is discarded and fetched again`() {
        val directory = temporary.newFolder()
        File(directory, FileAmLyricsIndexCache.INDEX_FILE_NAME).writeText("{ not an index")
        File(directory, FileAmLyricsIndexCache.META_FILE_NAME).writeText(
            """{"version":1,"etag":"\"etag-1\"","fetchedAtMs":$NOW}""",
        )
        val executor = QueuedExecutor()
        val transport = FakeTransport { _, _ -> ok(indexJson(42L, "First"), "\"etag-2\"") }
        val cache = cache(directory, transport, executor)

        // A payload this build cannot use must never be served - and must not stay on disk.
        assertNull(cache.index())
        assertFalse(File(directory, FileAmLyricsIndexCache.INDEX_FILE_NAME).exists())
        assertFalse(File(directory, FileAmLyricsIndexCache.META_FILE_NAME).exists())

        executor.runAll()

        assertEquals(42L, cache.index()!!.entries.single().appleMusicId)
        assertTrue(File(directory, FileAmLyricsIndexCache.INDEX_FILE_NAME).isFile)
    }

    @Test
    fun `an unparseable download is discarded and the next endpoint is tried`() {
        val directory = temporary.newFolder()
        val executor = QueuedExecutor()
        val urls = listOf("https://mirror.test/amlyrics/index.json", "https://raw.test/index.json")
        val transport = FakeTransport { url, _ ->
            if (url == urls.first()) {
                ok("{\"version\":99}", "\"mirror-etag\"")
            } else {
                ok(indexJson(42L, "Fallback"), "\"raw-etag\"")
            }
        }
        val cache = cache(directory, transport, executor, urls = { urls })
        cache.write(indexJson(7L, "Previous"), "\"etag-1\"", NOW - THIRTEEN_HOURS_MS)

        assertEquals(7L, cache.index()!!.entries.single().appleMusicId)
        executor.runAll()

        assertEquals(urls, transport.calls)
        // The ETag of the discarded payload is never sent to the next host.
        assertEquals(listOf<String?>("\"etag-1\"", null), transport.conditionals)
        assertEquals(42L, cache.index()!!.entries.single().appleMusicId)
    }

    @Test
    fun `a failed refresh keeps the old copy and backs off for an hour`() {
        val directory = temporary.newFolder()
        val executor = QueuedExecutor()
        var now = NOW
        val transport = FakeTransport { _, _ -> null }
        val cache = cache(directory, transport, executor, clock = { now })
        cache.write(indexJson(42L, "First"), "\"etag-1\"", NOW - THIRTEEN_HOURS_MS)

        assertEquals(42L, cache.index()!!.entries.single().appleMusicId)
        executor.runAll()

        // The old copy survives the failure, and a lookup inside the backoff window does not
        // start another refresh (a dead network must not cost one request per song).
        assertEquals(42L, cache.index()!!.entries.single().appleMusicId)
        assertEquals(0, executor.tasks.size)

        now += FileAmLyricsIndexCache.RETRY_BACKOFF_MS
        assertEquals(42L, cache.index()!!.entries.single().appleMusicId)
        assertEquals(1, executor.tasks.size)
    }

    private fun cache(
        directory: File,
        transport: LyricHttpTransport,
        executor: Executor,
        urls: () -> List<String> = { listOf(DEFAULT_URL) },
        clock: () -> Long = { NOW },
    ): FileAmLyricsIndexCache = FileAmLyricsIndexCache(
        directory = directory,
        transport = transport,
        indexUrls = urls,
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
        const val NOW = 1_700_000_000_000L
        const val FIXED_STAMP_MS = 1_000_000L
        const val THIRTEEN_HOURS_MS = 13L * 60L * 60L * 1000L
        const val SHA = "0123456789abcdef0123456789abcdef0123456789abcdef0123456789abcdef"
        const val DEFAULT_URL = "https://mirror.test/amlyrics/index.json"

        /** A payload that passes [dev.amenhancer.module.lyrics.source.AmLyricsClient.parseIndex]. */
        fun indexJson(appleMusicId: Long, displayName: String): String = """
            {
              "version": 1,
              "layout": "artist-title-id",
              "entries": [
                {
                  "appleMusicId": $appleMusicId,
                  "alternateIds": [],
                  "displayName": "$displayName",
                  "path": "am-lyrics/artist/title/$appleMusicId.ttml",
                  "enabled": true,
                  "sizeBytes": 1024,
                  "sha256": "$SHA"
                }
              ]
            }
        """.trimIndent()
    }
}
