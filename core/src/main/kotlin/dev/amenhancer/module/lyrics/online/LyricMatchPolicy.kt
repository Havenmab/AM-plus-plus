package dev.amenhancer.module.lyrics.online

import java.text.Normalizer
import kotlin.math.abs

/** How a passing candidate is chosen from the scored, source-ordered candidates. */
enum class LyricSelectionMode {
    /** First candidate in the configured source order whose score passes. */
    FIRST_PASSING,

    /** Highest-scoring passing candidate, regardless of source order. */
    GLOBAL_BEST,
}

/** A candidate paired with its computed match score. */
data class ScoredSong(
    val song: SongSearchResult,
    val score: Int,
)

/**
 * The per-component evidence behind one candidate's [LyricMatchPolicy.calculateScore].
 * Every field is one of the weights the scorer has always used; [total] is their
 * sum, so the diagnostics and the pass/fail decision can never disagree.
 */
data class ScoreBreakdown(
    val title: Int,
    val artist: Int,
    val album: Int,
    val duration: Int,
    val features: Int,
) {
    val total: Int get() = title + artist + album + duration + features
}

/**
 * Ported match scoring and candidate selection from HyperLyricsEnhanced's
 * `OnlineLyricTargeter` / `OnlineLyricTargeterPolicy`, reduced to a pure
 * function over this module's own models.
 *
 * The Android-specific simplification step is injected as [toSimplified], so the
 * policy has no platform dependency; callers that need 繁体→简体 pass their own
 * converter, while tests and other callers get an identity default.
 */
object LyricMatchPolicy {

    /** A candidate at or above this score is accepted outright. */
    const val PASS_SCORE = 85

    /** Floor for a near-miss candidate that still needs lyric-overlap verification. */
    const val NEAR_MISS_MIN_SCORE = 50

    /** Duration drift under which a candidate is treated as strong identity. */
    const val STRONG_DURATION_TOLERANCE_MS = 1_500L

    /**
     * The duration channel's strong-identity credit. It is also the neutral
     * value used when either side exposes no length, so an unavailable duration
     * can never by itself deny a title/artist identity.
     */
    const val STRONG_DURATION_SCORE = 15

    /** Selects a candidate for [mode]; the ordered mode is the one wired today. */
    fun select(
        candidates: List<ScoredSong>,
        mode: LyricSelectionMode = LyricSelectionMode.FIRST_PASSING,
    ): SongSearchResult? = when (mode) {
        LyricSelectionMode.FIRST_PASSING -> selectFirstPassing(candidates)
        LyricSelectionMode.GLOBAL_BEST -> selectGlobalBest(candidates)
    }

    /** First candidate in the configured source order whose score passes. */
    fun selectFirstPassing(candidates: List<ScoredSong>): SongSearchResult? =
        candidates.firstOrNull { it.score >= PASS_SCORE }?.song

    /** Highest-scoring passing candidate; the seam for the later opt-in mode. */
    fun selectGlobalBest(candidates: List<ScoredSong>): SongSearchResult? =
        selectGlobalBestScored(candidates, ScoredSong::score)?.song

    /**
     * Highest-scoring passing entry of [entries], keeping the caller's own
     * wrapper type. The cross-source chain uses this to remember which provider
     * produced the winner without the policy depending on the provider.
     */
    fun <T> selectGlobalBestScored(entries: List<T>, score: (T) -> Int): T? =
        entries.filter { score(it) >= PASS_SCORE }.maxByOrNull(score)

    fun calculateScore(
        song: SongSearchResult,
        cleanLocalTitle: String,
        localArtists: List<String>,
        localFeatures: List<String>,
        localDurationMs: Long,
        cleanLocalAlbum: String,
        toSimplified: (String) -> String = { it },
    ): Int = scoreBreakdown(
        song = song,
        cleanLocalTitle = cleanLocalTitle,
        localArtists = localArtists,
        localFeatures = localFeatures,
        localDurationMs = localDurationMs,
        cleanLocalAlbum = cleanLocalAlbum,
        toSimplified = toSimplified,
    ).total

