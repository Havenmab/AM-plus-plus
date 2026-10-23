package dev.amenhancer.module.lyrics.player

/**
 * Frozen contract between the host bridge (`:host-applemusic`) and the player page
 * (`:lyrics-player`). Pure JVM: no Android types may appear here.
 *
 * The host owns *what* to show (document + transport position + geometry); the player
 * owns *how* (layout, motion, painting). Both sides are written against this file, so
 * changing it requires telling the Lead first.
 */

/** One timed word the player can paint. Times are milliseconds inside the current track. */
data class PlayerWord(
    val startMs: Long,
    val endMs: Long,
    val text: String,
) {
    /** Playable duration; never negative. */
    val durationMs: Long get() = (endMs - startMs).coerceAtLeast(0L)

    /** Paint progress of this word at [positionMs]: 0 before it starts, 1 once it is sung. */
    fun progressAt(positionMs: Long): Float {
        if (positionMs <= startMs) return 0f
        if (durationMs == 0L) return 1f
        return ((positionMs - startMs).toFloat() / durationMs.toFloat()).coerceIn(0f, 1f)
    }
}

/**
 * One lyric line. [text] is the line as the host would render it; [words] is the timed
 * breakdown (a line-only source is allowed to carry a single word spanning the line).
 * [backgroundWords] are harmony/backing vocals, [translation] is the secondary line.
 */
data class PlayerLine(
    val startMs: Long,
    val endMs: Long,
    val text: String,
    val words: List<PlayerWord>,
    val backgroundWords: List<PlayerWord> = emptyList(),
    val translation: String = "",
) {
    val durationMs: Long get() = (endMs - startMs).coerceAtLeast(0L)
}

/** A whole song's lyrics as handed to the player when they resolve. */
data class PlayerDocument(
    val lines: List<PlayerLine>,
    /** Identity of the song/document (host-provided); the page rebuilds text layouts when it changes. */
    val identity: String,
)

/** Track metadata for the page chrome. Artwork stays Android-side and lives outside core. */
data class PlayerTrackInfo(
    val identity: String,
    val title: String,
    val artist: String,
    val album: String = "",
    val durationMs: Long = 0L,
)

/**
 * Host view geometry and typography hints. All values are pixels in the overlay's own
 * coordinate space. Values the host cannot measure stay at their defaults.
 */
data class PlayerGeometry(
    val leftPx: Float,
    val topPx: Float,
    val widthPx: Float,
    val heightPx: Float,
    /** Host text size for one lyric line; 0 means "player picks its own default". */
    val textSizePx: Float = 0f,
    /** Vertical distance between two lyric lines; 0 means "player derives it from text size". */
    val lineSpacingPx: Float = 0f,
    /** Horizontal inset the host uses for lyric rows. */
    val horizontalInsetPx: Float = 0f,
    val rtl: Boolean = false,
)

/** Pure read side every host implementation provides; polled once per display frame. */
interface PlayerHostPort {
    /** Current lyrics, or null while the host has none (loading, instrumental, failed). */
    fun document(): PlayerDocument?

    /** Track position in milliseconds, or null when the host cannot report it yet. */
    fun positionMs(): Long?

    /** True while the track is playing; the page extrapolates position between host samples. */
    fun isPlaying(): Boolean

    /** Host geometry for this frame, or null while the host view is not laid out. */
    fun geometry(): PlayerGeometry?

    /** Ask the host to seek; false when the host refused or cannot seek. */
    fun seekTo(positionMs: Long): Boolean
}
