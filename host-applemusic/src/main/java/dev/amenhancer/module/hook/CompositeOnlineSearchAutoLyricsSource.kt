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
import dev.amenhancer.module.lyrics.source.AutoLyricsSource
import dev.amenhancer.module.lyrics.source.LyricHttpTransport
import dev.amenhancer.module.model.CustomLyricsSources
import io.github.proify.lyricon.amprovider.xposed.MediaMetadataCache
import java.util.concurrent.Callable
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
 * Fail-open is per provider: a throwing or timing-out search contributes no
 * candidate, and a throwing or empty lyric fetch makes the chain try the next
 * provider in first-passing mode or fall back to null in global-best mode. The
 * outer [fetch] never lets an exception escape.
 *
 * [fetchTranslationCandidates] is the translation-required variant of the same
 * chain: a candidate whose fetched lyrics carry no usable translation lane does
 * not pass, so first-passing keeps walking providers and global-best keeps only
 * translation-bearing passers. It is fail-open too, returning an empty list.
 *
 * Every candidate is scored with the local album supplied by [localAlbum], so
 * title plus artist plus album can carry the score on their own and the album
 * component is no longer structurally zero. The scorer's own lines — the query
 * and the per-component breakdown of each provider's top candidates — are
 * bounded per track by [TrackScopedDiagnostics].
 */
