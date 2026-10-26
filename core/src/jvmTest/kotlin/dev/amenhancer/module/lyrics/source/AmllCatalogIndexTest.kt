package dev.amenhancer.module.lyrics.source

import dev.amenhancer.module.lyrics.online.Source
import java.util.Locale
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Pins the AMLL raw-lyrics catalog: the documented line shape parses, everything
 * the network can hand us that is not a usable line is skipped, and the ranking is
 * the shared match policy's score.
 */
class AmllCatalogIndexTest {

    @Test
    fun `a well formed line parses every field`() {
        val entry = AmllCatalogIndex.parseLine(line(artists = listOf(ARTIST, "Ayase")))!!

        assertEquals(FILE, entry.rawLyricFile)
        assertEquals(FILE, entry.candidateId)
        assertEquals(TITLE, entry.title)
        assertEquals(listOf(ARTIST, "Ayase"), entry.artists)
        assertEquals(ALBUM, entry.album)
        assertEquals(listOf("123456"), entry.ncmMusicIds)
        assertEquals(listOf(1440818669L), entry.appleMusicIds)

        val result = entry.toSearchResult()
        assertEquals(FILE, result.id)
        assertEquals(TITLE, result.title)
        assertEquals(ARTIST + " / Ayase", result.artist)
        assertEquals(ALBUM, result.album)
        assertEquals(0L, result.duration)
        assertEquals(Source.AMLL, result.source)
    }

    @Test
    fun `a bare string metadata value is accepted`() {
        val raw = JSONObject()
            .put("rawLyricFile", FILE)
            .put("metadata", JSONArray().put(JSONArray().put("musicName").put(TITLE)))

        val entry = AmllCatalogIndex.parseLine(raw.toString())!!

        assertEquals(TITLE, entry.title)
        assertTrue(entry.artists.isEmpty())
    }

    @Test
    fun `a line without a usable file name is dropped`() {
        val metadataOnly = JSONObject().put("metadata", metadata()).toString()
        listOf(
            metadataOnly,
            line(rawLyricFile = "   "),
            line(rawLyricFile = "raw-lyrics/x.ttml"),
            line(rawLyricFile = ".."),
            line(rawLyricFile = ".hidden.ttml"),
            line(rawLyricFile = "x".repeat(AmllCatalogIndex.MAX_LINE_BYTES)),
            line(rawLyricFile = "a\u0000b.ttml"),
        ).forEach { raw -> assertNull(raw, AmllCatalogIndex.parseLine(raw)) }
    }

    @Test
    fun `a line with neither title nor artist is dropped`() {
        val raw = JSONObject()
            .put("rawLyricFile", FILE)
            .put("metadata", JSONArray().put(pair("album", listOf(ALBUM))))
            .toString()

        assertNull(AmllCatalogIndex.parseLine(raw))
    }

    @Test
    fun `invalid json and blank lines are dropped`() {
        listOf("", "   ", "not json", "{unterminated", "[1,2,3]").forEach { raw ->
            assertNull(raw, AmllCatalogIndex.parseLine(raw))
        }
    }

    @Test
    fun `a missing or malformed metadata block is dropped`() {
        assertNull(AmllCatalogIndex.parseLine(JSONObject().put("rawLyricFile", FILE).toString()))
        assertNull(
            AmllCatalogIndex.parseLine(
                JSONObject().put("rawLyricFile", FILE).put("metadata", "nope").toString(),
            ),
        )
    }

    @Test
    fun `an oversized line is dropped while the catalog keeps the rest`() {
        val huge = line(title = "x".repeat(AmllCatalogIndex.MAX_LINE_CHARS))
        assertNull(AmllCatalogIndex.parseLine(huge))

        val index = AmllCatalogIndex.parse(huge + "\n" + line())

        assertEquals(1, index.size)
    }

    @Test
    fun `duplicate file names collapse into the first entry`() {
        val index = AmllCatalogIndex.parse(
            line(title = "First") + "\n" + line(title = "Second"),
        )

        assertEquals(1, index.size)
        assertEquals(listOf("First"), index.candidates("first").map { it.title })
    }

    @Test
    fun `the entry cap bounds what is kept`() {
        val text = (1..3).joinToString("\n") { index -> line(rawLyricFile = "f" + index + ".ttml") }

        assertEquals(2, AmllCatalogIndex.parse(text, maxEntries = 2).size)
        assertEquals(0, AmllCatalogIndex.parse(text, maxEntries = 0).size)
    }

    @Test
    fun `search ranks a title match above an artist-only match`() {
        val titleHit = line(rawLyricFile = "a.ttml")
        val artistHit = line(rawLyricFile = "b.ttml", title = "Another Song", artists = listOf(TITLE))
        val index = AmllCatalogIndex.parse(titleHit + "\n" + artistHit)

        val results = index.search(TITLE)

        assertEquals(listOf("a.ttml", "b.ttml"), results.map { it.id })
        val titleEntry = index.candidates(TITLE).first()
        val artistEntry = index.candidates(TITLE).last()
        assertTrue(index.score(TITLE, titleEntry) > index.score(TITLE, artistEntry))
    }

