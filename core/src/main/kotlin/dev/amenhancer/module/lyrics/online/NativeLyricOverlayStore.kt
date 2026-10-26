package dev.amenhancer.module.lyrics.online

import dev.amenhancer.module.lyrics.AppleRomanizationLanguage

/**
 * Timing-keyed overlay of the online **translation** lane and the module's own
 * **displayed-document romanization** for the current track. This is the fork's
 * port of HLE's `AppleNativeOnlineTranslationStore`, reduced to the subset the
 * native lyric-model delivery reads.
 *
 * The pronunciation half carries the romanization of a document this module
 * wrote and the app is displaying (a custom/supplement track). On 1606 a
 * Word-timed `<transliterations>` head track written by the converter is parsed
 * into the app's model but never surfaces on the rendered rows — the device log
 * shows the language advertised (`languages=ja-Latn`) beside an empty per-line
 * `getHtmlPronunciationLineText` and no word getter — so the module serves the
 * document's own text through its line/word getters instead. A document Apple
 * itself parsed is **not** served from here: Apple delivers those lanes straight
 * from its own model, and the host reads this overlay only for a supplement
 * track.
 *
 * One overlay is held at a time (the current track); every lookup is gated on
 * the caller's song id, so a stale track can never leak into the next one.
 * Content is sanitized on write: a translation is only stored when
 * [OnlineTranslationContentPolicy] considers it real, and a pronunciation only
 * when [RomanizationPolicy] accepts it against the line text. Fails open
 * everywhere — a missing overlay, a blank id or a timing miss all return null,
 * so the caller keeps Apple's own value.
 */
class NativeLyricOverlayStore {

    private data class TimingKey(val begin: Long, val end: Long)

    private data class LineKey(val timing: TimingKey, val text: String)

    /**
     * The line's `ttm:role="x-bg"` background lane, kept apart from the main
     * lane. The app's native line exposes it through its own getters
     * (`getBackgroundWords` / `getPronunciationBackgroundWords`) and renders it
     * as the small row under the lyric, so the background's own word slices are
     * what that row must be served from — never a slice of the main line.
     */
    data class BackgroundContent(
        val text: String? = null,
        val pronunciation: String? = null,
        val pronunciationSpans: List<NativePronunciationSpan> = emptyList(),
        /**
         * The background entry's own romanization as the document wrote it
         * (concatenated in document order, un-sanitized). The app gates its
         * background word getter on `getHtmlPronunciationBackgroundVocalsLineText`
         * being non-empty and different from `getHtmlBackgroundVocalsLineText`, so
         * a partially romanized background (a Word-timed track whose later slices
         * still carry the lyric) must still be offered here — otherwise the gate
         * can never pass and the per-word lane is never asked for.
         */
        val romanization: String? = null,
        /** The background group's own extent, its second lookup key. */
        val begin: Long = 0L,
        val end: Long = 0L,
    )

    /** The lanes stored for one line. */
    data class Content(
        val translation: String?,
        val pronunciation: String? = null,
        /**
         * The line's own timed romanization slices. A Word-timed document's
         * pronunciation is these spans, one per lyric word — [pronunciation] is
         * only their concatenation for the line-lane fallback.
         */
        val pronunciationSpans: List<NativePronunciationSpan> = emptyList(),
        val background: BackgroundContent? = null,
    )

    private data class Entry(val text: String, val content: Content)

    /**
     * The overlay lookup done for one native line: the row when one was found and
     * the key that answered it. [OverlayMatch.MISSING] with a null [content] is the
     * loud-but-bounded failure a device log can act on.
     */
    data class OverlayRow(val content: Content?, val match: OverlayMatch)

    /** The background lane lookup: the lane itself plus which key answered it. */
    data class BackgroundRow(val background: BackgroundContent?, val match: OverlayMatch)

