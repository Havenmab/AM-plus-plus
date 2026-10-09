package dev.amenhancer.module.lyrics.online

/**
 * Timing-keyed overlay of the online translation/pronunciation lanes for the
 * current track. This is the fork's port of HLE's
 * `AppleNativeOnlineTranslationStore`, reduced to the subset the native
 * lyric-model delivery reads.
 *
 * HLE does not deliver pronunciation by editing TTML: it writes the online
 * lanes into Apple's own lyric model and advertises the languages. The
 * document lane in this fork already injects a `<transliterations>` head track,
 * but Apple Music 7.0.0-beta (1606) does not render it (device-confirmed:
 * `pronunciationLines=34` and `published=true`, yet the screen shows no
 * romanization). The native-model hooks therefore ask this store for the line
 * content keyed by the line's timing, exactly as HLE does through
 * `AppleNativeOnlineTranslationStore.translation/pronunciation`.
 *
 * One overlay is held at a time (the current track); every lookup is gated on
 * the caller's song id, so a stale track can never leak into the next one.
 * Content is sanitized on write: a pronunciation is only stored when
 * [RomanizationPolicy.sanitize] accepts it against Apple's own line text, and a
 * translation only when [OnlineTranslationContentPolicy] considers it real.
 * Fails open everywhere — a missing overlay, a blank id or a timing miss all
 * return null, so the caller keeps Apple's own value.
 */
class NativeLyricOverlayStore {

    private data class TimingKey(val begin: Long, val end: Long)

    private data class LineKey(val timing: TimingKey, val text: String)

    /** The lanes stored for one line; either side may be absent. */
    data class Content(val translation: String?, val pronunciation: String?)

    private data class Entry(val text: String, val content: Content)

    private data class Overlay(
        val songId: String,
        val exactContent: Map<LineKey, Content>,
        val contentByTiming: Map<TimingKey, List<Entry>>,
        val hasTranslation: Boolean,
        val hasPronunciation: Boolean,
        val translationSource: String?,
        val pronunciationSource: String?,
    )

    @Volatile
    private var overlay: Overlay? = null

    @Volatile
    private var revision: Long = 0L

