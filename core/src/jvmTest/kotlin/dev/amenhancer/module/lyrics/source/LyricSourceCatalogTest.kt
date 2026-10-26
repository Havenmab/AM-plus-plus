package dev.amenhancer.module.lyrics.source

import dev.amenhancer.module.lyrics.online.Source
import dev.amenhancer.module.lyrics.online.SongSearchResult
import dev.amenhancer.module.model.CustomLyricsSources
import java.io.File
import java.util.Locale
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/**
 * Pins the catalog's two rules: which options a lane lists (only sources that can
 * serve it) and which one is the lane's default (the source already in effect,
 * then Apple, never an un-applied online candidate).
 */
class LyricSourceCatalogTest {

    @get:Rule
    val temp = TemporaryFolder()

    @Test
    fun `the original lane lists every source family in display order`() {
        val input = input(
            apple = AppleLyricsCapability(
                hasLyrics = true,
                hasWords = true,
                hasTranslation = true,
            ),
            overlay = OverlayLyricsCapability(
                hasOriginal = true,
                hasWords = true,
                originalSource = CustomLyricsSources.KUWO,
            ),
            customFiles = listOf(fileRecord()),
            onlineCandidates = listOf(candidate()),
            activeFileId = null,
        )

        val original = LyricSourceCatalog.options(input, LyricLane.ORIGINAL, ZH)

        assertEquals(
            listOf(
                LyricSourceKeys.APPLE,
                LyricSourceKeys.overlay(LyricLane.ORIGINAL),
                LyricSourceKeys.customFile(FILE_ID),
                LyricSourceKeys.online(LyricSourceProviders.QQ, REMOTE_ID),
            ),
            original.map { it.key },
        )
        assertEquals(
            listOf(
                LyricSourceKind.APPLE,
                LyricSourceKind.OVERLAY_STORE,
                LyricSourceKind.CUSTOM_FILE,
                LyricSourceKind.ONLINE,
            ),
            original.map { it.kind },
        )
        assertEquals("Apple Music", original[0].label)
        assertEquals("酷我音乐", original[1].label)
        assertEquals("自定义文件", original[2].label)
        assertEquals(TITLE, original[3].label)
        assertEquals("YOASOBI · THE BOOK", original[3].detail)
    }

    @Test
    fun `the auto cache is listed as its own family after the custom files`() {
        val input = input(
            customFiles = listOf(
                fileRecord(originSource = CustomLyricsSources.AUTO_CACHE, fileId = "cache-1"),
                fileRecord(fileId = "file-1"),
            ),
        )

        val options = LyricSourceCatalog.options(input, LyricLane.ORIGINAL, ZH)

        assertEquals(
            listOf(LyricSourceKind.CUSTOM_FILE, LyricSourceKind.CACHE),
            options.map { it.kind },
        )
        assertEquals("自动缓存", options[1].label)
        assertEquals(LyricSourceKeys.cache("cache-1"), options[1].key)
    }

    @Test
    fun `a supplement lane only lists sources that carry that lane`() {
        val input = input(
            apple = AppleLyricsCapability(hasLyrics = true, hasTranslation = true),
            customFiles = listOf(
                fileRecord(fileId = "plain"),
                fileRecord(fileId = "translated", originSource = CustomLyricsSources.AMLL, hasTranslation = true),
            ),
            onlineCandidates = listOf(
                candidate(hasTranslation = false),
                candidate(id = "2002", hasTranslation = true),
            ),
        )

        val translation = LyricSourceCatalog.options(input, LyricLane.TRANSLATION, ZH)
        assertEquals(
            listOf(
                LyricSourceKeys.APPLE,
                LyricSourceKeys.customFile("translated"),
                LyricSourceKeys.online(LyricSourceProviders.QQ, "2002"),
            ),
            translation.map { it.key },
        )
        assertEquals("AMLL", translation[1].label)

        val original = LyricSourceCatalog.options(input, LyricLane.ORIGINAL, ZH)
        assertEquals("Apple Music", original[0].label)
        assertEquals("自定义文件", original[1].label)
        assertEquals("AMLL", original[2].label)
        assertTrue(LyricSourceCatalog.options(input, LyricLane.ROMANIZATION, ZH).isEmpty())
    }

