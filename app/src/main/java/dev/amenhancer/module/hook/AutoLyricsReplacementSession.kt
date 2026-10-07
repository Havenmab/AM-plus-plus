package dev.amenhancer.module.hook

import dev.amenhancer.module.i18n.ModuleText

import dev.amenhancer.module.lyrics.source.LunabeatClient
import dev.amenhancer.module.lyrics.source.HttpLyricTransport
import dev.amenhancer.module.lyrics.source.FileLunabeatCatalogCache
import dev.amenhancer.module.lyrics.source.AutoLyricsSourceResolver
import dev.amenhancer.module.lyrics.source.AmllTtmlClient
import dev.amenhancer.module.lyrics.source.AmLyricsClient
import android.app.Application
import dev.amenhancer.module.config.EmbeddedConfigurationSession
import dev.amenhancer.module.config.EmbeddedContentManager
import dev.amenhancer.module.config.HostPrivateEmbeddedStorage
import dev.amenhancer.module.lyrics.CustomLyricsFilePolicy
import dev.amenhancer.module.lyrics.CustomLyricsDraft
import dev.amenhancer.module.lyrics.CustomLyricsSaveResult
import dev.amenhancer.module.lyrics.TtmlInputPolicy
import dev.amenhancer.module.lyrics.CustomLyricsUpdateResult
import dev.amenhancer.module.lyrics.CustomLyricsUpdateSources
import dev.amenhancer.module.model.CustomLyricsSources
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.nio.file.AtomicMoveNotSupportedException
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.util.LinkedHashMap
import java.util.concurrent.Executor
import java.util.concurrent.ArrayBlockingQueue
import java.util.concurrent.RejectedExecutionException
import java.util.concurrent.ThreadPoolExecutor
import java.util.concurrent.TimeUnit
import org.json.JSONArray

private const val AUTO_CACHE_DIRECTORY = "ampp-auto-lyrics"

