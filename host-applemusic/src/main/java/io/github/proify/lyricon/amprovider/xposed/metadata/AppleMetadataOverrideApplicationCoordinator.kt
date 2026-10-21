/*
 * Copyright 2026 juren233
 * Licensed under the Apache License, Version 2.0
 * http://www.apache.org/licenses/LICENSE-2.0
 */

package io.github.proify.lyricon.amprovider.xposed

import com.juren233.hyperlyricsenhanced.BuildConfig
import io.github.proify.lyricon.amprovider.xposed.hooks.AppleFrameworkMetadataHooks
import java.util.concurrent.atomic.AtomicLong

/** Publishes resolved aliases to stores, active playback, and exact in-app consumers. */
internal class AppleMetadataOverrideApplicationCoordinator(
    private val runtime: AppleMusicProviderRuntime,
    private val metadataStore: AppleMetadataOverrideStore,
    private val registry: AppleInAppMetadataRegistry,
    private val resolutionCoordinator: AppleInAppMetadataResolutionCoordinator,
    private val catalogResolver: AppleInternalCatalogResolver,
    private val surfaceRuntime: AppleMetadataSurfaceRuntime,
    private val metadataApplier: AppleInAppMetadataApplier,
    private val librarySurfaceHooks: AppleLibrarySurfaceHooks,
    private val dataBindingHooks: AppleDataBindingMetadataHooks,
    private val listenNowHooks: AppleListenNowHooks,
    private val actionSheetMetadataHooks: AppleActionSheetMetadataHooks,
    private val playbackMetadataCoordinator: ApplePlaybackMetadataCoordinator,
    private val frameworkMetadataHooks: AppleFrameworkMetadataHooks,
    private val visibleMetadataDiagnostics: AppleVisibleMetadataDiagnostics,
    private val media3MetadataCoordinator: AppleMedia3MetadataCoordinator,
    private val configuredContentUiLanguage: () -> Int,
    private val traceSequence: AtomicLong,
) {
    fun rememberOriginalMetadataOverride(
        mediaId: String,
        alias: AppleInternalCatalogResolver.Alias,
        confirmed: Boolean,
    ) {
        metadataStore.rememberOriginalMetadata(
            mediaId = mediaId,
            alias = alias,
            confirmed = confirmed,
        )
    }

    fun publishCurrentPlaybackAlias(
        mediaId: String,
        alias: AppleInternalCatalogResolver.Alias,
    ) {
        if (playbackMetadataCoordinator.currentMetadataId() != mediaId) return
        metadataStore.updateCurrentPlaybackOverride(alias)
        val updated = MediaMetadataCache.updateDisplayMetadata(
            mediaId = mediaId,
            title = alias.title,
            artist = alias.artist,
        ) ?: return
        if (BuildConfig.DEBUG) {
            ProviderLogger.diagnostic(
                "associated_artist_playback_publish: " +
                    "id=$mediaId title=${updated.title} artist=${updated.artist}"
            )
        }
        PlaybackManager.onCatalogMetadataResolved(mediaId)
    }

    fun apply(
        mediaId: String,
        alias: AppleInternalCatalogResolver.Alias,
        forceInAppRebind: Boolean = true,
        rememberLocalizedArtist: Boolean = true,
        originalMetadata: Boolean = false,
        originalMetadataConfirmed: Boolean = false,
        artistOnly: Boolean = false,
        propagateArtistEntity: Boolean = true,
    ) {
        if (artistOnly && propagateArtistEntity) {
            val artistId = resolutionCoordinator.sharedAssociatedArtistId(mediaId)
            if (artistId != null) {
                propagateSharedArtistOverride(
                    sourceMediaId = mediaId,
                    artistId = artistId,
                    alias = alias,
                    forceInAppRebind = forceInAppRebind,
                    rememberLocalizedArtist = rememberLocalizedArtist,
                    originalMetadata = originalMetadata,
                    originalMetadataConfirmed = originalMetadataConfirmed,
                )
                return
            }
        }
        val previousEffective = resolutionCoordinator.effectiveAlias(mediaId)
        when {
            artistOnly && originalMetadata -> {
                metadataStore.markOriginalArtistResolved(mediaId)
                metadataStore.rememberOriginalArtist(mediaId, alias)
            }
            artistOnly -> metadataStore.rememberConfiguredArtist(mediaId, alias)
            originalMetadata -> metadataStore.rememberOriginalMetadata(
                mediaId = mediaId,
                alias = alias,
                confirmed = originalMetadataConfirmed,
            )
            else -> metadataStore.rememberConfiguredMetadata(mediaId, alias)
        }
        if (rememberLocalizedArtist && !originalMetadata && artistOnly) {
            catalogResolver.rememberLocalizedArtist(
                selection = configuredContentUiLanguage(),
                artistKeys = localizedArtistCacheKeys(metadataStore.artistKeys(mediaId).orEmpty()),
                localizedArtist = alias.artist,
            )
        }
        val effectiveAlias = resolutionCoordinator.effectiveAlias(mediaId)
        if (effectiveAlias == null) {
            ProviderLogger.info(
                "Apple 设定地区元数据仅缓存: id=$mediaId, reason=original_region_pending"
            )
            return
        }
        val shouldForceInAppRebind = forceInAppRebind || previousEffective != effectiveAlias
        val identity = media3MetadataCoordinator.activePlaybackIdentity()
        val appliesToActivePlayback = identity.mediaId == mediaId
        val allowModelRefresh = allowModelRefresh(mediaId, identity.mediaId)
        if (BuildConfig.DEBUG) {
            // These consumer counters only feed the diagnostic identity line; the refresh
            // decision above already has everything it needs from the two booleans.
            val hasBoundConsumer = metadataApplier.hasLiveModelTarget(mediaId)
            val surfaceRelevant = surfaceRuntime.isCurrentMediaId(mediaId)
            val hasVisibleExactConsumer = surfaceRuntime.hasVisibleConsumer(mediaId)
            val hasActiveVisibleLease = surfaceRuntime.hasVisibleResolutionLease(mediaId)
            media3MetadataCoordinator.logIdentity(
                event = "model_refresh_policy",
                identity = identity,
                details = "overrideId=$mediaId, entityType=${metadataStore.entityType(mediaId)}, " +
                    "forceInAppRebind=$shouldForceInAppRebind, " +
                    "requestedForceInAppRebind=$forceInAppRebind, " +
                    "allowModelRefresh=$allowModelRefresh, surfaceRelevant=$surfaceRelevant, " +
                    "visibleExactConsumer=$hasVisibleExactConsumer, " +
                    "visibleLease=$hasActiveVisibleLease, hasBoundConsumer=$hasBoundConsumer, " +
                    "epoxyRefs=${librarySurfaceHooks.controllerRefCount(mediaId)}, " +
                    "composeRefs=${librarySurfaceHooks.composeStateRefCount(mediaId)}, " +
                    "dataBindingRefs=${dataBindingHooks.refCount(mediaId)}",
            )
        }
        if (playbackMetadataCoordinator.currentMetadataId() == mediaId) {
            metadataStore.updateCurrentPlaybackOverride(effectiveAlias)
        }
        MediaMetadataCache.updateDisplayMetadata(mediaId, effectiveAlias.title, effectiveAlias.artist)
        PlaybackManager.onCatalogMetadataResolved(mediaId)
        // Coalesce repeated in-app refreshes for the same id onto one later message
        // (HLE v8.2.1 `1a665f69`): a batch resolves the same id two to four times
        // (configured metadata, both artist aliases, artist-id propagation) and the
        // effective alias is usually unchanged, so each application re-bound the
        // whole surface. The currently playing item still refreshes synchronously.
        val deferInAppRefresh = !appliesToActivePlayback
        val listenNowDirectBindingTargets = if (deferInAppRefresh) {
            if (deferredInAppRefreshes.offer(mediaId, shouldForceInAppRebind)) {
                runtime.mainHandler.post(::flushDeferredInAppRefreshes)
            }
            -1
        } else {
            deferredInAppRefreshes.cancel(mediaId)
            refreshInAppConsumers(
                mediaId = mediaId,
                alias = effectiveAlias,
                allowModelRefresh = allowModelRefresh,
                forceRebind = shouldForceInAppRebind,
            )
        }
        if (appliesToActivePlayback) {
            actionSheetMetadataHooks.applyAlias(mediaId, effectiveAlias)
            if (BuildConfig.DEBUG) {
                runtime.mainHandler.post {
                    visibleMetadataDiagnostics.scan("active_alias_applied")
                }
            }
        }
        if (BuildConfig.DEBUG) {
            val (metadataRefCount, playbackItemRefCount, containerItemRefCount) =
                registry.refCounts(mediaId)
            media3MetadataCoordinator.logIdentity(
                event = "override_applied",
                identity = identity,
                details = "overrideId=$mediaId, active=$appliesToActivePlayback, " +
                    "effective=${effectiveAlias.title}/${effectiveAlias.artist}/${effectiveAlias.album}, " +
                    "original=$originalMetadata, confirmed=$originalMetadataConfirmed, " +
                    "artistOnly=$artistOnly, changed=${previousEffective != effectiveAlias}, " +
                    "inAppRefreshDeferred=$deferInAppRefresh, " +
                    "listenNowDirectBindingTargets=$listenNowDirectBindingTargets, " +
                    "metadataRefs=$metadataRefCount, itemRefs=$playbackItemRefCount, " +
                    "containerRefs=$containerItemRefCount",
            )
        }
        if (previousEffective == effectiveAlias) {
            if (appliesToActivePlayback) {
                metadataApplier.refreshMetadataCallbacks(mediaId, effectiveAlias)
            }
            return
        }
        ProviderLogger.info(
            "Apple 播放元数据已覆盖: id=$mediaId, title=${effectiveAlias.title}, " +
                "artist=${effectiveAlias.artist}, language=${effectiveAlias.language}, " +
                "original=$originalMetadata, confirmed=$originalMetadataConfirmed, " +
                "artistOnly=$artistOnly"
        )
        playbackMetadataCoordinator.invokeCurrentRefresh(mediaId)
        frameworkMetadataHooks.refreshMediaSessionMetadata(mediaId, effectiveAlias)
        frameworkMetadataHooks.refreshMediaSessionQueue(mediaId)
        metadataApplier.refreshMetadataCallbacks(mediaId, effectiveAlias)
    }

    /** The refresh policy read once per application (and again per deferred drain). */
    private fun allowModelRefresh(mediaId: String, activeMediaId: String?): Boolean =
        shouldRefreshInAppSurface(
            surfaceRelevant = surfaceRuntime.isCurrentMediaId(mediaId),
            hasVisibleExactConsumer = surfaceRuntime.hasVisibleConsumer(mediaId),
            hasActiveVisibleLease = surfaceRuntime.hasVisibleResolutionLease(mediaId),
        ) && shouldNotifyInAppModelChange(
            mediaId = mediaId,
            activeMediaId = activeMediaId,
            hasBoundConsumer = metadataApplier.hasLiveModelTarget(mediaId),
        )

    /** Refreshes the exact in-app consumers; returns the direct Listen Now refresh count. */
    private fun refreshInAppConsumers(
        mediaId: String,
        alias: AppleInternalCatalogResolver.Alias,
        allowModelRefresh: Boolean,
        forceRebind: Boolean,
    ): Int {
        val listenNowDirectBindingTargets = if (
            !allowModelRefresh && listenNowHooks.hasDataBindingRefs(mediaId)
        ) {
            listenNowHooks.refreshDataBindings(mediaId, alias)
        } else {
            0
        }
        if (allowModelRefresh) {
            metadataApplier.applyAliasToMetadataRefs(
                mediaId = mediaId,
                alias = alias,
                forceRebind = forceRebind,
                notifyModelChange = true,
            )
        }
        return listenNowDirectBindingTargets
    }

    /**
     * Executes the coalesced in-app refreshes, spending at most
     * [DEFERRED_REFRESH_BUDGET_NS] per message. The remainder goes back to the
     * queue ahead of newly offered ids, so a cold-start page with ~100 ids does
     * not block one message for ~90 ms (HLE v8.2.1 `1a665f69`).
     */
    private fun flushDeferredInAppRefreshes() {
        val startNs = System.nanoTime()
        val drained = deferredInAppRefreshes.drain()
        val activeMediaId = media3MetadataCoordinator.activePlaybackIdentity().mediaId
        var refreshed = 0
        val iterator = drained.pending.entries.iterator()
        while (iterator.hasNext()) {
            if (refreshed > 0 && System.nanoTime() - startNs >= DEFERRED_REFRESH_BUDGET_NS) break
            val (mediaId, forceRebind) = iterator.next()
            refreshed += 1
            val alias = resolutionCoordinator.effectiveAlias(mediaId) ?: continue
            refreshInAppConsumers(
                mediaId = mediaId,
                alias = alias,
                allowModelRefresh = allowModelRefresh(mediaId, activeMediaId),
                forceRebind = forceRebind,
            )
        }
        val remaining = buildMap { iterator.forEachRemaining { put(it.key, it.value) } }
        if (deferredInAppRefreshes.restore(remaining)) {
            runtime.mainHandler.post(::flushDeferredInAppRefreshes)
        }
        if (BuildConfig.DEBUG) {
            ProviderLogger.diagnostic(
                "[OverrideCoalesce] calls=${drained.offers}, ids=${drained.pending.size}, " +
                    "refreshed=$refreshed, remaining=${remaining.size}, " +
                    "ms=${(System.nanoTime() - startNs) / 1_000_000.0}"
            )
        }
    }

    private val deferredInAppRefreshes = AppleDeferredInAppRefreshQueue()

    private companion object {
        const val DEFERRED_REFRESH_BUDGET_NS = 6_000_000L
    }

    private fun isSharedArtistAliasCurrent(
        mediaId: String,
        alias: AppleInternalCatalogResolver.Alias,
        originalMetadata: Boolean,
    ): Boolean = if (originalMetadata) {
        metadataStore.isOriginalArtistResolved(mediaId) && metadataStore.originalArtist(mediaId) == alias
    } else {
        metadataStore.configuredArtist(mediaId) == alias
    }

    private fun propagateSharedArtistOverride(
        sourceMediaId: String,
        artistId: String,
        alias: AppleInternalCatalogResolver.Alias,
        forceInAppRebind: Boolean,
        rememberLocalizedArtist: Boolean,
        originalMetadata: Boolean,
        originalMetadataConfirmed: Boolean,
    ) {
        val previousSharedAlias = if (originalMetadata) {
            metadataStore.rememberSharedOriginalArtist(artistId, alias)
        } else {
            metadataStore.rememberSharedConfiguredArtist(
                selection = configuredContentUiLanguage(),
                artistId = artistId,
                alias = alias,
            )
        }
        val targets = linkedSetOf(sourceMediaId, artistId).apply {
            addAll(metadataStore.associatedMediaIds("id:$artistId").orEmpty())
        }
        targets.forEach { targetMediaId ->
            if (
                targetMediaId != artistId &&
                !shouldShareAssociatedArtistAlias(
                    artistId = artistId,
                    targetArtistIds = metadataStore.associatedArtistIds(targetMediaId).orEmpty(),
                    targetArtistCredit = resolutionCoordinator.associatedArtistCredit(targetMediaId),
                )
            ) return@forEach
            // Each of an artist's songs propagates the alias when it resolves; writing the
            // same artist alias again cannot change the row (HLE v8.2.1 `1a665f69`: 48 ids
            // produced 157 overrides, 130 ms in one message).
            if (
                targetMediaId != sourceMediaId &&
                isSharedArtistAliasCurrent(targetMediaId, alias, originalMetadata)
            ) return@forEach
            apply(
                mediaId = targetMediaId,
                alias = alias,
                forceInAppRebind = forceInAppRebind ||
                    previousSharedAlias != alias || targetMediaId != sourceMediaId,
                rememberLocalizedArtist = rememberLocalizedArtist,
                originalMetadata = originalMetadata,
                originalMetadataConfirmed = originalMetadataConfirmed,
                artistOnly = true,
                propagateArtistEntity = false,
            )
        }
        if (BuildConfig.DEBUG) {
            ProviderLogger.info(
                "Apple Music 元数据链路: seq=${traceSequence.incrementAndGet()}, " +
                    "event=artist_id_alias_propagated, artistId=$artistId, " +
                    "sourceId=$sourceMediaId, targets=$targets, artist=${alias.artist}, " +
                    "original=$originalMetadata"
            )
        }
    }
}
