package dev.amenhancer.module.lyrics.source

import java.util.Locale
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Pins the one-line shapes a device log is grepped for. */
class LyricSourceDiagnosticsTest {

    @Test
    fun `the snapshot line names every lane and the overrides`() {
        val input = LyricSourceInput(
            song = LyricSongContext(songId = 42L, title = "夜\nに駆ける", artist = "YOASOBI"),
            apple = AppleLyricsCapability(hasLyrics = true, hasTranslation = true),
            onlineCandidates = listOf(candidate()),
        )
        val snapshot = LyricSourceCatalog.build(
            input,
            mapOf(LyricLane.TRANSLATION to LyricSourceKeys.online(LyricSourceProviders.QQ, "1001")),
            Locale.SIMPLIFIED_CHINESE,
        )

        val line = LyricSourceDiagnostics.snapshotLine(snapshot)

        assertTrue(line.startsWith(LyricSourceDiagnostics.PREFIX + " snapshot id=42"))
        assertFalse(line.contains("\n"))
        assertTrue(line.contains("title=\"夜 に駆ける\""))
        assertTrue(line.contains("original:apple"))
        assertTrue(line.contains("translation:online:qm:1001(overridden)"))
        assertTrue(line.contains("overrides=translation"))
    }

    @Test
    fun `the lane line lists the options and the apple availability`() {
        val input = LyricSourceInput(
            song = LyricSongContext(songId = 42L),
            apple = AppleLyricsCapability(hasLyrics = true, hasWords = true),
        )
        val state = LyricSourceCatalog.laneState(input, LyricLane.ORIGINAL, null, Locale.US)

        val line = LyricSourceDiagnostics.laneLine(42L, state)

        assertTrue(line.startsWith("lyrics-source lane id=42 lane=original"))
        assertTrue(line.contains("selected=apple kind=apple overridden=false apple=true"))
        assertTrue(line.contains("options=apple"))
        assertTrue(line.contains("labels=\"Apple Music (native)\""))
    }

    @Test
    fun `the apply line carries the error code`() {
        val line = LyricSourceDiagnostics.applyLine(
            songId = 42L,
            lane = LyricLane.TRANSLATION,
            option = null,
            result = ApplyResult.failed(LyricSourceErrorCodes.FETCH_FAILED, "拉取失败"),
        )

        assertTrue(line.startsWith("lyrics-source apply id=42 lane=translation option=none"))
        assertTrue(line.contains("ok=false"))
        assertTrue(line.contains("pending=false"))
        assertTrue(line.contains("code=fetch-failed"))
        assertTrue(line.contains("message=\"拉取失败\""))
    }

    @Test
    fun `the search line distinguishes success from failure`() {
        val ok = LyricSourceDiagnostics.searchLine(42L, LyricLane.ORIGINAL, "夜に駆ける", 7)
        assertTrue(ok.contains("candidates=7"))
        assertTrue(ok.contains("result=ok"))

        val failed = LyricSourceDiagnostics.searchLine(
            42L,
            LyricLane.ORIGINAL,
            "夜に駆ける",
            0,
            IllegalStateException("网络不可用"),
        )
        assertTrue(failed.contains("result=failed"))
        assertTrue(failed.contains("error=\"网络不可用\""))
    }

    @Test
    fun `the document failure line names the stage and the chinese reason`() {
        // The device regression: four different failures (no body, nothing but
        // credits, unreadable lanes, empty write) all collapsed into one
        // 「该来源无法预览」 and left no log line at all, so the report "every
        // source fails" could not be diagnosed from the export.
        val line = LyricSourceDiagnostics.documentFailedLine(
            action = "preview",
            stage = "filter",
            reason = "取回的歌词只有署名或版权信息",
        )

        assertEquals(
            "lyrics-source preview failed stage=filter reason=取回的歌词只有署名或版权信息",
            line,
        )
        // The four stages are the greppable vocabulary of the whole chain.
        listOf("fetch", "filter", "classify", "write").forEach { stage ->
            val apply = LyricSourceDiagnostics.documentFailedLine("apply", stage, "该来源没有这首歌词")
            assertTrue(apply.startsWith("lyrics-source apply failed stage=" + stage + " reason="))
        }
        // One line per failure, whatever the reason carries.
        val multi = LyricSourceDiagnostics.documentFailedLine("preview", "fetch", "first
second")
        assertFalse(multi.contains("
"))
    }

    @Test
    fun `the degraded line quotes the stored key`() {
        val line = LyricSourceDiagnostics.degradedLine(42L, LyricLane.TRANSLATION, "online:qm:999")

        assertEquals(
            "lyrics-source degraded id=42 lane=translation stored=\"online:qm:999\" reason=unresolved",
            line,
        )
    }

    @Test
    fun `the selection line lists every lane`() {
        val line = LyricSourceDiagnostics.selectionLine(
            42L,
            mapOf(LyricLane.TRANSLATION to LyricSourceKeys.HIDDEN),
        )

        assertEquals("lyrics-source selection id=42 original=- translation=none romanization=-", line)
    }

    @Test
    fun `a failed search line keeps the exception type and the throw site`() {
        // The device line this pins: the message *was* a class name, and the old
        // shape dropped the type and every frame, so a linkage error and a scored
        // candidate throw looked identical in the log.
        val error = NoClassDefFoundError("dev.amenhancer.module.lyrics.online.LyricMatchPolicy")

        val line = LyricSourceDiagnostics.searchLine(42L, LyricLane.ORIGINAL, "恋愛脳", 0, error)

        assertFalse(line.contains("\n"))
        assertTrue(line.contains("result=failed"))
        assertTrue(line.contains("type=java.lang.NoClassDefFoundError"))
        assertTrue(line.contains("frames="))
    }

    @Test
    fun `the failure trace names the type message and frames`() {
        val trace = LyricSourceDiagnostics.failureTrace(IllegalStateException("网络不可用"))

        assertTrue(trace.startsWith("type=java.lang.IllegalStateException"))
        assertTrue(trace.contains("message=网络不可用"))
        assertTrue(trace.contains("frames="))
        assertEquals("none", LyricSourceDiagnostics.failureTrace(null))
    }

    @Test
    fun `the failure trace stays on one line for a multi line message`() {
        val trace = LyricSourceDiagnostics.failureTrace(IllegalStateException("first\nsecond\r\nthird"))

        assertFalse(trace.contains("\n"))
        assertFalse(trace.contains("\r"))
        assertTrue(trace.contains("first second third"))
    }

    private fun candidate(): OnlineLyricsCandidate = OnlineLyricsCandidate(
        providerId = LyricSourceProviders.QQ,
        result = dev.amenhancer.module.lyrics.online.SongSearchResult(
            id = "1001",
            title = "夜に駆ける",
            artist = "YOASOBI",
            album = "THE BOOK",
            duration = 261_000L,
            source = dev.amenhancer.module.lyrics.online.Source.QM,
        ),
        hasWords = true,
        hasTranslation = true,
    )
}
