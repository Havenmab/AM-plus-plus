package dev.amenhancer.module.lyrics.source

import dev.amenhancer.module.lyrics.online.LyricMatchPolicy
import dev.amenhancer.module.lyrics.online.SongSearchResult
import java.util.Locale

/**
 * Builds one song's lyrics-source catalog: the three lanes, every option each
 * lane can switch to, and which option is currently in effect.
 *
 * This is the whole decision surface of the feature and it is a pure function of
 * [LyricSourceInput] plus the song's stored selection - no store, no client, no
 * platform API. Two rules are worth stating outright because the rest of the
 * feature depends on them:
 *
 * 1. **Only reachable options are listed.** An option is listed in a lane only
 *    when the source can actually serve that lane (Apple's translation lane,
 *    a document that carries romanization, ...), so the sheet can never offer a
 *    switch that is bound to fail. [LyricLaneState.appleAvailable] is the derived
 *    flag for "Apple's own lane exists", the sheet's cue for showing a
 *    "回到 Apple 官方" action.
 * 2. **The default is the source that is already in effect.** A lane with no
 *    stored override selects, in order: the content this module already
 *    published or installed for that lane (overlay store, then the active
 *    custom-lyrics document), then Apple's own lane, then - for the original
 *    lane only - any other document of the custom-lyrics index. A supplement
 *    lane left with nothing resolves to null, i.e. "不显示", instead of
 *    inventing a third-party lane.
 *
 *    An online candidate is never a lane's default: it is not in effect until
 *    the user applies it, so listing one as "当前来源" would claim content the
 *    page is not showing. The suggestion the search list starts from is
 *    [bestOnline].
 */
object LyricSourceCatalog {

    /** Hard bound on one lane's option list; the head (Apple/已写入/文件/缓存) always survives. */
    const val MAX_OPTIONS_PER_LANE = 60

    /** Lower bound of [LyricSourceOption.matchScore]. */
    const val MIN_MATCH_SCORE = 0

    /** Upper bound of [LyricSourceOption.matchScore]. */
    const val MAX_MATCH_SCORE = 100

    /**
     * Builds the snapshot for [input].
     *
     * [selection] is this song's stored lane -> option key map (see
     * [LyricSourceSelectionStore.selection]). A malformed key, a key on the wrong
     * lane and a key whose content is genuinely gone (a deleted custom file, an
     * overlay lane the store no longer holds) are ignored and the lane falls back to
     * its default. A key that is merely *not listed* - an applied online source, whose
     * search result is not replayed when the sheet opens - is rebuilt by
     * [synthesize] so the sheet keeps reporting the source the page is showing.
     */
    fun build(
        input: LyricSourceInput,
        selection: Map<LyricLane, String> = emptyMap(),
        locale: Locale = Locale.getDefault(),
    ): LyricsSourceSnapshot {
        val lanes = LyricLane.ALL.map { lane ->
            laneState(input, lane, selection[lane], locale)
        }
        return LyricsSourceSnapshot(
            song = input.song,
            lanes = lanes,
            overriddenLanes = lanes.filter { it.overridden }.mapTo(linkedSetOf()) { it.lane },
        )
    }

    /**
     * One lane: its options, its default and the state the stored key resolves
     * to. [storedKey] is the raw persisted value - the reserved
     * [LyricSourceKeys.HIDDEN] marker means "explicitly hidden", any other value
     * must name one of the lane's own options or it is dropped.
     */
    fun laneState(
        input: LyricSourceInput,
        lane: LyricLane,
        storedKey: String? = null,
        locale: Locale = Locale.getDefault(),
    ): LyricLaneState {
        val options = options(input, lane, locale)
        val fallback = defaultOption(input, lane, options)
        val stored = LyricSourceKeys.sanitize(storedKey)

        if (stored != null && LyricSourceKeys.isHidden(stored) && lane.isSupplement) {
            return LyricLaneState(lane = lane, selected = null, options = options, overridden = true)
        }

        val selected = stored
            ?.takeUnless(LyricSourceKeys::isHidden)
            ?.let { key ->
                options.firstOrNull { it.key == key } ?: synthesize(input, lane, key, locale)
            }

        if (selected == null) {
            return LyricLaneState(
                lane = lane,
                selected = fallback,
                options = options,
                overridden = false,
            )
        }
        // A synthesized placeholder is not part of the probed list, so it is offered
        // as one extra trailing entry: the sheet can show it as the current source and
        // the user can switch away from it, but it never grows the search results.
        val offered = if (options.any { it.key == selected.key }) options else options + selected
        return LyricLaneState(
            lane = lane,
            selected = selected,
            options = offered,
            overridden = selected.key != fallback?.key,
        )
    }

