package dev.amenhancer.module.hook.lyricsource

import dev.amenhancer.module.CurrentSongDetails
import dev.amenhancer.module.config.ConfigurationReader
import dev.amenhancer.module.config.TargetConfigClient
import dev.amenhancer.module.hook.CurrentSongIdentityCache
import dev.amenhancer.module.hook.LyricsSourceOverlayCacheBridge
import dev.amenhancer.module.hook.TtmlDocumentMetadata
import dev.amenhancer.module.hook.TtmlTimingMode
import dev.amenhancer.module.hook.TtmlTimingObservationRegistry
import dev.amenhancer.module.lyrics.online.NativeLyricLine
import dev.amenhancer.module.lyrics.online.NativeLyricModelPolicy
import dev.amenhancer.module.lyrics.online.NativeLyricOverlayStore
import dev.amenhancer.module.model.CustomLyricsSources
import dev.amenhancer.module.lyrics.source.LyricLane
import dev.amenhancer.module.lyrics.source.LyricSourceKeys
import dev.amenhancer.module.lyrics.source.LyricSourceProviders
import dev.amenhancer.module.lyrics.source.LyricSourceSelectionStore
import java.io.File
import java.io.InputStream
import java.util.concurrent.Executor
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.LooperMode

/**
 * The sheet's one hard invariant: a snapshot is null **only** when nothing is
 * playing.
 *
 * The reported regression was the opposite: the panel opened on a playing track
 * and rendered 「当前没有播放的歌曲」 with all three lanes 「暂无可选来源」, because
 * [LyricsSourceControllerImpl.snapshotOf] turned every failure — including the
 * probe not seeing the song — into the same null the sheet reads as "no song",
 * and it did so without a log line. These tests run the real probe, the real
 * overlay store and the real catalog against a published identity cache, so the
 * invariant is executed rather than asserted from source text.
 *
 * The second half of the file is the reported 「补全完了，面板还说「不显示」」 and
 * 「显示发音 过一会儿就点不动」: every lane (and its provider) lives in
 * [LyricsSourceOverlayPersistence], so the sheet must report it from there — after
 * an automatic completion, after the refresh that replaces the store, and after a
 * restart. The same record is what the native hooks replay, which is what keeps
 * the pronunciation row enabled once the lane has been published.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33], manifest = Config.NONE)
@LooperMode(LooperMode.Mode.PAUSED)
class LyricsSourceSnapshotTest {

    private val appleMusicId = 42L

    /** One Apple Word-timed line: enough for the original lane to have a source. */
    private val appleTtml = "<tt xmlns=\"http://www.w3.org/ns/ttml\" " +
        "xmlns:ttm=\"http://www.w3.org/ns/ttml#metadata\" xml:lang=\"ja\"><body><div>" +
        "<p begin=\"1.000\" end=\"3.000\">" +
        "<span begin=\"1.000\" end=\"2.000\">君</span>" +
        "<span begin=\"2.000\" end=\"3.000\">の名は</span></p>" +
        "</div></body></tt>"

    /**
     * The document 「补全歌词翻译与发音」 produces for [appleTtml]: Apple's body plus the
     * provider's Chinese under `<translations>` and the line's own reading under
     * `<transliterations>`. It is the body the completion hands the host record,
     * so the two lanes and the `ja-Latn` tag below are exactly what a device writes.
     */
    private val mergedTtml = """
        <tt xmlns="http://www.w3.org/ns/ttml" xmlns:ttm="http://www.w3.org/ns/ttml#metadata" 
            xmlns:itunes="http://music.apple.com/lyric-ttml-internal" itunes:timing="Word" xml:lang="ja">
        <head><metadata><iTunesMetadata xmlns="http://music.apple.com/lyric-ttml-internal">
        <translations><translation type="subtitle" xml:lang="zh-Hans">
        <text for="L1">你的名字</text></translation></translations>
        <transliterations><transliteration xml:lang="ja-Latn">
        <text for="L1"><span begin="1.000" end="3.000">Kimi no na wa</span></text>
        </transliteration></transliterations></iTunesMetadata></metadata></head>
        <body><div><p begin="1.000" end="3.000" itunes:key="L1">
        <span begin="1.000" end="2.000">君</span><span begin="2.000" end="3.000">の名は</span></p>
        </div></body></tt>
    """.trimIndent()

    private val executor = Executor { command -> command.run() }
    private var controller: LyricsSourceControllerImpl? = null

    @After
    fun tearDown() {
        // The controller registers itself on the module-wide automatic bridge;
        // release it so one test cannot steer the next one.
        controller?.release()
        LyricsSourceOverlayCacheBridge.replay = null
    }

    private fun config(): TargetConfigClient = TargetConfigClient(
        object : ConfigurationReader {
            override fun values(): Map<String, *> = emptyMap<String, Any>()
            override fun openFile(name: String): InputStream? = null
        },
    )

    private fun playingCache(): CurrentSongIdentityCache {
        val cache = CurrentSongIdentityCache()
        cache.publish(
            item = Any(),
            details = CurrentSongDetails(
                appleMusicId = appleMusicId,
                title = "明日の私に幸あれ",
                artist = "NANAOAKARI",
                durationMs = 216_000L,
            ),
        )
        return cache
    }

    /** The module's own translation lane for the playing song. */
    private fun overlayWithTranslationLane(): NativeLyricOverlayStore {
        val overlay = NativeLyricOverlayStore()
        overlay.update(
            songId = appleMusicId.toString(),
            lines = listOf(
                NativeLyricLine(
                    begin = 1_000L,
                    end = 3_000L,
                    text = "君の名は",
                    translation = "你的名字",
                ),
            ),
            translationSource = CustomLyricsSources.QQ,
        )
        return overlay
    }

    private fun timingWithAppleDocument(): TtmlTimingObservationRegistry {
        val timing = TtmlTimingObservationRegistry()
        // Keep a strong reference: the registry keys observations by pointer
        // identity, and only a live one proves the song's Apple document.
        val pointer = Any()
        timing.record(
            pointer = pointer,
            metadata = TtmlDocumentMetadata(
                timingMode = TtmlTimingMode.WORD,
                language = "ja",
                hasTranslation = false,
            ),
            appleMusicId = appleMusicId,
            rawTtml = appleTtml,
        )
        pointedDocuments += pointer
        return timing
    }

    /** Keeps the pointers the timing registry observes alive for the whole test. */
    private val pointedDocuments = mutableListOf<Any>()

    private fun directory(): File =
        File(System.getProperty("java.io.tmpdir"), "ampp-lyrics-source-" + System.nanoTime())
            .also { it.mkdirs() }

    private fun persistenceOf(folder: File): LyricsSourceOverlayPersistence =
        LyricsSourceOverlayPersistence(File(folder, OVERLAY_FILE))

    /**
     * What 「补全歌词翻译与发音」 writes once it has merged a provider's translation
     * into the displayed document: both lanes plus the provider of each one, in one
     * record ([LyricsSourceOverlayCacheBridge] hands the host exactly these values).
     */
    private fun recordAutomaticCompletion(folder: File): Boolean =
        persistenceOf(folder).saveMergedDocument(
            songId = appleMusicId,
            mergedTtml = mergedTtml,
            translationSource = CustomLyricsSources.QQ,
            pronunciationSource = CustomLyricsSources.QQ,
            originalSource = CustomLyricsSources.QQ,
            pronunciationLanguages = listOf(JA_LATN),
        )

    private fun probesFor(
        overlay: NativeLyricOverlayStore,
        folder: File,
        currentSong: CurrentSongIdentityCache = playingCache(),
    ): LyricsSourceProbes = LyricsSourceProbes(
        config = config(),
        currentSong = currentSong,
        overlay = overlay,
        persistence = persistenceOf(folder),
        timing = timingWithAppleDocument(),
    )

    private fun newController(
        currentSong: CurrentSongIdentityCache,
        probeSong: CurrentSongIdentityCache = currentSong,
        overlay: NativeLyricOverlayStore = overlayWithTranslationLane(),
        timing: TtmlTimingObservationRegistry = timingWithAppleDocument(),
        folder: File = directory(),
    ): LyricsSourceControllerImpl {
        // One record, like the host: the probe and the controller must not each keep
        // their own view of the file (a write through one would be invisible to the
        // other's cached index).
        val persistence = persistenceOf(folder)
        val probes = LyricsSourceProbes(
            config = config(),
            currentSong = probeSong,
            overlay = overlay,
            persistence = persistence,
            timing = timing,
        )
        val search = LyricsSourceSearch(
            transport = null,
            lunabeat = null,
            executor = executor,
            logger = {},
        )
        val created = LyricsSourceControllerImpl(
            currentSong = currentSong,
            overlay = overlay,
            probes = probes,
            search = search,
            store = LyricSourceSelectionStore(File(folder, "selection.json")),
            readDocument = { null },
            runtime = null,
            nativeRefresh = { null },
            persistence = persistence,
            pronunciationToggle = LyricsSourcePronunciationToggle(null),
            executor = executor,
            logger = {},
        )
        controller = created
        return created
    }

    @Test
    fun `a playing song always has a snapshot`() {
        val snapshot = requireNotNull(newController(playingCache()).snapshot()) {
            "a published track must never read as「当前没有播放的歌曲」"
        }
        assertEquals(appleMusicId, snapshot.songId)
        LyricLane.ALL.forEach { lane ->
            assertNotNull("lane " + lane.key + " must be listed", snapshot.lane(lane))
        }
        // Apple's own document is what the original lane is showing.
        assertTrue(snapshot.lane(LyricLane.ORIGINAL)!!.appleAvailable)
    }

    @Test
    fun `a snapshot with a published overlay lane still reports the song`() {
        val snapshot = requireNotNull(newController(playingCache()).snapshot())
        val translation = snapshot.lane(LyricLane.TRANSLATION)
        assertNotNull("the overlay holds this lane, so it must be reported", translation)
        // The lane's own content is what is displayed, so the catalog selects it
        // without any stored override.
        assertEquals(LyricSourceKeys.overlay(LyricLane.TRANSLATION), translation!!.selected?.key)
        assertFalse(translation.overridden)
        assertTrue(translation.options.isNotEmpty())
    }

    @Test
    fun `a probe that cannot see the song degrades instead of claiming silence`() {
        // The device failure mode: the identity cache still knows the track while
        // the probe's own read comes back empty. It must not become "no song".
        val snapshot = requireNotNull(
            newController(
                currentSong = playingCache(),
                probeSong = CurrentSongIdentityCache(),
            ).snapshot(),
        ) { "a playing track must not render as「当前没有播放的歌曲」" }
        assertEquals(appleMusicId, snapshot.songId)
        assertEquals("明日の私に幸あれ", snapshot.title)
        // Nothing could be probed, so every lane is honestly empty and listed.
        LyricLane.ALL.forEach { lane ->
            val state = snapshot.lane(lane)
            assertNotNull(state)
            assertNull(state!!.selected)
            assertTrue(state.options.isEmpty())
        }
    }

    @Test
    fun `no playing song is the only null snapshot`() {
        assertNull(newController(CurrentSongIdentityCache()).snapshot())
    }

    @Test
    fun `the probe reports the completed lanes from the record, not only from the store`() {
        val folder = directory()
        // 「补全歌词翻译与发音」 published its lanes and the provider of each one.
        assertTrue("the completion must be stored", recordAutomaticCompletion(folder))
        // A restart / a replaced store: the in-memory overlay holds nothing at all.
        val probed = requireNotNull(
            probesFor(overlay = NativeLyricOverlayStore(), folder = folder).probe(),
        ) { "a playing track must always probe" }
        assertTrue(
            "the record holds the translation lane, so it must be reported",
            probed.input.overlay.hasTranslation,
        )
        assertEquals(
            CustomLyricsSources.QQ,
            probed.input.overlay.translationSource,
        )
        assertTrue(probed.input.overlay.hasRomanization)
        assertEquals(CustomLyricsSources.QQ, probed.input.overlay.romanizationSource)
        // The document that supplied the lanes also supplied the lyrics themselves.
        assertEquals(CustomLyricsSources.QQ, probed.input.overlay.originalSource)
    }

    @Test
    fun `a completed translation names its provider in the panel and keeps it after a restart`() {
        val folder = directory()
        assertTrue(recordAutomaticCompletion(folder))
        // The completion's own process: the store was replaced by the refresh that
        // followed, so an empty store is the honest starting point.
        val first = requireNotNull(
            newController(playingCache(), overlay = NativeLyricOverlayStore(), folder = folder).snapshot(),
        )
        assertEquals(
            "the panel must report the provider the completion recorded",
            LyricSourceProviders.QQ,
            first.lane(LyricLane.TRANSLATION)?.selected?.providerId,
        )
        // Restart: fresh store, fresh controller, the same on-disk record.
        val restarted = requireNotNull(
            newController(playingCache(), overlay = NativeLyricOverlayStore(), folder = folder).snapshot(),
        )
        assertEquals(
            LyricSourceProviders.QQ,
            restarted.lane(LyricLane.TRANSLATION)?.selected?.providerId,
        )
        assertEquals(
            LyricSourceKeys.overlay(LyricLane.TRANSLATION),
            restarted.lane(LyricLane.TRANSLATION)?.selected?.key,
        )
        assertFalse(
            "a lane with content and a provider is never 「不显示」",
            restarted.lane(LyricLane.TRANSLATION)?.selected == null,
        )
    }

    @Test
    fun `the published pronunciation lane stays available for the toggle`() {
        val folder = directory()
        assertTrue(recordAutomaticCompletion(folder))
        val overlay = NativeLyricOverlayStore()
        requireNotNull(newController(playingCache(), overlay = overlay, folder = folder).snapshot())
        val key = appleMusicId.toString()
        assertTrue("the record's romanization lane must be published", overlay.hasPronunciation(key))
        assertEquals(listOf(JA_LATN), overlay.pronunciationLanguages(key))
        // A later refresh that carries only the translation must not take the lane —
        // and with it the 显示发音 row — away.
        overlay.update(
            songId = key,
            lines = listOf(
                NativeLyricLine(begin = 1_000L, end = 3_000L, text = "君の名は", translation = "你的名字"),
            ),
        )
        assertTrue(
            "a translation-only refresh must keep the pronunciation lane",
            overlay.hasPronunciation(key),
        )
        assertEquals(listOf(JA_LATN), overlay.pronunciationLanguages(key))
        // The app's menu row reads exactly this: the lane is Apple's own, or ours.
        assertTrue(
            NativeLyricModelPolicy.hasPronunciationAvailability(
                original = false,
                hasValidOfficialPronunciation = false,
                mandarinHidden = false,
                hasOnlinePronunciation = overlay.hasPronunciation(key),
            ),
        )
        // Leaving the track empties the store (it holds one song); the record is the
        // single source of truth, so the lane comes back and the row stays clickable.
        overlay.clear()
        LyricsSourceOverlayCacheBridge.replay = persistenceOf(folder)::restoreInto
        assertTrue(LyricsSourceOverlayCacheBridge.restore(appleMusicId, overlay))
        assertTrue(overlay.hasPronunciation(key))
        assertEquals(listOf(JA_LATN), overlay.pronunciationLanguages(key))
    }

    private companion object {
        const val OVERLAY_FILE = "overlay.json"

        const val JA_LATN = "ja-Latn"
    }
}
