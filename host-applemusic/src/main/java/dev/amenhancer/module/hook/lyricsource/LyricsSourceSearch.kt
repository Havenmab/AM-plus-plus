package dev.amenhancer.module.hook.lyricsource

import dev.amenhancer.module.hook.TtmlTimingPolicy
import dev.amenhancer.module.lyrics.online.AppleLyricTtmlReader
import dev.amenhancer.module.lyrics.online.AppleLyricTtmlWriter
import dev.amenhancer.module.lyrics.online.KugouSource
import dev.amenhancer.module.lyrics.online.LyricRecentSource
import dev.amenhancer.module.lyrics.online.LyricSourceRecents
import dev.amenhancer.module.lyrics.online.KuwoSource
import dev.amenhancer.module.lyrics.online.LyricsResult
import dev.amenhancer.module.lyrics.online.NeSource
import dev.amenhancer.module.lyrics.online.OnlineTranslationExtraction
import dev.amenhancer.module.lyrics.online.OnlineTranslationLine
import dev.amenhancer.module.lyrics.online.QmSource
import dev.amenhancer.module.lyrics.online.SearchLyricsSource
import dev.amenhancer.module.lyrics.online.SongSearchResult
import dev.amenhancer.module.lyrics.online.Source
import dev.amenhancer.module.lyrics.source.LunabeatClient
import dev.amenhancer.module.lyrics.source.LunabeatSong
import dev.amenhancer.module.lyrics.source.LyricHttpTransport
import dev.amenhancer.module.lyrics.source.LyricLane
import dev.amenhancer.module.lyrics.source.LyricSourceKind
import dev.amenhancer.module.lyrics.source.LyricSourceOption
import dev.amenhancer.module.lyrics.source.LyricSourceProviders
import dev.amenhancer.module.lyrics.source.OnlineLyricsCandidate
import dev.amenhancer.module.model.CustomLyricsSources
import java.util.Collections
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executor

/**
 * The provider side of the sheet's search box, and the one place that turns a
 * provider hit into something the host can publish.
 *
 * Two kinds of provider are usable, and both can replace the whole document:
 *
 * - Lunabeat serves Apple TTML by Adam ID. Its catalogue doubles as the search
 *   index (title / artist / album), and each hit is remembered so applying it
 *   costs one HTTP request.
 * - QQ / NetEase / Kuwo / Kugou return parsed LRC-style bodies (LyricsResult).
 *   The module already renders those as an Apple document: AppleLyricTtmlWriter
 *   keeps each line's word spans plus the aligned translation lane, and its
 *   build() picks the Word/Line timing declaration from the data itself, so the
 *   same publisher path accepts them.
 *
 * Precision is reported honestly: a body only claims word timing when its parsed
 * lines actually carry two or more timed words; a line-only LRC stays line-only
 * in the sheet's badge.
 *
 * Network calls run on [executor]; the caller marshals callbacks.
 */
