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
        candidates.filter { it.score >= PASS_SCORE }.maxByOrNull { it.score }?.song

    fun calculateScore(
        song: SongSearchResult,
        cleanLocalTitle: String,
        localArtists: List<String>,
        localFeatures: List<String>,
        localDurationMs: Long,
        cleanLocalAlbum: String,
        toSimplified: (String) -> String = { it },
    ): Int {
        var score = 0

        if (localDurationMs > 0 && song.duration > 0) {
            score += durationScore(localDurationMs, song.duration)
        }

        val cleanSongTitle = cleanString(song.title, toSimplified)

        if (cleanLocalTitle == cleanSongTitle ||
            cleanSongTitle.contains(cleanLocalTitle) ||
            cleanLocalTitle.contains(cleanSongTitle)
        ) {
            score += 50
        }

        val songArtists = splitArtists(song.artist).map { cleanString(it, toSimplified) }

        val hasCommonArtist = localArtists.any { localArtist ->
            songArtists.any { songArtist ->
                localArtist == songArtist ||
                    songArtist.contains(localArtist) ||
                    localArtist.contains(songArtist)
            }
        }
        if (hasCommonArtist) {
            score += 30
        }

        val remoteAlbum = normalizeAlbumForComparison(song.album, toSimplified)
        score += albumScore(cleanLocalAlbum, remoteAlbum)

        val songFeatures = featuresOf(song.title)

        if (localFeatures.isNotEmpty() && songFeatures.isNotEmpty()) {
            val commonFeatures = localFeatures.intersect(songFeatures.toSet())
            if (commonFeatures.isNotEmpty()) {
                score += 20
            }
        }

        return score
    }

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
        Normalizer.normalize(input, Normalizer.Form.NFKC)

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
        } while (changed)
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
     * Normalizes a title/artist token: drops bracketed segments, lowercases,
     * optionally simplifies, then removes all internal whitespace.
     */
    fun cleanString(input: String, toSimplified: (String) -> String = { it }): String {
        val cleaned = input.replace(BRACKETED_SEGMENT, "").trim().lowercase()
        return compactWhitespace(toSimplified(cleaned))
    }

    fun durationScore(localDurationMs: Long, remoteDurationMs: Long): Int {
        val diffMs = abs(localDurationMs - remoteDurationMs)
        return when {
            diffMs > 5_000L -> -30
            diffMs < STRONG_DURATION_TOLERANCE_MS -> 15
            else -> 10
        }
    }

    fun compactWhitespace(value: String): String = value.replace(WHITESPACE, "")

    fun splitArtists(value: String): List<String> =
        value.split("&", ",", "，", "、", "/", "／")

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
