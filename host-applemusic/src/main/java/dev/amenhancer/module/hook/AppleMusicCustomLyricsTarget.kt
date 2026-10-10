package dev.amenhancer.module.hook

import android.os.Handler
import android.os.Looper
import dev.amenhancer.module.config.TargetConfigClient
import dev.amenhancer.module.lyrics.CustomLyricsFilePolicy
import dev.amenhancer.module.lyrics.CustomLyricsFileReader
import dev.amenhancer.module.lyrics.online.TrackScopedDiagnostics
import dev.amenhancer.module.model.CustomLyricsEntry
import java.util.concurrent.ArrayBlockingQueue
import java.util.concurrent.Executor
import java.util.concurrent.ThreadPoolExecutor
import java.util.concurrent.TimeUnit

/** Apple Music 6.5.0 adapter for user-managed, offline ID -> TTML mappings. */
internal class AppleMusicCustomLyricsTarget(
    private val config: TargetConfigClient,
    private val symbols: TargetSymbolResolver,
    private val currentSong: CurrentSongIdentityCache,
    private val autoLyricsRuntime: AutoLyricsRuntime? = null,
    /**
     * Shared capture of the displayed Apple documents. The same instance is
     * read by the online search chain (to refuse replacing a timed document),
     * so it must be the one the parse seam below records into.
     */
    private val timingObservations: TtmlTimingObservationRegistry = TtmlTimingObservationRegistry(),
    /**
     * Shared native lane target. It owns Apple model hooks; this target only
     * forwards custom document and presentation events that it observes.
     */
    private val nativeLyricDelivery: NativeLyricsTarget? = null,
) : CustomLyricsTarget {
    private var installedResult: TargetCapabilityInstall? = null
    private val registration = HookRegistrationScope()
    @Synchronized override fun install(): TargetCapabilityInstall {
        installedResult?.let { return it }
        return try {
            installOnce().also { result ->
                if (result is TargetCapabilityInstall.Active) registration.activate()
                else if (result.message.startsWith("Custom lyric I2 replacement installed")) registration.activate()
                else registration.close()
                installedResult = result
            }
        } catch (error: Throwable) { registration.close(); throw error }
    }
    private fun hook(method: java.lang.reflect.Executable, callback: ModernMethodHook): Boolean =
        ModernXposedRuntime.hookMethod(method,callback,registration)
    private fun installOnce(): TargetCapabilityInstall {
        val installMethodResolution = symbols.resolve(AppleMusicSymbols.LyricsInstallMethod)
        val installMethod = installMethodResolution.valueOrNull()
            ?: return TargetCapabilityInstall.Degraded(installMethodResolution.summary)
        if (!runCatching {
                installMethod.isAccessible = true
                true
            }.getOrDefault(false)
        ) {
            return TargetCapabilityInstall.Degraded(
                "PlayerLyricsViewFragment.I2 could not be made accessible; " +
                    installMethodResolution.summary,
            )
        }
        val ptrResolution = symbols.resolve(AppleMusicSymbols.SongInfoPtr)
        val ptrClass = ptrResolution.valueOrNull()
            ?: return TargetCapabilityInstall.Degraded(ptrResolution.summary)
        val nativeResolution = symbols.resolve(AppleMusicSymbols.SongInfoNative)
        val nativeClass = nativeResolution.valueOrNull()
            ?: return TargetCapabilityInstall.Degraded(nativeResolution.summary)
        val parserResolution = symbols.resolve(AppleMusicSymbols.TtmlParserNative)
        val parserClass = parserResolution.valueOrNull()
            ?: return TargetCapabilityInstall.Degraded(parserResolution.summary)
        val parseMethodResolution = symbols.resolve(AppleMusicSymbols.TtmlSongInfoFromTtml)
        val parseMethod = parseMethodResolution.valueOrNull()
            ?: return TargetCapabilityInstall.Degraded(parseMethodResolution.summary)
        val seam = CurrentItemIdentitySeam(symbols)
        seam.resolve(installMethod)?.let { diagnostic ->
            return TargetCapabilityInstall.Degraded(diagnostic)
        }
        val nativeParser = TtmlNativeParser.create(
            parserClass = parserClass,
            parseMethod = parseMethod,
            ptrClass = ptrClass,
            nativeClass = nativeClass,
        ) ?: return TargetCapabilityInstall.Degraded(
            "TTML native parser surface was unavailable; " +
                listOf(
                    parserResolution.summary,
                    parseMethodResolution.summary,
                    ptrResolution.summary,
                    nativeResolution.summary,
                ).joinToString("; "),
        )
        val parser = OpaqueTtmlParser(nativeParser)
        val fileReader = CustomLyricsFileReader { fileId ->
            config.openFile(fileId)?.let { input ->
                runCatching {
                    input.use(CustomLyricsFilePolicy::readBounded)
                }.getOrNull()
            }
        }
        val mainHandler = Handler(Looper.getMainLooper())
        val translationLog = TrackScopedDiagnostics(ModernXposedRuntime::log)
        // The post-overlay presentation refresh the custom completion asks for:
        // HLE's own supplement store refreshes on its content change
        // (`AppleSupplementDataReceive` → `refreshAppleLyricsSupplementPresentation`),
        // and the feed's overlay write is the fork's equivalent. Wired below to
        // the native lyric delivery this target already owns — no new global — and
        // left a no-op until then, so an early completion fails open.
        var onCustomOverlayUpdated: (Long) -> Unit = {}
        // The other half of a successful completion: the enricher's merged
        // document (the user's body plus the injected lanes). The feed forwards
        // it here and, once the custom session exists below, it is re-parsed as
        // the track's preferred replacement pointer. That is what the automatic
        // path already does with its merged candidate, and it is the re-parse
        // that makes a late lane visible on a custom pointer instead of waiting
        // for the page to be re-created. Left a no-op until wired, so an early
        // completion fails open; the session re-checks the body invariant.
        var publishMergedDocument: (Long, String, String) -> Unit = { _, _, _ -> }
        // The custom path's half of the runtime's translation/pronunciation
        // completion. The automatic path stays closed for every manual/mapped
        // id: when its enrichment returns null it falls through to
        // `resolverFetch`, which would replace the user's body with a searched
        // document. This feed therefore calls the same enricher directly, for
        // the document the custom session is about to display. Its overlay side
        // effect delivers the lanes and its merged document becomes the session's
        // preferred pointer — but only after the session proves the body is
        // unchanged, so the custom body still wins and no searched source can
        // replace it.
        val customCompletion = autoLyricsRuntime?.let { runtime ->
            runtime.translationEnricher?.let { enrich ->
                CustomLyricsCompletionFeed(
                    enrich = enrich,
                    executor = runtime.executor,
                    log = { appleMusicId, line -> translationLog.log(appleMusicId, line) },
                    onOverlayUpdated = { appleMusicId -> onCustomOverlayUpdated(appleMusicId) },
                    onMergedDocument = { appleMusicId, raw, merged ->
                        publishMergedDocument(appleMusicId, raw, merged)
                    },
                )
            }
        }
        val readCustomTtml: (CustomLyricsEntry) -> String? = { entry ->
            fileReader.read(entry)?.also { ttml ->
                customCompletion?.remember(entry.appleMusicId, entry.sha256, ttml)
            }
        }
        lateinit var readyReapply: CustomLyricsReadyReapply
        var refreshSession: AutoLyricsRefreshSession? = null
        val configuredManualIds = runCatching {
            config.customLyricsManifest().entries
                .filter(CustomLyricsEntry::enabled)
                .mapTo(mutableSetOf(), CustomLyricsEntry::appleMusicId)
        }.getOrDefault(emptySet())
        val session = CustomLyricsReplacementSession(
            index = CustomLyricsIndexProvider {
                config.customLyricsManifest().entries.associateBy(
                    CustomLyricsEntry::appleMusicId,
                )
            },
            readTtml = readCustomTtml,
            parseTtml = parser::parse,
            isAlive = parser::isAlive,
            verifyPtr = parser::isValid,
            readAdamId = parser::adamIdOf,
            bindAdamId = parser::bindAdamId,
            onReplacementPublished = { appleMusicId ->
                mainHandler.post {
                    if (registration.isActive) {
                        readyReapply.onReplacementPublished(appleMusicId)
                        if (currentSong.current()?.details?.appleMusicId == appleMusicId) {
                            refreshSession?.onSongChanged(appleMusicId)
                        }
                    }
                }
            },
            executor = ThreadPoolExecutor(
                1,
                1,
                0L,
                TimeUnit.MILLISECONDS,
                ArrayBlockingQueue(1),
                { runnable -> Thread(runnable, "ampp-custom-lyrics").apply { isDaemon = true } },
                ThreadPoolExecutor.AbortPolicy(),
            ),
            logger = ModernXposedRuntime::log,
        )
        // The custom session now exists: a completed enrichment publishes its
        // merged document as the track's preferred replacement pointer, so the
        // post-overlay refresh below installs a fresh model that carries the
        // lanes. Idempotent per (track, merged content) and body-checked inside
        // the session, so a repeated completion is a no-op and the user's body
        // can never be replaced.
        publishMergedDocument = { appleMusicId, rawTtml, mergedTtml ->
            val published = session.publishMerged(appleMusicId, rawTtml, mergedTtml)
            translationLog.log(
                appleMusicId,
                "online-translation merged-publish id=$appleMusicId published=$published",
            )
        }
        refreshSession = autoLyricsRuntime?.let { runtime ->
            val refreshSong = runtime.refreshSong ?: return@let null
            val refreshExecutor = runtime.refreshExecutor ?: return@let null
            AutoLyricsRefreshSession(
                executor = refreshExecutor,
                refreshSong = refreshSong,
                isEnabled = { config.settings().let { it.customLyricsEnabled && it.automaticLyricsEnabled } },
                onUpdated = { entry, isCancelled ->
                    // This callback already runs on the independent refresh worker.
                    session.refreshEntry(entry, isCancelled, Executor { it.run() }) { id ->
                        mainHandler.post {
                            if (registration.isActive && !isCancelled()) readyReapply.onReplacementPublished(id)
                        }
                    }
                },
                logger = ModernXposedRuntime::log,
            ).also { refresh -> registration.onClose {
                refresh.close()
                runtime.closeRefresh()
            } }
        }
        val autoSession = autoLyricsRuntime?.let { runtime ->
            AutoLyricsReplacementSession(
                fetchCandidate = { appleMusicId ->
                    val enrich = runtime.translationEnricher
                    val observedMetadata = timingObservations.metadataOfAppleMusicId(appleMusicId)
                    val displayedTtml = enrich?.let {
                        runCatching {
                            timingObservations.rawTtmlOfAppleMusicId(appleMusicId)
                        }.getOrNull()
                    }
                    if (enrich != null) {
                        translationLog.log(
                            appleMusicId,
                            "online-translation capture id=$appleMusicId " +
                                "rawTtml=${if (displayedTtml != null) "present" else "absent"} " +
                                "observed=${observedMetadata != null} " +
                                "timing=${observedMetadata?.timingMode ?: "none"}",
                        )
                    }
                    val fetched = selectAutoLyricsFetch(
                        enrich = enrich,
                        displayedTtml = displayedTtml,
                        appleMusicId = appleMusicId,
                        resolverFetch = runtime.resolver::fetch,
                    )
                    if (enrich != null) {
                        translationLog.log(
                            appleMusicId,
                            "online-translation inject id=$appleMusicId " +
                                "source=${fetched?.source ?: "none"} " +
                                "published=${fetched != null}",
                        )
                    }
                    fetched?.let { candidate ->
                        val details = currentSong.current()
                            ?.takeIf { it.details.appleMusicId == appleMusicId }
                            ?.details
                        val displayName = listOfNotNull(
                            details?.title?.takeIf(String::isNotBlank),
                            details?.artist?.takeIf(String::isNotBlank),
                        ).joinToString(" - ").ifBlank { null }
                        candidate.copy(displayName = displayName)
                    }
                },
                cache = runtime.cache,
                parseTtml = parser::parse,
                isAlive = parser::isAlive,
                verifyPtr = parser::isValid,
                readAdamId = parser::adamIdOf,
                bindAdamId = parser::bindAdamId,
                onReplacementPublished = { appleMusicId ->
                    mainHandler.post {
                        if (registration.isActive) {
                            readyReapply.onReplacementPublished(appleMusicId)
                            if (currentSong.current()?.details?.appleMusicId == appleMusicId) {
                                refreshSession?.onSongChanged(appleMusicId)
                            }
                        }
                    }
                },
                publisher = runtime.publisher,
                isAllowed = { appleMusicId ->
                    appleMusicId !in runtime.suppressedIds &&
                        appleMusicId !in configuredManualIds &&
                        !session.isMapped(appleMusicId) &&
                        session.readyReplacementFor(appleMusicId) == null
                },
                executor = runtime.executor,
                logger = ModernXposedRuntime::log,
                onFreshCandidate = { refreshSession?.markDownloaded(it) },
            )
        }
        val readyReplacementFor: (Long) -> Any? = { appleMusicId ->
            parser.unwrap(session.readyReplacementFor(appleMusicId)
                ?: autoSession?.readyReplacementFor(appleMusicId))
        }
        val isTracking: (Long) -> Boolean = { appleMusicId ->
            session.isTracking(appleMusicId) || autoSession?.isTracking(appleMusicId) == true
        }
        // The native lane hooks are installed by NativeLyricsFeature. This
        // custom-lyrics capability only forwards its document/pointer events to
        // that shared delivery instance.
        nativeLyricDelivery?.setModuleSupplementSongResolver { appleMusicId ->
            readyReplacementFor(appleMusicId) != null
        }
        onCustomOverlayUpdated = { appleMusicId ->
            nativeLyricDelivery?.onCustomOverlayUpdated(appleMusicId)
        }
        val fragmentUsable = fragmentIsAddedPredicate(installMethod.declaringClass)
        readyReapply = CustomLyricsReadyReapply(
            installMethod = installMethod,
            seam = seam,
            readyReplacementFor = readyReplacementFor,
            isFragmentUsable = fragmentUsable,
            currentSong = currentSong,
            logger = ModernXposedRuntime::log,
        )
        registration.onClose(readyReapply::clear)
        val parserHooked = runCatching {
            parseMethod.isAccessible = true
            hook(parseMethod, object : ModernMethodHook() {
                override fun afterHookedMethod(param: MethodHookParam) {
                    runCatching {
                        val ttml = param.args.getOrNull(0) as? String ?: return@runCatching
                        // The module builds every replacement pointer through
                        // this same native entry point. Capturing one would
                        // record our generated document as Apple's, and the
                        // translation pass would then enrich its own output.
                        if (NativeTtmlParseOrigin.isModuleInitiated()) return@runCatching
                        val pointer = param.result
                        val metadata = TtmlTimingPolicy.metadataOf(ttml)
                        val appleMusicId = pointer?.let(parser::adamIdOf)?.takeIf { it > 0L }
                        // Record the displayed document even while the Adam ID
                        // is still unbound: Apple parses first and identifies
                        // the pointer later, so an id-gated capture drops every
                        // ordinary document. The I2 seam binds it to the track.
                        timingObservations.record(pointer, metadata, appleMusicId, rawTtml = ttml)
                        if (autoSession != null) {
                            val observedId = appleMusicId
                                ?: currentSong.current()?.details?.appleMusicId
                                ?: 0L
                            translationLog.log(
                                observedId,
                                "online-translation ttml-observed id=${appleMusicId ?: "unbound"} " +
                                    "current=${observedId.takeIf { it > 0L } ?: "unknown"} " +
                                    "captured=${pointer != null} bytes=${ttml.length} " +
                                    "timing=${metadata.timingMode} " +
                                    "appleTranslation=${metadata.hasTranslation}",
                            )
                        }
                        if (
                            appleMusicId != null &&
                            currentSong.current()?.details?.appleMusicId == appleMusicId &&
                            shouldTryAutoLyricsForMetadata(metadata) &&
                            session.readyReplacementFor(appleMusicId) == null
                        ) {
                            autoSession?.ensureRequested(appleMusicId)
                        }
                    }.onFailure { error ->
                        ModernXposedRuntime.log("custom lyrics TTML timing observation failed: $error")
                    }
                }
            })
        }.isSuccess
        val itemUpdateContext = LyricsItemUpdateContext()
        val hooked = runCatching {
            hook(installMethod, object : ModernMethodHook() {
                override fun beforeHookedMethod(param: MethodHookParam) {
                    itemUpdateContext.markAppleInvokedI2()
                    runCatching {
                        if (!acceptsLyricsInstallArguments(param.args, ptrClass)) return@runCatching
                        val original = param.args[0]
                        val fragmentAdamId = seam.currentItemAdamIdOf(param.thisObject)
                        val publishedCurrent = currentSong.current()
                        val publishedAdamId = publishedCurrent?.details?.appleMusicId
                        val adamId = selectLyricsInjectionAdamId(
                            original = original,
                            fragmentAdamId = fragmentAdamId,
                            publishedAdamId = publishedAdamId,
                        )
                        adamId ?: return@runCatching
                        // Ask the native-model delivery to install its per-line
                        // and availability hooks for the pointer that is about
                        // to be shown. HLE does this from the result
                        // presentation as well as the view-model build, because
                        // the build seam is not guaranteed to carry the pointer
                        // on every build.
                        nativeLyricDelivery?.onLyricsPointer(original)
                        // Remember the fragment+pointer the app is presenting:
                        // the build gate re-invokes this same presentation to
                        // make a late lane visible. Our own refresh's invoke is
                        // ignored inside (a re-entry guard), so the binding
                        // always belongs to Apple's presentation.
                        nativeLyricDelivery?.onLyricsPresentation(param.thisObject, original)
                        // The displayed Apple document was captured at parse
                        // time before its Adam ID was bound. This is the first
                        // seam that knows the track identity, so bind the
                        // capture here and start the translation lane once,
                        // when its body first becomes available. A request that
                        // already ran without a document (availability probe or
                        // an earlier null-pointer install) is retried instead
                        // of waiting out its retry cooldown.
                        if (original != null && autoSession != null) {
                            val capturedBefore = !timingObservations
                                .rawTtmlOfAppleMusicId(adamId).isNullOrEmpty()
                            if (timingObservations.associate(original, adamId)) {
                                val captured = timingObservations.rawTtmlOfAppleMusicId(adamId)
                                if (!captured.isNullOrEmpty() && !capturedBefore) {
                                    translationLog.log(
                                        adamId,
                                        "online-translation ttml-associated id=$adamId " +
                                            "bytes=${captured.length}",
                                    )
                                    autoSession.onDisplayedDocumentCaptured(adamId)
                                }
                            }
                        }
                        val manualReplacement = session.replacementFor(adamId)
                        val timingMetadata = timingObservations.metadataOf(original)
                        val autoEligible = autoSession != null &&
                            shouldTryAutoLyrics(
                                original = original,
                                metadata = timingMetadata,
                            )
                        val autoReplacement = when {
                            shouldPrepareAutomaticLyrics(manualReplacement, autoEligible) -> {
                                autoSession?.replacementFor(adamId)
                            }
                            manualReplacement == null -> {
                                autoSession?.takeoverReplacementFor(
                                    appleMusicId = adamId,
                                    original = parser.wrap(original),
                                    metadata = timingMetadata,
                                )
                            }
                            else -> null
                        }
                        // User-managed mappings always win; automatic sources are
                        // only a fallback for a missing/non-Word pointer or an
                        // explicitly foreign Word document without translation.
                        val replacement = manualReplacement ?: autoReplacement
                        val tracking = session.isTracking(adamId) ||
                            (autoEligible && autoSession?.isTracking(adamId) == true)
                        val needsRebind = original == null &&
                            publishedCurrent != null &&
                            publishedAdamId != null &&
                            publishedAdamId != fragmentAdamId
                        val canRebind = currentSong.canRebind(fragmentAdamId, publishedAdamId)
                        if (
                            needsRebind && tracking && canRebind &&
                            (fragmentAdamId == null || session.isMapped(adamId))
                        ) {
                            val rebound = param.thisObject?.let { fragment ->
                                seam.bindCurrentItemOf(fragment, publishedCurrent.item)
                            } == true
                            if (!rebound) return@runCatching
                        }
                        if (replacement == null) {
                            if (shouldRecordReadyLateMiss(original, replacement) && tracking) {
                                param.thisObject?.let { readyReapply.recordMiss(it, adamId) }
                            }
                        } else {
                            if (manualReplacement == null && autoReplacement != null) {
                                autoSession?.markTakeoverApplied(adamId)
                            }
                            param.thisObject?.let { readyReapply.dismiss(it) }
                            parser.unwrap(replacement)?.let { nativeReplacement ->
                                param.extras["ampp-installed-lyrics"] = adamId to nativeReplacement
                            }
                            if (replacement !== original) {
                                param.args[0] = parser.unwrap(replacement)
                            }
                            // The document about to be shown is the custom one.
                            // Queue the same lane completion the automatic path
                            // runs, off the hook thread; the feed dedupes per
                            // track and document revision and only ever writes
                            // the native overlay.
                            if (manualReplacement != null) {
                                customCompletion?.onDisplayed(adamId)
                            }
                        }
                    }.onFailure { error ->
                        ModernXposedRuntime.log("custom lyrics I2 replacement hook failed: $error")
                    }
                }

                override fun afterHookedMethod(param: MethodHookParam) {
                    if (param.throwable != null) return
                    @Suppress("UNCHECKED_CAST")
                    val installed = param.extras["ampp-installed-lyrics"] as? Pair<Long, Any> ?: return
                    if (param.args.firstOrNull() !== installed.second) return
                    param.thisObject?.let { readyReapply.recordInstalled(it, installed.first, installed.second) }
                }
            })
        }.isSuccess
        if (!hooked) {
            return TargetCapabilityInstall.Degraded(
                "PlayerLyricsViewFragment.I2 could not be hooked; ${installMethodResolution.summary}",
            )
        }
        val itemUpdateResolution = symbols.resolve(AppleMusicSymbols.LyricsItemUpdateMethod)
        val itemUpdateMethod = itemUpdateResolution.valueOrNull()
        if (itemUpdateMethod != null) {
            val coordinator = runCatching {
                LyricsItemUpdateCoordinator(
                    installMethod = installMethod,
                    flags = ItemUpdateFlags(itemUpdateMethod.parameterTypes[2]),
                    seam = seam,
                    readyReplacementFor = readyReplacementFor,
                    isTracking = isTracking,
                    isFragmentUsable = fragmentUsable,
                    readyReapply = readyReapply,
                    logger = ModernXposedRuntime::log,
                )
            }.getOrNull()
            if (coordinator != null) {
                val itemUpdateHooked = runCatching {
                    hook(itemUpdateMethod, object : ModernMethodHook() {
                        override fun beforeHookedMethod(param: MethodHookParam) {
                            itemUpdateContext.enterO2()
                        }

                        override fun afterHookedMethod(param: MethodHookParam) {
                            try {
                                val fragment = param.thisObject
                                val appleInvokedI2 = itemUpdateContext.appleInvokedI2DuringO2()
                                val flagsHolder = param.args.getOrNull(2)
                                itemUpdateContext.reentering {
                                    runCatching {
                                        fragment?.let { currentFragment ->
                                            coordinator.onItemUpdate(
                                                fragment = currentFragment,
                                                flagsHolder = flagsHolder,
                                                appleInvokedI2 = appleInvokedI2,
                                            )
                                        }
                                    }.onFailure { error ->
                                        ModernXposedRuntime.log(
                                            "custom lyrics item update hook failed: $error",
                                        )
                                    }
                                }
                            } finally {
                                itemUpdateContext.exitO2()
                            }
                        }
                    })
                }.isSuccess
                if (!itemUpdateHooked) {
                    ModernXposedRuntime.log(
                        "PlayerLyricsViewFragment.o2 could not be hooked; " +
                            itemUpdateResolution.summary,
                    )
                }
            }
        }
        val availabilityResolution = symbols.resolve(AppleMusicSymbols.LyricsAvailabilityPredicate)
        val availabilityMethod = availabilityResolution.valueOrNull()
        val availabilityHooked = availabilityMethod != null && runCatching {
            availabilityMethod.isAccessible = true
            hook(availabilityMethod, object : ModernMethodHook() {
                override fun afterHookedMethod(param: MethodHookParam) {
                    runCatching {
                        val nativeLyricsAvailable = param.result as? Boolean ?: return@runCatching
                        if (nativeLyricsAvailable) return@runCatching
                        val appleMusicId = seam.detailsOfItem(param.args.getOrNull(0))?.appleMusicId
                        appleMusicId?.let { id ->
                            session.ensureRequested(id)
                            autoSession?.ensureRequested(id)
                        }
                        val replacementReady = appleMusicId != null &&
                            (session.replacementOrPrepareFor(appleMusicId) != null ||
                                autoSession?.replacementOrPrepareFor(appleMusicId) != null)
                        if (
                            shouldExposeCustomLyrics(
                                nativeLyricsAvailable = nativeLyricsAvailable,
                                appleMusicId = appleMusicId,
                                replacementReady = replacementReady,
                            )
                        ) {
                            param.result = true
                        }
                    }.onFailure { error ->
                        ModernXposedRuntime.log("custom lyrics availability hook failed: $error")
                    }
                }
            })
        }.isSuccess
        session.start()
        val identitySubscription = currentSong.addListener { current ->
            val appleMusicId = current?.details?.appleMusicId
            appleMusicId?.let(session::ensureRequested)
            autoSession?.onSongChanged(appleMusicId)
            readyReapply.onSongChanged(appleMusicId)
            refreshSession?.onSongChanged(appleMusicId,
                cacheReady = appleMusicId != null && session.readyReplacementFor(appleMusicId) != null)
            appleMusicId?.let { id ->
                timingObservations.metadataOfAppleMusicId(id)
                    ?.takeIf(::shouldTryAutoLyricsForMetadata)
                    ?.takeIf { session.readyReplacementFor(id) == null }
                    ?.let { autoSession?.ensureRequested(id) }
            }
        }
        registration.onClose(identitySubscription::close)
        if (!availabilityHooked) {
            return TargetCapabilityInstall.Degraded(
                "Custom lyric I2 replacement installed, but unavailable-lyrics entry could not be enabled; " +
                    availabilityResolution.summary,
            )
        }
        return TargetCapabilityInstall.Active(
            "Custom lyric ID mappings installed" +
                (if (autoSession != null) " with automatic Word-TTML fallback" else "") + "; " +
                listOf(
                    installMethodResolution.summary,
                    availabilityResolution.summary,
                    itemUpdateResolution.summary,
                    ptrResolution.summary,
                    nativeResolution.summary,
                    parserResolution.summary,
                    parseMethodResolution.summary,
                    "timingHooked=$parserHooked",
                    "nativeLyricModel=${nativeLyricDelivery != null}",
                    seam.fieldSummary.orEmpty(),
                ).joinToString("; "),
        )
    }

}