    /**
     * The same score as [calculateScore], split into the component that earned
     * each point. The duration channel is scored from the real lengths when both
     * sides expose one; when either side is missing a length the channel stays
     * neutral and credits the strong-identity score, because an unavailable
     * length is a metadata gap on one side, not evidence against the candidate.
     * A candidate that *does* report a length far from the local one is still
     * penalised by [durationScore]. The component weights themselves are
     * unchanged.
     */
    fun scoreBreakdown(
        song: SongSearchResult,
        cleanLocalTitle: String,
        localArtists: List<String>,
        localFeatures: List<String>,
        localDurationMs: Long,
        cleanLocalAlbum: String,
        toSimplified: (String) -> String = { it },
    ): ScoreBreakdown = ScoreBreakdown(
        title = channel { titleScore(cleanLocalTitle, cleanString(song.title, toSimplified)) },
        // Aliases count: a provider that localises the artist name keeps the
        // original in brackets ("七音阿卡莉 (NANAOAKARI)"), and erasing that bracket
        // is what made every such candidate score 70 against the 85 floor.
        artist = channel { artistScore(localArtists, song.artist, toSimplified) },
        album = channel {
            albumScore(cleanLocalAlbum, normalizeAlbumForComparison(song.album, toSimplified))
        },
        // A length that cannot be read is neutral (never a penalty), so the
        // fallback is the strong-identity credit rather than zero.
        duration = runCatching { durationChannel(localDurationMs, song.duration) }
            .getOrDefault(STRONG_DURATION_SCORE),
        features = channel { featuresScore(localFeatures, song.title) },
    )

    /** The title channel: containment in either direction, as the scorer always done. */
    private fun titleScore(cleanLocalTitle: String, cleanSongTitle: String): Int =
        if (cleanLocalTitle == cleanSongTitle ||
            cleanSongTitle.contains(cleanLocalTitle) ||
            cleanLocalTitle.contains(cleanSongTitle)
        ) {
            50
        } else {
            0
        }

    /**
     * The artist channel, through [artistIdentities] so a bracketed alias counts.
     *
     * It runs once per candidate of every lane, so a credit line that cannot be
     * parsed is worth zero points and never an exception: an escaping throw here
     * used to take the whole sheet snapshot down with it, which is one song's
     * rename away from 「暂无可选来源」 on every lane.
     */
    private fun artistScore(
        localArtists: List<String>,
        remoteCredit: String,
        toSimplified: (String) -> String,
    ): Int =
        if (hasCommonArtist(localArtists, artistIdentities(remoteCredit, toSimplified))) 30 else 0

    /** The recording-marker channel, compared on top of the title match. */
    private fun featuresScore(localFeatures: List<String>, songTitle: String): Int {
        val songFeatures = featuresOf(songTitle)
        return if (localFeatures.isNotEmpty() && songFeatures.isNotEmpty() &&
            localFeatures.intersect(songFeatures.toSet()).isNotEmpty()
        ) {
            20
        } else {
            0
        }
    }

    /**
     * The duration channel. A length missing on either side is neutral, not
     * evidence against the candidate.
     */
    private fun durationChannel(localDurationMs: Long, remoteDurationMs: Long): Int = when {
        localDurationMs <= 0L -> STRONG_DURATION_SCORE
        remoteDurationMs <= 0L -> STRONG_DURATION_SCORE
        else -> durationScore(localDurationMs, remoteDurationMs)
    }

    /**
     * One scoring channel can never fail its candidate: an unreadable field costs
     * that channel's points, which is the same answer a non-match earns, and the
     * rest of the breakdown still decides the match. This is the outer defence the
     * policy needs, because [scoreBreakdown] is called for every candidate on the
     * snapshot and search paths, where an exception degrades every lane at once.
     */
    private inline fun channel(block: () -> Int): Int = runCatching(block).getOrDefault(0)

    /** Recording/version markers that are compared on top of the title match. */
    val FEATURE_MARKERS = listOf("live", "remastered", "翻唱", "cover")

    /** Markers present in [title]; the same list is applied to local and remote. */
    fun featuresOf(title: String): List<String> =
        FEATURE_MARKERS.filter { title.lowercase().contains(it) }

    /**
     * Album scoring. Whitespace is compacted while word boundaries still exist;
     * only equality ignores whitespace entirely. A version/recording suffix that
     * differs is worth a partial score.
     */
    fun albumScore(cleanLocalAlbum: String, cleanRemoteAlbum: String): Int {
        val localExact = compactWhitespace(cleanLocalAlbum)
        val remoteExact = compactWhitespace(cleanRemoteAlbum)
        val localBase = compactWhitespace(stripAlbumVersionSuffixes(cleanLocalAlbum))
        val remoteBase = compactWhitespace(stripAlbumVersionSuffixes(cleanRemoteAlbum))
        return when {
            localExact.isEmpty() || remoteExact.isEmpty() -> 0
            localExact == remoteExact -> 10
            localBase.isNotEmpty() && localBase == remoteBase -> 5
            else -> 0
        }
    }

