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
     * The reason of a module supplement's late-lane ask: the custom document's
     * own model advertised a pronunciation lane (or its per-line probe confirmed
     * one) *after* the build, and the supplement path re-presents itself. The
     * build gate deliberately never refreshes a supplement pointer, and the
     * post-overlay ask only runs when the completion feed actually wrote a lane,
     * so a custom document that already carries its translation (and therefore
     * produces no merged document) had no ask at all — the reported
     * "custom lyrics romanization only after backgrounding". The supplement is
     * never reloaded through Apple, so this is the custom-overlay rule applied
     * to the lane edge, not the build gate's `sourceIsApple` rule.
     */
    const val REFRESH_REASON_CUSTOM_LANE = "custom-lane"

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
     * The neutral Latin tag used when no lyrics language can be determined for a
     * document's `<transliteration>` lane, and when the per-line probe proves a
     * pronunciation exists but Apple advertised no language name for it. It is
     * only ever written into a document or printed in a diagnostic; it is never
     * handed to the app's native `setPronunciation` (see
     * [transliterationLanguageTag]).
     */
    const val FALLBACK_PRONUNCIATION_LANGUAGE = "und-Latn"

    /**
     * The `<transliteration xml:lang>` tag for a song whose lyrics language is
     * [lyricsLanguage], mirroring the set HLE requests
     * (`expandAppleLyricsPronunciationLanguages`): `ja-Latn`, `ko-Latn`,
     * `zh-Latn`.
     *
     * The tag is the lane's **name** in the app's own lyric model: the native
     * `SongInfoParser::parsePronunciations` keys each parsed pronunciation by the
     * `<transliteration>` element's language and `SongInfo#getPronunciationLanguages`
     * reports those keys back, while `SongInfo#setPronunciation` refuses — and
     * **clears the pronunciations already parsed** — when the language it is given
     * "was not found in the ttml" (`libandroidappmusic.so`, proven by the
     * `Can not switch to pronunciation … Will clear existing pronunciations.`
     * branch). A neutral `und-Latn` names no language, so a document that carries
     * romanization under it parses the text but advertises no lane, and nothing
     * may select it.
     *
     * The lyrics language is read from the document's own `xml:lang` first; a
     * primary subtag outside HLE's set (an English song, or one that names its
     * language only as `und`) keeps [FALLBACK_PRONUNCIATION_LANGUAGE].
     */
    fun transliterationLanguageTag(lyricsLanguage: String?): String =
        transliterationScriptLanguage(lyricsLanguage)
            ?.let { "$it-Latn" }
            ?: FALLBACK_PRONUNCIATION_LANGUAGE

    /**
     * The primary language a supported romanization tag can be built from, or
     * null when [lyricsLanguage] names none of them. Split out so a caller that
     * has more than one candidate language (a declared `xml:lang` and a script
     * inferred from the lyrics) can tell "unsupported" from "undetermined"
     * without comparing against [FALLBACK_PRONUNCIATION_LANGUAGE].
     */
    fun transliterationScriptLanguage(lyricsLanguage: String?): String? {
        val primary = lyricsLanguage
            ?.trim()
            ?.replace('_', '-')
            ?.lowercase()
            ?.substringBefore('-')
            ?.takeIf(String::isNotEmpty)
        return when (primary) {
            "ja", "jpn" -> "ja"
            "ko", "kor" -> "ko"
            "zh", "zho", "chi", "cmn", "yue" -> "zh"
            else -> null
        }
    }

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

    /**
     * The pure decision behind the post-refresh pronunciation-payload
     * re-application — HLE's "the adapter reads its flags once at bind" problem:
     *
     *  - no confirmed Apple lane ⇒ [PronunciationPayloadOutcome.NOT_AVAILABLE]
     *    (the reported "songs without an official lane are unchanged");
     *  - a lane the user has switched off ⇒
     *    [PronunciationPayloadOutcome.PREFERENCE_OFF], mirroring `F2`'s own
     *    `z && t2(...)` conjunct so the payload can never reveal romanization the
     *    user disabled;
     *  - the adapter's flag cannot be read ⇒
     *    [PronunciationPayloadOutcome.ADAPTER_UNAVAILABLE];
     *  - the flag is already set ⇒
     *    [PronunciationPayloadOutcome.ALREADY_TRUE] (the rows were already told);
     *  - otherwise the payload is dispatched.
     *
     * Kept pure so the truth table is JVM-tested; the host resolves the app's
     * adapter and calls in here.
     */
    fun pronunciationPayloadOutcome(
        hasConfirmedOfficialLane: Boolean,
        pronunciationSelected: Boolean,
        adapterPronunciationSelected: Boolean?,
    ): PronunciationPayloadOutcome = when {
        !hasConfirmedOfficialLane -> PronunciationPayloadOutcome.NOT_AVAILABLE
        !pronunciationSelected -> PronunciationPayloadOutcome.PREFERENCE_OFF
        adapterPronunciationSelected == null -> PronunciationPayloadOutcome.ADAPTER_UNAVAILABLE
        adapterPronunciationSelected -> PronunciationPayloadOutcome.ALREADY_TRUE
        else -> PronunciationPayloadOutcome.DISPATCHED
    }

    /**
     * Resolve the lane a payload dispatch may reveal, and where the evidence for
     * it came from.
     *
     * The Apple-only policy has one model per presented pointer, and a module
     * supplement (custom lyrics) track builds that model from the **user's own
     * document**. Its `<transliterations>` lane therefore never appears in the
     * per-song Apple advertisement cache that the build/lane-ready triggers read
     * before the document is installed; it can only be confirmed from the model
     * the document was parsed into. The order is the order the app itself would
     * accept the lane:
     *
     *  1. the lane the accepted ask already resolved ([stateOfficialLane]);
     *  2. the lane this module handed to `setPronunciation` and Apple accepted
     *     ([selectedLane]) — the same evidence `F2`'s `z` uses;
     *  3. the model's live/remembered `getPronunciationLanguages` admission
     *     ([advertisedLane]);
     *  4. a line whose pronunciation getter already returns romanization
     *     ([hasOfficialLineProbe]) — HLE's per-call `hasValidOfficialRomanization`,
     *     which is the only evidence a partially-populated official document
     *     offers. Apple exposed the **text** but advertised no language name for
     *     it, so a neutral Latin tag is synthesized
     *     ([probeConfirmedPronunciationLanguage]) purely to name the payload lane
     *     in the diagnostic and to let the module reveal the text Apple already
     *     parsed. The content is still Apple's own getter output — this is not a
     *     third-party transliteration lane, and the tag is **never** handed to the
     *     native `setPronunciation`: a language the TTML does not carry makes the
     *     app clear the pronunciations it parsed
     *     (`setPronunciation`'s "was not found in the ttml" branch).
     *
     * [NONE][PronunciationLaneSource.NONE] means the payload must not be
     * dispatched: without one of these an empty pronunciation row would appear.
     * Every input is filtered to Apple's own Latin lane, so a non-Latin language
     * tag can never confirm one. Pure, so the truth table is JVM-tested; the host
     * resolves the app's model and calls in here.
     */
    fun pronunciationPayloadLane(
        stateOfficialLane: String?,
        selectedLane: String?,
        advertisedLane: String?,
        hasOfficialLineProbe: Boolean,
        systemLyricsLanguage: String? = null,
    ): PronunciationPayloadLane = when {
        stateOfficialLane.isOfficialLane() ->
            PronunciationPayloadLane(
                confirmed = true,
                language = stateOfficialLane,
                source = PronunciationLaneSource.STATE_OFFICIAL_LANE,
            )
        selectedLane.isOfficialLane() ->
            PronunciationPayloadLane(
                confirmed = true,
                language = selectedLane,
                source = PronunciationLaneSource.CONFIRMED_SELECTION,
            )
        advertisedLane.isOfficialLane() ->
            PronunciationPayloadLane(
                confirmed = true,
                language = advertisedLane,
                source = PronunciationLaneSource.ADVERTISED_VECTOR,
            )
        hasOfficialLineProbe ->
            PronunciationPayloadLane(
                confirmed = true,
                language = probeConfirmedPronunciationLanguage(systemLyricsLanguage),
                source = PronunciationLaneSource.LINE_PROBE,
            )
        else ->
            PronunciationPayloadLane(
                confirmed = false,
                language = null,
                source = PronunciationLaneSource.NONE,
            )
    }

    /**
     * The neutral Latin tag **named in the diagnostic** when the per-line probe
     * proves a pronunciation exists but Apple advertised no language name for it
     * ([pronunciationPayloadLane]'s `line-probe` branch).
     *
     * This is HLE's `thirdPartyPronunciationFallbackLanguage()` **semantics**,
     * reduced to the Apple-only policy: the *content* is never third-party — it is
     * the app's own `getHtmlPronunciationLineText`. The system lyrics language is
     * reused when it is already a Latin tag (so the printed lane matches what the
     * app expects), else the neutral [FALLBACK_PRONUNCIATION_LANGUAGE]. It is a
     * **payload-lane name only**: unlike HLE, this module never passes it to the
     * native `setPronunciation`, because a language the document does not carry
     * makes the app clear the pronunciations it did parse. A document we write
     * carries a real per-song tag instead ([transliterationLanguageTag]).
     */
    fun probeConfirmedPronunciationLanguage(systemLyricsLanguage: String?): String =
        systemLyricsLanguage?.takeIf { isOfficialPronunciationLanguage(it) }
            ?: FALLBACK_PRONUNCIATION_LANGUAGE

    private fun String?.isOfficialLane(): Boolean =
        this != null && isOfficialPronunciationLanguage(this)

    private fun String?.latinLanguageOrNull(): String? = this
        ?.trim()
        ?.takeIf { it.isNotEmpty() && RomanizationPolicy.isLatinLanguageTag(it) }
}

