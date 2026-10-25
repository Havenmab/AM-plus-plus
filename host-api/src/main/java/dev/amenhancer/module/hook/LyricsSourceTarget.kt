package dev.amenhancer.module.hook

import android.app.Activity

/**
 * Host capability for the 「歌词源…」 menu entry and the sheet behind it.
 *
 * The user-facing surface is Apple Music's own lyrics "more" popup: the module
 * appends one item under the two native rows (隐藏发音 / 隐藏翻译) and opens a
 * Compose sheet that shows where each of the three lanes (lyrics / translation /
 * romanization) currently comes from and lets the user switch it.
 *
 * Everything that decides whether the entry may appear is host-side: the popup
 * contract, the current-song probe and the apply pipeline. The sheet itself
 * lives in the module's app module, so this capability only reports whether the
 * entry could be installed at all — a [TargetCapabilityInstall.Degraded] result
 * means the native popup was left untouched.
 */
fun interface LyricsSourceTarget {
    fun install(): TargetCapabilityInstall
}

/**
 * Bridge from the Apple Music process to the module's Compose sheet.
 *
 * The app module registers [show] once while it boots the settings surface. The
 * host never references the sheet class, so `:host-applemusic` keeps no
 * dependency on `:app` (which depends on the host, not the other way around).
 *
 * A null [show] means the sheet cannot be opened in this process; the menu hook
 * then refuses to add its item, because a menu row that opens nothing is worse
 * than no row at all. The field is volatile because the host reads it on the
 * main thread while the app installs or clears it from its own lifecycle.
 */
object LyricsSourceSheetEntry {

    @Volatile
    var show: ((Activity) -> Unit)? = null

    /** True when an app-side surface is registered and the entry may appear. */
    val available: Boolean get() = show != null

    /**
     * Opens the sheet for [activity].
     *
     * Returns false when no surface is registered, or when the registered one
     * threw (a closed Activity, a Compose host that was already torn down). The
     * caller logs that and keeps the native menu behaviour intact.
     */
    fun open(activity: Activity): Boolean {
        val entry = show ?: return false
        return runCatching { entry(activity) }.isSuccess
    }

    /** Clears the surface only when it is still the given one (mirrors uninstall semantics). */
    fun uninstall(registered: (Activity) -> Unit) {
        if (show === registered) show = null
    }
}

/**
 * Write seam for the module's custom-lyrics index.
 *
 * The host can *read* the index through [dev.amenhancer.module.config.TargetConfigClient]
 * (`customLyricsManifest` / `openFile`), but writing it is app-side work: the
 * index lives in the module's own configuration storage and the app owns the
 * session that publishes it. Two operations are all the sheet needs:
 *
 * - [replace] makes one song's document become `ttml` from provider `source`,
 *   in one atomic step (content + manifest entry + source tag), so a failed
 *   write can never leave a half-updated index behind.
 * - [remove] deletes the song's entry and its cached body, which is what
 *   "恢复 Apple 原生" needs when the displayed lyrics are a custom document.
 *
 * Both are registered by the app module. When they are absent, the controller
 * refuses the switch with [dev.amenhancer.module.lyrics.source.LyricSourceErrorCodes.NOT_SUPPORTED]
 * and a user-facing Chinese reason instead of silently doing nothing.
 */
object LyricsSourceCustomIndexBridge {

    /** `(appleMusicId, ttml, providerSource) -> replaced`. */
    @Volatile
    var replace: ((Long, String, String) -> Boolean)? = null

    /** `(appleMusicId) -> removed`. */
    @Volatile
    var remove: ((Long) -> Boolean)? = null

    val canReplace: Boolean get() = replace != null

    val canRemove: Boolean get() = remove != null

    /** Replaces the song's custom-lyrics document; false when no writer is registered. */
    fun replaceLyrics(songId: Long, ttml: String, source: String): Boolean {
        val writer = replace ?: return false
        return runCatching { writer(songId, ttml, source) }.getOrDefault(false)
    }

    /** Removes the song's custom-lyrics document; false when no writer is registered. */
    fun removeLyrics(songId: Long): Boolean {
        val writer = remove ?: return false
        return runCatching { writer(songId) }.getOrDefault(false)
    }
}