    private data class Overlay(
        val songId: String,
        val exactContent: Map<LineKey, Content>,
        val contentByTiming: Map<TimingKey, List<Entry>>,
        val contentByBegin: Map<Long, List<Entry>>,
        val contentByText: Map<String, List<Entry>>,
        val backgroundByTiming: Map<TimingKey, List<Entry>>,
        val hasTranslation: Boolean,
        val translationSource: String?,
        /** Provider of the displayed original document; null when the caller did not name it. */
        val originalSource: String? = null,
        val hasPronunciation: Boolean,
        val pronunciationSource: String?,
        /**
         * The `<transliteration xml:lang>` tags the published document carries,
         * per song. The app's own `getPronunciationLanguages` can stay empty for a
         * supplement document the module wrote, but its transliteration lane is
         * real and the language tag is what the app's `setPronunciation` accepts;
         * the toggle-availability fallback reads this so a confirmed supplement
         * song is not left with a disabled romanization button.
         */
        val pronunciationLanguages: List<String> = emptyList(),
    )

    @Volatile
    private var overlay: Overlay? = null

    @Volatile
    private var revision: Long = 0L

    /**
     * Replaces the overlay with [lines] for [songId]. Returns true when the
     * stored content changed. A blank id or a body with neither lane is rejected
     * and leaves the previous overlay in place, mirroring HLE's "Apple source
     * first, online only when it has something" rule. A provider's `roma` column
     * never reaches here as pronunciation: the enrichment layer only ever sets
     * [NativeLyricLine.roma] from the displayed document's own
     * `<transliterations>` entries.
     */
    @Synchronized
    fun update(
        songId: String?,
        lines: List<NativeLyricLine>,
        translationSource: String? = null,
        pronunciationSource: String? = null,
        pronunciationLanguages: List<String> = emptyList(),
        /**
         * The provider the displayed original came from, when the caller knows it.
         *
         * The lanes stored here are the translation/pronunciation of a document this
         * module wrote; only the publisher knows where that document came from, so it
         * hands the name in and the lyrics-source sheet can show the real platform
         * instead of the anonymous "已写入歌词".
         */
        originalSource: String? = null,
    ): Boolean {
        val id = songId?.takeIf(String::isNotBlank) ?: return false
        val entries = lines.mapNotNull { line ->
            val backgroundSpans = line.backgroundRomaSpans.filter { it.text.isNotBlank() }
            val backgroundText = line.backgroundText?.takeIf(String::isNotBlank)
            val background = if (backgroundText != null || backgroundSpans.isNotEmpty()) {
                BackgroundContent(
                    text = backgroundText,
                    pronunciation = RomanizationPolicy.sanitize(backgroundText, line.backgroundRoma),
                    pronunciationSpans = backgroundSpans,
                    romanization = line.backgroundRoma?.takeIf(String::isNotBlank),
                    begin = line.backgroundBegin,
                    end = line.backgroundEnd,
                )
            } else {
                null
            }
            val content = Content(
                translation = OnlineTranslationContentPolicy.sanitize(line.translation),
                pronunciation = RomanizationPolicy.sanitize(line.text, line.roma),
                pronunciationSpans = line.romaSpans.filter { it.text.isNotBlank() },
                background = background,
            )
            if (
                content.translation == null &&
                content.pronunciation == null &&
                content.pronunciationSpans.isEmpty() &&
                content.background == null
            ) {
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
            contentByBegin = entries.groupBy(
                keySelector = { it.first.timing.begin },
                valueTransform = { Entry(it.first.text, it.second) },
            ),
            contentByText = entries
                .filter { it.first.text.isNotEmpty() }
                .groupBy(
                    keySelector = { it.first.text },
                    valueTransform = { Entry(it.first.text, it.second) },
                ),
            // The background's own extent is the second key the app's background
            // getter can be matched by, independent of the line's own timing.
            backgroundByTiming = entries
                .mapNotNull { (key, content) ->
                    val background = content.background ?: return@mapNotNull null
                    if (background.end <= background.begin) return@mapNotNull null
                    TimingKey(background.begin, background.end) to Entry(key.text, content)
                }
                .groupBy(
                    keySelector = { it.first },
                    valueTransform = { it.second },
                ),
            hasTranslation = entries.any { it.second.translation != null },
            translationSource = translationSource,
            originalSource = originalSource,
            hasPronunciation = entries.any {
                it.second.pronunciation != null ||
                    it.second.pronunciationSpans.isNotEmpty() ||
                    it.second.background != null
            },
            pronunciationSource = pronunciationSource,
            pronunciationLanguages = pronunciationLanguages.distinct(),
        )
        if (overlay == next) return false
        overlay = next
        revision += 1
        return true
    }

    /**
     * Publishes the lanes of a document this module wrote and the app is
     * displaying (a custom/supplement track): the per-line translation and
     * romanization read back through [DisplayedLyricPublication]. Returns true
     * when the stored content changed. A blank id or a document with neither
     * lane is rejected, leaving any previous overlay in place.
     */
    fun publishDisplayed(
        songId: Long,
        ttml: String,
        pronunciationSource: String? = DISPLAYED_DOCUMENT_SOURCE,
        /** Provider of this document, for the lyrics-source sheet's original lane. */
        originalSource: String? = null,
    ): Boolean {
        if (songId <= 0L || ttml.isBlank()) return false
        return update(
            songId = songId.toString(),
            lines = DisplayedLyricPublication.lines(ttml),
            pronunciationSource = pronunciationSource,
            originalSource = originalSource,
            // The document's own `<transliteration xml:lang>` tags: the lane the
            // app's native parser keys the pronunciation by, which the toggle's
            // availability needs when Apple's own advertisement stays empty.
            pronunciationLanguages = AppleLyricTtmlReader.transliterationLanguages(ttml),
        )
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

    /**
     * The provider the displayed original came from, or null when the publisher did not
     * name one. Gated on the song id like every other lookup, so a stale track can never
     * answer for the next one.
     */
    fun originalSource(songId: String?): String? =
        overlay?.takeIf { it.songId == songId }?.originalSource

    /** True when the displayed document's own romanization was published. */
    fun hasPronunciation(songId: String?): Boolean =
        !songId.isNullOrBlank() &&
            overlay?.let { it.songId == songId && it.hasPronunciation } == true

    fun pronunciationSource(songId: String?): String? =
        overlay?.takeIf { it.songId == songId && it.hasPronunciation }?.pronunciationSource

    /**
     * The `<transliteration xml:lang>` tags the published document carries for
     * [songId], in document order. Empty for another song or a document with no
     * named lane. The app's toggle-availability fallback reads this when Apple's
     * own `getPronunciationLanguages` does not advertise the supplement's lane.
     */
    fun pronunciationLanguages(songId: String?): List<String> =
        overlay?.takeIf { it.songId == songId && it.hasPronunciation }
            ?.pronunciationLanguages
            .orEmpty()

    fun translation(songId: String?, begin: Long, end: Long, text: String?): String? =
        content(songId, begin, end, text)?.translation

    /** The romanization published for the line at [begin]/[end] carrying [text]. */
    fun pronunciation(songId: String?, begin: Long, end: Long, text: String?): String? =
        content(songId, begin, end, text)?.pronunciation

    /**
     * The line's own timed romanization slices for [begin]/[end]/[text]. The word
     * getter matches each main word against these by time; an empty list means the
     * line has no word-level lane (only [pronunciation], the line-lane fallback).
     */
    fun pronunciationSpans(
        songId: String?,
        begin: Long,
        end: Long,
        text: String?,
    ): List<NativePronunciationSpan> =
        content(songId, begin, end, text)?.pronunciationSpans.orEmpty()

    /**
     * The stored row for a native line plus **which key matched it**, so a device
     * log can name the reason a row was served or missed instead of only
     * observing that nothing rendered.
     */
    fun lookup(songId: String?, begin: Long, end: Long, text: String?): OverlayRow =
        row(songId, begin, end, text)

    /**
     * The **background** lane of the native line at [lineBegin]/[lineEnd]. The
     * app's `getPronunciationBackgroundWords(boolean)` is answered by the same
     * line object as the main getter, so the line is resolved exactly as the main
     * lane is and its background is taken from the same row; when that row cannot
     * be found, the background group's own extent ([backgroundBegin]/
     * [backgroundEnd], the `x-bg` span's own `begin`/`end`) is the bounded second
     * key. Never falls back to the main lane: a background row with no own lane
     * stays empty rather than showing the lyric as romanization.
     */
    fun lookupBackground(
        songId: String?,
        lineBegin: Long,
        lineEnd: Long,
        lineText: String?,
        backgroundBegin: Long? = null,
        backgroundEnd: Long? = null,
    ): BackgroundRow {
        val current = overlay ?: return BackgroundRow(null, OverlayMatch.MISSING)
        if (songId.isNullOrBlank() || current.songId != songId) {
            return BackgroundRow(null, OverlayMatch.MISSING)
        }
        row(songId, lineBegin, lineEnd, lineText).let { lineRow ->
            lineRow.content?.background?.let { return BackgroundRow(it, lineRow.match) }
        }
        if (backgroundBegin != null && backgroundEnd != null && backgroundEnd > backgroundBegin) {
            current.backgroundByTiming[TimingKey(backgroundBegin, backgroundEnd)]
                ?.singleOrNull()
                ?.content
                ?.background
                ?.let { return BackgroundRow(it, OverlayMatch.TIMING) }
        }
        return BackgroundRow(null, OverlayMatch.MISSING)
    }

    private fun content(songId: String?, begin: Long, end: Long, text: String?): Content? =
        row(songId, begin, end, text).content

    private fun row(songId: String?, begin: Long, end: Long, text: String?): OverlayRow {
        val current = overlay ?: return OverlayRow(null, OverlayMatch.MISSING)
        if (songId.isNullOrBlank() || current.songId != songId) {
            return OverlayRow(null, OverlayMatch.MISSING)
        }
        val timing = TimingKey(begin, end)
        // The exact (timing, text) entry wins; a timing that carries exactly one
        // entry still answers when Apple re-renders the same span with a slightly
        // different whitespace normalization. Two entries on one timing stay
        // ambiguous and return null rather than guessing, as HLE does.
        current.exactContent[LineKey(timing, normalizeText(text))]?.let {
            return OverlayRow(it, OverlayMatch.EXACT_TIMING)
        }
        current.contentByTiming[timing]?.singleOrNull()?.let {
            return OverlayRow(it.content, OverlayMatch.TIMING)
        }
        // A native line's own end is not a stable key. The displayed document's
        // `<p end>` may stop at the line's last lyric word while the app's line
        // carries the background vocal's later end (Apple's own documents set the
        // two equal), and an offline/online merge may re-time an end by a few
        // milliseconds. The line's **begin** is its identity in a timed document,
        // so it is the one further key that may answer — still only when it is
        // unambiguous, so a malformed document with two lines on one begin is
        // never guessed at.
        current.contentByBegin[begin]?.singleOrNull()?.let {
            return OverlayRow(it.content, OverlayMatch.BEGIN)
        }
        // Last resort for a line whose timing is unusable at both ends: a single
        // row carrying this exact lyric text is that line. Two rows with the same
        // text (a repeated chorus line) stay ambiguous and return nothing.
        val normalized = normalizeText(text)
        if (normalized.isNotEmpty()) {
            current.contentByText[normalized]?.singleOrNull()?.let {
                return OverlayRow(it.content, OverlayMatch.TEXT)
            }
        }
        return OverlayRow(null, OverlayMatch.MISSING)
    }

    companion object {
        private val WHITESPACE = Regex("\\s+")

        /** The `pronunciationSource` of a lane read from the displayed document. */
        const val DISPLAYED_DOCUMENT_SOURCE = "displayed"

        /**
         * Normalized forms of recently seen line texts. The native lyric getters
         * resolve a line's overlay row on every render (translation, pronunciation
         * and word getters), and each lookup used to run [WHITESPACE] over the same
         * immutable line text. The cache only depends on the input string, so it
         * cannot change a lookup result; [MAX_NORMALIZED_TEXT_ENTRIES] bounds it.
         */
        private const val MAX_NORMALIZED_TEXT_ENTRIES = 512

        private val normalizedTextCache = object : LinkedHashMap<String, String>(
            128,
            0.75f,
            true,
        ) {
            override fun removeEldestEntry(
                eldest: MutableMap.MutableEntry<String, String>?,
            ): Boolean = size > MAX_NORMALIZED_TEXT_ENTRIES
        }

        fun normalizeText(text: String?): String {
            if (text.isNullOrEmpty()) return ""
            synchronized(normalizedTextCache) { normalizedTextCache[text] }?.let { return it }
            val normalized = text.replace(WHITESPACE, " ").trim()
            synchronized(normalizedTextCache) { normalizedTextCache[text] = normalized }
            return normalized
        }

        /** Entry count of the bounded normalization cache, for tests. */
        internal fun normalizedTextCacheSize(): Int =
            synchronized(normalizedTextCache) { normalizedTextCache.size }

        /** Drops the normalization cache, for tests that measure a cold lookup. */
        internal fun clearNormalizedTextCacheForTest() {
            synchronized(normalizedTextCache) { normalizedTextCache.clear() }
        }
    }
}

/**
 * Which stored key answered a native line's overlay lookup, printed as
 * `match=` in the `word-lane` diagnostic.
 *
 * The app's native line and the displayed document describe the same line with
 * two independently derived timing pairs, so a row must be findable by more than
 * one key; naming the key that answered says whether the document's own `<p>`
 * timing was exact ([EXACT_TIMING], [TIMING]) or whether a bounded fallback had
 * to stand in ([BEGIN], [TEXT]). [MISSING] means no stored row matched at all —
 * the one case that leaves a Word-timed line without word-level romanization.
 */
enum class OverlayMatch(val token: String) {
    EXACT_TIMING("exact"),
    TIMING("timing"),
    BEGIN("begin"),
    TEXT("text"),
    MISSING("missing"),
}

/**
 * The lanes of a document this module wrote and the app is displaying, as the
 * per-line models [NativeLyricOverlayStore] stores.
 *
 * This is the publish counterpart the custom (supplement) path was missing: a
 * document that already carries its own translation produces no merged document,
 * so the enricher returns null, nothing ever writes the native overlay, and on
 * 1606 the app's model does not surface the document's Word-timed
 * `<transliterations>` track either. Reading the displayed document back through
 * [AppleLyricTtmlReader] gives the module both lanes so its own getters can
 * deliver them for that supplement track.
 *
 * Pure and fail-open: a malformed document yields an empty list.
 */
object DisplayedLyricPublication {

    fun lines(ttml: String): List<NativeLyricLine> =
        AppleLyricTtmlReader.read(ttml).map { line ->
            NativeLyricLine(
                begin = line.begin,
                end = line.end,
                text = line.text,
                translation = line.translation,
                roma = line.romanization,
                romaSpans = line.romanizationSpans.map { span ->
                    NativePronunciationSpan(
                        begin = span.begin,
                        end = span.end,
                        text = span.text,
                    )
                },
                backgroundText = line.backgroundText,
                backgroundRoma = line.backgroundRomanization,
                backgroundRomaSpans = line.backgroundRomanizationSpans.map { span ->
                    NativePronunciationSpan(
                        begin = span.begin,
                        end = span.end,
                        text = span.text,
                    )
                },
                backgroundBegin = line.backgroundWords.firstOrNull()?.begin
                    ?: line.backgroundRomanizationSpans.firstOrNull()?.begin
                    ?: 0L,
                backgroundEnd = line.backgroundWords.lastOrNull()?.end
                    ?: line.backgroundRomanizationSpans.lastOrNull()?.end
                    ?: 0L,
            )
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
     * The pronunciation lane the app's toggle may act on, Apple-first: Apple's own
     * matched lane when there is one, otherwise the supplement document's own lane
     * (only for a module supplement song, so an Apple-sourced track is never given
     * a lane it did not advertise). Null when neither exists — the state that
     * leaves the "show/hide romanization" button disabled.
     */
    fun pronunciationLaneFor(
        appleLane: String?,
        overlayLane: String?,
        isSupplement: Boolean,
    ): String? = appleLane ?: overlayLane?.takeIf { isSupplement }

    /**
     * Whether the romanization toggle has a lane to act on. Pure truth table:
     * Apple's own lane ⇒ available; a module supplement with a published overlay
     * lane ⇒ available; a supplement with none, or an Apple source with no lane,
     * ⇒ not available. The user's own preference still gates whether the locked
     * check is combined with it by the app (`t2`).
     */
    fun pronunciationAvailable(
        appleLane: String?,
        overlayLane: String?,
        isSupplement: Boolean,
    ): Boolean = pronunciationLaneFor(appleLane, overlayLane, isSupplement) != null

    /**
     * The neutral Latin tag used when no lyrics language can be determined for a
     * document's `<transliteration>` lane, and when the per-line probe proves a
     * pronunciation exists but Apple advertised no language name for it. It is
     * only ever written into a document or printed in a diagnostic; it is never
     * handed to the app's native `setPronunciation` (see
     * [transliterationLanguageTag]).
     *
     * Alias of [AppleRomanizationLanguage.NEUTRAL_LATIN_TAG]: the AMLL converter
     * owns the mapping and must stay free of any overlay dependency, so this
     * policy is the one that delegates.
     */
    const val FALLBACK_PRONUNCIATION_LANGUAGE = AppleRomanizationLanguage.NEUTRAL_LATIN_TAG

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
     *
     * Delegates to [AppleRomanizationLanguage] so the converter and the overlay
     * share one mapping.
     */
    fun transliterationLanguageTag(lyricsLanguage: String?): String =
        AppleRomanizationLanguage.transliterationLanguageTag(lyricsLanguage)

    /**
     * The primary language a supported romanization tag can be built from, or
     * null when [lyricsLanguage] names none of them. Split out so a caller that
     * has more than one candidate language (a declared `xml:lang` and a script
     * inferred from the lyrics) can tell "unsupported" from "undetermined"
     * without comparing against [FALLBACK_PRONUNCIATION_LANGUAGE].
     */
    fun transliterationScriptLanguage(lyricsLanguage: String?): String? =
        AppleRomanizationLanguage.transliterationScriptLanguage(lyricsLanguage)

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
     * HLE's `hasPronunciation` / `setPronunciation` availability resolution. A
     * Mandarin song is hidden outright. Otherwise Apple's own valid romanization
     * advertises the lane, and — for a module supplement track only — the
     * displayed document's own published romanization does too, so the app's
     * pronunciation row is shown for the custom document whose Word-timed head
     * track the model does not surface.
     */
    fun hasPronunciationAvailability(
        original: Boolean,
        hasValidOfficialPronunciation: Boolean,
        mandarinHidden: Boolean,
        hasOnlinePronunciation: Boolean = false,
    ): Boolean {
        if (mandarinHidden) return false
        if (hasOnlinePronunciation) return true
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
     *  4. the **displayed document's own** lane, as published into the overlay by
     *     this module ([supplementOverlayLane]) — the supplement path's content
     *     source. A custom document's `<transliteration xml:lang>` is the lane
     *     Apple's parser keys the model by; when the model has not advertised it
     *     yet (or at all) the overlay is the only evidence the module holds, and
     *     it is exactly the tag the document carries. The host passes it only
     *     for a module supplement (custom lyrics) song
     *     ([NativeLyricOverlayStore.hasPronunciation] gates it), so an
     *     Apple-sourced track is never given a lane it did not advertise;
     *  5. a line whose pronunciation getter already returns romanization
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
        supplementOverlayLane: String? = null,
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
        supplementOverlayLane.isOfficialLane() ->
            PronunciationPayloadLane(
                confirmed = true,
                language = supplementOverlayLane,
                source = PronunciationLaneSource.SUPPLEMENT_OVERLAY,
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

    /**
     * The displayed supplement document's own `<transliteration xml:lang>`, as
     * published into the overlay by the custom completion. The supplement path's
     * content source: a custom document's lane is real and `setPronunciation`
     * accepts it, but Apple's model advertisement may never name it, so without
     * this branch the payload could not be dispatched for a custom track.
     */
    SUPPLEMENT_OVERLAY("supplement"),

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
