package dev.amenhancer.module.lyrics.online

/**
 * Timing-keyed overlay of the online **translation** lane for the current track.
 * This is the fork's port of HLE's `AppleNativeOnlineTranslationStore`, reduced
 * to the subset the native lyric-model delivery reads.
 *
 * Only the translation half survives the Apple-only pronunciation policy: the
 * third-party romanization lane has been removed from the product, so a
 * provider's pronunciation column is never stored here and can never reach the
 * app's own lyric model. Apple's own pronunciation is delivered by the native
 * hooks straight from Apple's lyric model.
 *
 * One overlay is held at a time (the current track); every lookup is gated on
 * the caller's song id, so a stale track can never leak into the next one.
 * Content is sanitized on write: a translation is only stored when
 * [OnlineTranslationContentPolicy] considers it real. Fails open everywhere — a
 * missing overlay, a blank id or a timing miss all return null, so the caller
 * keeps Apple's own value.
 */
class NativeLyricOverlayStore {

    private data class TimingKey(val begin: Long, val end: Long)

    private data class LineKey(val timing: TimingKey, val text: String)

    /** The translation lane stored for one line. */
    data class Content(val translation: String?)

    private data class Entry(val text: String, val content: Content)

    private data class Overlay(
        val songId: String,
        val exactContent: Map<LineKey, Content>,
        val contentByTiming: Map<TimingKey, List<Entry>>,
        val hasTranslation: Boolean,
        val translationSource: String?,
    )

    @Volatile
    private var overlay: Overlay? = null

    @Volatile
    private var revision: Long = 0L

    /**
     * Replaces the overlay with [lines] for [songId]. Returns true when the
     * stored content changed. A blank id or a body with no translation lane is
     * rejected and leaves the previous overlay in place, mirroring HLE's
     * "Apple source first, online only when it has something" rule. A provider's
     * `roma` column is deliberately ignored: only Apple delivers pronunciation.
     */
    @Synchronized
    fun update(
        songId: String?,
        lines: List<NativeLyricLine>,
        translationSource: String? = null,
    ): Boolean {
        val id = songId?.takeIf(String::isNotBlank) ?: return false
        val entries = lines.mapNotNull { line ->
            val content = Content(
                translation = OnlineTranslationContentPolicy.sanitize(line.translation),
            )
            if (content.translation == null) {
                return@mapNotNull null
            }
            LineKey(TimingKey(line.begin, line.end), normalizeText(line.text)) to content
        }
        if (entries.isEmpty()) return false
        val next = Overlay(
            songId = id,
            exactContent = entries.toMap(),
            contentByTiming = entries.groupBy(
                keySelector = { it.first.timing },
                valueTransform = { Entry(it.first.text, it.second) },
            ),
            hasTranslation = entries.any { it.second.translation != null },
            translationSource = translationSource,
        )
        if (overlay == next) return false
        overlay = next
        revision += 1
        return true
    }

    /** Drops the overlay; a non-null [songId] only clears when it still matches. */
    @Synchronized
    fun clear(songId: String? = null): Boolean {
        val current = overlay ?: return false
        if (!songId.isNullOrBlank() && current.songId != songId) return false
        overlay = null
        revision += 1
        return true
    }

    fun revision(): Long = revision

    /** The track the overlay currently belongs to, or null when empty. */
    fun currentSongId(): String? = overlay?.songId

    fun hasTranslation(songId: String?): Boolean =
        !songId.isNullOrBlank() &&
            overlay?.let { it.songId == songId && it.hasTranslation } == true

    fun translationSource(songId: String?): String? =
        overlay?.takeIf { it.songId == songId && it.hasTranslation }?.translationSource

    fun translation(songId: String?, begin: Long, end: Long, text: String?): String? =
        content(songId, begin, end, text)?.translation

    private fun content(songId: String?, begin: Long, end: Long, text: String?): Content? {
        val current = overlay ?: return null
        if (songId.isNullOrBlank() || current.songId != songId) return null
        val timing = TimingKey(begin, end)
        // The exact (timing, text) entry wins; a timing that carries exactly one
        // entry still answers when Apple re-renders the same span with a slightly
        // different whitespace normalization. Two entries on one timing stay
        // ambiguous and return null rather than guessing, as HLE does.
        current.exactContent[LineKey(timing, normalizeText(text))]?.let { return it }
        return current.contentByTiming[timing]?.singleOrNull()?.content
    }