internal fun createAutoLyricsRuntime(
    application: Application,
    suppressedIds: Set<Long> = emptySet(),
): AutoLyricsRuntime {
    val root = File(application.filesDir, AUTO_CACHE_DIRECTORY)
    val lyricTransport = HttpLyricTransport(
        connectTimeoutMs = 4_000,
        readTimeoutMs = 8_000,
        maxResponseBytes = TtmlInputPolicy.MAX_TTML_BYTES,
    )
    val indexTransport = HttpLyricTransport(
        connectTimeoutMs = 4_000,
        readTimeoutMs = 8_000,
        maxResponseBytes = LunabeatClient.INDEX_MAX_BYTES,
    )
    val lunabeat = LunabeatClient(
        indexTransport = indexTransport,
        lyricsTransport = lyricTransport,
        cache = FileLunabeatCatalogCache(File(root, "lunabeat")),
    )
    val amll = AmllTtmlClient(lyricTransport)
    val amLyrics = AmLyricsClient(lyricTransport)
    val resolver = AutoLyricsSourceResolver.fixed(
        amll = amll,
        amLyrics = amLyrics,
        lunabeat = lunabeat,
    )
    val cache = FileAutoLyricsCache(root)
    val configuredContent = EmbeddedContentManager(
        session = EmbeddedConfigurationSession(HostPrivateEmbeddedStorage(application)),
    )
    val publisher = AutoLyricsPublisher { appleMusicId, candidate ->
        val existing = runCatching {
            configuredContent.listLyrics().firstOrNull { it.appleMusicId == appleMusicId }
        }.getOrNull()
        when {
            existing != null && existing.enabled -> AutoLyricsPublishResult.ALREADY_CONFIGURED
            existing != null -> AutoLyricsPublishResult.FAILED
            else -> {
                val displayName = candidate.displayName
                    ?.takeIf(String::isNotBlank)
                    ?: ModuleText.AUTO_CACHED_LYRICS_NAME.text(appleMusicId)
                when (
                    runCatching {
                        configuredContent.saveLyrics(
                            CustomLyricsDraft(
                                appleMusicId = appleMusicId,
                                displayName = displayName,
                                ttml = candidate.ttml,
                                source = candidate.source,
                            ),
                        )
                    }.getOrNull()
                ) {
                    is CustomLyricsSaveResult.Saved -> AutoLyricsPublishResult.PUBLISHED
                    else -> AutoLyricsPublishResult.FAILED
                }
            }
        }
    }
    val executor = ThreadPoolExecutor(
        1,
        1,
        0L,
        TimeUnit.MILLISECONDS,
        // Keep only one queued task; a newer song must replace stale work.
        ArrayBlockingQueue(1),
        { runnable -> Thread(runnable, "ampp-auto-lyrics").apply { isDaemon = true } },
        ThreadPoolExecutor.DiscardOldestPolicy(),
    )
    runCatching {
        executor.execute {
            cache.cachedIds().forEach { appleMusicId ->
                if (appleMusicId in suppressedIds) return@forEach
                val candidate = cache.readCandidate(appleMusicId)
                    ?: return@forEach
                when (
                    runCatching {
                        publisher.publish(
                            appleMusicId,
                            candidate,
                        )
                    }.getOrDefault(AutoLyricsPublishResult.FAILED)
                ) {
                    AutoLyricsPublishResult.PUBLISHED,
                    AutoLyricsPublishResult.ALREADY_CONFIGURED,
                    -> cache.delete(appleMusicId)
                    AutoLyricsPublishResult.FAILED -> Unit
                }
            }
        }
    }
    val refreshExecutor = ThreadPoolExecutor(
        1, 1, 0L, TimeUnit.MILLISECONDS, ArrayBlockingQueue(1),
        { runnable -> Thread(runnable, "ampp-lyrics-refresh").apply { isDaemon = true } },
        ThreadPoolExecutor.AbortPolicy(),
    )
    val sources = CustomLyricsUpdateSources(
        fetchAmll = amll::fetch,
        loadAmLyricsIndex = amLyrics::fetchIndex,
        fetchAmLyricsTtml = amLyrics::fetchTtml,
        loadLunabeatCatalog = lunabeat::loadCatalog,
        fetchLunabeatTtml = lunabeat::fetch,
        fetchAutoCache = resolver::fetch,
    )
    return AutoLyricsRuntime(
        resolver = resolver,
        cache = cache,
        executor = executor,
        publisher = publisher,
        suppressedIds = suppressedIds,
        refreshExecutor = Executor { task ->
            // Old waiting visits have already been cancelled by the session's generation.
            refreshExecutor.queue.clear()
            refreshExecutor.execute(task)
        },
        closeRefresh = { refreshExecutor.shutdownNow() },
        refreshSong = refresh@{ appleMusicId, isCancelled ->
            if (isCancelled()) return@refresh null
            var previous = configuredContent.listLyrics().firstOrNull { it.appleMusicId == appleMusicId }
            if (previous == null) {
                // A restart may still have a temporary cache awaiting migration.
                val cached = cache.readCandidate(appleMusicId) ?: return@refresh null
                if (isCancelled()) return@refresh null
                if (publisher.publish(appleMusicId, cached) == AutoLyricsPublishResult.FAILED) return@refresh null
                cache.delete(appleMusicId)
                previous = configuredContent.listLyrics().firstOrNull { it.appleMusicId == appleMusicId }
            }
            val entry = previous ?: return@refresh null
            if (!entry.enabled || entry.source == CustomLyricsSources.MANUAL || isCancelled()) return@refresh null
            val result = configuredContent.updateSong(appleMusicId, sources, isCancelled)
            val updated = result as? CustomLyricsUpdateResult.Updated ?: run {
                if (result is CustomLyricsUpdateResult.Failed && !isCancelled()) {
                    ModernXposedRuntime.log("cached lyrics update failed id=$appleMusicId: ${result.message}")
                }
                return@refresh null
            }
            if (updated.failed > 0) {
                ModernXposedRuntime.log("cached lyrics update failed id=$appleMusicId: ${updated.issues.firstOrNull()?.message.orEmpty()}")
                return@refresh null
            }
            // Lunabeat can fall back to an older catalog, so don't log an authoritative 'unchanged'.
            if (updated.skipped > 0 || isCancelled()) return@refresh null
            // The native session skips identical keys; this also retries a formerly failed native parse.
            updated.manifest.entries.firstOrNull { it.appleMusicId == appleMusicId }
        },
    )
}

