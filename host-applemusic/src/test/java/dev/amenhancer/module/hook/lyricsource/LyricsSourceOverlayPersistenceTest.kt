package dev.amenhancer.module.hook.lyricsource

import dev.amenhancer.module.lyrics.online.NativeLyricOverlayStore
import dev.amenhancer.module.lyrics.source.LyricLane
import dev.amenhancer.module.lyrics.source.LyricSourceCatalog
import dev.amenhancer.module.lyrics.source.LyricSourceInput
import dev.amenhancer.module.lyrics.source.LyricSourceKeys
import dev.amenhancer.module.lyrics.source.LyricSourceProviders
import dev.amenhancer.module.lyrics.source.LyricSongContext
import dev.amenhancer.module.model.CustomLyricsSources
import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The one truth behind the lyrics-source panel: [LyricsSourceOverlayPersistence].
 *
 * The device report this covers: 「补全歌词翻译与发音」 finished, the panel's
 * 「翻译」 still said 「不显示」, and 「显示发音」 turned permanently grey a while
 * after the lane had been published. Both were the same shape — the lane and its
 * provider lived only in the process-memory overlay, so the first refresh, track
 * change or restart that replaced that overlay took them with it.
 *
 * These tests run the real record over a real file (the store itself is covered by
 * [NativeLyricOverlayStoreTest]) so the three properties the panel depends on are
 * executed, not asserted from source text:
 *
 *  1. every publish path writes the provider of each lane into the same record;
 *  2. a later publish that does not know a provider, or does not carry a lane,
 *     never erases what is already stored;
 *  3. a fresh record over the same file — a restart — still answers with the
 *     provider, and the catalog still selects that lane instead of 「不显示」.
 */
class LyricsSourceOverlayPersistenceTest {

    private val songId = 42L

    private val ttml = "<tt xmlns=\"http://www.w3.org/ns/ttml\" " +
        "xmlns:ttm=\"http://www.w3.org/ns/ttml#metadata\" " +
        "xmlns:itunes=\"http://music.apple.com/lyric-ttml-internal\" " +
        "itunes:timing=\"Word\" xml:lang=\"ja\"><head><metadata>" +
        "<iTunesMetadata xmlns=\"http://music.apple.com/lyric-ttml-internal\">" +
        "<translations><translation type=\"subtitle\" xml:lang=\"zh-Hans\">" +
        "<text for=\"L1\">你的名字</text></translation></translations>" +
        "<transliterations><transliteration xml:lang=\"ja-Latn\">" +
        "<text for=\"L1\"><span begin=\"1.000\" end=\"3.000\">Kimi no na wa</span></text>" +
        "</transliteration></transliterations></iTunesMetadata>" +
        "</metadata></head><body><div>" +
        "<p begin=\"1.000\" end=\"3.000\" itunes:key=\"L1\">" +
        "<span begin=\"1.000\" end=\"2.000\">君</span>" +
        "<span begin=\"2.000\" end=\"3.000\">の名は</span></p>" +
        "</div></body></tt>"

    /** Apple's own body for the same song: no translation, no transliteration track. */
    private val plainTtml = "<tt xmlns=\"http://www.w3.org/ns/ttml\" " +
        "xmlns:ttm=\"http://www.w3.org/ns/ttml#metadata\" xml:lang=\"ja\"><body><div>" +
        "<p begin=\"1.000\" end=\"3.000\">" +
        "<span begin=\"1.000\" end=\"2.000\">君</span>" +
        "<span begin=\"2.000\" end=\"3.000\">の名は</span></p>" +
        "</div></body></tt>"

    private fun directory(): File =
        File(System.getProperty("java.io.tmpdir"), "ampp-overlay-" + System.nanoTime())
            .also { it.mkdirs() }

    private fun persistenceOf(folder: File): LyricsSourceOverlayPersistence =
        LyricsSourceOverlayPersistence(File(folder, FILE_NAME))

