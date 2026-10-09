package dev.amenhancer.module.hook

import android.os.Handler
import android.os.Looper
import dev.amenhancer.module.lyrics.online.ApplePronunciationVisibilityPolicy
import dev.amenhancer.module.lyrics.online.NativeLyricModelPolicy
import dev.amenhancer.module.lyrics.online.NativeLyricOverlayStore
import dev.amenhancer.module.lyrics.online.OnlineTranslationContentPolicy
import dev.amenhancer.module.lyrics.online.RomanizationPolicy
import dev.amenhancer.module.lyrics.online.TrackScopedDiagnostics
import io.github.proify.lyricon.amprovider.xposed.AppleMusicHookPoint
import io.github.proify.lyricon.amprovider.xposed.AppleMusicHookProfiles
import io.github.proify.lyricon.amprovider.xposed.AppleMusicHookResolver
import io.github.proify.lyricon.amprovider.xposed.AppleMusicRuntimeMember
import io.github.proify.lyricon.amprovider.xposed.AppleReflection
import io.github.proify.lyricon.amprovider.xposed.expandAppleLyricsPronunciationLanguages
import io.github.proify.lyricon.amprovider.xposed.expandAppleLyricsTranslationLanguages
import java.lang.reflect.Method
import java.util.concurrent.ConcurrentHashMap

/**
 * HLE's model-level delivery for translation/pronunciation, ported onto the
 * fork's hook runtime.
 *
 * The document lane edits Apple's own TTML head, but the app does not render an
 * injected `<transliterations>` track on Apple Music 7.0.0-beta (1606):
 * device-confirmed, the log shows `publish … pronunciationSource=QM
 * pronunciationLines=34` and `inject … published=true`, yet the screen shows no
 * romanization, while HLE shows it on the same device. HLE never relies on the
 * document for this: it asks the app's own lyric model.
 *
 * This installer reproduces HLE's behaviour with the fork's mechanism:
 *
 *  - `PlayerLyricsViewModel.buildTimeRangeToLyricsMap(SongInfoPtr)` is the
 *    native-model seam. Before it runs, the pointer is dereferenced to the
 *    `SongInfoNative` model and the per-class lyric hooks are installed, exactly
 *    as HLE does from `LYRICS_VIEW_MODEL_BUILD`.
 *  - Each line's `getHtmlPronunciationLineText` / `getHtmlTranslationLineText`
 *    is overridden to return the timing-keyed online lane only when Apple's own
 *    value is absent, so Apple's data always wins (HLE's `selectAppleLyricsText`
 *    with the official value first).
 *  - The song's `hasPronunciation` / `hasTranslation` (and their `set*`
 *    siblings) advertise the online lanes, so the UI considers the content
 *    available; the Mandarin rule still returns false for a hidden song.
 *  - The preferred-language request arrays are expanded with
 *    `ja-Latn`/`ko-Latn`/`zh-Latn` (HLE's
 *    `hookAppleLyricsPreferredLanguages`), and the official pronunciation
 *    language match returns the third-party fallback language (system language
 *    when it is a Latin tag, else `und-Latn`).
 *  - `setPronunciation` mirrors HLE's `applyAppleNativePronunciationSelection`:
 *    it is handed Apple's own advertised Latin language whenever the song offers
 *    one, else the legitimate third-party fallback (`thirdPartyPronunciationFallbackLanguage`).
 *    An empty/absent `getPronunciationLanguages` vector is not "select nothing":
 *    HLE falls through to the fallback, and leaving it unselected is what
 *    suppressed third-party-only songs (device log: `languages= …
 *    selectedLanguage=none deferred=true` while the overlay published
 *    `pronunciationSource=NE pronunciationLines=31`). The choice is deliberately
 *    not gated on the per-line `getHtmlPronunciationLineText` probe as HLE gates
 *    it: on 1606 that getter stays empty until a pronunciation language has
 *    already been selected, so the strict mirror always took the fallback and
 *    overwrote Apple's lane (device log: `languages=ja-Latn
 *    officialPronunciation=false` becoming `und-Latn`). The HLE probe is still
 *    evaluated, per call as HLE does, for the availability override and the
 *    diagnostic.
 *  - The selection is open and self-correcting. A transient empty read keeps the
 *    last non-empty Apple advertisement, so the third-party tag is only selected
 *    while Apple's lane is genuinely unknown; every later entry point — a hook on
 *    the song's `getPronunciationLanguages`, the availability overrides, the
 *    pronunciation line getter and the preferred-language request — re-runs the
 *    decision, so Apple's own lane replaces the fallback as soon as Apple
 *    advertises it and third-party-only songs still get the fallback selected.
 *    The `native-write` line carries `appleLanguagesKnown=`, `selection=` and
 *    `reason=` so the next device log proves what was chosen and why.
 *  - HLE's `shouldRefreshPresentationAfterBuild` is evaluated from an `after`
 *    hook on the same build seam. On 1606 the model is often built before Apple
 *    advertises its pronunciation lane and before the online overlay is
 *    published; nothing re-reads the model afterwards, so the page keeps its
 *    first render until it is re-created. When the gate accepts, the app's own
 *    result-presentation method (`AppleMusicSymbols.LyricsInstallMethod` — the
 *    profile's `lyrics-install-method` contract, `PlayerLyricsViewFragment#w2`
 *    on 1606, exactly HLE's `LYRICS_RESULT_PRESENTATION`) is re-invoked on the
 *    main handler for the bound fragment and pointer, so the adapter rebinds
 *    against the updated model. The `presentation-refresh` line records the
 *    decision and the outcome.
 *
 * Every step fails open: an unresolved profile target, a missing member name, a
 * malformed vector or a throwing getter leaves Apple's own value in place. The
 * member names come from the exact 1606 profile's `LYRICS_VIEW_MODEL_LOAD`
 * `runtimeMemberNames` dictionary ([AppleMusicHookProfiles]); a profile without
 * it (for example 6.5.2/6.5.3) makes this installer a no-op.
 */
