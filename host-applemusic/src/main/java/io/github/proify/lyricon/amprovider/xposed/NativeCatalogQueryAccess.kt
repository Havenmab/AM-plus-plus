package io.github.proify.lyricon.amprovider.xposed

import android.content.Context
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import com.juren233.hyperlyricsenhanced.BuildConfig
import com.juren233.hyperlyricsenhanced.common.RootConstants
import com.juren233.hyperlyricsenhanced.common.lyric.AppleOriginalMetadataPolicy
import java.lang.reflect.Field
import java.lang.reflect.Method
import java.lang.reflect.Modifier
import java.lang.reflect.Proxy
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executor
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.atomic.AtomicReference

import io.github.proify.lyricon.amprovider.xposed.AppleInternalCatalogResolver.CatalogAccess
import io.github.proify.lyricon.amprovider.xposed.AppleInternalCatalogResolver.CatalogResponseSnapshot
import io.github.proify.lyricon.amprovider.xposed.AppleInternalCatalogResolver.CatalogEntitySnapshot
import io.github.proify.lyricon.amprovider.xposed.AppleInternalCatalogResolver.CatalogArtistSnapshot
import io.github.proify.lyricon.amprovider.xposed.AppleInternalCatalogResolver.CatalogIdentity
import io.github.proify.lyricon.amprovider.xposed.AppleInternalCatalogResolver.CatalogSong
import io.github.proify.lyricon.amprovider.xposed.AppleInternalCatalogResolver.LocalizedRequest
import io.github.proify.lyricon.amprovider.xposed.AppleInternalCatalogResolver.LockedIsrcFallbackTask
import io.github.proify.lyricon.amprovider.xposed.AppleInternalCatalogResolver.OriginalEntityRequest
import io.github.proify.lyricon.amprovider.xposed.AppleInternalCatalogResolver.Alias
import io.github.proify.lyricon.amprovider.xposed.AppleInternalCatalogResolver.OriginalResolution
import io.github.proify.lyricon.amprovider.xposed.AppleInternalCatalogResolver.LocalizedLookup
import io.github.proify.lyricon.amprovider.xposed.AppleInternalCatalogResolver.RequestPriority
import io.github.proify.lyricon.amprovider.xposed.AppleInternalCatalogResolver.LocalizedEntityType
import io.github.proify.lyricon.amprovider.xposed.AppleInternalCatalogResolver.Companion.CURRENT_LANGUAGE
import io.github.proify.lyricon.amprovider.xposed.AppleInternalCatalogResolver.Companion.CACHE_SIZE
import io.github.proify.lyricon.amprovider.xposed.AppleInternalCatalogResolver.Companion.LOCALIZED_CACHE_SIZE
import io.github.proify.lyricon.amprovider.xposed.AppleInternalCatalogResolver.Companion.LOCALIZED_ARTIST_ALIAS_CACHE_SIZE
import io.github.proify.lyricon.amprovider.xposed.AppleInternalCatalogResolver.Companion.REQUEST_PRIORITY_CACHE_SIZE
import io.github.proify.lyricon.amprovider.xposed.AppleInternalCatalogResolver.Companion.ORIGINAL_METADATA_CACHE_SCHEMA
import io.github.proify.lyricon.amprovider.xposed.AppleInternalCatalogResolver.Companion.LOCALIZED_BATCH_SIZE
import io.github.proify.lyricon.amprovider.xposed.AppleInternalCatalogResolver.Companion.MAX_LOCALIZED_BATCHES_RUNNING
import io.github.proify.lyricon.amprovider.xposed.AppleInternalCatalogResolver.Companion.MAX_BACKGROUND_LOCALIZED_BATCHES_RUNNING
import io.github.proify.lyricon.amprovider.xposed.AppleInternalCatalogResolver.Companion.MAX_LOCKED_ISRC_FALLBACK_RUNNING
import io.github.proify.lyricon.amprovider.xposed.AppleInternalCatalogResolver.Companion.LOCALIZED_BATCH_DELAY_MS
import io.github.proify.lyricon.amprovider.xposed.AppleInternalCatalogResolver.Companion.ORIGINAL_ENTITY_BATCH_SIZE
import io.github.proify.lyricon.amprovider.xposed.AppleInternalCatalogResolver.Companion.MAX_ORIGINAL_ENTITY_BATCHES_RUNNING
import io.github.proify.lyricon.amprovider.xposed.AppleInternalCatalogResolver.Companion.MAX_BACKGROUND_ORIGINAL_ENTITY_BATCHES_RUNNING
import io.github.proify.lyricon.amprovider.xposed.AppleInternalCatalogResolver.Companion.ORIGINAL_ENTITY_BATCH_DELAY_MS
import io.github.proify.lyricon.amprovider.xposed.AppleInternalCatalogResolver.Companion.QUERY_SLOW_RESPONSE_MS
import io.github.proify.lyricon.amprovider.xposed.AppleInternalCatalogResolver.Companion.QUERY_TIMEOUT_MS
import io.github.proify.lyricon.amprovider.xposed.AppleInternalCatalogResolver.Companion.ARTIST_ALIAS_CACHE_SCHEMA
import io.github.proify.lyricon.amprovider.xposed.AppleInternalCatalogResolver.Companion.CATALOG_REQUEST_TOKEN_PARAM
import io.github.proify.lyricon.amprovider.xposed.AppleInternalCatalogResolver.Companion.COLLABORATION_ARTIST_PATTERNS
import io.github.proify.lyricon.amprovider.xposed.AppleInternalCatalogResolver.Companion.collaborationArtistCache

