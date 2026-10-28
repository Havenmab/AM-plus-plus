package dev.amenhancer.module.hook.lyricsource

import dev.amenhancer.module.CurrentSongDetails
import dev.amenhancer.module.config.ConfigurationReader
import dev.amenhancer.module.config.TargetConfigClient
import dev.amenhancer.module.hook.CurrentSongIdentityCache
import dev.amenhancer.module.hook.TtmlDocumentMetadata
import dev.amenhancer.module.hook.TtmlTimingMode
import dev.amenhancer.module.hook.TtmlTimingObservationRegistry
import dev.amenhancer.module.lyrics.online.NativeLyricLine
import dev.amenhancer.module.lyrics.online.NativeLyricOverlayStore
import dev.amenhancer.module.model.CustomLyricsSources
import dev.amenhancer.module.lyrics.source.LyricLane
import dev.amenhancer.module.lyrics.source.LyricSourceKeys
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

    private val executor = Executor { command -> command.run() }
    private var controller: LyricsSourceControllerImpl? = null

    @After
    fun tearDown() {
        // The controller registers itself on the module-wide automatic bridge;
        // release it so one test cannot steer the next one.
        controller?.release()
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

    private fun newController(
        currentSong: CurrentSongIdentityCache,
        probeSong: CurrentSongIdentityCache = currentSong,
        overlay: NativeLyricOverlayStore = overlayWithTranslationLane(),
        timing: TtmlTimingObservationRegistry = timingWithAppleDocument(),
    ): LyricsSourceControllerImpl {
        val probes = LyricsSourceProbes(
            config = config(),
            currentSong = probeSong,
            overlay = overlay,
            timing = timing,
        )
        val search = LyricsSourceSearch(
            transport = null,
            lunabeat = null,
            executor = executor,
            logger = {},
        )
        val folder = directory()
        val created = LyricsSourceControllerImpl(
            currentSong = currentSong,
            overlay = overlay,
            probes = probes,
            search = search,
            store = LyricSourceSelectionStore(File(folder, "selection.json")),
            readDocument = { null },
            runtime = null,
            nativeRefresh = { null },
            persistence = LyricsSourceOverlayPersistence(File(folder, "overlay.json")),
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
}
