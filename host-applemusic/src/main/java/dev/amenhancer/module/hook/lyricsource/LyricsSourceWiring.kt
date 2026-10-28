package dev.amenhancer.module.hook.lyricsource

import android.app.Application
import dev.amenhancer.host.applemusic.AppleMusicHostProfile
import dev.amenhancer.module.config.TargetConfigClient
import dev.amenhancer.module.hook.AutoLyricsRuntime
import dev.amenhancer.module.hook.CurrentSongIdentityCache
import dev.amenhancer.module.hook.LyricsSourceTarget
import dev.amenhancer.module.hook.ModernXposedRuntime
import dev.amenhancer.module.hook.NativeLyricsTarget
import dev.amenhancer.module.hook.TargetCapabilityInstall
import dev.amenhancer.module.hook.TtmlTimingObservationRegistry
import dev.amenhancer.module.lyrics.TtmlInputPolicy
import dev.amenhancer.module.lyrics.online.NativeLyricOverlayStore
import dev.amenhancer.module.lyrics.source.HttpLyricTransport
import dev.amenhancer.module.lyrics.source.LunabeatCatalogCache
import dev.amenhancer.module.lyrics.source.LunabeatCatalogCacheSnapshot
import dev.amenhancer.module.lyrics.source.LunabeatClient
import dev.amenhancer.module.lyrics.source.LyricsSourceAccess
import dev.amenhancer.module.lyrics.source.LyricSourceSelectionStore
import io.github.proify.lyricon.amprovider.xposed.AppleMusicHookPoint
import io.github.proify.lyricon.amprovider.xposed.AppleMusicHookResolver
import java.io.File
import java.util.concurrent.ArrayBlockingQueue
import java.util.concurrent.ThreadPoolExecutor
import java.util.concurrent.TimeUnit

/**
 * Apple's own 隐藏发音 / 隐藏翻译 preference.
 *
 * The sheet's 不显示 switch means the same thing as those native toggles, so it
 * drives them instead of inventing a second meaning for "hidden". The setter is
 * profile-pinned (1609: `ja.h0#m(boolean)`); when a build has no such symbol the
 * caller falls back to hiding only this module's own lane and says so.
 */
internal class LyricsSourcePronunciationToggle(
    private val resolver: AppleMusicHookResolver?,
) {
    /** Sets Apple's "lyrics pronunciation selected" flag; false when unavailable. */
    fun setSelected(selected: Boolean): Boolean {
        val target = resolver ?: return false
        return runCatching {
            val resolved = target.resolveMethod(AppleMusicHookPoint.LYRICS_PRONUNCIATION_PREFERENCE)
            resolved.method.invoke(null, selected)
            true
        }.getOrDefault(false)
    }
}

/**
 * Assembles the lyrics-source feature: the menu entry plus the controller the
 * sheet talks to.
 *
 * This is the single call the host factory makes for the feature, so all of the
 * wiring (which store file, which transport, which refresh seams) stays in this
 * package instead of spreading through the factory:
 *
 * ```
 * lyricsSource = createLyricsSourceTarget(
 *     application = application,
 *     classLoader = classLoader,
 *     profile = profile,
 *     config = config,
 *     currentSong = currentSong,
 *     overlay = nativeLyricOverlay,
 *     timing = timingObservations,
 *     runtime = autoLyricsRuntime,
 *     nativeLyrics = { nativeLyricsTarget },
 *     nativeResolver = nativeLyricResolver,
 *     enabled = { config.settings().lyricsSourceSheetEnabled },
 *     onFailure = { message -> ...reportHealth(DEGRADED...) },
 * )
 * ```
 */