import io.github.proify.lyricon.amprovider.xposed.AppleInternalCatalogResolver.Companion.isCoroutineSuspended
import io.github.proify.lyricon.amprovider.xposed.AppleInternalCatalogResolver.Companion.originalSongCacheKey
import io.github.proify.lyricon.amprovider.xposed.AppleInternalCatalogResolver.Companion.originalDirectEntityCacheKey
import io.github.proify.lyricon.amprovider.xposed.AppleInternalCatalogResolver.Companion.legacyAmbiguousSongCacheKey
import io.github.proify.lyricon.amprovider.xposed.AppleInternalCatalogResolver.Companion.originalEntityCacheKey
import io.github.proify.lyricon.amprovider.xposed.AppleInternalCatalogResolver.Companion.originalEntityCacheLookupKeys
import io.github.proify.lyricon.amprovider.xposed.AppleInternalCatalogResolver.Companion.localizedMetadataCacheKey
import io.github.proify.lyricon.amprovider.xposed.AppleInternalCatalogResolver.Companion.isLocalizedArtistAliasCacheKey
import io.github.proify.lyricon.amprovider.xposed.AppleInternalCatalogResolver.Companion.originalLanguageCacheKeyVariants
import io.github.proify.lyricon.amprovider.xposed.AppleInternalCatalogResolver.Companion.selectNextRequestIndex
import io.github.proify.lyricon.amprovider.xposed.AppleInternalCatalogResolver.Companion.canStartRequest
import io.github.proify.lyricon.amprovider.xposed.AppleInternalCatalogResolver.Companion.higherPriority
import io.github.proify.lyricon.amprovider.xposed.AppleInternalCatalogResolver.Companion.priorityForRequestScope
import io.github.proify.lyricon.amprovider.xposed.AppleInternalCatalogResolver.Companion.normalizeRequestScopeIds
import io.github.proify.lyricon.amprovider.xposed.AppleInternalCatalogResolver.Companion.languageTagsForGenre
import io.github.proify.lyricon.amprovider.xposed.AppleInternalCatalogResolver.Companion.knownLanguageTagsForGenre
import io.github.proify.lyricon.amprovider.xposed.AppleInternalCatalogResolver.Companion.languageTagsForIsrc
import io.github.proify.lyricon.amprovider.xposed.AppleInternalCatalogResolver.Companion.languageTagsForOriginalMetadata
import io.github.proify.lyricon.amprovider.xposed.AppleInternalCatalogResolver.Companion.languageTagsForOriginalMetadata
import io.github.proify.lyricon.amprovider.xposed.AppleInternalCatalogResolver.Companion.canonicalOriginalLanguage
import io.github.proify.lyricon.amprovider.xposed.AppleInternalCatalogResolver.Companion.supportedOriginalLanguageOrNull
import io.github.proify.lyricon.amprovider.xposed.AppleInternalCatalogResolver.Companion.storefrontForOriginalLanguage
import io.github.proify.lyricon.amprovider.xposed.AppleInternalCatalogResolver.Companion.isLegacyTraditionalChineseLanguage
import io.github.proify.lyricon.amprovider.xposed.AppleInternalCatalogResolver.Companion.canonicalCachedOriginalAlias
import io.github.proify.lyricon.amprovider.xposed.AppleInternalCatalogResolver.Companion.regionalOriginalAliases
import io.github.proify.lyricon.amprovider.xposed.AppleInternalCatalogResolver.Companion.isAcceptableOriginalAlias
import io.github.proify.lyricon.amprovider.xposed.AppleInternalCatalogResolver.Companion.selectExactOriginalEntityAlias
import io.github.proify.lyricon.amprovider.xposed.AppleInternalCatalogResolver.Companion.selectExactIdentityAlias
import io.github.proify.lyricon.amprovider.xposed.AppleInternalCatalogResolver.Companion.containsHanCharacters
import io.github.proify.lyricon.amprovider.xposed.AppleInternalCatalogResolver.Companion.storefrontForContentUiLanguage
import io.github.proify.lyricon.amprovider.xposed.AppleInternalCatalogResolver.Companion.languageTagsForContentUiLanguage
import io.github.proify.lyricon.amprovider.xposed.AppleInternalCatalogResolver.Companion.languageTagForContentUiLanguage
import io.github.proify.lyricon.amprovider.xposed.AppleInternalCatalogResolver.Companion.localizedStorefrontHeaderValue
import io.github.proify.lyricon.amprovider.xposed.AppleInternalCatalogResolver.Companion.normalizedArtistNameKey
import io.github.proify.lyricon.amprovider.xposed.AppleInternalCatalogResolver.Companion.artistCacheKey
import io.github.proify.lyricon.amprovider.xposed.AppleInternalCatalogResolver.Companion.storefrontFromContentPath
import io.github.proify.lyricon.amprovider.xposed.AppleInternalCatalogResolver.Companion.isAccountScopedPlaybackPath
import io.github.proify.lyricon.amprovider.xposed.AppleInternalCatalogResolver.Companion.selectLocalizedArtistName
import io.github.proify.lyricon.amprovider.xposed.AppleInternalCatalogResolver.Companion.selectOriginalAlias
import io.github.proify.lyricon.amprovider.xposed.AppleInternalCatalogResolver.Companion.isConfidentOriginalSongAlias
import io.github.proify.lyricon.amprovider.xposed.AppleInternalCatalogResolver.Companion.isReusableOriginalSongAlias
import io.github.proify.lyricon.amprovider.xposed.AppleInternalCatalogResolver.Companion.isCollaborationArtistName
import io.github.proify.lyricon.amprovider.xposed.AppleInternalCatalogResolver.Companion.isOriginalTitle
import io.github.proify.lyricon.amprovider.xposed.AppleInternalCatalogResolver.Companion.shouldResolve
import io.github.proify.lyricon.amprovider.xposed.AppleInternalCatalogResolver.Companion.nonLatinLetterCount
import io.github.proify.lyricon.amprovider.xposed.AppleInternalCatalogResolver.Companion.normalize
import io.github.proify.lyricon.amprovider.xposed.AppleInternalCatalogResolver.Companion.shouldCacheCatalogIdentity
import io.github.proify.lyricon.amprovider.xposed.AppleInternalCatalogResolver.Companion.shouldRetryEmptyCatalogIdentity

