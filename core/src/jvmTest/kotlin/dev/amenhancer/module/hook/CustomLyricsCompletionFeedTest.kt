package dev.amenhancer.module.hook

import dev.amenhancer.module.model.CustomLyricsEntry
import dev.amenhancer.module.model.CustomLyricsManifest
import dev.amenhancer.module.model.CustomLyricsSources
import java.util.concurrent.Executor
import java.util.concurrent.RejectedExecutionException
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Coverage for [CustomLyricsCompletionFeed], the bridge that gives a custom
 * (manual/module-mapped) lyrics document the translation/pronunciation
 * completion the automatic path could never run for it.
 *
 * The device bug: manual/mapped ids are excluded from the automatic session's
 * `isAllowed`, and only a successful automatic enrichment writes the native
 * lyric-model overlay — so a custom track got neither the document lane nor the
 * overlay and no romanization rendered at all. These tests pin the two halves of
 * the fix: the same enricher now runs for the custom document, and the searched
 * document it may return can never replace the custom body.
 */
class CustomLyricsCompletionFeedTest {

    @Test
    fun `the enricher receives the custom body and its merged result is forwarded, not re-enriched`() {
        val seen = mutableListOf<String>()
        val forwarded = mutableListOf<Pair<String, String>>()
        val feed = newFeed(
            onMergedDocument = { _, raw, merged -> forwarded += raw to merged },
        ) { _, ttml ->
            seen += ttml
            MERGED_DOCUMENT
        }
        feed.remember(42L, REVISION, CUSTOM_DOCUMENT)

        feed.onDisplayed(42L)
        feed.onDisplayed(42L)

        // One lane pass per (track, revision); the enricher only ever sees the
        // custom body, and its merged result is forwarded exactly once together
        // with the raw body so the session can re-check it before publishing.
        assertEquals(listOf(CUSTOM_DOCUMENT), seen)
        assertEquals(listOf(CUSTOM_DOCUMENT to MERGED_DOCUMENT), forwarded)
    }

    @Test
    fun `a new file revision is enriched again`() {
        val seen = mutableListOf<String>()
        val feed = newFeed { _, ttml ->
            seen += ttml
            "merged"
        }
        feed.remember(42L, REVISION, CUSTOM_DOCUMENT)
        feed.onDisplayed(42L)

        feed.remember(42L, REVISION_B, CUSTOM_DOCUMENT_B)
        feed.onDisplayed(42L)

        assertEquals(listOf(CUSTOM_DOCUMENT, CUSTOM_DOCUMENT_B), seen)
    }

    @Test
    fun `returning to a track after another display enriches it again`() {
        val seen = mutableListOf<Long>()
        val feed = newFeed { id, _ ->
            seen += id
            "merged"
        }
        feed.remember(42L, REVISION, CUSTOM_DOCUMENT)
        feed.remember(43L, REVISION, CUSTOM_DOCUMENT_B)

        feed.onDisplayed(42L)
        feed.onDisplayed(42L)
        feed.onDisplayed(43L)
        feed.onDisplayed(42L)

        // The native overlay holds one track at a time, so a revisit must run
        // again even though the document revision did not change.
        assertEquals(listOf(42L, 43L, 42L), seen)
    }

    @Test
    fun `a display without a remembered document never reaches the enricher`() {
        var calls = 0
        val feed = newFeed { _, _ ->
            calls += 1
            "merged"
        }

        feed.onDisplayed(42L)
        feed.onDisplayed(0L)

        assertEquals(0, calls)
    }

    @Test
    fun `the custom completion reports capture and inject for the track`() {
        val lines = mutableListOf<String>()
        val feed = newFeed(log = { _, line -> lines += line }) { _, _ -> "merged" }
        feed.remember(42L, REVISION, CUSTOM_DOCUMENT)

        feed.onDisplayed(42L)

        // The two visible-channel lines the automatic path emits from its parse
        // seam and inject point; the enricher adds `publish` with the per-lane
        // sources and the native hooks add `native-write`.
        assertEquals(2, lines.size)
        assertTrue(lines[0].startsWith("online-translation capture id=42 rawTtml=custom"))
        assertTrue(lines[0].contains("timing=WORD"))
        assertEquals(
            "online-translation inject id=42 source=online-translation published=true",
            lines[1],
        )
    }

    @Test
    fun `a throwing enricher fails open and is not retried for the same revision`() {
        var calls = 0
        val lines = mutableListOf<String>()
        val feed = newFeed(log = { _, line -> lines += line }) { _, _ ->
            calls += 1
            error("provider exploded")
        }
        feed.remember(42L, REVISION, CUSTOM_DOCUMENT)

        feed.onDisplayed(42L)
        feed.onDisplayed(42L)

        assertEquals(1, calls)
        assertTrue(lines.any { it.contains("published=false") })
    }

