package dev.amenhancer.module.hook

import dev.amenhancer.module.lyrics.source.LunabeatClient
import dev.amenhancer.module.lyrics.source.HttpLyricTransport
import dev.amenhancer.module.lyrics.source.FileLunabeatCatalogCache
import dev.amenhancer.module.lyrics.source.AutoLyricsSourceResolver
import dev.amenhancer.module.lyrics.source.AmllTtmlClient
import dev.amenhancer.module.lyrics.source.AmLyricsClient
import dev.amenhancer.module.lyrics.source.AutoLyricsSource
import android.app.Application
import dev.amenhancer.module.CurrentSongDetails
import dev.amenhancer.module.config.EmbeddedConfigurationSession
import dev.amenhancer.module.config.EmbeddedContentManager
import dev.amenhancer.module.config.HostPrivateEmbeddedStorage
import dev.amenhancer.module.lyrics.CustomLyricsFilePolicy
import dev.amenhancer.module.lyrics.CustomLyricsDraft
import dev.amenhancer.module.lyrics.CustomLyricsSaveResult
import dev.amenhancer.module.lyrics.TtmlInputPolicy
import dev.amenhancer.module.lyrics.online.NeSessionStore
import dev.amenhancer.module.lyrics.online.OnlineLyricSelection
import dev.amenhancer.module.lyrics.online.OnlineLyricSourcePolicy
import dev.amenhancer.module.lyrics.online.OnlineTranslationEnrichment
import dev.amenhancer.module.lyrics.online.SearchLyricsSource
import dev.amenhancer.module.model.CustomLyricsSources
import dev.amenhancer.module.model.ModuleSettings
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

/**
 * The single online chain plus the composite that owns it. One object serves
 * both opt-ins: the supplement prepends it to the resolver, while the
 * translation pass calls its translation-required variant directly.
 */
internal data class OnlineLyricsChain(
    val leading: List<AutoLyricsSource>,
    val composite: CompositeOnlineSearchAutoLyricsSource?,
)

/**
 * Builds the composite entry when either online opt-in needs it. The master
 * supplement toggle decides whether it is prepended to the resolver; the
 * translation toggle only borrows the composite, so with both off no provider
 * is constructed at all.
 */
internal fun buildOnlineLyricsChain(
    supplementEnabled: Boolean,
    translationEnabled: Boolean,
    selection: OnlineLyricSelection,
    currentTrack: () -> CurrentSongDetails? = { null },
    providerFor: (String) -> SearchLyricsSource?,
): OnlineLyricsChain {
    if (!supplementEnabled && !translationEnabled) return OnlineLyricsChain(emptyList(), null)
    val providers = selection.sources.mapNotNull { sourceId ->
        providerFor(sourceId)?.let { OnlineLyricProvider(sourceId, it) }
    }
    if (providers.isEmpty()) return OnlineLyricsChain(emptyList(), null)
    val composite = CompositeOnlineSearchAutoLyricsSource.create(
        mode = selection.mode,
        providers = providers,
        currentTrack = currentTrack,
    )
    return OnlineLyricsChain(
        leading = if (supplementEnabled) listOf(composite.autoLyricsSource()) else emptyList(),
        composite = composite,
    )
}

/**
 * Never throws: a failed enrichment must leave the displayed document
 * untouched. The composite supplies translation-bearing candidates only; the
 * pure policy decides whether the document needs one and merges the winner.
 */
internal fun translationEnricher(
    composite: CompositeOnlineSearchAutoLyricsSource,
    currentTrack: () -> CurrentSongDetails?,
): (Long, String) -> String? = { appleMusicId, rawTtml ->
    runCatching {
        OnlineTranslationEnrichment.enrich(
            ttml = rawTtml,
            candidates = composite.fetchTranslationCandidates(appleMusicId),
            translationRequested = true,
            durationMs = currentTrack()?.durationMs ?: 0L,
        )?.ttml
    }.getOrNull()
}

internal fun createAutoLyricsRuntime(
    application: Application,
    suppressedIds: Set<Long> = emptySet(),
    onlineLyricsSupplementEnabled: Boolean = false,
    onlineLyricsTranslationEnabled: Boolean = false,
    onlineLyricsSelection: OnlineLyricSelection =
        OnlineLyricSourcePolicy.resolve(ModuleSettings()),
    currentTrack: () -> CurrentSongDetails? = { null },
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
    val sessionStore: NeSessionStore by lazy { SharedPreferencesNeSessionStore(application) }
    val chain = buildOnlineLyricsChain(
        supplementEnabled = onlineLyricsSupplementEnabled,
        translationEnabled = onlineLyricsTranslationEnabled,
        selection = onlineLyricsSelection,
        currentTrack = currentTrack,
    ) { sourceId ->
        onlineLyricProviderFor(
            sourceId = sourceId,
            transport = lyricTransport,
            sessionStore = sessionStore,
        )
    }
    val leading = chain.leading
    val resolver = AutoLyricsSourceResolver.fixed(
        amll = AmllTtmlClient(lyricTransport),
        amLyrics = AmLyricsClient(lyricTransport),
        lunabeat = lunabeat,
        leading = leading,
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
                    ?: "自动缓存歌词 · $appleMusicId"
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
                val ttml = cache.read(appleMusicId)
                    ?.takeIf(AutoLyricsTimingPolicy::isAcceptableAtSeam)
                    ?: return@forEach
                when (
                    runCatching {
                        publisher.publish(
                            appleMusicId,
                            AutoLyricsCandidate(CustomLyricsSources.AUTO_CACHE, ttml),
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
    return AutoLyricsRuntime(
        resolver = resolver,
        cache = cache,
        executor = executor,
        publisher = publisher,
        suppressedIds = suppressedIds,
        translationEnricher = chain.composite
            ?.takeIf { onlineLyricsTranslationEnabled }
            ?.let { composite -> translationEnricher(composite, currentTrack) },
    )
}