    @Test
    fun `apple is listed only for the lanes it serves`() {
        val input = input(
            apple = AppleLyricsCapability(hasLyrics = true, hasWords = true, hasRomanization = true),
        )

        val original = LyricSourceCatalog.laneState(input, LyricLane.ORIGINAL, null, ZH)
        val translation = LyricSourceCatalog.laneState(input, LyricLane.TRANSLATION, null, ZH)
        val romanization = LyricSourceCatalog.laneState(input, LyricLane.ROMANIZATION, null, ZH)

        assertTrue(original.appleAvailable)
        assertFalse(translation.appleAvailable)
        assertTrue(romanization.appleAvailable)
        assertEquals(LyricSourceKeys.APPLE, original.selected?.key)
        assertNull(translation.selected)
        assertEquals(LyricSourceKeys.APPLE, romanization.selected?.key)
        assertTrue(romanization.selected!!.hasRomanization)
    }

    @Test
    fun `the original lane defaults to the source already in effect`() {
        val appleOnly = input(apple = AppleLyricsCapability(hasLyrics = true))
        assertEquals(
            LyricSourceKeys.APPLE,
            LyricSourceCatalog.laneState(appleOnly, LyricLane.ORIGINAL, null, ZH).selected?.key,
        )

        val written = input(
            apple = AppleLyricsCapability(hasLyrics = true),
            overlay = OverlayLyricsCapability(hasOriginal = true, originalSource = CustomLyricsSources.KUWO),
        )
        assertEquals(
            LyricSourceKeys.overlay(LyricLane.ORIGINAL),
            LyricSourceCatalog.laneState(written, LyricLane.ORIGINAL, null, ZH).selected?.key,
        )

        val installed = input(
            apple = AppleLyricsCapability(hasLyrics = true),
            customFiles = listOf(fileRecord(fileId = "installed"), fileRecord(fileId = "other")),
            activeFileId = "installed",
        )
        val installedState = LyricSourceCatalog.laneState(installed, LyricLane.ORIGINAL, null, ZH)
        assertEquals(LyricSourceKeys.customFile("installed"), installedState.selected?.key)
        assertFalse(installedState.overridden)
    }

    @Test
    fun `the original lane falls back to the custom index when Apple has nothing`() {
        val cacheOnly = input(
            customFiles = listOf(fileRecord(originSource = CustomLyricsSources.AUTO_CACHE, fileId = "c1")),
        )
        assertEquals(
            LyricSourceKeys.cache("c1"),
            LyricSourceCatalog.laneState(cacheOnly, LyricLane.ORIGINAL, null, ZH).selected?.key,
        )

        val nothing = input()
        assertNull(LyricSourceCatalog.laneState(nothing, LyricLane.ORIGINAL, null, ZH).selected)
    }

    @Test
    fun `a supplement lane defaults to Apple and otherwise shows nothing`() {
        val withApple = input(apple = AppleLyricsCapability(hasLyrics = true, hasTranslation = true))
        assertEquals(
            LyricSourceKeys.APPLE,
            LyricSourceCatalog.laneState(withApple, LyricLane.TRANSLATION, null, ZH).selected?.key,
        )

        val withoutApple = input(
            customFiles = listOf(fileRecord(hasTranslation = true)),
            onlineCandidates = listOf(candidate(hasTranslation = true)),
        )
        val state = LyricSourceCatalog.laneState(withoutApple, LyricLane.TRANSLATION, null, ZH)
        assertNull(state.selected)
        assertTrue(state.hidden)
        assertFalse(state.overridden)
        assertTrue(state.options.isNotEmpty())
    }