    /**
     * Replaces the overlay with [lines] for [songId]. Returns true when the
     * stored content changed. A blank id or a body with no lane at all is
     * rejected and leaves the previous overlay in place, mirroring HLE's
     * "Apple source first, online only when it has something" rule.
     */
    @Synchronized
    fun update(
        songId: String?,
        lines: List<NativeLyricLine>,
        translationSource: String? = null,
        pronunciationSource: String? = null,
    ): Boolean {
        val id = songId?.takeIf(String::isNotBlank) ?: return false
        val entries = lines.mapNotNull { line ->
            val content = Content(
                translation = OnlineTranslationContentPolicy.sanitize(line.translation),
                pronunciation = RomanizationPolicy.sanitize(line.text, line.roma),
            )
            if (content.translation == null && content.pronunciation == null) {
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
            hasPronunciation = entries.any { it.second.pronunciation != null },
            translationSource = translationSource,
            pronunciationSource = pronunciationSource,
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

    fun hasPronunciation(songId: String?): Boolean =
        !songId.isNullOrBlank() &&
            overlay?.let { it.songId == songId && it.hasPronunciation } == true

    fun translationSource(songId: String?): String? =
        overlay?.takeIf { it.songId == songId && it.hasTranslation }?.translationSource

    fun pronunciationSource(songId: String?): String? =
        overlay?.takeIf { it.songId == songId && it.hasPronunciation }?.pronunciationSource

    fun translation(songId: String?, begin: Long, end: Long, text: String?): String? =
        content(songId, begin, end, text)?.translation

    fun pronunciation(songId: String?, begin: Long, end: Long, text: String?): String? =
        content(songId, begin, end, text)?.pronunciation

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
 * Pure decisions of HLE's native-model pronunciation delivery, kept free of
 * Android and reflection so the device-facing rule is covered by JVM tests.
 *
 * These are the non-reflection halves of `AppleSupplementTextHooks` /
 * `ApplePronunciationPolicy`; the host hooks only resolve the app's members and
 * call into here.
 */
object NativeLyricModelPolicy {

    /** HLE's third-party pronunciation tag when the system language is not Latin. */
    const val THIRD_PARTY_PRONUNCIATION_LANGUAGE = "und-Latn"

    /** Why a [PronunciationSelectionPlan] chose the language it did, for the log. */
    const val REASON_APPLE_LANE = "apple-lane"
    const val REASON_APPLE_NO_OWN_LANE = "apple-no-own-lane"
    const val REASON_APPLE_UNANSWERED = "apple-unanswered"
    const val REASON_NO_FALLBACK = "no-fallback"
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
     * HLE's `ApplePronunciationPolicy.shouldRefreshPresentationAfterBuild`,
     * ported verbatim.
     *
     * Apple's own data is the primary source, but it can become available after
     * the first lyrics-page presentation: on 1606 `getPronunciationLanguages` is
     * still empty at the build seam and nothing re-runs afterwards, so the page
     * keeps Apple's first (lane-less) render until it is re-created. Once the
     * model exists, a completed build must therefore re-present the lyrics when
     * there is something new to show: an online translation/pronunciation lane,
     * or an official pronunciation the user asked for.
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
     * HLE's `thirdPartyPronunciationFallbackLanguage`: the language to advertise
     * for a third-party pronunciation. Null when the Mandarin rule hides it,
     * when the feature is off, or when there is no online pronunciation at all;
     * otherwise the system lyrics language when it is already a Latin script
     * tag, else the script-neutral [THIRD_PARTY_PRONUNCIATION_LANGUAGE].
     */
    fun thirdPartyPronunciationFallbackLanguage(
        systemLanguage: String?,
        enabled: Boolean,
        hasOnlinePronunciation: Boolean,
        hideMandarinPinyin: Boolean,
        pronunciationLanguages: Collection<String> = listOfNotNull(systemLanguage),
        genre: String? = null,
    ): String? {
        if (
            ApplePronunciationVisibilityPolicy.shouldHide(
                genre = genre,
                pronunciationLanguages = pronunciationLanguages,
                hideMandarinPinyin = hideMandarinPinyin,
            )
        ) {
            return null
        }
        if (!enabled) return null
        if (!hasOnlinePronunciation) return null
        return systemLanguage.latinLanguageOrNull() ?: THIRD_PARTY_PRONUNCIATION_LANGUAGE
    }

    /**
     * HLE's `ApplePronunciationPolicy.selectLanguage`: keep the system match
     * when it is a Latin tag, else the first Apple language that is, else the
     * online fallback when it is Latin. Everything else is null, so Apple's own
     * match survives untouched.
     */
    fun selectLanguage(
        systemMatch: String?,
        appleLanguages: List<String>,
        onlineFallbackLanguage: String?,
    ): String? = systemMatch.latinLanguageOrNull()
        ?: appleLanguages.firstNotNullOfOrNull { it.latinLanguageOrNull() }
        ?: onlineFallbackLanguage.latinLanguageOrNull()

    /**
     * True when [language] is a pronunciation lane Apple itself advertises: a
     * Latin tag that is not the third-party placeholder. The document lane writes
     * [THIRD_PARTY_PRONUNCIATION_LANGUAGE] for its own transliteration track, so
     * counting that exact tag as Apple's would misreport the third-party lane as
     * the platform's own.
     */
    fun isOfficialPronunciationLanguage(language: String?): Boolean {
        val normalized = language?.trim()?.takeIf(String::isNotEmpty) ?: return false
        if (normalized.equals(THIRD_PARTY_PRONUNCIATION_LANGUAGE, ignoreCase = true)) return false
        return RomanizationPolicy.isLatinLanguageTag(normalized)
    }

    /**
     * Apple's own pronunciation language, from the song's advertised
     * `getPronunciationLanguages` vector, or null when Apple offers none.
     */
    fun officialPronunciationLanguage(appleLanguages: List<String>): String? =
        appleLanguages.firstOrNull(::isOfficialPronunciationLanguage)

    /**
     * The language HLE's `applyAppleNativePronunciationSelection` hands to the
     * song's `setPronunciation`.
     *
     * HLE gates Apple's own language behind `hasValidOfficialRomanization`, a
     * per-line read of `getHtmlPronunciationLineText`. On 1606 that getter is
     * empty until a pronunciation language has already been selected, so a strict
     * mirror always took the third-party branch and overwrote Apple's own lane:
     * the device log shows `languages=ja-Latn officialPronunciation=false`
     * turning into `languages=und-Latn` after the fallback was selected. Apple's
     * advertised Latin lane therefore wins whenever there is one — selecting it
     * is the only way the per-line probe can ever become valid — and the
     * third-party fallback is used only when Apple offers no lane of its own. The
     * line getter still fills an individual line Apple leaves empty, so a
     * selected-but-partially-empty official track never leaves a gap.
     */
    fun selectPronunciationLanguage(
        appleLanguages: List<String>,
        thirdPartyFallbackLanguage: String?,
    ): String? = officialPronunciationLanguage(appleLanguages) ?: thirdPartyFallbackLanguage

    /**
     * Apple's best-known advertisement of its own pronunciation lanes: the live
     * vector when it is non-empty, otherwise the last non-empty one. A transient
     * empty read means "Apple has not answered on this call", not "Apple has no
     * lane", so it must never withdraw a lane Apple already advertised nor hand
     * the third-party tag over it (the invariant the device log's
     * `languages=ko-Latn` → `languages=` → `und-Latn` sequence broke).
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
     * answered, so the fallback is standing in" ([REASON_APPLE_UNANSWERED]) from
     * "Apple answered without a lane of its own" ([REASON_APPLE_NO_OWN_LANE]) or
     * "nothing legitimate to select" ([REASON_NO_FALLBACK]).
     */
    data class PronunciationSelectionPlan(
        val language: String?,
        val selection: PronunciationSelection,
        val reason: String,
        val appleLanguagesKnown: Boolean,
    )

    /** Which lane a [PronunciationSelectionPlan] chose, printed in the log. */
    enum class PronunciationSelection(val token: String) {
        APPLE("apple"),
        THIRD_PARTY("third-party"),
        NONE("none"),
    }

    /**
     * Plans one `applyAppleNativePronunciationSelection` pass, mirroring HLE:
     *
     * ```
     * officialLanguages.firstOrNull()?.takeIf { hasValidOfficialRomanization(songNative) }
     *     ?: thirdPartyPronunciationFallbackLanguage() ?: return
     * ```
     *
     * Apple's advertised Latin lane wins ([PronunciationSelection.APPLE]). With
     * no lane of its own — a non-empty vector without one, or an empty vector
     * because Apple has not answered yet — the legitimate third-party fallback is
     * selected ([PronunciationSelection.THIRD_PARTY]). Only when there is no lane
     * and no fallback is nothing handed to `setPronunciation`
     * ([PronunciationSelection.NONE]).
     *
     * An empty Apple vector therefore is not "select nothing": it selects the
     * fallback, exactly as HLE's `?: thirdPartyPronunciationFallbackLanguage()`
     * does, which is what lets a third-party-only song render again. Because the
     * selection is re-run at every later entry point, Apple's own lane replaces
     * that fallback as soon as Apple advertises it.
     */
    fun planPronunciationSelection(
        appleLanguages: List<String>,
        thirdPartyFallbackLanguage: String?,
    ): PronunciationSelectionPlan {
        val known = appleLanguages.isNotEmpty()
        // The selection itself is HLE's `appleLane ?: fallback ?: nothing`, shared
        // with [selectPronunciationLanguage] so there is one rule, not two.
        val appleLane = officialPronunciationLanguage(appleLanguages)
        val language = selectPronunciationLanguage(appleLanguages, thirdPartyFallbackLanguage)
        val selection = when {
            appleLane != null -> PronunciationSelection.APPLE
            language != null -> PronunciationSelection.THIRD_PARTY
            else -> PronunciationSelection.NONE
        }
        val reason = when (selection) {
            PronunciationSelection.APPLE -> REASON_APPLE_LANE
            PronunciationSelection.THIRD_PARTY ->
                if (known) REASON_APPLE_NO_OWN_LANE else REASON_APPLE_UNANSWERED
            PronunciationSelection.NONE -> REASON_NO_FALLBACK
        }
        return PronunciationSelectionPlan(
            language = language,
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
     * HLE's `hasPronunciation` / `setPronunciation` availability resolution: a
     * Mandarin song is hidden outright, otherwise the online romanization is
     * advertised and Apple's own valid romanization is never withdrawn.
     */
    fun hasPronunciationAvailability(
        original: Boolean,
        enabled: Boolean,
        hasOnlinePronunciation: Boolean,
        hasValidOfficialPronunciation: Boolean,
        mandarinHidden: Boolean,
    ): Boolean {
        if (mandarinHidden) return false
        return (enabled && hasOnlinePronunciation) ||
            (original && hasValidOfficialPronunciation)
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
    ADAPTER_UNAVAILABLE("adapter-unavailable", true),
    NO_PRESENTATION_METHOD("no-presentation-method", false),
    NOT_BOUND("not-bound", false),
    POINTER_DEAD("pointer-dead", false),
    SONG_CHANGED("song-changed", false),
    INVOKE_FAILED("invoke-failed", false),
    ;

    /** True when the recorded dedupe state must be cleared so a later attempt can retry. */
    val cleared: Boolean get() = !latches
}