internal fun AppleInternalCatalogResolver.queryById(
        mediaId: String,
        language: String?,
        onResult: (CatalogSong?) -> Unit
    ) {
        val queryParams = linkedMapOf(
            "ids" to mediaId,
            "platform" to "android",
            "include[songs]" to "artists"
        )
        language?.let { queryParams["l"] = it }
        query(
            storefront = language?.let(::storefrontForLanguage),
            language = language,
            description = "id=$mediaId",
            path = "songs",
            queryParams = queryParams,
            onResult = onResult
        )
    }


internal fun AppleInternalCatalogResolver.queryByConfiguredRegion(
        mediaIds: List<String>,
        entityType: LocalizedEntityType,
        storefront: String,
        language: String,
        onResult: (Map<String, CatalogSong>) -> Unit,
    ) {
        val queryParams = linkedMapOf(
            "ids" to mediaIds.joinToString(","),
            "l" to language,
            "platform" to "android",
        )
        if (entityType != LocalizedEntityType.ARTIST) {
            queryParams["include[${entityType.path}]"] = "artists"
        }
        queryResponse(
            storefront = storefront,
            language = language,
            description = "localized-${entityType.path}-ids=${mediaIds.size}",
            path = entityType.path,
            queryParams = queryParams,
            snapshotEntityType = entityType,
            transformOffMain = { snapshot ->
                runCatching {
                    snapshot
                        ?.let { parseCatalogEntities(it, language, entityType) }
                        .orEmpty()
                        .mapNotNull { song -> song.id?.let { it to song } }
                        .toMap()
                }.onFailure { error ->
                    ProviderLogger.error(
                        "Apple 地区批量元数据响应解析失败: entityType=$entityType, " +
                            "ids=${mediaIds.size}, storefront=$storefront, language=$language",
                        error,
                    )
                }.getOrDefault(emptyMap())
            },
        ) { songs ->
            val byId = songs.orEmpty()
            // Identity cache writes are bounded but may fan out over a 50-item response.  Keep
            // that preprocessing off the UI thread and publish only the immutable map/callback.
            catalogBackgroundExecutor.execute {
                runCatching {
                    byId.forEach(::rememberCatalogIdentity)
                }.onFailure { error ->
                    ProviderLogger.error(
                        "Apple 地区批量元数据缓存预处理失败: entityType=$entityType, " +
                            "ids=${mediaIds.size}, storefront=$storefront, language=$language",
                        error,
                    )
                }
                mainHandler.post {
                    ProviderLogger.info(
                        "Apple 地区批量元数据候选: entityType=$entityType, " +
                            "requested=${mediaIds.size}, resolved=${byId.size}, " +
                            "storefront=$storefront, language=$language"
                    )
                    onResult(byId)
                }
            }
        }
    }


