package dev.amenhancer.module.lyrics.online

import dev.amenhancer.module.hook.PronunciationBoundaryRuntime
import dev.amenhancer.module.hook.PronunciationBoundarySession
import dev.amenhancer.module.lyrics.FilePronunciationBoundaryCache
import dev.amenhancer.module.lyrics.LlmLyricsConfig
import java.nio.file.Files
import java.util.concurrent.Executor
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The chain that carries a model answer from a document to the store: the request builder, the disk
 * cache, the store and the session's budget. Everything runs on a direct executor and an in-memory
 * cache, so no thread, no file and no network is involved.
 */
class PronunciationBoundaryPlanChainTest {

    private val config = LlmLyricsConfig(baseUrl = "https://example.test/v1", model = "test-model")

    // ---- request builder ------------------------------------------------------------------

    @Test
    fun aDocumentBecomesOneRequestLinePerLyricLine() {
        val request = PronunciationBoundaryRequests.fromTtml("77", document(), title = "t")

        assertNotNull(request)
        val lines = request!!.lines
        println(
            "[bndry-builder] lines=" + lines.size +
                " tokens=" + lines.map { it.tokens.size } +
                " keys=" + lines.map { it.lineKey } +
                " readings=" + lines.map { line -> line.tokens.map { it.reading } } +
                " kana=" + lines.map { line -> line.tokens.map { it.kanaCount } },
        )
        assertEquals(2, lines.size)
        assertEquals(listOf("赤", "い"), lines[0].tokens.map { it.kana })
        assertEquals(listOf(1, 1), lines[0].tokens.map { it.kanaCount })
        assertEquals(listOf("aka", "i"), lines[0].tokens.map { it.reading })
        assertEquals("赤い", lines[0].lineKey)
        assertEquals("を", lines[0].nextLine)
        assertEquals(2, PronunciationBoundaryRequests.tokenCounts(request).size)
        assertEquals(64, PronunciationBoundaryRequests.kanaHash(request).length)
    }

    @Test
    fun aReadingLessKanaStillCountsAsAKana() {
        val request = PronunciationBoundaryRequests.fromTtml(
            "77",
            document(lines = 1, spans = listOf("ス", "テ", "ー", "ジ")),
        )!!

        println(
            "[bndry-builder] one line tokens=" + request.lines.single().tokens.map { it.kana } +
                " kana=" + request.lines.single().tokens.map { it.kanaCount },
        )
        assertEquals(listOf(1, 1, 1, 1), request.lines.single().tokens.map { it.kanaCount })
        assertEquals(
            listOf(false, false, false, false),
            request.lines.single().tokens.map { it.isMarkOnly },
        )
    }

    // ---- store ----------------------------------------------------------------------------

    @Test
    fun theStoreAnswersOnlyForTheSongItHolds() {
        val store = PronunciationBoundaryStore()
        assertNull(store.planFor("赤い"))

        assertTrue(store.install("77", listOf(PronunciationBoundary.BoundaryLine("赤い", listOf(0..1)))))

        assertEquals("77", store.currentSongId())
        assertEquals(listOf(0..1), store.planFor("赤い")!!.groups)
        assertEquals(1L, store.revision())

        store.clear()
        assertNull(store.planFor("赤い"))
        assertEquals(2L, store.revision())
    }

    // ---- cache ----------------------------------------------------------------------------

    @Test
    fun aCachedAnswerIsReusedOnlyWhileEverythingMatches() {
        val directory = Files.createTempDirectory("boundary-cache").toFile()
        try {
            val cache = FilePronunciationBoundaryCache(directory)
            val counts = listOf(2, 1)
            val lines = listOf(
                PronunciationBoundary.BoundaryLine("赤い", listOf(0..1)),
                PronunciationBoundary.BoundaryLine("を", listOf(0..0)),
            )

            assertNull(cache.read(77L, hash(), counts, config))
            assertTrue(cache.write(77L, hash(), counts, config, lines))
            assertEquals(lines, cache.read(77L, hash(), counts, config))

            // a different token count means a different line, even under the same kana hash
            assertNull(cache.read(77L, hash(), listOf(2, 2), config))
            assertTrue(cache.write(77L, hash(), counts, config, lines))

            // a different model is a different answer
            assertNull(cache.read(77L, hash(), counts, config.copy(model = "other-model")))
            assertTrue(cache.write(77L, hash(), counts, config, lines))

            // a different kana hash is a different song
            assertNull(cache.read(77L, hash(seed = "x"), counts, config))
        } finally {
            directory.deleteRecursively()
        }
    }