    @Test
    fun `the score is the shared match policy total`() {
        val index = AmllCatalogIndex.parse(line())
        val entry = index.candidates(TITLE).single()

        // title channel 50 + the neutral duration credit 15
        assertEquals(65, index.score(TITLE, entry))
        // artist channel 30 + 15
        assertEquals(45, index.score(ARTIST, entry))
        // both channels
        assertEquals(95, index.score(TITLE + " " + ARTIST, entry))
        assertTrue(index.score("nothing alike", entry) in 0..100)
    }

    @Test
    fun `search respects the limit and refuses an empty query`() {
        val text = (1..5).joinToString("\n") { index -> line(rawLyricFile = "f" + index + ".ttml") }
        val index = AmllCatalogIndex.parse(text)

        assertEquals(5, index.search(TITLE, 10).size)
        assertEquals(2, index.search(TITLE, 2).size)
        assertTrue(index.search(TITLE, 0).isEmpty())
        assertTrue(index.search("").isEmpty())
        assertTrue(index.search("   ").isEmpty())
    }

    @Test
    fun `a multi token query requires every token`() {
        val index = AmllCatalogIndex.parse(line())

        assertEquals(1, index.search(TITLE + " " + ARTIST).size)
        assertTrue(index.search(TITLE + " missingword").isEmpty())
    }

    @Test
    fun `invisible characters do not change a match`() {
        val withZeroWidthSpace = TITLE.substring(0, 2) + "\u200B" + TITLE.substring(2)
        val index = AmllCatalogIndex.parse(line(title = withZeroWidthSpace))

        assertEquals(1, index.search(TITLE).size)
        assertTrue(index.isEmpty.not())
        assertEquals(1, index.size)
    }

    @Test
    fun `an AMLL hit becomes a catalog option under the AMLL provider`() {
        val hit = AmllCatalogIndex.parse(line()).search(TITLE).single()
        val input = LyricSourceInput(
            song = LyricSongContext(
                songId = 42L,
                title = TITLE,
                artist = ARTIST,
                album = ALBUM,
                durationMs = 261_000L,
            ),
            onlineCandidates = listOf(
                OnlineLyricsCandidate(
                    providerId = LyricSourceProviders.AMLL,
                    result = hit,
                    hasWords = true,
                ),
            ),
        )

        val option = LyricSourceCatalog
            .onlineOptions(input, LyricLane.ORIGINAL, ZH)
            .single()

        assertEquals(LyricSourceProviders.AMLL, option.providerId)
        assertEquals(hit.title, option.label)
        assertEquals("YOASOBI · THE BOOK", option.detail)
        assertEquals(100, option.matchScore)
        assertEquals(LyricSourceKeys.online(LyricSourceProviders.AMLL, FILE), option.key)
        assertTrue(option.hasWords)
    }

    @Test
    fun `metadata keys are matched case-insensitively`() {
        val raw = JSONObject()
            .put("rawLyricFile", FILE)
            .put(
                "metadata",
                JSONArray()
                    .put(pair("MusicName", listOf(TITLE)))
                    .put(pair("ARTISTS", listOf(ARTIST)))
                    .put(pair("AppleMusicId", listOf("1440818669"))),
            )
            .toString()

        val entry = AmllCatalogIndex.parseLine(raw)!!

        assertEquals(TITLE, entry.title)
        assertEquals(listOf(ARTIST), entry.artists)
        assertEquals(listOf(1440818669L), entry.appleMusicIds)
    }

    private fun line(
        rawLyricFile: String = FILE,
        title: String = TITLE,
        artists: List<String> = listOf(ARTIST),
        album: String = ALBUM,
        ncmId: String = "123456",
        appleId: String = "1440818669",
    ): String = JSONObject()
        .put("rawLyricFile", rawLyricFile)
        .put(
            "metadata",
            metadata(
                title = title,
                artists = artists,
                album = album,
                ncmId = ncmId,
                appleId = appleId,
            ),
        )
        .toString()

    private fun metadata(
        title: String? = TITLE,
        artists: List<String> = listOf(ARTIST),
        album: String? = ALBUM,
        ncmId: String? = "123456",
        appleId: String? = "1440818669",
    ): JSONArray {
        val metadata = JSONArray()
        title?.let { metadata.put(pair("musicName", listOf(it))) }
        if (artists.isNotEmpty()) metadata.put(pair("artists", artists))
        album?.let { metadata.put(pair("album", listOf(it))) }
        ncmId?.let { metadata.put(pair("ncmMusicId", listOf(it))) }
        appleId?.let { metadata.put(pair("appleMusicId", listOf(it))) }
        return metadata
    }

    private fun pair(key: String, values: List<String>): JSONArray =
        JSONArray().put(key).put(JSONArray().apply { values.forEach { put(it) } })

    private companion object {
        const val FILE = "1768754400682-250306205-r6IrpmBd.ttml"
        const val TITLE = "夜に駆ける"
        const val ARTIST = "YOASOBI"
        const val ALBUM = "THE BOOK"
        val ZH: Locale = Locale.SIMPLIFIED_CHINESE
    }
}
