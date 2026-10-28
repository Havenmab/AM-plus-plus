package dev.amenhancer.module.hook

/**
 * Write-through seam for the overlay cache.
 *
 * The automatic completion pipeline runs in the app module and publishes the merged translation/
 * pronunciation lanes into the in-memory [dev.amenhancer.module.lyrics.online.NativeLyricOverlayStore].
 * Only the host module owns the on-disk copy, so it registers the writer here and the app calls it
 * whenever it publishes lanes. Without it a restart forgot which provider supplied a translation
 * until the lyrics page re-parsed the displayed document.
 */
object LyricsSourceOverlayCacheBridge {

    /** `(songId, mergedTtml, translationSource, pronunciationSource, originalSource, languages) -> stored`. */
    @Volatile
    var save: ((Long, String, String?, String?, String?, List<String>) -> Boolean)? = null

    /** Best-effort: a cache write must never break the publish that triggered it. */
    fun remember(
        songId: Long,
        mergedTtml: String,
        translationSource: String?,
        pronunciationSource: String?,
        originalSource: String?,
        languages: List<String>,
    ): Boolean = runCatching {
        save?.invoke(songId, mergedTtml, translationSource, pronunciationSource, originalSource, languages) ?: false
    }.getOrDefault(false)

    fun uninstall(writer: (Long, String, String?, String?, String?, List<String>) -> Boolean) {
        if (save === writer) save = null
    }
}