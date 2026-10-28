package dev.amenhancer.module.hook.lyricsource

import dev.amenhancer.module.hook.TtmlTimingPolicy
import dev.amenhancer.module.lyrics.TtmlInputPolicy
import dev.amenhancer.module.lyrics.online.AppleLyricTtmlReader
import dev.amenhancer.module.lyrics.online.AppleLyricTtmlWriter
import dev.amenhancer.module.lyrics.online.KugouSource
import dev.amenhancer.module.lyrics.online.LyricLaneClassifier
import dev.amenhancer.module.lyrics.online.LyricLineFilter
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
import dev.amenhancer.module.lyrics.source.LyricSourceDiagnostics
import dev.amenhancer.module.lyrics.source.LyricSourceKeys
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
    /**
     * The LRC providers. Null builds the module's own four; a test pins one so a
     * provider body can be replayed through [documentFor] without a network.
     */
    sources: List<SearchLyricsSource>? = null,
    /**
     * Reads a filtered provider body's auxiliary columns into the two lanes.
     * [OnlineTranslationExtraction] on the device; injectable so a test can prove
     * what a reader that cannot classify a body is allowed to cost — the lanes,
     * never the document.
     */
    private val laneReader: (LyricsResult) -> List<OnlineTranslationLine> = { result ->
        OnlineTranslationExtraction.extract(result, alreadyFiltered = true)
    },
) {

    /** A provider document with the timing precision its own data supports. */
    internal data class Document(val ttml: String, val hasWords: Boolean)

    /**
     * The four steps an online hit goes through before it becomes a document.
     *
     * A failure names the step that produced nothing. Every one of them used to
     * collapse into the same `null`, which the sheet could only render as
     * 「该来源无法预览」 and which left the log with no line at all — the reason a
     * device report of "every row fails" was not diagnosable.
     */
    internal enum class Stage(val token: String) {
        /** The provider answered nothing for this hit, or could not be reached. */
        FETCH("fetch"),
        /** The filter left no lyric line (only credits/notices, or it threw). */
        FILTER("filter"),
        /** The lane reader could not read the provider's auxiliary columns. */
        CLASSIFY("classify"),
        /** The Apple TTML writer produced nothing publishable. */
        WRITE("write"),
    }

    /** One [documentFor] outcome: a document, or the stage and Chinese reason that stopped it. */
    internal data class DocumentOutcome(
        val document: Document? = null,
        val stage: Stage? = null,
        val reason: String = "",
    ) {
        val failed: Boolean get() = document == null
    }

    /** One [translationOutcome] outcome, in the same shape. */
    internal data class LinesOutcome(
        val lines: List<OnlineTranslationLine>? = null,
        val stage: Stage? = null,
        val reason: String = "",
    )

    private val lineSources: List<SearchLyricsSource> = sources ?: if (transport == null) {
        emptyList()
    } else {
        listOf(
            QmSource(transport),
            NeSource(transport),
            KuwoSource(transport),
            KugouSource(transport),
        )
    }

    /**
     * The provider hit each listed option came from, keyed by the option key.
     *
     * The key only carries the provider plus its remote id, but several providers
     * need the rest of the hit to answer at all: KuGou's `getLyrics` returns null
     * without the `accessKey`/`contentType` extras its own search attached, and
     * QQ's `GetPlayLyricInfo` sends the song/album/artist names and the track
     * duration. Rebuilding a bare [SongSearchResult] from the key is what made
     * every KuGou row unpublishable and left QQ answering with an empty body; the
     * hit is remembered here the same way [lunabeatHits] remembers a LunaBeat
     * entry, so a preview and an apply fetch exactly what the search found.
     */
    private val lineHits = ConcurrentHashMap<String, SongSearchResult>()

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
                // Keep the hit itself: preview and apply re-fetch from the key, and
                // the key does not carry KuGou's accessKey or the names QQ's lyric
                // endpoint is asked with.
                rememberHit(result)
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
     * LRC body rendered through the module's own writer.
     *
     * The outcome carries the stage that stopped it. "The provider has no body",
     * "the body held only credits", "the lanes could not be read" and "the writer
     * produced nothing" are four different answers for the user and for the log;
     * collapsing them into one `null` is what left the sheet saying
     * 「该来源无法预览」 with no line to grep.
     */
    fun documentFor(
        option: LyricSourceOption,
        durationMs: Long,
        songTitle: String? = null,
        songArtist: String? = null,
    ): DocumentOutcome {
        if (option.kind != LyricSourceKind.ONLINE) {
            return failed(Stage.FETCH, "该来源不支持预览")
        }
        when (option.providerId) {
            LyricSourceProviders.AM_LYRICS -> {
                val entry = amLyrics?.entryFor(remoteIdOf(option))
                    ?: return failed(Stage.FETCH, NO_BODY)
                val document = amLyrics.document(entry)
                    ?: return failed(Stage.WRITE, "AM-Lyrics 文件校验失败")
                return DocumentOutcome(Document(ttml = document.ttml, hasWords = document.hasWords))
            }

            LyricSourceProviders.AMLL -> {
                // AMLL ships its own TTML dialect; the repository's converter rewrites
                // it into the shape Apple's parser reads before anything is published.
                val document = amll?.document(remoteIdOf(option))
                    ?: return failed(Stage.FETCH, NO_BODY)
                return DocumentOutcome(Document(ttml = document.ttml, hasWords = document.hasWords))
            }

            LyricSourceProviders.LUNABEAT -> {
                val ttml = fetchDocument(option) ?: return failed(Stage.FETCH, NO_BODY)
                val hasWords = runCatching { TtmlTimingPolicy.isWord(ttml) }.getOrDefault(false)
                return DocumentOutcome(Document(ttml = ttml, hasWords = hasWords))
            }
        }
        // Stage 1: the provider body. The hit the search listed is replayed, so the
        // provider-only fields (KuGou extras, QQ names/duration) survive the key.
        val fetched = fetchOutcome(option, songTitle, songArtist, durationMs)
        val result = fetched.result ?: return failed(Stage.FETCH, fetched.reason)
        // Stage 2: filtering. The identity lets the rules drop the provider's
        // `歌名 - 歌手` header, so it is passed here rather than left to the writer.
        val clean = runCatching { LyricLineFilter.filter(result, songTitle, songArtist) }
            .getOrElse { error ->
                logStageFailure("document", Stage.FILTER, error)
                return failed(Stage.FILTER, "取回的歌词无法过滤")
            }
        // The only thing that may still stop the document: no lyric to publish.
        // A line counts when it has visible text — the filter already dropped the
        // credits, the platform notices and the placeholders, so what is left is the
        // song. **Any script is a lyric**: Japanese (kana + Han) is not an "unknown
        // language" to be refused, and neither is English or a body whose script
        // nobody has taught the classifier yet.
        if (!hasLyricLine(clean)) {
            // The one number that tells the two empty cases apart on a device: a body
            // that parsed to nothing never had a line to filter.
            logger(
                "lyrics-source document stage=filter before=" + result.original.size +
                    " after=" + clean.original.size,
            )
            return failed(Stage.FILTER, emptyLaneReason(result))
        }
        // Stage 3: the provider's translation/romanization columns.
        // The translation/romanization split is advisory: a classifier that cannot read a
        // provider's column must not take the whole document away. The lyrics are publishable on
        // their own, so a failure here degrades to "no auxiliary lanes".
        val lanes = runCatching { laneReader(clean) }
            .onFailure { error -> logStageFailure("document", Stage.CLASSIFY, error) }
            .getOrDefault(emptyList<OnlineTranslationLine>())
        // 语种 is a hint, never a verdict: it explains an empty lane in the log
        // (script=latin beside lanes=none says "English song", not "failed").
        logScriptHint("document", clean)
        // Stage 4: the Apple document itself.
        val lines = runCatching { AppleLyricTtmlWriter.from(clean, lanes) }
            .getOrElse { error ->
                logStageFailure("document", Stage.WRITE, error)
                return failed(Stage.WRITE, "取回的歌词无法写入歌词文档")
            }
        if (lines.isEmpty()) return failed(Stage.WRITE, "取回的歌词没有正文")
        val built = runCatching {
            AppleLyricTtmlWriter.build(
                lines = lines,
                durationMs = durationMs.takeIf { it > 0L } ?: 0L,
            )
        }.getOrNull()
        val ttml = built?.takeIf { it.isNotBlank() }
            ?: return failed(Stage.WRITE, "生成的歌词文档为空")
        if (!TtmlInputPolicy.isAcceptable(ttml)) {
            return failed(Stage.WRITE, "取回的歌词格式不被接受")
        }
        return DocumentOutcome(Document(ttml = ttml, hasWords = lines.any { it.words.size >= 2 }))
    }

    /**
     * Why a lane ended up with no line, in the two shapes that need different
     * answers: the body parsed to nothing at all (a parser/dialect problem) or the
     * body had lines and the filter kept none of them (credits and platform
     * notices only). "该来源无法预览" said neither.
     */
    private fun emptyLaneReason(result: LyricsResult): String =
        // No line at all, or nothing but textless slots (`//`, `♪`, a lost LRC
        // conversion): the body never carried a lyric. Only a body that really had
        // lines, all of them credits, gets the other sentence.
        if (result.original.isEmpty() || !hasLyricLine(result)) NO_LINES else CREDITS_ONLY

    /**
     * True when the body still holds a line a document can be published from.
     *
     * The one publishable condition: at least one line with visible text. The filter
     * has already dropped the credits, the platform notices and the placeholders, so
     * what survives is the song — in whatever script it is written. This is the only
     * gate left in front of the writer; the lane reader cannot close it.
     */
    private fun hasLyricLine(clean: LyricsResult): Boolean =
        clean.original.any { line -> line.words.any { word -> word.text.isNotBlank() } }

    /**
     * The script the body is written in, for the log only.
     *
     * 「语种」 decides nothing here: which lane a provider column belongs to is the
     * only thing the classifier is asked, and a song it does not recognise is still a
     * song. Logged so an empty translations track on the device reads as "this is an
     * English/Japanese/unknown-script body" instead of as a source that failed.
     */
    private fun logScriptHint(action: String, clean: LyricsResult) {
        val first = clean.original.firstOrNull { line ->
            line.words.any { word -> word.text.isNotBlank() }
        } ?: return
        val text = first.words.joinToString("") { word -> word.text }
        val script = runCatching { LyricLaneClassifier.classify(text) }.getOrNull() ?: return
        logger(
            "lyrics-source " + action + " script=" + script.name.lowercase() +
                " lines=" + clean.original.size,
        )
    }

    /** A failed document build with the stage that produced nothing and the user-facing reason. */
    private fun failed(stage: Stage, reason: String): DocumentOutcome =
        DocumentOutcome(stage = stage, reason = reason)

    private fun failedLines(stage: Stage, reason: String): LinesOutcome =
        LinesOutcome(stage = stage, reason = reason)

    /** Why one stage threw: log only. The sheet only ever sees the Chinese reason. */
    private fun logStageFailure(action: String, stage: Stage, error: Throwable) {
        logger(
            "lyrics-source " + action + " stage=" + stage.token + " " +
                LyricSourceDiagnostics.failureTrace(error),
        )
    }

    /**
     * Remembers one search hit for the option key it will be applied with, so
     * [fetchOutcome] can replay the hit instead of rebuilding a bare identity.
     */
    private fun rememberHit(result: SongSearchResult) {
        val key = LyricSourceKeys.online(providerIdOf(result.source), result.id)
        // Bound the cache: a session only ever applies what it just searched, and a
        // long-lived sheet would otherwise keep every hit of every query.
        if (lineHits.size > MAX_REMEMBERED_HITS) lineHits.clear()
        lineHits[key] = result
    }

    /** The provider's translation lane, for a merge into the displayed document. */
    fun translationLines(option: LyricSourceOption): List<OnlineTranslationLine>? =
        translationOutcome(option).lines

    /**
     * The same body as [translationLines], with the stage that produced nothing.
     *
     * The translation lane runs the same fetch/filter/classify chain as the
     * original one, so it reports the same four stages; a lane the provider does
     * not carry is a *fetch* answer, never a silent null.
     */
    fun translationOutcome(
        option: LyricSourceOption,
        durationMs: Long = 0L,
        songTitle: String? = null,
        songArtist: String? = null,
    ): LinesOutcome {
        if (option.kind != LyricSourceKind.ONLINE) return failedLines(Stage.FETCH, "该来源不支持译文")
        // Both GitHub corpora are listed for the original lane only, so they have no
        // translation body to merge here (see searchBlocking).
        if (option.providerId == LyricSourceProviders.AMLL) {
            return failedLines(Stage.FETCH, NO_TRANSLATION)
        }
        if (option.providerId == LyricSourceProviders.AM_LYRICS) {
            return failedLines(Stage.FETCH, NO_TRANSLATION)
        }
        if (option.providerId == LyricSourceProviders.LUNABEAT) {
            // Apple's own document shape: the reader already pairs every line with
            // its translation, so those lanes are reused verbatim.
            val ttml = fetchDocument(option) ?: return failedLines(Stage.FETCH, NO_BODY)
            val lines = runCatching {
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
                ?: return failedLines(Stage.FETCH, NO_TRANSLATION)
            return LinesOutcome(lines)
        }
        val fetched = fetchOutcome(option, songTitle, songArtist, durationMs)
        val result = fetched.result ?: return failedLines(Stage.FETCH, fetched.reason)
        val clean = runCatching { LyricLineFilter.filter(result) }
            .getOrElse { error ->
                logStageFailure("document", Stage.FILTER, error)
                return failedLines(Stage.FILTER, "取回的译文无法过滤")
            }
        if (!hasLyricLine(clean)) {
            return failedLines(Stage.FILTER, emptyLaneReason(result))
        }
        // The same rule as the document: the lane reader is advisory. A body it
        // cannot classify carries no mergeable translation, which is an *empty lane*
        // (「该来源没有译文」), not a failed fetch and never a 语种 rejection.
        val lines = runCatching { laneReader(clean) }
            .onFailure { error -> logStageFailure("document", Stage.CLASSIFY, error) }
            .getOrDefault(emptyList<OnlineTranslationLine>())
        logScriptHint("translation", clean)
        return LinesOutcome(lines)
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
    fun fetchResult(option: LyricSourceOption): LyricsResult? = fetchOutcome(option).result

    /** One provider body plus, when there is none, the reason to show the user. */
    private fun fetchOutcome(
        option: LyricSourceOption,
        songTitle: String? = null,
        songArtist: String? = null,
        durationMs: Long = 0L,
    ): FetchOutcome {
        val remoteId = remoteIdOf(option)
        if (remoteId.isEmpty()) return FetchOutcome(null, "该来源的歌曲标识不完整")
        val source = lineSources.firstOrNull { providerIdOf(it.sourceType) == option.providerId }
            ?: return FetchOutcome(null, "该来源不在本机支持的来源列表里")
        // The hit as the search listed it. A key restored from disk has no hit, so
        // the song's own metadata stands in for the fields the key cannot carry.
        val hit = lineHits[option.key] ?: SongSearchResult(
            id = remoteId,
            title = songTitle.orEmpty(),
            artist = songArtist.orEmpty(),
            album = "",
            duration = durationMs.coerceAtLeast(0L),
            source = source.sourceType,
        )
        val outcome = runCatching { source.getLyrics(hit) }
        outcome.exceptionOrNull()?.let { error ->
            logger(
                "lyrics-source fetch: " + source.sourceType.name + " failed " +
                    LyricSourceDiagnostics.failureTrace(error),
            )
        }
        val result = outcome.getOrNull()
        return if (result == null) FetchOutcome(null, NO_BODY) else FetchOutcome(result)
    }

    /** One provider body plus, when it is missing, the Chinese reason to report. */
    private data class FetchOutcome(val result: LyricsResult?, val reason: String = "")

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

        /** Search hits remembered for re-fetch, so a long session cannot grow without bound. */
        const val MAX_REMEMBERED_HITS = 200

        /** The provider had no body for this hit (gone, or answered nothing). */
        const val NO_BODY = "该来源没有这首歌词"

        /** The body parsed to no line at all (a dialect the parser could not read). */
        const val NO_LINES = "取回的歌词没有可用的歌词行"

        /** The body had lines, and all of them were credits or platform notices. */
        const val CREDITS_ONLY = "取回的歌词只有署名或版权信息"

        /** The provider carries no translation lane for this hit. */
        const val NO_TRANSLATION = "该来源没有译文"

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
