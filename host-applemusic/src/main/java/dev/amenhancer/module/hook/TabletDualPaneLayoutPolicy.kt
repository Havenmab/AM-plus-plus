package dev.amenhancer.module.hook

/** Shared presentation for the Activity and Fragment tablet song columns. */
internal object TabletDualPaneLayoutPolicy {
    const val SONG_WIDTH_FRACTION = 0.45f
    fun songColumnWidth(playerWidth: Int): Int = (playerWidth * SONG_WIDTH_FRACTION).toInt()

    const val CONTENT_SIDE_INSET_DP = 50
    // The symmetric player column ends at the native lyric text, not the divider.
    // Keep the lyrics viewport and its drawing/word-width insets unchanged.
    const val LYRICS_TEXT_START_INSET_DP = 16 + 20 + 12
    // Native metadata uses a 16dp guideline plus a 16dp margin; progress uses 32dp.
    private const val NATIVE_CONTENT_SIDE_INSET_DP = 32
    const val SONG_HOST_SIDE_MARGIN_DP = CONTENT_SIDE_INSET_DP - NATIVE_CONTENT_SIDE_INSET_DP

    fun songHostWidth(playerWidth: Int, hostSideMargin: Int, lyricsTextStartInset: Int): Int =
        (songColumnWidth(playerWidth) + lyricsTextStartInset - 2 * hostSideMargin).coerceAtLeast(1)
}