internal fun AppleInternalCatalogResolver.queryByIsrc(
        isrc: String,
        language: String,
        storefrontOverride: String? = null,
        onResult: (CatalogSong?) -> Unit
    ) {
        val queryParams = linkedMapOf(
            "filter[isrc]" to isrc,
            "l" to language,
            "platform" to "android",
            "include[songs]" to "artists",
            "limit" to "1"
        )
        query(
            storefront = storefrontOverride ?: storefrontForLanguage(language),
            language = language,
            description = "isrc=$isrc",
            path = "songs",
            queryParams = queryParams,
            onResult = onResult
        )
    }


internal fun AppleInternalCatalogResolver.query(
        storefront: String?,
        language: String?,
        description: String,
        path: String,
        queryParams: Map<String, String>,
        onResult: (CatalogSong?) -> Unit
    ) {
        queryResponse(
            storefront = storefront,
            language = language,
            description = description,
            path = path,
            queryParams = queryParams,
            transformOffMain = { snapshot ->
                runCatching {
                    snapshot?.let {
                        parseCatalogSong(it, language ?: CURRENT_LANGUAGE)
                    }
                }.onFailure { error ->
                    ProviderLogger.error(
                        "Apple 内部原名响应解析失败: $description, language=$language",
                        error,
                    )
                }.getOrNull()
            },
        ) { song ->
            ProviderLogger.info(
                "Apple 内部原名候选: $description, storefront=$storefront, " +
                    "language=$language, value=${song?.alias?.title}/${song?.alias?.artist}, " +
                    "isrc=${song?.isrc}"
            )
            onResult(song)
        }
    }

    /**
     * The host query itself intentionally remains in the main adapter: Apple Music's MediaApi,
     * storefront mutation, and Continuation contract are not proven thread-safe.  After a result
     * arrives, [AppleCatalogResponseWorkDispatcher] copies host values on main and runs
     * [transformOffMain] on the Catalog CPU executor before publishing the existing main-thread
     * callback.
     */

