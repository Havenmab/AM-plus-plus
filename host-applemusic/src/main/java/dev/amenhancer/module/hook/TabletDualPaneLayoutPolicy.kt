package dev.amenhancer.module.hook

/** Shared presentation for the Activity and Fragment tablet song columns. */
internal object TabletDualPaneLayoutPolicy {
    const val SONG_WIDTH_FRACTION = 0.45f
    fun songColumnWidth(playerWidth: Int): Int = (playerWidth * SONG_WIDTH_FRACTION).toInt()

    const val CONTENT_SIDE_INSET_DP = 55
    // Lyrics begin 16dp (host) + 20dp (row) + 12dp (text) after the divider.
    private const val LYRICS_TEXT_START_INSET_DP = 48
    const val CONTENT_END_INSET_DP = CONTENT_SIDE_INSET_DP - LYRICS_TEXT_START_INSET_DP
    // Native metadata uses a 16dp guideline plus a 16dp margin; progress uses 32dp.
    private const val NATIVE_CONTENT_SIDE_INSET_DP = 32
    const val SONG_HOST_SIDE_MARGIN_DP = CONTENT_SIDE_INSET_DP - NATIVE_CONTENT_SIDE_INSET_DP
    const val SONG_HOST_END_MARGIN_DP = 0
}