/**
 * The terminal outcome of the post-refresh pronunciation-payload
 * re-application, printed as the `payload=` field on the `presentation-refresh`
 * line.
 *
 * `PlayerLyricsViewFragment.F2` is the only writer of the live lyrics adapter's
 * pronunciation flag (`n1.e`), and it derives that flag from
 * `LocaleUtil.matchToSystemLyricsScript(model.getPronunciationLanguages())`.
 * When Apple has not advertised the lane yet the vector is empty, `F2` latches
 * `e=false`, and a plain `notifyDataSetChanged` cannot repair it (the row skips
 * its whole text/visibility block when its cached strings are unchanged). The
 * app's own payload — `notifyItemRangeChanged(0, count, n1.i)` — is the only
 * path that re-applies `e` to already-bound rows, so the module replays it after
 * our refresh.
 *
 * [token] is the exact `payload=` value, so the printed token and the decision
 * can never disagree.
 */
enum class PronunciationPayloadOutcome(val token: String) {
    /** The flag was false and the app's own payload was dispatched. */
    DISPATCHED("dispatched"),

    /** No confirmed Apple lane for this song, or the refresh never reached the page. */
    NOT_AVAILABLE("skipped(not-available)"),

    /** A confirmed lane exists, but the user's pronunciation preference is off. */
    PREFERENCE_OFF("skipped(preference-off)"),