internal fun <Result> AppleInternalCatalogResolver.queryResponse(
        storefront: String?,
        language: String?,
        description: String,
        path: String,
        queryParams: Map<String, String>,
        snapshotEntityType: LocalizedEntityType = LocalizedEntityType.SONG,
        transformOffMain: (CatalogResponseSnapshot?) -> Result,
        onResult: (Result?) -> Unit,
    ) {
        val diagnosticRequestId = catalogDiagnosticSequence.incrementAndGet().toString(36)
        val queuedAtMs = SystemClock.uptimeMillis()
        logCatalogRequestDiagnostic(
            requestId = diagnosticRequestId,
            event = "queued",
            description = description,
            storefront = storefront,
            language = language,
            elapsedMs = 0L,
        )
        mainHandler.post {
            var requestToken: String? = null
            val completed = AtomicBoolean(false)
            var slowResponse: Runnable? = null
            var timeout: Runnable? = null

            val responseDiagnostic = AtomicReference<String?>(null)
            // Set only for the module-internal, untargeted identity probe.  It carries the one
            // bounded decision line that says which storefront the lookup actually used; the
            // outcome (response size or failure) is appended when this request terminates.
            var moduleIdentityDetail: String? = null
            fun logModuleIdentityOutcome(outcome: String) {
                val detail = moduleIdentityDetail ?: return
                moduleIdentityDetail = null
                logModuleIdentityLookup(detail, outcome)
            }
            val responseTask = catalogResponseDispatcher.newTask<Any, CatalogResponseSnapshot, Result>(
                snapshotOnMain = { response ->
                    val snapshot = response?.let {
                        snapshotCatalogResponse(it, snapshotEntityType)
                    }
                    responseDiagnostic.set(catalogResponseDiagnostic(response, snapshot))
                    snapshot
                },
                transformOffMain = transformOffMain,
                publishOnMain = { result ->
                    logCatalogRequestDiagnostic(
                        requestId = diagnosticRequestId,
                        event = "response",
                        description = description,
                        storefront = storefront,
                        language = language,
                        requestToken = requestToken,
                        elapsedMs = SystemClock.uptimeMillis() - queuedAtMs,
                        detail = responseDiagnostic.get(),
                    )
                    logModuleIdentityOutcome(responseDiagnostic.get() ?: "response")
                    onResult(result)
                },
                failOnMain = { error ->
                    ProviderLogger.error(
                        "Apple 内部目录响应后台处理失败: $description, language=$language",
                        error,
                    )
                    logCatalogRequestDiagnostic(
                        requestId = diagnosticRequestId,
                        event = "transform_failed",
                        description = description,
                        storefront = storefront,
                        language = language,
                        requestToken = requestToken,
                        elapsedMs = SystemClock.uptimeMillis() - queuedAtMs,
                        detail = "error=${error.javaClass.name}:${error.message}",
                    )
                    logModuleIdentityOutcome("transform_failed:${error.javaClass.simpleName}")
                    onResult(null)
                },
            )

            fun finish(response: Any?, event: String = "response") {
                if (!completed.compareAndSet(false, true)) {
                    logCatalogRequestDiagnostic(
                        requestId = diagnosticRequestId,
                        event = "late_$event",
                        description = description,
                        storefront = storefront,
                        language = language,
                        requestToken = requestToken,
                        elapsedMs = SystemClock.uptimeMillis() - queuedAtMs,
                        detail = "late_response_callback",
                    )
                    return
                }
                slowResponse?.let(mainHandler::removeCallbacks)
                timeout?.let(mainHandler::removeCallbacks)
                requestToken?.let(pendingCatalogRequests::remove)
                if (!responseTask.submit(response)) {
                    logCatalogRequestDiagnostic(
                        requestId = diagnosticRequestId,
                        event = "late_$event",
                        description = description,
                        storefront = storefront,
                        language = language,
                        requestToken = requestToken,
                        elapsedMs = SystemClock.uptimeMillis() - queuedAtMs,
                        detail = "response_task_rejected",
                    )
                }
            }

            fun fail(event: String, error: Throwable) {
                if (!completed.compareAndSet(false, true)) {
                    logCatalogRequestDiagnostic(
                        requestId = diagnosticRequestId,
                        event = "late_$event",
                        description = description,
                        storefront = storefront,
                        language = language,
                        requestToken = requestToken,
                        elapsedMs = SystemClock.uptimeMillis() - queuedAtMs,
                        detail = "error=${error.javaClass.name}:${error.message}",
                    )
                    return
                }
                slowResponse?.let(mainHandler::removeCallbacks)
                timeout?.let(mainHandler::removeCallbacks)
                requestToken?.let(pendingCatalogRequests::remove)
                ProviderLogger.error(
                    "Apple 内部目录直连查询失败: $description, language=$language",
                    error,
                )
                logCatalogRequestDiagnostic(
                    requestId = diagnosticRequestId,
                    event = event,
                    description = description,
                    storefront = storefront,
                    language = language,
                    requestToken = requestToken,
                    elapsedMs = SystemClock.uptimeMillis() - queuedAtMs,
                    detail = "error=${error.javaClass.name}:${error.message}",
                )
                logModuleIdentityOutcome("$event:${error.javaClass.simpleName}")
                // Continuations may resume from an Apple network thread.  Preserve the resolver
                // callback contract by publishing failures on the host main executor as well.
                mainHandler.post { onResult(null) }
            }

            runCatching {
                val access = catalogAccess ?: createCatalogAccess().also { catalogAccess = it }
                // An untargeted module query is the identity/ISRC/genre probe.  It must keep the
                // catalog target Apple's own client resolves, because its catalog IDs only exist
                // there.  A module token marks it as resolver-owned so the region rewrite leaves
                // it alone, and the executor storefront argument is never written from outside:
                // forcing one replaced the app's own resolution and emptied the identity, which
                // stopped the per-song original-name correction.  The shared MediaApi storefront
                // field is the storefront Apple's client builds that argument from, so while the
                // region rewrite holds it at the region's value it has to be handed back to the
                // captured account storefront for the duration of this call, then restored.
                val regionRewriteEnabled = isGlobalRegionRewriteEnabled()
                val untargetedModuleLookup = storefront == null && language == null
                if (untargetedModuleLookup && regionRewriteEnabled) {
                    // Capture before anything switches the field, and only from a value the
                    // region rewrite did not itself write.
                    captureAccountStorefront(access)
                }
                val localization = AppleInternalCatalogResolver.moduleCatalogRequestLocalization(
                    storefront = storefront,
                    language = language,
                    regionRewriteEnabled = regionRewriteEnabled,
                )
                val temporaryFieldStorefront =
                    AppleInternalCatalogResolver.moduleCatalogLookupFieldStorefront(
                        storefront = storefront,
                        language = language,
                        regionRewriteEnabled = regionRewriteEnabled,
                        accountStorefront = accountStorefront?.takeIf { accountStorefrontCaptured },
                    )
                if (localization != null) {
                    if (localization.storefront != null) {
                        // Capture before the field is temporarily switched below, otherwise the
                        // module's own target storefront would be remembered as the account's.
                        captureAccountStorefront(access)
                    }
                    requestToken = catalogRequestSequence.incrementAndGet().toString(36)
                    pendingCatalogRequests[requestToken] = localization
                }
                val directQueryParams = LinkedHashMap(queryParams)
                requestToken?.let { token ->
                    directQueryParams[CATALOG_REQUEST_TOKEN_PARAM] = token
                }
                slowResponse = Runnable {
                    if (completed.get()) return@Runnable
                    logCatalogRequestDiagnostic(
                        requestId = diagnosticRequestId,
                        event = "slow_response",
                        description = description,
                        storefront = storefront,
                        language = language,
                        requestToken = requestToken,
                        elapsedMs = SystemClock.uptimeMillis() - queuedAtMs,
                        detail = "mode=direct-network, slowMs=$QUERY_SLOW_RESPONSE_MS",
                    )
                }.also { mainHandler.postDelayed(it, QUERY_SLOW_RESPONSE_MS) }
                timeout = Runnable {
                    if (!completed.compareAndSet(false, true)) return@Runnable
                    responseTask.cancel()
                    requestToken?.let(pendingCatalogRequests::remove)
                    logCatalogRequestDiagnostic(
                        requestId = diagnosticRequestId,
                        event = "timeout",
                        description = description,
                        storefront = storefront,
                        language = language,
                        requestToken = requestToken,
                        elapsedMs = SystemClock.uptimeMillis() - queuedAtMs,
                        detail = "mode=direct-network, timeoutMs=$QUERY_TIMEOUT_MS",
                    )
                    logModuleIdentityOutcome("timeout")
                    onResult(null)
                }.also { mainHandler.postDelayed(it, QUERY_TIMEOUT_MS) }

                val continuation = createDirectCatalogContinuation(
                    access = access,
                    onSuccess = { response -> finish(response) },
                    onFailure = { error -> fail("request_failed", error) },
                )
                val previousStorefront = access.storefrontField.get(access.mediaApi) as? String
                val targetStorefront = localization?.storefront
                // The field value actually written for the call: the temporary account
                // storefront for an untargeted identity probe, or the request's own target.
                val appliedStorefront = temporaryFieldStorefront ?: targetStorefront
                if (localization != null && untargetedModuleLookup) {
                    moduleIdentityDetail = AppleInternalCatalogResolver.moduleIdentityLookupDetail(
                        fieldBefore = previousStorefront,
                        fieldUsed = temporaryFieldStorefront,
                        idsCount = AppleInternalCatalogResolver.catalogLookupIdCount(queryParams),
                        storefront = localization.storefront,
                        language = localization.language,
                        accountStorefront = accountStorefront,
                        accountStorefrontCaptured = accountStorefrontCaptured,
                    )
                }
                val directResult = try {
                    activeCatalogRequest.set(localization)
                    if (appliedStorefront != null) {
                        access.storefrontField.set(access.mediaApi, appliedStorefront)
                    }
                    access.directQueryMethod.invoke(
                        access.mediaApi,
                        path,
                        directQueryParams,
                        continuation,
                    )
                } finally {
                    activeCatalogRequest.remove()
                    // Only a field still holding our own write is restored to the value observed
                    // before the call.  The region apply/retry writes the same field from another
                    // site; if it won the race, that newer value owns the field and restoring the
                    // stale observation would silently undo the chosen region.
                    val currentStorefront = access.storefrontField.get(access.mediaApi) as? String
                    if (shouldRestoreModuleLookupStorefront(appliedStorefront, currentStorefront)) {
                        access.storefrontField.set(access.mediaApi, previousStorefront)
                    }
                }
                logCatalogRequestDiagnostic(
                    requestId = diagnosticRequestId,
                    event = "observing",
                    description = description,
                    storefront = storefront,
                    language = language,
                    requestToken = requestToken,
                    elapsedMs = SystemClock.uptimeMillis() - queuedAtMs,
                    detail = "mode=direct-network, path=$path, " +
                        "suspended=${isCoroutineSuspended(directResult)}",
                )
                if (!isCoroutineSuspended(directResult)) {
                    finish(directResult)
                }
            }.onFailure { error ->
                activeCatalogRequest.remove()
                fail("start_failed", error)
            }
        }
    }