    // ---- session --------------------------------------------------------------------------

    @Test
    fun anAcceptedAnswerIsInstalledOnceAndRepaintsOnce() {
        val harness = Harness(answer())

        assertTrue(harness.session.onDocument(77L, document(), title = "t"))

        assertEquals(1, harness.requests)
        assertEquals(1, harness.installs)
        assertEquals(1, harness.writes)
        assertEquals(listOf(0..1), harness.store.planFor("赤い")!!.groups)
    }

    @Test
    fun aRejectedAnswerInstallsNothingAndWritesNothing() {
        // One line where the document has two: the whole answer is refused, never partly used.
        val harness = Harness("{\"lines\":[{\"i\":0,\"g\":[[0,1]]}]}")

        assertTrue(harness.session.onDocument(77L, document()))

        assertEquals(1, harness.requests)
        assertEquals(0, harness.installs)
        assertEquals(0, harness.writes)
        assertNull(harness.store.planFor("赤い"))
    }

    @Test
    fun theSameDocumentIsAttemptedOnce() {
        val harness = Harness(answer())

        assertTrue(harness.session.onDocument(77L, document()))
        harness.session.onDocument(77L, document())

        assertEquals(1, harness.requests)
    }

    @Test
    fun theSessionStopsAfterItsCallBudget() {
        val harness = Harness(answer(), maxCalls = 2)

        harness.session.onDocument(77L, document(lines = 1))
        harness.session.onDocument(77L, document(lines = 2))
        harness.session.onDocument(77L, document(lines = 3))

        assertEquals(2, harness.session.callCount())
        assertEquals(2, harness.requests)
    }

    @Test
    fun aCacheHitCostsNoRequest() {
        val harness = Harness(answer())
        harness.cached = listOf(
            PronunciationBoundary.BoundaryLine("赤い", listOf(0..1)),
            PronunciationBoundary.BoundaryLine("を", listOf(0..0)),
        )

        harness.session.onDocument(77L, document())

        assertEquals(0, harness.requests)
        assertEquals(1, harness.installs)
        assertEquals(listOf(0..1), harness.store.planFor("赤い")!!.groups)
    }

    @Test
    fun everyDocumentLogsOneEntryLine() {
        val harness = Harness(answer())

        harness.session.onDocument(77L, document())

        println("[bndry-log] " + harness.logs)
        val entry = harness.logs.firstOrNull { it.startsWith("bndry doc ") }
        assertEquals("bndry doc song=77 lines=2 enabled=true", entry)
    }

    @Test
    fun aDisabledChainLogsTheEntryLineAndOneSkip() {
        val harness = Harness(answer(), configured = false)

        assertTrue(!harness.session.onDocument(77L, document()))

        println("[bndry-log] " + harness.logs)
        assertEquals("bndry doc song=77 lines=2 enabled=false", harness.logs.first())
        assertEquals("bndry skip reason=noconfig", harness.logs.last())
        assertEquals(0, harness.requests)
    }

    @Test
    fun aRejectedAnswerLogsItsReasonAndLine() {
        val harness = Harness("{\"lines\":[{\"i\":0,\"g\":[[0,1]]}]}")

        harness.session.onDocument(77L, document())

        println("[bndry-log] " + harness.logs)
        val reject = harness.logs.firstOrNull { it.startsWith("bndry reject ") }
        assertNotNull(reject)
        assertTrue(reject!!.contains("reason=line-count"))
        assertTrue(reject.contains("line=0"))
    }