    /**
     * Rebuilds a stored selection as an option when the probe no longer lists it.
     *
     * A snapshot is built from what the host can prove right now, but the selection
     * file remembers what the user chose. Without this step an applied online
     * translation would fall back to "不显示" on the next snapshot - the search that
     * produced it is not replayed, so its candidate is not in [options] - while the
     * page keeps showing the QQ translation; the sheet would then disagree with the
     * lyrics. The placeholder is deliberately honest about what is known: the
     * provider comes from the key, the label is its brand, the detail says the source
     * is applied, and precision stays unknown (false).
     *
     * A custom-lyrics key is only rebuilt while its index record still exists: the
     * document is what the host would have to publish, so a deleted file must degrade
     * (fall back to the lane default) instead of resurrecting a name for missing
     * content. The overlay key is never rebuilt either - content the store no longer
     * holds is genuinely gone.
     */
    fun synthesize(
        input: LyricSourceInput,
        lane: LyricLane,
        key: String,
        locale: Locale = Locale.getDefault(),
    ): LyricSourceOption? = when {
        LyricSourceKeys.onlineProvider(key) != null -> onlinePlaceholder(lane, key, locale)
        LyricSourceKeys.customFileId(key) != null -> documentPlaceholder(input, lane, key, locale)
        else -> null
    }

    /**
     * Every option [lane] can switch to, in display order:
     * Apple, the content already written to the overlay store, the custom-lyrics
     * files (a user's own document before downloaded ones), the auto cache, then
     * online candidates ranked by [score] descending.
     */
    fun options(
        input: LyricSourceInput,
        lane: LyricLane,
        locale: Locale = Locale.getDefault(),
    ): List<LyricSourceOption> {
        val options = LinkedHashMap<String, LyricSourceOption>()
        add(options, appleOption(input, lane, locale))
        add(options, overlayOption(input, lane, locale))
        customFileOptions(input, lane, locale).forEach { add(options, it) }
        cacheOptions(input, lane, locale).forEach { add(options, it) }
        onlineOptions(input, lane, locale).forEach { add(options, it) }
        return options.values.take(MAX_OPTIONS_PER_LANE)
    }

    /** Apple's own option for [lane], or null when the document does not serve it. */
    fun appleOption(
        input: LyricSourceInput,
        lane: LyricLane,
        locale: Locale = Locale.getDefault(),
    ): LyricSourceOption? {
        val apple = input.apple
        if (!apple.hasLane(lane)) return null
        return LyricSourceOption(
            key = LyricSourceKeys.APPLE,
            kind = LyricSourceKind.APPLE,
            providerId = LyricSourceProviders.APPLE,
            label = LyricSourceText.providerLabel(LyricSourceProviders.APPLE, locale),
            hasWords = apple.hasWords,
            hasTranslation = apple.hasTranslation,
            hasRomanization = apple.hasRomanization,
            detail = LyricSourceText.appleDetail(
                hasWords = apple.hasWords,
                hasTranslation = apple.hasTranslation,
                hasRomanization = apple.hasRomanization,
                locale = locale,
            ),
        )
    }

    /** The content this module already published for [lane], or null when there is none. */
    fun overlayOption(
        input: LyricSourceInput,
        lane: LyricLane,
        locale: Locale = Locale.getDefault(),
    ): LyricSourceOption? {
        val overlay = input.overlay
        if (!overlay.hasLane(lane)) return null
        val recorded = overlay.sourceFor(lane)?.takeIf { it.isNotBlank() }
        val providerId = recorded
            ?.let(LyricSourceProviders::fromCustomLyricsSource)
            ?: LyricSourceProviders.OVERLAY
        return LyricSourceOption(
            key = LyricSourceKeys.overlay(lane),
            kind = LyricSourceKind.OVERLAY_STORE,
            providerId = providerId,
            label = LyricSourceText.providerLabel(providerId, locale),
            hasWords = lane == LyricLane.ORIGINAL && overlay.hasWords,
            hasTranslation = overlay.hasTranslation,
            hasRomanization = overlay.hasRomanization,
            detail = LyricSourceText.overlayDetail(lane, providerId, locale),
        )
    }

