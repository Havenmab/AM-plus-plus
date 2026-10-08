package dev.amenhancer.module.hook

import dev.amenhancer.module.CurrentSongDetails
import dev.amenhancer.module.lyrics.online.AppleLyricTtmlWriter
import dev.amenhancer.module.lyrics.online.InMemoryNeSessionStore
import dev.amenhancer.module.lyrics.online.KugouSource
import dev.amenhancer.module.lyrics.online.KuwoSource
import dev.amenhancer.module.lyrics.online.LyricMatchPolicy
import dev.amenhancer.module.lyrics.online.LyricSelectionMode
import dev.amenhancer.module.lyrics.online.NeSessionStore
import dev.amenhancer.module.lyrics.online.NeSource
import dev.amenhancer.module.lyrics.online.OnlineMatchDiagnostics
import dev.amenhancer.module.lyrics.online.OnlineTranslationCandidate
import dev.amenhancer.module.lyrics.online.OnlineTranslationContentPolicy
import dev.amenhancer.module.lyrics.online.OnlineTranslationExtraction
import dev.amenhancer.module.lyrics.online.QmSource
import dev.amenhancer.module.lyrics.online.ScoreBreakdown
import dev.amenhancer.module.lyrics.online.ScoredSong
import dev.amenhancer.module.lyrics.online.SearchLyricsSource
import dev.amenhancer.module.lyrics.online.SongSearchResult
import dev.amenhancer.module.lyrics.online.TrackScopedDiagnostics
import dev.amenhancer.module.lyrics.online.resolveMetadataSearchOrder
import dev.amenhancer.module.lyrics.online.shouldRetryWithOriginalMetadata
import dev.amenhancer.module.lyrics.source.AutoLyricsSource
import dev.amenhancer.module.lyrics.source.LyricHttpTransport
import dev.amenhancer.module.model.CustomLyricsSources
import io.github.proify.lyricon.amprovider.xposed.MediaMetadataCache
import io.github.proify.lyricon.amprovider.xposed.ProviderLogger
import java.util.concurrent.Callable
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.ExecutorService
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.ThreadPoolExecutor
import java.util.concurrent.TimeUnit

/** One enabled search provider plus the setting id it was resolved under. */
data class OnlineLyricProvider(
    val sourceId: String,
    val source: SearchLyricsSource,
)

/**
 * The published source id for a lyric that the cross-provider chain resolved.
 * One resolver entry now covers all enabled providers, so the winning provider
 * is deliberately not part of the name; the manifest policy already folds the
 * previous per-provider ids into the same manually-managed bucket.
 */
const val ONLINE_SEARCH_LYRIC_SOURCE = "online-search"

/**
 * The published source id for a translation lane the chain resolved. Only a
 * provider whose lyrics carry a usable translation counts, and the merged
 * document keeps Apple's displayed lines.
 */
const val ONLINE_TRANSLATION_LYRIC_SOURCE = "online-translation"

/**
 * The whole search fan-out settles inside this budget. Four searches run with
 * bounded concurrency, so a slow or broken provider can hold the chain for at
 * most this long regardless of how long its socket read lingers.
 */
const val ONLINE_SEARCH_BUDGET_MS = 5_000L

/** Bounded concurrency for the global-best fan-out; one thread per provider at most. */
private const val MAX_PARALLEL_SEARCHES = 4

/**
 * How many searches one provider may issue for one track across every metadata
 * pass. The primary attempt plus its single fallback already cost two (the
 * previous cap); the original-metadata pass may add exactly one more, so the
 * extra pass cannot multiply the `invokeAll` budget. Two passes therefore cost
 * at most three searches per provider, never four.
 */
private const val MAX_SEARCHES_PER_PROVIDER = 3

/** HLE's label for the displayed metadata pass. */
private const val CURRENT_METADATA_LABEL = "当前元数据"

/** HLE's label for Apple's internal original-metadata pass. */
private const val ORIGINAL_METADATA_LABEL = "Apple 内部原名"

