package dev.amenhancer.module.hook

import android.app.Activity

/**
 * Host playback surface required by the iPad-style tablet chrome.
 *
 * The chrome never creates a player: the implementation captures the host's live
 * `MediaPlayerController` (the `MediaPlaybackService` owns it) and forwards to it. Every capability
 * is reported separately because they are resolved separately — the chrome renders a control only
 * when its capability is available and reports `DEGRADED` for the rest, never a silent no-op.
 *
 * All methods are safe to call before [available] is true; they then do nothing.
 */
internal interface TabletChromeCommands {
    /** True once the live controller was captured and the command seams resolved. */
    val available: Boolean

    val shuffleAvailable: Boolean
    val repeatAvailable: Boolean
    val moreAvailable: Boolean

    fun isPlaying(): Boolean

    fun shuffleEnabled(): Boolean

    /** Host `PlaybackRepeatMode`: `0` off, `1` one, `2` all. */
    fun repeatMode(): Int

    fun setShuffleEnabled(enabled: Boolean)

    /** Cycles off -> all -> one -> off, matching the reference three-state button. */
    fun cycleRepeatMode()

    fun skipToPrevious()

    fun skipToNext()

    fun play()

    fun pause()

    /** Current song title from the host controller, or null while unavailable. */
    fun currentTitle(): String?

    /** Current artist name from the host controller, or null while unavailable. */
    fun currentArtist(): String?

    /** Expands the collapsed player into the full player. */
    fun expandPlayer(activity: Activity)

    /** Opens the lyrics pane. */
    fun openLyrics(activity: Activity)

    /** Opens the host's own queue pane. */
    fun openQueue(activity: Activity)

    fun openSongMenu(activity: Activity)

    /**
     * Observes host state changes (play state, shuffle, repeat, current item). The returned handle
     * unregisters on [AutoCloseable.close]; a session drops it in its own `close()`.
     */
    fun addListener(listener: () -> Unit): AutoCloseable
}