    @Test
    fun `an un-applied online candidate is never the lane default`() {
        val input = input(onlineCandidates = listOf(candidate()))

        val state = LyricSourceCatalog.laneState(input, LyricLane.ORIGINAL, null, ZH)

        assertEquals(listOf(LyricSourceKind.ONLINE), state.options.map { it.kind })
        assertNull(state.selected)
    }

    @Test
    fun `a stored selection wins over the default and is reported as an override`() {
        val input = input(
            apple = AppleLyricsCapability(hasLyrics = true),
            onlineCandidates = listOf(candidate(id = "2002")),
        )

        val snapshot = LyricSourceCatalog.build(
            input,
            mapOf(LyricLane.ORIGINAL to LyricSourceKeys.online(LyricSourceProviders.QQ, "2002")),
            ZH,
        )

        val original = snapshot.lane(LyricLane.ORIGINAL)!!
        assertEquals(LyricSourceKeys.online(LyricSourceProviders.QQ, "2002"), original.selected?.key)
        assertTrue(original.overridden)
        assertEquals(setOf(LyricLane.ORIGINAL), snapshot.overriddenLanes)
        assertTrue(snapshot.hasOverrides)
    }

    @Test
    fun `a stored key equal to the default is not an override`() {
        val input = input(apple = AppleLyricsCapability(hasLyrics = true))

        val snapshot = LyricSourceCatalog.build(
            input,
            mapOf(LyricLane.ORIGINAL to LyricSourceKeys.APPLE),
            ZH,
        )

        assertEquals(LyricSourceKeys.APPLE, snapshot.lane(LyricLane.ORIGINAL)!!.selected?.key)
        assertFalse(snapshot.lane(LyricLane.ORIGINAL)!!.overridden)
        assertFalse(snapshot.hasOverrides)
    }

    @Test
    fun `an unknown stored key degrades to the default without claiming an override`() {
        val input = input(
            apple = AppleLyricsCapability(hasLyrics = true),
            customFiles = listOf(fileRecord(fileId = "other")),
        )

        val snapshot = LyricSourceCatalog.build(
            input,
            mapOf(
                // The file record is gone from the index.
                LyricLane.ORIGINAL to LyricSourceKeys.customFile("deleted"),
                // The overlay store no longer holds this lane; nothing can be rebuilt.
                LyricLane.TRANSLATION to LyricSourceKeys.overlay(LyricLane.TRANSLATION),
            ),
            ZH,
        )

        assertEquals(LyricSourceKeys.APPLE, snapshot.lane(LyricLane.ORIGINAL)!!.selected?.key)
        assertFalse(snapshot.lane(LyricLane.ORIGINAL)!!.overridden)
        assertNull(snapshot.lane(LyricLane.TRANSLATION)!!.selected)
        assertFalse(snapshot.hasOverrides)
    }

    @Test
    fun `a malformed stored key is ignored`() {
        val input = input(apple = AppleLyricsCapability(hasLyrics = true))

        listOf("", " ", "apple rosa", "a".repeat(LyricSourceKeys.MAX_KEY_LENGTH + 1)).forEach { raw ->
            val snapshot = LyricSourceCatalog.build(input, mapOf(LyricLane.ORIGINAL to raw), ZH)
            assertEquals(LyricSourceKeys.APPLE, snapshot.lane(LyricLane.ORIGINAL)!!.selected?.key)
            assertFalse(snapshot.hasOverrides)
        }
    }

    @Test
    fun `the hidden marker hides a supplement lane but is ignored on the original`() {
        val input = input(
            apple = AppleLyricsCapability(hasLyrics = true, hasTranslation = true),
        )

        val hidden = LyricSourceCatalog.build(
            input,
            mapOf(
                LyricLane.TRANSLATION to LyricSourceKeys.HIDDEN,
                LyricLane.ORIGINAL to LyricSourceKeys.HIDDEN,
            ),
            ZH,
        )

        val translation = hidden.lane(LyricLane.TRANSLATION)!!
        assertNull(translation.selected)
        assertTrue(translation.hidden)
        assertTrue(translation.overridden)
        assertTrue(translation.appleAvailable)

        val original = hidden.lane(LyricLane.ORIGINAL)!!
        assertEquals(LyricSourceKeys.APPLE, original.selected?.key)
        assertFalse(original.overridden)
        assertEquals(setOf(LyricLane.TRANSLATION), hidden.overriddenLanes)
    }

