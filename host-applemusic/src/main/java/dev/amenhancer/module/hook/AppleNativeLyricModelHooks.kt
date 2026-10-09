package dev.amenhancer.module.hook

import android.os.Handler
import android.os.Looper
import dev.amenhancer.module.lyrics.online.ApplePronunciationPolicy
import dev.amenhancer.module.lyrics.online.ApplePronunciationVisibilityPolicy
import dev.amenhancer.module.lyrics.online.ApplePronunciationWordTrack
import dev.amenhancer.module.lyrics.online.NativeLyricModelPolicy
import dev.amenhancer.module.lyrics.online.NativeLyricOverlayStore
import dev.amenhancer.module.lyrics.online.OnlineTranslationContentPolicy
import dev.amenhancer.module.lyrics.online.PresentationRefreshOutcome
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
import java.util.ArrayDeque
import java.util.Collections
import java.util.IdentityHashMap
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
 *    main handler for the bound fragment and pointer. That only re-runs the
 *    presentation: because the lyrics adapter reads the pronunciation flags once
 *    at bind, the refresh then mirrors HLE's `refreshAppleLyricsRecyclerView`
 *    and notifies the adapter (`AppleLyricsPresentationRebind`). Fragments are
 *    also bound from the native-presentation seam (`LYRICS_NATIVE_PRESENTATION`,
 *    HLE's `R2`/`F2`), so a refresh that ran before the view existed is retried
 *    instead of being swallowed. Only an invoke that returns latches the
 *    anti-thrash state. The `presentation-refresh` line records the decision,
 *    the outcome, the resolved `adapter=`, the `trigger=` and whether the state
 *    was `cleared` for retry.
 *  - The build gate deliberately skips a module supplement pointer (HLE: Apple's
 *    track refresh on a supplement pointer makes the lyrics page twitch). The
 *    supplement path owns its own refresh instead: HLE's store update
 *    (`AppleSupplementDataReceive`) calls
 *    `refreshAppleLyricsSupplementPresentation` on a content change, and on this
 *    fork [onCustomOverlayUpdated] is that ask — the custom-lyrics completion
 *    feed reports a successful overlay write, and this installer re-presents on
 *    the main handler once per (song, overlay revision). The refresh re-runs
 *    HLE's full pre-presentation sequence (`ensureNativeModel` — the fork's
 *    `ensureAppleLyricTextHooks` — then the selection) so the re-presentation
 *    picks up both the online translation and the pronunciation lane, not only
 *    the lane the build gate happened to catch.
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

    /**
     * One-shot pronunciation plan per Apple word vector identity (HLE's
     * `AppleLyricsPronunciationState.pendingRenderPlans`). Registered by the
     * `getPronunciationWords` override and consumed once by the app's word-render
     * adapter, so the main line's own render pass can never see the romanization.
     */
    private val pendingPronunciationRenderPlans =
        Collections.synchronizedMap(IdentityHashMap<Any, PronunciationRenderPlan>())

    /**
     * The render-scope stack (HLE's `wordRenderContexts`): pushed while the
     * adapter lays out a pronunciation vector, popped afterwards. Only reads made
     * inside that scope are rewritten by the word-text hook.
     */
    private val pronunciationWordRenderContexts = ThreadLocal<ArrayDeque<PronunciationWordRenderContext>>()

    /** De-duplicates the `pronunciation-words` diagnostic per build and getter. */
    private val wordDiagnosticKeys = ConcurrentHashMap.newKeySet<String>()

    /** True once at least one `LyricsWordVector -> ArrayMap` adapter method is hooked. */
    @Volatile
    private var wordRenderAdapterAvailable: Boolean = false

    /** HLE's `ApplePronunciationRenderPlan`: the line text to distribute. */
    private data class PronunciationRenderPlan(val pronunciation: String)

    /** HLE's `ApplePronunciationWordKey`: the native word's stable fields. */
    private data class PronunciationWordKey(val wordId: Int, val begin: Int, val end: Int)

    /** HLE's `ApplePronunciationWordRenderContext`: per-word display text. */
    private data class PronunciationWordRenderContext(
        val displayTextByWord: Map<PronunciationWordKey, String>,
    )

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
     * HLE's `refreshAppleLyricsRecyclerView` tail: after our own re-presentation
     * returns, the lyrics adapter is rebound so the flags it read once at bind
     * reflect the updated model. The RecyclerView accessor is the profile-pinned
     * `LYRICS_UI_ON_CREATE_VIEW#LYRICS_UI_RECYCLER_VIEW_METHOD` when the profile
     * carries one, else the fork's own verified `getRecyclerView`
     * (`LyricsTypefaceSession`/`TabletLyricTypography`); the obfuscated notify
     * and item-count members come from the profile's `LYRICS_RECYCLER_ADAPTER`
     * point. Every step is fail-open.
     */
    private val presentationRebind = AppleLyricsPresentationRebind(
        recyclerMethodNames = lyricsRecyclerMethodNames(),
        adapterItemCountMemberNames = lyricsAdapterMemberNames(
            AppleMusicRuntimeMember.LYRICS_ADAPTER_ITEM_COUNT_METHOD,
        ),
        adapterNotifyMemberNames = lyricsAdapterMemberNames(
            AppleMusicRuntimeMember.LYRICS_ADAPTER_NOTIFY_DATA_CHANGED_METHOD,
        ),
    )

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
     * An accepted refresh whose main-handler attempt has not re-invoked the
     * app's presentation yet, or aborted before it could (`not-bound`,
     * `pointer-dead`, `song-changed`, `invoke-failed`). The native-presentation
     * binding seam re-dispatches it once the fragment and pointer exist, which
     * is how HLE's R2/F2 seam makes the refresh succeed on the first play. Only
     * a successful invoke ([PresentationRefreshOutcome.latches]) clears it.
     */
    @Volatile
    private var pendingPresentationRefresh: PresentationRefreshState? = null

    /**
     * How one presentation-refresh attempt was initiated, printed as the
     * `trigger=` diagnostic field. [BUILD] is HLE's build-after gate,
     * [CUSTOM_OVERLAY] is the custom-lyrics completion feed's post-overlay ask —
     * the fork's replacement for the refresh HLE's own supplement store runs when
     * its content changes — and [F2_RETRY] is the native-presentation (R2/F2)
     * seam re-dispatching an accepted refresh whose first attempt could not reach
     * the page.
     */
    private enum class PresentationRefreshTrigger(val token: String) {
        BUILD("build"),
        CUSTOM_OVERLAY("custom-overlay"),
        F2_RETRY("f2-retry"),
    }

    /**
     * One accepted refresh decision. The whole gate input set is part of the
     * key, so any signal that can change after a build — a new overlay revision,
     * Apple advertising its lane, the supplement pointer resolving to Apple's
     * document, the Mandarin rule — is a new state, while an unchanged re-build
     * is not. [trigger] names which of the two triggers decided it, so a custom
     * overlay write and a build for the same revision are distinct states and
     * neither can swallow the other's refresh.
     */
    private data class PresentationRefreshState(
        val trigger: PresentationRefreshTrigger,
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
        installNativePresentationSeam()
        installPreferredLanguageExpansion()
        installPronunciationLanguageMatch()
    }

    /**
     * The lyrics RecyclerView accessors tried in order: the profile's own
     * `LYRICS_UI_ON_CREATE_VIEW#LYRICS_UI_RECYCLER_VIEW_METHOD` when it carries
     * one, then the fork's verified `getRecyclerView` (the same accessor
     * `LyricsTypefaceSession`, `TabletLyricTypography` and HLE's 1606 inherited
     * target use). The 1606 profile leaves `LYRICS_UI_ON_CREATE_VIEW` empty, so
     * the fallback is what runs there; no signature is invented.
     */
    private fun lyricsRecyclerMethodNames(): List<String> = buildList {
        AppleMusicHookProfiles
            .exactTargets(resolver.version, AppleMusicHookPoint.LYRICS_UI_ON_CREATE_VIEW)
            .forEach { target ->
                target.runtimeMemberNames[AppleMusicRuntimeMember.LYRICS_UI_RECYCLER_VIEW_METHOD]
                    ?.let { name -> add(name) }
            }
        add(FALLBACK_RECYCLER_VIEW_METHOD)
    }.distinct()

    /** The `LYRICS_RECYCLER_ADAPTER` members of [member], in profile order. */
    private fun lyricsAdapterMemberNames(member: AppleMusicRuntimeMember): List<String> =
        AppleMusicHookProfiles
            .exactTargets(resolver.version, AppleMusicHookPoint.LYRICS_RECYCLER_ADAPTER)
            .mapNotNull { target -> target.runtimeMemberNames[member] }
            .distinct()

    /**
     * HLE's `LYRICS_NATIVE_PRESENTATION` binding seam — `R2` on 6.5.x, `F2` on
     * the 1606 profile. HLE remembers the fragment and pointer here because the
     * view-model build can finish before the lyrics view exists; the fork bound
     * only from the install method (`w2`), so a refresh that ran first aborted
     * `not-bound` and nothing re-asked once the view arrived. That is the
     * "romanization only after backgrounding" stall. Fail-open: an unresolved
     * point or a throwing capture never affects Apple's presentation.
     */
    private fun installNativePresentationSeam() {
        val method = runCatching {
            resolver.resolveMethod(AppleMusicHookPoint.LYRICS_NATIVE_PRESENTATION).method
        }.getOrNull() ?: run {
            log(
                "online-translation presentation-refresh native seam unavailable on " +
                    resolver.version.displayName,
            )
            return
        }
        ModernXposedRuntime.hookMethod(method, object : ModernMethodHook() {
            override fun beforeHookedMethod(param: MethodHookParam) {
                runCatching {
                    val fragment = param.thisObject ?: return@runCatching
                    val pointer = param.args.getOrNull(0) ?: return@runCatching
                    // Install the per-line/availability hooks for the pointer that
                    // is about to be shown, exactly as HLE's F2/R2 `before` does.
                    onLyricsPointer(pointer)
                    onLyricsPresentation(fragment, pointer)
                }.onFailure { error ->
                    log("online-translation presentation-refresh native seam failed: ${error.message}")
                }
            }
        }, scope)
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
        retryPendingPresentationRefresh()
    }

    /**
     * Re-dispatches an accepted refresh that has not re-invoked Apple's
     * presentation yet, from a later binding seam (HLE's R2/F2). The first
     * main-handler attempt can run before Apple has presented the lyrics view;
     * it aborts `not-bound` and clears the dedupe state, and without this seam
     * nothing asks again until the page is re-created. The run is posted so it
     * lands after Apple's current presentation, guarded against our own invoke,
     * and idempotent in [performPresentationRefresh], so repeated seam events
     * cannot double-refresh a latched state.
     */
    private fun retryPendingPresentationRefresh() {
        if (presentationInvokeGuard.get() == true) return
        val pending = pendingPresentationRefresh ?: return
        val pointer = presentationPointerRef?.get() ?: return
        val songNative = pointerGet(pointer) ?: return
        if (nativeSongId(songNative) != pending.songId) return
        mainHandler.post {
            performPresentationRefresh(pending, PresentationRefreshTrigger.F2_RETRY)
        }
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
     *    dispatched to another thread);
     *  - an accepted refresh is remembered in [pendingPresentationRefresh] until
     *    it actually re-invokes the presentation, so the native-presentation
     *    binding seam can run it once the lyrics view exists.
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

        val state = PresentationRefreshState(
            trigger = PresentationRefreshTrigger.BUILD,
            songId = songId,
            overlayRevision = overlay.revision(),
            sourceIsApple = sourceIsApple,
            officialPronunciation = officialPronunciation,
            officialLane = officialLane,
            onlineTranslation = onlineTranslation,
            onlinePronunciation = onlinePronunciation,
            pronunciationSelected = pronunciationSelected,
        )
        val reason = refreshReason(state)
        val shouldRefresh = NativeLyricModelPolicy.shouldRefreshPresentationAfterBuild(
            sourceIsApple = sourceIsApple,
            hasValidOfficialPronunciation = officialPronunciation,
            hasOnlineTranslation = onlineTranslation,
            hasOnlinePronunciation = onlinePronunciation,
            pronunciationSelected = pronunciationSelected,
        )
        // An unchanged state has already been decided (and, when accepted,
        // refreshed). This is what keeps the re-presentation's own build from
        // looping: the gate holds again but the state matches.
        if (state == lastPresentationRefreshState) return
        lastPresentationRefreshState = state
        // A different state supersedes any accepted-but-unapplied refresh; the
        // same state may still be pending because its earlier attempt aborted
        // and is waiting for the binding seam. A pending *custom-overlay* ask is
        // the exception: a build cannot decide it was stale — the completion's
        // own next overlay revision does that — and dropping it here would lose
        // the post-overlay refresh before the native-presentation seam can retry
        // it (the ordering a first play can produce: overlay ask aborts
        // `not-bound`, then the build seam runs, then the F2 binding arrives).
        if (pendingPresentationRefresh != state &&
            pendingPresentationRefresh?.trigger != PresentationRefreshTrigger.CUSTOM_OVERLAY
        ) {
            pendingPresentationRefresh = null
        }

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
            adapterName = null,
            stateCleared = false,
            trigger = PresentationRefreshTrigger.BUILD.token,
        )
        if (!shouldRefresh) return

        pendingPresentationRefresh = state
        mainHandler.post { performPresentationRefresh(state, PresentationRefreshTrigger.BUILD) }
    }

    /**
     * The custom-lyrics completion feed's post-overlay trigger.
     *
     * HLE's supplement path owns a refresh exactly like this one: its store
     * update (`AppleSupplementDataReceive`) calls
     * `refreshAppleLyricsSupplementPresentation` whenever the receipt reports
     * `displayContentChanged`. The fork's custom-lyrics completion feed writes
     * the same overlay from `CustomLyricsCompletionFeed` → `runtime.translationEnricher`
     * → `overlay.update(...)` and, before this trigger, never asked for a
     * re-presentation: the device log's every custom track logged
     * `detail=supplement refreshed=false` and the page kept its first render
     * until the view was re-created.
     *
     * The build-time `supplement` skip is deliberately not lifted — HLE explains
     * Apple's track refresh on a supplement pointer makes the lyrics page twitch
     * — so this post-overlay ask is the supplement path's own refresh instead.
     *
     * Guarantees: posted to the main handler; once per (song, overlay revision)
     * via the shared [lastPresentationRefreshState] dedupe; the completion must
     * belong to the model the hooks are installed for (`modelSongId`) and
     * [performPresentationRefresh] re-checks the bound pointer's native id, which
     * is HLE's own expected-song gate; and an invoke of our own is ignored through
     * [presentationInvokeGuard], so the re-presentation cannot re-enter here.
     */
    fun onCustomOverlayUpdated(songId: Long) {
        if (!enabled || nativeNames.isEmpty()) return
        if (songId <= 0L) return
        mainHandler.post { requestCustomOverlayPresentationRefresh(songId) }
    }

    private fun requestCustomOverlayPresentationRefresh(songId: Long) {
        // The invoke guard is the hard recursion stop for a completion that
        // arrives while our own re-presentation is running.
        if (presentationInvokeGuard.get() == true) return
        // Expected-song check: the overlay write must belong to the song whose
        // native model the hooks are installed for. The authoritative check is
        // performed again against the bound pointer before the invoke.
        if (songId != modelSongId) return
        val onlineTranslation = enabled && overlay.hasTranslation(songId.toString())
        val onlinePronunciation = enabled && overlay.hasPronunciation(songId.toString())
        val songNative = songNativeRef?.get()?.takeIf { nativeSongId(it) == songId }
        val advertised = songNative
            ?.let { advertisedPronunciationLanguages(songPronunciationLanguages(it)) }
            .orEmpty()
        val officialLane = NativeLyricModelPolicy.officialPronunciationLanguage(advertised)
        val state = PresentationRefreshState(
            trigger = PresentationRefreshTrigger.CUSTOM_OVERLAY,
            songId = songId,
            overlayRevision = overlay.revision(),
            sourceIsApple = !isModuleSupplementSong(songId),
            // Diagnostic only on this trigger: the custom decision below is the
            // overlay, never the build gate's Apple-lane rule. A dead model ref
            // fails to false and hides nothing.
            officialPronunciation = songNative != null &&
                (hasValidOfficialPronunciation(songNative) || officialLane != null),
            officialLane = officialLane,
            onlineTranslation = onlineTranslation,
            onlinePronunciation = onlinePronunciation,
            pronunciationSelected = enabled && !mandarinHidden,
        )
        // Once per (song, overlay revision): a completion that changed no overlay
        // content recomputes the same state and is a no-op.
        if (state == lastPresentationRefreshState) return
        lastPresentationRefreshState = state
        if (pendingPresentationRefresh != state) pendingPresentationRefresh = null

        val reason = refreshReason(state)
        val shouldRefresh = NativeLyricModelPolicy.shouldRefreshPresentationAfterCustomOverlay(
            hasOnlineTranslation = onlineTranslation,
            hasOnlinePronunciation = onlinePronunciation,
        )
        val detail = if (shouldRefresh) "custom-refresh" else "gate"
        logPresentationRefresh(
            songId = songId,
            reason = reason,
            hasValidOfficialPronunciation = state.officialPronunciation,
            onlineTranslation = onlineTranslation,
            onlinePronunciation = onlinePronunciation,
            pronunciationSelected = state.pronunciationSelected,
            refreshed = false,
            detail = detail,
            adapterName = null,
            stateCleared = false,
            trigger = PresentationRefreshTrigger.CUSTOM_OVERLAY.token,
        )
        if (!shouldRefresh) return

        pendingPresentationRefresh = state
        mainHandler.post {
            performPresentationRefresh(state, PresentationRefreshTrigger.CUSTOM_OVERLAY)
        }
    }

    /**
     * The [PresentationRefreshState]'s reason, from the trigger that decided it:
     * the build gate keeps HLE's `sourceIsApple`/online/official rule, while the
     * post-overlay trigger uses the store-update rule (a lane exists in the
     * overlay). One place, so a log can never name a branch the decision did not
     * take.
     */
    private fun refreshReason(state: PresentationRefreshState): String =
        when (state.trigger) {
            PresentationRefreshTrigger.CUSTOM_OVERLAY ->
                NativeLyricModelPolicy.customOverlayRefreshReason(
                    hasOnlineTranslation = state.onlineTranslation,
                    hasOnlinePronunciation = state.onlinePronunciation,
                )
            PresentationRefreshTrigger.BUILD,
            PresentationRefreshTrigger.F2_RETRY -> NativeLyricModelPolicy.presentationRefreshReason(
                sourceIsApple = state.sourceIsApple,
                hasValidOfficialPronunciation = state.officialPronunciation,
                hasOnlineTranslation = state.onlineTranslation,
                hasOnlinePronunciation = state.onlinePronunciation,
                pronunciationSelected = state.pronunciationSelected,
            )
        }

    /**
     * The main-handler half of HLE's `refreshAppleLyricsSupplementPresentation`:
     * resolve the bound fragment and pointer, verify the pointer still belongs to
     * the expected song, re-run HLE's full pre-presentation sequence
     * (`ensureAppleLyricTextHooks(songNative)` then
     * `applyAppleNativeSupplementSelection(songNative)`), re-invoke the app's
     * presentation method and then rebind the lyrics adapter
     * (`refreshAppleLyricsRecyclerView` → `notifyDataSetChanged`).
     *
     * [trigger] is how *this* attempt was dispatched, not which trigger originally
     * decided: an accepted custom-overlay refresh that aborted `not-bound` is
     * re-dispatched here by the native-presentation seam and logs
     * `trigger=f2-retry`.
     *
     * Only an invoke that actually returned latches the state; every abort clears
     * the dedupe state ([PresentationRefreshOutcome.cleared]) *and* leaves the
     * state pending in [pendingPresentationRefresh], so either a later build or
     * the native-presentation binding seam can ask again. A duplicate queued
     * attempt for an already-latched state is a no-op.
     */
    private fun performPresentationRefresh(
        state: PresentationRefreshState,
        trigger: PresentationRefreshTrigger,
    ) {
        // Only the accepted-and-still-current state may run: a duplicate queued
        // after a successful apply, or a stale attempt superseded by a newer
        // build decision, is a no-op.
        if (pendingPresentationRefresh != state) return

        val reason = refreshReason(state)

        fun finish(outcome: PresentationRefreshOutcome, adapterName: String?) {
            if (outcome.latches) {
                if (pendingPresentationRefresh == state) pendingPresentationRefresh = null
            } else if (lastPresentationRefreshState == state) {
                lastPresentationRefreshState = null
            }
            logPresentationRefresh(
                songId = state.songId,
                reason = reason,
                hasValidOfficialPronunciation = state.officialPronunciation,
                onlineTranslation = state.onlineTranslation,
                onlinePronunciation = state.onlinePronunciation,
                pronunciationSelected = state.pronunciationSelected,
                refreshed = outcome.latches,
                detail = outcome.token,
                adapterName = adapterName,
                stateCleared = outcome.cleared,
                trigger = trigger.token,
            )
        }

        val method = presentationMethod
            ?: return finish(PresentationRefreshOutcome.NO_PRESENTATION_METHOD, adapterName = null)
        val fragment = presentationFragmentRef?.get()
            ?: return finish(PresentationRefreshOutcome.NOT_BOUND, adapterName = null)
        val pointer = presentationPointerRef?.get()
            ?: return finish(PresentationRefreshOutcome.NOT_BOUND, adapterName = null)
        val songNative = pointerGet(pointer)
            ?: return finish(PresentationRefreshOutcome.POINTER_DEAD, adapterName = null)
        if (nativeSongId(songNative) != state.songId) {
            return finish(PresentationRefreshOutcome.SONG_CHANGED, adapterName = null)
        }

        // HLE's refresh re-runs its full pre-presentation sequence before the
        // invoke: `ensureAppleLyricTextHooks(songNative)` (remember the
        // advertisement and (re)install every per-line/word/availability hook so
        // both the online translation and the pronunciation lane are read back
        // from the settled model) and then
        // `applyAppleNativeSupplementSelection(songNative)`. The fork's
        // `ensureNativeModel` is the first half and was missing: without it a
        // model built before the overlay existed could be re-presented with the
        // translation getter still reading Apple's empty value, which is why the
        // user saw Apple's translation only after backgrounding. The explicit
        // selection call after it keeps HLE's second step visible even though
        // `ensureNativeModel` already runs the same idempotent selection.
        runCatching {
            ensureNativeModel(songNative, viewModel = null)
        }.onFailure { error ->
            log("online-translation presentation-refresh ensure failed: ${error.message}")
        }
        runCatching {
            applyAppleNativePronunciationSelection(songNative, songPronunciationLanguages(songNative))
        }.onFailure { error ->
            log("online-translation presentation-refresh selection failed: ${error.message}")
        }

        val invoke = runCatching {
            presentationInvokeGuard.set(true)
            try {
                method.invoke(fragment, pointer)
            } finally {
                presentationInvokeGuard.remove()
            }
        }
        invoke.onFailure { error ->
            log("online-translation presentation-refresh invoke failed: ${error.message}")
            finish(PresentationRefreshOutcome.INVOKE_FAILED, adapterName = null)
        }
        if (invoke.isFailure) return

        // HLE only rebinds after a successful re-presentation, and only on the
        // next frame while the layout manager is busy. A missing view/adapter
        // keeps the invoke latched but is reported as `adapter-unavailable`.
        val rebound = runCatching { presentationRebind.rebind(fragment) }
            .getOrElse { error ->
                log("online-translation presentation-refresh rebind failed: ${error.message}")
                AppleLyricsPresentationRebind.Result(didNotify = false, adapterName = null)
            }
        finish(
            outcome = if (rebound.didNotify) {
                PresentationRefreshOutcome.REBOUND
            } else {
                PresentationRefreshOutcome.ADAPTER_UNAVAILABLE
            },
            adapterName = rebound.adapterName,
        )
    }

    /**
     * The device-facing proof of the refresh decision, on the same visible
     * channel as `native-write`: the exact gate inputs, the branch that fired,
     * the attempt's `trigger=`, the resolved adapter and whether the recorded
     * state was cleared for retry. A `refreshed=true detail=rebound` line on the
     * first play is the evidence the fix landed; `detail=not-bound state=cleared`
     * is the proof a refresh that ran before the view existed stayed retryable;
     * and `detail=custom-refresh trigger=custom-overlay` is the proof the
     * custom-lyrics completion feed's post-overlay ask reached the page.
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
        adapterName: String?,
        stateCleared: Boolean,
        trigger: String,
    ) {
        log(
            "online-translation presentation-refresh id=$songId reason=$reason " +
                "officialPronunciation=$hasValidOfficialPronunciation " +
                "onlineTranslation=$onlineTranslation " +
                "onlinePronunciation=$onlinePronunciation " +
                "pronunciationSelected=$pronunciationSelected " +
                "presentationMethod=${presentationMethod != null} " +
                "refreshed=$refreshed detail=$detail trigger=$trigger " +
                "adapter=${adapterName ?: NONE} " +
                "state=${if (stateCleared) STATE_CLEARED else STATE_LATCHED}",
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
        installPronunciationWordHooks(lines)
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
     * HLE's word-level pronunciation delivery (`hookApplePronunciationWordsGetter`
     * plus `hookApplePronunciationWordRendering`).
     *
     * Apple renders most lyrics word by word (`itunes:timing="Word"`), so the
     * line-level `getHtmlPronunciationLineText` the earlier PRs override is never
     * consumed for those songs: the app asks each line for
     * `getPronunciationWords()` and lays out one view per returned `LyricsWord`.
     * The device log shows the language selected and Apple's line text read while
     * nothing is romanized, which is exactly that gap.
     *
     * The port mirrors HLE exactly:
     *
     *  - `getPronunciationWords()` / `getPronunciationBackgroundWords(boolean)`
     *    return Apple's own vector when its words are valid **and** timed like the
     *    main line (OFFICIAL — Apple's own data first), otherwise Apple's *main*
     *    word vector plus a one-shot render plan (MAIN_LINE_TIMING), otherwise
     *    HLE's empty container (HIDDEN, the Mandarin rule).
     *  - The plan is consumed by the app's own word-render adapter
     *    (`LYRICS_WORD_RENDER_ADAPTER`: the methods taking a `LyricsWordVector` and
     *    returning `android.util.ArrayMap`); while it runs, each native main word's
     *    `getHtmlLineText` is replaced by its
     *    [ApplePronunciationPolicy.displaySegments] slice, so the romanization
     *    reuses the main word's parent line, word id and timeline.
     *  - The line-level background-vocals pronunciation is Apple's own text,
     *    sanitized, with no online fallback — exactly HLE's
     *    `LYRICS_NATIVE_PRONUNCIATION_BACKGROUND_TEXT_METHOD` branch.
     *
     * No native `LyricsWord` is ever synthesized: the adapter dereferences
     * `word.getLyricsLine().get().getLineId()` (verified against the 6.5.3 dex),
     * so a parentless word would break Apple's own rendering — which is why HLE
     * reuses the main vector. Every step fails open: a missing member, a malformed
     * vector or a throwing getter leaves Apple's value untouched, and when no
     * render adapter is resolvable MAIN_LINE_TIMING degrades to HLE's
     * `emptyApplePronunciationWords(...)` rather than returning the main vector,
     * so the main text can never be rendered as romanization.
     */
    private fun installPronunciationWordHooks(lines: List<Any>) {
        // One-shot plans never outlive a model build; a plan registered for a
        // vector the adapter did not consume is dropped here.
        synchronized(pendingPronunciationRenderPlans) { pendingPronunciationRenderPlans.clear() }
        wordDiagnosticKeys.clear()
        lines.map { it.javaClass }.distinct().forEach { clazz ->
            installLineGetter(
                clazz = clazz,
                member = AppleMusicRuntimeMember.LYRICS_NATIVE_PRONUNCIATION_BACKGROUND_TEXT_METHOD,
            ) { line, original ->
                if (mandarinHidden) {
                    ""
                } else {
                    ApplePronunciationPolicy.nonNullDisplayText(
                        RomanizationPolicy.sanitize(
                            originalText = rawText(
                                line,
                                AppleMusicRuntimeMember.LYRICS_NATIVE_BACKGROUND_TEXT_METHOD,
                            ),
                            pronunciation = original,
                        ),
                    )
                }
            }
            installPronunciationWordsGetter(
                clazz = clazz,
                member = AppleMusicRuntimeMember.LYRICS_NATIVE_PRONUNCIATION_WORDS_METHOD,
                parameterCount = 0,
                originalTextMember = AppleMusicRuntimeMember.LYRICS_NATIVE_LINE_TEXT_METHOD,
                pronunciationTextMember =
                    AppleMusicRuntimeMember.LYRICS_NATIVE_PRONUNCIATION_TEXT_METHOD,
                mainWordsMember = AppleMusicRuntimeMember.LYRICS_NATIVE_WORDS_METHOD,
                onlineFallback = true,
            )
            installPronunciationWordsGetter(
                clazz = clazz,
                member = AppleMusicRuntimeMember.LYRICS_NATIVE_PRONUNCIATION_BACKGROUND_WORDS_METHOD,
                parameterCount = 1,
                originalTextMember = AppleMusicRuntimeMember.LYRICS_NATIVE_BACKGROUND_TEXT_METHOD,
                pronunciationTextMember =
                    AppleMusicRuntimeMember.LYRICS_NATIVE_PRONUNCIATION_BACKGROUND_TEXT_METHOD,
                mainWordsMember = AppleMusicRuntimeMember.LYRICS_NATIVE_BACKGROUND_WORDS_METHOD,
                onlineFallback = false,
            )
        }
        installPronunciationWordTextHooks(lines)
        installPronunciationWordRenderHooks()
    }

    /**
     * HLE installs the word-text hook once per word class
     * (`hookAppleLyricTextGetter(wordClass, getHtmlLineText)`). Outside a
     * pronunciation render scope it is a transparent pass-through, so the main
     * line is untouched.
     */
    private fun installPronunciationWordTextHooks(lines: List<Any>) {
        val words = buildList {
            lines.forEach { line ->
                addAll(vectorItems(call(line, AppleMusicRuntimeMember.LYRICS_NATIVE_WORDS_METHOD)))
                val background = call(
                    line,
                    AppleMusicRuntimeMember.LYRICS_NATIVE_BACKGROUND_WORDS_METHOD,
                    false,
                ) ?: call(line, AppleMusicRuntimeMember.LYRICS_NATIVE_BACKGROUND_WORDS_METHOD)
                addAll(vectorItems(background))
            }
        }
        words.map { it.javaClass }.distinct().forEach { wordClass ->
            val name = nativeNames[AppleMusicRuntimeMember.LYRICS_NATIVE_LINE_TEXT_METHOD]
                ?: return@forEach
            val method = AppleReflection.findMethodOrNull(wordClass, name, parameterCount = 0)
                ?: return@forEach
            if (method.returnType != String::class.java) return@forEach
            if (!installed.add("${method.declaringClass.name}#${method.name}")) return@forEach
            ModernXposedRuntime.hookMethod(method, object : ModernMethodHook() {
                override fun afterHookedMethod(param: MethodHookParam) {
                    if (rawRead.get() == true) return
                    runCatching {
                        val replacement = pronunciationWordRenderText(param.thisObject)
                            ?: return@runCatching
                        param.result = replacement
                    }
                }
            }, scope)
        }
    }

    /**
     * HLE's `hookApplePronunciationWordsGetter`: one result override per getter.
     * The raw-read guard keeps our own probes (word text, begins, vector text)
     * from being answered by the hook we install here.
     */
    private fun installPronunciationWordsGetter(
        clazz: Class<*>,
        member: AppleMusicRuntimeMember,
        parameterCount: Int,
        originalTextMember: AppleMusicRuntimeMember,
        pronunciationTextMember: AppleMusicRuntimeMember,
        mainWordsMember: AppleMusicRuntimeMember,
        onlineFallback: Boolean,
    ) {
        val name = nativeNames[member] ?: return
        val method = AppleReflection.findMethodOrNull(clazz, name, parameterCount = parameterCount)
            ?: return
        if (!installed.add("${method.declaringClass.name}#${method.name}/${method.parameterCount}")) {
            return
        }
        ModernXposedRuntime.hookMethod(method, object : ModernMethodHook() {
            override fun afterHookedMethod(param: MethodHookParam) {
                if (rawRead.get() == true) return
                val line = param.thisObject ?: return
                runCatching {
                    val resolved = resolvePronunciationWords(
                        line = line,
                        original = param.result,
                        args = param.args,
                        originalTextMember = originalTextMember,
                        pronunciationTextMember = pronunciationTextMember,
                        mainWordsMember = mainWordsMember,
                        onlineFallback = onlineFallback,
                        getterName = name,
                        parameterCount = parameterCount,
                    )
                    if (resolved != null) param.result = resolved
                }
            }
        }, scope)
    }

    /**
     * HLE's `hookApplePronunciationWordsGetter` body, in HLE's order: official
     * text and word compatibility first, then the `wordTrack` decision, then the
     * resolved vector. `mainTimingPronunciation` deliberately prefers Apple's own
     * line text when Apple has one but its word vector cannot be aligned to the
     * main line — that is HLE's `officialPronunciation` branch, not the online
     * one.
     */
    private fun resolvePronunciationWords(
        line: Any,
        original: Any?,
        args: Array<Any?>,
        originalTextMember: AppleMusicRuntimeMember,
        pronunciationTextMember: AppleMusicRuntimeMember,
        mainWordsMember: AppleMusicRuntimeMember,
        onlineFallback: Boolean,
        getterName: String,
        parameterCount: Int,
    ): Any? {
        if (mandarinHidden) {
            return emptyPronunciationWords(original, null) ?: original
        }
        val originalText = rawText(line, originalTextMember)
        val officialPronunciation = RomanizationPolicy.sanitize(
            originalText = originalText,
            pronunciation = rawText(line, pronunciationTextMember),
        )
        val onlinePronunciationText = if (onlineFallback) {
            onlinePronunciation(line, originalText)
        } else {
            null
        }
        val hasValidOfficialWords = officialPronunciation != null &&
            RomanizationPolicy.sanitize(
                originalText = originalText,
                pronunciation = rawWordVectorText(original),
            ) != null
        val mainWords = nativeNames[mainWordsMember]?.let { name ->
            runCatching { AppleReflection.call(line, name, *args) }.getOrNull()
        }
        val hasCompatibleOfficialWords = hasValidOfficialWords &&
            ApplePronunciationPolicy.hasCompatibleOfficialWordTiming(
                mainWordBegins = renderableWordBegins(mainWords),
                pronunciationWordBegins = renderableWordBegins(original),
            )
        val mainTimingPronunciation = when {
            officialPronunciation != null && !hasCompatibleOfficialWords -> officialPronunciation
            else -> onlinePronunciationText
        }
        val track = ApplePronunciationPolicy.wordTrack(
            hasValidOfficialPronunciation = hasCompatibleOfficialWords,
            hasOnlinePronunciation = mainTimingPronunciation != null,
        )
        val vector = mainWords?.takeIf { vectorSize(it) > 0 }
        val pronunciation = mainTimingPronunciation
        val canRegisterRenderPlan =
            track == ApplePronunciationWordTrack.MAIN_LINE_TIMING &&
                vector != null &&
                pronunciation != null &&
                wordRenderAdapterAvailable
        val resolved: Any? = when (track) {
            ApplePronunciationWordTrack.OFFICIAL -> original
            ApplePronunciationWordTrack.MAIN_LINE_TIMING -> {
                // The explicit null checks (not `canRegisterRenderPlan`) are what
                // let the compiler smart-cast the two arguments.
                if (vector != null && pronunciation != null && canRegisterRenderPlan) {
                    registerPronunciationRenderPlan(vector, pronunciation)
                    vector
                } else {
                    emptyPronunciationWords(original, mainWords) ?: original
                }
            }
            ApplePronunciationWordTrack.HIDDEN -> emptyPronunciationWords(original, null) ?: original
        }
        reportPronunciationWords(
            line = line,
            getterName = getterName,
            parameterCount = parameterCount,
            track = track,
            resolved = resolved,
            renderPlanRegistered = canRegisterRenderPlan,
        )
        return resolved
    }

    /**
     * HLE's `hookApplePronunciationWordRendering` install step: scan each
     * resolved adapter class and its superclasses for
     * `(LyricsWordVector, ...) -> android.util.ArrayMap` and hook them scoped.
     * The class/vector names come from the profile, never from a guessed
     * signature.
     */
    private fun installPronunciationWordRenderHooks() {
        val vectorClassName =
            nativeNames[AppleMusicRuntimeMember.LYRICS_WORD_VECTOR_CLASS_NAME]
                ?: runCatching {
                    resolver.resolveClass(AppleMusicHookPoint.LYRICS_WORD_VECTOR_CLASS).clazz.name
                }.getOrNull()
                ?: return
        val adapterClasses = runCatching {
            resolver.resolveClasses(AppleMusicHookPoint.LYRICS_WORD_RENDER_ADAPTER)
        }.getOrDefault(emptyList())
        if (adapterClasses.isEmpty()) {
            log(
                "online-translation pronunciation-words render-adapter unavailable: " +
                    "${resolver.version.displayName} pins no LYRICS_WORD_RENDER_ADAPTER",
            )
            return
        }
        var installedAny = false
        adapterClasses.forEach { adapter ->
            generateSequence(adapter.clazz) { it.superclass }
                .flatMap { it.declaredMethods.asSequence() }
                .filter { method ->
                    !method.isBridge &&
                        method.parameterTypes.firstOrNull()?.name == vectorClassName &&
                        method.returnType.name == ARRAY_MAP_CLASS
                }
                .distinctBy { method ->
                    method.name to method.parameterTypes.joinToString { it.name }
                }
                .forEach { method ->
                    if (installPronunciationWordRenderHook(method)) installedAny = true
                }
        }
        wordRenderAdapterAvailable = installedAny
        if (!installedAny) {
            log(
                "online-translation pronunciation-words render-adapter found but no " +
                    "LyricsWordVector -> ArrayMap method on " +
                    adapterClasses.joinToString { it.clazz.name },
            )
        }
    }

    /**
     * HLE's `installScopedHook` over one adapter method: `enter` consumes the
     * one-shot plan and pushes the render context, `exit` pops it. `ModernMethodHook`
     * has no exit callback, but `afterHookedMethod` always runs after the body
     * (including on a thrown body), so it is the `finally` half here.
     */
    private fun installPronunciationWordRenderHook(method: Method): Boolean {
        if (!installed.add("render#${method.declaringClass.name}#${method.name}/${method.parameterCount}")) {
            return true
        }
        runCatching { method.isAccessible = true }
        val pushed = ThreadLocal<Boolean>()
        ModernXposedRuntime.hookMethod(method, object : ModernMethodHook() {
            override fun beforeHookedMethod(param: MethodHookParam) {
                if (rawRead.get() == true) return
                runCatching {
                    val vector = param.args.firstOrNull() ?: return@runCatching
                    val plan = consumePronunciationRenderPlan(vector) ?: return@runCatching
                    val context = buildPronunciationWordRenderContext(vector, plan)
                        ?: return@runCatching
                    pushPronunciationWordRenderContext(context)
                    pushed.set(true)
                }
            }

            override fun afterHookedMethod(param: MethodHookParam) {
                if (pushed.get() != true) return
                pushed.remove()
                popPronunciationWordRenderContext()
            }
        }, scope)
        log(
            "online-translation pronunciation-words render hook installed: " +
                "${method.declaringClass.name}#${method.name}/${method.parameterCount}",
        )
        return true
    }

    /**
     * HLE's `buildApplePronunciationWordRenderContext`: align the line's
     * pronunciation to the native main words and key each slice by the word's
     * stable identity. `lastVisibleSegment` keeps the trailing separator off the
     * final visible word, exactly as HLE does.
     */
    private fun buildPronunciationWordRenderContext(
        vector: Any,
        plan: PronunciationRenderPlan,
    ): PronunciationWordRenderContext? {
        val wordIdName = nativeNames[AppleMusicRuntimeMember.LYRICS_NATIVE_WORD_ID_METHOD]
            ?: return null
        val beginName = nativeNames[AppleMusicRuntimeMember.LYRICS_NATIVE_BEGIN_METHOD]
            ?: return null
        val endName = nativeNames[AppleMusicRuntimeMember.LYRICS_NATIVE_END_METHOD]
            ?: return null
        val words = vectorItems(vector)
        val contentWords = words.filterNot { word ->
            (call(word, AppleMusicRuntimeMember.LYRICS_NATIVE_WHITESPACE_METHOD) as? Boolean) == true
        }
        val mainWordTexts = withRawRead {
            contentWords.map { word ->
                rawText(word, AppleMusicRuntimeMember.LYRICS_NATIVE_LINE_TEXT_METHOD).orEmpty()
            }
        }
        val segments = ApplePronunciationPolicy.displaySegments(
            pronunciation = plan.pronunciation,
            mainWordTexts = mainWordTexts,
        )
        if (segments.isEmpty()) return null
        val lastVisibleSegment = segments.indexOfLast(String::isNotEmpty)
        val displayTextByWord = LinkedHashMap<PronunciationWordKey, String>(words.size)
        words.forEach { word ->
            pronunciationWordKey(word, wordIdName, beginName, endName)?.let { key ->
                displayTextByWord[key] = ""
            }
        }
        contentWords.forEachIndexed { index, word ->
            val key = pronunciationWordKey(word, wordIdName, beginName, endName)
                ?: return@forEachIndexed
            val segment = segments.getOrNull(index) ?: return@forEachIndexed
            displayTextByWord[key] = when {
                segment.isEmpty() -> ""
                index < lastVisibleSegment -> "$segment "
                else -> segment
            }
        }
        return PronunciationWordRenderContext(displayTextByWord)
    }

    /** HLE's `ApplePronunciationWordRenderContext.displayText`, keyed by identity. */
    private fun pronunciationWordRenderText(word: Any?): String? {
        val context = currentPronunciationWordRenderContext() ?: return null
        val key = pronunciationWordKey(word) ?: return null
        return context.displayTextByWord[key]
    }

    /** HLE's `applePronunciationWordKey`. */
    private fun pronunciationWordKey(word: Any?): PronunciationWordKey? {
        val wordIdName = nativeNames[AppleMusicRuntimeMember.LYRICS_NATIVE_WORD_ID_METHOD]
            ?: return null
        val beginName = nativeNames[AppleMusicRuntimeMember.LYRICS_NATIVE_BEGIN_METHOD]
            ?: return null
        val endName = nativeNames[AppleMusicRuntimeMember.LYRICS_NATIVE_END_METHOD]
            ?: return null
        return pronunciationWordKey(word, wordIdName, beginName, endName)
    }

    private fun pronunciationWordKey(
        word: Any?,
        wordIdName: String,
        beginName: String,
        endName: String,
    ): PronunciationWordKey? {
        if (word == null) return null
        return runCatching {
            PronunciationWordKey(
                wordId = (AppleReflection.call(word, wordIdName) as Number).toInt(),
                begin = (AppleReflection.call(word, beginName) as Number).toInt(),
                end = (AppleReflection.call(word, endName) as Number).toInt(),
            )
        }.getOrNull()
    }

    /**
     * HLE's `emptyApplePronunciationWords`: only ever a container, never a
     * `LyricsWord`. `originalVector?.javaClass ?: mainWords?.javaClass` preserves
     * the exact native vector type; the no-arg constructor exists on
     * `LyricsWordVector` (verified against the 6.5.3 dex).
     */
    private fun emptyPronunciationWords(originalVector: Any?, mainWords: Any?): Any? {
        val vectorClass = originalVector?.javaClass ?: mainWords?.javaClass ?: return null
        return runCatching { AppleReflection.newInstance(vectorClass) }.getOrNull()
    }

    /**
     * The visible-channel proof that the app asks for word-level pronunciation
     * and what we answer:
     * `online-translation pronunciation-words id=… getter=… track=… words=N line=…`.
     * Emitted on the raw logger (not the budgeted `native-write` diagnostic) and
     * de-duplicated per build/getter, so the next device log always carries it.
     */
    private fun reportPronunciationWords(
        line: Any,
        getterName: String,
        parameterCount: Int,
        track: ApplePronunciationWordTrack,
        resolved: Any?,
        renderPlanRegistered: Boolean,
    ) {
        val songId = modelSongId
        val key = "$songId:$getterName/$parameterCount:${track.name}:$renderPlanRegistered"
        if (!wordDiagnosticKeys.add(key)) return
        runCatching {
            val begin = number(call(line, AppleMusicRuntimeMember.LYRICS_NATIVE_BEGIN_METHOD))
            log(
                "online-translation pronunciation-words id=$songId " +
                    "getter=$getterName/$parameterCount track=${track.name} " +
                    "words=${vectorSize(resolved)} line=${begin ?: NONE} " +
                    "renderPlan=$renderPlanRegistered " +
                    "renderAdapter=$wordRenderAdapterAvailable",
            )
        }
    }

    /**
     * The native main-word vector is read raw (HLE's `nativeRawWordVectorText`):
     * our own word-text hook must not answer with a render-scope slice while we
     * are deciding the track.
     */
    private fun rawWordVectorText(vector: Any?): String? = withRawRead {
        vectorItems(vector)
            .joinToString(separator = "") { word ->
                rawText(word, AppleMusicRuntimeMember.LYRICS_NATIVE_LINE_TEXT_METHOD).orEmpty()
            }
            .trim()
            .takeIf(String::isNotEmpty)
    }

    /** HLE's `nativeRenderableWordBegins`: non-whitespace words with a real begin. */
    private fun renderableWordBegins(vector: Any?): List<Int> = withRawRead {
        vectorItems(vector).mapNotNull { word ->
            val isWhitespace =
                (call(word, AppleMusicRuntimeMember.LYRICS_NATIVE_WHITESPACE_METHOD) as? Boolean) == true
            val text = rawText(word, AppleMusicRuntimeMember.LYRICS_NATIVE_LINE_TEXT_METHOD)
                ?.trim()
                .orEmpty()
            val begin = (call(word, AppleMusicRuntimeMember.LYRICS_NATIVE_BEGIN_METHOD) as? Number)
                ?.toInt()
            begin?.takeIf { !isWhitespace && text.isNotEmpty() && it >= 0 }
        }
    }

    private fun vectorItems(vector: Any?, limit: Int = MAX_WORDS): List<Any> {
        if (vector == null) return emptyList()
        return buildList {
            repeat(vectorSize(vector).coerceIn(0, limit)) { index ->
                val item = vectorItem(vector, index)
                if (item != null) add(item)
            }
        }
    }

    private fun rawText(receiver: Any?, member: AppleMusicRuntimeMember): String? = withRawRead {
        call(receiver, member) as? String
    }

    /** HLE's `registerApplePronunciationRenderPlan`; bounded, one-shot per vector. */
    private fun registerPronunciationRenderPlan(vector: Any, pronunciation: String) {
        synchronized(pendingPronunciationRenderPlans) {
            if (pendingPronunciationRenderPlans.size >= MAX_PRONUNCIATION_RENDER_PLANS) {
                pendingPronunciationRenderPlans.clear()
            }
            pendingPronunciationRenderPlans[vector] = PronunciationRenderPlan(pronunciation)
        }
    }

    /** HLE's `consumeApplePronunciationRenderPlan`: identity lookup, removed at once. */
    private fun consumePronunciationRenderPlan(vector: Any): PronunciationRenderPlan? =
        synchronized(pendingPronunciationRenderPlans) {
            pendingPronunciationRenderPlans.remove(vector)
        }

    private fun currentPronunciationWordRenderContext(): PronunciationWordRenderContext? =
        pronunciationWordRenderContexts.get()?.peekLast()

    private fun pushPronunciationWordRenderContext(context: PronunciationWordRenderContext) {
        val stack = pronunciationWordRenderContexts.get()
            ?: ArrayDeque<PronunciationWordRenderContext>().also {
                pronunciationWordRenderContexts.set(it)
            }
        stack.addLast(context)
    }

    private fun popPronunciationWordRenderContext() {
        val stack = pronunciationWordRenderContexts.get() ?: return
        if (stack.isNotEmpty()) stack.removeLast()
        if (stack.isEmpty()) pronunciationWordRenderContexts.remove()
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

        /** HLE's `nativeVectorItems(..., limit = 256)` for word vectors. */
        const val MAX_WORDS = 256

        /** HLE's `AppleLyricsPronunciationState.MAX_RENDER_PLANS`. */
        const val MAX_PRONUNCIATION_RENDER_PLANS = 256

        /** HLE's `hookApplePronunciationWordRendering` return-type filter. */
        const val ARRAY_MAP_CLASS = "android.util.ArrayMap"

        /** Placeholder for a diagnostic field with no language/line to report. */
        const val NONE = "none"

        /**
         * The fork's verified lyrics-RecyclerView accessor, used when the
         * profile pins no `LYRICS_UI_ON_CREATE_VIEW#LYRICS_UI_RECYCLER_VIEW_METHOD`
         * (the 1606 profile leaves that point empty). `LyricsTypefaceSession`,
         * `TabletLyricTypography` and HLE's inherited 6.5.x target all resolve
         * the same name.
         */
        const val FALLBACK_RECYCLER_VIEW_METHOD = "getRecyclerView"

        /** `state=` tokens: whether the dedupe state was cleared for a retry. */
        const val STATE_CLEARED = "cleared"
        const val STATE_LATCHED = "latched"

        /**
         * HLE emits one full native-model line per build, but the shared
         * per-track budget on this channel is deliberately small; keep the
         * native proof to a couple of lines per track.
         */
        const val MAX_DIAGNOSTIC_LINES = 4
    }
}