    /** The custom-lyrics index records that can serve [lane], in display order. */
    fun customFileOptions(
        input: LyricSourceInput,
        lane: LyricLane,
        locale: Locale = Locale.getDefault(),
    ): List<LyricSourceOption> = input.customFiles
        .asSequence()
        .filter { !it.isAutoCache }
        .filter { servesFile(it, lane) }
        .sortedWith(FILE_ORDER)
        .map { fileOption(it, lane, locale) }
        .toList()

    /** The auto-cache records that can serve [lane]. */
    fun cacheOptions(
        input: LyricSourceInput,
        lane: LyricLane,
        locale: Locale = Locale.getDefault(),
    ): List<LyricSourceOption> = input.customFiles
        .asSequence()
        .filter { it.isAutoCache }
        .filter { servesFile(it, lane) }
        .sortedWith(FILE_ORDER)
        .map { cacheOption(it, lane, locale) }
        .toList()

    /**
     * Online candidates for [lane], ranked by [score] descending and then by the
     * module's own provider chain, so the list is deterministic for equal scores.
     * Candidates are listed even below the auto-match threshold: the user asked
     * for them explicitly and the sheet shows the score, so filtering would hide
     * the very thing they are judging.
     */
    fun onlineOptions(
        input: LyricSourceInput,
        lane: LyricLane,
        locale: Locale = Locale.getDefault(),
    ): List<LyricSourceOption> {
        val identity = input.song.scoringIdentity()
        val ranked = input.onlineCandidates
            .asSequence()
            .filter { servesCandidate(it, lane) }
            .map { it to score(it.result, identity) }
            .sortedWith(ONLINE_ORDER)
            .toList()

        val seen = linkedSetOf<String>()
        val options = ArrayList<LyricSourceOption>(ranked.size)
        for ((candidate, matchScore) in ranked) {
            val option = onlineOption(candidate, matchScore, locale)
            if (seen.add(option.key)) options += option
        }
        return options
    }

    /** One online candidate as an option, with [matchScore] already clamped to 0..100. */
    fun onlineOption(
        candidate: OnlineLyricsCandidate,
        matchScore: Int,
        locale: Locale = Locale.getDefault(),
    ): LyricSourceOption {
        val result = candidate.result
        return LyricSourceOption(
            key = LyricSourceKeys.online(candidate.providerId, result.id),
            kind = LyricSourceKind.ONLINE,
            providerId = candidate.providerId,
            label = result.title.trim().ifEmpty {
                LyricSourceText.providerLabel(candidate.providerId, locale)
            },
            hasWords = candidate.hasWords,
            hasTranslation = candidate.hasTranslation,
            hasRomanization = candidate.hasRomanization,
            matchScore = matchScore.coerceIn(MIN_MATCH_SCORE, MAX_MATCH_SCORE),
            detail = LyricSourceText.onlineDetail(result.artist, result.album, locale),
        )
    }

    /**
     * The lane's default option: the source that is already in effect for it.
     *
     * 1. The content this module already published for the lane (the overlay
     *    store entry), because that is what the page is displaying.
     * 2. The custom-lyrics document the host reports as installed
     *    ([LyricSourceInput.activeFileId]) - a supplement track's original text
     *    comes from that file, not from Apple.
     * 3. Apple's own lane.
     * 4. Original lane only: any other document of the custom-lyrics index, then
     *    the auto cache. A supplement lane stops here and resolves to null
     *    ("不显示").
     *
     * An online candidate is deliberately not part of this chain - see the note
     * on [LyricSourceCatalog].
     */
    fun defaultOption(
        input: LyricSourceInput,
        lane: LyricLane,
        options: List<LyricSourceOption>,
    ): LyricSourceOption? {
        if (input.overlay.hasLane(lane)) {
            val writtenKey = LyricSourceKeys.overlay(lane)
            options.firstOrNull { it.key == writtenKey }?.let { return it }
        }
        input.activeFileId?.let { activeId ->
            val activeKey = LyricSourceKeys.customFile(activeId)
            val activeCacheKey = LyricSourceKeys.cache(activeId)
            options.firstOrNull { it.key == activeKey || it.key == activeCacheKey }?.let { return it }
        }
        return defaultOption(lane, options)
    }