    @Test
    fun `online candidates are ranked by score then provider then title`() {
        val input = input(
            onlineCandidates = listOf(
                candidate(provider = LyricSourceProviders.KUWO, id = "k1", title = "Other", artist = "Other", album = "Other", duration = 300_000L),
                candidate(provider = LyricSourceProviders.NETEASE, id = "n1", title = "Other", artist = "Other", album = "Other", duration = 300_000L),
                candidate(provider = LyricSourceProviders.QQ, id = "q1"),
            ),
        )

        val options = LyricSourceCatalog.onlineOptions(input, LyricLane.ORIGINAL, ZH)

        assertEquals(
            listOf(
                LyricSourceKeys.online(LyricSourceProviders.QQ, "q1"),
                LyricSourceKeys.online(LyricSourceProviders.NETEASE, "n1"),
                LyricSourceKeys.online(LyricSourceProviders.KUWO, "k1"),
            ),
            options.map { it.key },
        )
        assertEquals(100, options[0].matchScore)
        // Nothing matches and the duration is 39 s off, so the shared policy total
        // is negative and clamps to zero for both.
        assertEquals(0, options[1].matchScore)
        assertEquals(0, options[2].matchScore)
        assertEquals(options[0], LyricSourceCatalog.bestOnline(options))
    }

    @Test
    fun `duplicate provider hits collapse into one option`() {
        val input = input(
            onlineCandidates = listOf(
                candidate(id = "same"),
                candidate(id = "same"),
            ),
        )

        val options = LyricSourceCatalog.onlineOptions(input, LyricLane.ORIGINAL, ZH)

        assertEquals(1, options.size)
    }

    @Test
    fun `one lane never lists more options than the catalog bound`() {
        val candidates = (1..LyricSourceCatalog.MAX_OPTIONS_PER_LANE + 10).map { index ->
            candidate(id = "id-" + index)
        }
        val input = input(
            apple = AppleLyricsCapability(hasLyrics = true),
            onlineCandidates = candidates,
        )

        val options = LyricSourceCatalog.options(input, LyricLane.ORIGINAL, ZH)

        assertEquals(LyricSourceCatalog.MAX_OPTIONS_PER_LANE, options.size)
        assertEquals(LyricSourceKeys.APPLE, options.first().key)
    }

    @Test
    fun `a song switch never leaks the previous song's selection`() {
        val store = LyricSourceSelectionStore(
            File(temp.newFolder("selection"), LyricSourceSelectionStore.FILE_NAME),
            maxSongs = 4,
        )
        store.select(42L, LyricLane.TRANSLATION, LyricSourceKeys.online(LyricSourceProviders.QQ, "2002"))

        val first = LyricSourceCatalog.build(
            input(songId = 42L, onlineCandidates = listOf(candidate(id = "2002", hasTranslation = true))),
            store.selection(42L),
            ZH,
        )
        val second = LyricSourceCatalog.build(
            input(songId = 43L, onlineCandidates = listOf(candidate(id = "2002", hasTranslation = true))),
            store.selection(43L),
            ZH,
        )

        assertEquals(
            LyricSourceKeys.online(LyricSourceProviders.QQ, "2002"),
            first.lane(LyricLane.TRANSLATION)!!.selected?.key,
        )
        assertNull(second.lane(LyricLane.TRANSLATION)!!.selected)
        assertFalse(second.lane(LyricLane.TRANSLATION)!!.overridden)
        assertEquals(42L, first.songId)
        assertEquals(43L, second.songId)
    }