    /** The adapter flag was already true, so the rows had already been told. */
    ALREADY_TRUE("skipped(already-true)"),

    /** The live adapter, or its payload surface, could not be resolved or threw. */
    ADAPTER_UNAVAILABLE("skipped(adapter-unavailable)"),
}

/**
 * Where the pronunciation lane a payload dispatch is about to reveal was
 * confirmed from, printed as the `laneSrc=` field on `presentation-refresh`.
 *
 * The distinction is what makes the next device log answer *why* a custom
 * (module supplement) track's romanization did or did not appear: its document's
 * lane is invisible to the per-song Apple advertisement the build/lane-ready
 * gates read before the document is installed, so the model the document was
 * parsed into has to confirm it, either through its advertisement
 * ([ADVERTISED_VECTOR]) or through a line that already reads its romanization
 * ([LINE_PROBE]).
 */
enum class PronunciationLaneSource(val token: String) {
    /** The lane the accepted refresh ask already resolved (`state.officialLane`). */
    STATE_OFFICIAL_LANE("state"),

    /** The lane this module handed to `setPronunciation` and Apple accepted. */
    CONFIRMED_SELECTION("confirmed"),

    /** The model's live/remembered `getPronunciationLanguages` admission. */
    ADVERTISED_VECTOR("advertised"),

    /** The per-line pronunciation getter already returns romanization. */
    LINE_PROBE("line-probe"),

    /** Nothing confirmed a lane; the payload must not be dispatched. */
    NONE("none"),
}

/**
 * One payload-lane resolution: whether the payload may be dispatched, the lane
 * to print (null for the per-line probe, which has no language tag), and the
 * evidence [source] that confirmed it.
 */
data class PronunciationPayloadLane(
    val confirmed: Boolean,
    val language: String?,
    val source: PronunciationLaneSource,
)

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
 * remaining device-only uncertainty; [PAYLOAD_ONLY] is a supplement lane ask
 * that deliberately ran only the pronunciation payload (no re-presentation), so
 * it is a latched success with nothing left to retry.
 *
 * The tokens are the `detail=` values the device log prints, so this enum is
 * the single place the retry rule and the diagnostics can drift apart.
 */
enum class PresentationRefreshOutcome(val token: String, val latches: Boolean) {
    REBOUND("rebound", true),
    // A supplement (custom lyrics) lane ask is payload-only: its document is
    // already installed, so no re-presentation/rebind ran and there is nothing
    // for the `adapter-unavailable` retry to wait for. It is still a latched,
    // successful attempt (`payload=` carries what the replay did).
    PAYLOAD_ONLY("payload-only", true),
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