internal fun AppleInternalCatalogResolver.createDirectCatalogContinuation(
        access: CatalogAccess,
        onSuccess: (Any?) -> Unit,
        onFailure: (Throwable) -> Unit,
    ): Any = Proxy.newProxyInstance(
        access.continuationType.classLoader ?: classLoader,
        arrayOf(access.continuationType),
    ) { proxy, method, args ->
        when (method.name) {
            "getContext" -> access.emptyCoroutineContext
            "resumeWith" -> {
                val result = args?.firstOrNull()
                val failure = coroutineResultFailure(result)
                if (failure != null) onFailure(failure) else onSuccess(result)
                null
            }
            "equals" -> proxy === args?.firstOrNull()
            "hashCode" -> System.identityHashCode(proxy)
            "toString" -> "AppleCatalogContinuation"
            else -> null
        }
    }


internal fun AppleInternalCatalogResolver.coroutineResultFailure(result: Any?): Throwable? {
        if (result is Throwable) return result
        val value = result ?: return null
        // Kotlin's Result.Failure is the only wrapper whose field we need to inspect.  Do not
        // reflect arbitrary Apple response objects from a continuation/network thread.
        if (value.javaClass.name != "kotlin.Result\$Failure") return null
        val fields = value.javaClass.declaredFields.filterNot { field ->
            Modifier.isStatic(field.modifiers)
        }
        if (fields.size != 1) return null
        val field = fields.single()
        if (!Throwable::class.java.isAssignableFrom(field.type)) return null
        field.isAccessible = true
        return field.get(value) as? Throwable
    }


