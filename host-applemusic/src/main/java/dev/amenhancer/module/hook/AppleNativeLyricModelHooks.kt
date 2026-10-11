package dev.amenhancer.module.hook

import android.os.Handler
import android.os.Looper
import dev.amenhancer.module.lyrics.online.ApplePronunciationPolicy
import dev.amenhancer.module.lyrics.online.ApplePronunciationTextSource
import dev.amenhancer.module.lyrics.online.ApplePronunciationVisibilityPolicy
import dev.amenhancer.module.lyrics.online.ApplePronunciationWordTrack
import dev.amenhancer.module.lyrics.online.LyricsRenderProbe
import dev.amenhancer.module.lyrics.online.NativeLyricModelPolicy
import dev.amenhancer.module.lyrics.online.NativeLyricOverlayStore
import dev.amenhancer.module.lyrics.online.OnlineTranslationContentPolicy
import dev.amenhancer.module.lyrics.online.PresentationRefreshOutcome
import dev.amenhancer.module.lyrics.online.PronunciationLaneSource
import dev.amenhancer.module.lyrics.online.PronunciationPayloadLane
import dev.amenhancer.module.lyrics.online.PronunciationPayloadOutcome
import dev.amenhancer.module.lyrics.online.RomanizationPolicy
import dev.amenhancer.module.lyrics.online.TrackScopedDiagnostics
import io.github.proify.lyricon.amprovider.xposed.AppleMusicHookPoint
import io.github.proify.lyricon.amprovider.xposed.AppleMusicHookProfiles
import io.github.proify.lyricon.amprovider.xposed.AppleMusicHookResolver
import io.github.proify.lyricon.amprovider.xposed.AppleMusicRuntimeMember
import io.github.proify.lyricon.amprovider.xposed.AppleReflection
import io.github.proify.lyricon.amprovider.xposed.expandAppleLyricsPronunciationLanguages
import io.github.proify.lyricon.amprovider.xposed.expandAppleLyricsTranslationLanguages
import io.github.proify.lyricon.amprovider.xposed.selectAppleLyricsTranslationLanguage
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
 *    siblings) advertise the available lanes: the online translation lane for
 *    translation, and **Apple's own** pronunciation for pronunciation. The
 *    Mandarin rule still returns false for a hidden song. There is no
 *    third-party pronunciation lane any more, so a song for which Apple itself
 *    exposes no pronunciation shows no romanization at all.
 *  - The preferred-language request arrays are expanded with
 *    `ja-Latn`/`ko-Latn`/`zh-Latn` (HLE's
 *    `hookAppleLyricsPreferredLanguages`), and the official pronunciation
 *    language match returns only a language Apple itself advertises (the system
 *    match when it is a Latin tag, else the first Apple Latin tag).
 *  - `setPronunciation` is handed Apple's own advertised Latin language whenever
 *    the song offers one, and nothing otherwise. The third-party fallback
 *    (`und-Latn`) has been removed: an empty/absent `getPronunciationLanguages`
 *    vector now means "Apple has not advertised a lane yet", so the selection
 *    stays open ([pronunciationSelectionOpen]) and every later entry point
 *    re-runs the decision until Apple's own lane appears. The choice is
 *    deliberately not gated on the per-line `getHtmlPronunciationLineText` probe:
 *    on 1606 that getter stays empty until a pronunciation language has already
 *    been selected, so gating Apple's lane behind it would select nothing. The
 *    HLE probe is still evaluated, per call as HLE does, for the availability
 *    override and the diagnostic.
 *  - The selection is open and self-correcting: a transient empty read keeps the
 *    last non-empty Apple advertisement, and every later entry point — a hook on
 *    the song's `getPronunciationLanguages`, the availability overrides, the
 *    pronunciation line getter and the preferred-language request — re-runs the
 *    decision, so Apple's own lane is picked up as soon as Apple advertises it.
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
 *  - A late-lane ask is the third trigger: Apple's own pronunciation lane often
 *    becomes available *after* the build (`native-write … officialAtBuild=false`
 *    then `true`, while the gate's own line already said `rebound`). A song with
 *    Apple's lyrics and no online source has no overlay write, so nothing asked
 *    again and the romanization only appeared after backgrounding. The
 *    `getPronunciationLanguages` query hook and the availability override (both
 *    the app's own reads, after the build) call [onPronunciationLaneReady], which
 *    dedupes on the (song, lane, per-line probe) edge and requests the same
 *    guarded refresh, tagged `trigger=lane-ready`. A lane-ready that coincides
 *    with a build for the same lane state is a no-op
 *    (`PresentationRefreshState.sameLaneRefresh`); the build gate never consumes
 *    the edge because it only reads [pronunciationLaneRevision].
 *  - The custom-lyrics half of that report is the fifth trigger: a module
 *    supplement document carries its **own** pronunciation text, the build gate
 *    deliberately skips a supplement pointer, and the completion feed's
 *    post-overlay ask only runs when the enricher actually published a merged
 *    document (a document that already carries its translation publishes none).
 *    Three things can therefore reach the payload for a supplement, all
 *    **payload-only** ([performPresentationRefresh]: the document is already
 *    installed, so the heavy re-present/rebind is skipped while the app's own
 *    pronunciation payload still runs last):
 *      * the build ask itself ([requestPresentationRefreshAfterBuild] runs the
 *        payload before its `detail=supplement` early return), so the very first
 *        frame can set `n1.e`;
 *      * [onOfficialLaneConfirmed], when our **own** selection is what confirmed
 *        the lane (`selection=apple reason=apple-lane`), tagged
 *        `trigger=lane-confirmed`;
 *      * [onPronunciationLaneReady], which admits a supplement for the app's own
 *        advertisement/probe edge.
 *    The payload lane is resolved from the model actually presenting the lyrics
 *    ([NativeLyricModelPolicy.pronunciationPayloadLane]): a lane Apple advertised
 *    or we selected, or — when Apple exposed the **text** but named no language —
 *    a synthesized neutral Latin tag ([probeConfirmedPronunciationLanguage],
 *    HLE's `thirdPartyPronunciationFallbackLanguage()` semantics; the content
 *    stays Apple's own getter output). A supplement that confirms no lane is
 *    unchanged (`payload=skipped(custom-no-lane)`). The synthesized tag is a
 *    **payload-lane name and diagnostic only**: it is never handed to the native
 *    `setPronunciation`, which *clears the pronunciations it parsed* when the
 *    language "was not found in the ttml" (`libandroidappmusic.so`). A document
 *    this module writes instead names its lane for its own song, per
 *    [NativeLyricModelPolicy.transliterationLanguageTag].
 *  - Apple's own **word** vector is the only word-level source: a word-timing
 *    line whose line-level `getHtmlPronunciationLineText` Apple left empty used
 *    to render our online lane on the main word timing (device log:
 *    `getPronunciationWords … track=MAIN_LINE_TIMING` beside
 *    `getHtmlPronunciationLineText … official=false online=true`).
 *    [ApplePronunciationPolicy.planPronunciationWords] now returns Apple's own
 *    aligned word vector as `OFFICIAL`, and `HIDDEN` otherwise; the third-party
 *    text lane is gone, so no romanization is substituted.
 *  - A late **word-ready** ask is the fourth trigger: the word track is decided
 *    per `getPronunciationWords()` call and the app caches the vector, so a line
 *    that first answered `HIDDEN` and later answers `OFFICIAL` calls
 *    [onPronunciationWordReady], which dedupes per song
 *    ([NativeLyricModelPolicy.pronunciationWordReadyKey], recorded before the
 *    ask) and reuses the same guarded refresh, tagged `trigger=word-ready`. That
 *    bounds the late-Apple-word correction to at most one extra re-presentation
 *    per track.
 *  - The app's own pronunciation preference is honoured: the 1606 profile now
 *    pins `ja.i0#m(boolean)` and `APPLE_SHARED_PREFERENCES_CLASS` (`g`/`s`/`k`/
 *    `h`/`d`), [AppleLyricsPreferenceReader] ports HLE's cache-first
 *    `PreferencesMonitor` read, and the preference hook runs HLE's
 *    `onAppleLyricsDisplayPreferenceChanged(PRONUNCIATION)` — clear the pending
 *    render plans and re-present — so a toggle is visible on the same frame
 *    instead of the old `enabled && !mandarinHidden` substitute.
 *  - A latched refresh also reproduces the mechanism a background→foreground
 *    cycle uses: the app's own `PlayerLyricsViewModel#loadLyrics` is re-invoked
 *    with the exact (view model, PlaybackItem) pair Apple itself passed
 *    (`installNativeLoadSeam`; HLE's `AppleLyricsPlaybackBinding` and its
 *    `recoverBlankNativeLyricsPage`). An in-place re-presentation re-binds rows
 *    whose bound data was computed from the pre-write model; Apple's own load
 *    builds a fresh model, which is what actually made the lane appear. The
 *    reload is attempted only for Apple's own document (`sourceIsApple`) — on a
 *    module supplement pointer HLE deliberately lets the supplement path own its
 *    re-presentation — and is bounded to once per (song, overlay revision, lane
 *    revision) by `lastReloadKey`, so our own reload can never loop back through
 *    the build gate. `reload=` on the `presentation-refresh` line records the
 *    decision and the outcome.
 *  - `render-probe` lines prove the app re-read the overrides after a refresh
 *    attempt: the line getter, the word getter and the app's word-render adapter
 *    report a deduped, per-track-bounded `online-translation render-probe
 *    phase=… result=… afterRefresh=true` line, so the next exported log answers
 *    "did the app actually re-render" without a rebuild.
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

    /**
     * True when the exact profile pins the Apple pronunciation-preference setter
     * (1606: `ja.i0#m(boolean)`). [applePronunciationSelected] then reads the real
     * preference; a profile without the surface keeps the old fail-open behaviour.
     */
    private val pronunciationPreferenceAvailable =
        AppleMusicHookProfiles.exactTargets(
            resolver.version,
            AppleMusicHookPoint.LYRICS_PRONUNCIATION_PREFERENCE,
        ).isNotEmpty()

    private val lineSongIds =
        Collections.synchronizedMap(IdentityHashMap<Any, Long>())

    @Volatile
    private var modelSongId: Long = 0L

    @Volatile
    private var systemLyricsLanguage: String? = null

    @Volatile
    private var applePronunciationLanguages: List<String> = emptyList()

    @Volatile
    private var mandarinHidden: Boolean = false

    /** The song object the last successful native lane selections were applied to. */
    @Volatile
    private var selectedPronunciationSong: java.lang.ref.WeakReference<Any>? = null

    @Volatile
    private var selectedPronunciationLanguage: String? = null

    @Volatile
    private var selectedTranslationSong: java.lang.ref.WeakReference<Any>? = null

    @Volatile
    private var selectedTranslationLanguage: String? = null

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

    /**
     * The lane-ready edge, part of every [PresentationRefreshState]: bumped once
     * per (song, Apple lane) by [onPronunciationLaneReady] from a seam that
     * runs *after* Apple advertises its lane — its own `getPronunciationLanguages`
     * read or an availability query. The build gate reads the current value but
     * never bumps it, so the gate cannot consume the edge: a gate that ran before
     * the advertisement records the old revision and the later lane-ready ask is
     * a new state, while a lane-ready coinciding with a gate that already saw the
     * same lane state is suppressed by
     * [PresentationRefreshState.sameLaneRefresh]. Reset with the rest of the
     * per-song state on a track change.
     */
    @Volatile
    private var pronunciationLaneRevision: Long = 0L

    /**
     * The (song, Apple lane) the last lane-ready edge was counted for.
     * [pronunciationLaneRevision] is only bumped when this changes, so the app's
     * repeated `getPronunciationLanguages` reads during one bind can never
     * request a refresh per read (the anti-thrash half of the lane trigger).
     */
    @Volatile
    private var laneReadyKey: String? = null

    /**
     * The (song, lane, probe) edge of the last accepted **lane-confirmed** ask —
     * our own selection handing an Apple lane to `setPronunciation` and Apple
     * accepting it on a module supplement pointer. It is a separate one-shot from
     * [laneReadyKey] because the app's own `getPronunciationLanguages` read never
     * happens for that confirmation: the selection runs inside
     * `pronunciationSelectionGuard`, so the availability override deliberately does
     * not count it, and nothing else re-asks. Recorded before the request, so the
     * re-presentation cannot re-arm it. Reset with the rest of the per-song state
     * on a track change.
     */
    @Volatile
    private var laneConfirmedKey: String? = null

    /**
     * The word-ready edge: the per-song key of the last accepted late-word-vector
     * ask ([NativeLyricModelPolicy.pronunciationWordReadyKey]). Recorded *before*
     * the refresh is requested, so the rebuild our own refresh triggers can never
     * bump it again — the anti-loop half of the word trigger. Reset with the rest
     * of the per-song state on a track change.
     */
    @Volatile
    private var wordReadyKey: String? = null

    /**
     * True once a word getter answered a line with `HIDDEN` for the current
     * model, i.e. Apple's own aligned word vector was not available yet. The
     * first later OFFICIAL answer for the model is the "Apple's words arrived
     * late" edge. Per model, reset on a track change.
     */
    @Volatile
    private var wordDecisionSawHidden: Boolean = false

    /**
     * The last value the app's own pronunciation setter pushed (HLE's
     * `PreferencesMonitor.pronunciationSnapshot`). Wins over a cold DataStore read
     * in [AppleLyricsPreferenceReader], so the preference hook's result is visible
     * immediately.
     */
    @Volatile
    private var pronunciationPreferenceSnapshot: Boolean? = null

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
     * The app's own `PlayerLyricsViewModel#loadLyrics` (the 1606 profile's
     * `LYRICS_VIEW_MODEL_LOAD`) and the last (view model, PlaybackItem) pair the
     * app handed it — HLE's `AppleLyricsPlaybackBinding`. Re-invoking that exact
     * pair is how this fork reproduces the fresh model a background→foreground
     * cycle builds; nothing is synthesized because the profile pins only
     * `parameterCount=1`, never a parameter type.
     *
     * The pair is remembered from Apple's own load (the `before` hook below) and
     * is independent of the visible fragment/pointer ticket, exactly as HLE
     * documents: a load may legitimately bring an old page up to the current
     * queue. Null until the load seam resolves and Apple loads once.
     */
    @Volatile
    private var lyricsLoadMethod: Method? = null

    @Volatile
    private var loadViewModelRef: java.lang.ref.WeakReference<Any>? = null

    @Volatile
    private var loadPlaybackItemRef: java.lang.ref.WeakReference<Any>? = null

    /**
     * The last (song, overlay revision) the app's `loadLyrics` was invoked for on
     * the refresh path. The hard anti-loop stop: our reload never advances the
     * overlay revision, so recording the key *before* the invoke makes any
     * re-entrant build caused by our own load a `skipped(same-revision)` no-op.
     */
    @Volatile
    private var lastReloadKey: ReloadKey? = null

    /** A load was invoked and the fresh model must trigger the pending presentation. */
    @Volatile
    private var reloadAwaitingPresentation: ReloadKey? = null

    /**
     * The visible-channel proof that the app re-read our overrides after a
     * refresh attempt (`online-translation render-probe …`). Armed on the main
     * handler immediately before the app's presentation is re-invoked, bounded
     * per track and de-duplicated per attempt.
     */
    private val renderProbe = LyricsRenderProbe(log)

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
     * HLE's "the adapter reads its flags once at bind" tail, the fork's second
     * half of the pronunciation fix: after the re-presentation and the rebind,
     * the app's own `n1.i` payload is replayed so a row bound while `n1.e` was
     * still `false` reveals Apple's romanization without a background→foreground
     * cycle. The adapter field (`p0` on 1606) and the flag (`e`) are the profile
     * pinned `LYRICS_UI_ON_CREATE_VIEW#LYRICS_UI_ADAPTER_FIELD` /
     * `LYRICS_RECYCLER_ADAPTER#LYRICS_ADAPTER_PRONUNCIATION_SELECTED_FIELD`
     * names, with the report's verified literals as the fallback so a profile
     * that leaves those points empty still resolves the 1606 shape. Every step
     * is fail-open.
     */
    private val pronunciationPayload = AppleLyricsPronunciationPayload(
        adapterFieldNames = lyricsUiMemberNames(AppleMusicRuntimeMember.LYRICS_UI_ADAPTER_FIELD)
            .ifEmpty { listOf(FALLBACK_LYRICS_ADAPTER_FIELD) },
        adapterFlagFieldNames = lyricsAdapterMemberNames(
            AppleMusicRuntimeMember.LYRICS_ADAPTER_PRONUNCIATION_SELECTED_FIELD,
        ).ifEmpty { listOf(FALLBACK_PRONUNCIATION_SELECTED_FIELD) },
        adapterItemCountMemberNames = lyricsAdapterMemberNames(
            AppleMusicRuntimeMember.LYRICS_ADAPTER_ITEM_COUNT_METHOD,
        ),
    )

    /**
     * The last (song, overlay revision, official lane) the build gate decided.
     * A repeated build with the same state is never refreshed twice, which is
     * what stops the refresh from looping through our own build hook: the
     * re-presentation rebuilds the model, the gate holds again, and the recorded
     * state makes the second pass a no-op.
     */
    /** Adapter rows can appear after the presentation callback; retry a few frames. */
    private var adapterRetryAttempts = 0

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
     * its content changes — [LANE_READY] is the late pronunciation lane becoming
     * available *after* the build (the reported "Apple's own romanization only
     * after backgrounding"), [LANE_CONFIRMED] is our **own** selection handing an
     * Apple lane to `setPronunciation` and Apple accepting it on a module
     * supplement pointer (the failing custom-lyrics log's
     * `selection=apple reason=apple-lane`, which used to arrive after the only
     * build ask and re-arm nothing), and [F2_RETRY] is the native-presentation
     * (R2/F2) seam re-dispatching an accepted refresh whose first attempt could
     * not reach the page.
     */
    private enum class PresentationRefreshTrigger(val token: String) {
        BUILD("build"),
        CUSTOM_OVERLAY("custom-overlay"),
        LANE_READY("lane-ready"),
        LANE_CONFIRMED("lane-confirmed"),
        WORD_READY("word-ready"),
        PREFERENCE("preference"),
        F2_RETRY("f2-retry"),
    }

    /**
     * One accepted refresh decision. The whole gate input set is part of the
     * key, so any signal that can change after a build — a new overlay revision,
     * Apple advertising its lane, the supplement pointer resolving to Apple's
     * document, the Mandarin rule — is a new state, while an unchanged re-build
     * is not. [trigger] names which of the triggers decided it, so a custom
     * overlay write and a build for the same revision are distinct states and
     * neither can swallow the other's refresh.
     *
     * [laneRevision] is the lane-ready edge: it is bumped once per (song, lane)
     * by [onPronunciationLaneReady] from a seam that runs *after* Apple
     * advertises its lane (the `getPronunciationLanguages` query hook or the
     * availability override). The build gate reads it but never bumps it, so a
     * gate that ran before the advertisement carries the old revision and the
     * later lane-ready ask is a different state, while a lane-ready that
     * coincides with a gate that already saw the same lane is the same state and
     * is a no-op (`sameLaneRefresh`).
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
        val laneRevision: Long,
    ) {
        /**
         * Equality of everything the *lane-ready* trigger dedupes on, i.e. the
         * whole state minus the trigger. Two asks with the same lane state must
         * not refresh twice even when one is the build gate and the other the
         * late lane-ready edge; the trigger stays part of full equality so the
         * custom-overlay ask is never swallowed by a build for the same
         * revision.
         */
        fun sameLaneRefresh(other: PresentationRefreshState): Boolean =
            copy(trigger = other.trigger) == other
    }

    /**
     * The reload's hard dedupe: one app `loadLyrics` per
     * (song, overlay revision, lane revision).
     *
     * The two content revisions that can make a *new* lane appear are the overlay
     * write and the late lane-ready edge, so a reload for an unchanged pair could
     * not change the outcome and is exactly what would loop — our own reload
     * triggers a build, and the build would otherwise ask for a reload again.
     * [laneRevision] is included because the lane-ready ask must be able to
     * rebuild the model *after* the build gate already reloaded the same overlay
     * revision; it is bumped only on a real lane edge, so the bound is a bounded
     * number of reloads per track, not a loop.
     */
    private data class ReloadKey(
        val songId: Long,
        val overlayRevision: Long,
        val laneRevision: Long,
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
        installNativeLoadSeam()
        installNativeModelSeam()
        installNativePresentationSeam()
        installLyricsViewFragmentSeam()
        installPronunciationPreferenceHook()
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

    /** The `LYRICS_UI_ON_CREATE_VIEW` fragment members of [member], in profile order. */
    private fun lyricsUiMemberNames(member: AppleMusicRuntimeMember): List<String> =
        AppleMusicHookProfiles
            .exactTargets(resolver.version, AppleMusicHookPoint.LYRICS_UI_ON_CREATE_VIEW)
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
     * HLE's `LYRICS_VIEW_MODEL_LOAD` binding (`AppleLyricsPlaybackBinding`): the
     * app calls `PlayerLyricsViewModel#loadLyrics(PlaybackItem)` to build the
     * model, and that exact pair is what a reload must re-invoke. Remembering
     * Apple's own argument is what makes the reload safe on a profile that pins
     * only `parameterCount=1`: the instance already matches the method's
     * parameter type, so no signature is invented and no queue id is
     * reconstructed. A missing receiver retains the previous view model, as HLE's
     * binding does. Fail-open: an unresolved point or a throwing capture never
     * affects Apple's own load.
     */
    private fun installNativeLoadSeam() {
        val method = runCatching {
            resolver.resolveMethod(AppleMusicHookPoint.LYRICS_VIEW_MODEL_LOAD).method
        }.getOrNull() ?: run {
            log(
                "online-translation presentation-refresh reload unavailable on " +
                    resolver.version.displayName,
            )
            return
        }
        lyricsLoadMethod = method
        ModernXposedRuntime.hookMethod(method, object : ModernMethodHook() {
            override fun beforeHookedMethod(param: MethodHookParam) {
                runCatching {
                    val item = param.args.getOrNull(0) ?: return@runCatching
                    param.thisObject?.let { viewModel ->
                        loadViewModelRef = java.lang.ref.WeakReference(viewModel)
                    }
                    loadPlaybackItemRef = java.lang.ref.WeakReference(item)
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
        // The module's own document romanization is a supplement-only lane: an
        // Apple-sourced song keeps Apple's own lanes and never reads the overlay.
        val onlinePronunciation = supplementPronunciationLane(songId)
        val pronunciationSelected = enabled && !mandarinHidden && applePronunciationSelected()
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
            // Read, never bumped: the gate must not consume the lane-ready edge.
            // If Apple already advertised before this build, the revision matches
            // the later lane-ready ask and `sameLaneRefresh` suppresses it.
            laneRevision = pronunciationLaneRevision,
        )
        val reason = refreshReason(state)
        val shouldRefresh = NativeLyricModelPolicy.shouldRefreshPresentationAfterBuild(
            sourceIsApple = sourceIsApple,
            hasValidOfficialPronunciation = officialPronunciation,
            hasOnlineTranslation = onlineTranslation,
            hasOnlinePronunciation = onlinePronunciation,
            pronunciationSelected = pronunciationSelected,
        )
        // A build caused by our own reload is the completion barrier for that
        // reload. It must be allowed through even though its state equals the
        // original refresh request; otherwise the fresh model would never be
        // presented and only the old pointer would be rebound.
        val reloadKey = ReloadKey(
            songId = state.songId,
            overlayRevision = state.overlayRevision,
            laneRevision = state.laneRevision,
        )
        if (reloadAwaitingPresentation == reloadKey) {
            reloadAwaitingPresentation = null
            if (lastPresentationRefreshState == state) {
                lastPresentationRefreshState = null
            }
        }
        // An unchanged state has already been decided (and, when accepted,
        // refreshed). This is what keeps the re-presentation's own build from
        // looping: the gate holds again but the state matches.
        val lastState = lastPresentationRefreshState
        if (state == lastState) return
        // The lane-ready reload builds synchronously inside the attempt that
        // latched it, so the gate runs again with the same (bumped) lane revision
        // and could ask for a second refresh of the state that was just applied.
        // Only a preceding edge trigger for this exact lane state is suppressed —
        // a preceding custom-overlay stays a distinct state, as before.
        if (lastState != null &&
            (lastState.trigger == PresentationRefreshTrigger.LANE_READY ||
                lastState.trigger == PresentationRefreshTrigger.LANE_CONFIRMED ||
                lastState.trigger == PresentationRefreshTrigger.WORD_READY ||
                lastState.trigger == PresentationRefreshTrigger.PREFERENCE) &&
            state.sameLaneRefresh(lastState)
        ) {
            return
        }
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
            reload = reloadIntent(state, shouldRefresh),
            trigger = PresentationRefreshTrigger.BUILD.token,
        )
        if (!shouldRefresh) {
            // The supplement skip exists to avoid Apple's track refresh (the
            // I2/reload twitch), not to skip the payload. When the custom model
            // already exposes Apple's own pronunciation text (or a lane) and the
            // user wants it, the guaranteed payload replay is exactly what the
            // first frame needs — the only other path was the backgrounding the
            // user reported. `performPresentationRefresh` treats a supplement
            // build ask as payload-only: no `w2`, no rebind, payload last. An ask
            // that is already pending (the same state, or a preserved
            // custom-overlay ask that does the full refresh) is left alone.
            if (pendingPresentationRefresh == null &&
                !sourceIsApple &&
                officialPronunciation &&
                pronunciationSelected
            ) {
                pendingPresentationRefresh = state
                adapterRetryAttempts = 0
                mainHandler.post {
                    performPresentationRefresh(state, PresentationRefreshTrigger.BUILD)
                }
            }
            return
        }

        pendingPresentationRefresh = state
        adapterRetryAttempts = 0
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
        // The module's own document romanization is a supplement-only lane: an
        // Apple-sourced song keeps Apple's own lanes and never reads the overlay.
        val onlinePronunciation = supplementPronunciationLane(songId)
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
            // The build gate's Apple-lane rule never decides this trigger, but the
            // model's own lane is still a reason to re-present when a completion
            // added no translation: the supplemental payload then needs the same
            // guaranteed dispatch. A dead model ref fails to false and hides
            // nothing.
            officialPronunciation = songNative != null &&
                (hasValidOfficialPronunciation(songNative) || officialLane != null),
            officialLane = officialLane,
            onlineTranslation = onlineTranslation,
            onlinePronunciation = onlinePronunciation,
            pronunciationSelected = enabled && !mandarinHidden && applePronunciationSelected(),
            laneRevision = pronunciationLaneRevision,
        )
        // Once per (song, overlay revision): a completion that changed no overlay
        // content recomputes the same state and is a no-op.
        if (state == lastPresentationRefreshState) return
        lastPresentationRefreshState = state
        if (pendingPresentationRefresh != state) pendingPresentationRefresh = null

        val policyRefresh = NativeLyricModelPolicy.shouldRefreshPresentationAfterCustomOverlay(
            hasOnlineTranslation = onlineTranslation,
            hasOnlinePronunciation = onlinePronunciation,
        )
        // A completion that added no translation still carries the document's own
        // lane; the overlay rule alone would suppress the ask that dispatches the
        // payload. The lane is only acted on when the user wants pronunciation,
        // exactly like the build gate's official-lane branch.
        val laneRefresh = state.officialPronunciation && state.pronunciationSelected
        val shouldRefresh = policyRefresh || laneRefresh
        val reason = if (laneRefresh && !policyRefresh) {
            NativeLyricModelPolicy.REFRESH_REASON_CUSTOM_LANE
        } else {
            refreshReason(state)
        }
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
            reload = reloadIntent(state, shouldRefresh),
            trigger = PresentationRefreshTrigger.CUSTOM_OVERLAY.token,
        )
        if (!shouldRefresh) return

        pendingPresentationRefresh = state
        adapterRetryAttempts = 0
        mainHandler.post {
            performPresentationRefresh(state, PresentationRefreshTrigger.CUSTOM_OVERLAY)
        }
    }

    /**
     * The late-lane trigger: the pronunciation lane became available *after* the
     * model build, on Apple's own document or on a module supplement (custom
     * lyrics) document.
     *
     * The build gate (HLE's `shouldRefreshPresentationAfterBuild`) runs at the
     * same instant as the build. The device log's
     * `presentation-refresh … detail=rebound trigger=build` at `.208/.241` sits
     * beside `native-write … officialAtBuild=false` at `.198`: the model exists
     * before the per-line lane is populated, and nothing asks again. A song with
     * an online source is rescued by the completion feed's overlay write seconds
     * later, but a song with Apple's own lyrics and no source has no overlay
     * write at all, so its only remaining trigger is a background→foreground
     * cycle that re-creates the page — the user's report.
     *
     * The same report has a custom-lyrics half: a supplement document that
     * already carries its translation produces no merged document, so the
     * post-overlay ask never runs, and the build gate deliberately skips a
     * supplement pointer — leaving its own `<transliterations>` lane with no ask
     * at all.
     *
     * This is called from the two seams that run *after* the lane exists and on
     * the app's own read, never ours:
     *  - the `getPronunciationLanguages` query hook, when Apple itself asks and
     *    gets a non-empty vector (the advertisement *is* the lane), and
     *  - the `hasPronunciation`/`setPronunciation` availability override, which
     *    already evaluates the per-line probe on every call, so the
     *    `officialAtBuild=false → true` transition is caught even when the
     *    advertised language never changed.
     *
     * Exactly the custom-overlay ask's guarantees, plus the lane edge:
     *  - main-handler posted, so it lands after Apple's current presentation;
     *  - once per (song, lane, probe) edge — the edge key is
     *    [NativeLyricModelPolicy.pronunciationLaneReadyKey] — via the shared
     *    [lastPresentationRefreshState], compared with
     *    [PresentationRefreshState.sameLaneRefresh] so a lane-ready that
     *    coincides with a gate for the *same* lane state does not refresh twice,
     *    while a gate that ran before the lane appeared carries the old
     *    [pronunciationLaneRevision] and cannot swallow the later ask;
     *  - expected-song checked here and again against the bound pointer in
     *    [performPresentationRefresh];
     *  - our own invoke is ignored through [presentationInvokeGuard], and the
     *    reload it performs is bounded by [ReloadKey] (which includes
     *    [pronunciationLaneRevision]), so the re-presentation's build cannot
     *    re-enter this trigger and our own reload cannot loop;
     *  - a module supplement (custom lyrics) pointer is included, because its
     *    document carries its **own** `<transliterations>` lane and every other
     *    trigger deliberately skips it: the build gate skips it (Apple's track
     *    refresh twitches the page) and the post-overlay ask only runs when the
     *    completion feed actually wrote a lane. A custom document that already
     *    carries its translation produces no merged document, so it had **no**
     *    ask at all and its romanization waited for the page to be re-created.
     *    The supplement keeps its own rules: it is marked `sourceIsApple=false`
     *    (so the reload stays `skipped(supplement-pointer)` and Apple's track is
     *    never re-resolved), its edge key carries a supplement prefix so a lane
     *    edge already consumed for Apple's own document cannot swallow it, and
     *    [performPresentationRefresh] still dispatches the payload last.
     */
    private fun onPronunciationLaneReady(
        songNative: Any?,
        officialLane: String?,
        officialProbe: Boolean,
    ) {
        if (!enabled || nativeNames.isEmpty()) return
        if (presentationInvokeGuard.get() == true) return
        if (songNative == null) return
        val songId = nativeSongId(songNative)
        if (songId <= 0L || !isCurrentSong(songNative)) return
        // A supplement document's lane is its own; the ask is allowed, the reload
        // (which only ever touches Apple's document) stays skipped downstream.
        val supplement = isModuleSupplementSong(songId)
        if (!officialProbe && officialLane == null) return
        val key = NativeLyricModelPolicy.pronunciationLaneReadyKey(
            songId = songId,
            officialLane = officialLane,
            hasValidOfficialPronunciation = officialProbe,
        ).let { base -> if (supplement) "$SUPPLEMENT_LANE_KEY_PREFIX$base" else base }
        // The anti-thrash half: the app reads `getPronunciationLanguages` and
        // the per-line availability many times during one bind; only a *new*
        // (lane, probe) edge bumps the revision and asks for a refresh.
        if (key == laneReadyKey) return
        laneReadyKey = key
        pronunciationLaneRevision += 1

        val onlineTranslation = enabled && overlay.hasTranslation(songId.toString())
        // The module's own document romanization is a supplement-only lane: an
        // Apple-sourced song keeps Apple's own lanes and never reads the overlay.
        val onlinePronunciation = supplementPronunciationLane(songId)
        val state = PresentationRefreshState(
            trigger = PresentationRefreshTrigger.LANE_READY,
            songId = songId,
            overlayRevision = overlay.revision(),
            // A supplement pointer is never reloaded through Apple; only Apple's
            // own document takes the reload half of this trigger.
            sourceIsApple = !supplement,
            officialPronunciation = officialProbe || officialLane != null,
            officialLane = officialLane,
            onlineTranslation = onlineTranslation,
            onlinePronunciation = onlinePronunciation,
            pronunciationSelected = enabled && !mandarinHidden && applePronunciationSelected(),
            laneRevision = pronunciationLaneRevision,
        )
        // The lane state, not the trigger, is the dedupe key for this ask: a
        // build that already refreshed this exact lane state must not be
        // repeated. `laneRevision` makes a later appearance a different state.
        val last = lastPresentationRefreshState
        if (last != null && state.sameLaneRefresh(last)) return
        lastPresentationRefreshState = state
        // A newer lane state supersedes an accepted-but-unapplied ask; a pending
        // custom-overlay ask is preserved for its own next revision, exactly as
        // the build gate preserves it.
        if (pendingPresentationRefresh?.trigger != PresentationRefreshTrigger.CUSTOM_OVERLAY) {
            pendingPresentationRefresh = null
        }

        val reason = refreshReason(state)
        logPresentationRefresh(
            songId = songId,
            reason = reason,
            hasValidOfficialPronunciation = state.officialPronunciation,
            onlineTranslation = onlineTranslation,
            onlinePronunciation = onlinePronunciation,
            pronunciationSelected = state.pronunciationSelected,
            refreshed = false,
            detail = DETAIL_LANE_READY,
            adapterName = null,
            stateCleared = false,
            // The latched attempt re-invokes the app's own `loadLyrics` for
            // Apple's own document (the fresh-model path a background→foreground
            // cycle uses); a supplement pointer is never reloaded. `laneRevision`
            // is part of the reload key, so it is exactly once per lane edge even
            // when the build gate already reloaded for the same overlay revision.
            reload = reloadIntent(state, shouldRefresh = true),
            trigger = PresentationRefreshTrigger.LANE_READY.token,
        )
        pendingPresentationRefresh = state
        adapterRetryAttempts = 0
        mainHandler.post {
            performPresentationRefresh(state, PresentationRefreshTrigger.LANE_READY)
        }
    }

    /**
     * The just-confirmed-lane trigger: our **own** selection handed Apple's own
     * lane to `setPronunciation` and Apple accepted it (`selection=apple
     * reason=apple-lane`) for a module supplement (custom lyrics) pointer.
     *
     * This is the failing device log's exact hole: the custom track's only refresh
     * was the build ask, which `detail=supplement` deliberately skips before the
     * lane is known; the lane was then confirmed by our own selection, and because
     * the availability override's lane-ready edge is suppressed while
     * `pronunciationSelectionGuard` is set, **nothing re-asked** — so the
     * guaranteed payload dispatch never got a chance to run and the romanization
     * waited for a background→foreground page re-creation.
     *
     * Only a supplement pointer asks here: Apple's own document already has the
     * build and lane-ready triggers. Guarantees match [onPronunciationLaneReady]:
     * main-handler posted; expected-song checked here and again against the bound
     * pointer in [performPresentationRefresh]; our own invoke ignored through
     * [presentationInvokeGuard]; one-shot per (song, lane, probe) via
     * [laneConfirmedKey], recorded before the request. The attempt itself is
     * payload-only (see [performPresentationRefresh]): the custom document is
     * already installed, so the heavy re-present/rebind is skipped and only the
     * app's own pronunciation payload is replayed. An ask that is already pending
     * is left to resolve the lane at its own payload step, which is also what
     * stops the confirmation our own refresh's ensure step makes from re-arming
     * this trigger.
     */
    private fun onOfficialLaneConfirmed(songNative: Any, language: String) {
        if (!enabled || nativeNames.isEmpty()) return
        if (presentationInvokeGuard.get() == true) return
        if (pendingPresentationRefresh != null) return
        val songId = nativeSongId(songNative)
        if (songId <= 0L || !isCurrentSong(songNative)) return
        if (!isModuleSupplementSong(songId)) return
        val officialLane = language
            .takeIf { NativeLyricModelPolicy.isOfficialPronunciationLanguage(it) }
            ?: return
        val officialProbe = hasValidOfficialPronunciation(songNative)
        val key = NativeLyricModelPolicy.pronunciationLaneReadyKey(
            songId = songId,
            officialLane = officialLane,
            hasValidOfficialPronunciation = officialProbe,
        )
        if (key == laneConfirmedKey) return
        laneConfirmedKey = key
        pronunciationLaneRevision += 1

        val state = PresentationRefreshState(
            trigger = PresentationRefreshTrigger.LANE_CONFIRMED,
            songId = songId,
            overlayRevision = overlay.revision(),
            sourceIsApple = false,
            officialPronunciation = true,
            officialLane = officialLane,
            onlineTranslation = enabled && overlay.hasTranslation(songId.toString()),
            // A supplement document's own romanization is the module's lane; an
            // Apple-sourced song never reads the overlay for pronunciation.
            onlinePronunciation = supplementPronunciationLane(songId),
            pronunciationSelected = enabled && !mandarinHidden && applePronunciationSelected(),
            laneRevision = pronunciationLaneRevision,
        )
        if (state == lastPresentationRefreshState) return
        lastPresentationRefreshState = state

        logPresentationRefresh(
            songId = songId,
            reason = refreshReason(state),
            hasValidOfficialPronunciation = state.officialPronunciation,
            onlineTranslation = state.onlineTranslation,
            onlinePronunciation = state.onlinePronunciation,
            pronunciationSelected = state.pronunciationSelected,
            refreshed = false,
            detail = DETAIL_LANE_CONFIRMED,
            adapterName = null,
            stateCleared = false,
            reload = reloadIntent(state, shouldRefresh = true),
            trigger = PresentationRefreshTrigger.LANE_CONFIRMED.token,
        )
        pendingPresentationRefresh = state
        adapterRetryAttempts = 0
        mainHandler.post {
            performPresentationRefresh(state, PresentationRefreshTrigger.LANE_CONFIRMED)
        }
    }

    /**
     * The late-word-vector trigger: Apple's own pronunciation words became
     * available *after* the first word getter had already answered with our lane.
     *
     * The word track is decided inside `getPronunciationWords()`, and the app
     * caches the vector it gets back while the row binds, so nothing re-reads the
     * decision on its own. The first OFFICIAL answer after our lane had to stand in
     * for a line (`announceWordReadyIfAppleWordsArrived`) is that edge; this asks
     * for the same guarded re-presentation as [onPronunciationLaneReady], tagged
     * `trigger=word-ready`.
     *
     * Guarantees, identical to the lane-ready ask:
     *  - main-handler posted, so it lands after Apple's current presentation;
     *  - Apple's own document only (a supplement pointer's lanes are the
     *    overlay's job);
     *  - expected-song checked here and again against the bound pointer in
     *    [performPresentationRefresh];
     *  - our own invoke is ignored through [presentationInvokeGuard];
     *  - deduped per song by [NativeLyricModelPolicy.pronunciationWordReadyKey]
     *    and recorded *before* the ask, so the rebuild our own refresh triggers
     *    can never bump it again — the hard anti-loop bound. A model whose first
     *    word answer is already OFFICIAL never asks. The bumped
     *    [pronunciationLaneRevision] is part of [ReloadKey], so the reload is
     *    still exactly once per edge.
     */
    private fun onPronunciationWordReady() {
        if (!enabled || nativeNames.isEmpty()) return
        if (presentationInvokeGuard.get() == true) return
        val songNative = songNativeRef?.get() ?: return
        val songId = nativeSongId(songNative)
        if (songId <= 0L || !isCurrentSong(songNative)) return
        if (isModuleSupplementSong(songId)) return
        val key = NativeLyricModelPolicy.pronunciationWordReadyKey(songId)
        if (key == wordReadyKey) return
        wordReadyKey = key
        pronunciationLaneRevision += 1

        val advertised = advertisedPronunciationLanguages(songPronunciationLanguages(songNative))
        val officialLane = NativeLyricModelPolicy.officialPronunciationLanguage(advertised)
        val onlineTranslation = enabled && overlay.hasTranslation(songId.toString())
        // The module's own document romanization is a supplement-only lane: an
        // Apple-sourced song keeps Apple's own lanes and never reads the overlay.
        val onlinePronunciation = supplementPronunciationLane(songId)
        val state = PresentationRefreshState(
            trigger = PresentationRefreshTrigger.WORD_READY,
            songId = songId,
            overlayRevision = overlay.revision(),
            sourceIsApple = true,
            // The edge *is* Apple's own word vector having arrived.
            officialPronunciation = true,
            officialLane = officialLane,
            onlineTranslation = onlineTranslation,
            onlinePronunciation = onlinePronunciation,
            pronunciationSelected = enabled && !mandarinHidden && applePronunciationSelected(),
            laneRevision = pronunciationLaneRevision,
        )
        val last = lastPresentationRefreshState
        if (last != null && state.sameLaneRefresh(last)) return
        lastPresentationRefreshState = state
        if (pendingPresentationRefresh?.trigger != PresentationRefreshTrigger.CUSTOM_OVERLAY) {
            pendingPresentationRefresh = null
        }

        val reason = refreshReason(state)
        logPresentationRefresh(
            songId = songId,
            reason = reason,
            hasValidOfficialPronunciation = state.officialPronunciation,
            onlineTranslation = onlineTranslation,
            onlinePronunciation = onlinePronunciation,
            pronunciationSelected = state.pronunciationSelected,
            refreshed = false,
            detail = DETAIL_WORD_READY,
            adapterName = null,
            stateCleared = false,
            reload = reloadIntent(state, shouldRefresh = true),
            trigger = PresentationRefreshTrigger.WORD_READY.token,
        )
        pendingPresentationRefresh = state
        adapterRetryAttempts = 0
        mainHandler.post {
            performPresentationRefresh(state, PresentationRefreshTrigger.WORD_READY)
        }
    }

    /**
     * HLE's `PreferencesMonitor.notifyPronunciationSelectedChanged`: record the
     * value Apple itself just wrote, then run the pronunciation display event —
     * `clearPendingApplePronunciationRenderPlans()` followed by
     * `refreshAppleLyricsSupplementPresentation()`. The fork had neither, so a
     * preference toggle was invisible and the gate kept using the substitute.
     */
    internal fun onPronunciationPreferenceChanged(selected: Boolean) {
        pronunciationPreferenceSnapshot = selected
        if (!enabled || nativeNames.isEmpty()) return
        synchronized(pendingPronunciationRenderPlans) {
            pendingPronunciationRenderPlans.clear()
        }
        mainHandler.post { requestPreferencePresentationRefresh() }
    }

    /**
     * The preference trigger. HLE refreshes unconditionally here — the changed
     * preference *is* the reason — and the shared state dedupe makes a repeated
     * setter call with the same value a no-op while a genuine toggle is always a
     * new state ([PresentationRefreshState.pronunciationSelected]). The refresh
     * re-runs `applyAppleNativePronunciationSelection`, so Apple's own lane is
     * re-applied with the new preference on the same frame.
     */
    private fun requestPreferencePresentationRefresh() {
        if (presentationInvokeGuard.get() == true) return
        val songNative = songNativeRef?.get() ?: return
        val songId = nativeSongId(songNative)
        if (songId <= 0L || songId != modelSongId) return
        val advertised = advertisedPronunciationLanguages(songPronunciationLanguages(songNative))
        val officialLane = NativeLyricModelPolicy.officialPronunciationLanguage(advertised)
        val onlineTranslation = enabled && overlay.hasTranslation(songId.toString())
        // The module's own document romanization is a supplement-only lane: an
        // Apple-sourced song keeps Apple's own lanes and never reads the overlay.
        val onlinePronunciation = supplementPronunciationLane(songId)
        val state = PresentationRefreshState(
            trigger = PresentationRefreshTrigger.PREFERENCE,
            songId = songId,
            overlayRevision = overlay.revision(),
            sourceIsApple = !isModuleSupplementSong(songId),
            officialPronunciation = hasValidOfficialPronunciation(songNative) || officialLane != null,
            officialLane = officialLane,
            onlineTranslation = onlineTranslation,
            onlinePronunciation = onlinePronunciation,
            pronunciationSelected = enabled && !mandarinHidden && applePronunciationSelected(),
            laneRevision = pronunciationLaneRevision,
        )
        if (state == lastPresentationRefreshState) return
        lastPresentationRefreshState = state
        if (pendingPresentationRefresh != state &&
            pendingPresentationRefresh?.trigger != PresentationRefreshTrigger.CUSTOM_OVERLAY
        ) {
            pendingPresentationRefresh = null
        }

        val reason = refreshReason(state)
        logPresentationRefresh(
            songId = songId,
            reason = reason,
            hasValidOfficialPronunciation = state.officialPronunciation,
            onlineTranslation = onlineTranslation,
            onlinePronunciation = onlinePronunciation,
            pronunciationSelected = state.pronunciationSelected,
            refreshed = false,
            detail = DETAIL_PREFERENCE,
            adapterName = null,
            stateCleared = false,
            reload = reloadIntent(state, shouldRefresh = true),
            trigger = PresentationRefreshTrigger.PREFERENCE.token,
        )
        pendingPresentationRefresh = state
        adapterRetryAttempts = 0
        mainHandler.post {
            performPresentationRefresh(state, PresentationRefreshTrigger.PREFERENCE)
        }
    }

    /**
     * The [PresentationRefreshState]'s reason, from the trigger that decided it:
     * the build gate keeps HLE's `sourceIsApple`/online/official rule, while the
     * post-overlay trigger uses the store-update rule (a lane exists in the
     * overlay). A late-lane ask on a module supplement is the custom path's own
     * edge — the custom document's model confirmed a lane after the build — so it
     * is named `custom-lane` rather than the build gate's `none`. One place, so a
     * log can never name a branch the decision did not take.
     */
    private fun refreshReason(state: PresentationRefreshState): String =
        when (state.trigger) {
            PresentationRefreshTrigger.CUSTOM_OVERLAY ->
                NativeLyricModelPolicy.customOverlayRefreshReason(
                    hasOnlineTranslation = state.onlineTranslation,
                    hasOnlinePronunciation = state.onlinePronunciation,
                )
            PresentationRefreshTrigger.LANE_READY,
            PresentationRefreshTrigger.LANE_CONFIRMED,
            PresentationRefreshTrigger.WORD_READY -> if (!state.sourceIsApple) {
                NativeLyricModelPolicy.REFRESH_REASON_CUSTOM_LANE
            } else {
                NativeLyricModelPolicy.presentationRefreshReason(
                    sourceIsApple = state.sourceIsApple,
                    hasValidOfficialPronunciation = state.officialPronunciation,
                    hasOnlineTranslation = state.onlineTranslation,
                    hasOnlinePronunciation = state.onlinePronunciation,
                    pronunciationSelected = state.pronunciationSelected,
                )
            }
            PresentationRefreshTrigger.BUILD -> if (!state.sourceIsApple) {
                // A supplement's build ask is its payload-only lane replay.
                NativeLyricModelPolicy.REFRESH_REASON_CUSTOM_LANE
            } else {
                NativeLyricModelPolicy.presentationRefreshReason(
                    sourceIsApple = state.sourceIsApple,
                    hasValidOfficialPronunciation = state.officialPronunciation,
                    hasOnlineTranslation = state.onlineTranslation,
                    hasOnlinePronunciation = state.onlinePronunciation,
                    pronunciationSelected = state.pronunciationSelected,
                )
            }
            PresentationRefreshTrigger.PREFERENCE,
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
     * A module supplement (custom lyrics) **lane** ask is payload-only: its
     * document is already installed and the heavy re-present/rebind is what HLE
     * reports as the lyrics-page twitch on a supplement pointer, so only the
     * pronunciation payload is replayed (last, as always). The completion feed's
     * `custom-overlay` ask is the exception and keeps the full sequence, because
     * only its I2 substitution installs a freshly published merged document.
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

        fun finish(
            outcome: PresentationRefreshOutcome,
            adapterName: String?,
            payload: AppleLyricsPronunciationPayload.Result? = null,
            lane: PronunciationPayloadLane? = null,
        ) {
            if (outcome.latches) {
                if (pendingPresentationRefresh == state) pendingPresentationRefresh = null
            } else if (lastPresentationRefreshState == state) {
                lastPresentationRefreshState = null
            }
            // A latched attempt is the only place the app's own `loadLyrics` is
            // re-invoked: re-running Apple's load is how a background→foreground
            // cycle builds the fresh model that made the lane appear, which an
            // in-place re-presentation of the pre-write model cannot. Aborts stay
            // retryable and are re-dispatched by the binding seam instead of
            // reloading from a half-applied attempt.
            val reload = if (outcome.latches) {
                reloadLyricsForRefresh(state)
            } else {
                reloadSkipped(outcome)
            }
            // A supplement (custom lyrics) document that confirmed no lane has
            // nothing to reveal; naming that case keeps the next log from reading
            // like a generic "no lane for Apple's own document" miss.
            val customNoLane = lane != null && !lane.confirmed &&
                payload?.outcome == PronunciationPayloadOutcome.NOT_AVAILABLE &&
                isModuleSupplementSong(state.songId)
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
                reload = reload,
                trigger = trigger.token,
                payload = if (customNoLane) PAYLOAD_CUSTOM_NO_LANE else payloadToken(payload),
                adapterE = adapterFlagTransition(payload),
                lane = lane?.language ?: NONE,
                laneSource = lane?.source?.token ?: PronunciationLaneSource.NONE.token,
            )
            if (outcome == PresentationRefreshOutcome.ADAPTER_UNAVAILABLE) {
                if (adapterRetryAttempts < MAX_ADAPTER_RETRY_ATTEMPTS) {
                    adapterRetryAttempts += 1
                    mainHandler.postDelayed(
                        {
                            if (pendingPresentationRefresh == state) {
                                performPresentationRefresh(state, trigger)
                            }
                        },
                        ADAPTER_RETRY_DELAY_MS,
                    )
                }
            } else if (outcome == PresentationRefreshOutcome.REBOUND ||
                outcome == PresentationRefreshOutcome.PAYLOAD_ONLY
            ) {
                adapterRetryAttempts = 0
            }
        }

        // A module supplement (custom lyrics) pointer's lane ask is payload-only:
        // its document is already installed, and re-invoking Apple's presentation
        // and rebinding the adapter is the lyrics-page twitch HLE attributes to a
        // supplement pointer's track refresh. The completion feed's own ask
        // (`CUSTOM_OVERLAY`) keeps the full sequence, because only its I2
        // substitution installs a freshly published merged document.
        val payloadOnly = !state.sourceIsApple &&
            trigger != PresentationRefreshTrigger.CUSTOM_OVERLAY
        val method = presentationMethod
        if (!payloadOnly && method == null) {
            return finish(PresentationRefreshOutcome.NO_PRESENTATION_METHOD, adapterName = null)
        }
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
            applyAppleNativeTranslationSelection(songNative)
            applyAppleNativePronunciationSelection(songNative, songPronunciationLanguages(songNative))
        }.onFailure { error ->
            log("online-translation presentation-refresh selection failed: ${error.message}")
        }

        // The visible-channel proof that the app re-reads the model after this
        // attempt: from here on the line/word getters and the word render adapter
        // report a `render-probe afterRefresh=true` line whenever the app asks
        // again, so the next exported log answers "did the app re-render".
        renderProbe.arm(state.songId)

        val invoke = if (payloadOnly) {
            true
        } else {
            val attempt = runCatching {
                presentationInvokeGuard.set(true)
                try {
                    method?.invoke(fragment, pointer)
                } finally {
                    presentationInvokeGuard.remove()
                }
            }
            attempt.onFailure { error ->
                log("online-translation presentation-refresh invoke failed: ${error.message}")
                finish(PresentationRefreshOutcome.INVOKE_FAILED, adapterName = null)
            }
            attempt.isSuccess
        }
        if (!invoke) return

        // HLE only rebinds after a successful re-presentation, and only on the
        // next frame while the layout manager is busy. A missing view/adapter
        // keeps the invoke latched but is reported as `adapter-unavailable`.
        // A payload-only supplement attempt never re-presents, so there is
        // nothing to rebind: the payload below resolves the live adapter itself.
        val rebound = if (payloadOnly) {
            AppleLyricsPresentationRebind.Result(didNotify = true, adapterName = null)
        } else {
            runCatching { presentationRebind.rebind(fragment) }
                .getOrElse { error ->
                    log("online-translation presentation-refresh rebind failed: ${error.message}")
                    AppleLyricsPresentationRebind.Result(didNotify = false, adapterName = null)
                }
        }

        // The last step, deliberately: `F2` (inside the re-presentation above)
        // recomputes `n1.e` from Apple's own vector and dispatches its payload
        // with `e=false` when the lane is not advertised yet, and the rebind's
        // full `notifyDataSetChanged` cannot repair a row whose cached strings
        // are unchanged. Replaying the app's own pronunciation payload after both
        // is what makes a row bound while `e` was false re-read the flag and
        // reveal Apple's romanization. It only runs for a confirmed official
        // lane the user wants, and every step is fail-open.
        //
        // A module supplement (custom lyrics) document is parsed into its **own**
        // model, so a lane is confirmed from that model: the lane this ask
        // resolved, the lane our selection handed to `setPronunciation`, the
        // model's live advertisement, or a line that already reads its
        // romanization. `laneSrc=` on the log line names which one; a supplement
        // that confirmed none prints `payload=skipped(custom-no-lane)` rather than
        // showing an empty pronunciation row.
        val payloadLane = runCatching {
            NativeLyricModelPolicy.pronunciationPayloadLane(
                stateOfficialLane = state.officialLane,
                selectedLane = selectedPronunciationLaneForCurrentSong(),
                advertisedLane = advertisedPronunciationLaneForCurrentSong(songNative),
                hasOfficialLineProbe = hasValidOfficialPronunciation(songNative),
                // The probe-confirmed branch synthesizes a neutral Latin tag from
                // this when Apple advertised no language name: the text is
                // Apple's own, the tag only lets the app reveal the track.
                systemLyricsLanguage = systemLyricsLanguage,
            )
        }.getOrElse { error ->
            log("online-translation presentation-refresh lane resolution failed: ${error.message}")
            NativeLyricModelPolicy.pronunciationPayloadLane(
                stateOfficialLane = null,
                selectedLane = null,
                advertisedLane = null,
                hasOfficialLineProbe = false,
            )
        }
        val payload = runCatching {
            pronunciationPayload.apply(
                fragment = fragment,
                hasConfirmedOfficialLane = payloadLane.confirmed,
                pronunciationSelected = state.pronunciationSelected,
            )
        }.getOrElse { error ->
            log("online-translation presentation-refresh pronunciation payload failed: ${error.message}")
            AppleLyricsPronunciationPayload.Result(
                outcome = PronunciationPayloadOutcome.ADAPTER_UNAVAILABLE,
                adapterName = null,
                before = false,
                after = false,
            )
        }
        // A payload-only attempt has no re-presentation to succeed: it latches
        // when the payload surface was reachable at all, and keeps the
        // `adapter-unavailable` retry only when the live adapter itself could not
        // be resolved.
        val outcome = when {
            payloadOnly && payload.outcome == PronunciationPayloadOutcome.ADAPTER_UNAVAILABLE ->
                PresentationRefreshOutcome.ADAPTER_UNAVAILABLE
            payloadOnly -> PresentationRefreshOutcome.PAYLOAD_ONLY
            rebound.didNotify -> PresentationRefreshOutcome.REBOUND
            else -> PresentationRefreshOutcome.ADAPTER_UNAVAILABLE
        }
        finish(
            outcome = outcome,
            adapterName = if (payloadOnly) payload.adapterName else rebound.adapterName,
            payload = payload,
            lane = payloadLane,
        )
    }

    /** The `payload=` token of one attempt: the outcome, or `not-available` for an abort. */
    private fun payloadToken(payload: AppleLyricsPronunciationPayload.Result?): String =
        payload?.outcome?.token ?: PronunciationPayloadOutcome.NOT_AVAILABLE.token

    /** The `adapterE=` field: the flag before and after, or `none->none` for an abort. */
    private fun adapterFlagTransition(payload: AppleLyricsPronunciationPayload.Result?): String =
        if (payload?.adapterName == null) {
            "$NONE->$NONE"
        } else {
            "${payload.before}->${payload.after}"
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
     *
     * `reload=` extends it with the fresh-model half: the decision line prints
     * `requested` (the attempt will re-invoke the app's `loadLyrics`) or
     * `skipped(gate|supplement-pointer|same-revision)`, and the attempt line
     * prints the real `invoked`/`failed`/`skipped(<why>)`. Together with the
     * `render-probe` lines they answer "did the app rebuild the model and then
     * re-read our getters" from the exported log alone.
     *
     * `payload=`/`adapterE=` extend it with the pronunciation fix's own proof:
     * `payload=dispatched adapterE=false->true` on the first play is the app's
     * own pronunciation payload having reached the live adapter, while
     * `skipped(not-available|preference-off|already-true|adapter-unavailable)`
     * names exactly why it did not. Decision lines (no attempt yet) print
     * `payload=skipped(not-available) adapterE=none->none`.
     *
     * `lane=`/`laneSrc=` name the evidence that confirmed the lane for a payload
     * attempt: the language the ask already resolved (`state`), the lane our
     * selection handed to `setPronunciation` (`confirmed`), the model's live
     * advertisement (`advertised`), or a line that already reads its
     * romanization (`line-probe`). A module supplement (custom lyrics) track
     * that confirmed none prints `payload=skipped(custom-no-lane)`, so "this
     * document has no Apple transliteration to show" is distinguishable from
     * "the payload was never reached".
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
        reload: String,
        trigger: String,
        payload: String = PronunciationPayloadOutcome.NOT_AVAILABLE.token,
        adapterE: String = "$NONE->$NONE",
        lane: String = NONE,
        laneSource: String = PronunciationLaneSource.NONE.token,
    ) {
        log(
            "online-translation presentation-refresh id=$songId reason=$reason " +
                "officialPronunciation=$hasValidOfficialPronunciation " +
                "onlineTranslation=$onlineTranslation " +
                "onlinePronunciation=$onlinePronunciation " +
                "pronunciationSelected=$pronunciationSelected " +
                "presentationMethod=${presentationMethod != null} " +
                "refreshed=$refreshed detail=$detail reload=$reload trigger=$trigger " +
                "adapter=${adapterName ?: NONE} " +
                "state=${if (stateCleared) STATE_CLEARED else STATE_LATCHED} " +
                "payload=$payload adapterE=$adapterE lane=$lane laneSrc=$laneSource",
        )
    }

    /**
     * The reload decision printed on the `presentation-refresh` *decision* line,
     * before the main-handler attempt runs. `requested` means the attempt, when
     * it latches, will re-invoke the app's own `loadLyrics`; every other token is
     * the condition that keeps it from being asked for.
     */
    private fun reloadIntent(
        state: PresentationRefreshState,
        shouldRefresh: Boolean,
    ): String = when {
        !state.sourceIsApple -> RELOAD_SKIPPED_SUPPLEMENT
        !shouldRefresh -> RELOAD_SKIPPED_GATE
        lastReloadKey == ReloadKey(
            state.songId,
            state.overlayRevision,
            state.laneRevision,
        ) -> RELOAD_SKIPPED_SAME_REVISION
        else -> RELOAD_REQUESTED
    }

    /** `reload=skipped(<detail>)` for an attempt that never reached the page. */
    private fun reloadSkipped(outcome: PresentationRefreshOutcome): String =
        "$RELOAD_SKIPPED_PREFIX${outcome.token})"

    /**
     * Reproduces the fresh model a background→foreground cycle builds: re-invokes
     * the app's own `PlayerLyricsViewModel#loadLyrics` with the exact (view model,
     * PlaybackItem) pair Apple itself passed (HLE's `AppleLyricsPlaybackBinding`).
     * HLE does the same in its blank-page recovery (`recoverBlankNativeLyricsPage`)
     * and only for Apple's own document, so a module supplement pointer is
     * deliberately skipped here: re-running Apple's load on the module's own
     * document would ask Apple to re-resolve/replace it, and HLE explains the
     * supplement path owns its own re-presentation ("Apple 的 R2 轨道刷新会反复重绑
     * adapter 并引起歌词页抽搐"). The fork's existing re-presentation + adapter
     * rebind is that supplement path's equivalent and still runs, unchanged, on
     * every accepted refresh.
     *
     * Runs on the main handler after the presentation invoke has returned (so
     * [presentationInvokeGuard] is clear), checks the expected song against the
     * remembered item, is bounded to once per (song, overlay revision, lane
     * revision) by [lastReloadKey], and is fully fail-open. The key is recorded
     * before the invoke, so a synchronous build from our own load can never ask
     * for a second reload.
     */
    private fun reloadLyricsForRefresh(state: PresentationRefreshState): String {
        if (!state.sourceIsApple) return RELOAD_SKIPPED_SUPPLEMENT
        if (presentationInvokeGuard.get() == true) return RELOAD_SKIPPED_INVOKE_GUARD
        val key = ReloadKey(state.songId, state.overlayRevision, state.laneRevision)
        if (lastReloadKey == key) return RELOAD_SKIPPED_SAME_REVISION
        val method = lyricsLoadMethod ?: return RELOAD_SKIPPED_NO_METHOD
        val viewModel = loadViewModelRef?.get() ?: return RELOAD_SKIPPED_NO_VIEW_MODEL
        val item = loadPlaybackItemRef?.get() ?: return RELOAD_SKIPPED_NO_PLAYBACK_ITEM
        val itemSongId = playbackItemSongId(item)
        if (itemSongId != 0L && itemSongId != state.songId) {
            return RELOAD_SKIPPED_STALE_ITEM
        }
        lastReloadKey = key
        reloadAwaitingPresentation = key
        return runCatching {
            method.invoke(viewModel, item)
        }.fold(
            onSuccess = { RELOAD_INVOKED },
            onFailure = { error ->
                reloadAwaitingPresentation = null
                log("online-translation presentation-refresh reload failed: ${error.message}")
                RELOAD_FAILED
            },
        )
    }

    /**
     * The remembered `PlaybackItem`'s song id, from HLE's own member
     * (`LYRICS_SONG_ID_METHOD`, `getId` on 1606). Used only to reject a stale
     * binding; an item that exposes no id still reloads, exactly as HLE's
     * recovery does.
     */
    private fun playbackItemSongId(item: Any?): Long {
        val value = call(item, AppleMusicRuntimeMember.LYRICS_SONG_ID_METHOD)
        return (value as? Number)?.toLong() ?: value?.toString()?.toLongOrNull() ?: 0L
    }

    /**
     * Feeds one app read of an overridden getter/adapter to the render probe. The
     * line timing is read here from the app's own line object, so the probe line
     * is comparable with the `native-write`/`presentation-refresh` diagnostics.
     */
    private fun recordRenderProbe(
        phase: LyricsRenderProbe.Phase,
        getter: String,
        line: Any?,
        official: Boolean,
        online: Boolean,
        result: String,
    ) {
        val songId = modelSongId
        if (songId <= 0L) return
        // Skip the reflective line read on the ordinary (unarmed) render: the
        // probe only matters once a refresh attempt is in flight.
        if (!renderProbe.isArmed(songId)) return
        renderProbe.record(
            phase = phase,
            songId = songId,
            getter = getter,
            line = line?.let {
                number(call(it, AppleMusicRuntimeMember.LYRICS_NATIVE_BEGIN_METHOD))
            },
            official = official,
            online = online,
            result = result,
        )
    }

    private fun ensureNativeModel(songNative: Any, viewModel: Any?) {
        val songId = nativeSongId(songNative)
        val modelChanged = songId != modelSongId
        if (modelChanged) {
            // A new track: never let the previous song's lane, selection,
            // fallback language, or one-shot word render plan leak into this one.
            applePronunciationLanguages = emptyList()
            selectedPronunciationSong = null
            selectedPronunciationLanguage = null
            selectedTranslationSong = null
            selectedTranslationLanguage = null
            systemLyricsLanguage = null
            synchronized(pendingPronunciationRenderPlans) {
                pendingPronunciationRenderPlans.clear()
            }
            pronunciationSelectionOpen = false
            // The lane-ready edge is per song: the new track's own advertisement
            // must be able to request its own refresh, and must not inherit the
            // previous track's revision.
            pronunciationLaneRevision = 0L
            laneReadyKey = null
            // The lane-confirmed edge is per song too: the new track's own
            // accepted selection must be able to ask its own refresh.
            laneConfirmedKey = null
            // The word-ready edge is per song too: the new track's own late word
            // vector must be able to ask its own refresh, and must not inherit the
            // previous track's ask (the hard per-song bound).
            wordReadyKey = null
            wordDecisionSawHidden = false
        }
        modelSongId = songId
        songNativeRef = java.lang.ref.WeakReference(songNative)
        val languageViewModel = viewModel ?: loadViewModelRef?.get()
        if (languageViewModel != null) {
            systemLyricsLanguage = call(
                languageViewModel,
                AppleMusicRuntimeMember.LYRICS_VIEW_MODEL_CURRENT_LANGUAGE_METHOD,
            ) as? String
        }
        val languages = songPronunciationLanguages(songNative)
        mandarinHidden = isMandarinHidden(languages)

        installSongAvailabilityHooks(songNative.javaClass)
        installPronunciationLanguageQueryHook(songNative.javaClass)
        val lines = nativeLines(songNative)
        if (modelChanged) lineSongIds.clear()
        lines.forEach { line -> lineSongIds[line] = songId }
        // HLE-compatible per-call probe as the build-time reading, for the
        // diagnostic and for the "did Apple populate the lines yet" signal. It is
        // not the decision input: on 1606 it is false until a language is
        // selected, which is exactly why the selection may not gate Apple's own
        // lane behind it. The count is kept so the log can show a lane the
        // selection pass destroyed (34 -> 0) rather than only `true -> false`.
        val officialAtBuildLines = officialPronunciationLineCount(songNative)
        lines.map { it.javaClass }.distinct().forEach(::installLineTextHooks)
        installPronunciationWordHooks(lines, clearPendingPlans = modelChanged)
        applyAppleNativeTranslationSelection(songNative)
        val selection = applyAppleNativePronunciationSelection(songNative, languages)
        reportNativeWrite(
            lines = lines,
            officialAtBuild = !mandarinHidden && officialAtBuildLines > 0,
            officialAtBuildLines = officialAtBuildLines,
            officialNow = hasValidOfficialPronunciation(songNative),
            languages = languages,
            // The raw vector *after* the selection pass: it shows whether the lane
            // the document names is now advertised by the model, i.e. whether the
            // per-song `<transliteration xml:lang>` tag became a real lane name.
            advertisedAfterSelection = songPronunciationLanguages(songNative),
            selection = selection,
        )
    }

    /**
     * HLE's native translation selection: choose Apple's language matching the
     * ViewModel system language before the first model/presentation read. The
     * setter is optional on older profiles and a false Boolean is not latched.
     */
    private fun applyAppleNativeTranslationSelection(songNative: Any) {
        val systemLanguage = systemLyricsLanguage?.takeIf(String::isNotBlank) ?: return
        val languages = vectorStrings(
            call(songNative, AppleMusicRuntimeMember.LYRICS_NATIVE_SONG_TRANSLATION_LANGUAGES_METHOD),
        )
        val language = selectAppleLyricsTranslationLanguage(
            systemLanguage = systemLanguage,
            availableLanguages = languages,
        ) ?: systemLanguage
        if (
            language == selectedTranslationLanguage &&
            selectedTranslationSong?.get() === songNative
        ) return
        val name = nativeNames[AppleMusicRuntimeMember.LYRICS_NATIVE_SET_TRANSLATION_METHOD]
            ?: return
        val result = runCatching {
            AppleReflection.call(songNative, name, language)
        }.onFailure { error ->
            log("online-translation native-write selectTranslation failed: ${error.message}")
        }.getOrNull()
        if (result !is Boolean || result) {
            selectedTranslationSong = java.lang.ref.WeakReference(songNative)
            selectedTranslationLanguage = language
        }
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
        return officialPronunciationLineCount(songNative) > 0
    }

    /**
     * The build-time probe's **count**, not only its truth: `native-write` prints
     * it beside the post-selection count, so a lane that our own selection pass
     * destroyed (`probeAtBuildLines=34` then `pronunciationLines=0`) is visible in
     * one line instead of hiding behind two booleans.
     */
    private fun officialPronunciationLineCount(songNative: Any): Int =
        nativeLines(songNative).count(::hasOfficialPronunciationLine)

    /** Same per-line predicate [hasValidOfficialPronunciation] has always used. */
    private fun hasOfficialPronunciationLine(line: Any): Boolean {
        val text = rawLineText(line)
        val pronunciation = withRawRead {
            call(line, AppleMusicRuntimeMember.LYRICS_NATIVE_PRONUNCIATION_TEXT_METHOD) as? String
        }
        return !text.isNullOrBlank() && RomanizationPolicy.sanitize(text, pronunciation) != null
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
                    val language = NativeLyricModelPolicy.officialPronunciationLanguage(languages)
                    applePronunciationLanguages = languages
                    applyAppleNativePronunciationSelection(song, languages)
                    // The app's own read of the advertisement is the lane-ready
                    // edge: this seam runs after the build, so a lane Apple only
                    // advertised later is exactly what nothing used to re-ask
                    // for. The query guard above keeps our own reads out.
                    onPronunciationLaneReady(
                        songNative = song,
                        officialLane = language,
                        officialProbe = language != null,
                    )
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

    /**
     * The Apple pronunciation lane this module has already confirmed for the
     * model currently being delivered, or null when it has confirmed none.
     *
     * This is the `matchToSystemLyricsScript` after-hook's fallback: on 1606
     * `PlayerLyricsViewFragment.F2` reads that method's result to decide the
     * live adapter's pronunciation flag `n1.e`, but Apple's own
     * `getPronunciationLanguages` vector is still empty at the first
     * presentation, so the pure policy yields null and `F2` latches `e=false`
     * even though [applyAppleNativePronunciationSelection] already handed
     * Apple's lane to `setPronunciation`. Returning the lane the module already
     * confirmed lets `F2` see it in the same frame; the app's own setter still
     * has the final say, so a genuinely rejected language yields `false` exactly
     * as today.
     *
     * Cross-track safety: the confirmed selection is only returned while it
     * still belongs to the model this module is delivering ([modelSongId]) and
     * to the exact song instance the native setter accepted
     * ([selectedPronunciationSong]); the advertised fallback only reads the
     * per-model advertisement cache, which is reset on a track change and only
     * written for the current model. A previous track's lane therefore cannot
     * leak into the next one, and a song with no confirmed lane yields null.
     */
    private fun confirmedPronunciationLanguageForCurrentSong(): String? {
        selectedPronunciationLaneForCurrentSong()?.let { return it }
        val songNative = songNativeRef?.get() ?: return null
        if (!isCurrentSong(songNative)) return null
        // Apple's best-known advertisement for this model: the live read when it
        // is non-empty, else the last non-empty one. The cache is per model and
        // guarded by `isCurrentSong`, so it can only describe this song.
        return advertisedPronunciationLaneForCurrentSong(songNative)
    }

    /**
     * The lane this module handed to [applyAppleNativePronunciationSelection]'s
     * `setPronunciation` and Apple accepted, while it still belongs to the model
     * currently being delivered. This is the strongest evidence the payload gate
     * can use: `F2`'s own `z` is derived from the same setter, and Apple's setter
     * has already validated the lane against the document (Apple's own or the
     * custom one). Cross-track safe: the [selectedPronunciationSong] identity
     * check rejects a previous track's selection, and [isCurrentSong] rejects a
     * preloaded next track.
     */
    private fun selectedPronunciationLaneForCurrentSong(): String? {
        val songNative = songNativeRef?.get() ?: return null
        if (!isCurrentSong(songNative)) return null
        if (selectedPronunciationSong?.get() !== songNative) return null
        return selectedPronunciationLanguage
            ?.takeIf { NativeLyricModelPolicy.isOfficialPronunciationLanguage(it) }
    }

    /**
     * [songNative]'s own best-known advertisement of Apple's pronunciation lane
     * for the model currently being delivered. Used by the payload lane
     * resolution, which must consider the model actually presenting the lyrics —
     * for a module supplement (custom lyrics) track that is the model parsed from
     * the user's own document, not the per-song advertisement a build gate read
     * before the document was installed.
     */
    private fun advertisedPronunciationLaneForCurrentSong(songNative: Any): String? {
        if (!isCurrentSong(songNative)) return null
        return NativeLyricModelPolicy.officialPronunciationLanguage(
            advertisedPronunciationLanguages(songPronunciationLanguages(songNative)),
        )
    }

    private fun isMandarinHidden(languages: List<String>): Boolean =
        ApplePronunciationVisibilityPolicy.shouldHide(
            genre = genreFor(modelSongId).orEmpty().takeIf(String::isNotBlank),
            pronunciationLanguages = languages,
            hideMandarinPinyin = hideMandarinPinyin,
        )

    /**
     * The app's real "lyrics pronunciation selected" preference, HLE's
     * `PreferencesMonitor.isPronunciationSelected()`. Fail-open: true when the
     * profile pins no preference target or the value cannot be read, so a profile
     * without the surface never silently disables Apple's own pronunciation. This
     * replaces the fork's `enabled && !mandarinHidden` substitute, which asked
     * HLE's build gate for a refresh even when the user had switched Apple's
     * pronunciation row off (and vice versa: it could not react to a toggle at
     * all).
     */
    private fun applePronunciationSelected(): Boolean {
        if (!pronunciationPreferenceAvailable) return true
        return AppleLyricsPreferenceReader.isPronunciationSelected(
            resolver = resolver,
            snapshot = pronunciationPreferenceSnapshot,
        ) ?: true
    }

    private fun installSongAvailabilityHooks(clazz: Class<*>) {
        listOf(
            AppleMusicRuntimeMember.LYRICS_NATIVE_SET_TRANSLATION_METHOD,
            AppleMusicRuntimeMember.LYRICS_NATIVE_HAS_TRANSLATION_METHOD,
        ).forEach { member ->
            installBooleanAvailability(clazz, member) { song, original ->
                NativeLyricModelPolicy.hasTranslationAvailability(
                    original = original,
                    enabled = enabled,
                    hasOnlineTranslation = enabled && overlay.hasTranslation(
                        nativeSongId(song).takeIf { it > 0L }?.toString()
                            ?: currentSongId(),
                    ),
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
                val officialLane =
                    NativeLyricModelPolicy.officialPronunciationLanguage(advertised)
                val officialProbe = hasValidOfficialPronunciation(song)
                // The app's own availability query is the second lane-ready seam:
                // it runs after the build and re-evaluates the per-line probe, so
                // the `officialAtBuild=false → true` transition is caught even if
                // the advertised language never changed. Our own selection's
                // `setPronunciation` re-enters here; the selection guard keeps it
                // from counting the edge before the build gate has run.
                if (pronunciationSelectionGuard.get() != true) {
                    onPronunciationLaneReady(
                        songNative = song,
                        officialLane = officialLane,
                        officialProbe = officialProbe,
                    )
                }
                NativeLyricModelPolicy.hasPronunciationAvailability(
                    original = original,
                    hasValidOfficialPronunciation = officialProbe || officialLane != null,
                    mandarinHidden = isMandarinHidden(languages),
                    hasOnlinePronunciation = supplementPronunciationLane(
                        nativeSongId(song).takeIf { it > 0L } ?: modelSongId,
                    ),
                )
            }
        }
    }

    /**
     * HLE's `applyAppleNativePronunciationSelection`, Apple-only: hand the app's
     * own setter Apple's advertised Latin language when the song offers one, and
     * nothing otherwise. `setPronunciation` is left untouched when Apple's model
     * advertises no lane yet — the plan is recorded in
     * [pronunciationSelectionOpen] and every later entry point calls this again,
     * so a lane is picked up the moment it appears. A transient empty read never
     * withdraws a lane Apple already advertised
     * ([advertisedPronunciationLanguages]). Skipped entirely while the Mandarin
     * rule hides the song. Returns the plan, for the diagnostic.
     *
     * **Only an advertised language is ever handed to the setter.** A synthesized
     * tag is not: `libandroidappmusic.so`'s
     * `SongInfo::setPronunciation` logs `Can not switch to pronunciation {0}
     * because one was not found in the ttml. Will clear existing pronunciations.`
     * and *clears the pronunciations it parsed* when the language is not one the
     * document carries. A document we write therefore names the lane for its own
     * song ([NativeLyricModelPolicy.transliterationLanguageTag]); a document that
     * names none is left alone and the per-line text Apple did parse is revealed
     * by the payload path, which never touches the model.
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
        )
        // Only a successful Apple setter call may close the selection. A false
        // Boolean means Apple rejected the request and a later app read must
        // retry it.
        val appleLane = plan.selection == NativeLyricModelPolicy.PronunciationSelection.APPLE
        pronunciationSelectionOpen = !appleLane
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
        var confirmedLane = false
        pronunciationSelectionGuard.set(true)
        try {
            val result = runCatching { AppleReflection.call(songNative, name, language) }
                .onFailure { error ->
                    log("online-translation native-write selectPronunciation failed: ${error.message}")
                }
            val accepted = result.isSuccess &&
                (result.getOrNull() !is Boolean || result.getOrNull() == true)
            if (accepted) {
                selectedPronunciationSong = java.lang.ref.WeakReference(songNative)
                selectedPronunciationLanguage = language
                if (appleLane) pronunciationSelectionOpen = false
                confirmedLane = appleLane
            } else {
                pronunciationSelectionOpen = true
            }
        } finally {
            pronunciationSelectionGuard.remove()
        }
        if (confirmedLane) {
            // Our own accepted selection is the only place a supplement pointer's
            // lane confirmation is observable: the availability override's
            // lane-ready edge is deliberately suppressed while
            // `pronunciationSelectionGuard` is set, and no later app read
            // necessarily happens (the failing device log confirmed the lane with
            // `selection=apple reason=apple-lane` *after* the only build ask had
            // been skipped). Ask for the payload refresh here so the custom
            // document's romanization does not wait for the page to be re-created.
            runCatching { onOfficialLaneConfirmed(songNative, language) }.onFailure { error ->
                log("online-translation native-write lane-confirmed ask failed: ${error.message}")
            }
        }
        return plan
    }

    private fun installLineTextHooks(clazz: Class<*>) {
        val translationGetter =
            nativeNames[AppleMusicRuntimeMember.LYRICS_NATIVE_TRANSLATION_TEXT_METHOD]
                ?: "translation"
        val pronunciationGetter =
            nativeNames[AppleMusicRuntimeMember.LYRICS_NATIVE_PRONUNCIATION_TEXT_METHOD]
                ?: "pronunciation"
        installLineGetter(
            clazz = clazz,
            member = AppleMusicRuntimeMember.LYRICS_NATIVE_TRANSLATION_TEXT_METHOD,
        ) { line, original ->
            val official = OnlineTranslationContentPolicy.sanitize(original)
            val replacement = official ?: onlineTranslation(line)
            recordRenderProbe(
                phase = LyricsRenderProbe.Phase.GETTER,
                getter = translationGetter,
                line = line,
                official = official != null,
                online = official == null && replacement != null,
                result = when {
                    official != null -> PROBE_OFFICIAL
                    replacement != null -> PROBE_ONLINE
                    else -> PROBE_EMPTY
                },
            )
            replacement
        }
        installLineGetter(
            clazz = clazz,
            member = AppleMusicRuntimeMember.LYRICS_NATIVE_PRONUNCIATION_TEXT_METHOD,
        ) { line, original ->
            if (mandarinHidden) {
                recordRenderProbe(
                    phase = LyricsRenderProbe.Phase.GETTER,
                    getter = pronunciationGetter,
                    line = line,
                    official = false,
                    online = false,
                    result = PROBE_HIDDEN,
                )
                ""
            } else {
                // The line getter is a later entry point: retry an open
                // selection here so Apple's own lane becomes effective on the
                // very render that needs it.
                refreshPronunciationSelectionIfOpen()
                val text = rawLineText(line)
                val official = RomanizationPolicy.sanitize(text, original)
                val supplement = official == null && hasSupplementPronunciationLine(line, text)
                val resolved = official ?: if (supplement) supplementPronunciation(line, text) else null
                recordRenderProbe(
                    phase = LyricsRenderProbe.Phase.GETTER,
                    getter = pronunciationGetter,
                    line = line,
                    official = official != null,
                    online = supplement,
                    result = when {
                        official != null -> PROBE_OFFICIAL
                        supplement -> PROBE_ONLINE
                        else -> PROBE_EMPTY
                    },
                )
                resolved
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
     * The port is Apple-first, with the module's own displayed document as the
     * one fallback lane for a **supplement** track:
     *
     *  - `getPronunciationWords()` / `getPronunciationBackgroundWords(boolean)`
     *    return Apple's own vector whenever its words carry a sanitized
     *    romanization timed like the main line (`OFFICIAL`).
     *  - When Apple has no usable vector for the line but a text lane exists
     *    (`MAIN_LINE_TIMING`), Apple's **main** word vector is returned and a
     *    one-shot render plan distributes that text over the existing native
     *    words. This is the only way a Word-timed custom document's romanization
     *    reaches the rows on 1606: the app parses the document's
     *    `<transliterations>` track (`languages=ja-Latn`) but never renders it,
     *    so the module serves the document's own text it published into the
     *    overlay. The lane is gated to a supplement track
     *    ([supplementLaneAvailable]); an Apple-sourced song keeps Apple's own
     *    lanes and is never filled from the overlay.
     *  - Otherwise the line is HIDDEN and an empty container is returned, never
     *    the main vector carrying unromanized text.
     *    [ApplePronunciationPolicy.planPronunciationWords] owns that decision and
     *    is JVM-tested; the host only resolves Apple's members and reports the
     *    result.
     *  - The line-level background-vocals pronunciation is Apple's own text,
     *    sanitized, with no fallback — exactly HLE's
     *    `LYRICS_NATIVE_PRONUNCIATION_BACKGROUND_TEXT_METHOD` branch.
     *
     * No native `LyricsWord` is ever synthesized: the adapter dereferences
     * `word.getLyricsLine().get().getLineId()` (verified against the 6.5.3 dex),
     * so a parentless word would break Apple's own rendering — which is why HLE
     * reuses the main vector. Every step fails open: a missing member, a malformed
     * vector or a throwing getter leaves Apple's value untouched, and an
     * unusable official vector degrades to HLE's
     * `emptyApplePronunciationWords(...)` rather than returning the main vector,
     * so the main text can never be rendered as romanization.
     */
    private fun installPronunciationWordHooks(
        lines: List<Any>,
        clearPendingPlans: Boolean,
    ) {
        // Plans belong to a model generation. Re-entering ensureNativeModel for
        // the same model (w2/F2 after build) must not erase a plan waiting for
        // Apple's adapter; only a track/model change may clear it.
        if (clearPendingPlans) {
            synchronized(pendingPronunciationRenderPlans) {
                pendingPronunciationRenderPlans.clear()
            }
        }
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
                mainWordsMember = AppleMusicRuntimeMember.LYRICS_NATIVE_WORDS_METHOD,
                supplementLane = true,
            )
            installPronunciationWordsGetter(
                clazz = clazz,
                member = AppleMusicRuntimeMember.LYRICS_NATIVE_PRONUNCIATION_BACKGROUND_WORDS_METHOD,
                parameterCount = 1,
                originalTextMember = AppleMusicRuntimeMember.LYRICS_NATIVE_BACKGROUND_TEXT_METHOD,
                mainWordsMember = AppleMusicRuntimeMember.LYRICS_NATIVE_BACKGROUND_WORDS_METHOD,
                supplementLane = false,
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
        mainWordsMember: AppleMusicRuntimeMember,
        supplementLane: Boolean,
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
                        mainWordsMember = mainWordsMember,
                        supplementLane = supplementLane,
                        getterName = name,
                        parameterCount = parameterCount,
                    )
                    if (resolved != null) param.result = resolved
                }
            }
        }, scope)
    }

    /**
     * The `hookApplePronunciationWordsGetter` body, Apple-first: Apple's own
     * aligned word vector produces `OFFICIAL`; when Apple leaves the line empty,
     * a text lane (Apple's own line text, or the supplement document's
     * romanization the module published) is rendered on Apple's **main** word
     * vector through a one-shot render plan (`MAIN_LINE_TIMING`). Anything else
     * is `HIDDEN`.
     *
     * The first `OFFICIAL` answer after the fallback lane had to stand in for a
     * line is the late-word-vector edge
     * ([announceWordReadyIfAppleWordsArrived]).
     */
    private fun resolvePronunciationWords(
        line: Any,
        original: Any?,
        args: Array<Any?>,
        originalTextMember: AppleMusicRuntimeMember,
        mainWordsMember: AppleMusicRuntimeMember,
        supplementLane: Boolean,
        getterName: String,
        parameterCount: Int,
    ): Any? {
        if (mandarinHidden) {
            return emptyPronunciationWords(original, null) ?: original
        }
        // Resolved live, not when the hook was installed: the completion feed may
        // publish the document's lane after this model was built.
        val onlineFallback = supplementLane && supplementLaneAvailable(lineSongId(line))
        val originalText = rawText(line, originalTextMember)
        // Apple's own line-level pronunciation text (read raw, so our own line
        // getter override cannot answer with the fallback we are deciding here).
        val officialLineText = RomanizationPolicy.sanitize(
            originalText = originalText,
            pronunciation = rawText(
                line,
                AppleMusicRuntimeMember.LYRICS_NATIVE_PRONUNCIATION_TEXT_METHOD,
            ),
        )
        val onlinePronunciationText = if (onlineFallback) {
            supplementPronunciation(line, originalText)
        } else {
            null
        }
        // Apple's own word vector text (HLE's `nativeRawWordVectorText`): the
        // romanization carried on the pronunciation words themselves. On a
        // word-timing song this is often the only Apple source for the line.
        val officialWordVectorText = RomanizationPolicy.sanitize(
            originalText = originalText,
            pronunciation = rawWordVectorText(original),
        )
        val mainWords = nativeNames[mainWordsMember]?.let { name ->
            runCatching { AppleReflection.call(line, name, *args) }.getOrNull()
        }
        val officialWordsCompatible = ApplePronunciationPolicy.hasCompatibleOfficialWordTiming(
            mainWordBegins = renderableWordBegins(mainWords),
            pronunciationWordBegins = renderableWordBegins(original),
        )
        val plan = ApplePronunciationPolicy.planPronunciationWords(
            officialLineText = officialLineText,
            officialWordVectorText = officialWordVectorText,
            onlineText = onlinePronunciationText,
            officialWordsCompatible = officialWordsCompatible,
        )
        val track = plan.track
        val vector = mainWords?.takeIf { vectorSize(it) > 0 }
        val pronunciation = plan.pronunciation
        val canRegisterRenderPlan =
            track == ApplePronunciationWordTrack.MAIN_LINE_TIMING &&
                vector != null &&
                pronunciation != null &&
                wordRenderAdapterAvailable
        val resolved: Any? = when (track) {
            ApplePronunciationWordTrack.OFFICIAL -> original
            ApplePronunciationWordTrack.MAIN_LINE_TIMING ->
                // The explicit null checks (not `canRegisterRenderPlan`) are what
                // let the compiler smart-cast the two arguments.
                if (vector != null && pronunciation != null && canRegisterRenderPlan) {
                    registerPronunciationRenderPlan(vector, pronunciation)
                    vector
                } else {
                    emptyPronunciationWords(original, mainWords) ?: original
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
            officialWords = vectorSize(original),
            officialWordsCompatible = officialWordsCompatible,
            mainTimingSource = plan.source,
        )
        announceWordReadyIfAppleWordsArrived(track, plan.source, onlineFallback)
        recordRenderProbe(
            phase = LyricsRenderProbe.Phase.WORD_GETTER,
            getter = getterName,
            line = line,
            official = track == ApplePronunciationWordTrack.OFFICIAL,
            online = track == ApplePronunciationWordTrack.MAIN_LINE_TIMING,
            result = track.name,
        )
        return resolved
    }

    /**
     * The word-decision counterpart of [onPronunciationLaneReady]: Apple's own
     * word vector often arrives *after* the first bind, so the first
     * `getPronunciationWords()` answer is `MAIN_LINE_TIMING` with the module's
     * lane standing in. The first `OFFICIAL` answer after that is the edge; it
     * asks for one re-presentation so the rebuilt model re-consults the word
     * getter.
     *
     * Only an `ONLINE`-sourced `MAIN_LINE_TIMING` arms the edge: a line Apple
     * already covers with its own line text is not a substitution, so a mixed
     * song cannot request a needless rebuild. Anti-loop: [onPronunciationWordReady]
     * records the per-song edge key before asking, so the rebuild our own refresh
     * triggers can never bump it again, and a model whose very first word answer
     * is already `OFFICIAL` never asks at all. Only the main
     * `getPronunciationWords` getter participates ([supplementLane]); the
     * background getter's lane is Apple's own with no fallback.
     */
    private fun announceWordReadyIfAppleWordsArrived(
        track: ApplePronunciationWordTrack,
        source: ApplePronunciationTextSource,
        onlineFallback: Boolean,
    ) {
        if (!onlineFallback) return
        if (track == ApplePronunciationWordTrack.OFFICIAL) {
            if (!wordDecisionSawHidden) return
            wordDecisionSawHidden = false
            onPronunciationWordReady()
        } else if (source == ApplePronunciationTextSource.ONLINE) {
            wordDecisionSawHidden = true
        }
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
                    recordRenderProbe(
                        phase = LyricsRenderProbe.Phase.ADAPTER_BIND,
                        getter = method.name,
                        line = null,
                        official = false,
                        online = true,
                        result = PROBE_RENDER_PLAN,
                    )
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
     * `online-translation pronunciation-words id=… getter=… track=… words=N line=…
     * renderAdapter=… officialWords=N compat=… selected=…`.
     *
     * `officialWords` is how many words Apple's own pronunciation vector carries
     * for the line, `compat` whether those word begins align with the main line,
     * and `selected` the resulting Apple-only track (`OFFICIAL`/`HIDDEN`), so the
     * next device log explains *why* the decision went the way it did instead of
     * only which track won. Emitted on the raw logger (not the budgeted
     * `native-write` diagnostic) and de-duplicated per build/getter/decision, so
     * the next device log always carries it without increasing the per-track
     * budget.
     */
    private fun reportPronunciationWords(
        line: Any,
        getterName: String,
        parameterCount: Int,
        track: ApplePronunciationWordTrack,
        resolved: Any?,
        renderPlanRegistered: Boolean,
        officialWords: Int,
        officialWordsCompatible: Boolean,
        mainTimingSource: ApplePronunciationTextSource,
    ) {
        val songId = modelSongId
        val key = "$songId:$getterName/$parameterCount:${track.name}:" +
            "$officialWords:$officialWordsCompatible:$renderPlanRegistered"
        if (!wordDiagnosticKeys.add(key)) return
        runCatching {
            val begin = number(call(line, AppleMusicRuntimeMember.LYRICS_NATIVE_BEGIN_METHOD))
            log(
                "online-translation pronunciation-words id=$songId " +
                    "getter=$getterName/$parameterCount track=${track.name} " +
                    "words=${vectorSize(resolved)} line=${begin ?: NONE} " +
                    "renderAdapter=$wordRenderAdapterAvailable " +
                    "renderPlan=$renderPlanRegistered " +
                    "officialWords=$officialWords compat=$officialWordsCompatible " +
                    "mainTiming=${mainTimingSource.token} " +
                    "selected=${track.name}",
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

    /** HLE's `consumeApplePronunciationRenderPlan`: identity lookup, removed at once. */
    private fun consumePronunciationRenderPlan(vector: Any): PronunciationRenderPlan? =
        synchronized(pendingPronunciationRenderPlans) {
            pendingPronunciationRenderPlans.remove(vector)
        }

    /**
     * HLE's `registerApplePronunciationRenderPlan`; bounded, one-shot per vector.
     * The app caches the pronunciation vector it is handed, so the plan must be
     * registered under the exact vector object the adapter will lay out — which
     * is why the host returns Apple's own main vector and keys the plan by it.
     */
    private fun registerPronunciationRenderPlan(vector: Any, pronunciation: String) {
        synchronized(pendingPronunciationRenderPlans) {
            if (pendingPronunciationRenderPlans.size >= MAX_PRONUNCIATION_RENDER_PLANS) {
                pendingPronunciationRenderPlans.clear()
            }
            pendingPronunciationRenderPlans[vector] = PronunciationRenderPlan(pronunciation)
        }
    }

    /** [supplementLaneAvailable] for the model's own song, used by the refresh state. */
    private fun supplementPronunciationLane(songId: Long): Boolean =
        enabled && songId > 0L && isModuleSupplementSong(songId) &&
            overlay.hasPronunciation(songId.toString())

    /**
     * Whether [songId] is a supplement track with a published romanization lane.
     * Read live inside the getters (never captured when the hook was installed),
     * so a lane published after the model was built is served on the very next
     * getter call instead of waiting for a reinstall.
     */
    private fun supplementLaneAvailable(songId: String?): Boolean {
        if (!enabled) return false
        val id = songId?.toLongOrNull() ?: return false
        return id > 0L && isModuleSupplementSong(id) && overlay.hasPronunciation(songId)
    }

    /** True when the line's own timing/text resolves to a published supplement lane. */
    private fun hasSupplementPronunciationLine(line: Any, text: String?): Boolean {
        if (!enabled || mandarinHidden) return false
        if (!supplementLaneAvailable(lineSongId(line))) return false
        return supplementPronunciation(line, text) != null
    }

    /**
     * The overlay lane for one line: the displayed document's romanization at the
     * line's timing and text, sanitized against the line's own words. This is
     * HLE's `onlinePronunciation`, gated to a supplement track by the caller.
     */
    private fun supplementPronunciation(line: Any, text: String?): String? {
        if (!enabled || mandarinHidden) return null
        val begin = number(call(line, AppleMusicRuntimeMember.LYRICS_NATIVE_BEGIN_METHOD)) ?: return null
        val end = number(call(line, AppleMusicRuntimeMember.LYRICS_NATIVE_END_METHOD)) ?: return null
        return RomanizationPolicy.sanitize(
            originalText = text,
            pronunciation = overlay.pronunciation(lineSongId(line), begin, end, text),
        )
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
     * Mandarin pronunciation while hidden, and keep only a language Apple itself
     * advertises. No third-party language is substituted.
     *
     * The pure policy ([NativeLyricModelPolicy.selectLanguage]) is left
     * untouched and still returns null on an empty Apple vector. When it does,
     * the hook falls back to the lane this module has already confirmed for the
     * current song ([confirmedPronunciationLanguageForCurrentSong]), so the
     * app's own `F2` — the only writer of the live adapter's `n1.e` flag — sees
     * `strMatch != null`, calls `setPronunciation(lane)`, and dispatches its own
     * pronunciation payload. The fallback is identity-guarded to the current
     * model, and the native setter still has the final say, so a rejected or
     * absent lane keeps its old null/false outcome.
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
                    ) ?: confirmedPronunciationLanguageForCurrentSong()
                }
            }
        }, scope)
    }

    /**
     * HLE's `hookAppleLyricsDisplayPreferences` pronunciation half and its
     * `onAppleLyricsDisplayPreferenceChanged(PRONUNCIATION)` event: the app's own
     * `ja.i0.m(boolean)` setter is the authority for "the user just toggled
     * Apple's pronunciation row". The after hook records the value for
     * [AppleLyricsPreferenceReader] and asks for the guarded re-presentation
     * ([onPronunciationPreferenceChanged]). Fail-open: no pinned target leaves the
     * app's own behaviour untouched.
     */
    private fun installPronunciationPreferenceHook() {
        val method = runCatching {
            resolver.resolveMethod(AppleMusicHookPoint.LYRICS_PRONUNCIATION_PREFERENCE).method
        }.getOrNull() ?: return
        if (!installed.add("pref:${method.declaringClass.name}#${method.name}")) return
        ModernXposedRuntime.hookMethod(method, object : ModernMethodHook() {
            override fun afterHookedMethod(param: MethodHookParam) {
                val selected = param.args.firstOrNull() as? Boolean ?: return
                runCatching { onPronunciationPreferenceChanged(selected) }.onFailure { error ->
                    log("online-translation pronunciation-preference hook failed: ${error.message}")
                }
            }
        }, scope)
    }

    /**
     * HLE's early fragment registration (`LYRICS_UI_ON_CREATE_VIEW` `before`) and
     * its `onDestroyView` cleanup.
     *
     * HLE registers the fragment in `onCreateView` because a refresh that runs
     * before Apple's first native presentation has no fragment bound and aborts
     * `not-bound`; on 1606 the profile used to leave this point empty, so the
     * fork's only binding seam was the later `F2` presentation. Registering the
     * fragment here lets the native-presentation seam re-dispatch an accepted
     * refresh the moment the pointer arrives instead of waiting for the page to be
     * re-created. `onDestroyView` drops the binding so a dead fragment is never
     * re-invoked (HLE's `cleanupLyricsResultObserver`). Both are fail-open.
     */
    private fun installLyricsViewFragmentSeam() {
        val onCreateView = runCatching {
            resolver.resolveMethod(AppleMusicHookPoint.LYRICS_UI_ON_CREATE_VIEW).method
        }.getOrNull()
        if (onCreateView != null &&
            installed.add("ui-create:${onCreateView.declaringClass.name}#${onCreateView.name}")
        ) {
            ModernXposedRuntime.hookMethod(onCreateView, object : ModernMethodHook() {
                override fun beforeHookedMethod(param: MethodHookParam) {
                    val fragment = param.thisObject ?: return
                    if (presentationFragmentRef?.get() !== fragment) {
                        presentationFragmentRef = java.lang.ref.WeakReference(fragment)
                    }
                }
            }, scope)
        }
        val onDestroyView = runCatching {
            resolver.resolveMethod(AppleMusicHookPoint.LYRICS_UI_ON_DESTROY_VIEW).method
        }.getOrNull()
        if (onDestroyView != null &&
            installed.add("ui-destroy:${onDestroyView.declaringClass.name}#${onDestroyView.name}")
        ) {
            ModernXposedRuntime.hookMethod(onDestroyView, object : ModernMethodHook() {
                override fun afterHookedMethod(param: MethodHookParam) {
                    runCatching { cleanupPresentationBinding(param.thisObject) }
                }
            }, scope)
        }
    }

    /**
     * HLE's `onDestroyView` cleanup: drop the fragment/pointer binding when the
     * destroyed fragment is the bound one, and clear the one-shot word render
     * plans that belonged to its model, so a dead view is never re-invoked and no
     * stale plan leaks into the next one.
     */
    private fun cleanupPresentationBinding(fragment: Any?) {
        if (fragment != null && presentationFragmentRef?.get() === fragment) {
            presentationFragmentRef = null
            presentationPointerRef = null
        }
        synchronized(pendingPronunciationRenderPlans) {
            pendingPronunciationRenderPlans.clear()
        }
    }

    private fun lineSongId(line: Any): String? =
        lineSongIds[line]?.takeIf { it > 0L }?.toString() ?: currentSongId()

    private fun onlineTranslation(line: Any): String? {
        if (!enabled) return null
        val begin = number(call(line, AppleMusicRuntimeMember.LYRICS_NATIVE_BEGIN_METHOD)) ?: return null
        val end = number(call(line, AppleMusicRuntimeMember.LYRICS_NATIVE_END_METHOD)) ?: return null
        return overlay.translation(lineSongId(line), begin, end, rawLineText(line))
    }

    /**
     * The device-facing per-track proof that the native model was written.
     * `officialPronunciation` is the per-call reading that drives the decision;
     * `officialAtBuild`/`probeAtBuildLines` are the build-time reading of the same
     * per-line probe (counted, so a lane our own selection pass destroyed shows as
     * `probeAtBuildLines=34 … pronunciationLines=0`); `languages` is the raw
     * `getPronunciationLanguages` vector read **at build**, before our selection,
     * and `advertisedAfterSelection` the same raw read afterwards — the pair that
     * proves whether the document's `<transliteration xml:lang>` tag became a lane
     * name the model reports. `officialLanguage` is Apple's advertised own lane,
     * `selectedLanguage` what `setPronunciation` was handed, `appleLanguagesKnown`
     * whether Apple's language vector was populated at this call,
     * `translationSelected` the app's own lyrics-translation preference (HLE's
     * `PreferencesMonitor.isTranslationSelected()`, `none` when unreadable), and
     * `selection`/`reason` which Apple lane was chosen and why. `pronunciationLines`
     * counts the lines carrying **Apple's own** romanization; the module adds none.
     */
    private fun reportNativeWrite(
        lines: List<Any>,
        officialAtBuild: Boolean,
        officialAtBuildLines: Int,
        officialNow: Boolean,
        languages: List<String>,
        advertisedAfterSelection: List<String>,
        selection: NativeLyricModelPolicy.PronunciationSelectionPlan,
    ) {
        val songId = modelSongId
        val translationLines = lines.count { onlineTranslation(it) != null }
        val pronunciationLines = lines.count(::hasOfficialPronunciationLine)
        val advertised = advertisedPronunciationLanguages(languages)
        val officialLanguage = NativeLyricModelPolicy.officialPronunciationLanguage(advertised)
        diagnostic.log(
            songId,
            "online-translation native-write id=$songId " +
                "translationLines=$translationLines " +
                "pronunciationLines=$pronunciationLines " +
                "languages=${languages.joinToString(",")} " +
                "advertisedAfterSelection=${advertisedAfterSelection.joinToString(",")} " +
                "officialPronunciation=$officialNow " +
                "officialAtBuild=$officialAtBuild " +
                "probeAtBuildLines=$officialAtBuildLines " +
                "officialLanguage=${officialLanguage ?: NONE} " +
                "selectedLanguage=${selection.language ?: NONE} " +
                "appleLanguagesKnown=${selection.appleLanguagesKnown} " +
                "translationSelected=" +
                "${AppleLyricsPreferenceReader.isTranslationSelected(resolver)?.toString() ?: NONE} " +
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

        /** HLE's adapter-retry bound for a refresh whose RecyclerView is not ready. */
        const val MAX_ADAPTER_RETRY_ATTEMPTS = 4
        const val ADAPTER_RETRY_DELAY_MS = 32L

        /** HLE's `hookApplePronunciationWordRendering` return-type filter. */
        const val ARRAY_MAP_CLASS = "android.util.ArrayMap"

        /**
         * HLE's bound on the one-shot pronunciation render plans: a fresh model
         * registers at most one plan per main word vector, and a run that exceeds
         * this is cleared rather than grown without bound.
         */
        const val MAX_PRONUNCIATION_RENDER_PLANS = 256

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

        /**
         * The report's verified 1606 literals for the pronunciation-payload
         * surface, used when the profile leaves the corresponding point empty:
         * the fragment's live lyrics adapter field (`p0`, also pinned as
         * `LYRICS_UI_ON_CREATE_VIEW#LYRICS_UI_ADAPTER_FIELD` on 1606) and the
         * adapter's inherited pronunciation-selected flag (`e`, pinned as
         * `LYRICS_RECYCLER_ADAPTER#LYRICS_ADAPTER_PRONUNCIATION_SELECTED_FIELD`).
         * The remaining surfaces (`a`, `i`, `d(int,int,Object)`) live in
         * `AppleLyricsPronunciationPayload`, which resolves them structurally as
         * well.
         */
        const val FALLBACK_LYRICS_ADAPTER_FIELD = "p0"
        const val FALLBACK_PRONUNCIATION_SELECTED_FIELD = "e"

        /**
         * Prefix of a module supplement's lane-ready edge key. Apple's own lane
         * edge for the same (song, lane, probe) may already have been consumed
         * while Apple's document was the presented one; the custom document is a
         * different model with its own lane, so its first ask must not be
         * swallowed by that earlier edge.
         */
        const val SUPPLEMENT_LANE_KEY_PREFIX = "supplement:"

        /**
         * The `payload=` token for a module supplement (custom lyrics) track whose
         * document confirmed no pronunciation lane at all. Printed instead of the
         * generic `skipped(not-available)` so the next device log separates "this
         * custom document simply has no Apple transliteration to show" from "the
         * payload was never reached / no lane resolved for Apple's own document".
         */
        const val PAYLOAD_CUSTOM_NO_LANE = "skipped(custom-no-lane)"

        /** `state=` tokens: whether the dedupe state was cleared for a retry. */
        const val STATE_CLEARED = "cleared"
        const val STATE_LATCHED = "latched"

        /**
         * The `detail=` token of the lane-ready decision line. The attempt line
         * keeps the outcome token (`rebound`/`not-bound`/…), so the pair
         * `detail=lane-ready trigger=lane-ready` proves the late-lane ask
         * reached the page and answers the two known triggers (`build`,
         * `custom-overlay`).
         */
        const val DETAIL_LANE_READY = "lane-ready"

        /**
         * The `detail=` token of the just-confirmed-lane decision line
         * (`trigger=lane-confirmed`): our own selection's `setPronunciation` was
         * accepted by Apple on a module supplement (custom lyrics) pointer, after
         * the only build ask had been skipped. The later attempt line's
         * `payload=dispatched` (or `already-true`) is the visible proof the custom
         * document's own lane reached the live adapter.
         */
        const val DETAIL_LANE_CONFIRMED = "lane-confirmed"

        /**
         * The `detail=` token of the late-word-vector decision line
         * (`trigger=word-ready`): Apple's own pronunciation words arrived after
         * the first word getter had answered with our lane. The later
         * `render-probe … result=OFFICIAL` line is the visible proof the rebuilt
         * model answered with Apple's own words.
         */
        const val DETAIL_WORD_READY = "word-ready"

        /**
         * The `detail=` token of the preference decision line
         * (`trigger=preference`): the app's own pronunciation preference setter
         * ran, HLE's `onAppleLyricsDisplayPreferenceChanged(PRONUNCIATION)`.
         */
        const val DETAIL_PREFERENCE = "preference"

        /**
         * `result=` tokens on a `render-probe` line: what the app's own read was
         * answered with. `render-plan` means the app consumed the one-shot
         * romanization plan in its word-render adapter, the strongest proof of a
         * real re-render.
         */
        const val PROBE_OFFICIAL = "official"
        const val PROBE_ONLINE = "online"
        const val PROBE_EMPTY = "empty"
        const val PROBE_HIDDEN = "hidden"
        const val PROBE_RENDER_PLAN = "render-plan"

        /**
         * `reload=` tokens on the `presentation-refresh` line: whether the app's
         * own `loadLyrics` was asked for, ran, threw, or was skipped — and why.
         * The decision line prints `requested`/`skipped(gate|supplement-pointer|
         * same-revision)`; the attempt line prints the real outcome.
         */
        const val RELOAD_REQUESTED = "requested"
        const val RELOAD_INVOKED = "invoked"
        const val RELOAD_FAILED = "failed"
        const val RELOAD_SKIPPED_PREFIX = "skipped("
        const val RELOAD_SKIPPED_GATE = "skipped(gate)"
        const val RELOAD_SKIPPED_SUPPLEMENT = "skipped(supplement-pointer)"
        const val RELOAD_SKIPPED_SAME_REVISION = "skipped(same-revision)"
        const val RELOAD_SKIPPED_INVOKE_GUARD = "skipped(invoke-guard)"
        const val RELOAD_SKIPPED_NO_METHOD = "skipped(no-load-method)"
        const val RELOAD_SKIPPED_NO_VIEW_MODEL = "skipped(no-view-model)"
        const val RELOAD_SKIPPED_NO_PLAYBACK_ITEM = "skipped(no-playback-item)"
        const val RELOAD_SKIPPED_STALE_ITEM = "skipped(stale-playback-item)"

        /**
         * HLE emits one full native-model line per build, but the shared
         * per-track budget on this channel is deliberately small; keep the
         * native proof to a couple of lines per track.
         */
        const val MAX_DIAGNOSTIC_LINES = 4
    }
}