internal fun createLyricsSourceTarget(
    application: Application,
    classLoader: ClassLoader,
    profile: AppleMusicHostProfile,
    config: TargetConfigClient,
    currentSong: CurrentSongIdentityCache,
    overlay: NativeLyricOverlayStore,
    timing: TtmlTimingObservationRegistry,
    runtime: AutoLyricsRuntime?,
    nativeLyrics: () -> NativeLyricsTarget?,
    nativeResolver: AppleMusicHookResolver?,
    enabled: () -> Boolean,
    onFailure: (String) -> Unit,
    logger: (String) -> Unit = { message -> ModernXposedRuntime.log(message) },
): LyricsSourceTarget {
    val directory = File(application.filesDir, STORAGE_DIRECTORY)
    val store = LyricSourceSelectionStore(File(directory, LyricSourceSelectionStore.FILE_NAME))
    val executor = ThreadPoolExecutor(
        1,
        1,
        0L,
        TimeUnit.MILLISECONDS,
        ArrayBlockingQueue(1),
        { runnable -> Thread(runnable, "ampp-lyrics-source").apply { isDaemon = true } },
        ThreadPoolExecutor.DiscardOldestPolicy(),
    )
    val lyricsTransport = runCatching {
        HttpLyricTransport(
            connectTimeoutMs = REQUEST_TIMEOUT_MS,
            readTimeoutMs = REQUEST_TIMEOUT_MS * 2,
            maxResponseBytes = TtmlInputPolicy.MAX_TTML_BYTES,
        )
    }.getOrNull()
    val lunabeat = lyricsTransport?.let { transport -> runCatching {
        LunabeatClient(
            indexTransport = HttpLyricTransport(
                connectTimeoutMs = REQUEST_TIMEOUT_MS,
                readTimeoutMs = REQUEST_TIMEOUT_MS * 2,
                maxResponseBytes = INDEX_MAX_BYTES,
            ),
            lyricsTransport = transport,
            cache = LunabeatDiskCache(File(directory, LUNABEAT_DIRECTORY)),
        )
    }.getOrNull() }
    val probes = LyricsSourceProbes(
        config = config,
        currentSong = currentSong,
        overlay = overlay,
        timing = timing,
        metadataTitle = { id -> metadata(id)?.title },
        metadataArtist = { id -> metadata(id)?.artist },
        metadataAlbum = { id -> metadata(id)?.originalAlbum },
        // The host's metadata record carries no artwork URL today, so the sheet
        // keeps its placeholder until the app-side bridge forwards one.
        metadataArtworkUrl = { null },
    )
    // The two GitHub corpora share one downloader: whichever mirror works on this
    // device is remembered once, and both of them report the same diagnostics.
    val corpus = LyricsSourceCorpusDownloader(logger = logger)
    val amll = LyricsSourceAmllProvider(
        directory = File(directory, AMLL_DIRECTORY),
        downloader = corpus,
        logger = logger,
    )
    val amLyrics = LyricsSourceAmLyricsProvider(
        directory = File(directory, AM_LYRICS_DIRECTORY),
        downloader = corpus,
        logger = logger,
    )
    val search = LyricsSourceSearch(
        transport = lyricsTransport,
        lunabeat = lunabeat,
        amll = amll,
        amLyrics = amLyrics,
        executor = executor,
        logger = logger,
    )
    // The overlay store is memory only; this file is what survives a restart
    // (same directory as the selection, so one module data folder holds the state).
    val overlayPersistence = LyricsSourceOverlayPersistence(File(directory, OVERLAY_FILE_NAME))
    // The automatic completion pipeline publishes lanes in memory only; write them through so a
    // restart still knows which provider supplied a translation.
    LyricsSourceOverlayCacheBridge.save = { songId, mergedTtml, translationSource, pronunciationSource,
        originalSource, languages ->
        overlayPersistence.saveMergedDocument(
            songId = songId,
            mergedTtml = mergedTtml,
            translationSource = translationSource,
            pronunciationSource = pronunciationSource,
            originalSource = originalSource,
            pronunciationLanguages = languages,
        )
    }
    val controller = LyricsSourceControllerImpl(
        currentSong = currentSong,
        overlay = overlay,
        probes = probes,
        search = search,
        store = store,
        persistence = overlayPersistence,
        readDocument = { fileId ->
            runCatching {
                config.openFile(fileId)?.use { input -> input.readBytes().toString(Charsets.UTF_8) }
            }.getOrNull()
        },
        runtime = runtime,
        nativeRefresh = nativeLyrics,
        pronunciationToggle = LyricsSourcePronunciationToggle(nativeResolver),
        executor = executor,
        logger = logger,
    )
    val menu = LyricsSourceMenuTarget(
        application = application,
        classLoader = classLoader,
        profile = profile,
        enabled = enabled,
        onFailure = onFailure,
        logger = logger,
    )
    return LyricsSourceTarget {
        // The controller is published independently of the menu: the settings
        // page opens the same sheet, and a denied menu contract must not take the
        // sheet away with it.
        LyricsSourceAccess.install(controller)
        menu.install()
    }
}

/** The current metadata record, used only to fill the sheet's song card. */
private fun metadata(appleMusicId: Long): io.github.proify.lyricon.amprovider.xposed.MediaMetadataCache.Metadata? =
    runCatching {
        io.github.proify.lyricon.amprovider.xposed.MediaMetadataCache.getMetadataById(appleMusicId.toString())
    }.getOrNull()

/**
 * Bounded on-disk cache for the Lunabeat catalogue.
 *
 * The catalogue is a few hundred kilobytes; keeping it beside the selection file
 * means a search after a process restart does not pay for the full download, and
 * a corrupt cache simply reads as absent.
 */
private class LunabeatDiskCache(private val directory: File) : LunabeatCatalogCache {

    override fun read(): LunabeatCatalogCacheSnapshot? = runCatching {
        val manifest = File(directory, MANIFEST_FILE)
        val index = File(directory, INDEX_FILE)
        if (!manifest.isFile || !index.isFile) return null
        if (manifest.length() > MAX_CACHE_BYTES || index.length() > MAX_CACHE_BYTES) return null
        LunabeatCatalogCacheSnapshot(
            manifestJson = manifest.readText(Charsets.UTF_8),
            indexJson = index.readText(Charsets.UTF_8),
        )
    }.getOrNull()

    override fun write(manifestJson: String, indexJson: String, etag: String?): Boolean = runCatching {
        if (!directory.exists() && !directory.mkdirs()) return false
        File(directory, MANIFEST_FILE).writeText(manifestJson, Charsets.UTF_8)
        File(directory, INDEX_FILE).writeText(indexJson, Charsets.UTF_8)
        true
    }.getOrDefault(false)

    private companion object {
        const val MANIFEST_FILE = "manifest.json"
        const val INDEX_FILE = "songs.json"
        const val MAX_CACHE_BYTES = 4L * 1024L * 1024L
    }
}

private const val STORAGE_DIRECTORY = "ampp-lyrics-source"
private const val OVERLAY_FILE_NAME = "overlay.json"
private const val LUNABEAT_DIRECTORY = "lunabeat"
private const val AMLL_DIRECTORY = "amll"
private const val AM_LYRICS_DIRECTORY = "am-lyrics"
private const val REQUEST_TIMEOUT_MS = 4_000
private const val INDEX_MAX_BYTES = 2 * 1024 * 1024
