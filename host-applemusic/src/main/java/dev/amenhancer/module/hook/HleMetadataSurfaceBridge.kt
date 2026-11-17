package dev.amenhancer.module.hook

import android.os.SystemClock
import com.juren233.hyperlyricsenhanced.BuildConfig
import io.github.proify.lyricon.amprovider.xposed.*
import io.github.proify.lyricon.amprovider.xposed.hooks.AppleFrameworkMetadataHooks
import java.util.concurrent.atomic.AtomicLong

/**
 * Wires the complete HLE metadata surface without bringing HLE's lyric
 * provider lifecycle into AM++. The surface modules are the original HLE
 * implementations; this class only supplies the host callbacks that connect
 * them to the transplanted resolver, cache and AM++ process lifecycle.
 */
internal class HleMetadataSurfaceBridge(
    internal val runtime: AppleMusicProviderRuntime,
    internal val catalogResolver: AppleInternalCatalogResolver,
    internal val metadataStore: AppleMetadataOverrideStore,
    internal val playbackCoordinator: ApplePlaybackMetadataCoordinator,
    internal val playbackHooks: io.github.proify.lyricon.amprovider.xposed.hooks.ApplePlaybackHooks,
    internal val frameworkHooks: AppleFrameworkMetadataHooks,
    internal val contentItemHooks: AppleContentItemMetadataHooks,
    internal val queueMetadataHooks: AppleQueueMetadataHooks,
    internal val actionSheetMetadataHooks: AppleActionSheetMetadataHooks,
    /**
     * HLE's `configuredContentUiLanguage()`: the current 将Apple Music改成其他地区 selection.
     * A supplier, because the region can change while the process is alive.
     */
    internal val configuredContentUiLanguage: () -> Int,
    /**
     * HLE's `AppleOrchestratorCatalogLanguageAssembly.shouldOverrideAccountLanguage`: true only
     * when the selection the caller passes is a region **and** the 「歌曲信息替换至设定地区语言」
     * switch is on.  Supplied rather than snapshotted so the switch is read at call time, which
     * is the whole point of HLE's version taking the selection as a parameter.
     */
    internal val overrideAccountLanguage: (selection: Int) -> Boolean,
    /**
     * HLE's `isRestoreCjkOriginalMetadataEnabled()`: read per call, never captured, so turning
     * 「替换中日韩歌曲信息为原地区原名」 off stops the original-name probe immediately.
     */
    internal val restoreOriginalMetadata: () -> Boolean,
    internal val profileId: String,
    private val installation: HleMetadataInstallation,
    /**
     * Runs once before every resolution seam so the one-shot 「清空检索库」 signal
     * is handled before the request can answer from a cache.  Idempotent; the
     * observer drops the signal after the first clear.
     */
    private val observeMetadataCacheClear: () -> Unit = {},
    /**
     * Runs on the same request seams, right after [observeMetadataCacheClear]: samples the
     * live metadata configuration so a switch flipped in the settings page is noticed by the
     * next request instead of the next process.
     */
    private val observeMetadataConfiguration: () -> Unit = {},
) {
    internal val traceSequence = AtomicLong(0L)
    internal val registry = AppleInAppMetadataRegistry()
    internal lateinit var refreshQueue: AppleInAppMetadataRefreshQueue

    internal lateinit var surfaceRuntime: AppleMetadataSurfaceRuntime
    internal lateinit var librarySurfaceHooks: AppleLibrarySurfaceHooks
    internal lateinit var dataBindingHooks: AppleDataBindingMetadataHooks
    internal lateinit var collectionSurfaceHooks: AppleCollectionSurfaceHooks
    internal lateinit var artistSurfaceHooks: AppleArtistSurfaceHooks
    internal lateinit var listenNowHooks: AppleListenNowHooks
    internal lateinit var mediaApiMetadataCoordinator: AppleMediaApiMetadataCoordinator
    internal lateinit var resolutionCoordinator: AppleInAppMetadataResolutionCoordinator
    internal lateinit var metadataApplier: AppleInAppMetadataApplier
    internal lateinit var metadataRegistrationCoordinator: AppleInAppMetadataRegistrationCoordinator
    internal lateinit var metadataOverrideApplicationCoordinator:
        AppleMetadataOverrideApplicationCoordinator
    internal lateinit var media3MetadataCoordinator: AppleMedia3MetadataCoordinator
    internal lateinit var playbackItemConversionHooks: ApplePlaybackItemConversionHooks
    internal lateinit var inAppArtworkContinuityHooks: AppleInAppArtworkContinuityHooks
    internal lateinit var visibleMetadataDiagnostics: AppleVisibleMetadataDiagnostics
    /**
     * HLE's `AppleMetadataConfigurationDispatcher`, wired exactly like
     * `AppleOrchestratorInAppMetadataAssembly` wires it.  It used to be dead code in this
     * fork: the runtime snapshotted its controls, so nothing could ever change them.
     */
    internal lateinit var metadataConfigurationDispatcher: AppleMetadataConfigurationDispatcher

    fun install() {
        MediaMetadataCache.setProfile(profileId)
        refreshQueue = AppleInAppMetadataRefreshQueue(
            postToMain = { callback -> runtime.mainHandler.post { callback() } },
            diagnostics = if (BuildConfig.DEBUG) {
                { stats ->
                    ProviderLogger.diagnostic(
                        "HLE metadata refresh frame: enqueued=${stats.enqueued}, " +
                            "merged=${stats.merged}, executed=${stats.executed}, " +
                            "failed=${stats.failed}, maxDepth=${stats.maxDepth}, " +
                            "durationMs=${stats.durationNanos / 1_000_000.0}",
                    )
                }
            } else {
                null
            },
        )
        val hosts = createHostAdapters()
        surfaceRuntime = AppleMetadataSurfaceRuntime(
            runtime = runtime,
            host = hosts.surface,
        )
        librarySurfaceHooks = AppleLibrarySurfaceHooks(
            runtime = runtime,
            metadataStore = metadataStore,
            host = hosts.library,
            refreshQueue = refreshQueue,
        )
        dataBindingHooks = AppleDataBindingMetadataHooks(
            runtime = runtime,
            host = hosts.dataBinding,
            refreshQueue = refreshQueue,
        )
        collectionSurfaceHooks = AppleCollectionSurfaceHooks(
            runtime = runtime,
            metadataStore = metadataStore,
            librarySurfaceHooks = librarySurfaceHooks,
            dataBindingHooks = dataBindingHooks,
            host = hosts.collection,
            refreshQueue = refreshQueue,
        )
        artistSurfaceHooks = AppleArtistSurfaceHooks(
            runtime = runtime,
            metadataStore = metadataStore,
            librarySurfaceHooks = librarySurfaceHooks,
            dataBindingHooks = dataBindingHooks,
            host = hosts.artist,
            refreshQueue = refreshQueue,
        )
        mediaApiMetadataCoordinator = AppleMediaApiMetadataCoordinator(
            runtime = runtime,
            metadataStore = metadataStore,
            catalogResolver = catalogResolver,
            librarySurfaceHooks = librarySurfaceHooks,
            artistSurfaceHooks = artistSurfaceHooks,
            host = hosts.mediaApi,
            refreshQueue = refreshQueue,
        )
        resolutionCoordinator = AppleInAppMetadataResolutionCoordinator(
            runtime = runtime,
            metadataStore = metadataStore,
            catalogResolver = catalogResolver,
            host = hosts.resolution,
        )
        listenNowHooks = AppleListenNowHooks(
            runtime = runtime,
            metadataStore = metadataStore,
            catalogResolver = catalogResolver,
            host = hosts.listenNow,
            refreshQueue = refreshQueue,
        )
        metadataApplier = AppleInAppMetadataApplier(
            runtime = runtime,
            metadataStore = metadataStore,
            registry = registry,
            contentItemMetadataHooks = contentItemHooks,
            librarySurfaceHooks = librarySurfaceHooks,
            collectionSurfaceHooks = collectionSurfaceHooks,
            artistSurfaceHooks = artistSurfaceHooks,
            dataBindingHooks = dataBindingHooks,
            listenNowHooks = listenNowHooks,
            queueMetadataHooks = queueMetadataHooks,
            traceSequence = traceSequence,
            logMetadataIdentity = { event, details ->
                ProviderLogger.diagnostic("$event: $details")
            },
        )
        media3MetadataCoordinator = AppleMedia3MetadataCoordinator(
            runtime = runtime,
            metadataStore = metadataStore,
            resolutionCoordinator = resolutionCoordinator,
            frameworkMetadataHooks = frameworkHooks,
            queueMetadataHooks = queueMetadataHooks,
            playbackMetadataCoordinator = playbackCoordinator,
            traceSequence = traceSequence,
        )
        metadataRegistrationCoordinator = AppleInAppMetadataRegistrationCoordinator(
            runtime = runtime,
            metadataStore = metadataStore,
            registry = registry,
            resolutionCoordinator = resolutionCoordinator,
            catalogResolver = catalogResolver,
            contentItemMetadataHooks = contentItemHooks,
            metadataApplier = metadataApplier,
            surfaceRuntime = surfaceRuntime,
            dataBindingHooks = dataBindingHooks,
            configuredContentUiLanguage = this@HleMetadataSurfaceBridge.configuredContentUiLanguage,
        )
        visibleMetadataDiagnostics = AppleVisibleMetadataDiagnostics(
            runtime = runtime,
            host = hosts.visibleDiagnostics,
        )
        metadataOverrideApplicationCoordinator = AppleMetadataOverrideApplicationCoordinator(
            runtime = runtime,
            metadataStore = metadataStore,
            registry = registry,
            resolutionCoordinator = resolutionCoordinator,
            catalogResolver = catalogResolver,
            surfaceRuntime = surfaceRuntime,
            metadataApplier = metadataApplier,
            librarySurfaceHooks = librarySurfaceHooks,
            dataBindingHooks = dataBindingHooks,
            listenNowHooks = listenNowHooks,
            actionSheetMetadataHooks = actionSheetMetadataHooks,
            playbackMetadataCoordinator = playbackCoordinator,
            frameworkMetadataHooks = frameworkHooks,
            visibleMetadataDiagnostics = visibleMetadataDiagnostics,
            media3MetadataCoordinator = media3MetadataCoordinator,
            configuredContentUiLanguage = this@HleMetadataSurfaceBridge.configuredContentUiLanguage,
            traceSequence = traceSequence,
        )
        playbackItemConversionHooks = ApplePlaybackItemConversionHooks(
            runtime = runtime,
            host = hosts.playbackItem,
        )
        inAppArtworkContinuityHooks = AppleInAppArtworkContinuityHooks(
            runtime = runtime,
            host = hosts.artworkContinuity,
        )
        metadataConfigurationDispatcher = AppleMetadataConfigurationDispatcher(
            // HLE's order.  `artistSurfaceHooks.clearBrowseState()` has no counterpart here:
            // this fork has no browse/search surface module (see AppleArtistSurfaceHooks), so
            // that owner is a logged omission rather than a silently missing clear.
            clearStateOwners = listOf<() -> Unit>(
                metadataStore::onConfigurationChanged,
                { librarySurfaceHooks.clearConfigurationState() },
                { dataBindingHooks.clearConfigurationState() },
                { listenNowHooks.clearMetadataState() },
                {
                    metadataApplier.clearCallbackState()
                    resolutionCoordinator.clearDeferredResolutions()
                },
            ),
            restoreCapturedModels = metadataApplier::restoreCapturedModels,
            scheduleConsumerRefresh = { scheduleConsumerRefresh() },
        )
        logMissingConfigurationStateOwner()

        installSafely("metadata-surface-lifecycle") { surfaceRuntime.installLifecycleHooks() }
        installSafely("library-entity") { librarySurfaceHooks.installEntityHooks() }
        if (librarySurfaceHooks.hasPresentationSurface()) {
            installSafely("metadata-presentation") { librarySurfaceHooks.installPresentationHooks() }
        }
        installSafely("library-compose") { librarySurfaceHooks.installComposeHooks() }
        installSafely("library-epoxy") { librarySurfaceHooks.installEpoxyHooks() }
        installSafely("data-binding") { dataBindingHooks.installDataBindingHooks() }
        installSafely("recycler") { dataBindingHooks.installRecyclerHooks() }
        installSafely("collection") { collectionSurfaceHooks.installHooks() }
        installSafely("artist-top-songs") { artistSurfaceHooks.installTopSongHooks() }
        installSafely("artist-profile") { artistSurfaceHooks.installProfileHooks() }
        // Stage declared Compose surfaces even when a target is missing, so failure is reported.
        if (artistSurfaceHooks.hasComposeProfileSurface()) {
            installSafely("artist-compose-profile") { artistSurfaceHooks.installComposeProfileHooks() }
        }
        installSafely("listen-now-artwork") { listenNowHooks.installArtworkContinuityHooks() }
        installSafely("listen-now-binding") { listenNowHooks.installMetadataBindingHooks() }
        installSafely("recently-searched") { mediaApiMetadataCoordinator.installRecentlySearchedHooks() }
        installSafely("in-app-artwork-continuity") { inAppArtworkContinuityHooks.installHooks() }
        installSafely("playback-item-conversion") { playbackItemConversionHooks.installHooks() }
    }

    /**
     * The metadata request seam.
     *
     * Both live signals ride on it: the one-shot 「清空检索库」 generation and the four
     * metadata controls.  HLE gets the second one pushed from a cross-process preference
     * listener; AM++ has no such listener, so every request that could resolve metadata
     * re-samples the value behind a short TTL cache (see HleMetadataRuntime).
     */
    private fun observeRuntimeSignals() {
        observeMetadataCacheClear()
        observeMetadataConfiguration()
    }

    /**
     * HLE's `scheduleConsumerRefresh`: restore the MediaSession payloads we rewrote, refresh
     * the queue/now-playing callbacks and re-resolve the item that is playing now, so the new
     * display language is visible immediately instead of on the next page load.
     *
     * Everything here is main-thread work (MediaSession, RecyclerView notify, queue item
     * lookup) and every call is failure-tolerant: a language change must never crash the host.
     */
    private fun scheduleConsumerRefresh() {
        runtime.mainHandler.post {
            runCatching {
                frameworkHooks.restoreMediaSessionMetadata()
                frameworkHooks.restoreMediaSessionQueue()
                metadataApplier.refreshMetadataCallbacks()
            }.onFailure { error ->
                ProviderLogger.error("Apple Music 元数据回调刷新失败", error)
            }
            runCatching {
                playbackHooks.activePlayer()?.let { mediaPlayer ->
                    playbackCoordinator.refreshCurrentQueueItem(
                        mediaPlayer,
                        "Apple Music 语言覆盖设置变更",
                    )
                }
            }.onFailure { error ->
                ProviderLogger.error("Apple Music 当前播放项刷新失败", error)
            }
        }
    }

    /**
     * HLE's `dispatch()`, called by the runtime once it decided a change is owed.
     *
     * False means an owner threw: the runtime keeps the previous baseline so the next request
     * seam offers the same change again instead of recording it as applied.
     */
    fun dispatchMetadataConfigurationChange(): Boolean {
        if (!::metadataConfigurationDispatcher.isInitialized) return true
        return runCatching { metadataConfigurationDispatcher.dispatch() }
            .onFailure { ProviderLogger.error("Apple Music 元数据配置状态清理失败", it) }
            .isSuccess
    }

    /**
     * HLE's `clearStateOwners` also clears `artistSurfaceHooks.clearBrowseState()`, which this
     * fork has no equivalent for: there is no browse/search surface module here.  Say so once
     * instead of pretending the clear is complete.
     */
    private fun logMissingConfigurationStateOwner() {
        ProviderLogger.diagnostic(
            "Apple Music 元数据配置状态清理: artistSurfaceHooks.clearBrowseState 在本仓无等价实现，已跳过"
        )
    }

    fun ensureOverride(
        mediaId: String,
        preBind: Boolean = false,
        priority: AppleInternalCatalogResolver.RequestPriority =
            AppleInternalCatalogResolver.RequestPriority.VISIBLE,
    ) {
        observeRuntimeSignals()
        resolutionCoordinator.ensureOverride(mediaId, preBind, priority)
    }

    fun ensureOverrides(
        mediaIds: Collection<String>,
        preBind: Boolean = false,
        originalResolutionLimit: Int = mediaIds.size,
    ) {
        observeRuntimeSignals()
        resolutionCoordinator.ensureOverrides(mediaIds, preBind, originalResolutionLimit)
    }

    fun registerMetadata(
        mediaId: String,
        metadata: Any,
        requestResolution: Boolean,
        preBind: Boolean,
        priority: AppleInternalCatalogResolver.RequestPriority,
    ) {
        observeRuntimeSignals()
        metadataRegistrationCoordinator.registerMetadata(
            mediaId = mediaId,
            metadata = metadata,
            requestResolution = requestResolution,
            preBind = preBind,
            priority = priority,
        )
    }

    fun registerPlaybackItem(
        mediaId: String,
        playbackItem: Any,
        notifyChange: Boolean,
        analyzeMetadata: Boolean,
    ) {
        observeRuntimeSignals()
        metadataRegistrationCoordinator.registerPlaybackItem(
            mediaId = mediaId,
            playbackItem = playbackItem,
            notifyChange = notifyChange,
            analyzeMetadata = analyzeMetadata,
        )
    }

    fun media3MetadataId(
        metadata: Any,
        fallback: String?,
        trustedFallback: Boolean,
    ): String? = media3MetadataCoordinator.mediaId(metadata, fallback, trustedFallback)

    fun media3MetadataDetails(metadata: Any): String =
        media3MetadataCoordinator.details(metadata)

    fun activePlaybackIdentity(): ActivePlaybackMediaIdentity =
        if (::media3MetadataCoordinator.isInitialized) {
            media3MetadataCoordinator.activePlaybackIdentity()
        } else {
            val mediaId = playbackCoordinator.currentMetadataId()
            ActivePlaybackMediaIdentity(
                mediaId = mediaId,
                source = "queue",
                candidates = mediaId.orEmpty(),
            )
        }

    fun effectiveAlias(mediaId: String): AppleInternalCatalogResolver.Alias? =
        if (::resolutionCoordinator.isInitialized) {
            resolutionCoordinator.effectiveAlias(mediaId)
        } else {
            metadataStore.originalMetadata(mediaId) ?: metadataStore.configuredMetadata(mediaId)
        }

    fun applyAliasToPlaybackItem(
        playbackItem: Any,
        alias: AppleInternalCatalogResolver.Alias,
        notifyChange: Boolean,
    ) = metadataApplier.applyAliasToPlaybackItem(playbackItem, alias, notifyChange)

    /**
     * Keep playback resolution publication on the same HLE coordinator path as
     * the original provider.  The coordinator owns the in-app rebind policy,
     * album/artist propagation, and persistent original-region bookkeeping;
     * writing only the two stores here leaves library rows stale until playback.
     */
    fun applyPlaybackMetadataOverride(
        mediaId: String,
        alias: AppleInternalCatalogResolver.Alias,
        forceInAppRebind: Boolean = true,
        rememberLocalizedArtist: Boolean = true,
        originalMetadata: Boolean = false,
        originalMetadataConfirmed: Boolean = false,
        artistOnly: Boolean = false,
        propagateArtistEntity: Boolean = true,
    ) {
        if (MediaMetadataCache.profile() != profileId) {
            ProviderLogger.debug(
                "忽略过期元数据 profile 回调: expected=$profileId, active=${MediaMetadataCache.profile()}"
            )
            return
        }
        metadataOverrideApplicationCoordinator.apply(
            mediaId = mediaId,
            alias = alias,
            forceInAppRebind = forceInAppRebind,
            rememberLocalizedArtist = rememberLocalizedArtist,
            originalMetadata = originalMetadata,
            originalMetadataConfirmed = originalMetadataConfirmed,
            artistOnly = artistOnly,
            propagateArtistEntity = propagateArtistEntity,
        )
    }

    internal fun applyPlaybackMetadataOverrideFromHost(
        mediaId: String,
        alias: AppleInternalCatalogResolver.Alias,
        forceInAppRebind: Boolean,
        rememberLocalizedArtist: Boolean,
        originalMetadata: Boolean,
        originalMetadataConfirmed: Boolean,
        artistOnly: Boolean,
        propagateArtistEntity: Boolean,
    ) {
        if (originalMetadata && !restoreOriginalMetadata()) return
        if (::metadataOverrideApplicationCoordinator.isInitialized) {
            applyPlaybackMetadataOverride(
                mediaId = mediaId,
                alias = alias,
                forceInAppRebind = forceInAppRebind,
                rememberLocalizedArtist = rememberLocalizedArtist,
                originalMetadata = originalMetadata,
                originalMetadataConfirmed = originalMetadataConfirmed,
                artistOnly = artistOnly,
                propagateArtistEntity = propagateArtistEntity,
            )
        } else {
            if (originalMetadata) {
                metadataStore.rememberOriginalMetadata(mediaId, alias, originalMetadataConfirmed)
            } else {
                metadataStore.rememberConfiguredMetadata(mediaId, alias)
            }
            frameworkHooks.refreshMediaSessionMetadata(mediaId, alias)
        }
    }

    internal fun publishCurrentPlaybackAliasFromHost(
        mediaId: String,
        alias: AppleInternalCatalogResolver.Alias,
    ) {
        if (::metadataOverrideApplicationCoordinator.isInitialized) {
            metadataOverrideApplicationCoordinator.publishCurrentPlaybackAlias(mediaId, alias)
        }
    }

    fun applyAliasToContainerItem(
        containerItem: Any,
        kind: InAppContainerKind,
        alias: AppleInternalCatalogResolver.Alias,
        notifyChange: Boolean = true,
    ) = metadataApplier.applyAliasToContainerItem(containerItem, kind, alias, notifyChange)

    fun markMetadataVisible(mediaIds: Collection<String>) = surfaceRuntime.markVisible(mediaIds)

    fun isCurrentMetadataSurfaceMediaId(mediaId: String): Boolean =
        surfaceRuntime.isCurrentMediaId(mediaId)

    fun setPlaybackMediaId(mediaId: String) = surfaceRuntime.setPlaybackMediaId(mediaId)

    fun requestPriority(mediaId: String): AppleInternalCatalogResolver.RequestPriority =
        surfaceRuntime.requestContext(mediaId).priority

    fun contentItemLocalizedEntityType(contentItem: Any): AppleInternalCatalogResolver.LocalizedEntityType? =
        metadataRegistrationCoordinator.contentItemLocalizedEntityType(contentItem)

    fun recordComposeMediaId(mediaId: String) = librarySurfaceHooks.recordComposeMediaId(mediaId)

    fun recordCurrentRecyclerMediaId(mediaId: String) {
        dataBindingHooks.recordCurrentRecyclerMediaId(mediaId)
    }

    fun shouldRequestOverride(mediaId: String): Boolean =
        resolutionCoordinator.shouldRequestOverride(mediaId)

    fun shouldShareOriginalSongLanguage(
        localizedTitle: String?,
        localizedArtist: String?,
        alias: AppleInternalCatalogResolver.Alias?,
    ): Boolean = restoreOriginalMetadata() && resolutionCoordinator.shouldShareOriginalSongLanguage(
        localizedTitle = localizedTitle,
        localizedArtist = localizedArtist,
        alias = alias,
    )

    fun rememberOriginalLanguageForArtist(mediaId: String, language: String) {
        if (restoreOriginalMetadata()) {
            resolutionCoordinator.rememberOriginalLanguageForArtist(mediaId, language)
        }
    }

    fun containerNavigationBinding(containerItem: Any): InAppContainerNavigationRef? =
        metadataRegistrationCoordinator.containerNavigationBinding(containerItem)

    fun registerContainerItem(
        mediaId: String,
        containerItem: Any,
        kind: InAppContainerKind,
    ) = metadataRegistrationCoordinator.registerContainerItem(mediaId, containerItem, kind)

    fun rawContentItemValue(contentItem: Any, runtimeMember: AppleMusicRuntimeMember): Any? =
        metadataRegistrationCoordinator.rawContentItemValue(contentItem, runtimeMember)

    fun knownValues(mediaId: String, field: VisibleTextField): Set<String> = buildSet {
        val account = metadataStore.accountMetadata(mediaId)
        val value = alias(mediaId)
        when (field) {
            VisibleTextField.TITLE -> Unit
            VisibleTextField.ARTIST -> {
                account?.artist?.let(::add)
                value?.artist?.let(::add)
                registry.livePlaybackItemRefs(mediaId).forEach { ref ->
                    ref.originalArtist?.toString()?.let(::add)
                }
            }
            VisibleTextField.ALBUM -> {
                value?.album?.let(::add)
                registry.livePlaybackItemRefs(mediaId).forEach { ref ->
                    ref.originalCollectionName
                        ?.takeIf(String::isNotBlank)
                        ?.let(::add)
                }
            }
        }
    }

    fun hasLivePlaybackItem(mediaId: String): Boolean = registry.hasLivePlaybackItem(mediaId)

    fun markPlaybackItemHistory(playbackItem: Any) = registry.markPlaybackItemContract(
        playbackItem,
        InAppPlaybackItemContract.HISTORY,
    )

    fun recordArtistAssociation(mediaId: String, item: Any, rawTitle: String?) {
        val artistKeys = metadataRegistrationCoordinator.contentItemArtistCacheKeys(item, rawTitle)
        if (artistKeys.isNotEmpty()) {
            metadataStore.mergeArtistKeys(mediaId, artistKeys)
        }
        resolutionCoordinator.mergePlaybackAssociatedArtistIds(
            mediaId = mediaId,
            artistIds = io.github.proify.lyricon.amprovider.xposed.artistIdsFromAssociationKeys(artistKeys) +
                metadataRegistrationCoordinator.contentItemCatalogLookupIds(item, mediaId = "")
                    .filterNot { it == mediaId },
        )
    }

    /**
     * Concrete adapters for the HLE host interfaces.  These objects are built once during
     * installation and keep all compatibility/fail-open handling at the seam.  Hot callbacks
     * therefore use normal virtual dispatch instead of method-name/argument-array lookup on
     * every invocation.
     */
    internal data class HostAdapters(
        val surface: AppleMetadataSurfaceHost,
        val library: AppleLibrarySurfaceHost,
        val dataBinding: AppleDataBindingMetadataHost,
        val collection: AppleCollectionSurfaceHost,
        val artist: AppleArtistSurfaceHost,
        val mediaApi: AppleMediaApiMetadataHost,
        val resolution: AppleInAppMetadataResolutionHost,
        val listenNow: AppleListenNowHost,
        val visibleDiagnostics: AppleVisibleMetadataDiagnosticsHost,
        val playbackItem: ApplePlaybackItemConversionHost,
        val artworkContinuity: AppleInAppArtworkContinuityHost,
    )

    internal inline fun <T> hostCall(
        name: String,
        fallback: T,
        block: () -> T,
    ): T {
        return try {
            block()
        } catch (error: Throwable) {
            runCatching {
                ProviderLogger.debug("HLE typed host callback $name failed: ${error.message}")
            }
            fallback
        }
    }

    internal fun createHostAdapters(): HostAdapters = createMetadataHostAdapters()

    internal fun installSafely(name: String, block: () -> Unit) {
        installation.install(name, block)
    }

    internal fun currentIdentity(): ActivePlaybackMediaIdentity = ActivePlaybackMediaIdentity(
        mediaId = playbackCoordinator.currentMetadataId(),
        source = "ampp_hle",
        candidates = playbackCoordinator.currentMetadataId().orEmpty(),
    )

    internal fun alias(mediaId: String?): AppleInternalCatalogResolver.Alias? =
        mediaId?.let(::effectiveAlias)

    internal fun markVisible(ids: Collection<String>) {
        surfaceRuntime.markVisible(ids)
    }

    internal fun shouldRetryOriginalMetadataCacheProbe(mediaId: String): Boolean =
        metadataStore.shouldRetryOriginalSong(mediaId, SystemClock.uptimeMillis()) ?:
        io.github.proify.lyricon.amprovider.xposed.shouldRetryOriginalMetadataCacheProbe(
            originalResolved = metadataStore.isOriginalResolved(mediaId),
            lastMissUptimeMillis = metadataStore.originalCacheMissUptimeMillis(mediaId),
            nowUptimeMillis = SystemClock.uptimeMillis(),
        )

    internal fun ensureOverride(
        mediaId: String,
        priority: AppleInternalCatalogResolver.RequestPriority =
            AppleInternalCatalogResolver.RequestPriority.VISIBLE,
    ) {
        if (::resolutionCoordinator.isInitialized && resolutionCoordinator.shouldRequestOverride(mediaId)) {
            resolutionCoordinator.ensureOverride(mediaId, preBind = false, priority = priority)
        }
    }

    internal fun applyAliasToObject(
        target: Any,
        value: AppleInternalCatalogResolver.Alias,
        notifyChange: Boolean,
    ) {
        val writes = listOf(
            "setTitle" to value.title,
            "setArtistName" to value.artist,
            "setCollectionName" to value.album,
        )
        writes.forEach { (method, text) ->
            if (text.isBlank()) return@forEach
            runCatching { AppleReflection.call(target, method, text) }
        }
        if (notifyChange) {
            runCatching { AppleReflection.call(target, "notifyChange") }
        }
    }

}
