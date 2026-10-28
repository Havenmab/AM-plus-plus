package dev.amenhancer.module.hook

import dev.amenhancer.module.ModuleConstants
import dev.amenhancer.module.i18n.ModuleText

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
import dev.amenhancer.module.lyrics.online.AppleLyricTtmlReader
import dev.amenhancer.module.lyrics.online.LyricLaneClassifier
import dev.amenhancer.module.lyrics.online.NativeLyricLine
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
import dev.amenhancer.module.lyrics.LlmLyricsDocument
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

/** What a per-lane diagnostic prints when no provider supplied that lane. */
private const val LANE_NONE = "none"

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
 * The single 「补全歌词翻译与发音」 opt-in fills the lanes of the document Apple is
 * showing. Which lane a piece of text belongs to is decided by
 * [LyricLaneClassifier], never by the provider's field name: QQ Music answers a
 * Japanese song's `trans` request with the **reading** (kana and romaji), and
 * writing that into the translation lane is what made the page render a
 * romanization under 翻译 while 「显示发音」 had nothing to show. A reading is
 * published as pronunciation only, Chinese as translation only, so the two
 * switches Apple derives from the overlay can never describe each other's lane.
 *
 * The provider of each lane is recorded with it, in the module's own source
 * vocabulary ([CustomLyricsSources]) that the lyrics-source sheet maps back to a
 * platform name. A lane no provider supplied — Apple's own preserved
 * romanization — keeps no provider, so the sheet never attributes Apple's text
 * to a platform.
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
     * Timing-keyed native-model overlay. On success the merged per-line lanes are
     * written here so the host's lyric-model getters can deliver them even when
     * Apple renders no track from the document.
     */
    overlay: NativeLyricOverlayStore? = null,
): (Long, String) -> String? {
    val scoped = TrackScopedDiagnostics(logger)
    return { appleMusicId, rawTtml ->
        runCatching {
            OnlineTranslationEnrichment.enrich(
                ttml = rawTtml,
                candidates = composite.fetchTranslationCandidates(appleMusicId),
                translationRequested = true,
                durationMs = currentTrack()?.durationMs ?: 0L,
                appleMusicId = appleMusicId,
                diagnostic = { line -> scoped.log(appleMusicId, line) },
            )?.let { outcome ->
                // A song whose source the user picked — including "restore Apple" — owns
                // its lanes: automatic completion must not write over it again.
                if (!AutoLyricsReplacementBridge.isLocked(appleMusicId)) {
                    val lines = lanePureLines(outcome.lines)
                    val published = publishLanes(overlay, appleMusicId, outcome, lines)
                    scoped.log(appleMusicId, publishLine(appleMusicId, outcome, lines, published))
                }
                outcome.ttml
            }
        }.getOrNull()
    }
}

/**
 * Writes [outcome]'s lanes into the native overlay, lane-pure.
 *
 * The enrichment already re-homes the provider pair, but the overlay is the value
 * Apple's own 显示翻译 / 显示发音 switches are computed from
 * ([NativeLyricOverlayStore.hasTranslation] / [NativeLyricOverlayStore.hasPronunciation]),
 * so the same verdict is applied once more at the write: a line's text is stored
 * as a translation only when [LyricLaneClassifier.isTranslationLike] accepts it,
 * and as a pronunciation only when it is that line's reading. The recorded
 * sources speak the module's vocabulary, because
 * [dev.amenhancer.module.lyrics.source.LyricSourceProviders.fromCustomLyricsSource]
 * is what turns them back into a platform name for the sheet.
 */