class CompositeOnlineSearchAutoLyricsSource private constructor(
    private val mode: LyricSelectionMode,
    private val providers: List<OnlineLyricProvider>,
    private val currentTrack: () -> CurrentSongDetails?,
    private val localAlbum: (Long) -> String?,
    private val searchExecutor: ExecutorService,
    private val searchBudgetMs: Long,
    private val diagnostic: (String) -> Unit = {},
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
        return when (mode) {
            LyricSelectionMode.FIRST_PASSING -> firstPassingTranslations(appleMusicId, request)
            LyricSelectionMode.GLOBAL_BEST -> globalBestTranslations(appleMusicId, request)
        }
    }

    private fun fetchOrNull(appleMusicId: Long): String? {
        if (appleMusicId <= 0L || providers.isEmpty()) return null
        val request = searchRequest(appleMusicId) ?: return null
        return when (mode) {
            LyricSelectionMode.FIRST_PASSING -> firstPassing(appleMusicId, request)
            LyricSelectionMode.GLOBAL_BEST -> globalBest(appleMusicId, request)
        }
    }

    /**
     * Resolves the verified current track into a search request, or null. The
     * local album is the region/original-metadata album the current track
     * resolved to, so the album component can actually corroborate a candidate
     * instead of always contributing zero.
     */
    private fun searchRequest(appleMusicId: Long): SearchRequest? {
        val track = currentTrack()?.takeIf { it.appleMusicId == appleMusicId } ?: return null
        // Apple metadata carries invisible code points in the wild. Strip them
        // once here so the keyword, the fallback keyword and the identity the
        // scorer sees are the same clean strings.
        val title = LyricMatchPolicy.stripInvisible(track.title?.trim().orEmpty())
        if (title.isEmpty()) return null
        val artist = LyricMatchPolicy.stripInvisible(track.artist?.trim().orEmpty())
        val album = LyricMatchPolicy.stripInvisible(
            runCatching { localAlbum(appleMusicId) }.getOrNull()?.trim().orEmpty(),
        )
        val localArtists = LyricMatchPolicy.splitArtists(artist)
            .map { LyricMatchPolicy.cleanString(it) }
            .filter(String::isNotEmpty)
        val primaryArtist = LyricMatchPolicy.primaryArtist(artist)
        val creditlessTitle = LyricMatchPolicy.stripTrailingFeatureCredit(title)
        val multiCredit = LyricMatchPolicy.isMultiCreditArtist(localArtists)
        val request = SearchRequest(
            // Query with the first credited artist: a long credit list buries
            // the performer the provider indexes, like HLE's narrow query.
            keyword = listOf(title, primaryArtist)
                .filter(String::isNotEmpty)
                .joinToString(" "),
            // One bounded fallback per provider. A title carrying a feature
            // credit is retried without it plus the primary artist, because the
            // credited title is itself what buries the provider's indexed
            // title; otherwise HLE's multi-credit fallback sends the title plus
            // the original album, or the title alone when no album resolved.
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
            durationMs = track.durationMs,
            localTitle = title,
            localArtist = artist,
            localAlbum = album,
            cleanTitle = LyricMatchPolicy.cleanString(title),
            localArtists = localArtists,
            localFeatures = LyricMatchPolicy.featuresOf(title),
            cleanLocalAlbum = LyricMatchPolicy.normalizeAlbumForComparison(album),
        )
        scopedDiagnostic.log(
            appleMusicId,
            OnlineMatchDiagnostics.queryLine(
                appleMusicId = appleMusicId,
                keyword = request.keyword,
                localTitle = request.localTitle,
                localArtist = request.localArtist,
                localAlbum = request.localAlbum,
                localDurationMs = request.durationMs,
                localDurationRaw = track.durationRaw,
                localDurationUnit = track.durationUnit,
            ),
        )
        return request
    }

    /** Ordered walk that skips any provider whose lyrics carry no translation. */
    private fun firstPassingTranslations(
        appleMusicId: Long,
        request: SearchRequest,
    ): List<OnlineTranslationCandidate> {
        providers.forEach { provider ->
            val searched = searchBounded(listOf(provider), request).firstOrNull()
                ?: ProviderCandidates(provider, emptyList())
            val selected = LyricMatchPolicy.selectFirstPassing(
                searched.candidates.map(ScoredCandidate::scoredSong),
            )
            diagnosticSearch(appleMusicId, searched, selected != null)
            if (selected == null) return@forEach
            val candidate = translationCandidate(provider, selected, appleMusicId) ?: return@forEach
            return listOf(candidate)
        }
        return emptyList()
    }

    /** Every passing candidate that carries a translation, best score first. */
    private fun globalBestTranslations(
        appleMusicId: Long,
        request: SearchRequest,
    ): List<OnlineTranslationCandidate> {
        val byProvider = searchBounded(providers, request)
        byProvider.forEach { searched ->
            val selected = searched.candidates
                .filter { it.score >= LyricMatchPolicy.PASS_SCORE }
                .maxByOrNull(ScoredCandidate::score)
            diagnosticSearch(appleMusicId, searched, selected != null)
        }
        val passing = byProvider.flatMap { searched ->
            searched.candidates.map { ProviderCandidate(searched.provider, it) }
        }
            .filter { it.candidate.score >= LyricMatchPolicy.PASS_SCORE }
            .sortedByDescending { it.candidate.score }
        return passing.mapNotNull { entry ->
            translationCandidate(entry.provider, entry.candidate.song, appleMusicId)
        }
    }

    /**
     * One summary line per source, then the per-component breakdown of its
     * top-scoring candidates, so a below-floor verdict is no longer guesswork.
     * Both go through [scopedDiagnostic], so the per-track budget still bounds
     * however long the provider's result list is.
     */
    private fun diagnosticSearch(
        appleMusicId: Long,
        searched: ProviderCandidates,
        passed: Boolean,
    ) {
        val provider = searched.provider
        val candidates = searched.candidates
        logRetry(appleMusicId, searched)
        val best = candidates.maxOfOrNull(ScoredCandidate::score)
        scopedDiagnostic.log(
            appleMusicId,
            "online-translation source id=$appleMusicId source=${provider.sourceId} " +
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

    /** One provider's aligned lanes, or null when it contributes no translation. */
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
        if (lines.none { OnlineTranslationContentPolicy.isMeaningful(it.translation) }) {
            scopedDiagnostic.log(
                appleMusicId,
                "online-translation candidate id=$appleMusicId source=${provider.sourceId} " +
                    "reason=no_meaningful_translation lines=${lines.size}",
            )
            return null
        }
        scopedDiagnostic.log(
            appleMusicId,
            "online-translation candidate id=$appleMusicId source=${provider.sourceId} " +
                "reason=accepted lines=${lines.size}",
        )
        OnlineTranslationCandidate(provider.source.sourceType, lines)
    }.getOrNull()

    /** Ordered walk, short-circuiting on the first provider with usable lyrics. */
    private fun firstPassing(appleMusicId: Long, request: SearchRequest): String? {
        providers.forEach { provider ->
            val searched = searchBounded(listOf(provider), request).firstOrNull()
                ?: ProviderCandidates(provider, emptyList())
            logRetry(appleMusicId, searched)
            val selected = LyricMatchPolicy.selectFirstPassing(
                searched.candidates.map(ScoredCandidate::scoredSong),
            ) ?: return@forEach
            val ttml = render(provider, selected, request.durationMs)
            if (ttml != null) return ttml
        }
        return null
    }

    /** One bounded fan-out, then the single best candidate across every provider. */
    private fun globalBest(appleMusicId: Long, request: SearchRequest): String? {
        val byProvider = searchBounded(providers, request)
        byProvider.forEach { searched -> logRetry(appleMusicId, searched) }
        val all = byProvider.flatMap { searched ->
            searched.candidates.map { ProviderCandidate(searched.provider, it) }
        }
        val winner = LyricMatchPolicy.selectGlobalBestScored(all) { it.candidate.score }
            ?: return null
        return render(winner.provider, winner.candidate.song, request.durationMs)
    }

    /** Reports the one fallback query a provider issued, when it issued one. */
    private fun logRetry(appleMusicId: Long, searched: ProviderCandidates) {
        val fallback = searched.retriedKeyword ?: return
        scopedDiagnostic.log(
            appleMusicId,
            "online-translation retry id=$appleMusicId source=${searched.provider.sourceId} " +
                "keyword=\"$fallback\"",
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
    ): List<ProviderCandidates> {
        if (targets.isEmpty() || searchBudgetMs <= 0L) return emptyList()
        val tasks = targets.map { provider ->
            Callable { searchProvider(provider, request) }
        }
        return runCatching {
            searchExecutor.invokeAll(tasks, searchBudgetMs, TimeUnit.MILLISECONDS)
        }.getOrElse { emptyList() }
            .mapNotNull { future -> runCatching { future.get() }.getOrNull() }
    }

    /**
     * One provider's scored candidates, with at most one fallback search. The
     * fallback keeps HLE's bounded shape (`OnlineLyricTargeter.evaluateSource`:
     * one alternate keyword, then [betterProviderCandidates]) and adds the
     * feature-credit variant for a title whose credit buries the provider's
     * indexed name. A track therefore costs at most two searches per provider,
     * and the whole fan-out stays inside the same `invokeAll` deadline. Any
     * search failure means "no candidates".
     */
    private fun searchProvider(
        provider: OnlineLyricProvider,
        request: SearchRequest,
    ): ProviderCandidates = runCatching {
        val primary = searchProviderOnce(provider, request, request.keyword)
        val fallback = retryKeywordFor(request, primary) ?: return@runCatching primary
        val retry = searchProviderOnce(provider, request, fallback)
        betterProviderCandidates(request, primary, retry).copy(retriedKeyword = fallback)
    }.getOrElse { ProviderCandidates(provider, emptyList()) }

    /** One search request, scored with the same local identity as its sibling. */
    private fun searchProviderOnce(
        provider: OnlineLyricProvider,
        request: SearchRequest,
        keyword: String,
    ): ProviderCandidates = runCatching {
        val songs = provider.source.search(keyword = keyword, durationMs = request.durationMs)
        ProviderCandidates(
            provider = provider,
            candidates = songs.map { song ->
                val breakdown = scoreBreakdown(song, request)
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
        request: SearchRequest,
        primary: ProviderCandidates,
    ): String? {
        val retry = request.retry ?: return null
        val best = primary.candidates.maxByOrNull(ScoredCandidate::score)
        val passing = best != null && best.score >= LyricMatchPolicy.PASS_SCORE
        if (passing) return null
        if (!retry.artistMissRequired) return retry.keyword
        val artistMatched = best != null && LyricMatchPolicy.hasCommonArtist(
            request.localArtists,
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

    private fun scoreBreakdown(song: SongSearchResult, request: SearchRequest): ScoreBreakdown =
        LyricMatchPolicy.scoreBreakdown(
            song = song,
            cleanLocalTitle = request.cleanTitle,
            localArtists = request.localArtists,
            localFeatures = request.localFeatures,
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
        val keyword: String,
        /** The one fallback query, or null when this track gets no retry. */
        val retry: Retry?,
        val durationMs: Long,
        val localTitle: String,
        val localArtist: String,
        val localAlbum: String,
        val cleanTitle: String,
        val localArtists: List<String>,
        val localFeatures: List<String>,
        val cleanLocalAlbum: String,
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

    companion object {
        fun create(
            mode: LyricSelectionMode,
            providers: List<OnlineLyricProvider>,
            currentTrack: () -> CurrentSongDetails?,
            localAlbum: (Long) -> String? = ::originalAlbumOfCurrentTrack,
            searchExecutor: ExecutorService = defaultSearchExecutor(providers.size),
            searchBudgetMs: Long = ONLINE_SEARCH_BUDGET_MS,
            diagnostic: (String) -> Unit = {},
        ): CompositeOnlineSearchAutoLyricsSource =
            CompositeOnlineSearchAutoLyricsSource(
                mode = mode,
                providers = providers,
                currentTrack = currentTrack,
                localAlbum = localAlbum,
                searchExecutor = searchExecutor,
                searchBudgetMs = searchBudgetMs,
                diagnostic = diagnostic,
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
 * The album the region/original-metadata path resolved for [appleMusicId],
 * read through the same cache the metadata override path keeps its original
 * metadata in. A missing entry is simply "no album"; the search never fails
 * because the metadata has not resolved yet.
 */
private fun originalAlbumOfCurrentTrack(appleMusicId: Long): String? = runCatching {
    MediaMetadataCache.getMetadataById(appleMusicId.toString())?.originalAlbum
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