    companion object {
        private val WHITESPACE = Regex("\\s+")

        fun normalizeText(text: String?): String = text.orEmpty().replace(WHITESPACE, " ").trim()
    }
}

/**
 * Pure decisions of the native-model pronunciation delivery, kept free of
 * Android and reflection so the device-facing rule is covered by JVM tests.
 *
 * Under the Apple-only policy, pronunciation is Apple's own data: the selection
 * policy advertises only a language Apple itself lists, and the availability
 * resolution never adds a third-party lane. The translation lane and the
 * presentation-refresh machinery are unchanged.
 *
 * These are the non-reflection halves of `AppleSupplementTextHooks` /
 * `ApplePronunciationPolicy`; the host hooks only resolve the app's members and
 * call into here.
 */
object NativeLyricModelPolicy {

    /**
     * Why a [PronunciationSelectionPlan] chose the language it did, for the log.
     * The third-party fallback branch is gone: with no Apple lane there is simply
     * nothing to select, and the selection stays open so a later advertisement is
     * still picked up.
     */
    const val REASON_APPLE_LANE = "apple-lane"
    const val REASON_APPLE_NO_OWN_LANE = "apple-no-own-lane"
    const val REASON_APPLE_UNANSWERED = "apple-unanswered"
    const val REASON_MANDARIN_HIDDEN = "mandarin-hidden"

    /**
     * Why a completed Apple lyric model requests a presentation refresh, for the
     * device log. Mirrors the branch order of
     * [shouldRefreshPresentationAfterBuild], so the emitted `reason=` and the
     * decision can never disagree.
     */
    const val REFRESH_REASON_ONLINE_LANE = "online-lane"
    const val REFRESH_REASON_OFFICIAL_LANE = "official-lane"
    const val REFRESH_REASON_NONE = "none"

    /**
     * The reason of the fork's second, post-overlay refresh trigger: the custom
     * lyric-model overlay was just written for the current track. HLE's own
     * supplement path refreshes on exactly this signal — `AppleSupplementDataReceive`
     * calls `refreshAppleLyricsSupplementPresentation` whenever the store receipt
     * reports `displayContentChanged` — because a completion that arrives after
     * the first presentation would otherwise never be re-presented. The custom
     * document is the module's own, so the build gate's `sourceIsApple` rule
     * (which deliberately skips a supplement pointer) does not apply here.
     */
    const val REFRESH_REASON_CUSTOM_OVERLAY = "custom-overlay"

    /**
     * HLE's `ApplePronunciationPolicy.shouldRefreshPresentationAfterBuild`,
     * ported verbatim.
     *
     * Apple's own data is the primary source, but it can become available after
     * the first lyrics-page presentation: on 1606 `getPronunciationLanguages` is
     * still empty at the build seam and nothing re-runs afterwards, so the page
     * keeps Apple's first (lane-less) render until it is re-created. Once the
     * model exists, a completed build must therefore re-present the lyrics when
     * there is something new to show: an online translation lane, or an official
     * pronunciation the user asked for. [hasOnlinePronunciation] is retained as
     * HLE's rule but is now always false — the fork's pronunciation lane is
     * Apple-only, so the host has no online romanization to report.
     *
     * The function is the pure half; the host resolves the app's members and the
     * overlay and calls in here, so the truth table is covered by JVM tests.
     */
    fun shouldRefreshPresentationAfterBuild(
        sourceIsApple: Boolean,
        hasValidOfficialPronunciation: Boolean,
        hasOnlineTranslation: Boolean,
        hasOnlinePronunciation: Boolean,
        pronunciationSelected: Boolean,
    ): Boolean {
        if (!sourceIsApple) return false
        if (hasOnlineTranslation || hasOnlinePronunciation) return true
        return pronunciationSelected && hasValidOfficialPronunciation
    }

