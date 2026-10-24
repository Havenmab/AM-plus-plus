package dev.amenhancer.lyricsplayer

import android.graphics.Bitmap
import dev.amenhancer.module.lyrics.player.PlayerHostPort
import dev.amenhancer.module.lyrics.player.PlayerTrackInfo

/**
 * Android side of the player page contract.
 *
 * The host bridge (`:host-applemusic`) implements this: the five members inherited from
 * [PlayerHostPort] are the read-only per-frame source, the rest drive the page chrome and
 * the transport controls. Every method is called on the main thread.
 *
 * Return values report whether the host accepted the request; `false` means the page
 * dims/disables the control instead of pretending it worked (see spec §7.4).
 */
interface LyricsPlayerPort : PlayerHostPort {
    /** Track metadata for the page title, or null while the host has none. */
    fun track(): PlayerTrackInfo?

    /** Current artwork, or null when the host has no bitmap (the page falls back to a dark gradient). */
    fun artwork(): Bitmap?

    /** Toggle playback; true when the host handled it. */
    fun playPause(): Boolean

    /** Skip to the next track; true when the host handled it. */
    fun skipNext(): Boolean

    /** Skip to the previous track; true when the host handled it. */
    fun skipPrevious(): Boolean

    /** Leave the lyrics page (host back); false when the host cannot leave. */
    fun closePage(): Boolean

    /** Optional host "more" menu; false hides the overflow button. */
    fun more(): Boolean = false

    /**
     * The user-imported lyric font (module settings "lyrics font" slot, lyrics_font_*), or
     * null when nothing is imported / it cannot be opened. The host opens it through
     * TargetConfigClient.openFileDescriptor and returns the Typeface; the page derives the
     * Regular/Medium/Semibold/Bold roles from that single base face.
     */
    fun importedTypeface(): android.graphics.Typeface? = null

    /**
     * Live user settings (settings spec §8). The page polls this about twice per second and
     * clamps every value again, so a host may map the raw settings without validating them.
     * The default keeps Apple's own timing.
     */
    fun preferences(): LyricsPlayerConfig = LyricsPlayerConfig()
}

/** Safe-area insets of the overlay, in pixels. */
data class LyricsPlayerInsets(
    val leftPx: Int,
    val topPx: Int,
    val rightPx: Int,
    val bottomPx: Int,
) {
    companion object {
        val ZERO = LyricsPlayerInsets(0, 0, 0, 0)
    }
}

/** Receives the overlay size once it is laid out; pixels are in the overlay's own space. */
fun interface LyricsPlayerViewportListener {
    fun onViewportChanged(widthPx: Int, heightPx: Int, density: Float)
}
