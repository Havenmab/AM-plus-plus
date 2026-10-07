package dev.amenhancer.module.hook

import java.io.File
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class HleMetadataIntegrationStructuralTest {
    @Test
    fun `nullable embedded module native directory does not abort HLE construction`() {
        val runtime = source("host-applemusic/src/main/java/dev/amenhancer/module/hook/HleMetadataRuntime.kt")
            .replace("\r\n", "\n")
        // The module application info is optional: a failed lookup must not throw while the
        // runtime is being constructed, and the native library directory may stay empty because
        // the package keeps its native libraries inside the APK (extractNativeLibs=false).
        assertTrue(runtime.contains("runCatching { module.getModuleApplicationInfo() }.getOrNull()"))
        assertTrue(
            runtime.contains("nativeLibraryDir = moduleApplicationInfo?.nativeLibraryDir.orEmpty()"),
        )
        assertTrue(runtime.contains("moduleApkPaths = listOfNotNull("))
        assertTrue(runtime.contains("moduleApplicationInfo?.splitSourceDirs.orEmpty()"))
    }

    private fun source(relative: String): String = sequenceOf(
        File(relative),
        File("../$relative"),
    ).firstOrNull(File::isFile)?.readRefactorComponent()
        ?: error("Missing $relative")

    @Test
    fun `title switch installs HLE metadata runtime without global catalog language target`() {
        val feature = source("app/src/main/java/dev/amenhancer/module/hook/TitleCorrectionFeature.kt")
        val installation = source("app/src/main/java/dev/amenhancer/module/hook/FeatureInstallation.kt")
        assertTrue(feature.contains("hleMetadata.install()"))
        assertFalse(installation.contains("LibraryRefreshFeature()"))
        assertFalse(installation.contains("CatalogLanguageFeature()"))
        assertTrue(installation.contains("TitleCorrectionFeature()"))
    }

    @Test
    fun `surface bridge keeps the original HLE metadata hook families live`() {
        val bridge = source("host-applemusic/src/main/java/dev/amenhancer/module/hook/HleMetadataSurfaceBridge.kt")
        val playback = source(
            "host-applemusic/src/main/java/io/github/proify/lyricon/amprovider/xposed/metadata/ApplePlaybackMetadataHooks.kt",
        )
        listOf(
            "AppleListenNowHooks",
            "AppleLibrarySurfaceHooks",
            "AppleDataBindingMetadataHooks",
            "AppleCollectionSurfaceHooks",
            "AppleArtistSurfaceHooks",
            "AppleInAppArtworkContinuityHooks",
            "ApplePlaybackItemConversionHooks",
            "AppleMetadataSurfaceRuntime",
        ).forEach { module ->
            assertTrue("missing HLE module $module", bridge.contains(module))
        }
        assertTrue(playback.contains("attachActivePlayer(mediaPlayer)"))
    }

    @Test
    fun `playback host delegates alias validation to HLE policy without recursion`() {
        val runtime = source("host-applemusic/src/main/java/dev/amenhancer/module/hook/HleMetadataRuntime.kt")
        assertTrue(
            runtime.contains(
                "io.github.proify.lyricon.amprovider.xposed.validatedOriginalSongAlias(",
            ),
        )
        assertFalse(
            runtime.contains("validatedOriginalSongAlias(alias, localizedTitle, localizedArtist)"),
        )
    }

    @Test
    fun `region rewriting is opt-in and entitlement-bound requests keep the account storefront`() {
        val runtime = source("host-applemusic/src/main/java/dev/amenhancer/module/hook/HleMetadataRuntime.kt")
        val localization = source(
            "host-applemusic/src/main/java/io/github/proify/lyricon/amprovider/xposed/hooks/AppleContentLocalizationHooks.kt",
        )
        val resolver = source(
            "host-applemusic/src/main/java/io/github/proify/lyricon/amprovider/xposed/AppleInternalCatalogResolver.kt",
        )
        val bridge = source(
            "host-applemusic/src/main/java/dev/amenhancer/module/hook/HleMetadataSurfaceBridge.kt",
        )
        // The runtime applies the region plan and installs all four localization seams.
        assertTrue(runtime.contains("applyRegionConfiguration("))
        assertTrue(runtime.contains("regionReplacementRequested = requestPlan.rewritesCatalogRequests"))
        // The plan now carries HLE's three controls, including the override switch.
        assertTrue(runtime.contains("RegionTitleRequestPolicy.plan("))
        assertTrue(runtime.contains("overrideAccountLanguage = overrideAccountLanguage"))
        assertTrue(runtime.contains("restoreCjkOriginalMetadata = restoreCjkOriginalMetadata"))
        // And the account-language override follows HLE's region+override pair, not the region
        // alone.  That pair reaches BOTH hosts: the playback host straight from the plan, and the
        // in-app resolution host through the surface bridge.  Leaving the resolution host on
        // `configuredContentUiLanguage != 0` was the fork-only deviation.
        assertTrue(runtime.contains("requestPlan.overrideAccountLanguage"))
        assertTrue(runtime.contains("overrideAccountLanguage = requestPlan.overrideAccountLanguage"))
        assertTrue(bridge.contains("internal val overrideAccountLanguage: Boolean"))
        assertTrue(bridge.contains("this@createMetadataHostAdapters.overrideAccountLanguage"))
        assertFalse(bridge.contains("configuredContentUiLanguage != 0"))
        assertTrue(runtime.contains("contentLocalizationHooks.installMediaApiLocalization()"))
        assertTrue(runtime.contains("contentLocalizationHooks.installCatalogRequestLocalization()"))
        assertTrue(runtime.contains("contentLocalizationHooks.installContentHttpLocalization()"))
        assertTrue(runtime.contains("contentLocalizationHooks.installAmpApiHttpLocalization()"))
        // Module-owned lookups still win through their token.
        assertTrue(localization.contains("resolver.catalogRequestLocalization(requestToken)"))
        assertTrue(localization.contains("resolver.activeCatalogRequestLocalization()"))
        // Ordinary traffic is only rewritten while the user enabled a region replacement, and the
        // decision is now a pure seam action that a token-carrying request with no resolvable
        // localization fails open on instead of falling back to the configured region.
        assertTrue(localization.contains("catalogSeamAction("))
        assertTrue(
            localization.contains(
                "globalRegionRewriteEnabled = resolver.isGlobalRegionRewriteEnabled()",
            ),
        )
        assertTrue(localization.contains("CatalogSeamAction.SKIP_MARKED"))
        // Radio/station and lyrics requests are pulled back to the account storefront, and that
        // check must run BEFORE the seam action decides: the catalog executor can stamp a request
        // it already redirected, and leaving such a request on the configured region is what makes
        // account-available radio/lyrics unplayable.
        assertTrue(localization.contains("AppleInternalCatalogResolver.isAccountScopedPlaybackPath"))
        assertTrue(localization.contains("resolver.accountStorefrontForPlaybackRequest()"))
        assertTrue(localization.contains("rewriteAccountScopedRequest("))
        val accountScopedCheck = localization.indexOf("isAccountScopedPlaybackPath(pathSegments)")
        val seamDecisionCheck = localization.indexOf("val seamAction = catalogSeamAction(")
        assertTrue(accountScopedCheck >= 0)
        assertTrue(seamDecisionCheck >= 0)
        assertTrue(accountScopedCheck < seamDecisionCheck)
        assertTrue(localization.contains("Accept-Language"))
        // HLE writes the request language itself into Accept-Language.  The fork may normalize
        // the tag, but it must not map it: the removed CatalogLanguagePolicy.headerLanguage
        // turned "ja-JP" into "ja", and Apple selects the storefront's own localization from
        // this header, so the downgrade made the jp original-region lookup return the account
        // market's romanized title.  This pins the production content-HTTP seam (the
        // CatalogLanguageRewritePolicy helper above is the inert legacy adapter).
        assertTrue(localization.contains("CatalogLanguagePolicy.normalize(language)"))
        assertFalse(localization.contains("CatalogLanguagePolicy.headerLanguage"))
        // The storefront is written into MediaApi only through the account-preserving helper.
        assertTrue(resolver.contains("restoreConfiguredStorefront(access)"))
        assertTrue(resolver.contains("captureAccountStorefront(access)"))
        assertTrue(resolver.contains("isAccountScopedPlaybackPath"))
        assertFalse(resolver.contains("functionally disabled"))
    }

    @Test
    fun `fixed region lookups retain HLE identity and ISRC fallback`() {
        val resolver = source(
            "host-applemusic/src/main/java/io/github/proify/lyricon/amprovider/xposed/AppleInternalCatalogResolver.kt",
        )
        assertTrue(resolver.contains("resolveLocalizedRequestByLockedIsrc"))
        assertTrue(resolver.contains("enqueueLockedIsrcFallback"))
        assertTrue(resolver.contains("lockedIsrcFallbackPending"))
        assertTrue(resolver.contains("MAX_LOCKED_ISRC_FALLBACK_RUNNING"))
        assertTrue(resolver.contains("resolveCatalogIdentity(identityId, emptyList())"))
        assertTrue(resolver.contains("queryByIsrc("))
        assertTrue(resolver.contains("storefrontOverride = request.storefront"))
        val batchCompletion = resolver
            .substringAfter("matches.forEach")
            .substringBefore("private fun enqueueLockedIsrcFallback")
        assertTrue(batchCompletion.contains("enqueueLockedIsrcFallback(request)"))
        assertFalse(batchCompletion.contains("resolveLocalizedRequestByLockedIsrc(request)"))
        val fallbackScheduler = resolver
            .substringAfter("private fun scheduleLockedIsrcFallbacks")
            .substringBefore("private fun resolveLocalizedRequestByLockedIsrc")
        assertTrue(fallbackScheduler.contains("currentRequestPriority(task.request.mediaId"))
        assertTrue(fallbackScheduler.contains("selectNextRequestIndex(priorities)"))
        val priorityUpdate = resolver
            .substringAfter("private fun updatePendingRequestPriorities")
            .substringBefore("private fun currentScopedPriority")
        assertTrue(priorityUpdate.contains("scheduleLockedIsrcFallbacks()"))
    }

    @Test
    fun `the identity probe takes HLE's catalog path instead of the fork's field switch`() {
        val resolver = source(
            "host-applemusic/src/main/java/io/github/proify/lyricon/amprovider/xposed/AppleInternalCatalogResolver.kt",
        )
        val query = source(
            "host-applemusic/src/main/java/io/github/proify/lyricon/amprovider/xposed/NativeCatalogQueryAccess.kt",
        )
        val localization = source(
            "host-applemusic/src/main/java/io/github/proify/lyricon/amprovider/xposed/hooks/AppleContentLocalizationHooks.kt",
        )
        // HLE's queryById(mediaId, null) builds no localization: the untargeted identity probe
        // is not tokenized, so the region seams localize it like ordinary traffic...
        assertFalse(resolver.contains("moduleCatalogLookupFieldStorefront"))
        assertFalse(resolver.contains("keepsAccountCatalogTarget"))
        assertFalse(query.contains("temporaryFieldStorefront"))
        // ...and the shared MediaApi storefront field, which the direct query derives its
        // catalog target from, is left at the region's value instead of being switched.
        val untargeted = query
            .substringAfter("val untargetedModuleLookup")
            .substringBefore("if (localization != null)")
        assertFalse(untargeted.contains("storefrontField.set"))
        assertTrue(untargeted.contains("moduleCatalogRequestLocalization("))
        // The request language is written for module requests too, exactly like HLE.  Suppressing
        // it for a target-less request is what returned the romanized alias and let HLE's
        // matching-language shortcut accept it as the original name.
        val mediaApiHook = localization
            .substringAfter("fun installMediaApiLocalization()")
            .substringBefore("fun installCatalogRequestLocalization()")
        assertFalse(mediaApiHook.contains("keepsAccountCatalogTarget"))
        assertTrue(mediaApiHook.contains("requestLocalization?.language"))
        assertTrue(mediaApiHook.contains("params[\"l\"] = language"))
    }

    @Test
    fun `targeted original-region lookups state their storefront and fail open at the seam`() {
        val query = source(
            "host-applemusic/src/main/java/io/github/proify/lyricon/amprovider/xposed/NativeCatalogQueryAccess.kt",
        )
        val resolver = source(
            "host-applemusic/src/main/java/io/github/proify/lyricon/amprovider/xposed/AppleInternalCatalogResolver.kt",
        )
        val localization = source(
            "host-applemusic/src/main/java/io/github/proify/lyricon/amprovider/xposed/hooks/AppleContentLocalizationHooks.kt",
        )
        // The targeted entity batch / ISRC shape gets its own visible-channel decision line.
        assertTrue(query.contains("logTargetedCatalogLookup("))
        assertTrue(query.contains("AppleCatalogTargetedLookup"))
        assertTrue(query.contains("targetedCatalogLookupDetail("))
        assertTrue(resolver.contains("requested=\${requestedStorefront"))
        assertTrue(resolver.contains("fieldDuring"))
        // The batch completion line names the storefront it asked for, next to the value.
        val scheduling = source(
            "host-applemusic/src/main/java/io/github/proify/lyricon/amprovider/xposed/CatalogRequestScheduling.kt",
        )
        assertTrue(scheduling.contains("storefront=\${request.storefront}"))
        // The seam logs its own decision (source, action, headers) and never lets a module request
        // whose localization is gone fall back to the configured region.
        assertTrue(localization.contains("AppleCatalogSeam"))
        assertTrue(localization.contains("CatalogSeamAction.FAIL_OPEN_UNRESOLVED_TOKEN"))
        assertTrue(localization.contains("fail-open-unresolved-token"))
        assertTrue(localization.contains("catalogSeamAction("))
        assertTrue(localization.contains("AppleCatalogExecutor"))
        assertTrue(localization.contains("localized=\${token != null}"))
    }

    @Test
    fun `library and album refresh paths delegate to HLE stateful hosts`() {
        val bridge = source("host-applemusic/src/main/java/dev/amenhancer/module/hook/HleMetadataSurfaceBridge.kt")
        val runtime = source("host-applemusic/src/main/java/dev/amenhancer/module/hook/HleMetadataRuntime.kt")
        assertTrue(bridge.contains("collectionSurfaceHooks.albumTrackMediaIds"))
        assertTrue(bridge.contains("metadataApplier.requestLibraryControllerBuild"))
        assertTrue(bridge.contains("collectionSurfaceHooks.controllerAppliedAlias"))
        assertTrue(bridge.contains("registry.livePlaybackItems"))
        assertTrue(bridge.contains("librarySurfaceHooks.liveEntities"))
        assertTrue(bridge.contains("artistSurfaceHooks.shouldInvalidateAppliedAlias"))
        assertTrue(bridge.contains("dataBindingHooks.recordCurrentRecyclerMediaId"))
        assertTrue(runtime.contains("contentItemMetadataOverride("))
        assertTrue(runtime.contains("surfaceBridge.recordComposeMediaId(mediaId)"))
        assertTrue(runtime.contains("surfaceBridge.recordCurrentRecyclerMediaId(mediaId)"))
        assertFalse(bridge.contains("\"controllerAlbumTrackMediaIds\" -> emptyList"))
        assertFalse(bridge.contains("\"requestControllerBuild\" -> false"))
    }

    @Test
    fun `typed host adapters preserve HLE callback contracts`() {
        val bridge = source("host-applemusic/src/main/java/dev/amenhancer/module/hook/HleMetadataSurfaceBridge.kt")
        assertTrue(bridge.contains("createHostAdapters()"))
        assertTrue(bridge.contains("internal inline fun <T> hostCall"))
        listOf(
            "AppleMetadataSurfaceHost",
            "AppleLibrarySurfaceHost",
            "AppleDataBindingMetadataHost",
            "AppleCollectionSurfaceHost",
            "AppleArtistSurfaceHost",
            "AppleMediaApiMetadataHost",
            "AppleInAppMetadataResolutionHost",
            "AppleListenNowHost",
            "AppleVisibleMetadataDiagnosticsHost",
            "ApplePlaybackItemConversionHost",
            "AppleInAppArtworkContinuityHost",
        ).forEach { host ->
            assertTrue("missing typed host $host", bridge.contains("$host"))
        }
        assertFalse(bridge.contains("Proxy.newProxyInstance"))
        assertFalse(bridge.contains("Array<out Any?>"))
        assertFalse(bridge.contains("surfaceValue"))
        assertFalse(bridge.contains("dataBindingValue"))
        assertTrue(bridge.contains("mediaApiMetadataCoordinator.registerLibraryEntity"))
        assertTrue(bridge.contains("requestResolution = false"))
        assertTrue(bridge.contains("retainEntityRef = true"))
    }

    @Test
    fun `queue host delegates media3 identity and surface scope to HLE runtime`() {
        val runtime = source("host-applemusic/src/main/java/dev/amenhancer/module/hook/HleMetadataRuntime.kt")
        val bridge = source("host-applemusic/src/main/java/dev/amenhancer/module/hook/HleMetadataSurfaceBridge.kt")

        assertFalse(runtime.contains("metadata as? android.media.MediaMetadata"))
        assertTrue(runtime.contains("bridgeMedia3MetadataId(metadata, fallback, trustedFallback)"))
        assertTrue(runtime.contains("bridgeMedia3MetadataDetails(metadata)"))
        assertTrue(runtime.contains("bridgeIsCurrentMetadataSurfaceMediaId(mediaId)"))
        assertTrue(runtime.contains("bridgeMedia3MetadataId = surfaceBridge::media3MetadataId"))
        assertTrue(runtime.contains("bridgeMedia3MetadataDetails = surfaceBridge::media3MetadataDetails"))
        assertTrue(
            runtime.contains(
                "bridgeIsCurrentMetadataSurfaceMediaId = surfaceBridge::isCurrentMetadataSurfaceMediaId",
            ),
        )
        assertTrue(bridge.contains("fun media3MetadataId("))
        assertTrue(bridge.contains("media3MetadataCoordinator.mediaId("))
        assertTrue(bridge.contains("fun media3MetadataDetails("))
        assertTrue(bridge.contains("media3MetadataCoordinator.details(metadata)"))
        assertTrue(bridge.contains("fun isCurrentMetadataSurfaceMediaId("))
        assertTrue(bridge.contains("surfaceRuntime.isCurrentMediaId(mediaId)"))
    }

    @Test
    fun `all HLE hosts use merged aliases and preserve original album candidates`() {
        val runtime = source("host-applemusic/src/main/java/dev/amenhancer/module/hook/HleMetadataRuntime.kt")
        val bridge = source("host-applemusic/src/main/java/dev/amenhancer/module/hook/HleMetadataSurfaceBridge.kt")

        assertTrue(runtime.contains("bridgeEffectiveAlias(mediaId)"))
        assertTrue(runtime.contains("bridgeEffectiveAlias = surfaceBridge::effectiveAlias"))
        assertTrue(bridge.contains("fun effectiveAlias(mediaId: String)"))
        assertTrue(bridge.contains("resolutionCoordinator.effectiveAlias(mediaId)"))
        assertTrue(bridge.contains("VisibleTextField.ALBUM"))
        assertTrue(bridge.contains("registry.livePlaybackItemRefs(mediaId)"))
        assertTrue(bridge.contains("originalCollectionName"))
    }

    @Test
    fun `action sheet and data binding hosts delegate stateful HLE policies`() {
        val runtime = source("host-applemusic/src/main/java/dev/amenhancer/module/hook/HleMetadataRuntime.kt")
        val bridge = source("host-applemusic/src/main/java/dev/amenhancer/module/hook/HleMetadataSurfaceBridge.kt")

        val actionSheet = runtime
            .substringAfter("actionSheetMetadataHooks = AppleActionSheetMetadataHooks")
            .substringBefore("runCatching { actionSheetMetadataHooks.installHooks() }")
        assertTrue(actionSheet.contains("surfaceBridge.shouldRequestOverride(mediaId)"))
        assertTrue(
            actionSheet.indexOf("surfaceBridge.shouldRequestOverride(mediaId)") <
                actionSheet.indexOf("metadataStore.originalMetadata(mediaId) == null"),
        )

        val dataBinding = bridge
            .substringAfter("dataBinding = object : AppleDataBindingMetadataHost")
            .substringBefore("collection = object : AppleCollectionSurfaceHost")
        assertTrue(dataBinding.contains("bridge.isCurrentMetadataSurfaceMediaId(mediaId)"))
        assertFalse(dataBinding.contains("mediaId == bridge.playbackCoordinator.currentMetadataId()"))
    }

    @Test
    fun `framework queue and action sheet identities share the media3 fallback chain`() {
        val runtime = source("host-applemusic/src/main/java/dev/amenhancer/module/hook/HleMetadataRuntime.kt")
        val bridge = source("host-applemusic/src/main/java/dev/amenhancer/module/hook/HleMetadataSurfaceBridge.kt")

        assertTrue(
            runtime
                .substringAfter("frameworkHooks = AppleFrameworkMetadataHooks")
                .substringBefore("frameworkHooks.installMediaSessionMetadata()")
                .contains("this@HleMetadataRuntime.activePlaybackIdentity()"),
        )
        assertTrue(
            runtime
                .substringAfter("queueMetadataHooks = AppleQueueMetadataHooks")
                .substringBefore("runCatching { queueMetadataHooks.installHooks() }")
                .contains("this@HleMetadataRuntime.activePlaybackIdentity()"),
        )
        assertTrue(
            runtime
                .substringAfter("actionSheetMetadataHooks = AppleActionSheetMetadataHooks")
                .substringBefore("runCatching { actionSheetMetadataHooks.installHooks() }")
                .contains("this@HleMetadataRuntime.activePlaybackIdentity()"),
        )
        assertTrue(bridge.contains("fun activePlaybackIdentity(): ActivePlaybackMediaIdentity"))
        assertTrue(bridge.contains("media3MetadataCoordinator.activePlaybackIdentity()"))
    }

    @Test
    fun `visible refresh paths share one frame queue while playback stays immediate`() {
        val queue = source(
            "host-applemusic/src/main/java/io/github/proify/lyricon/amprovider/xposed/metadata/AppleInAppMetadataRefreshQueue.kt",
        )
        assertTrue(queue.contains("MetadataFrameScheduler"))
        assertTrue(queue.contains("VISIBLE_RESOLUTION"))
        assertTrue(queue.contains("higherPriority"))
        assertTrue(queue.contains("higherResolutionMode"))
        assertTrue(queue.contains("frameScheduler.postFrame"))
        val binding = source(
            "host-applemusic/src/main/java/io/github/proify/lyricon/amprovider/xposed/metadata/AppleDataBindingMetadataHooks.kt",
        )
        val library = source(
            "host-applemusic/src/main/java/io/github/proify/lyricon/amprovider/xposed/metadata/AppleLibrarySurfaceHooks.kt",
        )
        val listenNow = source(
            "host-applemusic/src/main/java/io/github/proify/lyricon/amprovider/xposed/metadata/AppleListenNowHooks.kt",
        )
        assertTrue(binding.contains("AppleMetadataRefreshKind.DATA_BINDING_REBIND"))
        assertTrue(binding.contains("AppleMetadataRefreshKind.GENERIC_RECYCLER_NOTIFY"))
        assertTrue(library.contains("AppleMetadataRefreshKind.LIBRARY_CONTROLLER_REBIND"))
        assertTrue(library.contains("AppleMetadataRefreshKind.LIBRARY_COMPOSE_REBIND"))
        assertTrue(listenNow.contains("AppleMetadataRefreshKind.LISTEN_NOW_REBIND"))
        val applier = source(
            "host-applemusic/src/main/java/io/github/proify/lyricon/amprovider/xposed/metadata/AppleInAppMetadataApplier.kt",
        )
        assertTrue(applier.contains("runtime.mainHandler.post"))
    }

    @Test
    fun `listen now release artwork lookup avoids diagnostics cache scans`() {
        val listenNow = source(
            "host-applemusic/src/main/java/io/github/proify/lyricon/amprovider/xposed/metadata/AppleListenNowHooks.kt",
        )
        assertTrue(
            listenNow.contains(
                "val cachedArtwork = synchronized(inAppListenNowArtworkContinuityCache)",
            ),
        )
        assertTrue(listenNow.contains("if (BuildConfig.DEBUG) {\n                        val cacheDiagnostics"))
        assertFalse(listenNow.contains("InAppListenNowArtworkCacheProbe"))
    }

    @Test
    fun `generic profile top songs use the direct relationship and h1 binding seam`() {
        val artist = source(
            "host-applemusic/src/main/java/io/github/proify/lyricon/amprovider/xposed/metadata/AppleArtistSurfaceHooks.kt",
        )
        val coordinator = source(
            "host-applemusic/src/main/java/io/github/proify/lyricon/amprovider/xposed/metadata/AppleMediaApiMetadataCoordinator.kt",
        )
        assertTrue(artist.contains("method.name == \"populateViews\""))
        assertTrue(artist.contains("registerGenericProfileRelationship(controller, relationship)"))
        assertTrue(artist.contains("genericProfileTopSongTexts[controller] = texts"))
        assertTrue(artist.contains("resolveGenericTopSongSnapshot(model)"))
        assertTrue(artist.contains("ARTIST_TOP_SONG_TITLE_FIELD"))
        assertTrue(artist.contains("ARTIST_TOP_SONG_SUBTITLE_FIELD"))
        assertTrue(coordinator.contains("getViews"))
        assertTrue(coordinator.contains("field(entity, \"views\")"))
        assertFalse(artist.contains("[DEBUG-ARTIST-PREFETCH]"))
    }

    @Test
    fun `region seams stay uninstalled on hosts without verified targets`() {
        val localization = source(
            "host-applemusic/src/main/java/io/github/proify/lyricon/amprovider/xposed/hooks/AppleContentLocalizationHooks.kt",
        )
        // Both region-only seams must check for a verified target before resolving.  Without the
        // gate they fall through to the DexKit structural layer, where the only outcomes are
        // "hook an unverified method" or "fail" — and a failure is not cached, so on 6.5.2 the
        // whole-DEX scan repeated on every cold start.
        assertTrue(localization.contains("requireExactTargets: Boolean = false"))
        assertTrue(localization.contains("requireExactTargets = true"))
        assertTrue(
            localization.contains(
                "AppleMusicHookProfiles.exactTargets(runtime.hookResolver.version, hookPoint).isEmpty()",
            ),
        )
        // The executor gate must run before the resolution it guards.
        val catalogInstaller = localization
            .substringAfter("fun installCatalogRequestLocalization()")
            .substringBefore("private fun installContentHttpHook")
        val gate = catalogInstaller.indexOf("if (exactTargets.isEmpty())")
        val resolve = catalogInstaller.indexOf("resolveClasses(")
        assertTrue(gate >= 0)
        assertTrue(resolve >= 0)
        assertTrue(gate < resolve)
        // The content HTTP seam is deliberately exempt: 6.5.1/6.5.2 have no exact target for it
        // either, but reach their verified owner through the compatibility chain, so gating it
        // would disable a working hook.
        assertTrue(localization.contains("Deliberately not applied to CONTENT_HTTP_LOCALIZATION"))
        // The MediaApi parameter map belongs to the host and every native request passes through
        // the after-hook, so the write must be idempotent.
        assertTrue(localization.contains("if (params[\"l\"] == language) return@installHook"))
    }

    @Test
    fun `embedded settings expose HLE's four region controls without restoring refresh action`() {
        val embedded = source("app/src/main/java/dev/amenhancer/module/ui/EmbeddedSettingsHost.kt")
        // HLE's four controls, with HLE's own labels, in HLE's order.
        assertTrue(embedded.contains("将Apple Music改成其他地区"))
        assertTrue(embedded.contains("regionSelection"))
        assertTrue(embedded.contains("歌曲信息替换至设定地区语言"))
        assertTrue(embedded.contains("overrideAccountLanguage"))
        assertTrue(embedded.contains("替换中日韩歌曲信息为原地区原名"))
        assertTrue(embedded.contains("restoreCjkOriginalMetadata"))
        assertTrue(embedded.contains("创建检索库以提升替换体验"))
        assertTrue(embedded.contains("localizedMetadataCache"))
        // The retired fork-only master switch is gone.
        assertFalse(embedded.contains("titleCorrectionEnabled"))
        // HLE's visibility rules: the override only with a region, the cache only while
        // the metadata lookup is enabled, and the restore switch always.
        assertTrue(embedded.contains("settings.regionSelection.replacesRegion"))
        assertTrue(embedded.contains("regionReplacementEnabled"))
        assertTrue(embedded.contains("metadataLookupEnabled"))
        // The picker enumerates the model, so new regions appear without a UI change.
        assertTrue(embedded.contains("RegionSelection.values()"))
        assertFalse(embedded.contains("刷新资料库"))
        // AM++ adds the one-shot 「清空检索库」 action right after HLE's four controls,
        // with its own confirmation dialog and the persisted generation signal.
        assertTrue(embedded.contains("清空检索库"))
        assertTrue(embedded.contains("metadataCacheClearGeneration"))
        assertTrue(embedded.contains("confirmEmbeddedMetadataCacheClear"))
        assertTrue(embedded.contains("下次读取时重新向 Apple Music 获取"))
    }

    @Test
    fun `clear index action signals the live runtime through the config generation`() {
        val schema = source("core/src/main/kotlin/dev/amenhancer/module/config/ModuleSettingsSchema.kt")
        val factory = source(
            "host-applemusic/src/main/java/dev/amenhancer/module/hook/AppleMusicHostFactory.kt",
        )
        val runtime = source(
            "host-applemusic/src/main/java/dev/amenhancer/module/hook/HleMetadataRuntime.kt",
        )
        val bridge = source(
            "host-applemusic/src/main/java/dev/amenhancer/module/hook/HleMetadataSurfaceBridge.kt",
        )
        val index = source(
            "host-applemusic/src/main/java/dev/amenhancer/module/hook/HleMetadataCacheIndex.kt",
        )
        // The UI only writes one schema field; the host reads it live.
        assertTrue(schema.contains("metadata_cache_clear_generation"))
        assertTrue(
            factory.contains(
                "metadataCacheClearSignal = { config.metadataCacheClearGeneration() }",
            ),
        )
        assertTrue(runtime.contains("HleMetadataCacheClearObserver("))
        assertTrue(runtime.contains("HleMetadataCacheClearHandledStore("))
        assertTrue(runtime.contains("metadataCacheClearObserver.observe()"))
        assertTrue(runtime.contains("observeMetadataCacheClear = metadataCacheClearObserver::observe"))
        assertTrue(index.contains("HleMetadataCacheClearHandledStore"))
        assertTrue(index.contains("handled_clear_generation"))
        assertTrue(bridge.contains("observeMetadataCacheClear()"))
        // Every region namespace is derived from the model, and all three SQLite
        // sidecars plus the artist-region preferences are removed.
        assertTrue(index.contains("RegionSelection.values().map(RegionSelection::cacheNamespace)"))
        assertTrue(index.contains("-journal"))
        assertTrue(index.contains("-wal"))
        assertTrue(index.contains("-shm"))
        assertTrue(index.contains("deleteSharedPreferences"))
        assertTrue(index.contains("hyperlyricsenhanced_apple_metadata\""))
        assertTrue(index.contains("hyperlyricsenhanced_apple_original_metadata\""))
        assertTrue(index.contains("hyperlyricsenhanced_apple_original_artist_regions\""))
        // The retired un-namespaced databases are always included.
        assertTrue(index.contains("\${LOCALIZED_DATABASE_PREFIX}.db"))
        assertTrue(index.contains("\${ORIGINAL_DATABASE_PREFIX}.db"))
        // ... including the fork's short-lived pre-namespace v5 files.
        assertTrue(index.contains("_v5.db"))
        assertTrue(index.contains("\${ARTIST_REGION_PREFERENCES_PREFIX}_v5"))
        // The one visible log line uses ProviderLogger.info, never diagnostic.
        assertTrue(index.contains("ProviderLogger.info"))
        assertFalse(index.contains("ProviderLogger.diagnostic"))
    }

    @Test
    fun `schema owns the profile selector and HLE token requests remain isolated`() {
        val schema = source("core/src/main/kotlin/dev/amenhancer/module/config/ModuleSettingsSchema.kt")
        val target = source(
            "host-applemusic/src/main/java/dev/amenhancer/module/hook/AppleMusicCatalogLanguageTarget.kt",
        )
        val bridge = source("host-applemusic/src/main/java/dev/amenhancer/module/hook/HleMetadataSurfaceBridge.kt")
        assertTrue(schema.contains("KEY_REGION_SELECTION"))
        // The retired picker key survives only as a migration input.
        assertTrue(schema.contains("KEY_TITLE_CORRECTION_MODE"))
        assertTrue(schema.contains("KEY_TITLE_CORRECTION_TARGET_LANGUAGE"))
        assertTrue(target.contains("isHleResolverRequest"))
        assertTrue(target.contains("CATALOG_REQUEST_TOKEN_PARAM"))
        assertTrue(target.contains("region rewrite is owned by the HLE localization hooks"))
        assertFalse(target.contains("ModernXposedRuntime.hookMethod"))
        assertTrue(bridge.contains("MediaMetadataCache.setProfile(profileId)"))
        assertTrue(bridge.contains("if (MediaMetadataCache.profile() != profileId)"))
        assertTrue(bridge.contains("restoreOriginalMetadata"))
    }
}