internal fun selectLyricsInjectionAdamId(
    original: Any?,
    fragmentAdamId: Long?,
    publishedAdamId: Long?,
): Long? = if (
    original == null &&
    publishedAdamId != null &&
    publishedAdamId > 0L &&
    publishedAdamId != fragmentAdamId
) {
    publishedAdamId
} else {
    fragmentAdamId
}

/**
 * Apple emits a null SongInfoPtr when a playback item has no native lyrics,
 * and a live SongInfoPtr otherwise. Both forms can be recorded by the
 * ready-late reapply ledger while their custom replacement is preparing; the
 * ledger applies the same current-item and lifecycle gates to both.
 */
internal fun acceptsLyricsInstallArguments(args: Array<Any?>, ptrClass: Class<*>): Boolean =
    args.isNotEmpty() && (args[0] == null || ptrClass.isInstance(args[0]))

/**
 * Automatic lookup is fail-open for a missing native pointer and opt-in only
 * when the parser seam proved that the original document is not Word-timed or
 * is a foreign Word-timed document without a translation track. An unobserved
 * live pointer is left untouched rather than guessing.
 */
internal fun shouldTryAutoLyrics(
    original: Any?,
    metadata: TtmlDocumentMetadata?,
): Boolean = original == null ||
    shouldTryAutoLyricsForMetadata(metadata)