/**
 * The four selectable search providers behind one [AutoLyricsSource].
 *
 * Keeping the providers in one object is what lets the strategy span sources:
 * the resolver seam only sees this single entry and returns its TTML, so a
 * per-provider entry could never compare candidates across providers.
 *
 * [LyricSelectionMode.FIRST_PASSING] walks the resolved order and stops at the
 * first provider whose first passing candidate yields usable lyrics, exactly
 * like the previous one-entry-per-provider chain. It searches sequentially and
 * bounds each provider's search by [searchBudgetMs].
 *
 * [LyricSelectionMode.GLOBAL_BEST] searches every enabled provider with
 * bounded concurrency inside one overall [searchBudgetMs] budget, scores every
 * candidate with [LyricMatchPolicy], and fetches lyrics only from the single
 * highest-scoring passing candidate's provider. The losing providers are only
 * ever searched, never fetched.
 *
 * Every search is ordered over the metadata passes HLE defines: the displayed
 * metadata first (`当前元数据`), then Apple's internal original metadata
 * (`Apple 内部原名`) when the cache resolved one that really differs. HLE
 * always runs both passes; the fork deliberately runs the second only when the
 * first produced no passing candidate at all, so the extra pass cannot fire on
 * every track. As in HLE a blank original field falls back to the displayed
 * value, and the labeled passes share one per-track, per-provider search
 * budget ([MAX_SEARCHES_PER_PROVIDER]). Every pass is announced on the visible
 * [visibleLog] channel, never on the budgeted [diagnostic] one.
 *
 * Fail-open is per provider: a throwing or timing-out search contributes no
 * candidate, and a throwing or empty lyric fetch makes the chain try the next
 * provider in first-passing mode or fall back to null in global-best mode. The
 * outer [fetch] never lets an exception escape.
 *
 * The auto-lyrics [fetch] additionally refuses to replace a document Apple
 * already synchronises: when the currently displayed document (see
 * [displayedTtml]) carries per-line or per-word timing, the search path returns
 * null without touching a provider and logs the skip. The
 * translation-required [fetchTranslationCandidates] is deliberately not gated
 * by that rule, because the translation lane only augments the displayed
 * document.
 *
 * [fetchTranslationCandidates] is the translation-required variant of the same
 * chain: a candidate whose fetched lyrics carry neither a usable translation nor
 * a romanization lane does not pass, so first-passing keeps walking providers
 * and global-best keeps only lane-bearing passers. Translation-bearing
 * candidates still win over pronunciation-only ones, so the translation-only
 * behaviour is unchanged; the pronunciation-only fallback exists because a
 * provider often returns only the pronunciation column for a song Apple already
 * translated. It is fail-open too, returning an empty list.
 *
 * Every candidate is scored with the local album supplied by [originalMetadata],
 * so title plus artist plus album can carry the score on their own and the
 * album component is no longer structurally zero. The scorer's own lines — the
 * query and the per-component breakdown of each provider's top candidates — are
 * bounded per track by [TrackScopedDiagnostics].
 */