    @Test
    fun `persistedKey stores the option key or the hidden marker`() {
        val input = input(apple = AppleLyricsCapability(hasLyrics = true, hasTranslation = true))

        val apple = LyricSourceCatalog.laneState(input, LyricLane.ORIGINAL, null, ZH)
        assertEquals(LyricSourceKeys.APPLE, LyricSourceCatalog.persistedKey(apple))

        val hidden = LyricSourceCatalog.laneState(input, LyricLane.TRANSLATION, LyricSourceKeys.HIDDEN, ZH)
        assertEquals(LyricSourceKeys.HIDDEN, LyricSourceCatalog.persistedKey(hidden))

        val emptyOriginal = LyricSourceCatalog.laneState(input(), LyricLane.ORIGINAL, null, ZH)
        assertNull(LyricSourceCatalog.persistedKey(emptyOriginal))
    }

    @Test
    fun `option resolves a stored key back to the listed option`() {
        val input = input(onlineCandidates = listOf(candidate(id = "2002")))

        assertNotNull(
            LyricSourceCatalog.option(
                input,
                LyricLane.ORIGINAL,
                LyricSourceKeys.online(LyricSourceProviders.QQ, "2002"),
                ZH,
            ),
        )
        assertNull(
            LyricSourceCatalog.option(
                input,
                LyricLane.TRANSLATION,
                LyricSourceKeys.online(LyricSourceProviders.QQ, "2002"),
                ZH,
            ),
        )
        assertNull(LyricSourceCatalog.option(input, LyricLane.ORIGINAL, LyricSourceKeys.HIDDEN, ZH))
    }

    @Test
    fun `labels follow the requested locale`() {
        val input = input(
            apple = AppleLyricsCapability(hasLyrics = true, hasWords = true),
            customFiles = listOf(fileRecord(originSource = CustomLyricsSources.AMLL, userEdited = true)),
        )

        val zh = LyricSourceCatalog.options(input, LyricLane.ORIGINAL, ZH)
        val en = LyricSourceCatalog.options(input, LyricLane.ORIGINAL, EN)

        assertEquals("逐词", zh[0].detail)
        assertEquals("Word by word", en[0].detail)
        assertEquals("AMLL（已编辑）", zh[1].label)
        assertEquals("AMLL (edited)", en[1].label)
    }

    @Test
    fun `an empty song still yields three lanes with an empty state`() {
        val snapshot = LyricSourceCatalog.build(input(songId = 7L), emptyMap(), ZH)

        assertEquals(LyricLane.ALL, snapshot.lanes.map { it.lane })
        assertTrue(snapshot.isEmpty)
        assertNull(snapshot.lane(LyricLane.ORIGINAL)!!.selected)
        assertEquals(7L, snapshot.songId)
    }

    @Test
    fun `an applied online selection is synthesized into the snapshot`() {
        // Bug A: the applied key is not in the probe list because the search that
        // produced it is not replayed when the sheet opens. The lane must still
        // report the source the page is showing instead of falling back to 不显示.
        val key = LyricSourceKeys.online(LyricSourceProviders.QQ, "552173242")
        val input = input()

        val snapshot = LyricSourceCatalog.build(input, mapOf(LyricLane.TRANSLATION to key), ZH)
        val translation = snapshot.lane(LyricLane.TRANSLATION)!!
        val selected = translation.selected

        assertEquals(key, selected?.key)
        assertEquals(LyricSourceKind.ONLINE, selected?.kind)
        assertEquals(LyricSourceProviders.QQ, selected?.providerId)
        assertEquals("QQ 音乐", selected?.label)
        assertEquals("已应用的在线翻译", selected?.detail)
        assertEquals(false, selected?.hasWords)
        assertEquals(false, selected?.precisionKnown)
        assertEquals(0, selected?.matchScore)
        assertTrue(translation.overridden)
        assertFalse(translation.hidden)
        assertEquals(setOf(LyricLane.TRANSLATION), snapshot.overriddenLanes)
        // The sheet lists the current source too, so the user can see and leave it.
        assertTrue(translation.options.any { it.key == key })
    }

