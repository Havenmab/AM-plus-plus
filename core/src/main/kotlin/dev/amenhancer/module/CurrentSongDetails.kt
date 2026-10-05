package dev.amenhancer.module

/** Verified current-item metadata shared by target hooks and embedded settings. */
data class CurrentSongDetails(
    val appleMusicId: Long,
    val title: String? = null,
    val artist: String? = null,
    /**
     * Track length in milliseconds when the current item exposes it. Zero means
     * unverified; the online match policy then treats the duration channel as
     * neutral so an unavailable accessor can never be the sole reason a
     * title/artist match misses the pass floor.
     */
    val durationMs: Long = 0L,
)