    /**
     * The [shouldRefreshPresentationAfterBuild] branch that fired, as a stable
     * log token: [REFRESH_REASON_ONLINE_LANE], [REFRESH_REASON_OFFICIAL_LANE] or
     * [REFRESH_REASON_NONE]. The same inputs produce the same decision, so the
     * diagnostic names the reason HLE's gate accepted.
     */
    fun presentationRefreshReason(
        sourceIsApple: Boolean,
        hasValidOfficialPronunciation: Boolean,
        hasOnlineTranslation: Boolean,
        hasOnlinePronunciation: Boolean,
        pronunciationSelected: Boolean,
    ): String = when {
        !sourceIsApple -> REFRESH_REASON_NONE
        hasOnlineTranslation || hasOnlinePronunciation -> REFRESH_REASON_ONLINE_LANE
        pronunciationSelected && hasValidOfficialPronunciation -> REFRESH_REASON_OFFICIAL_LANE
        else -> REFRESH_REASON_NONE
    }

    /**
     * HLE's `AppleSupplementDataReceive` store-update decision, reduced to the
     * fork's overlay: after a custom document's lane completion wrote the
     * native overlay, re-present whenever there is a lane to show. HLE gates its
     * refresh on `displayContentChanged`, not on the build gate's
     * `sourceIsApple` rule, because a supplement pointer deliberately never
     * refreshes from the build seam (Apple's track refresh makes the page
     * twitch). This is the post-overlay half that then owns the re-presentation.
     *
     * The content-change half is the overlay revision the host state carries:
     * an unchanged completion produces an unchanged state and is a no-op, so the
     * function only has to answer "is there anything to re-present".
     */
    fun shouldRefreshPresentationAfterCustomOverlay(
        hasOnlineTranslation: Boolean,
        hasOnlinePronunciation: Boolean,
    ): Boolean = hasOnlineTranslation || hasOnlinePronunciation

    /**
     * The [shouldRefreshPresentationAfterCustomOverlay] branch that fired, as a
     * stable log token: [REFRESH_REASON_CUSTOM_OVERLAY] when the overlay has a
     * lane, else [REFRESH_REASON_NONE]. The same inputs produce the same
     * decision, so `reason=` and `detail=` can never disagree.
     */
    fun customOverlayRefreshReason(
        hasOnlineTranslation: Boolean,
        hasOnlinePronunciation: Boolean,
    ): String {
        val refresh = shouldRefreshPresentationAfterCustomOverlay(
            hasOnlineTranslation = hasOnlineTranslation,
            hasOnlinePronunciation = hasOnlinePronunciation,
        )
        return if (refresh) REFRESH_REASON_CUSTOM_OVERLAY else REFRESH_REASON_NONE
    }

    /**
     * HLE's `ApplePronunciationPolicy.selectLanguage`: keep the system match
     * when it is a Latin tag, else the first Apple language that is. Everything
     * else is null, so Apple's own match survives untouched and no third-party
     * language is ever substituted.
     */
    fun selectLanguage(
        systemMatch: String?,
        appleLanguages: List<String>,
    ): String? = systemMatch.latinLanguageOrNull()
        ?: appleLanguages.firstNotNullOfOrNull { it.latinLanguageOrNull() }

    /**
     * True when [language] is a pronunciation lane Apple itself advertises, i.e.
     * a Latin script tag. The Apple-only policy has no placeholder language to
     * exclude any more.
     */
    fun isOfficialPronunciationLanguage(language: String?): Boolean =
        RomanizationPolicy.isLatinLanguageTag(language)

    /**
     * The lane-ready edge key: the (song, Apple lane, per-line probe) triple.
     * The late-lane trigger fires when this key *changes*, so:
     *
     *  - Apple advertising a lane the build had not seen is a new edge;
     *  - a lane whose per-line text only populated after the build
     *    (`officialAtBuild=false` then `true`, the reported device sequence) is a
     *    new edge even though the advertised language is unchanged;
     *  - the app's repeated `getPronunciationLanguages` reads of one settled lane
     *    produce the same key and are suppressed, which is the anti-thrash half.
     *
     * Kept pure so the edge rule is covered by JVM tests; the host resolves the
     * values and stores the key per track.
     */
    fun pronunciationLaneReadyKey(
        songId: Long,
        officialLane: String?,
        hasValidOfficialPronunciation: Boolean,
    ): String = "$songId:${officialLane.orEmpty()}:$hasValidOfficialPronunciation"

