package dev.amenhancer.module.lyrics.player

/**
 * Time text for the transport row ("1:23", "1:02:03"). Pure and locale-free: the page
 * renders it with its own typography, so digits must not go through a formatter that could
 * localise the separators.
 */
object LyricsPlayerFormat {
    fun position(positionMs: Long): String {
        val totalSeconds = positionMs.coerceAtLeast(0L) / 1000L
        val seconds = totalSeconds % 60L
        val minutes = (totalSeconds / 60L) % 60L
        val hours = totalSeconds / 3600L
        return if (hours > 0L) {
            hours.toString() + ":" + twoDigits(minutes) + ":" + twoDigits(seconds)
        } else {
            minutes.toString() + ":" + twoDigits(seconds)
        }
    }

    private fun twoDigits(value: Long): String = if (value < 10L) "0$value" else value.toString()
}
