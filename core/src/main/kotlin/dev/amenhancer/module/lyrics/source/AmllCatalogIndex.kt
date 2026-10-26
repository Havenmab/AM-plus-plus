package dev.amenhancer.module.lyrics.source

import dev.amenhancer.module.lyrics.online.LyricMatchPolicy
import dev.amenhancer.module.lyrics.online.SongSearchResult
import dev.amenhancer.module.lyrics.online.Source
import java.util.Locale
import org.json.JSONArray
import org.json.JSONObject

/**
 * One entry of the AMLL raw-lyrics catalog (amll-ttml-db's
 * "metadata/raw-lyrics-index.jsonl").
 *
 * The corpus file is named after nothing but the submission
 * ("1768754400682-250306205-r6IrpmBd.ttml"), so [rawLyricFile] is both the stable
 * candidate id and the name the fetch path appends to "raw-lyrics/". The catalog
 * also records the NetEase id and the Apple Music ids the submitter supplied:
 * they are how a caller can recognise "this is the same song" without trusting
 * the title.
 *
 * [searchText] is the normalized haystack a query is filtered against, built once
 * at parse time: [LyricMatchPolicy.cleanString] lowercases, strips invisible
 * characters and bracketed segments and removes all whitespace, so a search never
 * pays that cost per candidate.
 */
/** Separator so a query token can never match across two metadata fields. */
private const val SEARCH_TEXT_SEPARATOR = '\u0001'

/** Normalized haystack: title, artists and album, cleaned like the match policy cleans. */
private fun buildSearchText(title: String, artists: List<String>, album: String): String =
    (listOf(title) + artists + listOf(album))
        .asSequence()
        .map(LyricMatchPolicy::cleanString)
        .filter { it.isNotEmpty() }
        .joinToString(SEARCH_TEXT_SEPARATOR.toString())

data class AmllCatalogEntry(
    /** Corpus file name inside the raw-lyrics directory; also the candidate id. */
    val rawLyricFile: String,
    /** "musicName" from the metadata block; may be empty when only artists are known. */
    val title: String,
    /** "artists" from the metadata block, in the submitter's order. */
    val artists: List<String>,
    /** "album" from the metadata block; may be empty. */
    val album: String,
    /** NetEase Cloud Music ids the submitter supplied. */
    val ncmMusicIds: List<String> = emptyList(),
    /** Apple Music Adam ids the submitter supplied. */
    val appleMusicIds: List<Long> = emptyList(),
) {
    /** The provider-side id: the corpus file name. */
    val candidateId: String get() = rawLyricFile

    internal val searchText: String = buildSearchText(title, artists, album)

    /** True when every token appears in this entry's search text. */
    internal fun matches(tokens: List<String>): Boolean =
        tokens.isNotEmpty() && tokens.all(searchText::contains)

    /** The catalog entry as the transport model the option layer consumes. */
    fun toSearchResult(): SongSearchResult = SongSearchResult(
        id = rawLyricFile,
        title = title,
        artist = artists.joinToString(ARTIST_SEPARATOR),
        album = album,
        duration = 0L,
        source = Source.AMLL,
    )

    private companion object {
        /** How several credited artists are joined for display and scoring. */
        const val ARTIST_SEPARATOR = " / "
    }
}

/**
 * The AMLL raw-lyrics catalog, parsed from amll-ttml-db's
 * "metadata/raw-lyrics-index.jsonl" and searched locally.
 *
 * AMLL publishes no search API: the whole catalog is one JSON-lines file whose
 * lines look like
 *
 *     {"metadata":[["album",["..."]],["artists",["..."]],["musicName",["..."]],
 *      ["ncmMusicId",["..."]],["appleMusicId",["..."]],...],
 *      "rawLyricFile":"1768754400682-250306205-r6IrpmBd.ttml"}
 *
 * (verified against amll-ttml-api's RawIndexEntry), so matching happens here, off
 * the network, and reuses [LyricMatchPolicy]'s normalization and scoring so an
 * AMLL hit is ranked exactly like a provider hit.
 *
 * The parser is deliberately tolerant: a corrupt line, a line larger than
 * [MAX_LINE_BYTES], a missing file name and a duplicate file name are skipped
 * instead of failing the whole catalog, because one bad submission must not take
 * the feature down. It is also bounded: at most [maxEntries] entries are kept, so
 * a huge or hostile catalog cannot exhaust the host process's heap.
 */
