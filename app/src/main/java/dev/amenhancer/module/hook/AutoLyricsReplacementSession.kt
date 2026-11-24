package dev.amenhancer.module.hook

import dev.amenhancer.module.ModuleConstants
import dev.amenhancer.module.i18n.ModuleText

import dev.amenhancer.module.lyrics.source.LunabeatClient
import dev.amenhancer.module.lyrics.source.HttpLyricTransport
import dev.amenhancer.module.lyrics.source.AutoLyricsSourceResolver
import dev.amenhancer.module.lyrics.source.AmllTtmlClient
import dev.amenhancer.module.lyrics.source.AmLyricsClient
import dev.amenhancer.module.lyrics.source.AutoLyricsSource
import dev.amenhancer.module.lyrics.FileAmLyricsIndexCache
import dev.amenhancer.module.lyrics.FileLyricPresenceCache
import android.app.Application
import android.os.Handler
import android.os.Looper
import dev.amenhancer.module.lyrics.FilePronunciationBoundaryCache
import dev.amenhancer.module.lyrics.online.PronunciationBoundaryStore
import dev.amenhancer.module.CurrentSongDetails
import dev.amenhancer.module.config.EmbeddedConfigurationSession
import dev.amenhancer.module.config.EmbeddedContentManager
import dev.amenhancer.module.config.cachedSongDisplayName
import dev.amenhancer.module.config.isCompleteCacheSongName
import dev.amenhancer.module.lyrics.TtmlInputPolicy
import dev.amenhancer.module.lyrics.online.NativeLyricOverlayStore
import dev.amenhancer.module.lyrics.online.NeSessionStore
import dev.amenhancer.module.lyrics.online.OnlineLyricSelection
import dev.amenhancer.module.lyrics.online.OnlineLyricSourcePolicy
import dev.amenhancer.module.lyrics.online.OnlineTranslationEnrichment
import dev.amenhancer.module.lyrics.online.SearchLyricsSource
import dev.amenhancer.module.lyrics.online.TrackScopedDiagnostics
import dev.amenhancer.module.lyrics.CustomLyricsUpdateResult
import dev.amenhancer.module.lyrics.CustomLyricsUpdateSources
import dev.amenhancer.module.model.CustomLyricsSources
import dev.amenhancer.module.model.ModuleSettings
import dev.amenhancer.module.lyrics.FileLlmLyricsCache
import dev.amenhancer.module.lyrics.LlmLyricsClient
import dev.amenhancer.module.lyrics.LlmLyricsConfig
import dev.amenhancer.module.lyrics.LlmLyricsPhase
import dev.amenhancer.module.lyrics.LlmLyricsDocument
import dev.amenhancer.module.lyrics.LlmTransliterationDocument
import dev.amenhancer.module.lyrics.LyricsPlaybackSources
import dev.amenhancer.module.lyrics.source.LunabeatCatalogCache
import java.io.File
import java.util.concurrent.Executor
import java.util.concurrent.ArrayBlockingQueue
import java.util.concurrent.RejectedExecutionException
import java.util.concurrent.ScheduledThreadPoolExecutor
import java.util.concurrent.ThreadPoolExecutor
import java.util.concurrent.TimeUnit

/** The AM-Lyrics index cache lives next to the other private automatic-lyrics state. */
private const val AM_LYRICS_CACHE_DIRECTORY = "ampp-amlyrics"

/** The presence index the self-hosted mirror publishes, also private automatic-lyrics state. */
private const val LYRIC_PRESENCE_CACHE_DIRECTORY = "ampp-lyrics-presence"

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
 *
 * [displayedTtml] reads the raw document Apple is currently showing for a
 * track; the composite uses it to skip the search path entirely for a document
 * that already carries timing.
 */