class CompositeOnlineSearchAutoLyricsSource private constructor(
    private val mode: LyricSelectionMode,
    private val providers: List<OnlineLyricProvider>,
    private val currentTrack: () -> CurrentSongDetails?,
    private val originalMetadata: (Long) -> AppleOriginalMetadata?,
    private val searchExecutor: ExecutorService,
    private val searchBudgetMs: Long,
    private val diagnostic: (String) -> Unit = {},
    /**
     * Raw TTML of the document Apple Music is currently showing for the track,
     * keyed by Adam ID — the same recorded document the translation pass reads.
     * Null/absent means Apple has no document, so the search path stays open.
     */
    private val displayedTtml: (Long) -> String? = { null },
    /**
     * The visible info channel the metadata passes are labelled on. It is
     * deliberately separate from [diagnostic]: the latter is bounded per track
     * and may drop the pass announcement. Defaults to the host's
     * [ProviderLogger.info], the same visible channel HLE logs its retry on.
     */
    private val visibleLog: (String) -> Unit = ProviderLogger::info,
) {

    /** The scorer's own lines are bounded per track, like the rest of the chain's. */
    private val scopedDiagnostic = TrackScopedDiagnostics(diagnostic)

    /** The single chain entry; line timing is allowed because LRC may lack word markers. */
    fun autoLyricsSource(): AutoLyricsSource = AutoLyricsSource(
        name = ONLINE_SEARCH_LYRIC_SOURCE,
        acceptsLineTiming = true,
        fetch = ::fetch,
    )

    /** Never throws: a failed supplement must leave the native lyrics untouched. */
    fun fetch(appleMusicId: Long): String? =
        runCatching { fetchOrNull(appleMusicId) }.getOrNull()

    /**
     * The translation-required variant of the same chain: only a provider whose
     * fetched lyric body carries a usable translation lane counts as passing, so
     * the chain keeps looking when a candidate has none. Never throws; an empty
     * list means "no source could supply a translation".
     *
     * First-passing keeps the ordered walk and stops at the first provider that
     * both passes the score floor and carries a translation. Global-best scores
     * every provider, keeps the passing candidates in descending score order and
     * returns every one that carries a translation, so the pure selector can rank
     * them by translation quality.
     */
    fun fetchTranslationCandidates(appleMusicId: Long): List<OnlineTranslationCandidate> =
        runCatching { translationCandidatesOrNull(appleMusicId) }.getOrDefault(emptyList())

    private fun translationCandidatesOrNull(appleMusicId: Long): List<OnlineTranslationCandidate> {
        if (appleMusicId <= 0L || providers.isEmpty()) {
            scopedDiagnostic.log(appleMusicId, "online-translation fetch id=$appleMusicId reason=no_provider")
            return emptyList()
        }
        val request = searchRequest(appleMusicId) ?: run {
            scopedDiagnostic.log(
                appleMusicId,
                "online-translation fetch id=$appleMusicId reason=no_track_identity",
            )
            return emptyList()
        }
        val ledger = SearchLedger()
        return when (mode) {
            LyricSelectionMode.FIRST_PASSING -> firstPassingTranslations(appleMusicId, request, ledger)
            LyricSelectionMode.GLOBAL_BEST -> globalBestTranslations(appleMusicId, request, ledger)
        }
    }

    private fun fetchOrNull(appleMusicId: Long): String? {
        if (appleMusicId <= 0L || providers.isEmpty()) return null
        // Product rule: the third-party search sources may only fill in when
        // Apple has no document or only unsynchronised text. A document that
        // already carries per-line or per-word timing is never replaced by a
        // search result, so the providers are neither searched nor fetched.
        val displayed = runCatching { displayedTtml(appleMusicId) }.getOrNull()
        if (!displayed.isNullOrBlank() && TtmlTimingPolicy.hasTiming(displayed)) {
            scopedDiagnostic.log(
                appleMusicId,
                "online-translation block id=$appleMusicId reason=apple_has_timed_lyrics " +
                    "timing=${TtmlTimingPolicy.timingKindOf(displayed)}",
            )
            return null
        }
        val request = searchRequest(appleMusicId) ?: return null
        val ledger = SearchLedger()
        return when (mode) {
            LyricSelectionMode.FIRST_PASSING -> firstPassing(appleMusicId, request, ledger)
            LyricSelectionMode.GLOBAL_BEST -> globalBest(appleMusicId, request, ledger)
        }
    }

    /**
     * Resolves the verified current track into a search request, or null. The
     * local album and the original title/artist are read from the same Apple
     * metadata entry, so the album component can corroborate a candidate instead
     * of always contributing zero and the original-metadata pass searches with
     * the internal name the displayed one hid.
     *
     * The pass order comes from HLE's [resolveMetadataSearchOrder] with
     * `preferOriginalMetadata = false`: the displayed metadata first, the
     * distinct original second. A blank original field falls back to the
     * displayed value (HLE's rule), and the original pass only exists when
     * [shouldRetryWithOriginalMetadata] says the two really differ.
     */
    private fun searchRequest(appleMusicId: Long): SearchRequest? {
        val track = currentTrack()?.takeIf { it.appleMusicId == appleMusicId } ?: return null
        // Apple metadata carries invisible code points in the wild. Strip them
        // once here so the keyword, the fallback keyword and the identity the
        // scorer sees are the same clean strings.
        val title = LyricMatchPolicy.stripInvisible(track.title?.trim().orEmpty())
        if (title.isEmpty()) return null
        val artist = LyricMatchPolicy.stripInvisible(track.artist?.trim().orEmpty())
        val apple = runCatching { originalMetadata(appleMusicId) }.getOrNull()
        val album = LyricMatchPolicy.stripInvisible(apple?.album?.trim().orEmpty())
        val current = searchMetadata(title, artist, album, CURRENT_METADATA_LABEL)
        val hasDistinctOriginalMetadata = shouldRetryWithOriginalMetadata(
            title = title,
            artist = artist,
            originalTitle = apple?.title,
            originalArtist = apple?.artist,
        )
        // HLE's blank fallback: each blank original field resolves to the
        // displayed value, so only the field that really differs is replaced.
        val originalTitle = apple?.title?.takeIf { it.isNotBlank() } ?: title
        val originalArtist = apple?.artist?.takeIf { it.isNotBlank() } ?: artist
        val original = if (!hasDistinctOriginalMetadata) {
            null
        } else {
            searchMetadata(
                title = LyricMatchPolicy.stripInvisible(originalTitle.trim()),
                artist = LyricMatchPolicy.stripInvisible(originalArtist.trim()),
                album = album,
                label = ORIGINAL_METADATA_LABEL,
            )
        }
        val passes = resolveMetadataSearchOrder(
            preferOriginalMetadata = false,
            hasDistinctOriginalMetadata = hasDistinctOriginalMetadata,
        ).map { useOriginal -> if (useOriginal) original!! else current }
        val request = SearchRequest(
            passes = passes,
            durationMs = track.durationMs,
            cleanLocalAlbum = LyricMatchPolicy.normalizeAlbumForComparison(album),
        )
        scopedDiagnostic.log(
            appleMusicId,
            OnlineMatchDiagnostics.queryLine(
                appleMusicId = appleMusicId,
                keyword = current.keyword,
                localTitle = current.title,
                localArtist = current.artist,
                localAlbum = album,
                localDurationMs = track.durationMs,
                localDurationRaw = track.durationRaw,
                localDurationUnit = track.durationUnit,
            ),
        )
        return request
    }

    /**
     * Builds one metadata pass: the query keyword (title plus the primary
     * credited artist) and the one bounded fallback, both derived from this
     * pass' title/artist. A title carrying a feature credit is retried without
     * it plus the primary artist, because the credited title is itself what
     * buries the provider's indexed title; otherwise HLE's multi-credit
     * fallback sends the title plus the original album, or the title alone when
     * no album resolved.
     */
    private fun searchMetadata(
        title: String,
        artist: String,
        album: String,
        label: String,
    ): SearchMetadata {
        val localArtists = LyricMatchPolicy.splitArtists(artist)
            .map { LyricMatchPolicy.cleanString(it) }
            .filter(String::isNotEmpty)
        val primaryArtist = LyricMatchPolicy.primaryArtist(artist)
        val creditlessTitle = LyricMatchPolicy.stripTrailingFeatureCredit(title)
        val multiCredit = LyricMatchPolicy.isMultiCreditArtist(localArtists)
        return SearchMetadata(
            label = label,
            title = title,
            artist = artist,
            keyword = listOf(title, primaryArtist)
                .filter(String::isNotEmpty)
                .joinToString(" "),
            retry = when {
                creditlessTitle != null -> Retry(
                    keyword = listOf(creditlessTitle, primaryArtist)
                        .filter(String::isNotEmpty)
                        .joinToString(" "),
                    // The credit, not a missing artist, is the suspect: retry
                    // whenever the first attempt produced nothing passing.
                    artistMissRequired = false,
                )
                multiCredit -> Retry(
                    keyword = listOf(title, album)
                        .filter(String::isNotEmpty)
                        .joinToString(" "),
                    artistMissRequired = true,
                )
                else -> null
            },
            cleanTitle = LyricMatchPolicy.cleanString(title),
            localArtists = localArtists,
            localFeatures = LyricMatchPolicy.featuresOf(title),
        )
    }

    /**
     * Ordered walk that skips any provider whose lyrics carry neither lane. A
     * translation-bearing candidate wins immediately; a pronunciation-only
     * candidate is remembered and only returned when no provider supplied a
     * translation, so the translation-only selection is unchanged.
     *
     * The displayed-metadata pass runs first; the original-metadata pass only
     * runs when the first found no candidate at or above the score floor.
     */
    private fun firstPassingTranslations(
        appleMusicId: Long,
        request: SearchRequest,
        ledger: SearchLedger,
    ): List<OnlineTranslationCandidate> {
        var firstPassFoundPassing = false
        request.passes.forEachIndexed { index, metadata ->
            if (index > 0 && firstPassFoundPassing) return@forEachIndexed
            logMetadataPass(appleMusicId, metadata)
            val outcome = firstPassingTranslationsPass(appleMusicId, request, metadata, ledger)
            if (outcome.candidates.isNotEmpty()) return outcome.candidates
            if (index == 0) firstPassFoundPassing = outcome.foundPassing
        }
        return emptyList()
    }

    /** One metadata pass of [firstPassingTranslations]. */
    private fun firstPassingTranslationsPass(
        appleMusicId: Long,
        request: SearchRequest,
        metadata: SearchMetadata,
        ledger: SearchLedger,
    ): TranslationPassOutcome {
        var foundPassing = false
        var pronunciationOnly: OnlineTranslationCandidate? = null
        providers.forEach { provider ->
            val searched = searchBounded(listOf(provider), request, metadata, ledger).firstOrNull()
                ?: ProviderCandidates(provider, emptyList())
            val selected = LyricMatchPolicy.selectFirstPassing(
                searched.candidates.map(ScoredCandidate::scoredSong),
            )
            diagnosticSearch(appleMusicId, searched, selected != null, metadata)
            if (selected == null) return@forEach
            foundPassing = true
            val candidate = translationCandidate(provider, selected, appleMusicId) ?: return@forEach
            if (candidate.hasTranslationLane()) {
                return TranslationPassOutcome(listOf(candidate), true)
            }
            // Pronunciation-only: keep walking providers for one that also
            // carries the translation lane, and fall back to this candidate only
            // when none does.
            if (pronunciationOnly == null) pronunciationOnly = candidate
        }
        return TranslationPassOutcome(listOfNotNull(pronunciationOnly), foundPassing)
    }

    /**
     * Every passing candidate that carries a translation, best score first. The
     * original-metadata pass is only reached when the displayed one produced no
     * score-passing candidate at all.
     */
    private fun globalBestTranslations(
        appleMusicId: Long,
        request: SearchRequest,
        ledger: SearchLedger,
    ): List<OnlineTranslationCandidate> {
        request.passes.forEach { metadata ->
            logMetadataPass(appleMusicId, metadata)
            val byProvider = searchBounded(providers, request, metadata, ledger)
            byProvider.forEach { searched ->
                val selected = searched.candidates
                    .filter { it.score >= LyricMatchPolicy.PASS_SCORE }
                    .maxByOrNull(ScoredCandidate::score)
                diagnosticSearch(appleMusicId, searched, selected != null, metadata)
            }
            val passing = byProvider.flatMap { searched ->
                searched.candidates.map { ProviderCandidate(searched.provider, it) }
            }
                .filter { it.candidate.score >= LyricMatchPolicy.PASS_SCORE }
            // A score-passing pass is a miss only for the translation lane; it
            // still must not trigger the original-metadata pass.
            if (passing.isNotEmpty()) {
                val accepted = passing.sortedByDescending { it.candidate.score }
                    .mapNotNull { entry ->
                        translationCandidate(entry.provider, entry.candidate.song, appleMusicId)
                    }
                // Translation-bearing candidates first, so a pronunciation-only
                // fallback can never displace the translation-only selection.
                val translated = accepted.filter { it.hasTranslationLane() }
                return translated.ifEmpty { accepted }
            }
        }
        return emptyList()
    }

    /**
     * One summary line per source, then the per-component breakdown of its
     * top-scoring candidates, so a below-floor verdict is no longer guesswork.
     * Every line carries the metadata pass it belongs to. All of it goes through
     * [scopedDiagnostic], so the per-track budget still bounds however long the
     * provider's result list is.
     */
    private fun diagnosticSearch(
        appleMusicId: Long,
        searched: ProviderCandidates,
        passed: Boolean,
        metadata: SearchMetadata,
    ) {
        val provider = searched.provider
        val candidates = searched.candidates
        logRetry(appleMusicId, searched, metadata)
        val best = candidates.maxOfOrNull(ScoredCandidate::score)
        scopedDiagnostic.log(
            appleMusicId,
            "online-translation source id=$appleMusicId source=${provider.sourceId} " +
                "pass=\"${metadata.label}\" " +
                "hits=${candidates.size} passing=" +
                "${candidates.count { it.score >= LyricMatchPolicy.PASS_SCORE }} " +
                "best=${best ?: "none"} " +
                "reason=${if (passed) "passing" else "below_score_floor"}",
        )
        candidates.sortedByDescending(ScoredCandidate::score)
            .take(OnlineMatchDiagnostics.MAX_LOGGED_CANDIDATES)
            .forEachIndexed { rank, candidate ->
                scopedDiagnostic.log(
                    appleMusicId,
                    OnlineMatchDiagnostics.scoreLine(
                        appleMusicId = appleMusicId,
                        sourceId = provider.sourceId,
                        rank = rank,
                        song = candidate.song,
                        breakdown = candidate.breakdown,
                    ),
                )
            }
    }

    /**
     * One provider's aligned lanes, or null when it contributes neither lane.
     * A translation-bearing candidate is preferred by the callers; a
     * pronunciation-only one is only the fallback, so a provider whose payload
     * carries just the romanization column can still fill a missing
     * transliterations lane on a song Apple already translated.
     */
    private fun translationCandidate(
        provider: OnlineLyricProvider,
        song: SongSearchResult,
        appleMusicId: Long,
    ): OnlineTranslationCandidate? = runCatching {
        val result = provider.source.getLyrics(song)
        if (result == null) {
            scopedDiagnostic.log(
                appleMusicId,
                "online-translation candidate id=$appleMusicId source=${provider.sourceId} " +
                    "reason=no_lyrics",
            )
            return null
        }
        val lines = OnlineTranslationExtraction.extract(result)
        val hasTranslation = lines.any {
            OnlineTranslationContentPolicy.isMeaningful(it.translation)
        }
        val hasPronunciation = lines.any { !it.romanization.isNullOrBlank() }
        if (!hasTranslation && !hasPronunciation) {
            scopedDiagnostic.log(
                appleMusicId,
                "online-translation candidate id=$appleMusicId source=${provider.sourceId} " +
                    "reason=no_meaningful_lane lines=${lines.size}",
            )
            return null
        }
        scopedDiagnostic.log(
            appleMusicId,
            "online-translation candidate id=$appleMusicId source=${provider.sourceId} " +
                "reason=accepted lines=${lines.size} " +
                "translation=$hasTranslation pronunciation=$hasPronunciation",
        )
        OnlineTranslationCandidate(provider.source.sourceType, lines)
    }.getOrNull()

    /** True when the candidate would fill a translation lane. */
    private fun OnlineTranslationCandidate.hasTranslationLane(): Boolean =
        lines.any { OnlineTranslationContentPolicy.isMeaningful(it.translation) }

    /**
     * Ordered walk, short-circuiting on the first provider with usable lyrics.
     * The displayed-metadata pass runs first; the original-metadata pass only
     * runs when the first produced no passing candidate.
     */
    private fun firstPassing(
        appleMusicId: Long,
        request: SearchRequest,
        ledger: SearchLedger,
    ): String? {
        var firstPassFoundPassing = false
        request.passes.forEachIndexed { index, metadata ->
            if (index > 0 && firstPassFoundPassing) return@forEachIndexed
            logMetadataPass(appleMusicId, metadata)
            val outcome = firstPassingPass(appleMusicId, request, metadata, ledger)
            if (outcome.ttml != null) return outcome.ttml
            if (index == 0) firstPassFoundPassing = outcome.foundPassing
        }
        return null
    }

    /** One metadata pass of [firstPassing]. */
    private fun firstPassingPass(
        appleMusicId: Long,
        request: SearchRequest,
        metadata: SearchMetadata,
        ledger: SearchLedger,
    ): PassOutcome {
        var foundPassing = false
        providers.forEach { provider ->
            val searched = searchBounded(listOf(provider), request, metadata, ledger).firstOrNull()
                ?: ProviderCandidates(provider, emptyList())
            logRetry(appleMusicId, searched, metadata)
            val selected = LyricMatchPolicy.selectFirstPassing(
                searched.candidates.map(ScoredCandidate::scoredSong),
            ) ?: return@forEach
            foundPassing = true
            val ttml = render(provider, selected, request.durationMs)
            if (ttml != null) return PassOutcome(ttml, true)
        }
        return PassOutcome(null, foundPassing)
    }

    /**
     * One bounded fan-out per metadata pass, then the single best candidate
     * across every provider. A pass with no passing candidate falls through to
     * the next pass.
     */
    private fun globalBest(
        appleMusicId: Long,
        request: SearchRequest,
        ledger: SearchLedger,
    ): String? {
        request.passes.forEach { metadata ->
            logMetadataPass(appleMusicId, metadata)
            val byProvider = searchBounded(providers, request, metadata, ledger)
            byProvider.forEach { searched -> logRetry(appleMusicId, searched, metadata) }
            val all = byProvider.flatMap { searched ->
                searched.candidates.map { ProviderCandidate(searched.provider, it) }
            }
            val winner = LyricMatchPolicy.selectGlobalBestScored(all) { it.candidate.score }
            if (winner != null) return render(winner.provider, winner.candidate.song, request.durationMs)
        }
        return null
    }

    /**
     * Announces the original-metadata pass on the visible channel, with HLE's
     * own label, so the next device log shows which pass was searched. Fail-open:
     * a broken log sink must never break the search.
     */
    private fun logMetadataPass(appleMusicId: Long, metadata: SearchMetadata) {
        runCatching {
            visibleLog(
                "online-lyrics metadata pass id=$appleMusicId " +
                    "pass=\"${metadata.label}\" " +
                    "title=\"${metadata.title}\" artist=\"${metadata.artist}\"",
            )
        }
    }

    /** Reports the one fallback query a provider issued, when it issued one. */
    private fun logRetry(
        appleMusicId: Long,
        searched: ProviderCandidates,
        metadata: SearchMetadata,
    ) {
        val fallback = searched.retriedKeyword ?: return
        scopedDiagnostic.log(
            appleMusicId,
            "online-translation retry id=$appleMusicId source=${searched.provider.sourceId} " +
                "pass=\"${metadata.label}\" keyword=\"$fallback\"",
        )
    }

    /**
     * Runs [targets]' searches with bounded concurrency and a single overall
     * deadline: the executor's pool caps parallelism, `invokeAll` returns as
     * soon as every worker finished or [searchBudgetMs] elapsed, and unfinished
     * futures are cancelled. A provider's own failure is folded into an empty
     * candidate list, so it cannot affect the others.
     */
    private fun searchBounded(
        targets: List<OnlineLyricProvider>,
        request: SearchRequest,
        metadata: SearchMetadata,
        ledger: SearchLedger,
    ): List<ProviderCandidates> {
        if (targets.isEmpty() || searchBudgetMs <= 0L) return emptyList()
        val tasks = targets.map { provider ->
            Callable { searchProvider(provider, request, metadata, ledger) }
        }
        return runCatching {
            searchExecutor.invokeAll(tasks, searchBudgetMs, TimeUnit.MILLISECONDS)
        }.getOrElse { emptyList() }
            .mapNotNull { future -> runCatching { future.get() }.getOrNull() }
    }

    /**
     * One provider's scored candidates for one metadata pass, with at most one
     * fallback search. The fallback keeps HLE's bounded shape
     * (`OnlineLyricTargeter.evaluateSource`: one alternate keyword, then
     * [betterProviderCandidates]) and adds the feature-credit variant for a
     * title whose credit buries the provider's indexed name.
     *
     * [ledger] caps the provider's total searches for the whole track, so the
     * original-metadata pass can add at most one more on top of the primary
     * attempt and its fallback; a spent budget simply contributes no candidates.
     * Any search failure means "no candidates".
     */
    private fun searchProvider(
        provider: OnlineLyricProvider,
        request: SearchRequest,
        metadata: SearchMetadata,
        ledger: SearchLedger,
    ): ProviderCandidates = runCatching {
        if (!ledger.tryReserve(provider)) return@runCatching ProviderCandidates(provider, emptyList())
        val primary = searchProviderOnce(provider, request, metadata, metadata.keyword)
        val fallback = retryKeywordFor(metadata, primary) ?: return@runCatching primary
        if (!ledger.tryReserve(provider)) return@runCatching primary
        val retry = searchProviderOnce(provider, request, metadata, fallback)
        betterProviderCandidates(request, primary, retry).copy(retriedKeyword = fallback)
    }.getOrElse { ProviderCandidates(provider, emptyList()) }

    /** One search request, scored with the same metadata pass' identity. */
    private fun searchProviderOnce(
        provider: OnlineLyricProvider,
        request: SearchRequest,
        metadata: SearchMetadata,
        keyword: String,
    ): ProviderCandidates = runCatching {
        val songs = provider.source.search(keyword = keyword, durationMs = request.durationMs)
        ProviderCandidates(
            provider = provider,
            candidates = songs.map { song ->
                val breakdown = scoreBreakdown(song, request, metadata)
                ScoredCandidate(song = song, score = breakdown.total, breakdown = breakdown)
            },
        )
    }.getOrElse { ProviderCandidates(provider, emptyList()) }

    /**
     * HLE's retry gate: no fallback while any candidate passed. The album
     * variant additionally keeps HLE's artist gate — it only fires while the
     * first attempt matched no artist either — while the feature-credit variant
     * fires on the miss alone, because the credited title, not a missing
     * artist, is what hid the provider's indexed name. A track with no
     * applicable variant is never retried, so the normal path is still a single
     * request per provider.
     */
    private fun retryKeywordFor(
        metadata: SearchMetadata,
        primary: ProviderCandidates,
    ): String? {
        val retry = metadata.retry ?: return null
        val best = primary.candidates.maxByOrNull(ScoredCandidate::score)
        val passing = best != null && best.score >= LyricMatchPolicy.PASS_SCORE
        if (passing) return null
        if (!retry.artistMissRequired) return retry.keyword
        val artistMatched = best != null && LyricMatchPolicy.hasCommonArtist(
            metadata.localArtists,
            LyricMatchPolicy.splitArtists(best.song.artist).map { LyricMatchPolicy.cleanString(it) },
        )
        return if (!artistMatched) retry.keyword else null
    }

    /**
     * HLE's `betterSourceAttempt`, applied to a scored candidate list: a
     * duration-close retry wins over a drifting first attempt, then the higher
     * score wins, otherwise the first attempt is kept.
     */
    private fun betterProviderCandidates(
        request: SearchRequest,
        first: ProviderCandidates,
        retry: ProviderCandidates,
    ): ProviderCandidates {
        val firstBest = first.candidates.maxByOrNull(ScoredCandidate::score) ?: return retry
        val retryBest = retry.candidates.maxByOrNull(ScoredCandidate::score) ?: return first
        val firstClose = LyricMatchPolicy.isStrongDurationMatch(
            request.durationMs,
            firstBest.song.duration,
        )
        val retryClose = LyricMatchPolicy.isStrongDurationMatch(
            request.durationMs,
            retryBest.song.duration,
        )
        return when {
            retryClose && !firstClose -> retry
            firstClose && !retryClose -> first
            retryBest.score > firstBest.score -> retry
            else -> first
        }
    }

    private fun scoreBreakdown(
        song: SongSearchResult,
        request: SearchRequest,
        metadata: SearchMetadata,
    ): ScoreBreakdown =
        LyricMatchPolicy.scoreBreakdown(
            song = song,
            cleanLocalTitle = metadata.cleanTitle,
            localArtists = metadata.localArtists,
            localFeatures = metadata.localFeatures,
            localDurationMs = request.durationMs,
            cleanLocalAlbum = request.cleanLocalAlbum,
        )

    private fun render(
        provider: OnlineLyricProvider,
        song: SongSearchResult,
        fallbackDurationMs: Long,
    ): String? = runCatching {
        val lines = AppleLyricTtmlWriter.from(provider.source.getLyrics(song) ?: return null)
        if (lines.isEmpty()) return null
        AppleLyricTtmlWriter.build(
            lines = lines,
            durationMs = song.duration.takeIf { it > 0L } ?: fallbackDurationMs,
        ).takeIf(String::isNotBlank)
    }.getOrNull()

    private data class SearchRequest(
        /**
         * The ordered metadata passes from HLE's [resolveMetadataSearchOrder]:
         * the displayed metadata first, the distinct original one second.
         */
        val passes: List<SearchMetadata>,
        val durationMs: Long,
        val cleanLocalAlbum: String,
    )

    /**
     * One metadata pass: its label, its identity and its own keyword variants.
     * The scorer compares each candidate against this pass' title/artist, so an
     * original-name candidate can pass even though the displayed name would not
     * have matched it.
     */
    private data class SearchMetadata(
        val label: String,
        val title: String,
        val artist: String,
        val keyword: String,
        /** The one fallback query, or null when this pass gets no retry. */
        val retry: Retry?,
        val cleanTitle: String,
        val localArtists: List<String>,
        val localFeatures: List<String>,
    )

    /**
     * One alternate keyword plus the gate it needs. [artistMissRequired] keeps
     * HLE's album-fallback gate (no passing candidate and no shared artist);
     * the feature-credit variant sets it false so it fires on the score miss.
     */
    private data class Retry(
        val keyword: String,
        val artistMissRequired: Boolean,
    )

    /** A scored candidate plus the component split the diagnostics report. */
    private data class ScoredCandidate(
        val song: SongSearchResult,
        val score: Int,
        val breakdown: ScoreBreakdown,
    ) {
        fun scoredSong(): ScoredSong = ScoredSong(song, score)
    }

    private data class ProviderCandidates(
        val provider: OnlineLyricProvider,
        val candidates: List<ScoredCandidate>,
        /** The fallback keyword that was searched, or null when the first attempt sufficed. */
        val retriedKeyword: String? = null,
    )

    private data class ProviderCandidate(
        val provider: OnlineLyricProvider,
        val candidate: ScoredCandidate,
    )

    /** One metadata pass' outcome, with whether it had any passing candidate. */
    private data class PassOutcome(
        val ttml: String?,
        val foundPassing: Boolean,
    )

    /** One translation pass' outcome, with whether it had any passing candidate. */
    private data class TranslationPassOutcome(
        val candidates: List<OnlineTranslationCandidate>,
        val foundPassing: Boolean,
    )

    /**
     * The per-track, per-provider search budget shared by both metadata passes.
     * The map is concurrent because the global-best fan-out searches providers
     * in parallel; the passes themselves run one after the other.
     */
    private class SearchLedger {
        private val used = ConcurrentHashMap<String, Int>()

        fun tryReserve(provider: OnlineLyricProvider): Boolean {
            val searches = used.compute(provider.sourceId) { _, current -> (current ?: 0) + 1 }
                ?: return false
            return searches <= MAX_SEARCHES_PER_PROVIDER
        }
    }

    companion object {
        fun create(
            mode: LyricSelectionMode,
            providers: List<OnlineLyricProvider>,
            currentTrack: () -> CurrentSongDetails?,
            originalMetadata: (Long) -> AppleOriginalMetadata? = ::originalMetadataOfCurrentTrack,
            searchExecutor: ExecutorService = defaultSearchExecutor(providers.size),
            searchBudgetMs: Long = ONLINE_SEARCH_BUDGET_MS,
            diagnostic: (String) -> Unit = {},
            displayedTtml: (Long) -> String? = { null },
            visibleLog: (String) -> Unit = ProviderLogger::info,
        ): CompositeOnlineSearchAutoLyricsSource =
            CompositeOnlineSearchAutoLyricsSource(
                mode = mode,
                providers = providers,
                currentTrack = currentTrack,
                originalMetadata = originalMetadata,
                searchExecutor = searchExecutor,
                searchBudgetMs = searchBudgetMs,
                diagnostic = diagnostic,
                displayedTtml = displayedTtml,
                visibleLog = visibleLog,
            )

        /** Daemon pool sized to the provider count so a stalled search cannot leak a live thread. */
        private fun defaultSearchExecutor(providerCount: Int): ExecutorService {
            val parallelism = providerCount.coerceIn(1, MAX_PARALLEL_SEARCHES)
            return ThreadPoolExecutor(
                parallelism,
                parallelism,
                30L,
                TimeUnit.SECONDS,
                LinkedBlockingQueue(),
                { runnable ->
                    Thread(runnable, "ampp-online-lyric-search").apply { isDaemon = true }
                },
            ).apply { allowCoreThreadTimeOut(true) }
        }
    }
}