    @Test
    fun `a rejected schedule leaves the track retryable`() {
        var reject = true
        var calls = 0
        val queued = QueuedExecutor()
        val feed = CustomLyricsCompletionFeed(
            enrich = { _, _ ->
                calls += 1
                "merged"
            },
            executor = Executor { command ->
                if (reject) throw RejectedExecutionException("busy") else queued.execute(command)
            },
        )
        feed.remember(42L, REVISION, CUSTOM_DOCUMENT)

        feed.onDisplayed(42L)
        assertEquals(0, calls)

        reject = false
        feed.onDisplayed(42L)
        queued.runAll()

        assertEquals(1, calls)
    }

    @Test
    fun `the session still publishes the custom pointer after a searched enrichment`() {
        val queued = QueuedExecutor()
        val parsed = mutableListOf<String>()
        val enriched = mutableListOf<String>()
        val pointer = Pointer()
        lateinit var feed: CustomLyricsCompletionFeed
        val session = CustomLyricsReplacementSession(
            index = CustomLyricsIndexProvider {
                CustomLyricsManifest(listOf(entry(42L))).entries
                    .associateBy(CustomLyricsEntry::appleMusicId)
            },
            readTtml = { entry ->
                CUSTOM_DOCUMENT.also { ttml ->
                    feed.remember(entry.appleMusicId, entry.sha256, ttml)
                }
            },
            parseTtml = { parsed += it; pointer },
            isAlive = { it is Pointer },
            verifyPtr = { it is Pointer },
            readAdamId = { (it as Pointer).adamId },
            bindAdamId = { value, id -> (value as Pointer).adamId = id; true },
            executor = queued,
            logger = {},
        )
        feed = newFeed(
            executor = queued,
            onMergedDocument = { id, raw, merged -> session.publishMerged(id, raw, merged) },
        ) { _, ttml ->
            enriched += ttml
            SEARCHED_DOCUMENT
        }
        session.start()
        queued.runAll()

        assertNull(session.replacementFor(42L))
        queued.runAll()
        assertSame(pointer, session.replacementFor(42L))

        feed.onDisplayed(42L)
        queued.runAll()

        // The searched document never replaced the user's body: the session's
        // body-preservation check rejected it, so it never reached the parser and
        // the ready pointer is still the one built from the custom document.
        assertEquals(listOf(CUSTOM_DOCUMENT), enriched)
        assertEquals(listOf(CUSTOM_DOCUMENT), parsed)
        assertSame(pointer, session.readyReplacementFor(42L))
    }

    @Test
    fun `a head-only merged document is forwarded before the post-overlay refresh`() {
        val order = mutableListOf<String>()
        val queued = QueuedExecutor()
        val mergedPointer = Pointer()
        var parses = 0
        val session = CustomLyricsReplacementSession(
            index = CustomLyricsIndexProvider {
                CustomLyricsManifest(listOf(entry(42L))).entries
                    .associateBy(CustomLyricsEntry::appleMusicId)
            },
            readTtml = { CUSTOM_DOCUMENT },
            // No raw prepare runs in this test: the first and only parse is the
            // merged document the completion publishes.
            parseTtml = { parses += 1; mergedPointer },
            isAlive = { it is Pointer },
            verifyPtr = { it is Pointer },
            readAdamId = { (it as Pointer).adamId },
            bindAdamId = { value, id -> (value as Pointer).adamId = id; true },
            executor = queued,
            logger = {},
        )
        val feed = newFeed(
            executor = queued,
            onOverlayUpdated = { order += "overlay" },
            onMergedDocument = { id, raw, merged ->
                order += "merged"
                // The session must already hold the enriched pointer when the
                // post-overlay refresh re-invokes I2; that is the ordering the
                // callback order guarantees.
                assertTrue(session.publishMerged(id, raw, merged))
                assertSame(mergedPointer, session.readyReplacementFor(id))
            },
        ) { _, _ -> MERGED_DOCUMENT }
        session.start()
        queued.runAll()
        feed.remember(42L, REVISION, CUSTOM_DOCUMENT)

        feed.onDisplayed(42L)
        queued.runAll()

        // Merged publish first, refresh second: the refresh installs whichever
        // pointer the session already holds.
        assertEquals(listOf("merged", "overlay"), order)
        assertEquals(1, parses)
        assertSame(mergedPointer, session.readyReplacementFor(42L))
    }

    @Test
    fun `a successful completion asks for the post-overlay refresh once`() {
        val requested = mutableListOf<Long>()
        val feed = newFeed(onOverlayUpdated = { id -> requested += id }) { _, _ -> "merged" }
        feed.remember(42L, REVISION, CUSTOM_DOCUMENT)

        feed.onDisplayed(42L)
        feed.onDisplayed(42L)

        // Once per (track, revision), like the enricher pass itself: this is the
        // host's post-overlay refresh ask, not a per-presentation callback.
        assertEquals(listOf(42L), requested)
    }