    /** What the automatic completion writes through the host's cache bridge. */
    private fun recordCompletion(persistence: LyricsSourceOverlayPersistence): Boolean =
        persistence.saveMergedDocument(
            songId = songId,
            mergedTtml = ttml,
            translationSource = CustomLyricsSources.QQ,
            pronunciationSource = CustomLyricsSources.QQ,
            originalSource = CustomLyricsSources.QQ,
            pronunciationLanguages = listOf(JA_LATN),
        )

    @Test
    fun `a completion records the provider of every lane it published`() {
        val persistence = persistenceOf(directory())

        assertTrue(recordCompletion(persistence))

        val sources = requireNotNull(persistence.sources(songId)) { "the record must exist" }
        assertEquals(CustomLyricsSources.QQ, sources.translationSource)
        assertEquals(CustomLyricsSources.QQ, sources.pronunciationSource)
        assertEquals(CustomLyricsSources.QQ, sources.originalSource)
        assertEquals(listOf(JA_LATN), sources.pronunciationLanguages)
        assertTrue(sources.hasTranslations)
        assertTrue(sources.hasRomanizations)
    }

    @Test
    fun `a restart still answers with the recorded provider`() {
        val folder = directory()
        assertTrue(recordCompletion(persistenceOf(folder)))

        // A restart: a new record over the same file, and an empty overlay store
        // (the store is process memory and holds one song at a time).
        val restarted = persistenceOf(folder)
        val overlay = NativeLyricOverlayStore()
        assertEquals(CustomLyricsSources.QQ, restarted.sources(songId)?.translationSource)
        assertTrue("the stored lanes must replay into the empty store", restarted.restoreInto(songId, overlay))

        val key = songId.toString()
        assertTrue(overlay.hasTranslation(key))
        assertEquals(CustomLyricsSources.QQ, overlay.translationSource(key))
        assertTrue(overlay.hasPronunciation(key))
        assertEquals(listOf(JA_LATN), overlay.pronunciationLanguages(key))
    }

    @Test
    fun `a later save that knows no provider never erases the recorded one`() {
        val folder = directory()
        val persistence = persistenceOf(folder)
        assertTrue(recordCompletion(persistence))

        // The displayed-document publish and the enrichment's own re-save both pass
        // null for a lane they cannot attribute. "Unknown" must never mean "forget".
        assertTrue(
            persistence.saveMergedDocument(
                songId = songId,
                mergedTtml = ttml,
                translationSource = null,
                pronunciationSource = null,
                originalSource = null,
                pronunciationLanguages = emptyList(),
            ),
        )

        val sources = requireNotNull(persistence.sources(songId))
        assertEquals(CustomLyricsSources.QQ, sources.translationSource)
        assertEquals(CustomLyricsSources.QQ, sources.pronunciationSource)
        assertEquals(CustomLyricsSources.QQ, sources.originalSource)
        // A body without a `<transliteration xml:lang>` tag must not erase the tag the
        // app's pronunciation toggle keys the published lane by.
        assertEquals(listOf(JA_LATN), sources.pronunciationLanguages)
    }

    @Test
    fun `a body that carries one lane keeps the other lane's content`() {
        val folder = directory()
        val persistence = persistenceOf(folder)
        assertTrue(recordCompletion(persistence))

        // The displayed custom document for the same song carries no translation at
        // all (it is Apple's body plus the module's own lanes elsewhere): the stored
        // translation lane and its provider have to survive the republish.
        assertTrue(persistence.saveDisplayedDocument(songId, plainTtml, CustomLyricsSources.AMLL, emptyList()))

        val sources = requireNotNull(persistence.sources(songId))
        assertTrue("the completed translation lane must survive", sources.hasTranslations)
        assertTrue("the completed romanization lane must survive", sources.hasRomanizations)
        assertEquals("a completion's provider wins over the file's origin", CustomLyricsSources.QQ, sources.translationSource)
    }