private fun publishLanes(
    overlay: NativeLyricOverlayStore?,
    appleMusicId: Long,
    outcome: OnlineTranslationEnrichment.Outcome,
    lines: List<NativeLyricLine>,
): Boolean {
    val translationSource = laneProvider(outcome, outcome.translationSource)
    val pronunciationSource = laneProvider(outcome, outcome.pronunciationSource)
    val languages = AppleLyricTtmlReader.transliterationLanguages(outcome.ttml)
    val published = overlay?.update(
        songId = appleMusicId.toString(),
        lines = lines,
        // Only a lane the winning provider really wrote is attributed; a lane the
        // enrichment preserved from Apple's own document keeps no provider.
        translationSource = translationSource,
        pronunciationSource = pronunciationSource,
        pronunciationLanguages = languages,
        originalSource = customSourceTag(outcome.source),
    ) ?: false
    if (published) {
        // The host owns the on-disk copy; without this write a restart forgot which provider
        // supplied the translation until the page re-parsed the displayed document.
        LyricsSourceOverlayCacheBridge.remember(
            songId = appleMusicId,
            mergedTtml = outcome.ttml,
            translationSource = translationSource,
            pronunciationSource = pronunciationSource,
            originalSource = customSourceTag(outcome.source),
            languages = languages,
        )
    }
    return published
}

/**
 * [lines] with both lanes re-homed by [LyricLaneClassifier].
 *
 * A reading the classifier moved (or dropped) also loses its timed slices:
 * [NativeLyricLine.romaSpans] describe the reading that was there before, and the
 * native word getter would otherwise serve slices of a different text.
 */
private fun lanePureLines(lines: List<NativeLyricLine>): List<NativeLyricLine> = lines.map { line ->
    val lanes = LyricLaneClassifier.rebalance(
        translation = line.translation,
        romanization = line.roma,
        original = line.text,
    )
    line.copy(
        translation = lanes.translation,
        roma = lanes.romanization,
        romaSpans = if (lanes.romanization != null && lanes.romanization == line.roma) {
            line.romaSpans
        } else {
            emptyList()
        },
    )
}

/**
 * The provider tag for one lane, or null when the provider did not write it.
 *
 * [laneSource] is the enrichment's own token ("apple" / "QM" / "apple+QM" /
 * "none"); it stays the diagnostic's vocabulary. Only a lane the winner really
 * supplied is attributed in the store — the classifier can move a provider's text
 * into the other lane, but it never invents one.
 */
private fun laneProvider(
    outcome: OnlineTranslationEnrichment.Outcome,
    laneSource: String,
): String? = customSourceTag(outcome.source).takeIf { tag ->
    tag.isNotBlank() &&
        laneSource.split('+').any { token -> token.trim() == outcome.source.name }
}

/** One line naming both lane sources and how many lines each really holds. */
private fun publishLine(
    appleMusicId: Long,
    outcome: OnlineTranslationEnrichment.Outcome,
    lines: List<NativeLyricLine>,
    published: Boolean,
): String {
    val translationLines = lines.count { line -> !line.translation.isNullOrBlank() }
    val pronunciationLines = lines.count { line ->
        !line.roma.isNullOrBlank() || line.romaSpans.isNotEmpty()
    }
    val translationSource = laneProvider(outcome, outcome.translationSource) ?: LANE_NONE
    val pronunciationSource = laneProvider(outcome, outcome.pronunciationSource) ?: LANE_NONE
    return "online-translation overlay id=$appleMusicId published=$published " +
        "translationSource=$translationSource " +
        "pronunciationSource=$pronunciationSource " +
        "translationLines=$translationLines pronunciationLines=$pronunciationLines " +
        "lines=${lines.size} source=${outcome.source} enrichment=${outcome.translationSource}"
}

/**
 * The overlay store records sources in the module's own vocabulary
 * (CustomLyricsSources), which is what the lyrics-source catalog maps back to a
 * platform name; the providers above speak in Source.
 */