    /** NFKC normalization stays shared; album word boundaries survive until scoring. */
    fun normalizeAlbumCharacters(input: String): String =
        stripInvisible(Normalizer.normalize(input, Normalizer.Form.NFKC))

    /** Same pipeline for local and remote albums; the only platform step is simplification. */
    fun normalizeAlbumForComparison(
        input: String,
        toSimplified: (String) -> String = { it },
    ): String {
        val cleaned = normalizeAlbumCharacters(input)
            .replace(BRACKETED_SEGMENT, "")
            .trim().lowercase()
        return toSimplified(cleaned).replace(WHITESPACE, " ").trim()
    }

    /**
     * Strips trailing version/recording markers from the end only, so that
     * "原曲 / 现场 / 不插电 / 翻唱 / 豪华版" variants of one album are not treated
     * as different albums, while ordinary names ("Greatest Hits") stay intact.
     */
    fun stripAlbumVersionSuffixes(value: String): String {
        var result = value.trim()
        var changed: Boolean
        var passes = 0
        do {
            changed = false
            for (suffix in ALBUM_VERSION_SUFFIXES.sortedByDescending { it.length }) {
                val stripped = stripTrailingVersionSuffix(result, suffix)
                if (stripped != result) {
                    result = stripped
                    changed = true
                    break
                }
            }
            // Each pass only shortens the value, so the loop cannot spin; the bound
            // is there so a future suffix table cannot turn that into a hot loop.
            passes++
        } while (changed && passes < MAX_SUFFIX_STRIPS)
        return result
    }

    fun stripTrailingVersionSuffix(value: String, suffix: String): String {
        if (suffix.isEmpty() || !value.endsWith(suffix, ignoreCase = true)) return value
        if (value.length == suffix.length) return ""

        val boundary = value.length - suffix.length
        val preceding = value[boundary - 1]
        // CJK suffixes (e.g. 豪华版) attach directly to an English album name;
        // English suffixes need a separator so "Alive" is not read as "live".
        val suffixIsCjk = suffix.any { it.isCjkUnifiedIdeograph() }
        if (!suffixIsCjk && preceding.isLetterOrDigit()) return value

        return value.substring(0, boundary).trimEnd { it in ALBUM_SUFFIX_SEPARATOR_CHARS }
    }

    fun Char.isCjkUnifiedIdeograph(): Boolean = code in 0x4E00..0x9FFF

    /**
     * The invisible code points Apple metadata carries in the wild but no
     * provider indexes: the zero-width/joiner block plus the soft hyphen, word
     * joiner and byte-order mark. They are dropped before a query is built and
     * before any identity comparison, so a local title is never sent or scored
     * under a different effective spelling (a ZWSP between two glyphs makes the
     * provider see a different word). Ordinary U+0020 spaces are kept: only the
     * zero-width/space-separator block is removed, never a real word boundary.
     */
    private val INVISIBLE_CHARACTERS = Regex("[\\u00AD\\u2000-\\u200F\\u2060\\uFEFF]")

    /** Drops every [INVISIBLE_CHARACTERS] code point; ordinary spaces survive. */
    fun stripInvisible(value: String): String = value.replace(INVISIBLE_CHARACTERS, "")

    /**
     * Normalizes a title/artist token: drops invisible characters and bracketed
     * segments, lowercases, optionally simplifies, then removes all internal
     * whitespace.
     */
    fun cleanString(input: String, toSimplified: (String) -> String = { it }): String {
        val cleaned = stripInvisible(input).replace(BRACKETED_SEGMENT, "").trim().lowercase()
        return compactWhitespace(toSimplified(cleaned))
    }

    fun durationScore(localDurationMs: Long, remoteDurationMs: Long): Int {
        val diffMs = abs(localDurationMs - remoteDurationMs)
        return when {
            diffMs > 5_000L -> -30
            diffMs < STRONG_DURATION_TOLERANCE_MS -> STRONG_DURATION_SCORE
            else -> 10
        }
    }

    fun compactWhitespace(value: String): String = value.replace(WHITESPACE, "")

    fun splitArtists(value: String): List<String> =
        value.split("&", ",", "，", "、", "/", "／")

