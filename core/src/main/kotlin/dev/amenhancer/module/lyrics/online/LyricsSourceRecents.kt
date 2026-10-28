package dev.amenhancer.module.lyrics.online

import dev.amenhancer.module.lyrics.source.OnlineLyricsCandidate

/** One source the user actually applied, remembered across sessions. */
data class LyricRecentSource(
    val providerId: String,
    val label: String,
    val title: String,
    val artist: String,
    val album: String,
    val remoteId: String,
    val usedAtMs: Long,
)

/**
 * Search ordering and the "recently used" shortcuts.
 *
 * Two separate needs share one history: the rows for the song currently playing should surface
 * first (a per-song memory), and the providers used last should be one tap away regardless of
 * the song. Both are pure list operations so the host can persist them and the sheet can render
 * them without touching storage.
 */
object LyricSourceRecents {

    const val MAX_ENTRIES = 30

    /** Distinct providers to show as quick picks, newest first. */
    const val QUICK_PICK_LIMIT = 6

    /** Prepends [entry], drops an older copy of the same source and trims the history. */
    fun record(
        existing: List<LyricRecentSource>,
        entry: LyricRecentSource,
        cap: Int = MAX_ENTRIES,
    ): List<LyricRecentSource> {
        if (entry.providerId.isBlank() || entry.remoteId.isBlank()) return existing
        val kept = existing.filterNot {
            it.providerId == entry.providerId && it.remoteId == entry.remoteId
        }
        return (listOf(entry) + kept).take(cap.coerceAtLeast(1))
    }

    /** Provider ids in the order the user last used them. */
    fun quickPickProviders(
        history: List<LyricRecentSource>,
        limit: Int = QUICK_PICK_LIMIT,
    ): List<String> {
        val seen = LinkedHashSet<String>()
        for (entry in history.sortedByDescending(LyricRecentSource::usedAtMs)) {
            if (entry.providerId.isNotBlank()) seen += entry.providerId
            if (seen.size >= limit) break
        }
        return seen.toList()
    }

    /**
     * Reorders [candidates] so the song's own history comes first, then the providers used most
     * recently, then [preferredProvider] (a quick-pick tap), and finally the original order.
     */
    fun pin(
        history: List<LyricRecentSource>,
        candidates: List<OnlineLyricsCandidate>,
        songTitle: String,
        songArtist: String,
        preferredProvider: String? = null,
    ): List<OnlineLyricsCandidate> {
        if (candidates.size < 2) return candidates
        val sorted = history.sortedByDescending(LyricRecentSource::usedAtMs)
        val sameSong = sorted
            .filter { matchesSong(it, songTitle, songArtist) }
            .map { it.providerId }
            .toSet()
        val providerRank = quickPickProviders(history, QUICK_PICK_LIMIT)
            .withIndex()
            .associate { (index, provider) -> provider to index }
        return candidates.sortedWith(
            compareByDescending<OnlineLyricsCandidate> { it.providerId in sameSong }
                .thenBy { candidate ->
                    when {
                        candidate.providerId == preferredProvider -> -1
                        else -> providerRank[candidate.providerId] ?: Int.MAX_VALUE
                    }
                },
        )
    }

    private fun matchesSong(entry: LyricRecentSource, title: String, artist: String): Boolean {
        if (title.isBlank() && artist.isBlank()) return false
        val sameTitle = title.isNotBlank() &&
            normalize(entry.title).contains(normalize(title))
        val sameArtist = artist.isBlank() ||
            entry.artist.isBlank() ||
            normalize(entry.artist).contains(normalize(artist))
        return sameTitle && sameArtist
    }

    private fun normalize(value: String): String =
        value.lowercase().filter { it.isLetterOrDigit() }

    /** Removes cross-provider duplicates of the same recording, keeping the first provider. */
    fun dedupe(candidates: List<OnlineLyricsCandidate>): List<OnlineLyricsCandidate> {
        if (candidates.size < 2) return candidates
        val seen = HashSet<String>(candidates.size)
        val kept = ArrayList<OnlineLyricsCandidate>(candidates.size)
        for (candidate in candidates) {
            val result = candidate.result
            val key = normalize(result.title) + "|" + normalize(result.artist)
            // A candidate without a recognisable identity cannot prove a duplicate.
            if (key == "|") {
                kept += candidate
                continue
            }
            if (seen.add(key)) kept += candidate
        }
        return kept
    }
}