/**
 * The Apple-internal (original-region) metadata for one track: the internal
 * title and artist the original-metadata pass searches with, plus the original
 * album the scorer already received. All three come from the same
 * [MediaMetadataCache] entry.
 */
data class AppleOriginalMetadata(
    val title: String? = null,
    val artist: String? = null,
    val album: String? = null,
)

/**
 * Apple's internal metadata for [appleMusicId], read through the same cache the
 * region/original-metadata path keeps its original title, artist and album in.
 * A missing entry is simply "no distinct original metadata" — the displayed
 * values fall back for every blank field — so the search never fails because
 * the region metadata has not resolved yet.
 */
private fun originalMetadataOfCurrentTrack(appleMusicId: Long): AppleOriginalMetadata? = runCatching {
    MediaMetadataCache.getMetadataById(appleMusicId.toString())?.let { metadata ->
        AppleOriginalMetadata(
            title = metadata.originalTitle,
            artist = metadata.originalArtist,
            album = metadata.originalAlbum,
        )
    }
}.getOrNull()

/**
 * Builds the provider for one source id, or null for an unknown id.
 *
 * The provider object is created here, so a caller must invoke this only for a
 * source that is actually enabled; the whole chain stays absent and inert when
 * the master toggle is off. [sessionStore] backs Netease's anonymous session.
 */
fun onlineLyricProviderFor(
    sourceId: String,
    transport: LyricHttpTransport,
    sessionStore: NeSessionStore = InMemoryNeSessionStore(),
): SearchLyricsSource? = when (sourceId) {
    CustomLyricsSources.NETEASE -> NeSource(transport, sessionStore)
    CustomLyricsSources.QQ -> QmSource(transport)
    CustomLyricsSources.KUWO -> KuwoSource(transport)
    CustomLyricsSources.KUGOU -> KugouSource(transport)
    else -> null
}
