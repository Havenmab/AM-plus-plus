/*
 * Copyright 2026 juren233
 * Licensed under the Apache License, Version 2.0
 * http://www.apache.org/licenses/LICENSE-2.0
 */

package io.github.proify.lyricon.amprovider.xposed

import io.github.proify.lyricon.amprovider.xposed.AppleInternalCatalogResolver.CatalogAccess
import io.github.proify.lyricon.amprovider.xposed.AppleInternalCatalogResolver.Companion.storefrontForContentUiLanguage

/** MediaApi is not ready during Application.onCreate on 6.5.3; retry the write. */
private const val STOREFRONT_APPLY_RETRY_DELAY_MS = 5_000L
private const val STOREFRONT_APPLY_MAX_ATTEMPTS = 6

/**
 * The account-preserving region engine of [AppleInternalCatalogResolver].
 *
 * Split out of the resolver so the class body stays focused on request resolution; the state
 * itself lives on the resolver because these are extensions, not members.
 */

/**
 * Applies the configured region to ordinary Apple Music catalog traffic.
 *
 * [regionReplacementRequested] is the user's region switch; a profile that selects no
 * storefront (`storefrontForContentUiLanguage` returns null) never rewrites traffic no
 * matter what the switch says.
 */
internal fun AppleInternalCatalogResolver.applyRegionConfiguration(
    selection: Int,
    regionReplacementRequested: Boolean,
    localizedMetadataCacheEnabled: Boolean,
) {
    contentUiLanguageSelection = selection
    globalRegionRewriteEnabled = regionReplacementRequested &&
        storefrontForContentUiLanguage(selection) != null
    // Records the profile and warms its display cache; the region write below is separate so
    // a profile that only restores names never touches ordinary traffic.
    applyContentUiLanguage(selection)
    setPersistentLocalizedCacheEnabled(localizedMetadataCacheEnabled)
    if (!globalRegionRewriteEnabled) {
        // Switching the region replacement off has to hand the storefront back to the
        // account, otherwise the process keeps browsing the previously selected region
        // until Apple Music restarts.
        if (accountStorefrontCaptured) restoreStorefrontAccess(isFirstAttempt = true)
        return
    }
    // A module-owned direct query is temporarily driving the storefront field; leave it.
    if (activeCatalogRequest.get() != null) return
    if (!restoreStorefrontAccess(isFirstAttempt = true)) scheduleStorefrontApplyRetry()
}

/** Whether ordinary Apple Music traffic is currently rewritten to the configured region. */
internal fun AppleInternalCatalogResolver.isGlobalRegionRewriteEnabled(): Boolean =
    globalRegionRewriteEnabled

/** The configured storefront for ordinary requests, or null when none applies. */
internal fun AppleInternalCatalogResolver.configuredStorefrontOrNull(): String? =
    if (globalRegionRewriteEnabled) storefrontForContentUiLanguage(contentUiLanguageSelection)
    else null

/** The configured catalog language for ordinary requests, or null when none applies. */
internal fun AppleInternalCatalogResolver.configuredLanguageOrNull(): String? =
    if (globalRegionRewriteEnabled) configuredLanguageTag else null

/**
 * storefront 应用成败只取决于 MediaApi 是否已就绪：6.5.3 在 Application.onCreate 阶段
 * applicationConnector 尚未初始化，此时 createCatalogAccess 必然失败，只能延迟重试。
 * 返回是否成功，供调用方决定是否继续排期。
 */
private fun AppleInternalCatalogResolver.restoreStorefrontAccess(isFirstAttempt: Boolean): Boolean =
    runCatching {
        val access = catalogAccess ?: createCatalogAccess().also { catalogAccess = it }
        restoreConfiguredStorefront(access)
    }.onFailure { error ->
        if (isFirstAttempt) {
            ProviderLogger.error(
                "Apple 内容 UI storefront 应用失败，已安排延迟重试: " +
                    "selection=$contentUiLanguageSelection",
                error,
            )
        } else {
            ProviderLogger.info(
                "Apple 内容 UI storefront 应用重试仍未就绪: " +
                    "selection=$contentUiLanguageSelection, error=${error.javaClass.simpleName}",
            )
        }
    }.isSuccess