    /**
     * The word-ready edge key: the late-word-vector counterpart of
     * [pronunciationLaneReadyKey].
     *
     * The word track is decided per `getPronunciationWords()` call, and the app
     * caches the vector it gets back when the row binds. On a word-timing song
     * Apple can populate its pronunciation vector *after* the first bind, so the
     * first answer is `HIDDEN` and nothing re-asks. This key is deliberately
     * **per song, not per line**: a whole song's worth of word getters runs
     * during one bind, and a per-line edge would request a refresh for every line
     * (thrash). One edge per song means at most one extra re-presentation per
     * track, and the host records the key before asking, so the refresh's own
     * rebuild cannot bump it again. Pure, so the anti-loop bound is JVM-tested.
     */
    fun pronunciationWordReadyKey(songId: Long): String = "$songId:words"

    /**
     * Apple's own pronunciation language, from the song's advertised
     * `getPronunciationLanguages` vector, or null when Apple offers none.
     */
    fun officialPronunciationLanguage(appleLanguages: List<String>): String? =
        appleLanguages.firstOrNull(::isOfficialPronunciationLanguage)

    /**
     * The language `applyAppleNativePronunciationSelection` hands to the song's
     * `setPronunciation`: Apple's own advertised Latin lane, or null when Apple
     * offers none. The third-party fallback is gone, so nothing else is ever
     * selected. Apple's advertised Latin lane wins whenever there is one —
     * selecting it is the only way the per-line probe can ever become valid. The
     * line getter still fills an individual line Apple leaves empty, so a
     * selected-but-partially-empty official track never leaves a gap.
     */
    fun selectPronunciationLanguage(appleLanguages: List<String>): String? =
        officialPronunciationLanguage(appleLanguages)

    /**
     * Apple's best-known advertisement of its own pronunciation lanes: the live
     * vector when it is non-empty, otherwise the last non-empty one. A transient
     * empty read means "Apple has not answered on this call", not "Apple has no
     * lane", so it must never withdraw a lane Apple already advertised (the
     * invariant the device log's `languages=ko-Latn` → `languages=` sequence
     * broke).
     */
    fun advertisedPronunciationLanguages(
        live: List<String>,
        remembered: List<String>,
    ): List<String> = live.ifEmpty { remembered }

    /**
     * One planned `applyAppleNativePronunciationSelection` pass: the language to
     * hand the song's `setPronunciation` (null = nothing to select), which lane
     * that language belongs to, why this branch was taken, and whether Apple's
     * advertisement was known at this call site.
     *
     * [reason] is the honest replacement for the old `deferred=` flag: it says
     * *what* was chosen and *why*, so a device log can tell "Apple had not
     * answered yet" ([REASON_APPLE_UNANSWERED]) from "Apple answered without a
     * lane of its own" ([REASON_APPLE_NO_OWN_LANE]). There is no third-party
     * branch any more.
     */
    data class PronunciationSelectionPlan(
        val language: String?,
        val selection: PronunciationSelection,
        val reason: String,
        val appleLanguagesKnown: Boolean,
    )

    /** Which lane a [PronunciationSelectionPlan] chose, printed in the log. */
    enum class PronunciationSelection(val token: String) {
        /** Apple's own advertised Latin lane. */
        APPLE("apple"),

        /** Nothing to select: Apple offers no Latin lane (yet). */
        NONE("none"),
    }

