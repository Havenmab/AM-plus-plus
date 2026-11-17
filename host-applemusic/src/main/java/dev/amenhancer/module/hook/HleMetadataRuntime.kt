package dev.amenhancer.module.hook

import android.app.Application
import android.os.SystemClock
import dev.amenhancer.module.config.RegionMetadataConfiguration
import dev.amenhancer.module.config.RegionMetadataConfigurationWatcher
import dev.amenhancer.module.config.RegionMetadataRefreshAction
import dev.amenhancer.module.config.RegionMetadataRefreshPolicy
import dev.amenhancer.module.config.RegionSelection
import dev.amenhancer.module.config.RegionTitleRequestPlan
import io.github.libxposed.api.XposedModule
import io.github.proify.lyricon.amprovider.xposed.AppleInternalCatalogResolver
import io.github.proify.lyricon.amprovider.xposed.AppleMetadataOverrideStore
import io.github.proify.lyricon.amprovider.xposed.AppleMusicHookResolver
import io.github.proify.lyricon.amprovider.xposed.AppleMusicProviderRuntime
import io.github.proify.lyricon.amprovider.xposed.AppleMusicVersion
import io.github.proify.lyricon.amprovider.xposed.applyRegionConfiguration
import io.github.proify.lyricon.amprovider.xposed.MediaMetadataCache
import io.github.proify.lyricon.amprovider.xposed.ProviderLogger
import io.github.proify.lyricon.amprovider.xposed.hooks.AppleFrameworkMetadataHooks
import io.github.proify.lyricon.amprovider.xposed.hooks.AppleContentLocalizationHooks
import io.github.proify.lyricon.amprovider.xposed.hooks.ApplePlaybackHooks
import io.github.proify.lyricon.amprovider.xposed.ApplePlaybackMetadataCoordinator
import io.github.proify.lyricon.amprovider.xposed.ApplePlaybackMetadataCoordinatorHost
import io.github.proify.lyricon.amprovider.xposed.ApplePlaybackMetadataHooks
import io.github.proify.lyricon.amprovider.xposed.AppleContentItemMetadataHooks
import io.github.proify.lyricon.amprovider.xposed.AppleContentItemMetadataHost
import io.github.proify.lyricon.amprovider.xposed.AppleContentItemGetter
import io.github.proify.lyricon.amprovider.xposed.AppleQueueMetadataHooks
import io.github.proify.lyricon.amprovider.xposed.AppleQueueMetadataHost
import io.github.proify.lyricon.amprovider.xposed.AppleActionSheetMetadataHooks
import io.github.proify.lyricon.amprovider.xposed.AppleActionSheetMetadataHost
import io.github.proify.lyricon.amprovider.xposed.InAppContainerKind
import io.github.proify.lyricon.amprovider.xposed.validatedOriginalSongAlias
import com.juren233.hyperlyricsenhanced.common.lyric.AppleOriginalMetadataPolicy
import java.util.concurrent.atomic.AtomicBoolean

/**
 * AM++ host for the transplanted HLE metadata subsystem.
 *
 * It intentionally wires only metadata concerns: HLE's lyric provider,
 * preference UI, remote player, and unrelated enhancement hooks are not
 * initialized. The original HLE resolver/cache/policy/hook profile path is
 * retained for Apple Music metadata.
 */
/** At most one configuration refresh attempt per second, so a failing action cannot spin. */
private const val METADATA_CONFIGURATION_RETRY_INTERVAL_MS = 1_000L

