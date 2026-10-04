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
import dev.amenhancer.module.lyrics.online.QmSource
import dev.amenhancer.module.lyrics.online.ScoredSong
import dev.amenhancer.module.lyrics.online.SearchLyricsSource
import dev.amenhancer.module.lyrics.online.SongSearchResult
import dev.amenhancer.module.lyrics.source.AutoLyricsSource
import dev.amenhancer.module.lyrics.source.LyricHttpTransport
import dev.amenhancer.module.model.CustomLyricsSources
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
 */
class CompositeOnlineSearchAutoLyricsSource private constructor(
    private val mode: LyricSelectionMode,
    private val providers: List<OnlineLyricProvider>,
    private val currentTrack: () -> CurrentSongDetails?,
    private val searchExecutor: ExecutorService,
    private val searchBudgetMs: Long,
) {

    /** The single chain entry; line timing is allowed because LRC may lack word markers. */
    fun autoLyricsSource(): AutoLyricsSource = AutoLyricsSource(
        name = ONLINE_SEARCH_LYRIC_SOURCE,
        acceptsLineTiming = true,
        fetch = ::fetch,
    )

    /** Never throws: a failed supplement must leave the native lyrics untouched. */
    fun fetch(appleMusicId: Long): String? =
        runCatching { fetchOrNull(appleMusicId) }.getOrNull()

    private fun fetchOrNull(appleMusicId: Long): String? {
        if (appleMusicId <= 0L || providers.isEmpty()) return null
        val track = currentTrack()?.takeIf { it.appleMusicId == appleMusicId } ?: return null
        val title = track.title?.trim().orEmpty()
        if (title.isEmpty()) return null
        val artist = track.artist?.trim().orEmpty()
        val request = SearchRequest(
            keyword = listOf(title, artist)
                .filter(String::isNotEmpty)
                .joinToString(" "),
            durationMs = track.durationMs,
            cleanTitle = LyricMatchPolicy.cleanString(title),
            localArtists = LyricMatchPolicy.splitArtists(artist)
                .map { LyricMatchPolicy.cleanString(it) }
                .filter(String::isNotEmpty),
            localFeatures = LyricMatchPolicy.featuresOf(title),
        )
        return when (mode) {
            LyricSelectionMode.FIRST_PASSING -> firstPassing(request)
            LyricSelectionMode.GLOBAL_BEST -> globalBest(request)
        }
    }

    /** Ordered walk, short-circuiting on the first provider with usable lyrics. */
    private fun firstPassing(request: SearchRequest): String? {
        providers.forEach { provider ->
            val candidates = searchBounded(listOf(provider), request).firstOrNull()?.candidates
                .orEmpty()
            val selected = LyricMatchPolicy.selectFirstPassing(candidates) ?: return@forEach
            val ttml = render(provider, selected, request.durationMs)
            if (ttml != null) return ttml
        }
        return null
    }

    /** One bounded fan-out, then the single best candidate across every provider. */
    private fun globalBest(request: SearchRequest): String? {
        val byProvider = searchBounded(providers, request)
        val all = byProvider.flatMap { searched ->
            searched.candidates.map { ProviderCandidate(searched.provider, it) }
        }
        val winner = LyricMatchPolicy.selectGlobalBestScored(all) { it.candidate.score }
            ?: return null
        return render(winner.provider, winner.candidate.song, request.durationMs)
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

    /** One provider's scored candidates; any search failure means "no candidates". */
    private fun searchProvider(
        provider: OnlineLyricProvider,
        request: SearchRequest,
    ): ProviderCandidates = runCatching {
        val songs = provider.source.search(
            keyword = request.keyword,
            durationMs = request.durationMs,
        )
        ProviderCandidates(
            provider = provider,
            candidates = songs.map { song -> ScoredSong(song, score(song, request)) },
        )
    }.getOrElse { ProviderCandidates(provider, emptyList()) }

    private fun score(song: SongSearchResult, request: SearchRequest): Int =
        LyricMatchPolicy.calculateScore(
            song = song,
            cleanLocalTitle = request.cleanTitle,
            localArtists = request.localArtists,
            localFeatures = request.localFeatures,
            localDurationMs = request.durationMs,
            cleanLocalAlbum = "",
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
        val durationMs: Long,
        val cleanTitle: String,
        val localArtists: List<String>,
        val localFeatures: List<String>,
    )

    private data class ProviderCandidates(
        val provider: OnlineLyricProvider,
        val candidates: List<ScoredSong>,
    )

    private data class ProviderCandidate(
        val provider: OnlineLyricProvider,
        val candidate: ScoredSong,
    )

    companion object {
        fun create(
            mode: LyricSelectionMode,
            providers: List<OnlineLyricProvider>,
            currentTrack: () -> CurrentSongDetails?,
            searchExecutor: ExecutorService = defaultSearchExecutor(providers.size),
            searchBudgetMs: Long = ONLINE_SEARCH_BUDGET_MS,
        ): CompositeOnlineSearchAutoLyricsSource =
            CompositeOnlineSearchAutoLyricsSource(
                mode = mode,
                providers = providers,
                currentTrack = currentTrack,
                searchExecutor = searchExecutor,
                searchBudgetMs = searchBudgetMs,
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