private fun AppleInternalCatalogResolver.scheduleStorefrontApplyRetry() {
    if (!storefrontApplyRetryGate.compareAndSet(false, true)) return
    mainHandler.postDelayed({
        storefrontApplyRetryGate.set(false)
        if (restoreStorefrontAccess(isFirstAttempt = false)) {
            storefrontApplyRetryAttempts.set(0)
            return@postDelayed
        }
        val attempts = storefrontApplyRetryAttempts.incrementAndGet()
        if (attempts < STOREFRONT_APPLY_MAX_ATTEMPTS) {
            scheduleStorefrontApplyRetry()
        } else {
            storefrontApplyRetryAttempts.set(0)
            ProviderLogger.info(
                "Apple 内容 UI storefront 应用重试放弃: attempts=$attempts, " +
                    "selection=$contentUiLanguageSelection",
            )
        }
    }, STOREFRONT_APPLY_RETRY_DELAY_MS)
}

/**
 * Writes the configured storefront into MediaApi without losing the account's own value.
 *
 * Apple Music builds catalog URLs from this field, so writing it is what makes ordinary
 * browsing, search and recommendations resolve in the configured region; [accountStorefront]
 * keeps the original value available for the entitlement-bound request paths.
 */
private fun AppleInternalCatalogResolver.restoreConfiguredStorefront(access: CatalogAccess) {
    captureAccountStorefront(access)
    val configuredStorefront = storefrontForContentUiLanguage(contentUiLanguageSelection)
    if (configuredStorefront == null && !accountStorefrontCaptured) return
    val target = configuredStorefront ?: accountStorefront
    val previous = access.storefrontField.get(access.mediaApi) as? String
    access.storefrontField.set(access.mediaApi, target)
    lastAppliedConfiguredStorefront = configuredStorefront
    if (previous != target) {
        ProviderLogger.info(
            "Apple 内容 UI storefront 已应用: selection=$contentUiLanguageSelection, " +
                "previous=${previous ?: "unset"}, " +
                "storefront=${target ?: "account-default"}, " +
                "accountStorefront=${accountStorefront ?: "account-default"}",
        )
    }
}

internal fun AppleInternalCatalogResolver.captureAccountStorefront(access: CatalogAccess) {
    val current = access.storefrontField.get(access.mediaApi) as? String ?: return
    if (!shouldCaptureAccountStorefront(current, lastAppliedConfiguredStorefront)) return
    accountStorefront = current
    accountStorefrontCaptured = true
}

/**
 * Whether [current] is a value worth remembering as the account's own storefront.
 *
 * A value this resolver itself wrote for the configured region is never the account's.  Recording
 * it would pin playback and every account-scoped lookup to the selected region instead of the
 * account's real one, so a field still holding our own last write is skipped.
 */
internal fun shouldCaptureAccountStorefront(
    current: String?,
    lastAppliedConfiguredStorefront: String?,
): Boolean = current != null &&
    (lastAppliedConfiguredStorefront == null || current != lastAppliedConfiguredStorefront)

/**
 * Whether the shared storefront field must be restored after a module-internal lookup that
 * temporarily wrote [applied] into it.
 *
 * The region apply/retry writes the same field, so a value different from [applied] means that
 * writer owns the field now.  Restoring an observation taken before the call would then silently
 * undo the region, so only our own still-in-place write is restored.
 */
internal fun shouldRestoreModuleLookupStorefront(applied: String?, current: String?): Boolean =
    applied != null && current == applied

/**
 * The account's real storefront, used to bring entitlement-bound requests back home.
 *
 * Falls back to reading MediaApi when the value was never captured, which also happens on
 * versions where the region configuration is applied before the account storefront is known.
 */
internal fun AppleInternalCatalogResolver.accountStorefrontForPlaybackRequest(): String? {
    accountStorefront?.let { return it }
    return runCatching {
        val access = catalogAccess ?: createCatalogAccess().also { catalogAccess = it }
        captureAccountStorefront(access)
        accountStorefront
    }.getOrNull()
}
