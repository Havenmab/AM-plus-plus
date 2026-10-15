package dev.amenhancer.module.lyrics.online

import dev.amenhancer.module.model.ModuleSettings
import dev.amenhancer.module.model.OnlineLyricSources

/**
 * The online-supplement chain resolved from the settings: the ordered source ids
 * to try plus the strategy used to pick a hit inside one source.
 */
data class OnlineLyricSelection(
    val sources: List<String>,
    val mode: LyricSelectionMode,
)

/**
 * Pure selection policy for the online lyric chain, ported from
 * HyperLyricsEnhanced's `OnlineTranslationSourcePreferences`.
 *
 * It is the single place where settings become a chain and where the
 * global-best flag becomes a [LyricSelectionMode]; the host only maps the
 * returned ids onto provider objects, and the settings UI never sees a
 * provider.
 */
object OnlineLyricSourcePolicy {

    /**
     * Resolves the ordered, enabled chain.
     *
     * [available] is the set of ids the caller can actually construct, so a
     * caller with fewer providers still gets a usable chain.
     *
     * Never-empty rule: when every source is disabled the chain falls back to
     * the built-in default order's enabled-by-default set, i.e. all of
     * [OnlineLyricSources.DEFAULT_ORDER] (all four default on) intersected with
     * [available], so at least one source always remains. HLE's
     * `resolveEnabledSources` implements the same guarantee by keeping only
     * `order.take(1)`; the documented rule here keeps the whole default set,
     * matching this setting's all-on defaults. Only a caller that offers no
     * source at all gets an empty chain.
     */
    fun resolve(
        settings: ModuleSettings,
        available: List<String> = OnlineLyricSources.ALL,
    ): OnlineLyricSelection {
        val order = order(settings)
        val enabled = order.filter { it in available && settings.onlineLyricsSourceEnabled(it) }
        val sources = enabled.ifEmpty {
            OnlineLyricSources.DEFAULT_ORDER.filter(available::contains)
        }
        return OnlineLyricSelection(
            sources = sources,
            mode = selectionMode(settings.onlineLyricsGlobalBestEnabled),
        )
    }

    /**
     * The order the chain walks: the built-in default when the automatic flag is
     * on, otherwise the user's stored, normalized order.
     */
    fun order(settings: ModuleSettings): List<String> =
        if (settings.onlineLyricsAutomaticOrderEnabled) {
            OnlineLyricSources.DEFAULT_ORDER
        } else {
            OnlineLyricSources.normalizeOrder(settings.onlineLyricsSourceOrder)
        }

    /**
     * The one mapping from the global-best setting to the match policy's
     * selection mode: off keeps today's first-passing candidate, on scores
     * every candidate and takes the highest.
     */
    fun selectionMode(globalBest: Boolean): LyricSelectionMode =
        if (globalBest) LyricSelectionMode.GLOBAL_BEST else LyricSelectionMode.FIRST_PASSING
}
