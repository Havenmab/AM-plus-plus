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
    /**
     * The unmodified value returned by the resolved duration accessor, before the
     * seam normalised it into [durationMs]. Kept only so the online-search query
     * line can report the raw value next to the milliseconds it produced; zero
     * when no accessor resolved.
     */
    val durationRaw: Long = 0L,
    /**
     * The unit the accessor reports the value in (`"seconds"` or
     * `"milliseconds"`), or null when no accessor resolved or it returned no
     * length. The 7.0 `getPlaybackDuration()` accessor reports seconds, which is
     * the mismatch this field makes visible in one device log line.
     */
    val durationUnit: String? = null,
)
