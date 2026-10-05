package dev.amenhancer.module.lyrics.online

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The score diagnostics are the device log's contract: a query line carrying the
 * local identity actually scored, and one candidate line carrying the
 * per-component breakdown whose total is the pass/fail decision. Both are pure,
 * so their exact shape is pinned here instead of by reading a device log.
 */
class OnlineMatchDiagnosticsTest {

    @Test
    fun `the query line carries the keyword and the local identity used`() {
        val line = OnlineMatchDiagnostics.queryLine(
            appleMusicId = 42L,
            keyword = "Song Artist",
            localTitle = "Song",
            localArtist = "Artist",
            localAlbum = "Album",
            localDurationMs = 215_000L,
        )

        assertEquals(
            "online-translation query id=42 keyword=\"Song Artist\" " +
                "localTitle=\"Song\" localArtist=\"Artist\" " +
                "localAlbum=\"Album\" localDurationMs=215000",
            line,
        )
    }

    @Test
    fun `a query line with no resolved album still reports an empty local album`() {
        val line = OnlineMatchDiagnostics.queryLine(
            appleMusicId = 7L,
            keyword = "Song",
            localTitle = "Song",
            localArtist = "",
            localAlbum = "",
            localDurationMs = 0L,
        )

        assertTrue(line.contains("localAlbum=\"\""))
        assertTrue(line.contains("localDurationMs=0"))
    }

    @Test
    fun `the score line spells out the identity and every component`() {
        val breakdown = ScoreBreakdown(title = 50, artist = 30, album = 10, duration = 15, features = 0)

        val line = OnlineMatchDiagnostics.scoreLine(
            appleMusicId = 42L,
            sourceId = "qq",
            rank = 0,
            song = SongSearchResult(
                id = "1638067562",
                title = "Song",
                artist = "Artist",
                album = "Album",
                duration = 215_000L,
                source = Source.QM,
            ),
            breakdown = breakdown,
        )

        assertEquals(
            "online-translation score id=42 source=qq rank=0 " +
                "title=\"Song\" artist=\"Artist\" candidateDurationMs=215000 " +
                "title=50 artist=30 album=10 duration=15 features=0 total=105",
            line,
        )
    }

    @Test
    fun `the score line total is the sum of the components`() {
        val breakdown = ScoreBreakdown(title = 50, artist = 0, album = 0, duration = 15, features = 0)

        val line = OnlineMatchDiagnostics.scoreLine(
            appleMusicId = 1L,
            sourceId = "kuwo",
            rank = 2,
            song = SongSearchResult(
                id = "1",
                title = "Song",
                artist = "Nobody",
                album = "",
                duration = 0L,
                source = Source.KUWO,
            ),
            breakdown = breakdown,
        )

        assertEquals(65, breakdown.total)
        assertTrue(line.endsWith("total=65"))
    }
}