class AmllCatalogIndex private constructor(
    private val entries: List<AmllCatalogEntry>,
) {

    /** How many entries the catalog holds. */
    val size: Int get() = entries.size

    val isEmpty: Boolean get() = entries.isEmpty()

    /** Ranked candidates for a free-text query, as the transport model. */
    fun search(query: String, limit: Int = DEFAULT_RESULT_LIMIT): List<SongSearchResult> =
        candidates(query, limit).map(AmllCatalogEntry::toSearchResult)

    /**
     * Ranked catalog entries for a free-text query.
     *
     * A query is split on whitespace and every token must appear somewhere in the
     * entry's title, artists or album - that keeps the scan cheap on a catalog of
     * tens of thousands of lines and matches how the search box is used ("artist
     * title"). Surviving entries are scored with [LyricMatchPolicy] (the free-text
     * query doubles as the title channel and the artist channel) and sorted by
     * score, then title, then file name so equal scores stay deterministic.
     */
    fun candidates(query: String, limit: Int = DEFAULT_RESULT_LIMIT): List<AmllCatalogEntry> {
        val cappedLimit = limit.coerceIn(0, MAX_RESULT_LIMIT)
        if (cappedLimit == 0 || entries.isEmpty()) return emptyList()
        val tokens = queryTokens(query)
        if (tokens.isEmpty()) return emptyList()

        val identity = queryIdentity(query)
        val matches = ArrayList<ScoredEntry>(cappedLimit * 2)
        for (entry in entries) {
            if (!entry.matches(tokens)) continue
            matches += ScoredEntry(entry, score(entry, identity))
            // Keep the working set bounded on a very broad query (one common token).
            if (matches.size >= cappedLimit * 8) {
                matches.sortWith(RANKING)
                matches.subList(cappedLimit * 2, matches.size).clear()
            }
        }
        matches.sortWith(RANKING)
        return matches.take(cappedLimit).map(ScoredEntry::entry)
    }

    /**
     * The identity score of [entry] against [query], clamped to 0..100.
     *
     * Exposed so the sheet can show the same percentage a provider hit shows, and so
     * the ranking rule is testable without re-deriving it from the result order.
     */
    fun score(query: String, entry: AmllCatalogEntry): Int = score(entry, queryIdentity(query))

    private fun score(entry: AmllCatalogEntry, identity: QueryIdentity): Int {
        val raw = LyricMatchPolicy.calculateScore(
            song = entry.toSearchResult(),
            cleanLocalTitle = identity.cleanQuery,
            localArtists = identity.artists,
            localFeatures = identity.features,
            localDurationMs = 0L,
            cleanLocalAlbum = "",
        )
        return raw.coerceIn(MIN_SCORE, MAX_SCORE)
    }

    private fun queryIdentity(query: String): QueryIdentity {
        val cleanQuery = LyricMatchPolicy.cleanString(query)
        return QueryIdentity(
            cleanQuery = cleanQuery,
            // The query is free text: offering it as one "artist" name lets a bare
            // artist name score through the artist channel while a title scores through
            // the title channel.
            artists = listOf(cleanQuery).filter { it.isNotEmpty() },
            features = LyricMatchPolicy.featuresOf(query),
        )
    }

    private fun queryTokens(query: String): List<String> {
        if (query.isBlank()) return emptyList()
        val tokens = ArrayList<String>(MAX_QUERY_TOKENS)
        for (part in LyricMatchPolicy.stripInvisible(query).split(WHITESPACE)) {
            if (tokens.size >= MAX_QUERY_TOKENS) break
            val token = LyricMatchPolicy.cleanString(part)
            if (token.isEmpty() || tokens.contains(token)) continue
            tokens += token
        }
        return tokens
    }

    private data class QueryIdentity(
        val cleanQuery: String,
        val artists: List<String>,
        val features: List<String>,
    )

    private data class ScoredEntry(val entry: AmllCatalogEntry, val score: Int)

    companion object {
        /** Default number of results handed to the sheet. */
        const val DEFAULT_RESULT_LIMIT = 30

        /** Upper bound a caller may ask for. */
        const val MAX_RESULT_LIMIT = 100

        /** Upper bound of one accepted JSON line (a real line is a few hundred bytes). */
        const val MAX_LINE_BYTES = 64 * 1024

        /** Char-count pre-check before the byte-count check. */
        const val MAX_LINE_CHARS = MAX_LINE_BYTES

        /** Upper bound of catalog entries kept in memory. */
        const val MAX_ENTRIES = 30_000

        /** How many whitespace-separated query tokens are considered. */
        const val MAX_QUERY_TOKENS = 6

        private const val MAX_METADATA_VALUES = 8
        private const val MAX_VALUE_CHARS = 200
        private const val MAX_FILE_NAME_CHARS = 120
        private const val INITIAL_CAPACITY = 1024
        private const val MIN_SCORE = 0
        private const val MAX_SCORE = 100
        private const val KEY_RAW_LYRIC_FILE = "rawLyricFile"
        private const val KEY_METADATA = "metadata"
        // Metadata keys are matched case-insensitively; the corpus writes camelCase
        // ("musicName"), and a submitter tool that lowercased them must still work.
        private const val KEY_TITLE = "musicname"
        private const val KEY_ARTISTS = "artists"
        private const val KEY_ALBUM = "album"
        private const val KEY_NCM_ID = "ncmmusicid"
        private const val KEY_APPLE_ID = "applemusicid"

        private val WHITESPACE = Regex("\\s+")

        private val RANKING: Comparator<ScoredEntry> =
            compareByDescending<ScoredEntry> { it.score }
                .thenBy { it.entry.title }
                .thenBy { it.entry.rawLyricFile }

        /** Parses a whole catalog body. [maxEntries] bounds what is kept. */
        fun parse(text: String, maxEntries: Int = MAX_ENTRIES): AmllCatalogIndex =
            parseLines(text.lineSequence(), maxEntries)

        /**
         * Parses a catalog stream. The host feeds a line sequence so a large file is
         * never materialized twice; parsing stops once [maxEntries] entries are kept.
         */
        fun parseLines(lines: Sequence<String>, maxEntries: Int = MAX_ENTRIES): AmllCatalogIndex {
            val capacity = maxEntries.coerceIn(0, MAX_ENTRIES)
            if (capacity == 0) return AmllCatalogIndex(emptyList())
            val byFile = LinkedHashMap<String, AmllCatalogEntry>(minOf(capacity, INITIAL_CAPACITY))
            for (line in lines) {
                if (byFile.size >= capacity) break
                val entry = parseLine(line) ?: continue
                if (!byFile.containsKey(entry.rawLyricFile)) byFile[entry.rawLyricFile] = entry
            }
            return AmllCatalogIndex(byFile.values.toList())
        }

        /**
         * Parses one JSON line, or null when the line is unusable.
         *
         * Skipped: blank lines, lines above [MAX_LINE_BYTES], invalid JSON, a missing
         * or unsafe file name (no separators, no control characters, not dot-prefixed,
         * bounded length), a missing metadata block, and a line that names neither a
         * title nor an artist (nothing a search could match).
         */
        fun parseLine(line: String): AmllCatalogEntry? {
            val text = line.trim()
            if (text.isEmpty()) return null
            if (text.length > MAX_LINE_CHARS) return null
            if (text.toByteArray(Charsets.UTF_8).size > MAX_LINE_BYTES) return null
            val raw = runCatching { JSONObject(text) }.getOrNull() ?: return null
            val rawLyricFile = readFileName(raw.optString(KEY_RAW_LYRIC_FILE, "")) ?: return null
            val metadata = raw.optJSONArray(KEY_METADATA) ?: return null
            val fields = readMetadata(metadata)
            val title = fields[KEY_TITLE].firstValue()
            val artists = fields[KEY_ARTISTS].orEmpty()
            if (title.isEmpty() && artists.isEmpty()) return null
            return AmllCatalogEntry(
                rawLyricFile = rawLyricFile,
                title = title,
                artists = artists,
                album = fields[KEY_ALBUM].firstValue(),
                ncmMusicIds = fields[KEY_NCM_ID].orEmpty(),
                appleMusicIds = fields[KEY_APPLE_ID]
                    .orEmpty()
                    .mapNotNull(String::toLongOrNull)
                    .filter { it > 0L },
            )
        }

        /**
         * Reads the metadata block: an array of [key, values] pairs whose values are
         * normally arrays of strings but may also be a bare string.
         */
        private fun readMetadata(metadata: JSONArray): Map<String, List<String>> {
            val fields = HashMap<String, List<String>>(4)
            for (index in 0 until metadata.length()) {
                val pair = metadata.optJSONArray(index) ?: continue
                if (pair.length() < 2) continue
                val key = pair.optString(0, "").trim().lowercase(Locale.ROOT)
                if (key.isEmpty()) continue
                val values = readValues(pair.opt(1))
                if (values.isNotEmpty()) fields[key] = values
            }
            return fields
        }

        private fun readValues(raw: Any?): List<String> = when (raw) {
            is JSONArray -> buildList {
                val bound = minOf(raw.length(), MAX_METADATA_VALUES)
                for (index in 0 until bound) {
                    val value = readValue(raw.opt(index))
                    if (value.isNotEmpty()) add(value)
                }
            }
            else -> readValue(raw).takeIf { it.isNotEmpty() }?.let(::listOf).orEmpty()
        }

        private fun readValue(raw: Any?): String = when (raw) {
            is String -> raw.trim().take(MAX_VALUE_CHARS)
            is Number -> raw.toString()
            is Boolean -> raw.toString()
            else -> ""
        }

        private fun readFileName(raw: String): String? {
            val value = raw.trim()
            if (value.isEmpty() || value.length > MAX_FILE_NAME_CHARS) return null
            if (value.startsWith(".")) return null
            if (value.any { it.isISOControl() || it == '/' || it == '\\' }) return null
            return value
        }

        private fun List<String>?.firstValue(): String = this?.firstOrNull().orEmpty()

    }
}