private fun customSourceTag(source: dev.amenhancer.module.lyrics.online.Source): String =
    when (source) {
        dev.amenhancer.module.lyrics.online.Source.QM -> CustomLyricsSources.QQ
        dev.amenhancer.module.lyrics.online.Source.NE -> CustomLyricsSources.NETEASE
        dev.amenhancer.module.lyrics.online.Source.KUWO -> CustomLyricsSources.KUWO
        dev.amenhancer.module.lyrics.online.Source.KUGOU -> CustomLyricsSources.KUGOU
        dev.amenhancer.module.lyrics.online.Source.LB -> CustomLyricsSources.LUNABEAT
        dev.amenhancer.module.lyrics.online.Source.AMLL -> CustomLyricsSources.AMLL
    }

internal fun createAutoLyricsRuntime(
    application: Application,
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
            )
        }
    logger(
        "online-translation runtime supplement=$onlineLyricsSupplementEnabled " +
            "translation=$onlineLyricsTranslationEnabled " +
            "applePronunciationOnly=true " +
            "hideMandarinPinyin=$hideMandarinPronunciation " +
            "sources=${onlineLyricsSelection.sources.joinToString(",")} " +
            "enricher=${enricher != null} " +
            "build=${ModuleConstants.BUILD_TAG}",
    )
    val llmExecutor = ThreadPoolExecutor(
        1, 1, 0L, TimeUnit.MILLISECONDS, ArrayBlockingQueue(1),
        { task -> Thread(task, "ampp-llm-lyrics").apply { isDaemon = true } },
        ThreadPoolExecutor.AbortPolicy(),
    )
    val llmCache = FileLlmLyricsCache(File(application.filesDir, "ampp-llm-lyrics"))
    val llmClient = LlmLyricsClient()
    val llm = LlmLyricsRuntime(
        configuration = {
            configuration().let { settings ->
                if (settings.customLyricsEnabled && settings.automaticLyricsEnabled && settings.llmLyricsEnabled) {
                    LlmLyricsConfig(
                        baseUrl = settings.llmLyricsBaseUrl,
                        apiKey = settings.llmLyricsApiKey,
                        model = settings.llmLyricsModel,
                        thinkingEnabled = settings.llmLyricsThinkingEnabled,
                    )
                } else null
            }
        },
        translate = translate@{ request, isCancelled ->
            if (isCancelled()) return@translate null
            // Eligibility always precedes cache reads, including on later visits.
            val document = LlmLyricsDocument.prepare(request.originalTtml) ?: return@translate null
            if (isCancelled()) return@translate null
            llmCache.read(request.appleMusicId, request.originalTtml, request.config)?.let { cached ->
                return@translate cached.takeUnless { isCancelled() }
            }
            val translations = llmClient.translate(
                config = request.config,
                texts = document.texts,
                title = request.title,
                artist = request.artist,
                sourceLanguage = document.sourceLanguage,
                backgroundIndices = document.backgroundIndices,
                isCancelled = isCancelled,
            ) ?: return@translate null
            if (isCancelled()) return@translate null
            val translated = document.withTranslations(translations) ?: return@translate null
            if (isCancelled()) return@translate null
            translated.takeUnless { isCancelled() }
        },
        executor = Executor { task ->
            llmExecutor.queue.clear()
            llmExecutor.execute(task)
        },
        close = { llmExecutor.shutdownNow() },
        onPrepared = { request, translated ->
            // Persist only a host-accepted result; never use the custom-lyrics publisher.
            llmCache.write(request.appleMusicId, request.originalTtml, request.config, translated)
        },
    )
    return AutoLyricsRuntime(
        resolver = resolver,
        cache = cache,
        executor = executor,
        publisher = publisher,
        llm = llm,
        suppressedIds = suppressedIds,
        translationEnricher = enricher,
        nativeLyricOverlay = nativeLyricOverlay,
        hideMandarinPinyin = hideMandarinPronunciation,
        genreFor = genreFor,
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
            // A document the user wrote or edited is left exactly as they saved
            // it: refreshing would re-convert the source over their edit.
            if (!entry.enabled || entry.userEdited || entry.source == CustomLyricsSources.MANUAL ||
                isCancelled()
            ) {
                return@refresh null
            }
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

