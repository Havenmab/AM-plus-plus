package dev.amenhancer.module.hook

/** Native current-item rule, without the lyric button's forced-open preference override. */
internal class FragmentTabletLyricsAvailability(
    private val currentItem: () -> Any?,
    private val supportsLyrics: (Any) -> Boolean,
    private val online: () -> Boolean,
    private val offlineLyrics: (Any) -> Boolean,
) {
    fun available(): Boolean? = runCatching {
        val item = currentItem() ?: return@runCatching null
        supportsLyrics(item) && (online() || offlineLyrics(item))
    }.getOrNull()

}