    /**
     * Every identity token one artist credit line carries, for the artist half of
     * the score.
     *
     * A provider that localises a name keeps the original beside it in brackets —
     * `七音阿卡莉 (NANAOAKARI)`, `NANAOAKARI (七音阿卡莉)` — while Apple may expose only
     * one of the two. [cleanString] drops bracketed segments (correctly, for a
     * title whose bracket is a version marker), so comparing the cleaned credit
     * alone compared `nanaoakari` against `七音阿卡莉` and scored **zero** on the
     * artist channel. The device log's rejected candidate is exactly that shape:
     * `title="明日の私に幸あれ" artist="七音阿卡莉 (NANAOAKARI)" … artist=0 … total=70`
     * against the 85 floor, and `decision … candidates=0 reason=no_candidates`.
     *
     * So the identity set is the split credit **plus** the content of every
     * bracketed segment written in a different script than the name around it.
     * That is an alias, not a guess: `Artist (Live)` adds nothing because the
     * bracket is written in the same script as the name, while
     * `七音阿卡莉 (NANAOAKARI)` contributes both spellings of the one performer.
     *
     * The function is total: a credit line it cannot parse contributes its plain
     * identity and no alias, and never propagates an exception. It runs for every
     * candidate of every lane, so a throw here would cost the whole sheet its lane
     * list instead of one row its alias.
     */
    fun artistIdentities(
        creditLine: String,
        toSimplified: (String) -> String = { it },
    ): List<String> {
        val names = splitArtists(creditLine).map { it.trim() }.filter { it.isNotEmpty() }
        val identities = LinkedHashSet<String>()
        names.forEach { name ->
            identityOf(name, toSimplified)?.let(identities::add)
            // One malformed credit line costs only its own aliases: the plain
            // identity above is already in, and nothing below may propagate. That is
            // the per-line degradation the device log demanded - a single line used
            // to fail the whole candidate, and with it every lane of the sheet.
            runCatching { aliasIdentitiesOf(name, toSimplified) }
                .getOrElse { emptyList() }
                .forEach { alias -> identities.add(alias) }
        }
        return identities.toList()
    }

    /** The cleaned identity of one credit token, or null when nothing is left of it. */
    private fun identityOf(value: String, toSimplified: (String) -> String): String? =
        runCatching { cleanString(value, toSimplified) }
            .getOrNull()
            .orEmpty()
            .takeIf { it.isNotEmpty() }

    /**
     * The aliases one credit token carries: every bracketed group written in a
     * script different from the name around it.
     *
     * A credit that is nothing *but* a bracketed name has no outer script to
     * compare against, so its groups are taken as the identities outright: that is
     * the only spelling the token has ("(NANAOAKARI)").
     */
    private fun aliasIdentitiesOf(
        name: String,
        toSimplified: (String) -> String,
    ): List<String> {
        val segments = bracketedSegments(name)
        if (segments.isEmpty()) return emptyList()
        val outerScript = scriptOf(withoutBracketedSegments(name))
        return segments.asSequence()
            .filter { alias -> outerScript == Script.NONE || scriptOf(alias) != outerScript }
            .mapNotNull { alias -> identityOf(alias, toSimplified) }
            .toList()
    }

    /** The raw content of every `(…)` / `（…）` / `[…]` / `【…】` group in [value]. */
    private fun bracketedSegments(value: String): List<String> {
        val segments = ArrayList<String>(2)
        var index = 0
        while (index < value.length) {
            val closer = closingBracketFor(value[index])
            if (closer == null) {
                index++
                continue
            }
            val end = value.indexOf(closer, index + 1)
            if (end < 0) {
                // Unbalanced opener: it is text, not a group.
                index++
                continue
            }
            val inner = value.substring(index + 1, end).trim()
            if (inner.isNotEmpty() && inner.any { it.isLetter() }) {
                segments.add(inner)
                if (segments.size >= MAX_ALIAS_SEGMENTS) return segments
            }
            index = end + 1
        }
        return segments
    }

    /** [value] with every bracketed group removed, i.e. the name's own spelling. */
    private fun withoutBracketedSegments(value: String): String {
        val builder = StringBuilder(value.length)
        var index = 0
        while (index < value.length) {
            val closer = closingBracketFor(value[index])
            if (closer == null) {
                builder.append(value[index])
                index++
                continue
            }
            val end = value.indexOf(closer, index + 1)
            if (end < 0) {
                builder.append(value[index])
                index++
                continue
            }
            index = end + 1
        }
        return builder.toString()
    }