internal class LyricsSourceSearch(
    transport: LyricHttpTransport?,
    private val lunabeat: LunabeatClient?,
    private val amll: LyricsSourceAmllProvider? = null,
    private val amLyrics: LyricsSourceAmLyricsProvider? = null,
    private val executor: Executor,
    /** Runs one provider each, so a slow catalogue cannot hold the first screen hostage. */
    private val parallelExecutor: Executor = executor,
    /** Recently applied sources, newest first (see [LyricSourceRecents]). */
    private val recentSources: () -> List<LyricRecentSource> = { emptyList() },
    private val logger: (String) -> Unit,
) {

    /** A provider document with the timing precision its own data supports. */
    internal data class Document(val ttml: String, val hasWords: Boolean)


    private val lineSources: List<SearchLyricsSource> = if (transport == null) {
        emptyList()
    } else {
        listOf(
            QmSource(transport),
            NeSource(transport),
            KuwoSource(transport),
            KugouSource(transport),
        )
    }

    /** Lunabeat catalogue hits, keyed by the option's remote id (its content hash). */
    private val lunabeatHits = ConcurrentHashMap<String, LunabeatSong>()

    /** Why the last Lunabeat lookup produced nothing, or null when it worked. */
    @Volatile
    private var lunabeatFailure: String? = null

    /**
     * Why the last search could not reach its catalogues (null when they were
     * usable). The controller turns this into a user-facing search error instead of
     * an empty list, because "your network cannot reach AMLL" and "this song is not
     * in AMLL" are different answers.
     */
    fun lastFailure(): String? = amll?.lastFailure ?: amLyrics?.lastFailure ?: lunabeatFailure

    /** Searches [query] for [lane]; [onDone] runs exactly once on the executor thread. */
    fun search(
        query: String,
        lane: LyricLane,
        songId: Long,
        durationMs: Long,
        songTitle: String = "",
        songArtist: String = "",
        preferredProvider: String? = null,
        onDone: (Result<List<OnlineLyricsCandidate>>) -> Unit,
    ) {
        val keyword = query.trim()
        if (keyword.isEmpty()) {
            onDone(Result.success(emptyList()))
            return
        }
        executor.execute {
            onDone(
                runCatching {
                    searchBlocking(keyword, lane, songId, durationMs, songTitle, songArtist, preferredProvider)
                },
            )
        }
    }

    private fun searchBlocking(
        query: String,
        lane: LyricLane,
        songId: Long,
        durationMs: Long,
        songTitle: String,
        songArtist: String,
        preferredProvider: String?,
    ): List<OnlineLyricsCandidate> {
        // Every catalogue runs at once: Lunabeat needs ~1.2 s while the GitHub corpora answer
        // in ~100 ms, and running them in sequence made the fastest rows wait for the slowest.
        // TTML sources keep provider priority for the ordering below, not for the clock.
        val tasks = buildList<Pair<String, () -> List<OnlineLyricsCandidate>>> {
            add("lunabeat" to { searchLunabeat(query) })
            // AMLL / AM-Lyrics serve a whole document only, so they are original-lane rows.
            if (lane == LyricLane.ORIGINAL) {
                add("amll" to { searchAmll(query) })
                add("am-lyrics" to { searchAmLyrics(query, songId) })
            }
            add("line" to { searchLineProviders(query, durationMs, lane) })
        }
        val results = Collections.synchronizedList(mutableListOf<OnlineLyricsCandidate>())
        val latch = CountDownLatch(tasks.size)
        val startedAt = System.currentTimeMillis()
        for ((name, task) in tasks) {
            parallelExecutor.execute {
                try {
                    results += task()
                } catch (error: Throwable) {
                    logger("lyrics-source search: " + name + " failed: " + error.message)
                } finally {
                    latch.countDown()
                }
            }
        }
        val finished = latch.await(SEARCH_TIMEOUT_MS, java.util.concurrent.TimeUnit.MILLISECONDS)
        if (!finished) {
            logger("lyrics-source search: timeout after " + SEARCH_TIMEOUT_MS + "ms, using partial rows")
        }
        val rows = results.toList()
        // TTML providers first inside the same batch, then whatever came back.
        val ordered = rows.sortedBy { candidate -> providerPriority(candidate.providerId) }
        val deduped = LyricSourceRecents.dedupe(ordered)
        if (deduped.size != ordered.size) {
            logger("lyrics-source search: deduped " + (ordered.size - deduped.size) + " duplicate row(s)")
        }
        val pinned = LyricSourceRecents.pin(
            history = recentSources(),
            candidates = deduped,
            songTitle = songTitle,
            songArtist = songArtist,
            preferredProvider = preferredProvider,
        )
        logger(
            "lyrics-source search: parallel rows=" + pinned.size +
                " tookMs=" + (System.currentTimeMillis() - startedAt),
        )
        return pinned
    }

    private fun providerPriority(providerId: String): Int = when (providerId) {
        LyricSourceProviders.LUNABEAT -> 0
        LyricSourceProviders.AMLL -> 1
        LyricSourceProviders.AM_LYRICS -> 2
        else -> 3
    }

    private fun searchAmLyrics(query: String, songId: Long): List<OnlineLyricsCandidate> {
        val provider = amLyrics ?: return emptyList()
        // The index is keyed by Adam ID, so the current song can hit exactly (that is
        // the strongest identity for a per-song lyric); the query then adds text
        // matches. The key segment is the file's sha256, which is also what the body
        // verification compares against.
        return provider.search(query, songId, SEARCH_PAGE_SIZE).map { entry ->
            OnlineLyricsCandidate(
                providerId = LyricSourceProviders.AM_LYRICS,
                result = SongSearchResult(
                    id = entry.sha256,
                    title = entry.displayName,
                    artist = "",
                    album = "",
                    duration = 0L,
                    // Source has no AM-Lyrics member yet; the catalog keys and labels
                    // everything by providerId, and this value only feeds the ranker.
                    source = Source.AMLL,
                ),
                hasWords = false,
                hasTranslation = false,
                hasRomanization = false,
            )
        }
    }

    private fun searchAmll(query: String): List<OnlineLyricsCandidate> {
        val provider = amll ?: return emptyList()
        return provider.search(query, SEARCH_PAGE_SIZE).map { entry ->
            OnlineLyricsCandidate(
                providerId = LyricSourceProviders.AMLL,
                // The core catalog already scored and ordered the entries; its
                // transport model carries Source.AMLL and the corpus file name as id.
                result = entry.toSearchResult(),
                hasWords = false,
                hasTranslation = false,
                hasRomanization = false,
            )
        }
    }

    private fun searchLineProviders(
        query: String,
        durationMs: Long,
        lane: LyricLane,
    ): List<OnlineLyricsCandidate> {
        val results = mutableListOf<OnlineLyricsCandidate>()
        lineSources.forEach { source ->
            val hits = runCatching {
                source.search(
                    keyword = query,
                    page = 1,
                    separator = " ",
                    pageSize = SEARCH_PAGE_SIZE,
                    durationMs = durationMs,
                )
            }.onFailure { error ->
                logger(
                    "lyrics-source search: " + source.sourceType.name + " failed: " +
                        (error.message ?: error.javaClass.simpleName),
                )
            }.getOrDefault(emptyList())
            hits.forEach { result ->
                results += OnlineLyricsCandidate(
                    providerId = providerIdOf(result.source),
                    result = result,
                    hasWords = false,
                    // Both lanes arrive in the same body, so the requested lane is
                    // what the row must be listed for; the original lane is always
                    // served by a body.
                    hasTranslation = lane == LyricLane.TRANSLATION,
                    hasRomanization = false,
                )
            }
        }
        return results
    }

    private fun searchLunabeat(query: String): List<OnlineLyricsCandidate> {
        val startedAt = System.currentTimeMillis()
        val client = lunabeat ?: run {
            lunabeatFailure = "LunaBeat：未配置"
            logger("lyrics-source search: lunabeat provider unavailable")
            return emptyList()
        }
        val catalog = runCatching { client.loadCatalog() }
            .onFailure { error ->
                lunabeatFailure = "LunaBeat：无法加载目录（" +
                    (error.message ?: error.javaClass.simpleName) + "）"
                logger(
                    "lyrics-source search: lunabeat catalogue failed: " +
                        (error.message ?: error.javaClass.simpleName),
                )
            }
            .getOrNull()
        if (catalog == null) {
            if (lunabeatFailure == null) lunabeatFailure = "LunaBeat：目录不可用（manifest/index 拉取失败）"
            logger("lyrics-source search: lunabeat catalogue unavailable (manifest/index fetch)")
            return emptyList()
        }
        lunabeatFailure = null
        val tokens = tokensOf(query)
        if (tokens.isEmpty()) return emptyList()
        val hits = catalog.songs
            .asSequence()
            .filter { song -> matches(song, tokens) }
            .take(SEARCH_PAGE_SIZE)
            .toList()
        logger(
            "lyrics-source search: lunabeat entries=" + catalog.songs.size +
                " hits=" + hits.size +
                " tookMs=" + (System.currentTimeMillis() - startedAt),
        )
        return hits
            .asSequence()
            .map { song ->
                lunabeatHits[song.sha256] = song
                OnlineLyricsCandidate(
                    providerId = LyricSourceProviders.LUNABEAT,
                    result = SongSearchResult(
                        id = song.sha256,
                        title = song.title,
                        artist = song.artists.joinToString(", "),
                        album = song.album,
                        duration = 0L,
                        source = Source.LB,
                    ),
                    // Precision is only known once the body is fetched (the preview
                    // carries it). The catalog derives precisionKnown from this flag,
                    // so claiming true here would label the row 逐词 on a guess.
                    hasWords = false,
                    hasTranslation = true,
                    hasRomanization = false,
                )
            }
            .toList()
    }

    /**
     * Every search token has to appear somewhere in the entry, in any order, so
     * the sheet's default "title artist" query matches whether the catalogue
     * spells it as two fields or one display name.
     */
    private fun matches(song: LunabeatSong, tokens: List<String>): Boolean {
        val haystack = buildString {
            append(song.title.lowercase()).append(' ')
            append(song.artists.joinToString(" ").lowercase()).append(' ')
            append(song.album.lowercase()).append(' ')
            append(song.displayName.lowercase())
        }
        return tokens.all { token -> haystack.contains(token) }
    }

    private fun tokensOf(query: String): List<String> = query.lowercase()
        .split(' ', '-', '/', ',', '，', '、')
        .map { it.trim() }
        .filter { it.isNotEmpty() }

    /**
     * The publishable document for [option]: TTML for the TTML sources, or the
     * LRC body rendered through the module's own writer. Null when the provider
     * no longer has the song.
     */
    fun documentFor(option: LyricSourceOption, durationMs: Long): Document? {
        if (option.kind != LyricSourceKind.ONLINE) return null
        if (option.providerId == LyricSourceProviders.AM_LYRICS) {
            val entry = amLyrics?.entryFor(remoteIdOf(option)) ?: return null
            val document = amLyrics.document(entry) ?: return null
            return Document(ttml = document.ttml, hasWords = document.hasWords)
        }
        if (option.providerId == LyricSourceProviders.AMLL) {
            // AMLL ships its own TTML dialect; the repository's converter rewrites
            // it into the shape Apple's parser reads before anything is published.
            val document = amll?.document(remoteIdOf(option)) ?: return null
            return Document(ttml = document.ttml, hasWords = document.hasWords)
        }
        if (option.providerId == LyricSourceProviders.LUNABEAT) {
            val ttml = fetchDocument(option) ?: return null
            val hasWords = runCatching { TtmlTimingPolicy.isWord(ttml) }.getOrDefault(false)
            return Document(ttml = ttml, hasWords = hasWords)
        }
        val result = fetchResult(option) ?: return null
        val lines = runCatching { AppleLyricTtmlWriter.from(result) }.getOrNull() ?: return null
        if (lines.isEmpty()) return null
        val ttml = runCatching {
            AppleLyricTtmlWriter.build(
                lines = lines,
                durationMs = durationMs.takeIf { it > 0L } ?: 0L,
            )
        }.getOrNull()?.takeIf { it.isNotBlank() } ?: return null
        return Document(ttml = ttml, hasWords = lines.any { it.words.size >= 2 })
    }

    /** The provider's translation lane, for a merge into the displayed document. */
    fun translationLines(option: LyricSourceOption): List<OnlineTranslationLine>? {
        if (option.kind != LyricSourceKind.ONLINE) return null
        // Both GitHub corpora are listed for the original lane only, so they have no
        // translation body to merge here (see searchBlocking).
        if (option.providerId == LyricSourceProviders.AMLL) return null
        if (option.providerId == LyricSourceProviders.AM_LYRICS) return null
        if (option.providerId == LyricSourceProviders.LUNABEAT) {
            // Apple's own document shape: the reader already pairs every line with
            // its translation, so those lanes are reused verbatim.
            val ttml = fetchDocument(option) ?: return null
            return runCatching {
                AppleLyricTtmlReader.read(ttml)
                    .filter { line -> !line.translation.isNullOrBlank() }
                    .map { line ->
                        OnlineTranslationLine(
                            startTimeMs = line.begin,
                            content = line.text,
                            translation = line.translation,
                        )
                    }
            }.getOrNull()?.takeIf { it.isNotEmpty() }
        }
        val result = fetchResult(option) ?: return null
        return runCatching { OnlineTranslationExtraction.extract(result) }.getOrNull()
    }

    /** The TTML body of a Lunabeat hit, or null when the catalogue moved on. */
    fun fetchDocument(option: LyricSourceOption): String? {
        val client = lunabeat ?: return null
        val remoteId = remoteIdOf(option)
        val song = lunabeatHits[remoteId]
            ?: runCatching { client.loadCatalog() }
                .getOrNull()
                ?.songs
                ?.firstOrNull { it.sha256 == remoteId }
            ?: return null
        lunabeatHits[remoteId] = song
        return runCatching { client.fetch(song) }.getOrNull()
    }

    /** One provider body for an LRC-style candidate, or null when unavailable. */
    fun fetchResult(option: LyricSourceOption): LyricsResult? {
        val remoteId = remoteIdOf(option)
        if (remoteId.isEmpty()) return null
        lineSources.forEach { source ->
            if (providerIdOf(source.sourceType) != option.providerId) return@forEach
            val result = SongSearchResult(
                id = remoteId,
                title = "",
                artist = "",
                album = "",
                duration = 0L,
                source = source.sourceType,
            )
            return runCatching { source.getLyrics(result) }.getOrNull()
        }
        return null
    }

    /** The module's own vocabulary for a provider id, used when publishing documents. */
    fun customSourceToken(providerId: String): String = when (providerId) {
        LyricSourceProviders.QQ -> CustomLyricsSources.QQ
        LyricSourceProviders.NETEASE -> CustomLyricsSources.NETEASE
        LyricSourceProviders.KUWO -> CustomLyricsSources.KUWO
        LyricSourceProviders.KUGOU -> CustomLyricsSources.KUGOU
        LyricSourceProviders.LUNABEAT -> CustomLyricsSources.LUNABEAT
        LyricSourceProviders.AMLL -> CustomLyricsSources.AMLL
        LyricSourceProviders.AM_LYRICS -> CustomLyricsSources.AM_LYRICS
        else -> CustomLyricsSources.MANUAL
    }

    /** The provider enum for a provider id, for the translation merger. */
    fun sourceOf(providerId: String): Source? = when (providerId) {
        LyricSourceProviders.QQ -> Source.QM
        LyricSourceProviders.NETEASE -> Source.NE
        LyricSourceProviders.KUWO -> Source.KUWO
        LyricSourceProviders.KUGOU -> Source.KUGOU
        LyricSourceProviders.LUNABEAT -> Source.LB
        LyricSourceProviders.AMLL -> Source.AMLL
        else -> null
    }

    private fun remoteIdOf(option: LyricSourceOption): String =
        option.key.substringAfterLast(':').trim()

    private companion object {
        /** Upper bound for one search batch; late providers still log and are discarded. */
        const val SEARCH_TIMEOUT_MS = 2_600L

        const val SEARCH_PAGE_SIZE = 20

        fun providerIdOf(source: Source): String = when (source) {
            Source.QM -> LyricSourceProviders.QQ
            Source.NE -> LyricSourceProviders.NETEASE
            Source.KUWO -> LyricSourceProviders.KUWO
            Source.KUGOU -> LyricSourceProviders.KUGOU
            Source.LB -> LyricSourceProviders.LUNABEAT
            // AMLL is a local corpus rather than a query API, but it is a
            // first-class provider id in the sheet, so the mapping stays exhaustive.
            Source.AMLL -> LyricSourceProviders.AMLL
        }
    }
}
