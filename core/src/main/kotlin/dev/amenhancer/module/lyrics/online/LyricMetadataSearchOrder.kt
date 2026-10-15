package dev.amenhancer.module.lyrics.online

/*
 * Ported verbatim from HLE's `OnlineLyricTargeterPolicy.kt` (HLE HEAD ad460bb):
 * `shouldRetryWithOriginalMetadata` and `resolveMetadataSearchOrder`. Only the
 * `OnlineLyricTargeter` extension receiver is dropped, because the fork's chain
 * is not an `OnlineLyricTargeter`; the bodies are unchanged. The visibility is
 * `public` rather than HLE's `internal` because `core` is a separate Gradle
 * module from the host that calls them. These two functions are the authority
 * for how the current and the Apple-internal original metadata are ordered —
 * do not "improve" them here.
 */

/**
 * Whether Apple resolved original metadata that really differs from the
 * displayed one. Blank on both sides means "nothing to retry with", and a
 * difference in either field counts, case-insensitively.
 */
fun shouldRetryWithOriginalMetadata(
    title: String,
    artist: String,
    originalTitle: String?,
    originalArtist: String?,
): Boolean {
    val resolvedTitle = originalTitle?.trim().orEmpty()
    val resolvedArtist = originalArtist?.trim().orEmpty()
    if (resolvedTitle.isEmpty() && resolvedArtist.isEmpty()) return false
    return !resolvedTitle.equals(title.trim(), ignoreCase = true) ||
        !resolvedArtist.equals(artist.trim(), ignoreCase = true)
}

/**
 * The metadata passes to search, in order: `false` means the displayed
 * metadata, `true` means Apple's internal original. No distinct original means
 * a single current pass; otherwise the current pass comes first unless
 * [preferOriginalMetadata] asks for the original one first.
 */
fun resolveMetadataSearchOrder(
    preferOriginalMetadata: Boolean,
    hasDistinctOriginalMetadata: Boolean,
): List<Boolean> = when {
    !hasDistinctOriginalMetadata -> listOf(false)
    preferOriginalMetadata -> listOf(true, false)
    else -> listOf(false, true)
}