    /**
     * The default when only the option list is known: Apple first, then the
     * already-written overlay content, then (original lane only) a custom file
     * and the auto cache. This is the tail of [defaultOption] and the fallback a
     * caller without [LyricSourceInput] can use.
     */
    fun defaultOption(lane: LyricLane, options: List<LyricSourceOption>): LyricSourceOption? =
        when (lane) {
            LyricLane.ORIGINAL -> options.firstOrNull { it.kind == LyricSourceKind.APPLE }
                ?: options.firstOrNull { it.kind == LyricSourceKind.OVERLAY_STORE }
                ?: options.firstOrNull { it.kind == LyricSourceKind.CUSTOM_FILE }
                ?: options.firstOrNull { it.kind == LyricSourceKind.CACHE }
            LyricLane.TRANSLATION, LyricLane.ROMANIZATION ->
                options.firstOrNull { it.kind == LyricSourceKind.APPLE }
                    ?: options.firstOrNull { it.kind == LyricSourceKind.OVERLAY_STORE }
        }

    /**
     * The best-scoring online candidate among [options] (or among the options of
     * one lane), i.e. the suggestion the search list starts from. Null when the
     * lane has no online candidate. Ties keep the catalog's order, whose
     * secondary keys are already deterministic.
     */
    fun bestOnline(options: List<LyricSourceOption>): LyricSourceOption? =
        options.filter { it.kind == LyricSourceKind.ONLINE }
            .maxByOrNull { it.matchScore }

    /**
     * The identity score of one provider hit against this song, clamped to
     * [MIN_MATCH_SCORE]..[MAX_MATCH_SCORE].
     *
     * [LyricMatchPolicy] is reused verbatim - its weights, its invisible-character
     * normalization and its missing-duration neutrality - so the score the sheet
     * shows is the same number the automatic chain used to decide the match. The
     * only new rule is the clamp: the policy can total above 100 (every channel
     * maxed) and below 0 (a duration that is 5 s+ off is worth -30), while the
     * option model promises a percentage.
     */
    fun score(result: SongSearchResult, song: LyricSongContext): Int =
        score(result, song.scoringIdentity())

    /** [score] against an identity that was already normalized for this search. */
    fun score(result: SongSearchResult, identity: LyricScoringIdentity): Int {
        val raw = LyricMatchPolicy.calculateScore(
            song = result,
            cleanLocalTitle = identity.cleanTitle,
            localArtists = identity.artists,
            localFeatures = identity.features,
            localDurationMs = identity.durationMs,
            cleanLocalAlbum = identity.cleanAlbum,
        )
        return raw.coerceIn(MIN_MATCH_SCORE, MAX_MATCH_SCORE)
    }

    /**
     * Resolves a persisted key to one of the lane's options, or null when the key
     * no longer names anything. The host uses this when it applies a stored
     * selection on a song change: it must apply exactly what the sheet shows, and
     * a dangling key must never reach an apply path.
     */
    fun option(
        input: LyricSourceInput,
        lane: LyricLane,
        key: String?,
        locale: Locale = Locale.getDefault(),
    ): LyricSourceOption? {
        val sanitized = LyricSourceKeys.sanitize(key) ?: return null
        if (LyricSourceKeys.isHidden(sanitized)) return null
        return options(input, lane, locale).firstOrNull { it.key == sanitized }
    }

    /** True when the source behind [option] can serve [lane]. */
    fun serves(option: LyricSourceOption, lane: LyricLane): Boolean = when (lane) {
        LyricLane.ORIGINAL -> true
        LyricLane.TRANSLATION -> option.hasTranslation
        LyricLane.ROMANIZATION -> option.hasRomanization
    }

    /**
     * The value to persist for a lane's current state: the option key, or the
     * reserved [LyricSourceKeys.HIDDEN] marker when the lane shows nothing.
     * Returns null when the lane has neither (the original lane's empty state),
     * which the caller stores as "no override".
     */
    fun persistedKey(laneState: LyricLaneState): String? =
        laneState.selected?.key
            ?: if (laneState.lane.isSupplement) LyricSourceKeys.HIDDEN else null

    private fun servesFile(record: CustomLyricsSourceRecord, lane: LyricLane): Boolean = when (lane) {
        LyricLane.ORIGINAL -> true
        LyricLane.TRANSLATION -> record.hasTranslation
        LyricLane.ROMANIZATION -> record.hasRomanization
    }

