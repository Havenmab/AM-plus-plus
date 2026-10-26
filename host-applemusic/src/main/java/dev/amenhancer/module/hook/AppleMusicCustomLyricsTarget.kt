package dev.amenhancer.module.hook

import android.os.Handler
import android.os.Looper
import dev.amenhancer.module.config.TargetConfigClient
import dev.amenhancer.module.lyrics.CustomLyricsFilePolicy
import dev.amenhancer.module.lyrics.CustomLyricsFileReader
import dev.amenhancer.module.lyrics.online.TrackScopedDiagnostics
import dev.amenhancer.module.lyrics.source.LyricSourceProviders
import dev.amenhancer.module.model.CustomLyricsEntry
import java.util.concurrent.ArrayBlockingQueue
import java.util.concurrent.Executor
import java.util.concurrent.ThreadPoolExecutor
import java.util.concurrent.TimeUnit

/** Submits prepared user and automatic lyrics through the exact host result contract. */
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
        // The submitted replacement for the current request. Every source below
        // goes through this one callback, so the native result observer remains
        // the only installer instead of an out-of-band Fragment re-entry.
        lateinit var resultBridge: LyricsResultSubmissionBridge
        // Every publish path funnels through this lambda, so the lyrics-source
        // window hears exactly the document the page just received. It is assigned
        // together with the bridge below; until then it is a no-op, because no
        // replacement can be published before the bridge exists.
        var publishReplacement: (Long) -> Unit = { }
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
                        // The document being displayed comes from the custom index, so the
                        // entry's own source is the only truthful answer. No entry (or an
                        // unreadable manifest) means "unknown", never a guess.
                        val providerSource = runCatching {
                            config.customLyricsManifest().entries
                                .firstOrNull { it.appleMusicId == appleMusicId }
                                ?.source
                                ?.let(LyricSourceProviders::fromCustomLyricsSource)
                        }.getOrNull()
                        runtime.nativeLyricOverlay.publishDisplayed(
                            appleMusicId,
                            ttml,
                            originalSource = providerSource,
                        )
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
        // Hoisted so the lyrics-source sheet can queue re-parse work behind the
        // session's own index/entry tasks (single thread, FIFO).
        val customLyricsExecutor = ThreadPoolExecutor(
            1,
            1,
            0L,
            TimeUnit.MILLISECONDS,
            ArrayBlockingQueue(1),
            { runnable -> Thread(runnable, "ampp-custom-lyrics").apply { isDaemon = true } },
            ThreadPoolExecutor.AbortPolicy(),
        )
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
                        publishReplacement(appleMusicId)
                        if (currentSong.current()?.details?.appleMusicId == appleMusicId) {
                            refreshSession?.onSongChanged(appleMusicId)
                        }
                    }
                }
            },
            executor = customLyricsExecutor,
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
                    if (registration.isActive) publishReplacement(appleMusicId)
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
                            if (registration.isActive && !isCancelled()) publishReplacement(id)
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
                cache = runtime.cache,
                parseTtml = parser::parse,
                isAlive = parser::isAlive,
                verifyPtr = parser::isValid,
                readAdamId = parser::adamIdOf,
                bindAdamId = parser::bindAdamId,
                onReplacementPublished = { appleMusicId ->
                    mainHandler.post {
                        if (registration.isActive) {
                            publishReplacement(appleMusicId)
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
                        session.readyReplacementFor(appleMusicId) == null &&
                        // A source the user picked in the lyrics-source sheet owns
                        // the song: the automatic chain must not replace it.
                        !AutoLyricsReplacementBridge.isLocked(appleMusicId)
                },
                executor = runtime.executor,
                logger = ModernXposedRuntime::log,
                onFreshCandidate = { refreshSession?.markDownloaded(it) },
                // Only an exhausted online chain reaches the final LLM tier.
                onLookupMiss = { llmSession?.onSourcesMissed(it) },
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
                        id !in configuredManualIds && !session.isMapped(id) &&
                        session.readyReplacementFor(id) == null && autoSession?.readyReplacementFor(id) == null &&
                        // Same rule as the automatic tier: a picked source wins.
                        !AutoLyricsReplacementBridge.isLocked(id)
                },
                onReplacementPublished = { id ->
                    mainHandler.post {
                        if (registration.isActive) publishReplacement(id)
                    }
                },
            ).also { translation ->
                translation.onSongChanged(currentSong.current()?.details?.appleMusicId)
                registration.onClose(translation::close)
            }
        }
        // The sheet just applied a source for this song: tell the automatic session
        // so its in-flight result is dropped and the visible document stays the
        // user's. Registered by identity so a torn-down generation cannot clear a
        // later one's handler.
        autoSession?.let { automatic ->
            val takeover: (Long) -> Unit = { appleMusicId -> automatic.markTakeoverApplied(appleMusicId) }
            AutoLyricsReplacementBridge.installUserApplied(takeover)
            registration.onClose { AutoLyricsReplacementBridge.uninstallUserApplied(takeover) }
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
        val selectReplacement: (Long, Any?) -> Any? = { id, original ->
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
            val manual = session.replacementFor(id)
            val metadata = timingObservations.metadataOf(original)
            val eligible = shouldTryAutoLyrics(original, metadata)
            val automatic = if (manual == null) {
                if (eligible) autoSession?.replacementFor(id)
                else autoSession?.takeoverReplacementFor(id, parser.wrap(original), metadata)
            } else null
            if (manual == null && automatic != null) autoSession?.markTakeoverApplied(id)
            val llmEligible = eligible && metadata?.hasTranslation != true &&
                (metadata == null || metadata.language == null || metadata.isForeign)
            val translated = if (manual == null && automatic == null && llmEligible) {
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
            parser.unwrap(manual ?: automatic ?: translated)
        }
        resultBridge = LyricsResultSubmissionBridge.create(
            symbols = symbols,
            registration = registration,
            currentSong = currentSong,
            fragmentSongId = seam::currentItemAdamIdOf,
            selectReplacement = selectReplacement,
            isAlive = parser::isAlive,
            documentOf = parser::documentOf,
            logger = ModernXposedRuntime::log,
        ) ?: return TargetCapabilityInstall.Degraded("Lyric ViewModel result contract unavailable")
        registration.onClose(resultBridge::close)
        publishReplacement = { appleMusicId ->
            if (appleMusicId > 0L) {
                resultBridge.onReplacementPublished(appleMusicId)
                AutoLyricsReplacementBridge.notifyPublished?.invoke(appleMusicId)
            }
        }
        // The lyrics-source sheet's two "make it visible now" actions. They live here
        // because only this target holds the replacement session (which turns a
        // document into a native pointer) and the result bridge (which submits it);
        // they are published on the shared bridge instead of widening the factory
        // signature.
        val reapplyCustomLyrics: (Long) -> Unit = { appleMusicId ->
            if (appleMusicId > 0L && registration.isActive) {
                runCatching {
                    customLyricsExecutor.execute {
                        // Re-read the manifest: the sheet just wrote that entry, so a
                        // cached one would describe the previous document.
                        val entry = runCatching {
                            config.customLyricsManifest().entries.firstOrNull { candidate ->
                                candidate.appleMusicId == appleMusicId && candidate.enabled
                            }
                        }.getOrNull()
                        if (entry == null) {
                            mainHandler.post { if (registration.isActive) publishReplacement(appleMusicId) }
                            return@execute
                        }
                        session.refreshEntry(entry, { false }, Executor { it.run() }) { published ->
                            mainHandler.post { if (registration.isActive) publishReplacement(published) }
                        }
                    }
                }.onFailure { error ->
                    ModernXposedRuntime.log("lyrics-source reapply was rejected: " + error.message)
                }
            }
        }
        val applyAppleOriginal: (Long) -> Unit = { appleMusicId ->
            if (appleMusicId > 0L && registration.isActive) {
                // Deleting the index entry does not uninstall the pointer the page is
                // already showing: refresh the index cache (which drops the entry),
                // then let the selector fall through to Apple's own document. Two
                // wake-ups because both the refresh and the selector are async, and
                // the page has to be back within a second.
                session.start()
                nativeLyricDelivery?.onCustomOverlayUpdated(appleMusicId)
                // Three wake-ups with growing spacing: the index refresh and the
                // selector are both async, and the page must be back on Apple within a
                // second or two even on a busy main thread. Every wake-up logs its
                // result so a device log shows which one actually landed.
                val wakes = longArrayOf(120L, 450L, 1200L)
                wakes.forEach { delayMs ->
                    mainHandler.postDelayed(
                        {
                            if (registration.isActive) {
                                publishReplacement(appleMusicId)
                                ModernXposedRuntime.log(
                                    "lyrics-source restore id=" + appleMusicId +
                                        " wake=" + delayMs + "ms published=true",
                                )
                            }
                        },
                        delayMs,
                    )
                }
            }
        }
        AutoLyricsReplacementBridge.installReapplyCustomLyrics(reapplyCustomLyrics)
        AutoLyricsReplacementBridge.installApplyAppleOriginal(applyAppleOriginal)
        registration.onClose {
            AutoLyricsReplacementBridge.uninstallReapplyCustomLyrics(reapplyCustomLyrics)
            AutoLyricsReplacementBridge.uninstallApplyAppleOriginal(applyAppleOriginal)
        }
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
                        val moduleParse =
                            parser.isModuleParse() || NativeTtmlParseOrigin.isModuleInitiated()
                        // The module's own parses never describe what Apple is
                        // displaying: recording them would let our generated
                        // document evict or masquerade as the native one.
                        if (moduleParse) return@runCatching
                        val pointer = param.result
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
        }.getOrDefault(false)
        // Observe installation solely to attach the exact host ViewModel. Selection
        // and publication happen upstream, before the native observer enters here.
        // The same seam also feeds the native model delivery: the pointer that is
        // about to be shown installs the per-line/availability hooks, and the
        // fragment+pointer binding is what a late lane's refresh re-presents.
        val hooked = hook(installMethod, object : ModernMethodHook() {
            override fun beforeHookedMethod(param: MethodHookParam) {
                runCatching {
                    val fragment = param.thisObject
                    val pointer = param.args.getOrNull(0)
                    nativeLyricDelivery?.onLyricsPointer(pointer)
                    nativeLyricDelivery?.onLyricsPresentation(fragment, pointer)
                }.onFailure { error ->
                    ModernXposedRuntime.log("custom lyrics native delivery binding failed: $error")
                }
                param.thisObject?.let(resultBridge::attach)
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
        }.getOrDefault(false)
        session.start()
        val identitySubscription = currentSong.addListener { current ->
            val appleMusicId = current?.details?.appleMusicId
            appleMusicId?.let(session::ensureRequested)
            autoSession?.onSongChanged(appleMusicId)
            llmSession?.onSongChanged(appleMusicId)
            resultBridge.onSongChanged(appleMusicId)
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