    /** The closer matching an opener, or null when [open] is not a bracket at all. */
    private fun closingBracketFor(open: Char): Char? = when (open) {
        '(' -> ')'
        '（' -> '）'
        '[' -> ']'
        '【' -> '】'
        '{' -> '}'
        '｛' -> '｝'
        else -> null
    }

    /** The script a credit token is written in, for the alias test above. */
    private enum class Script { NONE, LATIN, OTHER, MIXED }

    private fun scriptOf(value: String): Script {
        var latin = false
        var other = false
        var index = 0
        while (index < value.length) {
            val codePoint = value.codePointAt(index)
            index += Character.charCount(codePoint)
            if (!Character.isValidCodePoint(codePoint) || !Character.isLetter(codePoint)) {
                continue
            }
            if (isLatinLetter(codePoint)) {
                latin = true
            } else {
                other = true
            }
        }
        return when {
            latin && other -> Script.MIXED
            latin -> Script.LATIN
            other -> Script.OTHER
            else -> Script.NONE
        }
    }

    /**
     * The letters of the Latin script a credit actually carries: Basic Latin,
     * Latin-1 Supplement, Latin Extended-A/B and the full-width A-Z forms.
     * Everything else that is a letter counts as a different script, which is the
     * only distinction the alias rule needs.
     *
     * It deliberately avoids `Character.UnicodeScript`: that class is newer than
     * this module's minimum API level, and a missing class fails at the *class*
     * level - every later scoring call, and therefore every lane of the sheet, not
     * just the one credit line that reached it.
     */
    private fun isLatinLetter(codePoint: Int): Boolean = when (codePoint) {
        in 0x0041..0x005A, in 0x0061..0x007A -> true
        in 0x00C0..0x024F -> true
        in 0x1E00..0x1EFF -> true
        in 0xFF21..0xFF3A, in 0xFF41..0xFF5A -> true
        else -> false
    }

    /**
     * Introduces a featured performer who is not the primary artist. Ported from
     * HyperLyricsEnhanced's query surface: the credit list itself is still split
     * by [splitArtists] for identity comparison, but the search keyword must not
     * carry a featured name ahead of the performer the provider indexes.
     */
    private val FEATURED_CREDIT = Regex(
        "\\s+(?:feat\\.?|ft\\.?|featuring)\\s+",
        RegexOption.IGNORE_CASE,
    )

    /**
     * The primary credited artist: the first name before any multi-credit
     * separator or featured-credit marker. `"ナナツカゼ, PIKASONIC, なこたんまる"`
     * queries as `"ナナツカゼ"` so the whole credit list cannot bury the performer,
     * while [splitArtists] still sees every name for the artist score. An empty
     * or marker-only artist yields the empty string.
     */
    fun primaryArtist(artist: String): String {
        val beforeFeature = FEATURED_CREDIT.split(artist).firstOrNull().orEmpty()
        return splitArtists(beforeFeature).firstOrNull { it.isNotBlank() }?.trim().orEmpty()
    }

    /** The feature marker itself, word-bounded so "Defeat" is not a credit. */
    private val FEATURE_CREDIT_MARKER = Regex(
        "\\b(?:feat\\.?|ft\\.?|featuring)\\b",
        RegexOption.IGNORE_CASE,
    )

    /** A parenthesized/bracketed segment at the very end of a title, ASCII or full width. */
    private val TRAILING_PARENTHETICAL = Regex(
        "\\s*[\\(（\\[【][^)\\uFF09\\]】]*[\\)）\\]】]\\s*$",
    )

    /** An unparenthesized credit that runs to the end of the title. */
    private val TRAILING_FEATURE_CREDIT = Regex(
        "\\s+(?:feat\\.?|ft\\.?|featuring)\\s+.*$",
        RegexOption.IGNORE_CASE,
    )

    /** Openers whose group the bare-credit rule must not cut through. */
    private val BRACKET_OPENERS = setOf('(', '（', '[', '【', '{', '｛')