    @Test
    fun theAppRegistersTheChainAndNeverPointsTheSlotAtTheTrigger() {
        // Two stable facts only: the facade's public entry points are called, and the slot is not
        // wired back into triggerBoundary — that self-reference was a stack overflow on device, so
        // it is worth one regression assertion even though the rest of the wiring is no longer
        // pinned by source text.
        val app = sequenceOf(
            java.io.File("app/src/main/java/dev/amenhancer/module/hook/AutoLyricsReplacementSession.kt"),
            java.io.File("../app/src/main/java/dev/amenhancer/module/hook/AutoLyricsReplacementSession.kt"),
        ).firstOrNull { it.isFile }?.readText()
        assertNotNull(app)
        println("[bndry-wiring] appLines=" + app!!.lines().size)
        assertTrue(app.contains("setBoundaryPlans("))
        assertTrue(app.contains("setDocumentObserver("))
        assertFalse(app.contains("triggerBoundary(\"llm\""))
    }



    // ---- fixtures -------------------------------------------------------------------------

    private inner class Harness(answer: String, maxCalls: Int = 20, configured: Boolean = true) {
        val store = PronunciationBoundaryStore()
        val logs = ArrayList<String>()
        var cached: List<PronunciationBoundary.BoundaryLine>? = null
        var requests = 0
        var installs = 0
        var writes = 0
        val session = PronunciationBoundarySession(
            runtime = PronunciationBoundaryRuntime(
                configuration = { if (configured) config else null },
                cacheRead = { _, _, _, _ -> cached },
                cacheWrite = { _, _, _, _, lines ->
                    writes++
                    cached = lines
                },
                request = { _, _, _ ->
                    requests++
                    answer
                },
                executor = Executor { task -> task.run() },
                onInstalled = { installs++ },
                log = { line -> logs += line },
            ),
            store = store,
            maxCallsPerSession = maxCalls,
            retryDelayMs = 0L,
        )
    }

    private fun answer(): String = "{\"lines\":[{\"i\":0,\"g\":[[0,1]]},{\"i\":1,\"g\":[[0]]}]}"

    private fun hash(seed: String = "a"): String = seed.repeat(64).take(64)

    /**
     * A document shaped like the ones the reader actually parses: one timed `<p itunes:key>` per
     * line, one timed `<span>` per token, and a head transliteration lane whose `<text for>`
     * entry carries **timed** spans too — an untimed lane yields no readings, which is what the
     * first version of this fixture got wrong.
     */
    private fun document(
        lines: Int = 2,
        spans: List<String> = listOf("赤", "い"),
        second: String = "を",
    ): String {
        val body = StringBuilder()
        for (index in 0 until lines) {
            val glyphs = if (index == 0) spans else listOf(second)
            val lineBegin = index * 2_000
            body.append("<p begin=\"").append(clock(lineBegin)).append("\" end=\"")
                .append(clock(lineBegin + 2_000)).append("\" itunes:key=\"L").append(index + 1)
                .append("\">")
            glyphs.forEachIndexed { position, glyph ->
                body.append("<span begin=\"").append(clock(lineBegin + position * 500))
                    .append("\" end=\"").append(clock(lineBegin + (position + 1) * 500))
                    .append("\">").append(glyph).append("</span>")
            }
            body.append("</p>")
        }
        return "<tt xmlns=\"http://www.w3.org/ns/ttml\" " +
            "xmlns:itunes=\"http://music.apple.com/lyric-ttml-internal\" itunes:timing=\"Word\">" +
            "<head><metadata><iTunesMetadata xmlns=\"http://music.apple.com/lyric-ttml-internal\">" +
            transliterations(lines) +
            "</iTunesMetadata></metadata></head>" +
            "<body><div>" + body + "</div></body></tt>"
    }

    private fun transliterations(lines: Int): String {
        val entries = StringBuilder("<transliterations><transliteration xml:lang=\"ja-Latn\">")
        for (index in 0 until lines) {
            val readings = if (index == 0) listOf("aka", "i") else listOf("o")
            val lineBegin = index * 2_000
            entries.append("<text for=\"L").append(index + 1).append("\">")
            readings.forEachIndexed { position, reading ->
                entries.append("<span begin=\"").append(clock(lineBegin + position * 500))
                    .append("\" end=\"").append(clock(lineBegin + (position + 1) * 500))
                    .append("\">").append(reading).append("</span>")
            }
            entries.append("</text>")
        }
        entries.append("</transliteration></transliterations>")
        return entries.toString()
    }

    /** A TTML clock with three millisecond digits, the form the reader parses. */
    private fun clock(millis: Int): String =
        (millis / 1000).toString() + "." + (millis % 1000).toString().padStart(3, '0')
}
