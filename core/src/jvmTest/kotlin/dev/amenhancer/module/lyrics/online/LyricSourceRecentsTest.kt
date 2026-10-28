package dev.amenhancer.module.lyrics.online

import dev.amenhancer.module.lyrics.source.OnlineLyricsCandidate
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class LyricSourceRecentsTest {

    private fun entry(provider: String, remote: String, title: String, artist: String, usedAt: Long) =
        LyricRecentSource(
            providerId = provider,
            label = provider,
            title = title,
            artist = artist,
            album = "",
            remoteId = remote,
            usedAtMs = usedAt,
        )

    private fun candidate(provider: String, title: String, artist: String) = OnlineLyricsCandidate(
        providerId = provider,
        result = SongSearchResult(
            id = title,
            title = title,
            artist = artist,
            album = "",
            duration = 0L,
            source = Source.QM,
        ),
    )

    @Test
    fun `recording moves a source to the front and keeps it unique`() {
        val first = entry("qm", "1", "晴天", "周杰伦", 10L)
        val second = entry("ne", "2", "晴天", "周杰伦", 20L)
        val history = LyricSourceRecents.record(LyricSourceRecents.record(emptyList(), first), second)
        assertEquals(listOf("ne", "qm"), history.map { it.providerId })
        val again = LyricSourceRecents.record(history, first.copy(usedAtMs = 30L))
        assertEquals(listOf("qm", "ne"), again.map { it.providerId })
        assertEquals(2, again.size)
    }

    @Test
    fun `quick picks are distinct providers in use order`() {
        val history = listOf(
            entry("amll", "a", "x", "y", 50L),
            entry("amll", "b", "z", "w", 40L),
            entry("qm", "c", "x", "y", 30L),
        )
        assertEquals(listOf("amll", "qm"), LyricSourceRecents.quickPickProviders(history))
    }

    @Test
    fun `pinning prefers the song history then the chosen provider`() {
        val history = listOf(
            entry("ne", "1", "晴天", "周杰伦", 100L),
            entry("qm", "2", "别的歌", "别人", 200L),
        )
        val rows = listOf(
            candidate("kugou", "晴天", "周杰伦"),
            candidate("qm", "晴天", "周杰伦"),
            candidate("ne", "晴天", "周杰伦"),
        )
        val pinned = LyricSourceRecents.pin(history, rows, "晴天", "周杰伦")
        assertEquals("ne", pinned.first().providerId)
        val withChoice = LyricSourceRecents.pin(history, rows, "", "", preferredProvider = "qm")
        assertEquals("qm", withChoice.first().providerId)
    }

    @Test
    fun `duplicates across providers collapse but different songs stay`() {
        val rows = listOf(
            candidate("qm", "晴天", "周杰伦"),
            candidate("ne", "晴天", "周杰伦"),
            candidate("qm", "稻香", "周杰伦"),
            candidate("kugou", "", ""),
        )
        val deduped = LyricSourceRecents.dedupe(rows)
        assertEquals(3, deduped.size)
        assertTrue(deduped.any { it.providerId == "qm" && it.result.title == "稻香" })
    }
}