internal fun shouldTryAutoLyricsForMetadata(metadata: TtmlDocumentMetadata?): Boolean =
    metadata?.timingMode == TtmlTimingMode.NON_WORD || metadata?.needsTranslationFallback == true

internal fun shouldPrepareAutomaticLyrics(
    manualReplacement: Any?,
    autoEligible: Boolean,
): Boolean = manualReplacement == null && autoEligible

/**
 * Picks the candidate the automatic path should prepare.
 *
 * With the translation toggle on, [enrich] is non-null and a previously
 * observed displayed document [displayedTtml] routes the lookup through it.
 * When enrichment returns null — no translation needed, no source found, or any
 * failure — the fixed resolver still runs, exactly as it did before the
 * translation feature existed. Failing closed here would let enabling the
 * translation toggle suppress a lyric supplement that otherwise worked.
 * Only the merged document produced by a successful enrichment is attributed to
 * [ONLINE_TRANSLATION_LYRIC_SOURCE].
 */
internal fun selectAutoLyricsFetch(
    enrich: ((Long, String) -> String?)?,
    displayedTtml: String?,
    appleMusicId: Long,
    resolverFetch: (Long) -> AutoLyricsCandidate?,
): AutoLyricsCandidate? {
    val enriched: () -> AutoLyricsCandidate? = {
        if (enrich == null || displayedTtml == null) {
            null
        } else {
            runCatching { enrich.invoke(appleMusicId, displayedTtml) }.getOrNull()
                ?.let { merged -> AutoLyricsCandidate(ONLINE_TRANSLATION_LYRIC_SOURCE, merged) }
        }
    }
    // Apple has no lyrics, or only unsynchronised text that does not scroll with playback: a
    // third-party document carrying a real timeline is a strict improvement (it can be followed
    // and tapped), so the replacement path runs FIRST and enrichment is only the fallback.
    // Previously enrichment was always tried first, which meant it always produced something for a
    // document that merely lacked a translation -- so the replacement path never ran at all for an
    // unsynchronised document, and that permission was empty.
    val replacementFirst = displayedTtml == null || !TtmlTimingPolicy.hasTiming(displayedTtml)
    return if (replacementFirst) {
        resolverFetch(appleMusicId) ?: enriched()
    } else {
        // Apple already carries line or word timing: a search source must never replace it, so only
        // the translation lane is added.  The resolver stays the fallback when enrichment yields
        // nothing -- it may still reach the bundled providers, which are allowed to replace.
        enriched() ?: resolverFetch(appleMusicId)
    }
}

internal fun shouldExposeCustomLyrics(
    nativeLyricsAvailable: Boolean,
    appleMusicId: Long?,
    replacementReady: Boolean,
): Boolean = nativeLyricsAvailable || (appleMusicId != null && appleMusicId > 0L && replacementReady)
