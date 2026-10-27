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