internal fun AppleInternalCatalogResolver.createCatalogAccess(): CatalogAccess {
        val resolvedHolder = resolvedCatalogHolder
        val holderClass = resolvedHolder.clazz
        val mediaApi = NativeCatalogAccessContract.mediaApi(
            holderClass,
            resolvedHolder.target.runtimeMemberName(
                AppleMusicRuntimeMember.MEDIA_API_HOLDER_GET_MEDIA_API_METHOD
            ),
        )
        val storefrontField = NativeCatalogAccessContract.storefrontField(
            mediaApi,
            resolvedHolder.target.runtimeMemberName(
                AppleMusicRuntimeMember.MEDIA_API_STOREFRONT_FIELD
            ),
        )
        val directQueryMethod = findDirectCatalogQueryMethod(
            clazz = mediaApi.javaClass,
            methodName = resolvedHolder.target.runtimeMemberName(
                AppleMusicRuntimeMember.MEDIA_API_DIRECT_QUERY_METHOD
            ),
        )
        val continuationType = directQueryMethod.parameterTypes[2]
        val coroutineContextType = continuationType.methods.firstOrNull { method ->
            method.name == "getContext" && method.parameterCount == 0
        }?.returnType ?: error("Apple Continuation context type unavailable")
        val emptyCoroutineContext = createEmptyCoroutineContext(coroutineContextType)
        return CatalogAccess(
            mediaApi = mediaApi,
            storefrontField = storefrontField,
            directQueryMethod = directQueryMethod,
            continuationType = continuationType,
            emptyCoroutineContext = emptyCoroutineContext,
        )
    }