internal fun buildOnlineLyricsChain(
    supplementEnabled: Boolean,
    translationEnabled: Boolean,
    selection: OnlineLyricSelection,
    currentTrack: () -> CurrentSongDetails? = { null },
    diagnostic: (String) -> Unit = {},
    displayedTtml: (Long) -> String? = { null },
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
        diagnostic = diagnostic,
        displayedTtml = displayedTtml,
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
 * [logger] receives the bounded per-track decision lines through the module's
 * existing log channel.
 *
 * The single 「补全歌词翻译与发音」 opt-in now drives the **translation** lane only:
 * the third-party pronunciation lane has been removed, so a provider's
 * romanization column is read past and never published. Apple's own pronunciation
 * is delivered by the native lyric-model hooks, and a song Apple itself gives no
 * pronunciation shows no romanization at all.
 *
 * The `rawTtml` this receives is Apple's own parsed document, so the lane only
 * runs once Apple has parsed the displayed document — in practice once the
 * lyrics view for the track has been on screen. That is the product constraint
 * the capture seam exists for: the trigger is the capture, so a song that never
 * shows its lyrics leaves this lane idle instead of searching on every change
 * and finding nothing to merge into.
 */
internal fun translationEnricher(
    composite: CompositeOnlineSearchAutoLyricsSource,
    currentTrack: () -> CurrentSongDetails?,
    logger: (String) -> Unit = {},
    /**
     * Timing-keyed native-model overlay. On success the merged per-line
     * translations are written here so the host's lyric-model getters can deliver
     * the translation lane even when Apple renders no track from the document.
     */
    overlay: NativeLyricOverlayStore? = null,
    /**
     * Called with the merged document this enricher produced, once, just before it is returned. It is
     * how the boundary planner sees the enrichment path — the document that lands on screen — rather
     * than only the LLM fallback's.
     */
    onDocument: ((Long, String) -> Unit)? = null,
): (Long, String) -> String? {
    val scoped = TrackScopedDiagnostics(logger)
    return { appleMusicId, rawTtml ->
        logger("bndry path=enricher entered song=" + appleMusicId)
        android.util.Log.i("AMPP-Bndry", "bndry path=enricher entered song=" + appleMusicId)
        runCatching {
            OnlineTranslationEnrichment.enrich(
                ttml = rawTtml,
                candidates = composite.fetchTranslationCandidates(appleMusicId),
                translationRequested = true,
                durationMs = currentTrack()?.durationMs ?: 0L,
                appleMusicId = appleMusicId,
                diagnostic = { line -> scoped.log(appleMusicId, line) },
            )?.let { outcome ->
                overlay?.update(
                    songId = appleMusicId.toString(),
                    lines = outcome.lines,
                    translationSource = outcome.translationSource,
                )
                onDocument?.invoke(appleMusicId, outcome.ttml)
                outcome.ttml
            }
        }.getOrNull()
    }
}

internal fun createAutoLyricsRuntime(
    application: Application,
    content: EmbeddedContentManager,
    lunabeatCatalogCache: LunabeatCatalogCache,
    currentSong: CurrentSongIdentityCache,
    suppressedIds: Set<Long> = emptySet(),
    onlineLyricsSupplementEnabled: Boolean = false,
    onlineLyricsTranslationEnabled: Boolean = false,
    hideMandarinPronunciation: Boolean = false,
    genreFor: (Long) -> String? = { null },
    onlineLyricsSelection: OnlineLyricSelection =
        OnlineLyricSourcePolicy.resolve(ModuleSettings()),
    currentTrack: () -> CurrentSongDetails? = { null },
    logger: (String) -> Unit = {},
    displayedTtml: (Long) -> String? = { null },
    nativeLyricOverlay: NativeLyricOverlayStore = NativeLyricOverlayStore(),
    configuration: () -> ModuleSettings = { ModuleSettings() },
): AutoLyricsRuntime {
    val sourceStore = content.sourceCache
    val failures = ThreadLocal<dev.amenhancer.module.lyrics.source.LyricFetchFailure?>()
    val lyricTransport = HttpLyricTransport(
        connectTimeoutMs = 4_000,
        readTimeoutMs = 8_000,
        maxResponseBytes = TtmlInputPolicy.MAX_TTML_BYTES,
        onFailure = { failures.set(it) },
    )
    val indexTransport = HttpLyricTransport(
        connectTimeoutMs = 4_000,
        readTimeoutMs = 8_000,
        maxResponseBytes = LunabeatClient.INDEX_MAX_BYTES,
        onFailure = { failures.set(it) },
    )
    val lunabeat = LunabeatClient(
        indexTransport = indexTransport,
        lyricsTransport = lyricTransport,
        cache = lunabeatCatalogCache,
        onFailure = { failures.set(it) },
    )
    // A self-hosted mirror root from the settings is tried before every public host: the same
    // 50 KB file measured ~65 ms locally against 470-540 ms cross-border, so it is what an
    // ordinary lookup normally pays. Read once at assembly; the settings page tells the user a
    // restart applies a change, exactly like the other runtime switches.
    val customSourceBaseUrl = ModuleSettings.normalizeCustomLyricsSourceBaseUrl(
        runCatching { configuration().customLyricsSourceBaseUrl }.getOrDefault(""),
    ).takeIf(String::isNotEmpty)
    val customSourceLabel = if (customSourceBaseUrl == null) "public" else "custom"
    // The AM-Lyrics index is ~55 KB and changes rarely, so it is served from disk and refreshed
    // in the background instead of being downloaded again for every track.
    val amLyricsIndexScheduler = ScheduledThreadPoolExecutor(
        1,
        { runnable -> Thread(runnable, "ampp-amlyrics-index").apply { isDaemon = true } },
    )
    val amLyricsIndexCache = FileAmLyricsIndexCache(
        directory = File(application.filesDir, AM_LYRICS_CACHE_DIRECTORY),
        transport = indexTransport,
        // Same walk as the documents themselves, so a self-hosted mirror serves both.
        indexUrls = { AmLyricsClient(indexTransport, customSourceBaseUrl).indexUrls() },
        executor = amLyricsIndexScheduler,
    )
    // The presence index (~48 KB, ~13 KB gzipped) says which bundled repository carries a song.
    // Its subject is the miss, not the hit: without it a song no repository carries still walks
    // every endpoint of every repository before the translation lane is attempted. Only the
    // self-hosted mirror publishes it, so with a blank root the cache stays disabled and answers
    // "unknown", which is exactly the previous retry-everything behaviour.
    val lyricPresenceCache = FileLyricPresenceCache(
        directory = File(application.filesDir, LYRIC_PRESENCE_CACHE_DIRECTORY),
        transport = indexTransport,
        customRoot = customSourceBaseUrl,
        executor = amLyricsIndexScheduler,
    )
    val amll = AmllTtmlClient(lyricTransport, customSourceBaseUrl)
    val amLyrics = AmLyricsClient(lyricTransport, customSourceBaseUrl, amLyricsIndexCache)
    // The periodic TTL check: every 6h, look at the cached copy and refresh only when it is
    // older than the 12h TTL. Daemon thread, shut down with the runtime below. The presence
    // index shares the thread and the policy - it is the same 12h copy of a self-hosted file.
    runCatching {
        amLyricsIndexScheduler.scheduleWithFixedDelay(
            { runCatching { amLyricsIndexCache.refreshIfStale() } },
            0L,
            FileAmLyricsIndexCache.PERIODIC_CHECK_MS,
            TimeUnit.MILLISECONDS,
        )
        amLyricsIndexScheduler.scheduleWithFixedDelay(
            { runCatching { lyricPresenceCache.refreshIfStale() } },
            0L,
            FileLyricPresenceCache.PERIODIC_CHECK_MS,
            TimeUnit.MILLISECONDS,
        )
    }
    val sessionStore: NeSessionStore by lazy { SharedPreferencesNeSessionStore(application) }
    val chain = buildOnlineLyricsChain(
        supplementEnabled = onlineLyricsSupplementEnabled,
        translationEnabled = onlineLyricsTranslationEnabled,
        selection = onlineLyricsSelection,
        currentTrack = currentTrack,
        diagnostic = logger,
        displayedTtml = displayedTtml,
    ) { sourceId ->
        onlineLyricProviderFor(
            sourceId = sourceId,
            transport = lyricTransport,
            sessionStore = sessionStore,
        )
    }
    // Fallback-only: the fixed providers (AMLL, LunaBeat, the user's own repository) carry
    // word-level curated lyrics and must win whenever they have the song, so the online search
    // chain is appended *after* them.  Prepending it -- the previous behaviour -- let a line-timed
    // online result pre-empt a word-timed AMLL one and visibly downgraded songs the bundled sources
    // already had.
    val onlineSources = chain.leading
    val resolver = AutoLyricsSourceResolver.fixed(
        amll = amll,
        amLyrics = amLyrics,
        lunabeat = lunabeat,
        trailing = onlineSources,
        // Null while unknown keeps the old "ask every repository" path; a loaded index that does
        // not name the track skips the bundled repositories without a single request.
        presence = lyricPresenceCache,
    )

    fun cacheSongName(title: String?, artist: String?): String? {
        val songTitle = title?.trim()?.takeIf(String::isNotEmpty) ?: return null
        return listOfNotNull(songTitle, artist?.trim()?.takeIf(String::isNotEmpty)).joinToString(" - ")
    }

    fun AutoLyricsCandidate.withSongTitle(appleMusicId: Long, existingName: String? = null): AutoLyricsCandidate {
        fun usableName(name: String?): String? =
            name?.trim()?.takeIf { it.isNotEmpty() && it != source }

        val ownName = usableName(displayName)
        val previousName = usableName(existingName)
            ?: sourceStore.entryFor(appleMusicId, source)?.cachedSongDisplayName()
        val details = currentSong.current()?.details?.takeIf { it.appleMusicId == appleMusicId }
        val currentName = cacheSongName(details?.title, details?.artist)
        val resolvedName = when {
            ownName?.isCompleteCacheSongName() == true -> ownName
            previousName?.isCompleteCacheSongName() == true -> previousName
            currentName != null -> currentName
            ownName != null -> ownName
            else -> previousName
        }
        return if (resolvedName == null) this else copy(displayName = resolvedName)
    }

    fun lookupCacheSongNames(
        appleMusicIds: List<Long>,
        isCancelled: () -> Boolean,
    ): Map<Long, String> {
        failures.remove()
        return try {
            val requested = appleMusicIds.asSequence()
                .filter { it > 0L }
                .toCollection(linkedSetOf())
            if (requested.isEmpty() || isCancelled()) return emptyMap()

            val names = linkedMapOf<Long, String>()
            currentSong.current()?.details?.let { details ->
                if (details.appleMusicId in requested) {
                    cacheSongName(details.title, details.artist)
                        ?.takeIf(String::isCompleteCacheSongName)
                        ?.let { names[details.appleMusicId] = it }
                }
            }

            var remaining = requested - names.keys
            if (remaining.isNotEmpty() && !isCancelled()) {
                // Our mirror and the 12h disk copy must stay in this path: a bare client reads
                // GitHub raw directly, so a blocked network loses the backfill and every cold
                // start re-downloads the index.
                val index = runCatching { amLyricsIndexCache.index() ?: amLyrics.fetchIndex() }.getOrNull()
                if (index != null) {
                    for (entry in index.entries) {
                        if (isCancelled()) return names
                        for (appleMusicId in entry.allAppleMusicIds) {
                            if (appleMusicId in remaining &&
                                entry.displayName.isCompleteCacheSongName()
                            ) {
                                names.putIfAbsent(appleMusicId, entry.displayName)
                            }
                        }
                    }
                }
                remaining = requested - names.keys
            }

            if (remaining.isNotEmpty() && !isCancelled()) {
                val catalog = runCatching { lunabeat.loadCatalog() }.getOrNull()
                if (catalog != null) {
                    for (song in catalog.songs) {
                        if (isCancelled()) return names
                        for (appleMusicId in song.appleMusicIds) {
                            if (appleMusicId in remaining && song.displayName.isCompleteCacheSongName()) {
                                names.putIfAbsent(appleMusicId, song.displayName)
                            }
                        }
                    }
                }
            }
            names
        } finally {
            failures.remove()
        }
    }
    val cache = object : AutoLyricsCache {
        override fun readCandidate(appleMusicId: Long, source: String): AutoLyricsCandidate? =
            sourceStore.readCandidate(appleMusicId, source)
        override fun writeCandidateIfCurrent(appleMusicId: Long, candidate: AutoLyricsCandidate, generation: Long): Boolean =
            sourceStore.writeCandidate(appleMusicId, candidate.withSongTitle(appleMusicId), generation)
        override fun generation(): Long = sourceStore.generation()
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
    // Every path that publishes a document of ours hands it here, so the planner sees enrichment and
    // replaced lyrics too, not just the LLM fallback. The session it forwards to is created further
    // down, so the slot is null until then and a call in that window is a no-op.
    var boundaryOnDocument: ((Long, String) -> Unit)? = null

    // One shape for every hand-off, so "nothing logged at all" and "triggered but dropped" are
    // distinguishable on logcat without reading the Xposed log.
    fun triggerBoundary(path: String, appleMusicId: Long, ttml: String) {
        android.util.Log.i(
            "AMPP-Bndry",
            "bndry trigger called path=" + path + " slot=" + (if (boundaryOnDocument == null) "null" else "set"),
        )
        android.util.Log.i(
            "AMPP-Bndry",
            "bndry trigger path=" + path + " song=" + appleMusicId + " ttml=" + ttml.length,
        )
        val slot = boundaryOnDocument
        if (slot == null) {
            android.util.Log.i("AMPP-Bndry", "bndry trigger dropped path=" + path + " reason=slot-null")
            return
        }
        runCatching { slot(appleMusicId, ttml) }.onFailure { error ->
            android.util.Log.i(
                "AMPP-Bndry",
                "bndry trigger failed reason=" + (error.message ?: error.javaClass.simpleName),
            )
        }
    }
    // TODO(phase3): the fork used to publish every cached candidate into the configured
    // lyrics index at start-up (AutoLyricsPublisher). Upstream 1.6.9 publishes into the
    // per-source store instead, so that sweep has no consumer left; re-point it at
    // sourceStore once the two publication paths are reconciled.
    val refreshExecutor = ThreadPoolExecutor(
        1, 1, 0L, TimeUnit.MILLISECONDS, ArrayBlockingQueue(1),
        { runnable -> Thread(runnable, "ampp-lyrics-refresh").apply { isDaemon = true } },
        ThreadPoolExecutor.AbortPolicy(),
    )
    val sources = CustomLyricsUpdateSources(
        fetchAmll = amll::fetch,
        // The user-initiated update pass reads the cached index first and only fetches when the
        // cache is still cold; otherwise it would re-download 55 KB on every tap.
        loadAmLyricsIndex = { amLyricsIndexCache.index() ?: amLyrics.fetchIndex() },
        fetchAmLyricsTtml = amLyrics::fetchTtml,
        loadLunabeatCatalog = lunabeat::loadCatalog,
        fetchLunabeatTtml = lunabeat::fetch,
        fetchAutoCache = { id ->
            resolver.fetch(id, ModuleSettings.normalizeAutomaticLyricsSourceOrder(
                configuration().automaticLyricsSourceOrder,
            ).filterNot { it == CustomLyricsSources.AM_NATIVE })
        },
    )
    // One overlay for the active track. The optional enricher writes it and the
    // independent native-lyrics target reads it back, so Apple's own lanes still
    // work when online completion is disabled.
    val enricher = chain.composite
        ?.takeIf { onlineLyricsTranslationEnabled }
        ?.let { composite ->
            translationEnricher(
                composite = composite,
                currentTrack = currentTrack,
                logger = logger,
                overlay = nativeLyricOverlay,
                onDocument = { appleMusicId, ttml -> triggerBoundary("enricher", appleMusicId, ttml) },
            )
        }
    logger(
        "online-translation runtime supplement=$onlineLyricsSupplementEnabled " +
            "translation=$onlineLyricsTranslationEnabled " +
            "applePronunciationOnly=true " +
            "customSource=$customSourceLabel " +
            "hideMandarinPinyin=$hideMandarinPronunciation " +
            "sources=${onlineLyricsSelection.sources.joinToString(",")} " +
            "enricher=${enricher != null} " +
            "presence=${lyricPresenceCache.enabled} " +
            "build=${ModuleConstants.BUILD_TAG}",
    )
    refreshExecutor.execute { sourceStore.prepare() }
    val llmExecutor = ThreadPoolExecutor(
        2, 2, 0L, TimeUnit.MILLISECONDS, ArrayBlockingQueue(4),
        { task -> Thread(task, "ampp-llm-lyrics").apply { isDaemon = true } },
        ThreadPoolExecutor.AbortPolicy(),
    )
    val llmCache = FileLlmLyricsCache(File(application.filesDir, "ampp-llm-lyrics"))
    val llmClient = LlmLyricsClient()
    // Boundary planning rides the same channel: one request per (song, kana), cached on disk, and
    // never on the playback path — a lyric row only ever reads the store.
    // Nothing in this block may throw during module start-up: every step is fallible on its own and
    // a failure only disables the chain (logged once), never the module.
    val boundaryStore = runCatching { PronunciationBoundaryStore() }.getOrNull()
    val boundaryCache = runCatching {
        FilePronunciationBoundaryCache(File(application.filesDir, "ampp-llm-boundary"))
    }.onFailure { error ->
        android.util.Log.i("AMPP-Bndry", "bndry init failed reason=" + (error.message ?: "cache"))
    }.getOrNull()
    val boundaryExecutor = runCatching {
    ThreadPoolExecutor(
        1, 1, 0L, TimeUnit.MILLISECONDS, ArrayBlockingQueue(1),
        { task -> Thread(task, "ampp-llm-boundary").apply { isDaemon = true } },
        ThreadPoolExecutor.DiscardOldestPolicy(),
    )
    }.getOrNull()
    val boundarySession = runCatching {
    PronunciationBoundarySession(
        runtime = PronunciationBoundaryRuntime(
            configuration = {
                configuration().let { settings ->
                    if (!settings.llmPronunciationBoundaryEnabled) return@let null
                    // The same snapshot the translation call builds. A bare constructor here left
                    // thinkingFormat/effort at their defaults, so the request carried neither
                    // `thinking.type` nor `reasoning_effort` and the server fell back to its own
                    // reasoning default (minutes per answer) even with 「推理」 switched off.
                    LlmLyricsConfig.fromSettings(settings).takeIf { it.isConfigured }
                }
            },
            cacheRead = { id, hash, counts, config -> boundaryCache?.read(id, hash, counts, config) },
            cacheWrite = { id, hash, counts, config, lines -> boundaryCache?.write(id, hash, counts, config, lines) },
            request = { config, request, cancelled ->
                // The session collapses every transport failure into "request-failed", which leaves a
                // failed merge indistinguishable from a wrong endpoint, a rejected key or a timeout.
                // The client already names the reason and the HTTP status; surface both, and the host
                // and model (never the key) so a misconfigured base URL is obvious from one log line.
                android.util.Log.i(
                    "AMPP-Bndry",
                    "bndry transport endpoint=" + (
                        config.endpoint?.let { runCatching { java.net.URI(it).host }.getOrNull() }
                            ?: "invalid"
                        ) + " model=" + config.normalizedModel,
                )
                llmClient.segmentBoundaries(config, request, cancelled) { reason, status ->
                    android.util.Log.i(
                        "AMPP-Bndry",
                        "bndry transport failed reason=" + reason + " status=" + (status ?: "-"),
                    )
                }
            },
            executor = Executor { task ->
                boundaryExecutor?.let { executor ->
                    executor.queue.clear()
                    executor.execute(task)
                }
            },
            onInstalled = {
                Handler(Looper.getMainLooper()).post {
                    LyricsPronunciationCentringBridge.reapplyObservedRows()
                }
            },
            // Xposed's log never reaches logcat, and the boundary chain is the one thing users are
            // asked to trace: mirror every line with a fixed tag. Low volume (one or two per song).
            log = { line ->
                logger(line)
                android.util.Log.i("AMPP-Bndry", line)
            },
            close = { boundaryExecutor?.shutdownNow() },
        ),
        store = boundaryStore ?: error("no store"),
    )
    }.onFailure { error ->
        android.util.Log.i("AMPP-Bndry", "bndry init failed reason=" + (error.message ?: "session"))
    }.getOrNull()
    // The splice's only door to a plan: a lookup in memory, never a request.
    runCatching {
        LyricsPronunciationCentringBridge.setBoundaryPlans { lineKey -> boundaryStore?.planFor(lineKey) }
    }
    run {
        // One line at start-up: whether the chain exists at all, and what the two gates said then.
        val settings = configuration()
        val snapshot = LlmLyricsConfig.fromSettings(settings)
        val configured = snapshot.isConfigured
        android.util.Log.i(
            "AMPP-Bndry",
            "bndry ready enabled=" + settings.llmPronunciationBoundaryEnabled +
                " configured=" + configured +
                " format=" + snapshot.thinkingFormat +
                " effort=" + snapshot.effectiveThinkingEffort.storageValue +
                " thinking=" + snapshot.effectiveThinkingEnabled,
        )
    }
    // The custom-lyrics quick path injects an already-configured document without ever entering the
    // publish callbacks above, so the host reports every document it hands to Apple through here.
    runCatching {
        LyricsPronunciationCentringBridge.setDocumentObserver { appleMusicId, ttml ->
            triggerBoundary("native-hook", appleMusicId, ttml)
        }
    }
    // The slot holds the terminal action, never triggerBoundary: triggerBoundary ends by calling the
    // slot, so pointing it back at the helper is an unbounded self-recursion (it overflowed the stack
    // on device). Here the chain stops at the session.
    boundaryOnDocument = { appleMusicId, ttml ->
        runCatching { boundarySession?.onDocument(appleMusicId, ttml) }.onFailure { error ->
            android.util.Log.i(
                "AMPP-Bndry",
                "bndry trigger failed reason=" + (error.message ?: error.javaClass.simpleName),
            )
        }
        Unit
    }
    LyricsPlaybackRuntimeRegistry.attachLlmStatus(llmExecutor)
    fun failurePhase(reason: String, status: Int?): LlmLyricsPhase = when {
        status == 401 || status == 403 -> LlmLyricsPhase.AUTH_FAILED
        status == 429 -> LlmLyricsPhase.RATE_LIMITED
        reason == "timeout" -> LlmLyricsPhase.TIMED_OUT
        reason == "invalid_response" || reason == "response_limit" -> LlmLyricsPhase.INVALID_RESPONSE
        else -> LlmLyricsPhase.REQUEST_FAILED
    }
    val llm = LlmLyricsRuntime(
        configuration = {
            configuration().let { settings ->
                if (settings.llmLyricsTransliterationEnabled ||
                    (settings.customLyricsEnabled && settings.automaticLyricsEnabled && settings.llmLyricsEnabled)
                ) {
                    LlmLyricsConfig.fromSettings(settings)
                } else null
            }
        },
        translate = translate@{ request, isCancelled ->
            android.util.Log.i("AMPP-Bndry", "bndry path=llm entered song=" + request.appleMusicId)
            if (isCancelled()) return@translate null
            // Hand the document to the boundary planner once. It queues its own work and returns
            // immediately, so it cannot slow this fallback down.
            runCatching {
                android.util.Log.i(
                    "AMPP-Bndry",
                    "bndry trigger path=llm song=" + request.appleMusicId +
                        " ttml=" + request.originalTtml.length,
                )
                boundarySession?.onDocument(
                    request.appleMusicId,
                    request.originalTtml,
                    request.title,
                    request.artist,
                )
            }
            // Eligibility always precedes cache reads, including on later visits.
            val document = LlmLyricsDocument.prepare(request.originalTtml) ?: run {
                val metadata = TtmlTimingPolicy.metadataOf(request.originalTtml)
                request.reportStatus(when {
                    metadata.hasTranslation -> LlmLyricsPhase.EXISTS
                    metadata.language != null && !metadata.isForeign -> LlmLyricsPhase.NOT_NEEDED
                    else -> LlmLyricsPhase.UNSUPPORTED
                })
                return@translate null
            }
            if (isCancelled()) return@translate null
            llmCache.read(request.appleMusicId, request.originalTtml, request.config)?.let { cached ->
                request.reportStatus(LlmLyricsPhase.CACHED)
                return@translate cached.takeUnless { isCancelled() }
            }
            request.reportStatus(LlmLyricsPhase.GENERATING)
            val translations = llmClient.translate(
                config = request.config,
                lines = document.translationLines,
                title = request.title,
                artist = request.artist,
                sourceLanguage = document.sourceLanguage,
                onFailure = { reason, status -> request.reportStatus(failurePhase(reason, status)) },
                isCancelled = isCancelled,
            ) ?: return@translate null
            if (isCancelled()) return@translate null
            val translated = document.withTranslations(translations) ?: run {
                request.reportStatus(LlmLyricsPhase.INVALID_RESPONSE)
                return@translate null
            }
            if (isCancelled()) return@translate null
            translated.takeUnless { isCancelled() }
        },
        executor = Executor { task ->
            llmExecutor.execute(task)
        },
        close = {
            llmExecutor.shutdownNow()
            LyricsPlaybackRuntimeRegistry.detachLlmStatus(llmExecutor)
        },
        onPrepared = { request, translated ->
            // Persist only a host-accepted result; never use the custom-lyrics publisher.
            llmCache.write(
                request.appleMusicId, request.originalTtml, request.config, translated,
                expectedGeneration = request.cacheGeneration,
            )
        },
        translationEnabled = {
            configuration().let { it.customLyricsEnabled && it.automaticLyricsEnabled && it.llmLyricsEnabled }
        },
        transliterationEnabled = { configuration().llmLyricsTransliterationEnabled },
        transliterate = transliterate@{ request, isCancelled ->
            if (isCancelled()) return@transliterate null
            val document = LlmTransliterationDocument.prepare(request.originalTtml) ?: run {
                request.reportStatus(if (LlmTransliterationDocument.hasPronunciation(request.originalTtml))
                    LlmLyricsPhase.EXISTS else LlmLyricsPhase.UNSUPPORTED)
                return@transliterate null
            }
            if (document.isLatinOnly) {
                request.reportStatus(LlmLyricsPhase.NOT_NEEDED)
                return@transliterate null
            }
            if (isCancelled()) return@transliterate null
            llmCache.read(request.appleMusicId, request.originalTtml, request.config, kind = "transliteration")
                ?.let {
                    request.reportStatus(LlmLyricsPhase.CACHED)
                    return@transliterate it.takeUnless { isCancelled() }
                }
            request.reportStatus(LlmLyricsPhase.GENERATING)
            llmClient.transliterate(
                config = request.config,
                document = document,
                title = request.title,
                artist = request.artist,
                onFailure = { reason, status -> request.reportStatus(failurePhase(reason, status)) },
                isCancelled = isCancelled,
            )?.takeUnless { isCancelled() }
        },
        onTransliterationPrepared = { request, ttml ->
            llmCache.write(
                request.appleMusicId, request.originalTtml, request.config, ttml,
                kind = "transliteration", expectedGeneration = request.cacheGeneration,
            )
        },
        discardPending = { llmExecutor.queue.clear() },
        onStatusChanged = { LyricsPlaybackRuntimeRegistry.publishLlmStatus(llmExecutor, it) },
        cacheGeneration = llmCache::generation,
    )
    val playbackExecutor = ThreadPoolExecutor(
        1, 1, 0L, TimeUnit.MILLISECONDS, ArrayBlockingQueue(8),
        { task -> Thread(task, "ampp-lyrics-controls").apply { isDaemon = true } },
        ThreadPoolExecutor.AbortPolicy(),
    )
    val playback = EmbeddedLyricsPlaybackRuntime(
        content = content,
        settings = configuration,
        executor = playbackExecutor,
        fetch = { id, source ->
            failures.remove()
            val result = resolver.fetchSelected(id, source) {
                failures.set(dev.amenhancer.module.lyrics.source.LyricFetchFailure.INVALID)
            }
            result?.withSongTitle(id) to failures.get().also { failures.remove() }
        },
        lookupCacheSongNames = ::lookupCacheSongNames,
        llmCache = llmCache,
    )
    LyricsPlaybackRuntimeRegistry.attach(playback)
    val songNameSubscription = currentSong.addListener { song ->
        val details = song?.details ?: return@addListener
        val artist = details.artist?.takeIf(String::isNotBlank) ?: return@addListener
        val name = cacheSongName(details.title, artist) ?: return@addListener
        playback.backfillCacheSongName(details.appleMusicId, name)
    }
    return AutoLyricsRuntime(
        resolver = resolver,
        cache = cache,
        executor = executor,
        llm = llm,
        suppressedIds = suppressedIds,
        translationEnricher = enricher,
        nativeLyricOverlay = nativeLyricOverlay,
        hideMandarinPinyin = hideMandarinPronunciation,
        genreFor = genreFor,
        playback = playback,
        refreshExecutor = Executor { task ->
            refreshExecutor.queue.clear()
            refreshExecutor.execute(task)
        },
        closeRefresh = {
            songNameSubscription.close()
            refreshExecutor.shutdownNow()
            amLyricsIndexScheduler.shutdownNow()
            playbackExecutor.shutdownNow()
            executor.shutdownNow()
            playback.close()
        },
        refreshSong = refresh@{ appleMusicId, isCancelled ->
            val source = playback.snapshot(appleMusicId).activeSource?.takeIf {
                it == CustomLyricsSources.AMLL || it == CustomLyricsSources.AM_LYRICS || it == CustomLyricsSources.LUNABEAT
            } ?: return@refresh null
            val previous = sourceStore.entryFor(appleMusicId, source) ?: return@refresh null
            if (!previous.enabled || isCancelled()) return@refresh null
            val epoch = sourceStore.generation()
            val candidate = resolver.fetchSelected(appleMusicId, source) ?: return@refresh null
            val current = playback.snapshot(appleMusicId)
            if (isCancelled() || !current.isCurrentSong || current.activeSource != source ||
                (current.preferredSource == LyricsPlaybackSources.AUTOMATIC && !TtmlTimingPolicy.isWord(candidate.ttml))
            ) return@refresh null
            val latest = sourceStore.entryFor(appleMusicId, source) ?: return@refresh null
            // A display-name backfill does not invalidate a refresh of the same cached lyrics.
            if (latest.copy(displayName = previous.displayName) != previous ||
                !sourceStore.writeCandidate(appleMusicId, candidate.withSongTitle(appleMusicId, latest.displayName), epoch)
            ) return@refresh null
            sourceStore.entryFor(appleMusicId, source)?.takeIf { it.sha256 != previous.sha256 }
        },
    )
}