    @Test
    fun `the displayed document only fills in a provider it does not know`() {
        val folder = directory()
        val persistence = persistenceOf(folder)
        // A custom document with no completion behind it: its own origin is the only
        // provider there is, so it is recorded for the lanes the document carries.
        assertTrue(persistence.saveDisplayedDocument(songId, ttml, CustomLyricsSources.AMLL, listOf(JA_LATN)))

        val sources = requireNotNull(persistence.sources(songId))
        assertEquals(CustomLyricsSources.AMLL, sources.translationSource)
        assertEquals(CustomLyricsSources.AMLL, sources.pronunciationSource)
        assertEquals(CustomLyricsSources.AMLL, sources.originalSource)
        assertEquals(listOf(JA_LATN), sources.pronunciationLanguages)
    }

    @Test
    fun `dropping a lane forgets its provider and keeps the other lane`() {
        val persistence = persistenceOf(directory())
        assertTrue(recordCompletion(persistence))

        assertTrue(persistence.dropLane(songId, NativeLyricOverlayStore.Lane.PRONUNCIATION))

        val sources = requireNotNull(persistence.sources(songId))
        assertFalse(sources.hasRomanizations)
        assertNull(sources.pronunciationSource)
        assertEquals(emptyList<String>(), sources.pronunciationLanguages)
        assertTrue("the translation lane must survive a pronunciation drop", sources.hasTranslations)
        assertEquals(CustomLyricsSources.QQ, sources.translationSource)
    }

    @Test
    fun `the panel selects the persisted lane with its provider when the store is empty`() {
        val folder = directory()
        assertTrue(recordCompletion(persistenceOf(folder)))
        val persisted = persistenceOf(folder).sources(songId)

        // No live store at all: exactly the state a restart or a replaced overlay
        // leaves the probe in.
        val input = LyricSourceInput(
            song = LyricSongContext(
                songId = songId,
                title = "明日の私に幸あれ",
                artist = "NANAOAKARI",
                durationMs = 216_000L,
            ),
            overlay = overlayCapabilityOf(
                snapshot = OverlayLaneSnapshot(holdsSong = false),
                persisted = persisted,
                hasTiming = true,
            ),
        )
        val snapshot = LyricSourceCatalog.build(input)

        val translation = requireNotNull(snapshot.lane(LyricLane.TRANSLATION))
        assertEquals(LyricSourceKeys.overlay(LyricLane.TRANSLATION), translation.selected?.key)
        assertEquals(LyricSourceProviders.QQ, translation.selected?.providerId)
        // The lane is the content the module already published, so it is not
        // reported as a user override.
        assertFalse(translation.overridden)
        // The romanization lane is reported the same way instead of 「不显示」.
        val romanization = requireNotNull(snapshot.lane(LyricLane.ROMANIZATION))
        assertEquals(LyricSourceKeys.overlay(LyricLane.ROMANIZATION), romanization.selected?.key)
        assertEquals(LyricSourceProviders.QQ, romanization.selected?.providerId)
    }

    @Test
    fun `a stored provider is never reported for a lane that holds nothing`() {
        val folder = directory()
        val persistence = persistenceOf(folder)
        assertTrue(recordCompletion(persistence))
        assertTrue(persistence.dropLane(songId, NativeLyricOverlayStore.Lane.PRONUNCIATION))

        val capability = overlayCapabilityOf(
            snapshot = OverlayLaneSnapshot(holdsSong = false),
            persisted = persistence.sources(songId),
            hasTiming = true,
        )

        assertTrue(capability.hasTranslation)
        assertEquals(CustomLyricsSources.QQ, capability.translationSource)
        assertFalse(capability.hasRomanization)
        assertNull(
            "a provider without a lane would claim content the page is not showing",
            capability.romanizationSource,
        )
    }

    private companion object {
        const val FILE_NAME = "overlay.json"

        const val JA_LATN = "ja-Latn"
    }
}