    @Test
    fun `a placeholder keeps an unknown provider id readable`() {
        val key = LyricSourceKeys.online("future-provider", "42")

        val state = LyricSourceCatalog.laneState(
            input(),
            LyricLane.ROMANIZATION,
            key,
            ZH,
        )

        assertEquals(key, state.selected?.key)
        assertEquals("future-provider", state.selected?.providerId)
        assertEquals("future-provider", state.selected?.label)
        assertEquals("已应用的在线音译", state.selected?.detail)
        assertTrue(state.overridden)
    }

    @Test
    fun `a saved custom file is rebuilt even when the lane filter hid it`() {
        // The probe only reads a bounded number of documents, so it may report a
        // record without the translation flag while the user has applied it.
        val record = fileRecord(
            fileId = "saved-1",
            displayName = "QQ 音乐",
            originSource = CustomLyricsSources.MANUAL,
            hasTranslation = false,
        )
        val key = LyricSourceKeys.customFile("saved-1")
        val input = input(customFiles = listOf(record))

        val state = LyricSourceCatalog.laneState(input, LyricLane.TRANSLATION, key, ZH)
        val selected = state.selected!!

        assertEquals(key, selected.key)
        assertEquals(LyricSourceKind.CUSTOM_FILE, selected.kind)
        assertEquals(LyricSourceProviders.QQ, selected.providerId)
        assertEquals("QQ 音乐", selected.label)
        assertEquals("已保存到本地", selected.detail)
        assertTrue(selected.hasTranslation)
        assertFalse(selected.precisionKnown)
        assertTrue(state.overridden)
    }

    @Test
    fun `a file key whose record is gone still degrades to the default`() {
        val input = input(
            apple = AppleLyricsCapability(hasLyrics = true),
            customFiles = listOf(fileRecord(fileId = "other")),
        )

        val snapshot = LyricSourceCatalog.build(
            input,
            mapOf(LyricLane.ORIGINAL to LyricSourceKeys.customFile("deleted")),
            ZH,
        )

        assertEquals(LyricSourceKeys.APPLE, snapshot.lane(LyricLane.ORIGINAL)!!.selected?.key)
        assertFalse(snapshot.hasOverrides)
    }

    @Test
    fun `a resolvable stored key is listed once and not duplicated`() {
        val candidateKey = LyricSourceKeys.online(LyricSourceProviders.QQ, "2002")
        val withCandidate = input(
            onlineCandidates = listOf(candidate(id = "2002", hasTranslation = true)),
        )

        val state = LyricSourceCatalog.laneState(withCandidate, LyricLane.TRANSLATION, candidateKey, ZH)

        assertEquals(candidateKey, state.selected?.key)
        assertEquals(1, state.options.count { it.key == candidateKey })
        assertEquals(1, state.options.size)
        assertTrue(state.overridden)
    }

    @Test
    fun `a stored Apple key that Apple no longer serves is not fabricated`() {
        val input = input(customFiles = listOf(fileRecord(hasTranslation = true)))

        val state = LyricSourceCatalog.laneState(input, LyricLane.TRANSLATION, LyricSourceKeys.APPLE, ZH)

        assertNull(state.selected)
        assertFalse(state.overridden)
    }

    @Test
    fun `a synthesized online source recovers precision from the displayed document`() {
        // An applied online document is installed as the song's custom lyrics, so when
        // it is the document on screen the probe read it: the placeholder can tell the
        // truth about word timing instead of defaulting to line-level.
        val record = fileRecord(
            fileId = "applied",
            displayName = "QQ 音乐",
            originSource = CustomLyricsSources.QQ,
            hasWords = true,
        )
        val key = LyricSourceKeys.online(LyricSourceProviders.QQ, "552173242")
        val input = input(customFiles = listOf(record), activeFileId = "applied")

        val state = LyricSourceCatalog.laneState(input, LyricLane.ORIGINAL, key, ZH)
        val selected = state.selected!!

        assertEquals(key, selected.key)
        assertTrue(selected.hasWords)
        assertTrue(selected.precisionKnown)
        assertTrue(state.overridden)
    }