    @Test
    fun `a completion that wrote no overlay never asks for a refresh`() {
        var requested = 0
        val feed = newFeed(onOverlayUpdated = { requested += 1 }) { _, _ -> null }
        feed.remember(42L, REVISION, CUSTOM_DOCUMENT)

        feed.onDisplayed(42L)

        // The enricher returned no merged document, so no overlay write happened
        // and there is nothing to re-present.
        assertEquals(0, requested)
    }

    @Test
    fun `a throwing refresh callback fails open`() {
        val lines = mutableListOf<String>()
        val feed = newFeed(
            log = { _, line -> lines += line },
            onOverlayUpdated = { error("host exploded") },
        ) { _, _ -> "merged" }
        feed.remember(42L, REVISION, CUSTOM_DOCUMENT)

        feed.onDisplayed(42L)

        // The completion still reports its inject line; the host callback is a
        // best-effort side channel that must never fail the enrichment.
        assertTrue(lines.any { it.contains("published=true") })
    }

    private fun newFeed(
        executor: Executor = Executor { command -> command.run() },
        log: (Long, String) -> Unit = { _, _ -> },
        onOverlayUpdated: (Long) -> Unit = {},
        onMergedDocument: (Long, String, String) -> Unit = { _, _, _ -> },
        enrich: (Long, String) -> String?,
    ): CustomLyricsCompletionFeed = CustomLyricsCompletionFeed(
        enrich = enrich,
        executor = executor,
        log = log,
        onOverlayUpdated = onOverlayUpdated,
        onMergedDocument = onMergedDocument,
    )

    private fun entry(id: Long, sha256: String = REVISION) = CustomLyricsEntry(
        appleMusicId = id,
        displayName = "Song",
        fileId = "lyrics_$id",
        sizeBytes = CUSTOM_DOCUMENT.toByteArray().size.toLong(),
        sha256 = sha256,
        source = CustomLyricsSources.MANUAL,
        enabled = true,
    )

    private data class Pointer(var adamId: Long = 0L)

    private class QueuedExecutor : Executor {
        private val commands = mutableListOf<Runnable>()

        override fun execute(command: Runnable) {
            commands += command
        }

        fun runAll() {
            while (commands.isNotEmpty()) commands.removeAt(0).run()
        }
    }

    private companion object {
        const val REVISION = "0cba697d61a21fb62408b2411aa2152d1bc24cc2414d2bd162f70e04d20c5e53"
        const val REVISION_B = "1111111111111111111111111111111111111111111111111111111111111111"

        /** A Word-timed custom document, the ordinary shape of an imported file. */
        const val CUSTOM_DOCUMENT =
            "<tt xmlns=\"http://www.w3.org/ns/ttml\" " +
                "xmlns:itunes=\"http://music.apple.com/lyric-ttml-internal\" " +
                "itunes:timing=\"Word\" xml:lang=\"ja\"><head></head>" +
                "<body dur=\"0:05.000\"><div begin=\"0.000\" end=\"5.000\">" +
                "<p begin=\"1.000\" end=\"2.000\">" +
                "<span begin=\"1.000\" end=\"1.500\">夜</span>" +
                "<span begin=\"1.500\" end=\"2.000\">に</span>" +
                "</p></div></body></tt>"

        const val CUSTOM_DOCUMENT_B =
            "<tt xmlns=\"http://www.w3.org/ns/ttml\" " +
                "xmlns:itunes=\"http://music.apple.com/lyric-ttml-internal\" " +
                "itunes:timing=\"Word\" xml:lang=\"ja\"><head></head>" +
                "<body dur=\"0:05.000\"><div begin=\"0.000\" end=\"5.000\">" +
                "<p begin=\"1.000\" end=\"2.000\">" +
                "<span begin=\"1.000\" end=\"2.000\">空</span>" +
                "</p></div></body></tt>"

        /**
         * What the enricher returns for [CUSTOM_DOCUMENT]: a lane added to the
         * head, the body byte-identical. Only this shape is allowed to become the
         * replacement pointer.
         */
        val MERGED_DOCUMENT: String = CUSTOM_DOCUMENT.replace(
            "<head></head>",
            "<head><translations><text xml:lang=\"zh\">译文</text></translations></head>",
        )

        const val SEARCHED_DOCUMENT =
            "<tt xmlns=\"http://www.w3.org/ns/ttml\" xml:lang=\"ja\"><body><div>" +
                "<p>a completely different searched song</p>" +
                "</div></body></tt>"
    }
}
