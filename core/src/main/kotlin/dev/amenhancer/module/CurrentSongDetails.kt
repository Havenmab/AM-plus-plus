package dev.amenhancer.module

/** Verified current-item metadata shared by target hooks and embedded settings. */
data class CurrentSongDetails(
    val appleMusicId: Long,
    val title: String? = null,
    val artist: String? = null,
    /**
     * Track length in milliseconds when the current item exposes it. Zero means
     * unverified, and the online match policy then scores title/artist only.
     */
    val durationMs: Long = 0L,
)