internal class AppleNativeLyricModelHooks(
    private val resolver: AppleMusicHookResolver,
    private val overlay: NativeLyricOverlayStore,
    private val enabled: Boolean,
    private val hideMandarinPinyin: Boolean,
    private val genreFor: (Long) -> String?,
    private val scope: HookRegistrationScope? = null,
    private val log: (String) -> Unit = { ModernXposedRuntime.log(it) },
    /**
     * The app's lyric-result presentation method, resolved from
     * `AppleMusicSymbols.LyricsInstallMethod` (the 1606 profile pins it to
     * `PlayerLyricsViewFragment#w2(SongInfo$SongInfoPtr)`). Invoked with the
     * bound fragment and pointer to force the adapter to rebind after the model
     * changes. Null leaves the refresh a no-op.
     */
    private val presentationMethod: Method? = null,
    /**
     * True when the current song's displayed lyrics are the module's own
     * supplement document. That path has its own re-presentation, and HLE
     * explains Apple's R2 track refresh makes the lyrics page twitch, so the
     * build gate must not request a second refresh for it.
     */
    private val isModuleSupplementSong: (Long) -> Boolean = { false },
) {
    private val diagnostic = TrackScopedDiagnostics(log, MAX_DIAGNOSTIC_LINES)
    private val installed = ConcurrentHashMap.newKeySet<String>()
    private val rawRead = ThreadLocal<Boolean>()

    /** The exact profile's member-name dictionary; empty means "not pinned here". */
    private val nativeNames: Map<AppleMusicRuntimeMember, String> =
        AppleMusicHookProfiles
            .exactTargets(resolver.version, AppleMusicHookPoint.LYRICS_VIEW_MODEL_LOAD)
            .firstOrNull()
            ?.runtimeMemberNames
            .orEmpty()

    @Volatile
    private var modelSongId: Long = 0L

    @Volatile
    private var systemLyricsLanguage: String? = null

    @Volatile
    private var applePronunciationLanguages: List<String> = emptyList()

    @Volatile
    private var mandarinHidden: Boolean = false

    /** The song object the last successful `setPronunciation` was applied to. */
    @Volatile
    private var selectedPronunciationSong: java.lang.ref.WeakReference<Any>? = null

    @Volatile
    private var selectedPronunciationLanguage: String? = null

    /**
     * True while the current song's pronunciation selection could still change:
     * nothing has been selected yet (no Apple lane and no legitimate fallback at
     * the last pass, e.g. the overlay had not been published), or the
     * third-party tag is standing in for an Apple lane that may still be
     * advertised. Every later entry point re-runs the selection while this is
     * true, so a fallback is always superseded by Apple's own lane and an
     * unselected third-party-only song is retried once its overlay exists.
     */
    @Volatile
    private var pronunciationSelectionOpen: Boolean = false

    /** The song whose model the current hooks were installed for. */
    @Volatile
    private var songNativeRef: java.lang.ref.WeakReference<Any>? = null

    /** Guards our own `getPronunciationLanguages` reads from the query hook. */
    private val pronunciationQueryGuard = ThreadLocal<Boolean>()

    /** Guards `setPronunciation` re-entry through the availability override. */
    private val pronunciationSelectionGuard = ThreadLocal<Boolean>()

    /** Main-thread posting for the presentation refresh, exactly as HLE does. */
    private val mainHandler = Handler(Looper.getMainLooper())

    /** The fragment of the last lyric-result presentation, or null before it. */
    @Volatile
    private var presentationFragmentRef: java.lang.ref.WeakReference<Any>? = null

    /** The `SongInfoPtr` of the last lyric-result presentation. */
    @Volatile
    private var presentationPointerRef: java.lang.ref.WeakReference<Any>? = null

    /**
     * Guards our own `presentationMethod.invoke` so the presentation hook does
     * not treat the refresh as a fresh Apple presentation (which would re-arm
     * the binding and could re-enter the refresh).
     */
    private val presentationInvokeGuard = ThreadLocal<Boolean>()

    /**
     * The last (song, overlay revision, official lane) the build gate decided.
     * A repeated build with the same state is never refreshed twice, which is
     * what stops the refresh from looping through our own build hook: the
     * re-presentation rebuilds the model, the gate holds again, and the recorded
     * state makes the second pass a no-op.
     */
    @Volatile
    private var lastPresentationRefreshState: PresentationRefreshState? = null

    /**
     * One accepted refresh decision. The whole gate input set is part of the
     * key, so any signal that can change after a build — a new overlay revision,
     * Apple advertising its lane, the supplement pointer resolving to Apple's
     * document, the Mandarin rule — is a new state, while an unchanged re-build
     * is not.
     */
    private data class PresentationRefreshState(
        val songId: Long,
        val overlayRevision: Long,
        val sourceIsApple: Boolean,
        val officialPronunciation: Boolean,
        val officialLane: String?,
        val onlineTranslation: Boolean,
        val onlinePronunciation: Boolean,
        val pronunciationSelected: Boolean,
    )

    fun install() {
        if (!enabled) {
            log("online-translation native-write skipped: translation/pronunciation is off")
            return
        }
        if (nativeNames.isEmpty()) {
            log(
                "online-translation native-write unavailable: " +
                    "${resolver.version.displayName} pins no LYRICS_VIEW_MODEL_LOAD members",
            )
            return
        }
        installNativeModelSeam()
        installPreferredLanguageExpansion()
        installPronunciationLanguageMatch()
    }

    /**
     * HLE hooks `LYRICS_VIEW_MODEL_BUILD` because its single argument is the
     * `SongInfoPtr` that is about to become the model the UI reads. Nothing is
     * changed when the profile cannot resolve the seam.
     */
    private fun installNativeModelSeam() {
        val method = runCatching {
            resolver.resolveMethod(AppleMusicHookPoint.LYRICS_VIEW_MODEL_BUILD).method
        }.getOrNull() ?: run {
            log("online-translation native-write seam unavailable on ${resolver.version.displayName}")
            return
        }
        ModernXposedRuntime.hookMethod(method, object : ModernMethodHook() {
            override fun beforeHookedMethod(param: MethodHookParam) {
                runCatching {
                    val pointer = param.args.getOrNull(0) ?: return@runCatching
                    val songNative = pointerGet(pointer) ?: return@runCatching
                    ensureNativeModel(songNative, param.thisObject)
                }.onFailure { error ->
                    log("online-translation native-write ensure failed: ${error.message}")
                }
            }

            /**
             * HLE evaluates its presentation-refresh gate from the build seam's
             * `after`: the model now exists, so a lane that arrived late can be
             * made visible by re-presenting. `ensureNativeModel` already ran in
             * `before`, so this only reads the settled state.
             */
            override fun afterHookedMethod(param: MethodHookParam) {
                runCatching {
                    val pointer = param.args.getOrNull(0) ?: return@runCatching
                    val songNative = pointerGet(pointer) ?: return@runCatching
                    requestPresentationRefreshAfterBuild(songNative)
                }.onFailure { error ->
                    log("online-translation presentation-refresh failed: ${error.message}")
                }
            }
        }, scope)
    }

    /**
     * Records the fragment and pointer Apple is presenting. HLE keeps the same
     * binding from its result-presentation hook and only re-invokes the
     * presentation while both are bound; the refresh is a no-op when the lyrics
     * view is absent. Our own refresh's invoke is ignored, so the binding always
     * belongs to Apple's presentation.
     */
    fun onLyricsPresentation(fragment: Any?, pointer: Any?) {
        if (!enabled || nativeNames.isEmpty()) return
        if (fragment == null || pointer == null) return
        if (presentationInvokeGuard.get() == true) return
        presentationFragmentRef = java.lang.ref.WeakReference(fragment)
        presentationPointerRef = java.lang.ref.WeakReference(pointer)
    }

    /**
     * The second trigger: Apple's lyric-result presentation (`I2`/`w2`) receives
     * the `SongInfoPtr` that is becoming visible. HLE installs its model hooks
     * there too, so the delivery still happens when the view-model build seam is
     * not the path that carries the pointer.
     */
    fun onLyricsPointer(pointer: Any?) {
        if (!enabled || nativeNames.isEmpty()) return
        runCatching {
            val songNative = pointerGet(pointer) ?: return@runCatching
            ensureNativeModel(songNative, viewModel = null)
        }.onFailure { error ->
            log("online-translation native-write pointer ensure failed: ${error.message}")
        }
    }

    /**
     * HLE's build-after decision: evaluate
     * `NativeLyricModelPolicy.shouldRefreshPresentationAfterBuild` for the model
     * that was just built and, when it accepts, re-present on the main handler.
     *
     * The inputs are the fork's truthful signals:
     *  - `sourceIsApple` is false for the module's own supplement document, which
     *    re-presents itself;
     *  - an online lane is the overlay published for this track, gated by the
     *    same `enabled` switch the availability overrides use;
     *  - the official pronunciation is the per-call line probe *or* a lane Apple
     *    itself advertises, exactly as the availability override resolves it (on
     *    1606 the probe is empty until a language was selected);
     *  - `pronunciationSelected` is the fork's translation/pronunciation switch
     *    with the Mandarin rule, the closest mapping to HLE's pronunciation
     *    preference;
     *  - a state already refreshed is skipped, which is the anti-thrash guard;
     *    the invoke guard additionally keeps the build our own re-presentation
     *    triggers from re-entering the gate (and the state dedupe covers a build
     *    dispatched to another thread).
     */
    private fun requestPresentationRefreshAfterBuild(songNative: Any) {
        // Never evaluate the gate from the build our own re-presentation
        // triggers: the invoke guard is the hard recursion stop, and the state
        // dedupe below covers a build that is dispatched to another thread.
        if (presentationInvokeGuard.get() == true) return
        val songId = nativeSongId(songNative)
        if (songId <= 0L) return

        val advertised = advertisedPronunciationLanguages(songPronunciationLanguages(songNative))
        val officialLane = NativeLyricModelPolicy.officialPronunciationLanguage(advertised)
        val officialPronunciation =
            hasValidOfficialPronunciation(songNative) || officialLane != null
        val onlineTranslation = enabled && overlay.hasTranslation(songId.toString())
        val onlinePronunciation = enabled && overlay.hasPronunciation(songId.toString())
        val pronunciationSelected = enabled && !mandarinHidden
        val sourceIsApple = !isModuleSupplementSong(songId)

        val reason = NativeLyricModelPolicy.presentationRefreshReason(
            sourceIsApple = sourceIsApple,
            hasValidOfficialPronunciation = officialPronunciation,
            hasOnlineTranslation = onlineTranslation,
            hasOnlinePronunciation = onlinePronunciation,
            pronunciationSelected = pronunciationSelected,
        )
        val shouldRefresh = NativeLyricModelPolicy.shouldRefreshPresentationAfterBuild(
            sourceIsApple = sourceIsApple,
            hasValidOfficialPronunciation = officialPronunciation,
            hasOnlineTranslation = onlineTranslation,
            hasOnlinePronunciation = onlinePronunciation,
            pronunciationSelected = pronunciationSelected,
        )

        val state = PresentationRefreshState(
            songId = songId,
            overlayRevision = overlay.revision(),
            sourceIsApple = sourceIsApple,
            officialPronunciation = officialPronunciation,
            officialLane = officialLane,
            onlineTranslation = onlineTranslation,
            onlinePronunciation = onlinePronunciation,
            pronunciationSelected = pronunciationSelected,
        )
        // An unchanged state has already been decided (and, when accepted,
        // refreshed). This is what keeps the re-presentation's own build from
        // looping: the gate holds again but the state matches.
        if (state == lastPresentationRefreshState) return
        lastPresentationRefreshState = state

        val detail = when {
            !sourceIsApple -> "supplement"
            !shouldRefresh -> "gate"
            else -> "requested"
        }
        logPresentationRefresh(
            songId = songId,
            reason = reason,
            hasValidOfficialPronunciation = officialPronunciation,
            onlineTranslation = onlineTranslation,
            onlinePronunciation = onlinePronunciation,
            pronunciationSelected = pronunciationSelected,
            refreshed = false,
            detail = detail,
        )
        if (!shouldRefresh) return

        mainHandler.post {
            performPresentationRefresh(
                state = state,
                reason = reason,
                hasValidOfficialPronunciation = officialPronunciation,
                onlineTranslation = onlineTranslation,
                onlinePronunciation = onlinePronunciation,
                pronunciationSelected = pronunciationSelected,
            )
        }
    }

    /**
     * The main-handler half of HLE's `refreshAppleLyricsSupplementPresentation`:
     * resolve the bound fragment and pointer, verify the pointer still belongs to
     * the expected song, re-run the selection so the getters read the settled
     * model, and re-invoke the app's presentation method. Every early return
     * clears the recorded state so a later binding can retry.
     */
    private fun performPresentationRefresh(
        state: PresentationRefreshState,
        reason: String,
        hasValidOfficialPronunciation: Boolean,
        onlineTranslation: Boolean,
        onlinePronunciation: Boolean,
        pronunciationSelected: Boolean,
    ) {
        fun finish(refreshed: Boolean, detail: String) {
            if (!refreshed && lastPresentationRefreshState == state) {
                lastPresentationRefreshState = null
            }
            logPresentationRefresh(
                songId = state.songId,
                reason = reason,
                hasValidOfficialPronunciation = hasValidOfficialPronunciation,
                onlineTranslation = onlineTranslation,
                onlinePronunciation = onlinePronunciation,
                pronunciationSelected = pronunciationSelected,
                refreshed = refreshed,
                detail = detail,
            )
        }

        val method = presentationMethod ?: return finish(false, "no-presentation-method")
        val fragment = presentationFragmentRef?.get() ?: return finish(false, "not-bound")
        val pointer = presentationPointerRef?.get() ?: return finish(false, "not-bound")
        val songNative = pointerGet(pointer) ?: return finish(false, "pointer-dead")
        if (nativeSongId(songNative) != state.songId) return finish(false, "song-changed")

        // HLE re-runs `applyAppleNativeSupplementSelection` before re-presenting.
        // Ours is the pronunciation half; the per-line getters already read the
        // overlay live, so only the language selection can still be settled here.
        runCatching {
            applyAppleNativePronunciationSelection(songNative, songPronunciationLanguages(songNative))
        }.onFailure { error ->
            log("online-translation presentation-refresh selection failed: ${error.message}")
        }

        val outcome = runCatching {
            presentationInvokeGuard.set(true)
            try {
                method.invoke(fragment, pointer)
            } finally {
                presentationInvokeGuard.remove()
            }
        }
        outcome
            .onSuccess { finish(true, "invoked") }
            .onFailure { error ->
                log("online-translation presentation-refresh invoke failed: ${error.message}")
                finish(false, "invoke-failed")
            }
    }

    /**
     * The device-facing proof of the refresh decision, on the same visible
     * channel as `native-write`: the exact gate inputs, the branch that fired and
     * whether the re-presentation actually ran. A `refreshed=true` line on the
     * first play is the evidence the fix landed.
     */
    private fun logPresentationRefresh(
        songId: Long,
        reason: String,
        hasValidOfficialPronunciation: Boolean,
        onlineTranslation: Boolean,
        onlinePronunciation: Boolean,
        pronunciationSelected: Boolean,
        refreshed: Boolean,
        detail: String,
    ) {
        log(
            "online-translation presentation-refresh id=$songId reason=$reason " +
                "officialPronunciation=$hasValidOfficialPronunciation " +
                "onlineTranslation=$onlineTranslation " +
                "onlinePronunciation=$onlinePronunciation " +
                "pronunciationSelected=$pronunciationSelected " +
                "presentationMethod=${presentationMethod != null} " +
                "refreshed=$refreshed detail=$detail",
        )
    }

    private fun ensureNativeModel(songNative: Any, viewModel: Any?) {
        val songId = nativeSongId(songNative)
        if (songId != modelSongId) {
            // A new track: never let the previous song's lane, selection or
            // open selection leak into this one.
            applePronunciationLanguages = emptyList()
            selectedPronunciationSong = null
            selectedPronunciationLanguage = null
            pronunciationSelectionOpen = false
        }
        modelSongId = songId
        songNativeRef = java.lang.ref.WeakReference(songNative)
        if (viewModel != null) {
            systemLyricsLanguage = call(
                viewModel,
                AppleMusicRuntimeMember.LYRICS_VIEW_MODEL_CURRENT_LANGUAGE_METHOD,
            ) as? String
        }
        val languages = songPronunciationLanguages(songNative)
        mandarinHidden = isMandarinHidden(languages)

        installSongAvailabilityHooks(songNative.javaClass)
        installPronunciationLanguageQueryHook(songNative.javaClass)
        val lines = nativeLines(songNative)
        // HLE-compatible per-call probe as the build-time reading, for the
        // diagnostic and for the "did Apple populate the lines yet" signal. It is
        // not the decision input: on 1606 it is false until a language is
        // selected, which is exactly why the selection may not gate Apple's own
        // lane behind it.
        val officialAtBuild = hasValidOfficialPronunciation(songNative)
        lines.map { it.javaClass }.distinct().forEach(::installLineTextHooks)
        val selection = applyAppleNativePronunciationSelection(songNative, languages)
        reportNativeWrite(
            lines = lines,
            officialAtBuild = officialAtBuild,
            officialNow = hasValidOfficialPronunciation(songNative),
            languages = languages,
            selection = selection,
        )
    }

    /**
     * HLE's `hasValidOfficialRomanization`, evaluated on every call the way HLE
     * evaluates it (from the availability override and from the selection) and
     * never once at BUILD time. On 1606 Apple fills
     * `getHtmlPronunciationLineText` only after a pronunciation language has been
     * selected, so the build-time reading is always false: the device log's
     * `officialPronunciation=false ×100` sits beside Apple's own
     * `languages=ja-Latn`. Reading it per call lets Apple's value become visible
     * as soon as the app has selected a track. Raw reads bypass our own getter, so
     * the online lane can never masquerade as Apple's.
     */
    private fun hasValidOfficialPronunciation(songNative: Any?): Boolean {
        if (songNative == null) return false
        if (isMandarinHidden(songPronunciationLanguages(songNative))) return false
        return nativeLines(songNative).any { line ->
            val text = rawLineText(line)
            val pronunciation = withRawRead {
                call(line, AppleMusicRuntimeMember.LYRICS_NATIVE_PRONUNCIATION_TEXT_METHOD) as? String
            }
            !text.isNullOrBlank() &&
                RomanizationPolicy.sanitize(text, pronunciation) != null
        }
    }

    /**
     * Apple's advertised pronunciation languages; updates the per-song cache when
     * the read belongs to the model currently being delivered (a preloaded next
     * track is queried on the same class, and its lane must not become this
     * model's remembered advertisement).
     */
    private fun songPronunciationLanguages(songNative: Any?): List<String> {
        // Read under the guard: this is our own call, so the query hook must not
        // re-enter the selection from under us (we run it explicitly after).
        val languages = withPronunciationQueryGuard {
            vectorStrings(
                call(
                    songNative,
                    AppleMusicRuntimeMember.LYRICS_NATIVE_SONG_PRONUNCIATION_LANGUAGES_METHOD,
                ),
            )
        }
        if (languages.isNotEmpty() && isCurrentSong(songNative)) {
            applePronunciationLanguages = languages
        }
        return languages
    }

    /**
     * Apple's advertisement of its own pronunciation lane is itself the signal
     * that an open selection can settle: when the app asks
     * `getPronunciationLanguages` and gets a non-empty vector, the selection runs
     * again so Apple's own lane wins over the third-party tag, exactly as HLE's
     * per-call selection would.
     */
    private fun installPronunciationLanguageQueryHook(clazz: Class<*>) {
        val name = nativeNames[AppleMusicRuntimeMember.LYRICS_NATIVE_SONG_PRONUNCIATION_LANGUAGES_METHOD]
            ?: return
        val method = AppleReflection.findMethodOrNull(clazz, name, parameterCount = 0) ?: return
        if (!installed.add("query:${method.declaringClass.name}#${method.name}")) return
        ModernXposedRuntime.hookMethod(method, object : ModernMethodHook() {
            override fun afterHookedMethod(param: MethodHookParam) {
                if (pronunciationQueryGuard.get() == true) return
                val song = param.thisObject ?: return
                runCatching {
                    val languages = vectorStrings(param.result)
                    if (languages.isEmpty()) return@runCatching
                    // A preloaded next track may be queried on the same class:
                    // never let its lane or selection leak into the current model.
                    val songId = nativeSongId(song)
                    if (songId > 0L && songId != modelSongId) return@runCatching
                    applePronunciationLanguages = languages
                    applyAppleNativePronunciationSelection(song, languages)
                }.onFailure { error ->
                    log(
                        "online-translation native-write language re-select failed: " +
                            "${error.message}",
                    )
                }
            }
        }, scope)
    }

    /**
     * Re-runs the pronunciation selection from a later entry point while the
     * decision is still [pronunciationSelectionOpen]. [languages] is the
     * advertisement the caller already read, or null to read it here. Called
     * from the availability overrides, the pronunciation line getter and the
     * preferred-language request so the build-time pass can never be final by
     * itself: a fallback that only just became available is selected, and a
     * fallback already standing in is replaced the moment Apple advertises.
     */
    private fun refreshPronunciationSelectionIfOpen(
        song: Any? = null,
        languages: List<String>? = null,
    ) {
        if (!pronunciationSelectionOpen) return
        val target = song ?: songNativeRef?.get() ?: return
        // Never let a preloaded next track's class instance carry this model's
        // selection; the same guard the language-query hook uses.
        if (!isCurrentSong(target)) return
        runCatching {
            applyAppleNativePronunciationSelection(
                target,
                languages ?: songPronunciationLanguages(target),
            )
        }.onFailure { error ->
            log("online-translation native-write pronunciation re-select failed: ${error.message}")
        }
    }

    /** True when [songNative] is the model currently being delivered. */
    private fun isCurrentSong(songNative: Any?): Boolean {
        if (songNative == null) return false
        val songId = nativeSongId(songNative)
        return songId <= 0L || songId == modelSongId
    }

    /**
     * Apple's best-known advertisement: the live read when non-empty, else the
     * last non-empty one, so a transient empty read cannot withdraw Apple's own
     * lane (#9's invariant) nor hand the third-party tag over it.
     */
    private fun advertisedPronunciationLanguages(languages: List<String>): List<String> =
        NativeLyricModelPolicy.advertisedPronunciationLanguages(
            live = languages,
            remembered = applePronunciationLanguages,
        )

    private fun isMandarinHidden(languages: List<String>): Boolean =
        ApplePronunciationVisibilityPolicy.shouldHide(
            genre = genreFor(modelSongId).orEmpty().takeIf(String::isNotBlank),
            pronunciationLanguages = languages,
            hideMandarinPinyin = hideMandarinPinyin,
        )

    private fun installSongAvailabilityHooks(clazz: Class<*>) {
        listOf(
            AppleMusicRuntimeMember.LYRICS_NATIVE_SET_TRANSLATION_METHOD,
            AppleMusicRuntimeMember.LYRICS_NATIVE_HAS_TRANSLATION_METHOD,
        ).forEach { member ->
            installBooleanAvailability(clazz, member) { _, original ->
                NativeLyricModelPolicy.hasTranslationAvailability(
                    original = original,
                    enabled = enabled,
                    hasOnlineTranslation = enabled && overlay.hasTranslation(currentSongId()),
                )
            }
        }
        listOf(
            AppleMusicRuntimeMember.LYRICS_NATIVE_SET_PRONUNCIATION_METHOD,
            AppleMusicRuntimeMember.LYRICS_NATIVE_HAS_PRONUNCIATION_METHOD,
        ).forEach { member ->
            installBooleanAvailability(clazz, member) { song, original ->
                // Per call, exactly as HLE re-runs `hasValidOfficialRomanization`
                // from this override.
                val languages = songPronunciationLanguages(song)
                // This override is a later entry point: re-run the selection
                // while it is still open, so a lane that arrived after the build
                // seam, or a fallback that only just became available, is
                // selected here rather than trusting the stale pass.
                refreshPronunciationSelectionIfOpen(song, languages)
                // Apple's advertised Latin lane is itself proof of an official
                // pronunciation, so availability never withdraws Apple's own
                // value while the per-line probe is still empty. Keep the last
                // non-empty advertisement so a transient empty read cannot
                // withdraw it either.
                val advertised = advertisedPronunciationLanguages(languages)
                NativeLyricModelPolicy.hasPronunciationAvailability(
                    original = original,
                    enabled = enabled,
                    hasOnlinePronunciation = enabled && overlay.hasPronunciation(currentSongId()),
                    hasValidOfficialPronunciation = hasValidOfficialPronunciation(song) ||
                        NativeLyricModelPolicy.officialPronunciationLanguage(advertised) != null,
                    mandarinHidden = isMandarinHidden(languages),
                )
            }
        }
    }

    /**
     * HLE's `applyAppleNativePronunciationSelection`: hand the app's own setter
     * Apple's advertised Latin language when the song offers one, and otherwise
     * the legitimate third-party fallback — including when Apple's vector is
     * empty, exactly as HLE's `?: thirdPartyPronunciationFallbackLanguage()`
     * does. Only when there is no lane and no fallback is `setPronunciation` left
     * untouched. The plan is recorded in [pronunciationSelectionOpen]: while it is
     * not Apple's own lane, every later entry point calls this again, so the
     * fallback is replaced the moment Apple advertises. A transient empty read
     * never withdraws a lane Apple already advertised ([advertisedPronunciationLanguages]).
     * Skipped entirely while the Mandarin rule hides the song. Returns the plan,
     * for the diagnostic.
     */
    private fun applyAppleNativePronunciationSelection(
        songNative: Any,
        languages: List<String>,
    ): NativeLyricModelPolicy.PronunciationSelectionPlan {
        val advertised = advertisedPronunciationLanguages(languages)
        if (mandarinHidden) {
            pronunciationSelectionOpen = false
            return NativeLyricModelPolicy.hiddenPronunciationSelection(
                appleLanguagesKnown = advertised.isNotEmpty(),
            )
        }
        val plan = NativeLyricModelPolicy.planPronunciationSelection(
            appleLanguages = advertised,
            thirdPartyFallbackLanguage = fallbackLanguage(advertised),
        )
        // Only Apple's own lane is final; otherwise a later entry point must be
        // free to select the fallback or let Apple supersede it.
        pronunciationSelectionOpen =
            plan.selection != NativeLyricModelPolicy.PronunciationSelection.APPLE
        val language = plan.language ?: return plan
        if (
            language == selectedPronunciationLanguage &&
            selectedPronunciationSong?.get() === songNative
        ) {
            return plan
        }
        if (pronunciationSelectionGuard.get() == true) return plan
        val name = nativeNames[AppleMusicRuntimeMember.LYRICS_NATIVE_SET_PRONUNCIATION_METHOD]
            ?: return plan
        // setPronunciation re-enters through the availability override; the guard
        // keeps that from recursing back into this method.
        pronunciationSelectionGuard.set(true)
        try {
            runCatching { AppleReflection.call(songNative, name, language) }
                .onSuccess {
                    selectedPronunciationSong = java.lang.ref.WeakReference(songNative)
                    selectedPronunciationLanguage = language
                }
                .onFailure { error ->
                    log("online-translation native-write selectPronunciation failed: ${error.message}")
                }
        } finally {
            pronunciationSelectionGuard.remove()
        }
        return plan
    }

    private fun installLineTextHooks(clazz: Class<*>) {
        installLineGetter(
            clazz = clazz,
            member = AppleMusicRuntimeMember.LYRICS_NATIVE_TRANSLATION_TEXT_METHOD,
        ) { line, original ->
            OnlineTranslationContentPolicy.sanitize(original) ?: onlineTranslation(line)
        }
        installLineGetter(
            clazz = clazz,
            member = AppleMusicRuntimeMember.LYRICS_NATIVE_PRONUNCIATION_TEXT_METHOD,
        ) { line, original ->
            if (mandarinHidden) {
                ""
            } else {
                // The line getter is a later entry point: retry an open
                // selection here so a fallback becomes effective on the very
                // render that needs it, and Apple's lane supersedes it later.
                refreshPronunciationSelectionIfOpen()
                val text = rawLineText(line)
                RomanizationPolicy.sanitize(text, original) ?: onlinePronunciation(line, text)
            }
        }
    }

    /**
     * Installs a zero-argument String getter override. [resolve] returns the
     * replacement, or null to keep the method's own result; the callback never
     * sees a value it produced itself (raw reads bypass it).
     */
    private fun installLineGetter(
        clazz: Class<*>,
        member: AppleMusicRuntimeMember,
        resolve: (Any, String?) -> Any?,
    ) {
        val name = nativeNames[member] ?: return
        val method = AppleReflection.findMethodOrNull(clazz, name, parameterCount = 0) ?: return
        if (method.returnType != String::class.java) return
        if (!installed.add("${method.declaringClass.name}#${method.name}")) return
        ModernXposedRuntime.hookMethod(method, object : ModernMethodHook() {
            override fun afterHookedMethod(param: MethodHookParam) {
                if (rawRead.get() == true) return
                val line = param.thisObject ?: return
                runCatching {
                    val replacement = resolve(line, param.result as? String)
                    if (replacement != null) param.result = replacement
                }
            }
        }, scope)
    }

    private fun installBooleanAvailability(
        clazz: Class<*>,
        member: AppleMusicRuntimeMember,
        resolve: (Any?, Boolean) -> Boolean,
    ) {
        val name = nativeNames[member] ?: return
        val method = AppleReflection.findMethodOrNull(clazz, name, parameterCount = 1) ?: return
        if (method.returnType != java.lang.Boolean.TYPE) return
        if (!installed.add("${method.declaringClass.name}#${method.name}")) return
        ModernXposedRuntime.hookMethod(method, object : ModernMethodHook() {
            override fun afterHookedMethod(param: MethodHookParam) {
                val original = param.result as? Boolean ?: return
                runCatching { param.result = resolve(param.thisObject, original) }
            }
        }, scope)
    }

    /**
     * HLE's `hookAppleLyricsPreferredLanguages`: expand both `String[]` request
     * arrays so the UI asks for the Latn tags the online lane can satisfy.
     */
    private fun installPreferredLanguageExpansion() {
        val clazz = runCatching {
            resolver.resolveClass(AppleMusicHookPoint.LYRICS_PREFERRED_LANGUAGES_REQUEST).clazz
        }.getOrNull() ?: return
        clazz.declaredConstructors.forEach { constructor ->
            val stringArrayIndexes = constructor.parameterTypes.indices.filter { index ->
                constructor.parameterTypes[index] == Array<String>::class.java
            }
            if (stringArrayIndexes.size != 2) return@forEach
            if (!installed.add("${constructor.declaringClass.name}<init>${stringArrayIndexes.joinToString()}")) {
                return@forEach
            }
            val translationIndex = stringArrayIndexes.first()
            val pronunciationIndex = stringArrayIndexes.last()
            runCatching { constructor.isAccessible = true }
            ModernXposedRuntime.hookMethod(constructor, object : ModernMethodHook() {
                override fun beforeHookedMethod(param: MethodHookParam) {
                    runCatching {
                        // The preferred-language request is built once the app has
                        // resolved its own language lists: another chance to
                        // settle an open selection before the request is
                        // assembled.
                        refreshPronunciationSelectionIfOpen()
                        (param.args.getOrNull(translationIndex) as? Array<*>)?.let { values ->
                            param.args[translationIndex] = expandAppleLyricsTranslationLanguages(
                                values.filterIsInstance<String>(),
                            ).toTypedArray()
                        }
                        (param.args.getOrNull(pronunciationIndex) as? Array<*>)?.let { values ->
                            param.args[pronunciationIndex] = expandAppleLyricsPronunciationLanguages(
                                values.filterIsInstance<String>(),
                            ).toTypedArray()
                        }
                    }
                }
            }, scope)
        }
    }

    /**
     * HLE's `hookAppleOfficialPronunciationLanguageMatching`: never match a
     * Mandarin pronunciation while hidden, and fall back to the third-party
     * language when Apple itself matches nothing.
     */
    private fun installPronunciationLanguageMatch() {
        val method = runCatching {
            resolver.resolveMethod(AppleMusicHookPoint.LYRICS_OFFICIAL_PRONUNCIATION_MATCH).method
        }.getOrNull() ?: return
        if (!installed.add("${method.declaringClass.name}#${method.name}")) return
        ModernXposedRuntime.hookMethod(method, object : ModernMethodHook() {
            override fun afterHookedMethod(param: MethodHookParam) {
                runCatching {
                    val appleLanguages = (param.args.firstOrNull() as? Array<*>)
                        ?.filterIsInstance<String>()
                        .orEmpty()
                    if (
                        ApplePronunciationVisibilityPolicy.shouldHide(
                            genre = genreFor(modelSongId).orEmpty().takeIf(String::isNotBlank),
                            pronunciationLanguages = appleLanguages,
                            hideMandarinPinyin = hideMandarinPinyin,
                        )
                    ) {
                        param.result = null
                        return@runCatching
                    }
                    param.result = NativeLyricModelPolicy.selectLanguage(
                        systemMatch = param.result as? String,
                        appleLanguages = appleLanguages,
                        onlineFallbackLanguage = fallbackLanguage(),
                    )
                }
            }
        }, scope)
    }

    private fun fallbackLanguage(languages: List<String> = applePronunciationLanguages): String? =
        NativeLyricModelPolicy.thirdPartyPronunciationFallbackLanguage(
            systemLanguage = systemLyricsLanguage,
            enabled = enabled,
            hasOnlinePronunciation = enabled && overlay.hasPronunciation(currentSongId()),
            hideMandarinPinyin = hideMandarinPinyin,
            pronunciationLanguages = languages,
            genre = genreFor(modelSongId).orEmpty().takeIf(String::isNotBlank),
        )

    private fun onlineTranslation(line: Any): String? {
        if (!enabled) return null
        val begin = number(call(line, AppleMusicRuntimeMember.LYRICS_NATIVE_BEGIN_METHOD)) ?: return null
        val end = number(call(line, AppleMusicRuntimeMember.LYRICS_NATIVE_END_METHOD)) ?: return null
        return overlay.translation(currentSongId(), begin, end, rawLineText(line))
    }

    private fun onlinePronunciation(line: Any, text: String?): String? {
        if (!enabled || mandarinHidden) return null
        val begin = number(call(line, AppleMusicRuntimeMember.LYRICS_NATIVE_BEGIN_METHOD)) ?: return null
        val end = number(call(line, AppleMusicRuntimeMember.LYRICS_NATIVE_END_METHOD)) ?: return null
        return RomanizationPolicy.sanitize(
            originalText = text,
            pronunciation = overlay.pronunciation(currentSongId(), begin, end, text),
        )
    }

    /**
     * The device-facing per-track proof that the native model was written.
     * `officialPronunciation` is the per-call reading that now drives the
     * decision; `officialAtBuild` is the old build-time reading, kept so a log
     * can show the two diverging; `officialLanguage` is Apple's advertised own
     * lane, `selectedLanguage` what `setPronunciation` was handed,
     * `appleLanguagesKnown` whether Apple's language vector was populated at this
     * call, and `selection`/`reason` which lane was chosen and why — so the log
     * proves a fallback was selected (`reason=apple-unanswered`) and can show it
     * superseded by Apple's lane (`reason=apple-lane`) on the next pass.
     */
    private fun reportNativeWrite(
        lines: List<Any>,
        officialAtBuild: Boolean,
        officialNow: Boolean,
        languages: List<String>,
        selection: NativeLyricModelPolicy.PronunciationSelectionPlan,
    ) {
        val songId = modelSongId
        val translationLines = lines.count { onlineTranslation(it) != null }
        val pronunciationLines = lines.count { onlinePronunciation(it, rawLineText(it)) != null }
        val advertised = advertisedPronunciationLanguages(languages)
        val officialLanguage = NativeLyricModelPolicy.officialPronunciationLanguage(advertised)
        diagnostic.log(
            songId,
            "online-translation native-write id=$songId " +
                "translationLines=$translationLines " +
                "pronunciationLines=$pronunciationLines " +
                "languages=${languages.joinToString(",")} " +
                "officialPronunciation=$officialNow " +
                "officialAtBuild=$officialAtBuild " +
                "officialLanguage=${officialLanguage ?: NONE} " +
                "selectedLanguage=${selection.language ?: NONE} " +
                "appleLanguagesKnown=${selection.appleLanguagesKnown} " +
                "selection=${selection.selection.token} " +
                "reason=${selection.reason} " +
                "mandarinHidden=$mandarinHidden",
        )
    }

    private fun nativeLines(songNative: Any): List<Any> {
        val sections = call(
            songNative,
            AppleMusicRuntimeMember.LYRICS_NATIVE_SONG_SECTIONS_METHOD,
        ) ?: return emptyList()
        val sectionCount = vectorSize(sections)
        return buildList {
            repeat(sectionCount.coerceIn(0, MAX_SECTIONS)) { sectionIndex ->
                val section = vectorItem(sections, sectionIndex) ?: return@repeat
                val lineVector = call(
                    section,
                    AppleMusicRuntimeMember.LYRICS_NATIVE_SECTION_LINES_METHOD,
                ) ?: return@repeat
                val lineCount = vectorSize(lineVector)
                repeat(lineCount.coerceIn(0, MAX_LINES_PER_SECTION)) { lineIndex ->
                    vectorItem(lineVector, lineIndex)?.let(::add)
                }
            }
        }
    }

    private fun vectorItem(vector: Any, index: Int): Any? {
        val name = nativeNames[AppleMusicRuntimeMember.LYRICS_NATIVE_VECTOR_GET_METHOD] ?: return null
        val pointer = runCatching { AppleReflection.call(vector, name, index.toLong()) }
            .recoverCatching { AppleReflection.call(vector, name, index) }
            .getOrNull() ?: return null
        return pointerGet(pointer)
    }

    private fun vectorSize(vector: Any?): Int =
        (call(vector, AppleMusicRuntimeMember.LYRICS_NATIVE_VECTOR_SIZE_METHOD) as? Number)
            ?.toInt()
            ?: 0

    private fun vectorStrings(vector: Any?): List<String> {
        if (vector == null) return emptyList()
        val name = nativeNames[AppleMusicRuntimeMember.LYRICS_NATIVE_VECTOR_GET_METHOD] ?: return emptyList()
        return buildList {
            repeat(vectorSize(vector).coerceIn(0, MAX_LANGUAGES)) { index ->
                val value = runCatching { AppleReflection.call(vector, name, index.toLong()) }
                    .recoverCatching { AppleReflection.call(vector, name, index) }
                    .getOrNull()
                if (value is String && value.isNotBlank()) add(value)
            }
        }
    }

    /** Dereferences a JavaCPP pointer through the profile's `get` member. */
    private fun pointerGet(pointer: Any?): Any? {
        if (pointer == null) return null
        val name = nativeNames[AppleMusicRuntimeMember.LYRICS_NATIVE_POINTER_GET_METHOD] ?: return pointer
        return runCatching { AppleReflection.call(pointer, name) }.getOrNull() ?: pointer
    }

    private fun call(receiver: Any?, member: AppleMusicRuntimeMember, vararg args: Any?): Any? {
        if (receiver == null) return null
        val name = nativeNames[member] ?: return null
        return runCatching { AppleReflection.call(receiver, name, *args) }.getOrNull()
    }

    private fun rawLineText(line: Any?): String? = withRawRead {
        call(line, AppleMusicRuntimeMember.LYRICS_NATIVE_LINE_TEXT_METHOD) as? String
    }

    private fun nativeSongId(songNative: Any?): Long {
        val value = call(songNative, AppleMusicRuntimeMember.LYRICS_SONG_ADAM_ID_METHOD)
        return (value as? Number)?.toLong() ?: value?.toString()?.toLongOrNull() ?: 0L
    }

    private fun currentSongId(): String? = modelSongId.takeIf { it > 0L }?.toString()

    private fun number(value: Any?): Long? = (value as? Number)?.toLong()

    private fun <T> withRawRead(block: () -> T): T {
        val previous = rawRead.get()
        rawRead.set(true)
        return try {
            block()
        } finally {
            if (previous == null) rawRead.remove() else rawRead.set(previous)
        }
    }

    private fun <T> withPronunciationQueryGuard(block: () -> T): T {
        val previous = pronunciationQueryGuard.get()
        pronunciationQueryGuard.set(true)
        return try {
            block()
        } finally {
            if (previous == null) pronunciationQueryGuard.remove() else pronunciationQueryGuard.set(previous)
        }
    }

    private companion object {
        const val MAX_SECTIONS = 8
        const val MAX_LINES_PER_SECTION = 64
        const val MAX_LANGUAGES = 32

        /** Placeholder for a diagnostic field with no language to report. */
        const val NONE = "none"

        /**
         * HLE emits one full native-model line per build, but the shared
         * per-track budget on this channel is deliberately small; keep the
         * native proof to a couple of lines per track.
         */
        const val MAX_DIAGNOSTIC_LINES = 4
    }
}