    private fun servesCandidate(candidate: OnlineLyricsCandidate, lane: LyricLane): Boolean =
        when (lane) {
            LyricLane.ORIGINAL -> true
            LyricLane.TRANSLATION -> candidate.hasTranslation
            LyricLane.ROMANIZATION -> candidate.hasRomanization
        }

    private fun fileOption(
        record: CustomLyricsSourceRecord,
        lane: LyricLane,
        locale: Locale,
    ): LyricSourceOption = LyricSourceOption(
        key = LyricSourceKeys.customFile(record.fileId),
        kind = LyricSourceKind.CUSTOM_FILE,
        providerId = record.providerId,
        label = LyricSourceText.customFileLabel(record.providerId, record.userEdited, locale),
        hasWords = record.hasWords,
        hasTranslation = record.hasTranslation,
        hasRomanization = record.hasRomanization,
        detail = LyricSourceText.customFileDetail(record.providerId, record.displayName, locale),
    )

    private fun cacheOption(
        record: CustomLyricsSourceRecord,
        lane: LyricLane,
        locale: Locale,
    ): LyricSourceOption = LyricSourceOption(
        key = LyricSourceKeys.cache(record.fileId),
        kind = LyricSourceKind.CACHE,
        providerId = LyricSourceProviders.CACHE,
        label = LyricSourceText.providerLabel(LyricSourceProviders.CACHE, locale),
        hasWords = record.hasWords,
        hasTranslation = record.hasTranslation,
        hasRomanization = record.hasRomanization,
        detail = LyricSourceText.cacheDetail(record.displayName, locale),
    )

    /**
     * The placeholder for an applied online source. The provider is read back out of
     * the key, so the brand the sheet shows and the apply path the host takes still
     * belong to the provider the user actually picked.
     */
    private fun onlinePlaceholder(
        lane: LyricLane,
        key: String,
        locale: Locale,
    ): LyricSourceOption? {
        val providerId = LyricSourceKeys.onlineProvider(key) ?: return null
        return LyricSourceOption(
            key = key,
            kind = LyricSourceKind.ONLINE,
            providerId = providerId,
            label = LyricSourceText.providerLabel(providerId, locale),
            // Precision of a document that is not in this snapshot is unknown, so it
            // is reported as line-level rather than guessed as word-level.
            hasWords = false,
            hasTranslation = lane == LyricLane.TRANSLATION,
            hasRomanization = lane == LyricLane.ROMANIZATION,
            detail = LyricSourceText.appliedOnlineDetail(lane, locale),
        )
    }

    /**
     * The placeholder for a stored custom-lyrics key whose record still exists.
     * The key itself is the proof that the document serves this lane, so the lane
     * flag is set even when the probe could not read the document body.
     */
    private fun documentPlaceholder(
        input: LyricSourceInput,
        lane: LyricLane,
        key: String,
        locale: Locale,
    ): LyricSourceOption? {
        val fileId = LyricSourceKeys.customFileId(key) ?: return null
        val record = input.customFiles.firstOrNull { it.fileId == fileId } ?: return null
        val base = if (record.isAutoCache) {
            cacheOption(record, lane, locale)
        } else {
            fileOption(record, lane, locale)
        }
        return base.copy(
            key = key,
            hasTranslation = base.hasTranslation || lane == LyricLane.TRANSLATION,
            hasRomanization = base.hasRomanization || lane == LyricLane.ROMANIZATION,
        )
    }

    private fun add(
        options: LinkedHashMap<String, LyricSourceOption>,
        option: LyricSourceOption?,
    ) {
        if (option == null) return
        if (!options.containsKey(option.key)) options[option.key] = option
    }

    /**
     * A user's own (edited) document first, then by display name, so the list is
     * stable across runs and the document the user recognizes is at hand.
     */
    private val FILE_ORDER: Comparator<CustomLyricsSourceRecord> =
        compareByDescending<CustomLyricsSourceRecord> { it.userEdited }
            .thenBy { it.displayName.lowercase(Locale.ROOT) }
            .thenBy { it.fileId }

    /** Higher score first, then the module's own provider chain, then the title. */
    private val ONLINE_ORDER: Comparator<Pair<OnlineLyricsCandidate, Int>> =
        compareByDescending<Pair<OnlineLyricsCandidate, Int>> { it.second }
            .thenBy { LyricSourceProviders.onlineRank(it.first.providerId) }
            .thenBy { it.first.result.title.lowercase(Locale.ROOT) }
}