    /**
     * Plans one `applyAppleNativePronunciationSelection` pass, Apple-only:
     *
     * ```
     * officialLanguages.firstOrNull()?.takeIf { isOfficialPronunciationLanguage(it) }
     * ```
     *
     * Apple's advertised Latin lane wins ([PronunciationSelection.APPLE]); with
     * no such lane — a non-empty vector without one, or an empty vector because
     * Apple has not answered yet — nothing is handed to `setPronunciation`
     * ([PronunciationSelection.NONE]). The selection is re-run at every later
     * entry point, so Apple's own lane is picked up the moment it appears.
     */
    fun planPronunciationSelection(
        appleLanguages: List<String>,
    ): PronunciationSelectionPlan {
        val known = appleLanguages.isNotEmpty()
        val appleLane = officialPronunciationLanguage(appleLanguages)
        val selection = if (appleLane != null) {
            PronunciationSelection.APPLE
        } else {
            PronunciationSelection.NONE
        }
        val reason = when {
            appleLane != null -> REASON_APPLE_LANE
            known -> REASON_APPLE_NO_OWN_LANE
            else -> REASON_APPLE_UNANSWERED
        }
        return PronunciationSelectionPlan(
            language = appleLane,
            selection = selection,
            reason = reason,
            appleLanguagesKnown = known,
        )
    }

    /** The plan while the Mandarin rule hides the song: nothing is handed over. */
    fun hiddenPronunciationSelection(appleLanguagesKnown: Boolean): PronunciationSelectionPlan =
        PronunciationSelectionPlan(
            language = null,
            selection = PronunciationSelection.NONE,
            reason = REASON_MANDARIN_HIDDEN,
            appleLanguagesKnown = appleLanguagesKnown,
        )

    /** HLE's `hasTranslation` / `setTranslation` availability resolution. */
    fun hasTranslationAvailability(
        original: Boolean,
        enabled: Boolean,
        hasOnlineTranslation: Boolean,
    ): Boolean = original || (enabled && hasOnlineTranslation)

    /**
     * HLE's `hasPronunciation` / `setPronunciation` availability resolution,
     * Apple-only: a Mandarin song is hidden outright, otherwise Apple's own
     * valid romanization is advertised. The module's online pronunciation lane
     * no longer exists, so there is no third-party term to add.
     */
    fun hasPronunciationAvailability(
        original: Boolean,
        hasValidOfficialPronunciation: Boolean,
        mandarinHidden: Boolean,
    ): Boolean {
        if (mandarinHidden) return false
        return original && hasValidOfficialPronunciation
    }

    private fun String?.latinLanguageOrNull(): String? = this
        ?.trim()
        ?.takeIf { it.isNotEmpty() && RomanizationPolicy.isLatinLanguageTag(it) }
}

/**
 * The terminal outcome of one main-thread presentation-refresh attempt.
 *
 * HLE's `refreshAppleLyricsSupplementPresentation` ends by re-invoking Apple's
 * own result presentation and then rebinding the lyrics adapter
 * (`refreshAppleLyricsRecyclerView` → `appleRecyclerNotifyDataSetChanged`); the
 * host prints which step the attempt reached in `detail=`.
 *
 * Only an attempt whose invoke actually returned may latch the gate dedupe
 * state. Every abort — a missing presentation method, an unbound
 * fragment/pointer, a dead pointer, a song that changed under us, or a throwing
 * invoke — clears the recorded state so a later build seam or the native
 * presentation binding seam can ask again (`cleared`). [REBOUND] is a latched
 * invoke whose adapter was also notified; [ADAPTER_UNAVAILABLE] latched the
 * invoke but could not resolve or notify the lyrics adapter, which is the
 * remaining device-only uncertainty.
 *
 * The tokens are the `detail=` values the device log prints, so this enum is
 * the single place the retry rule and the diagnostics can drift apart.
 */
enum class PresentationRefreshOutcome(val token: String, val latches: Boolean) {
    REBOUND("rebound", true),
    // The presentation invoke succeeded, but the RecyclerView may still be
    // empty while LiveData/layout work catches up. Keep the ticket retryable.
    ADAPTER_UNAVAILABLE("adapter-unavailable", false),
    NO_PRESENTATION_METHOD("no-presentation-method", false),
    NOT_BOUND("not-bound", false),
    POINTER_DEAD("pointer-dead", false),
    SONG_CHANGED("song-changed", false),
    INVOKE_FAILED("invoke-failed", false),
    ;

    /** True when the recorded dedupe state must be cleared so a later attempt can retry. */
    val cleared: Boolean get() = !latches
}