    /**
     * A title with a trailing feature credit removed, or null when it carries
     * none. `"Fake Bones (feat. 中村さんそ)"` becomes `"Fake Bones"`, while a
     * trailing parenthetical that is not a credit (`"Song (Live)"`) is kept, so
     * a version marker is not silently dropped from the query. Only a credit at
     * the very end counts: a credit in the middle of the title
     * (`"… (feat. X) アオワイファイ"`) stays, because the tail is part of the
     * real title. The caller uses the result for the one bounded retry search.
     */
    fun stripTrailingFeatureCredit(title: String): String? {
        val cleaned = stripInvisible(title).trim()
        if (cleaned.isEmpty()) return null

        TRAILING_PARENTHETICAL.find(cleaned)
            ?.takeIf { FEATURE_CREDIT_MARKER.containsMatchIn(it.value) }
            ?.let { match ->
                val prefix = cleaned.substring(0, match.range.first).trim()
                if (prefix.isNotEmpty()) return prefix
            }

        val bare = TRAILING_FEATURE_CREDIT.find(cleaned)
        if (bare != null && cleaned.getOrNull(bare.range.first - 1) !in BRACKET_OPENERS) {
            val prefix = cleaned.substring(0, bare.range.first).trim()
            if (prefix.isNotEmpty()) return prefix
        }
        return null
    }

    fun isStrongTitleMatch(localTitle: String, remoteTitle: String): Boolean =
        localTitle.isNotEmpty() && (
            localTitle == remoteTitle ||
                remoteTitle.contains(localTitle) ||
                localTitle.contains(remoteTitle)
            )

    fun isStrongDurationMatch(localDurationMs: Long, remoteDurationMs: Long): Boolean =
        localDurationMs <= 0L ||
            abs(localDurationMs - remoteDurationMs) < STRONG_DURATION_TOLERANCE_MS

    fun isNearMissEligible(
        score: Int,
        titleMatched: Boolean,
        durationClose: Boolean,
    ): Boolean = score >= NEAR_MISS_MIN_SCORE && titleMatched && durationClose

    fun isMultiCreditArtist(artists: List<String>): Boolean =
        artists.count(String::isNotBlank) >= 2

    fun hasCommonArtist(
        localArtists: List<String>,
        remoteArtists: List<String>,
    ): Boolean = localArtists.any { local ->
        local.isNotBlank() && remoteArtists.any { remote ->
            remote.isNotBlank() &&
                (local == remote || remote.contains(local) || local.contains(remote))
        }
    }

    /**
     * A candidate whose duration did not line up falls back to line-level lyric
     * pairing. [multiCredit] is the existing multi-artist channel; [artistMatched]
     * only applies to callers that opt in, who must then verify identity by lyric
     * overlap.
     */
    fun isLyricFallbackEligible(
        titleMatched: Boolean,
        multiCredit: Boolean,
        durationClose: Boolean,
        artistMatched: Boolean = false,
    ): Boolean = titleMatched && !durationClose && (multiCredit || artistMatched)

    private val WHITESPACE = Regex("\\s+")
    private val BRACKETED_SEGMENT = Regex("\\(.*?\\)|\\[.*?]|\\{.*?\\}")

    /** Bound on the aliases one credit line can contribute, so a hostile line stays cheap. */
    private const val MAX_ALIAS_SEGMENTS = 4

    /** Bound on [stripAlbumVersionSuffixes] passes over one album name. */
    private const val MAX_SUFFIX_STRIPS = 8

    private val ALBUM_SUFFIX_SEPARATOR_CHARS = setOf(' ', '-', '_', '~', '·', '|', '/')

    private val ALBUM_VERSION_SUFFIXES = listOf(
        // English version / recording markers, longest first.
        "live version", "acoustic version", "piano version", "studio version",
        "radio version", "deluxe edition", "full version", "clean version",
        "radio edit", "tv size", "hi-res", "320k",
        "live", "acoustic", "unplugged", "cover", "remastered", "remaster",
        "remix", "remixes", "deluxe", "explicit", "clean", "edited",
        "instrumental", "piano", "demo", "full", "studio", "radio", "edit",
        "single", "ep", "flac", "lossless",
        // Chinese version / recording markers.
        "现场版", "演唱会版", "不插电版", "木吉他版", "翻唱版", "重制版",
        "重置版", "混音版", "豪华版", "伴奏版", "纯音乐版", "钢琴版",
        "试听版", "完整版", "录音室版", "电台版", "单曲版", "无损版",
        "高音质版", "cover版", "remix版", "tv版", "短版", "剪辑版",
        "现场", "演唱会", "不插电", "吉他版", "翻唱", "重制", "重置",
        "混音", "豪华", "伴奏", "纯音乐", "试听", "录音室", "电台",
        "单曲", "无损", "高音质", "版",
    )
}