    @Test
    fun `a rebuilt document reports precision only when it is the displayed one`() {
        val record = fileRecord(fileId = "active", hasWords = true)
        val key = LyricSourceKeys.customFile("active")

        val showing = LyricSourceCatalog.laneState(
            input(customFiles = listOf(record), activeFileId = "active"),
            LyricLane.TRANSLATION,
            key,
            ZH,
        )
        assertTrue(showing.selected!!.precisionKnown)
        assertTrue(showing.selected!!.hasWords)

        val hidden = LyricSourceCatalog.laneState(
            input(customFiles = listOf(record)),
            LyricLane.TRANSLATION,
            key,
            ZH,
        )
        assertFalse(hidden.selected!!.precisionKnown)
    }

    private fun input(
        songId: Long = 42L,
        apple: AppleLyricsCapability = AppleLyricsCapability(),
        overlay: OverlayLyricsCapability = OverlayLyricsCapability(),
        customFiles: List<CustomLyricsSourceRecord> = emptyList(),
        onlineCandidates: List<OnlineLyricsCandidate> = emptyList(),
        activeFileId: String? = null,
    ): LyricSourceInput = LyricSourceInput(
        song = LyricSongContext(
            songId = songId,
            title = TITLE,
            artist = ARTIST,
            album = ALBUM,
            durationMs = DURATION_MS,
        ),
        apple = apple,
        overlay = overlay,
        customFiles = customFiles,
        onlineCandidates = onlineCandidates,
        activeFileId = activeFileId,
    )

    private fun candidate(
        provider: String = LyricSourceProviders.QQ,
        id: String = REMOTE_ID,
        title: String = TITLE,
        artist: String = ARTIST,
        album: String = ALBUM,
        duration: Long = DURATION_MS,
        hasWords: Boolean = true,
        hasTranslation: Boolean = false,
        hasRomanization: Boolean = false,
    ): OnlineLyricsCandidate = OnlineLyricsCandidate(
        providerId = provider,
        result = SongSearchResult(
            id = id,
            title = title,
            artist = artist,
            album = album,
            duration = duration,
            source = sourceOf(provider),
        ),
        hasWords = hasWords,
        hasTranslation = hasTranslation,
        hasRomanization = hasRomanization,
    )

    private fun sourceOf(provider: String): Source = when (provider) {
        LyricSourceProviders.QQ -> Source.QM
        LyricSourceProviders.NETEASE -> Source.NE
        LyricSourceProviders.KUWO -> Source.KUWO
        LyricSourceProviders.KUGOU -> Source.KUGOU
        else -> Source.LB
    }

    private fun fileRecord(
        fileId: String = FILE_ID,
        displayName: String = "custom.ttml",
        originSource: String = CustomLyricsSources.MANUAL,
        userEdited: Boolean = false,
        hasWords: Boolean = true,
        hasTranslation: Boolean = false,
        hasRomanization: Boolean = false,
    ): CustomLyricsSourceRecord = CustomLyricsSourceRecord(
        fileId = fileId,
        displayName = displayName,
        originSource = originSource,
        userEdited = userEdited,
        hasWords = hasWords,
        hasTranslation = hasTranslation,
        hasRomanization = hasRomanization,
    )

    private companion object {
        const val TITLE = "夜に駆ける"
        const val ARTIST = "YOASOBI"
        const val ALBUM = "THE BOOK"
        const val DURATION_MS = 261_000L
        const val REMOTE_ID = "1001"
        const val FILE_ID = "f1"
        val ZH: Locale = Locale.SIMPLIFIED_CHINESE
        val EN: Locale = Locale.US
    }
}