internal fun AppleInternalCatalogResolver.findDirectCatalogQueryMethod(clazz: Class<*>, methodName: String): Method {
        return AppleCatalogQueryMethod.resolve(clazz, methodName)
    }


internal fun AppleInternalCatalogResolver.createEmptyCoroutineContext(contextType: Class<*>): Any =
        Proxy.newProxyInstance(
            contextType.classLoader ?: classLoader,
            arrayOf(contextType),
        ) { proxy, method, args ->
            when (method.name) {
                "fold" -> args?.firstOrNull()
                "get" -> null
                "minusKey" -> proxy
                "plus" -> args?.firstOrNull()
                "equals" -> proxy === args?.firstOrNull()
                "hashCode" -> 0
                "toString" -> "EmptyCoroutineContext"
                else -> null
            }
        }


internal fun AppleInternalCatalogResolver.findField(instance: Any, name: String): Field {
        var current: Class<*>? = instance.javaClass
        while (current != null) {
            current.declaredFields.firstOrNull { field -> field.name == name }?.let { field ->
                field.isAccessible = true
                return field
            }
            current = current.superclass
        }
        error("${instance.javaClass.name}#$name unavailable")
    }


internal fun AppleInternalCatalogResolver.logCatalogRequestDiagnostic(
        requestId: String,
        event: String,
        description: String,
        storefront: String?,
        language: String?,
        elapsedMs: Long,
        requestToken: String? = null,
        detail: String? = null,
    ) {
        if (!BuildConfig.DEBUG) return
        val localizedState = synchronized(localizedPending) {
            "${localizedPending.size}/$localizedBatchesRunning"
        }
        val originalState = synchronized(originalEntityPending) {
            "${originalEntityPending.size}/$originalEntityBatchesRunning"
        }
        ProviderLogger.diagnostic(
            "AppleCatalogRequest: id=$requestId, token=${requestToken ?: "none"}, " +
                "event=$event, description=$description, storefront=$storefront, " +
                "language=$language, elapsedMs=$elapsedMs, " +
                "localizedPendingRunning=$localizedState, " +
                "originalPendingRunning=$originalState" +
                detail?.let { ", $it" }.orEmpty()
        )
    }


/**
 * One bounded decision line per module-internal, untargeted identity lookup: which storefront
 * the shared MediaApi field held, which one the call used, how many ids it sent, the chosen
 * localization, the captured account storefront at that moment, and the response size or failure.
 *
 * Emitted through [ProviderLogger.info] -- the same channel as the neighbouring catalog traces
 * (`Apple 内部原名候选`, `Apple 内部歌曲身份已就绪`) instead of the `[debug]`-prefixed diagnostic
 * channel that the exported device log filters out.  Like those neighbours it is not gated on
 * `BuildConfig.DEBUG`, so the line is present in the same log the identity/original-name traces
 * appear in.
 */
internal fun logModuleIdentityLookup(detail: String, outcome: String) {
    ProviderLogger.info("AppleCatalogModuleIdentity: $detail, outcome=$outcome")
}


internal fun AppleInternalCatalogResolver.catalogResponseDiagnostic(
        response: Any?,
        snapshot: CatalogResponseSnapshot?,
    ): String {
        if (response == null) return "value=null"
        // This diagnostic intentionally avoids touching the host response.  The snapshot has
        // already copied the only useful cardinality while all reflection was on the main thread.
        return "valueClass=${response.javaClass.name}, " +
            "dataSize=${snapshot?.entities?.size ?: "unknown"}"
    }


internal fun AppleInternalCatalogResolver.storefrontForLanguage(language: String): String =
        storefrontForOriginalLanguage(language)
            ?: error("Unsupported Apple storefront language: $language")

    /**
     * Copies the host response while still on the host's required main thread.  Nothing returned
     * from this method retains a reference to an Apple Music response/entity/attributes object;
     * parsing, language selection, and candidate matching consume only these immutable values on
     * the Catalog CPU executor.
     */

internal fun AppleInternalCatalogResolver.catalogMember(member: AppleMusicRuntimeMember): String =
        resolvedCatalogHolder.target.runtimeMemberName(member)