internal class HleMetadataRuntime(
    private val module: XposedModule,
    private val application: Application,
    private val classLoader: ClassLoader,
    /** The 将Apple Music改成其他地区 picker only; it never gates or implies the switches. */
    private val region: RegionSelection = RegionSelection.NONE,
    /**
     * HLE's 歌曲信息替换至设定地区语言 switch.  It only takes effect together with
     * [region]; on its own it does nothing.
     */
    private val overrideAccountLanguage: Boolean = false,
    /**
     * HLE's 替换中日韩歌曲信息为原地区原名 switch: restores CJK songs to their
     * original-region names.  Independent of [region]: the user may combine it with
     * any region.
     */
    private val restoreCjkOriginalMetadata: Boolean = false,
    /** Persist region/original metadata lookups in SQLite across cold starts. */
    private val localizedMetadataCache: Boolean = true,
    /**
     * Cheap read of the one-shot 「清空检索库」 signal.  The runtime polls it on its
     * metadata request seams and clears the persistent and in-memory caches once
     * per generation.  Defaults to "no pending clear" so test/dynamic runtimes
     * stay inert.
     */
    private val metadataCacheClearSignal: () -> Long = { 0L },
    /**
     * Live read of HLE's four metadata controls.
     *
     * HLE reads them at call time (`shouldOverrideAccountLanguage(selection)`,
     * `isRestoreCjkOriginalMetadataEnabled()`, `configuredContentUiLanguage()` all hit the
     * preference object again).  AM++ has no cross-process preference listener, so the host
     * supplies a reader and the runtime polls it through [configurationWatcher] on its request
     * seams.  The default keeps the construction-time scalars of [region] and the two
     * switches, which is what a test or a dynamic runtime without a configuration source
     * wants: a fixed value that never changes.
     */
    private val metadataConfiguration: () -> RegionMetadataConfiguration = {
        RegionMetadataConfiguration(
            region = region,
            overrideAccountLanguage = overrideAccountLanguage,
            restoreCjkOriginalMetadata = restoreCjkOriginalMetadata,
            localizedMetadataCache = localizedMetadataCache,
        )
    },
    /** Timed-read clock for [configurationWatcher] and the refresh retry; injected so both stay testable. */
    private val clock: () -> Long = SystemClock::elapsedRealtime,
) {
    /**
     * Short-TTL snapshot of [metadataConfiguration].
     *
     * Every seam that used to read `requestPlan` now reads it through this watcher, so the
     * value is the user's current configuration instead of the one that was on disk when the
     * hook was installed, without turning the hot path into a preference read per request.
     */
    private val configurationWatcher = RegionMetadataConfigurationWatcher(
        read = metadataConfiguration,
        clock = clock,
    )

    /** The four controls as they are right now. */
    private val configuration: RegionMetadataConfiguration
        get() = configurationWatcher.current()

    /** The three controls projected onto the request seams; re-derived on every access. */
    private val requestPlan: RegionTitleRequestPlan
        get() = configuration.plan

    private val version = runCatching {
        val info = application.packageManager.getPackageInfo(
            application.packageName,
            0,
        )
        AppleMusicVersion(
            versionName = info.versionName,
            versionCode = if (android.os.Build.VERSION.SDK_INT >= 28) {
                info.longVersionCode
            } else {
                @Suppress("DEPRECATION") info.versionCode.toLong()
            },
        )
    }.getOrDefault(AppleMusicVersion(null, null))

    private val moduleApplicationInfo = runCatching { module.getModuleApplicationInfo() }.getOrNull()

    private val hookResolver = AppleMusicHookResolver(
        version = version,
        application = application,
        nativeLibraryDir = moduleApplicationInfo?.nativeLibraryDir.orEmpty(),
        // The module packages libdexkit.so inside the APK (extractNativeLibs=false), so the
        // native library directory stays empty on install. The DexKit fallback resolver needs
        // the package paths to load or unpack the library in that case.
        moduleApkPaths = listOfNotNull(
            moduleApplicationInfo?.sourceDir,
            moduleApplicationInfo?.publicSourceDir,
        ).distinct() + moduleApplicationInfo?.splitSourceDirs.orEmpty(),
    )
    private val runtime = AppleMusicProviderRuntime(module, classLoader)
    private val installation = HleMetadataInstallation(runtime.hookRegistrar)
    private val playbackHooks = ApplePlaybackHooks()
    private val metadataStore = AppleMetadataOverrideStore()
    private val catalogResolver = AppleInternalCatalogResolver(
        context = application,
        classLoader = classLoader,
        hookResolver = hookResolver,
        mainHandler = runtime.mainHandler,
        cacheNamespace = requestPlan.cacheNamespace,
    )
    /**
     * Owns the user-facing 「清空检索库」 action: the settings page only bumps a
     * persisted generation, and this observer deletes the caches the first time
     * the runtime sees the new value on a request seam (or at install, if the
     * clear was requested just before the previous process ended).
     */
    private val metadataCacheClearHandledStore = HleMetadataCacheClearHandledStore(application)
    private val metadataCacheClearObserver = HleMetadataCacheClearObserver(
        signal = metadataCacheClearSignal,
        onClear = { HleMetadataCacheIndex.clear(application, catalogResolver, metadataStore) },
        initialHandledGeneration = metadataCacheClearHandledStore.read(),
        onHandled = metadataCacheClearHandledStore::write,
    )
    private lateinit var contentLocalizationHooks: AppleContentLocalizationHooks
    private lateinit var frameworkHooks: AppleFrameworkMetadataHooks
    private lateinit var playbackCoordinator: ApplePlaybackMetadataCoordinator
    private lateinit var contentItemHooks: AppleContentItemMetadataHooks
    private lateinit var queueMetadataHooks: AppleQueueMetadataHooks
    private lateinit var actionSheetMetadataHooks: AppleActionSheetMetadataHooks
    private lateinit var surfaceBridge: HleMetadataSurfaceBridge
    private var bridgeEnsureOverride: (
        String,
        Boolean,
        AppleInternalCatalogResolver.RequestPriority,
    ) -> Unit = { _, _, _ -> }
    private var bridgeEnsureOverrides: (
        Collection<String>,
        Boolean,
        Int,
    ) -> Unit = { _, _, _ -> }
    private var bridgeRegisterMetadata: (
        String,
        Any,
        Boolean,
        Boolean,
        AppleInternalCatalogResolver.RequestPriority,
    ) -> Unit = { _, _, _, _, _ -> }
    private var bridgeRegisterPlaybackItem: (String, Any, Boolean, Boolean) -> Unit =
        { _, _, _, _ -> }
    private var bridgeApplyAliasToPlaybackItem: (Any, AppleInternalCatalogResolver.Alias, Boolean) -> Unit =
        { _, _, _ -> }
    private var bridgeMarkMetadataVisible: (Collection<String>) -> Unit = {}
    private var bridgeSetPlaybackMediaId: (String) -> Unit = {}
    private var bridgeRegisterContainerItem: (String, Any, InAppContainerKind) -> Unit =
        { _, _, _ -> }
    private var bridgeContainerNavigationBinding: (Any) -> io.github.proify.lyricon.amprovider.xposed.InAppContainerNavigationRef? =
        { null }
    private var bridgeRawContentItemValue: (Any, io.github.proify.lyricon.amprovider.xposed.AppleMusicRuntimeMember) -> Any? =
        { _, _ -> null }
    private var bridgeKnownValues: (String, io.github.proify.lyricon.amprovider.xposed.VisibleTextField) -> Set<String> =
        { _, _ -> emptySet() }
    private var bridgeHasLivePlaybackItem: (String) -> Boolean = { false }
    private var bridgeEffectiveAlias: (String) -> AppleInternalCatalogResolver.Alias? = { mediaId ->
        metadataStore.originalMetadata(mediaId) ?: metadataStore.configuredMetadata(mediaId)
    }
    private var bridgeMedia3MetadataId: (Any, String?, Boolean) -> String? =
        { _, fallback, trustedFallback ->
            fallback?.takeIf { trustedFallback && it.isNotBlank() && it.all(Char::isDigit) }
        }
    private var bridgeMedia3MetadataDetails: (Any) -> String = { metadata -> metadata.javaClass.name }
    private var bridgeIsCurrentMetadataSurfaceMediaId: (String) -> Boolean = { mediaId ->
        ::playbackCoordinator.isInitialized && mediaId == playbackCoordinator.currentMetadataId()
    }

    fun install(): TargetCapabilityInstall {
        return try { installRuntime() } catch (error: Throwable) {
            installation.close()
            throw error
        }
    }

    private fun installRuntime(): TargetCapabilityInstall {
        runtime.attach(application, hookResolver)
        // Nothing is cached until the runtime actually acts: HLE has no master switch, so an
        // all-off configuration must look exactly like "the runtime was never installed" to
        // every reader of MediaMetadataCache (AppleMusicAssembly's genreFor reads the active
        // profile), and must not warm the persistent stores either.
        if (MetadataCachePublish.activate(configuration)) {
            catalogResolver.applyRegionConfiguration(
                selection = requestPlan.contentUiLanguageSelection,
                // Only a region that names a storefront redirects ordinary Apple Music traffic;
                // 不开启 keeps the account storefront and only restores names.
                regionReplacementRequested = requestPlan.rewritesCatalogRequests,
                localizedMetadataCacheEnabled = configuration.localizedMetadataCache,
            )
        }
        // What we just applied is the baseline: a switch flipped between install and the first
        // request seam has to be reported as a change, and what we installed with must not be.
        appliedConfiguration = configuration
        configurationWatcher.seed()
        // A clear requested just before the previous process ended has no live
        // request seam to ride on, so settle it as soon as the runtime is up.
        metadataCacheClearObserver.observe()

        contentLocalizationHooks = AppleContentLocalizationHooks(
            runtime = runtime,
            catalogResolver = { catalogResolver },
            // HLE re-reads the content-UI selection on every MediaApi request.  The equivalent
            // here is sampling the same TTL snapshot this seam rides on, so a changed region
            // reaches the very next request without a per-request preference read.
            onRequestSeam = ::observeConfigurationSeam,
        )
        // Region replacement installs five layers; the catalog-executor and amp-api
        // layers are the fork's additions and keep their own names so an
        // undeclared profile target reports as a named degradation instead of a
        // silent whole-runtime failure.
        installation.install("media-api-localization") { contentLocalizationHooks.installMediaApiLocalization() }
        installation.install("catalog-request-localization") { contentLocalizationHooks.installCatalogRequestLocalization() }
        installation.install("content-http-localization") { contentLocalizationHooks.installContentHttpLocalization() }
        installation.install("amp-api-http-localization") { contentLocalizationHooks.installAmpApiHttpLocalization() }

        frameworkHooks = AppleFrameworkMetadataHooks(
            runtime = runtime,
            preferences = { null },
            metadataStore = metadataStore,
            effectiveMetadataAlias = ::effectiveAlias,
            activePlaybackIdentity = { this@HleMetadataRuntime.activePlaybackIdentity() },
            logMetadataIdentity = { event, _, details ->
                ProviderLogger.diagnostic("$event: $details")
            },
        )

        playbackCoordinator = ApplePlaybackMetadataCoordinator(
            hookResolver = hookResolver,
            catalogResolver = catalogResolver,
            metadataStore = metadataStore,
            host = playbackHost(),
        )

        installation.install("media-session-metadata") { frameworkHooks.installMediaSessionMetadata() }
        installation.install("media-session-queue") { frameworkHooks.installMediaSessionQueue() }
        installation.install("playback-notification") { frameworkHooks.installPlaybackNotificationMetadata() }
        val playbackMetadataHooks = ApplePlaybackMetadataHooks(
            runtime = runtime,
            playbackHooks = { playbackHooks },
            metadataCoordinator = playbackCoordinator,
        )
        installation.install("playback-metadata") { playbackMetadataHooks.installHooks() }

        contentItemHooks = AppleContentItemMetadataHooks(
            runtime = runtime,
            host = object : AppleContentItemMetadataHost {
                override fun containerNavigationBinding(contentItem: Any) =
                    bridgeContainerNavigationBinding(contentItem)
                override fun effectiveAlias(
                    mediaId: String,
                ): AppleInternalCatalogResolver.Alias? =
                    this@HleMetadataRuntime.effectiveAlias(mediaId)
                override fun registerContainerItem(
                    mediaId: String,
                    contentItem: Any,
                    kind: InAppContainerKind,
                ) = bridgeRegisterContainerItem(mediaId, contentItem, kind)
                override fun localizedEntityType(contentItem: Any) =
                    if (::surfaceBridge.isInitialized) {
                        surfaceBridge.contentItemLocalizedEntityType(contentItem)
                    } else {
                        AppleInternalCatalogResolver.LocalizedEntityType.SONG
                    }
                override fun recordComposeMediaId(mediaId: String) {
                    if (::surfaceBridge.isInitialized) {
                        surfaceBridge.recordComposeMediaId(mediaId)
                    }
                    bridgeMarkMetadataVisible(listOf(mediaId))
                }
                override fun recordCurrentRecyclerMediaId(mediaId: String) {
                    if (::surfaceBridge.isInitialized) {
                        surfaceBridge.recordCurrentRecyclerMediaId(mediaId)
                    }
                }
                override fun requestPriority(mediaId: String) =
                    if (::surfaceBridge.isInitialized) {
                        surfaceBridge.requestPriority(mediaId)
                    } else {
                        AppleInternalCatalogResolver.RequestPriority.VISIBLE
                    }
                override fun shouldResolveFromGetter(
                    priority: AppleInternalCatalogResolver.RequestPriority,
                ) = io.github.proify.lyricon.amprovider.xposed.shouldResolveMetadataFromGetter(priority)
                override fun registerPlaybackItem(
                    mediaId: String,
                    playbackItem: Any,
                    notifyChange: Boolean,
                    analyzeMetadata: Boolean,
                ) = bridgeRegisterPlaybackItem(mediaId, playbackItem, notifyChange, analyzeMetadata)
                override fun shouldRequestOverride(mediaId: String) =
                    if (::surfaceBridge.isInitialized) {
                        surfaceBridge.shouldRequestOverride(mediaId)
                    } else {
                        metadataStore.originalMetadata(mediaId) == null
                    }
                override fun applyAliasToPlaybackItem(
                    playbackItem: Any,
                    alias: AppleInternalCatalogResolver.Alias,
                    notifyChange: Boolean,
                ) = bridgeApplyAliasToPlaybackItem(playbackItem, alias, notifyChange)
                override fun metadataOverride(
                    entityType: AppleInternalCatalogResolver.LocalizedEntityType,
                    getter: AppleContentItemGetter,
                    alias: AppleInternalCatalogResolver.Alias,
                    original: String?,
                ): String? = io.github.proify.lyricon.amprovider.xposed.contentItemMetadataOverride(
                    entityType = entityType,
                    getter = getter,
                    alias = alias,
                    original = original,
                )
            },
        )
        installation.install("content-item") { contentItemHooks.installHooks() }

        queueMetadataHooks = AppleQueueMetadataHooks(
            runtime = runtime,
            metadataStore = metadataStore,
            host = object : AppleQueueMetadataHost {
                override fun activePlaybackIdentity() =
                    this@HleMetadataRuntime.activePlaybackIdentity()
                override fun logMetadataIdentity(
                    event: String,
                    identity: io.github.proify.lyricon.amprovider.xposed.ActivePlaybackMediaIdentity,
                    details: String,
                ) = ProviderLogger.diagnostic("$event: $details")
                override fun media3MetadataId(
                    metadata: Any,
                    fallback: String?,
                    trustedFallback: Boolean,
                ): String? = bridgeMedia3MetadataId(metadata, fallback, trustedFallback)
                override fun media3MetadataDetails(metadata: Any): String =
                    bridgeMedia3MetadataDetails(metadata)
                override fun registerMetadata(
                    mediaId: String,
                    metadata: Any,
                    requestResolution: Boolean,
                    preBind: Boolean,
                    priority: AppleInternalCatalogResolver.RequestPriority,
                ) = bridgeRegisterMetadata(
                    mediaId,
                    metadata,
                    requestResolution,
                    preBind,
                    priority,
                )
                override fun markPlaybackItemHistory(playbackItem: Any) {
                    if (::surfaceBridge.isInitialized) {
                        surfaceBridge.markPlaybackItemHistory(playbackItem)
                    }
                }
                override fun registerPlaybackItem(
                    mediaId: String,
                    playbackItem: Any,
                    notifyChange: Boolean,
                    analyzeMetadata: Boolean,
                ) = bridgeRegisterPlaybackItem(mediaId, playbackItem, notifyChange, analyzeMetadata)
                override fun contentItemMediaId(contentItem: Any, refresh: Boolean): String? =
                    contentItemHooks.mediaId(contentItem, refresh)
                override fun effectiveAlias(mediaId: String) =
                    this@HleMetadataRuntime.effectiveAlias(mediaId)
                override fun applyAliasToPlaybackItem(
                    playbackItem: Any,
                    alias: AppleInternalCatalogResolver.Alias,
                    notifyChange: Boolean,
                ) = bridgeApplyAliasToPlaybackItem(playbackItem, alias, notifyChange)
                override fun shouldRequestOverride(mediaId: String) = true
                override fun ensureOverride(
                    mediaId: String,
                    preBind: Boolean,
                    priority: AppleInternalCatalogResolver.RequestPriority,
                ) = bridgeEnsureOverride(mediaId, preBind, priority)
                override fun ensureOverrides(
                    mediaIds: Collection<String>,
                    preBind: Boolean,
                    originalResolutionLimit: Int,
                ) = bridgeEnsureOverrides(mediaIds, preBind, originalResolutionLimit)
                override fun readPlaybackItemValue(
                    playbackItem: Any,
                    field: io.github.proify.lyricon.amprovider.xposed.InAppPlaybackItemField,
                    contract: io.github.proify.lyricon.amprovider.xposed.InAppPlaybackItemContract,
                ): String? = null
                override fun markMetadataVisible(mediaIds: Collection<String>) = bridgeMarkMetadataVisible(mediaIds)
                override fun isCurrentMetadataSurfaceMediaId(mediaId: String) =
                    bridgeIsCurrentMetadataSurfaceMediaId(mediaId)
                override fun hasLivePlaybackItem(mediaId: String) = bridgeHasLivePlaybackItem(mediaId)
            },
        )
        installation.install("queue-history") { queueMetadataHooks.installHooks() }

        actionSheetMetadataHooks = AppleActionSheetMetadataHooks(
            runtime = runtime,
            host = object : AppleActionSheetMetadataHost {
                override fun activePlaybackIdentity() =
                    this@HleMetadataRuntime.activePlaybackIdentity()
                override fun markMetadataVisible(mediaIds: Collection<String>) = bridgeMarkMetadataVisible(mediaIds)
                override fun rawContentItemValue(
                    contentItem: Any,
                    runtimeMember: io.github.proify.lyricon.amprovider.xposed.AppleMusicRuntimeMember,
                ): Any? = bridgeRawContentItemValue(contentItem, runtimeMember)
                override fun recordArtistAssociation(mediaId: String, item: Any, rawTitle: String?) {
                    if (::surfaceBridge.isInitialized) {
                        surfaceBridge.recordArtistAssociation(mediaId, item, rawTitle)
                    }
                }
                override fun effectiveAlias(mediaId: String) =
                    this@HleMetadataRuntime.effectiveAlias(mediaId)
                override fun knownValues(
                    mediaId: String,
                    field: io.github.proify.lyricon.amprovider.xposed.VisibleTextField,
                ): Set<String> = bridgeKnownValues(mediaId, field)
                override fun shouldRequestOverride(mediaId: String) =
                    if (::surfaceBridge.isInitialized) {
                        surfaceBridge.shouldRequestOverride(mediaId)
                    } else {
                        metadataStore.originalMetadata(mediaId) == null
                    }
                override fun ensureOverride(
                    mediaId: String,
                    priority: AppleInternalCatalogResolver.RequestPriority,
                ) = bridgeEnsureOverride(mediaId, false, priority)
                override fun localizedText(
                    field: io.github.proify.lyricon.amprovider.xposed.VisibleTextField,
                    alias: AppleInternalCatalogResolver.Alias,
                ): String = when (field) {
                    io.github.proify.lyricon.amprovider.xposed.VisibleTextField.TITLE -> alias.title
                    io.github.proify.lyricon.amprovider.xposed.VisibleTextField.ARTIST -> alias.artist
                    io.github.proify.lyricon.amprovider.xposed.VisibleTextField.ALBUM -> alias.album
                }
                override fun logMetadataIdentity(
                    event: String,
                    identity: io.github.proify.lyricon.amprovider.xposed.ActivePlaybackMediaIdentity?,
                    details: String,
                ) = ProviderLogger.diagnostic("$event: $details")
            },
        )
        installation.install("action-sheet") { actionSheetMetadataHooks.installHooks() }

        // The generic adapter is intentionally not used for the specialized
        // HLE surface points: installing both would double-hook the same
        // profiled methods and let a conservative string hook overwrite the
        // identity-aware HLE result. The bridge below installs HLE's complete
        // Listen Now/library/data-binding/artist/collection surface instead.
        // The content-item hooks are installed earlier and guard on `::surfaceBridge.isInitialized`,
        // so a failed install must leave the field unset instead of exposing a half-initialised
        // bridge that keeps throwing on every metadata read.
        val installedBridge = HleMetadataSurfaceBridge(
            runtime = runtime,
            catalogResolver = catalogResolver,
            metadataStore = metadataStore,
            playbackCoordinator = playbackCoordinator,
            playbackHooks = playbackHooks,
            frameworkHooks = frameworkHooks,
            contentItemHooks = contentItemHooks,
            queueMetadataHooks = queueMetadataHooks,
            actionSheetMetadataHooks = actionSheetMetadataHooks,
            // All three gates are supplied, not snapshotted: HLE re-reads them per call, and the
            // surface bridge outlives the value that was on disk when the hook was installed.
            configuredContentUiLanguage = { configuration.plan.contentUiLanguageSelection },
            overrideAccountLanguage = { selection -> configuration.shouldOverrideAccountLanguage(selection) },
            restoreOriginalMetadata = { configuration.isRestoreOriginalMetadataEnabled },
            initialProfileId = requestPlan.cacheNamespace,
            publishMetadataCache = { MetadataCachePublish.isActive(configuration) },
            installation = installation,
            observeMetadataCacheClear = metadataCacheClearObserver::observe,
            observeMetadataConfiguration = ::observeConfigurationSeam,
        )
        installedBridge.install()
        surfaceBridge = installedBridge
        bridgeEnsureOverride = surfaceBridge::ensureOverride
        bridgeEnsureOverrides = surfaceBridge::ensureOverrides
        bridgeRegisterMetadata = surfaceBridge::registerMetadata
        bridgeRegisterPlaybackItem = surfaceBridge::registerPlaybackItem
        bridgeApplyAliasToPlaybackItem = surfaceBridge::applyAliasToPlaybackItem
        bridgeMarkMetadataVisible = surfaceBridge::markMetadataVisible
        bridgeSetPlaybackMediaId = surfaceBridge::setPlaybackMediaId
        bridgeRegisterContainerItem = surfaceBridge::registerContainerItem
        bridgeContainerNavigationBinding = surfaceBridge::containerNavigationBinding
        bridgeRawContentItemValue = surfaceBridge::rawContentItemValue
        bridgeKnownValues = surfaceBridge::knownValues
        bridgeHasLivePlaybackItem = surfaceBridge::hasLivePlaybackItem
        bridgeEffectiveAlias = surfaceBridge::effectiveAlias
        bridgeMedia3MetadataId = surfaceBridge::media3MetadataId
        bridgeMedia3MetadataDetails = surfaceBridge::media3MetadataDetails
        bridgeIsCurrentMetadataSurfaceMediaId = surfaceBridge::isCurrentMetadataSurfaceMediaId

        return installation.result(
            "HLE metadata runtime installed for ${version.displayName}; " +
                "region=${region.catalogStorefront ?: "account"}, " +
                "restoreOriginal=${requestPlan.probesOriginalMetadata}, " +
                "original metadata + persistent SQLite cache enabled",
        )
    }

    private fun activePlaybackIdentity(): io.github.proify.lyricon.amprovider.xposed.ActivePlaybackMediaIdentity =
        if (::surfaceBridge.isInitialized) {
            surfaceBridge.activePlaybackIdentity()
        } else {
            val mediaId = if (::playbackCoordinator.isInitialized) {
                playbackCoordinator.currentMetadataId()
            } else {
                null
            }
            io.github.proify.lyricon.amprovider.xposed.ActivePlaybackMediaIdentity(
                mediaId = mediaId,
                source = "ampp_hle",
                candidates = mediaId.orEmpty(),
            )
        }

    private fun playbackHost() = object : ApplePlaybackMetadataCoordinatorHost {
        override fun activePlayer(): Any? = playbackHooks.activePlayer()
        override fun configuredContentUiLanguage(): Int =
            configuration.plan.contentUiLanguageSelection
        override fun shouldOverrideAccountLanguage(selection: Int): Boolean =
            // HLE's shouldOverrideAccountLanguage(selection): 不开启 never overrides, and the
            // switch is read now, not when the hook was installed.
            configuration.shouldOverrideAccountLanguage(selection)
        override fun shouldRestoreCjkOriginalMetadata(metadata: MediaMetadataCache.Metadata): Boolean =
            configuration.isRestoreOriginalMetadataEnabled &&
                AppleOriginalMetadataPolicy.shouldProbeCjkOriginalMetadata(
                mediaId = metadata.id,
                title = metadata.title,
                artist = metadata.artist,
                genre = metadata.genre,
            )
        override fun shouldPublishMetadataCache(): Boolean =
            // The in-memory cache is the playback pipeline's publish target, and a reader outside
            // this runtime (AppleMusicAssembly's genreFor) can see it: an all-off configuration
            // must not feed it.
            MetadataCachePublish.isActive(configuration)
        override fun ensureContentItemMetadataHooks(contentItemClass: Class<*>) {
            if (::contentItemHooks.isInitialized) contentItemHooks.ensureHooks(contentItemClass)
        }
        override fun setMetadataPlaybackMediaId(mediaId: String) =
            bridgeSetPlaybackMediaId(mediaId)
        override fun onCurrentPlaybackItem(mediaId: String, playbackItem: Any, queueId: Long) =
            bridgeRegisterPlaybackItem(mediaId, playbackItem, false, true)
        override fun effectiveMetadataAlias(mediaId: String): AppleInternalCatalogResolver.Alias? =
            effectiveAlias(mediaId)
        override fun applyPlaybackMetadataOverride(
            mediaId: String,
            alias: AppleInternalCatalogResolver.Alias,
            rememberLocalizedArtist: Boolean,
            originalMetadata: Boolean,
            originalMetadataConfirmed: Boolean,
        ) {
            if (::surfaceBridge.isInitialized) {
                surfaceBridge.applyPlaybackMetadataOverride(
                    mediaId = mediaId,
                    alias = alias,
                    forceInAppRebind = true,
                    rememberLocalizedArtist = rememberLocalizedArtist,
                    originalMetadata = originalMetadata,
                    originalMetadataConfirmed = originalMetadataConfirmed,
                )
            } else if (originalMetadata && !configuration.isRestoreOriginalMetadataEnabled) {
                return
            } else if (originalMetadata) {
                metadataStore.rememberOriginalMetadata(mediaId, alias, originalMetadataConfirmed)
            } else {
                metadataStore.rememberConfiguredMetadata(mediaId, alias)
            }
            if (!::surfaceBridge.isInitialized) {
                frameworkHooks.refreshMediaSessionMetadata(mediaId, alias)
            }
        }
        override fun logMetadataIdentity(event: String, details: String) =
            ProviderLogger.diagnostic("$event: $details")
        override fun validatedOriginalSongAlias(
            alias: AppleInternalCatalogResolver.Alias?,
            localizedTitle: String?,
            localizedArtist: String?,
        ): AppleInternalCatalogResolver.Alias? =
            io.github.proify.lyricon.amprovider.xposed.validatedOriginalSongAlias(
                alias = alias,
                localizedTitle = localizedTitle,
                localizedArtist = localizedArtist,
            )
        override fun shouldShareOriginalSongLanguage(
            localizedTitle: String?,
            localizedArtist: String?,
            alias: AppleInternalCatalogResolver.Alias?,
        ): Boolean = if (::surfaceBridge.isInitialized) {
            surfaceBridge.shouldShareOriginalSongLanguage(
                localizedTitle = localizedTitle,
                localizedArtist = localizedArtist,
                alias = alias,
            )
        } else {
            false
        }
        override fun rememberOriginalLanguageForArtist(mediaId: String, language: String) {
            if (::surfaceBridge.isInitialized) {
                surfaceBridge.rememberOriginalLanguageForArtist(mediaId, language)
            }
        }
        override fun isRestoreOriginalMetadataEnabled(): Boolean =
            configuration.isRestoreOriginalMetadataEnabled
    }

    private fun effectiveAlias(mediaId: String): AppleInternalCatalogResolver.Alias? =
        bridgeEffectiveAlias(mediaId)

    // ------------------------------------------------------- configuration refresh

    /** Guards the posted refresh so a burst of requests schedules one run, never a re-entry. */
    private val configurationRefreshScheduled = AtomicBoolean(false)

    /** The configuration the live seams currently behave as; the baseline for change detection. */
    @Volatile
    private var appliedConfiguration: RegionMetadataConfiguration? = null

    /** Uptime of the last refresh attempt, so retries are rate-limited (see [refreshRetryDue]). */
    @Volatile
    private var lastRefreshAttemptMillis: Long = Long.MIN_VALUE

    /**
     * The request-side replacement for HLE's `registerOnSharedPreferenceChangeListener`.
     *
     * Runs at most once per TTL window (the watcher caches the value), reports a change once,
     * and hands the actual work to the main thread: applying the region touches the MediaApi
     * storefront, and the surface clears touch live views.
     */
    private fun observeConfigurationSeam() {
        // The watcher only offers a change it has not had accepted yet, so no empty check here.
        configurationWatcher.observe {
            scheduleConfigurationRefresh()
            true
        }
        // Self-healing retry.  The baseline only advances when every owed action succeeded, so a
        // failed apply — or a value that moved while one was running — still differs from
        // [appliedConfiguration] here and is offered again.  Rate-limited, because a
        // persistently failing action must not turn every request into a main-thread retry.
        if (configurationRefreshScheduled.get()) return
        if (configurationWatcher.current() == appliedConfiguration) return
        if (!refreshRetryDue()) return
        scheduleConfigurationRefresh()
    }

    private fun refreshRetryDue(): Boolean {
        val last = lastRefreshAttemptMillis
        if (last == Long.MIN_VALUE) return true
        return clock() - last >= METADATA_CONFIGURATION_RETRY_INTERVAL_MS
    }

    private fun scheduleConfigurationRefresh() {
        if (!configurationRefreshScheduled.compareAndSet(false, true)) return
        lastRefreshAttemptMillis = clock()
        // post() answers false once the main looper has quit; leaving the flag set would drop
        // every later change for the rest of the process.
        val posted = runCatching {
            runtime.mainHandler.post { runConfigurationRefresh() }
        }.getOrDefault(false)
        if (!posted) configurationRefreshScheduled.set(false)
    }

    private fun runConfigurationRefresh() {
        try {
            applyConfigurationChange()
        } catch (error: Throwable) {
            // A settings change must never take the host down.
            ProviderLogger.error("Apple Music 元数据配置变更应用失败", error)
        } finally {
            configurationRefreshScheduled.set(false)
        }
    }

    /**
     * HLE's listener body, one branch per owed action:
     * `applyConfiguredContentUiLanguage` first (storefront/language/cache switch) and then
     * `metadataConfigurationDispatcher.dispatch()` (clear, restore, refresh).
     *
     * The baseline only advances once every owed action succeeded: a failed storefront write or
     * a failed surface clear must be offered again by the next request seam instead of being
     * recorded as applied and silently lost.
     */
    private fun applyConfigurationChange() {
        val previous = appliedConfiguration
        val current = configurationWatcher.current()
        val actions = RegionMetadataRefreshPolicy.actionsFor(previous, current)
        if (actions.isEmpty()) {
            appliedConfiguration = current
            return
        }
        var applied = true
        if (RegionMetadataRefreshAction.APPLY_REGION in actions) {
            applied = runCatching { applyConfiguredRegion(current) }
                .onFailure {
                    ProviderLogger.error(
                        "Apple Music 地区配置应用失败，将在下次请求重试: " +
                            "region=" + current.region.storageValue,
                        it,
                    )
                }
                .isSuccess
        }
        if (RegionMetadataRefreshAction.DISPATCH_CONFIGURATION_CHANGE in actions) {
            applied = dispatchConfigurationChange() && applied
        }
        if (!applied) return
        appliedConfiguration = current
        ProviderLogger.info(
            "Apple Music 元数据配置已生效: region=${current.region.storageValue}, " +
                "override=${current.overrideAccountLanguage}, " +
                "restoreOriginal=${current.restoreCjkOriginalMetadata}, " +
                "actions=${actions.joinToString("+") { it.name.lowercase() }}"
        )
        if (previous != null && previous.region.cacheNamespace != current.region.cacheNamespace) {
            // The persistent stores are opened with the install-time namespace; switching those
            // live is out of scope here, so say so rather than pretend otherwise.  The in-memory
            // profile does follow (see applyConfiguredRegion).
            ProviderLogger.info(
                "Apple Music 地区检索库命名空间变更: ${previous.region.cacheNamespace}->" +
                    "${current.region.cacheNamespace}; 本次进程沿用安装时的持久库，重启后切换"
            )
        }
    }

    /**
     * Re-runs the region engine, then republishes the in-memory profile.
     *
     * The engine always runs, even when the change turns every control off: handing the
     * storefront back to the account is exactly what that branch does.  The profile, on the
     * other hand, only follows while the runtime still has work to do, and is dropped when it
     * does not — otherwise a reader like AppleMusicAssembly's genreFor would keep seeing
     * entries this runtime published, which is a state an all-off install never had.
     */
    private fun applyConfiguredRegion(configuration: RegionMetadataConfiguration) {
        catalogResolver.applyRegionConfiguration(
            selection = configuration.plan.contentUiLanguageSelection,
            regionReplacementRequested = configuration.plan.rewritesCatalogRequests,
            localizedMetadataCacheEnabled = configuration.localizedMetadataCache,
        )
        if (MetadataCachePublish.activate(configuration)) {
            if (::surfaceBridge.isInitialized) {
                surfaceBridge.updateCacheProfile(configuration.region.cacheNamespace)
            }
        } else {
            // Nothing will be published from here on; leave the cache exactly as an all-off
            // install would have left it.
            runCatching { MediaMetadataCache.clearProfile() }
        }
    }

    /** Returns false when the surfaces could not be cleared, so the change is retried. */
    private fun dispatchConfigurationChange(): Boolean {
        if (!::surfaceBridge.isInitialized) {
            // The installed surfaces are not up this early; the region layer above still
            // followed the change, so report what could not be refreshed instead of failing.
            ProviderLogger.info(
                "Apple Music 元数据配置变更: 表面层尚未安装，跳过状态清理与当前项刷新"
            )
            return true
        }
        return surfaceBridge.dispatchMetadataConfigurationChange()
    }
}
