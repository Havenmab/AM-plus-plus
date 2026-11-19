package dev.amenhancer.module.hook

import android.os.Handler
import android.os.Looper
import dev.amenhancer.module.config.TargetConfigClient
import dev.amenhancer.module.lyrics.AmllTtmlFormatConverter
import dev.amenhancer.module.lyrics.CustomLyricsFilePolicy
import dev.amenhancer.module.lyrics.CustomLyricsFileReader
import dev.amenhancer.module.lyrics.online.TrackScopedDiagnostics
import dev.amenhancer.module.model.CustomLyricsEntry
import java.util.Locale
import java.util.concurrent.ArrayBlockingQueue
import java.util.concurrent.Executor
import java.util.concurrent.ThreadPoolExecutor
import java.util.concurrent.TimeUnit

/** Owns the native parser seam and submits prepared lyrics through the host result contract. */
internal class AppleMusicCustomLyricsTarget(
    private val config: TargetConfigClient,
    private val symbols: TargetSymbolResolver,
    private val currentSong: CurrentSongIdentityCache,
    private val autoLyricsRuntime: AutoLyricsRuntime? = null,
    /**
     * Shared capture of the displayed Apple documents. The same instance is
     * read by the online search chain (to refuse replacing a timed document)
     * and by the translation enricher, so it must be the one the parse seam
     * below records into. The result pipeline itself is not triggered from it.
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
    private var installedParserResult: TargetCapabilityInstall? = null
    private val parserRegistration = HookRegistrationScope()
    @Volatile private var parserObserver: ((String, Any?) -> Unit)? = null

    /** Native input repair is independent of enabling user-managed lyric replacement. */
    @Synchronized fun installParserRepair(): TargetCapabilityInstall {
        installedParserResult?.let { return it }
        val result = runCatching {
            val resolution = symbols.resolve(AppleMusicSymbols.TtmlSongInfoFromTtml)
            val method = resolution.valueOrNull()
                ?: return@runCatching TargetCapabilityInstall.Degraded(resolution.summary)
            method.isAccessible = true
            check(ModernXposedRuntime.hookMethod(method, object : ModernMethodHook() {
                override fun beforeHookedMethod(param: MethodHookParam) {
                    val ttml = param.args.getOrNull(0) as? String ?: return
                    runCatching {
                        // Custom lyrics may still need this hook for observation while repair is off.
                        if (!config.settings().lyricEffectsOptimizationEnabled ||
                            !isSimplifiedLyricsLocale(Locale.getDefault())
                        ) return@runCatching
                        val repaired = AmllTtmlFormatConverter.repairSimplifiedReplacementBackgrounds(ttml)
                        if (repaired != ttml) {
                            param.extras["originalTtml"] = ttml
                            param.args[0] = repaired
                        }
                    }.onFailure { error ->
                        ModernXposedRuntime.log("native TTML background repair failed: $error")
                    }
                }

                override fun afterHookedMethod(param: MethodHookParam) {
                    if (param.throwable != null) return
                    // Observations retain the exact host input, not the repaired display document.
                    val original = param.extras["originalTtml"] as? String
                        ?: param.args.getOrNull(0) as? String ?: return
                    parserObserver?.invoke(original, param.result)
                }
            }, parserRegistration)) { "Native TTML parser hook unavailable" }
            parserRegistration.activate()
            TargetCapabilityInstall.Active("Native TTML parser hook installed")
        }.getOrElse { error ->
            TargetCapabilityInstall.Degraded("Native TTML parser repair unavailable: ${error.message}")
        }
        if (result !is TargetCapabilityInstall.Active) parserRegistration.close()
        installedParserResult = result
        return result
    }

    @Synchronized override fun install(): TargetCapabilityInstall {
        installedResult?.let { return it }
        return try {
            installOnce().also { result ->
                if (result is TargetCapabilityInstall.Active) registration.activate()
                else if (result.message.startsWith("Custom lyric ViewModel submission installed")) registration.activate()
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
        val mainHandler = Handler(Looper.getMainLooper())
        lateinit var resultBridge: LyricsResultSubmissionBridge
        val preparationExecutor = ThreadPoolExecutor(
            1,
            1,
            0L,
            TimeUnit.MILLISECONDS,
            ArrayBlockingQueue(1),
            { runnable -> Thread(runnable, "ampp-custom-lyrics").apply { isDaemon = true } },
            ThreadPoolExecutor.AbortPolicy(),
        )
        val parser = OpaqueTtmlParser(
            native = nativeParser,
            executor = preparationExecutor,
            onSubmissionReady = { id ->
                mainHandler.post {
                    if (registration.isActive) resultBridge.onReplacementPublished(id)
                }
            },
            logger = ModernXposedRuntime::log,
        )
        registration.onClose { preparationExecutor.shutdownNow() }
        val fileReader = CustomLyricsFileReader { fileId ->
            config.openFile(fileId)?.let { input ->
                runCatching {
                    input.use(CustomLyricsFilePolicy::readBounded)
                }.getOrNull()
            }
        }
        val translationLog = TrackScopedDiagnostics(ModernXposedRuntime::log)
        // The submitted replacement for the current request. Every source below
        // goes through this one callback, so the native result observer remains
        // the only installer instead of an out-of-band Fragment re-entry.
        // The other half of a successful completion: the enricher's merged
        // document (the user's body plus the injected lanes). The feed forwards
        // it here and the custom session re-parses it as the track's preferred
        // replacement only after proving the body is unchanged; the bridge then
        // re-submits that prepared pointer through the host result channel.
        var publishMergedDocument: (Long, String, String) -> Unit = { _, _, _ -> }
        // The custom path's half of the runtime's translation/pronunciation
        // completion. The automatic path stays closed for every manual/mapped
        // id: when its enrichment returns null it falls through to
        // `resolverFetch`, which would replace the user's body with a searched
        // document. This feed therefore calls the same enricher directly, for
        // the document the custom session is about to display: its overlay side
        // effect delivers the lanes and its merged document becomes the
        // session's preferred pointer once the body invariant holds.
        val customCompletion = autoLyricsRuntime?.let { runtime ->
            runtime.translationEnricher?.let { enrich ->
                CustomLyricsCompletionFeed(
                    enrich = enrich,
                    executor = runtime.executor,
                    log = { appleMusicId, line -> translationLog.log(appleMusicId, line) },
                    // HLE's supplement store refreshes on its content change
                    // (`AppleSupplementDataReceive` → `refreshAppleLyricsSupplementPresentation`);
                    // the feed's overlay write is the fork's equivalent. Wired to
                    // the native lyric delivery this target already owns.
                    onOverlayUpdated = { appleMusicId ->
                        nativeLyricDelivery?.onCustomOverlayUpdated(appleMusicId)
                    },
                    onMergedDocument = { appleMusicId, raw, merged ->
                        publishMergedDocument(appleMusicId, raw, merged)
                    },
                    // The displayed document's own lanes are published before the
                    // enricher: a custom document that already carries its
                    // translation produces no merged document, so this is the only
                    // write that carries its (Word-timed) romanization into the
                    // model the native getters can serve.
                    onDisplayedDocument = { appleMusicId, ttml ->
                        runtime.nativeLyricOverlay.publishDisplayed(appleMusicId, ttml)
                    },
                )
            }
        }
        val readCustomTtml: (CustomLyricsEntry) -> String? = { entry ->
            fileReader.read(entry)?.also { ttml ->
                customCompletion?.remember(entry.appleMusicId, entry.sha256, ttml)
            }
        }
        var refreshSession: AutoLyricsRefreshSession? = null
        var llmSession: LlmLyricsReplacementSession? = null
        var autoSession: AutoLyricsReplacementSession? = null
        val configuredManualIds = runCatching {
            config.customLyricsManifest().entries
                .filter { it.enabled && !it.autoManaged }
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
            onDocument = { id, ttml ->
                LyricsPronunciationCentringBridge.reportDocument(id, ttml)
            },
            isAlive = parser::isAlive,
            verifyPtr = parser::isValid,
            readAdamId = parser::adamIdOf,
            bindAdamId = parser::bindAdamId,
            onReplacementPublished = { appleMusicId ->
                mainHandler.post {
                    if (registration.isActive) {
                        resultBridge.onReplacementPublished(appleMusicId)
                    }
                }
            },
            executor = preparationExecutor,
            logger = ModernXposedRuntime::log,
        )
        // The custom session now exists: a completed enrichment publishes its
        // merged document as the track's preferred replacement pointer and asks
        // the bridge to submit it. Idempotent per (track, merged content) and
        // body-checked inside the session, so a repeated completion is a no-op
        // and the user's body can never be replaced.
        publishMergedDocument = { appleMusicId, rawTtml, mergedTtml ->
            val published = session.publishMerged(appleMusicId, rawTtml, mergedTtml)
            translationLog.log(
                appleMusicId,
                "online-translation merged-publish id=$appleMusicId published=$published",
            )
            if (published) {
                mainHandler.post {
                    if (registration.isActive) resultBridge.onReplacementPublished(appleMusicId)
                }
            }
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
                            if (registration.isActive && !isCancelled()) {
                                autoSession?.invalidate(id)
                                resultBridge.onReplacementPublished(id)
                            }
                        }
                    }
                },
                logger = ModernXposedRuntime::log,
            ).also { refresh -> registration.onClose {
                refresh.close()
                runtime.closeRefresh()
            } }
        }
        autoSession = autoLyricsRuntime?.let { runtime ->
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
                    // Apple's synced document and the online translation lane
                    // share the automatic chain: the displayed document is
                    // replaced only when it has no timing, otherwise only the
                    // missing lane is merged.
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
                cache = object : AutoLyricsCache by runtime.cache {
                    override fun readCandidate(appleMusicId: Long): AutoLyricsCandidate? {
                        // This seam is only read by the preparation worker.
                        val entry = config.customLyricsManifest().entries.firstOrNull {
                            it.appleMusicId == appleMusicId && it.enabled && it.autoManaged
                        }
                        return entry?.let { stored ->
                            fileReader.read(stored)?.let { ttml ->
                                AutoLyricsCandidate(stored.source, ttml, stored.displayName)
                            }
                        } ?: runtime.cache.readCandidate(appleMusicId)
                    }
                },
                parseTtml = parser::parse,
                isAlive = parser::isAlive,
                verifyPtr = parser::isValid,
                readAdamId = parser::adamIdOf,
                bindAdamId = parser::bindAdamId,
                onReplacementPublished = { appleMusicId ->
                    mainHandler.post {
                        if (registration.isActive) {
                            resultBridge.onReplacementPublished(appleMusicId)
                        }
                    }
                },
                publisher = runtime.publisher,
                isAllowed = { appleMusicId ->
                    config.settings().let { it.customLyricsEnabled && it.automaticLyricsEnabled } &&
                        appleMusicId !in runtime.suppressedIds &&
                        appleMusicId !in configuredManualIds &&
                        (!session.isMapped(appleMusicId) || session.isAutoManaged(appleMusicId))
                },
                executor = runtime.executor,
                logger = ModernXposedRuntime::log,
                onFreshCandidate = { id ->
                    mainHandler.post {
                        if (registration.isActive && currentSong.current()?.details?.appleMusicId == id &&
                            autoSession?.readyReplacementFor(id) != null
                        ) {
                            refreshSession?.onSongChanged(id, cacheReady = false)
                            refreshSession?.markDownloaded(id)
                        }
                    }
                },
                onLookupMiss = { id ->
                    mainHandler.post {
                        if (registration.isActive) resultBridge.onReplacementPublished(id)
                    }
                },
                fetchOrderedCandidate = { appleMusicId, sources, cached ->
                    runtime.resolver.fetch(appleMusicId, sources, cached)?.let { found ->
                        val details = currentSong.current()
                            ?.takeIf { it.details.appleMusicId == appleMusicId }?.details
                        val displayName = listOfNotNull(
                            details?.title?.takeIf(String::isNotBlank),
                            details?.artist?.takeIf(String::isNotBlank),
                        ).joinToString(" - ").ifBlank { found.displayName }
                        if (found === cached) found else found.copy(displayName = displayName)
                    }
                },
            )
        }
        llmSession = autoLyricsRuntime?.llm?.let { runtime ->
            LlmLyricsReplacementSession(
                runtime = runtime,
                parseTtml = parser::parse,
                isAlive = parser::isAlive,
                verifyPtr = parser::isValid,
                readAdamId = parser::adamIdOf,
                bindAdamId = parser::bindAdamId,
                isAllowed = { id ->
                    config.settings().let { it.customLyricsEnabled && it.automaticLyricsEnabled && it.llmLyricsEnabled } &&
                        id !in autoLyricsRuntime.suppressedIds &&
                        id !in configuredManualIds &&
                        (!session.isMapped(id) || session.isAutoManaged(id)) &&
                        autoSession?.readyReplacementFor(id) == null
                },
                onReplacementPublished = { id ->
                    mainHandler.post {
                        if (registration.isActive) resultBridge.onReplacementPublished(id)
                    }
                },
            ).also { translation ->
                translation.onSongChanged(currentSong.current()?.details?.appleMusicId)
                registration.onClose(translation::close)
            }
        }
        val readyReplacementFor: (Long) -> Any? = { appleMusicId ->
            parser.unwrap(session.readyReplacementFor(appleMusicId)
                ?: autoSession?.readyReplacementFor(appleMusicId))
        }
        // The native lane hooks are installed by NativeLyricsFeature. This
        // custom-lyrics capability only forwards its document/pointer events to
        // that shared delivery instance.
        nativeLyricDelivery?.setModuleSupplementSongResolver { appleMusicId ->
            readyReplacementFor(appleMusicId) != null
        }
        // All sources share the host result channel; no Fragment re-entry ledger.
        // User mappings always win, the automatic chain is the fallback for a
        // missing/non-Word pointer or an explicitly foreign Word document
        // without a translation, and the LLM tier only runs after both missed.
        val selectReplacement: (Long, Long, Any?) -> Any? = { id, nativeId, original ->
            val settings = config.settings()
            val automaticEnabled = settings.customLyricsEnabled && settings.automaticLyricsEnabled
            // The native pointer is the only place where the track identity and
            // the document Apple actually displayed are known together. Bind
            // the parse observation here so the translation chain can read the
            // displayed document back, and start the automatic request when its
            // body first becomes available.
            val capturedBefore = !timingObservations.rawTtmlOfAppleMusicId(id).isNullOrEmpty()
            if (original != null && timingObservations.associate(original, id)) {
                val captured = timingObservations.rawTtmlOfAppleMusicId(id)
                if (!captured.isNullOrEmpty() && !capturedBefore) {
                    translationLog.log(
                        id,
                        "online-translation ttml-associated id=$id bytes=${captured.length}",
                    )
                    autoSession?.onDisplayedDocumentCaptured(id)
                }
            }
            val configured = session.replacementFor(id)
            val manual = configured.takeUnless { automaticEnabled && session.isAutoManaged(id) }
            val native = parser.wrap(original, id)
            val metadata = timingObservations.metadataOf(original)
            val sources = automaticLyricsLookupSources(settings, native != null, metadata)
            val automatic = if (manual == null && automaticEnabled) {
                autoSession?.configureSources(id, sources)
                if (sources.isNotEmpty()) autoSession?.replacementFor(id) else null
            } else null
            // Refresh only the provider actually used, not a cached source behind native.
            refreshSession?.onSongChanged(id.takeIf { manual != null || automatic != null })
            // Until preceding sources have missed, native is only the provisional display.
            // Sources after native never delay native's own translation enhancement.
            val nativeSelected = manual == null && automatic == null && automaticEnabled && native != null &&
                (sources.isEmpty() || autoSession?.hasLookupMiss(id) == true)
            val llmEligible = nativeSelected && metadata?.hasTranslation != true &&
                (metadata == null || metadata.language == null || metadata.isForeign)
            val translated = if (llmEligible) {
                llmSession?.onNativeSelected(id)
                // Only the verified native result's own raw document may reach the LLM.
                // Module-created parses are excluded by the parser hook below.
                val raw = timingObservations.nativeTtmlOf(original)
                val details = currentSong.current()?.takeIf { it.details.appleMusicId == id }?.details
                if (raw != null) llmSession?.replacementFor(id, raw, details?.title, details?.artist)
                else {
                    llmSession?.onNativeInputUnavailable(id)
                    null
                }
            } else {
                llmSession?.onHigherPriorityResult(id)
                null
            }
            // The custom document is now the one being submitted: queue the same
            // lane completion the automatic path runs, off the hook thread. The
            // feed dedupes per track and document revision and only ever writes
            // the native overlay or asks the bridge to resubmit.
            if (manual != null) customCompletion?.onDisplayed(id)
            parser.forSubmission(manual ?: automatic ?: translated ?: native, id, nativeId)
        }
        resultBridge = LyricsResultSubmissionBridge.create(
            symbols = symbols,
            registration = registration,
            currentSong = currentSong,
            fragmentSongId = { seam.currentItemCanonicalIdOf(it, currentSong.current()) },
            nativeFragmentSongId = seam::currentItemAdamIdOf,
            itemSongId = { seam.canonicalAdamIdOf(it, currentSong.current()) },
            selectReplacement = selectReplacement,
            isAlive = parser::isAlive,
            isCurrentSubmission = parser::isCurrentSubmission,
            documentOf = parser::documentOf,
            logger = ModernXposedRuntime::log,
        ) ?: return TargetCapabilityInstall.Degraded("Lyric ViewModel result contract unavailable")
        registration.onClose(resultBridge::close)
        val parserHooked = installParserRepair() is TargetCapabilityInstall.Active
        if (parserHooked) {
            parserObserver = { ttml, pointer ->
                if (registration.isActive) {
                    runCatching {
                        val moduleParse =
                            parser.isModuleParse() || NativeTtmlParseOrigin.isModuleInitiated()
                        if (moduleParse) return@runCatching
                        val metadata = TtmlTimingPolicy.metadataOf(ttml)
                        val appleMusicId = pointer?.let(parser::adamIdOf)?.takeIf { it > 0L }
                        // Record the displayed document even while the Adam ID
                        // is still unbound: Apple parses first and identifies
                        // the pointer later, and `selectReplacement` binds the
                        // observation to the track once the pair is known.
                        timingObservations.record(
                            pointer,
                            metadata,
                            appleMusicId,
                            rawTtml = ttml,
                        )
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
                            (!session.isMapped(appleMusicId) || session.isAutoManaged(appleMusicId))
                        ) {
                            val sources = automaticLyricsLookupSources(config.settings(), true, metadata)
                            autoSession?.configureSources(appleMusicId, sources)
                            if (sources.isNotEmpty()) autoSession?.ensureRequested(appleMusicId)
                        }
                    }.onFailure { error ->
                        ModernXposedRuntime.log("custom lyrics TTML timing observation failed: $error")
                    }
                }
            }
            registration.onClose { parserObserver = null }
        }
        // Attach the exact host ViewModel and guard an empty native fallback.
        // Selection and recovery still publish through the upstream result channel.
        val hooked = hook(installMethod, object : ModernMethodHook() {
            override fun beforeHookedMethod(param: MethodHookParam) {
                val fragment = param.thisObject ?: return
                val emptyInstall = param.args.isNotEmpty() && param.args[0] == null
                val pointer = param.args.getOrNull(0)
                runCatching {
                    nativeLyricDelivery?.onLyricsPointer(pointer)
                    nativeLyricDelivery?.onLyricsPresentation(fragment, pointer)
                }.onFailure { error ->
                    ModernXposedRuntime.log("custom lyrics native delivery binding failed: $error")
                }
                resultBridge.attach(fragment, publish = !emptyInstall)
                if (emptyInstall && resultBridge.suppressEmptyInstall(fragment)) param.result = null
            }
            override fun afterHookedMethod(param: MethodHookParam) {
                if (param.throwable == null) param.thisObject?.let { fragment ->
                    resultBridge.onInstalled(fragment, param.args.getOrNull(0))
                }
            }
        })
        if (!hooked) return TargetCapabilityInstall.Degraded("Lyric ViewModel owner hook unavailable")
        val availabilityResolution = symbols.resolve(AppleMusicSymbols.LyricsAvailabilityPredicate)
        val availabilityMethod = availabilityResolution.valueOrNull()
        val availabilityHooked = availabilityMethod != null && runCatching {
            availabilityMethod.isAccessible = true
            hook(availabilityMethod, object : ModernMethodHook() {
                override fun afterHookedMethod(param: MethodHookParam) {
                    runCatching {
                        val nativeLyricsAvailable = param.result as? Boolean ?: return@runCatching
                        if (nativeLyricsAvailable) return@runCatching
                        val appleMusicId = seam.canonicalAdamIdOf(param.args.getOrNull(0), currentSong.current())
                        appleMusicId?.let { id ->
                            session.ensureRequested(id)
                            autoSession?.configureSources(id,
                                automaticLyricsLookupSources(config.settings(), false, null))
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
        }.getOrDefault(false)
        session.start()
        val identitySubscription = currentSong.addListener { current ->
            val appleMusicId = current?.details?.appleMusicId
            appleMusicId?.let(session::ensureRequested)
            autoSession?.onSongChanged(appleMusicId)
            llmSession?.onSongChanged(appleMusicId)
            resultBridge.onSongChanged(appleMusicId)
            refreshSession?.onSongChanged(appleMusicId, cacheReady = false)
            appleMusicId?.let { id ->
                timingObservations.metadataOfAppleMusicId(id)
                    ?.takeIf { !session.isMapped(id) || session.isAutoManaged(id) }
                    ?.let { metadata ->
                        val sources = automaticLyricsLookupSources(config.settings(), true, metadata)
                        autoSession?.configureSources(id, sources)
                        if (sources.isNotEmpty()) autoSession?.ensureRequested(id)
                    }
            }
        }
        registration.onClose(identitySubscription::close)
        if (!availabilityHooked) {
            return TargetCapabilityInstall.Degraded(
                "Custom lyric ViewModel submission installed, but unavailable-lyrics entry could not be enabled; " +
                    availabilityResolution.summary,
            )
        }
        if (!parserHooked && config.settings().llmLyricsEnabled) {
            return TargetCapabilityInstall.Degraded(
                "Custom lyric ViewModel submission installed, but LLM native TTML observation unavailable; " +
                    parseMethodResolution.summary,
            )
        }
        return TargetCapabilityInstall.Active(
            "Custom lyric ViewModel submission installed" +
                (if (autoSession != null) " with automatic Word-TTML fallback" else "") + "; " +
                listOf(
                    installMethodResolution.summary,
                    availabilityResolution.summary,
                    "delivery=ViewModel-result",
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

/** Explicit script wins over region; ambiguous Chinese locales are left unchanged. */
internal fun isSimplifiedLyricsLocale(locale: Locale): Boolean {
    if (!locale.language.equals("zh", ignoreCase = true)) return false
    return if (locale.script.isNotEmpty()) locale.script.equals("Hans", ignoreCase = true)
        else locale.country.equals("CN", ignoreCase = true) || locale.country.equals("SG", ignoreCase = true)
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
 * and a live SongInfoPtr otherwise. Both forms can be submitted by the result
 * bridge for a request whose replacement is still preparing; the bridge
 * applies the same current-item and lifecycle gates to both.
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
    // The fixed providers (AMLL → Lunabeat → AM-Lyrics) are asked FIRST, because their documents
    // carry word-level timing and often their own translation/romanization; the four third-party
    // search sources exist to fill the translation lane afterwards.  Asking the enricher first used
    // to short-circuit that: any song whose translation could be merged kept Apple's line-timed
    // document, so a better word-timed one in AMLL was never even requested.
    //
    // Apple's own *word*-timed document is the one exception.  It is already the finest timeline
    // available, so replacing it could only make playback worse; there the lanes are filled and
    // the resolver is only the fallback when enrichment yields nothing.
    val appleIsWord = displayedTtml != null && TtmlTimingPolicy.isWord(displayedTtml)
    if (appleIsWord) {
        return enriched() ?: resolverFetch(appleMusicId)
    }
    return resolverFetch(appleMusicId) ?: enriched()
}

internal fun shouldExposeCustomLyrics(
    nativeLyricsAvailable: Boolean,
    appleMusicId: Long?,
    replacementReady: Boolean,
): Boolean = nativeLyricsAvailable || (appleMusicId != null && appleMusicId > 0L && replacementReady)
