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

    /**
     * `(appleMusicId, ttml, providerSource, displayName) -> failure message`.
     *
     * Null means the index accepted the document; a string is the user-facing reason the
     * write failed (an existing entry that could not be replaced, a rejected document, a
     * storage error). Returning the reason instead of a bare boolean is what lets the sheet
     * tell the user *why* a source could not be applied instead of a generic failure.
     *
     * [displayName] is the **song's** name, never the provider's: the custom-lyrics index
     * lists one row per song, so "AMLL" or "QQ 音乐" there describes nothing the user can
     * recognise (the provider is already in the entry's source field).
     */
    @Volatile
    var replace: ((Long, String, String, String) -> String?)? = null

    /** `(appleMusicId) -> removed`. */
    @Volatile
    var remove: ((Long) -> Boolean)? = null

    /**
     * `(appleMusicId, displayName) -> renamed`.
     *
     * Repairs an entry whose display name is still a provider label — the value every
     * sheet-written entry used to carry. The writer rewrites the manifest entry in place
     * (same body, same source, same flags), so the user never has to re-apply the source.
     */
    @Volatile
    var rename: ((Long, String) -> Boolean)? = null

    /** `(appleMusicId, mergedTtml, customSource, songName) -> saved`. */
    @Volatile
    var saveAutomatic: ((Long, String, String, String) -> Boolean)? = null

    /** `(appleMusicId, rawAppleTtml, songName) -> saved as disabled Apple-native copy`. */
    @Volatile
    var saveAppleNative: ((Long, String, String) -> Boolean)? = null

    val canReplace: Boolean get() = replace != null

    val canSaveAutomatic: Boolean get() = saveAutomatic != null

    val canSaveAppleNative: Boolean get() = saveAppleNative != null

    val canRemove: Boolean get() = remove != null

    val canRename: Boolean get() = rename != null

    /** Saves a successful automatic enrichment without taking a user-owned document. */
    fun saveAutomaticLyrics(songId: Long, ttml: String, source: String, displayName: String): Boolean {
        if (songId <= 0L || ttml.isBlank() || source.isBlank()) return false
        val writer = saveAutomatic ?: return false
        return runCatching { writer(songId, ttml, source, displayName) }.getOrDefault(false)
    }

    /** Saves a disabled Apple-native copy without changing the active lyric selection. */
    fun saveAppleNativeCopy(songId: Long, rawAppleTtml: String, songName: String): Boolean {
        if (songId <= 0L || rawAppleTtml.isBlank() || songName.isBlank()) return false
        val writer = saveAppleNative ?: return false
        return runCatching { writer(songId, rawAppleTtml, songName) }.getOrDefault(false)
    }

    /** Replaces the song's custom-lyrics document; null on success, else the reason. */
    fun replaceLyrics(songId: Long, ttml: String, source: String, displayName: String): String? {
        val writer = replace ?: return "切换自定义歌词需要 app 侧索引写入口（未注册）"
        return runCatching { writer(songId, ttml, source, displayName) }
            .getOrElse { error -> (error.javaClass.simpleName + ": " + error.message) }
    }

    /** Removes the song's custom-lyrics document; false when no writer is registered. */
    fun removeLyrics(songId: Long): Boolean {
        val writer = remove ?: return false
        return runCatching { writer(songId) }.getOrDefault(false)
    }

    /** Retitles the song's index entry in place; false when nothing could be renamed. */
    fun renameLyrics(songId: Long, displayName: String): Boolean {
        if (displayName.isBlank()) return false
        val writer = rename ?: return false
        return runCatching { writer(songId, displayName) }.getOrDefault(false)
    }
}
