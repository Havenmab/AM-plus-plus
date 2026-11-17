package dev.amenhancer.module.config

/**
 * The four controls HLE's metadata runtime derives its behaviour from, captured as one
 * value so a host can detect "the metadata configuration changed" without decoding the
 * whole [dev.amenhancer.module.model.ModuleSettings] on every request.
 *
 * HLE reads these controls at call time: `configuredContentUiLanguage()`,
 * `shouldOverrideAccountLanguage(selection)` and `isRestoreCjkOriginalMetadataEnabled()`
 * each hit the preference object again.  AM++ has no cross-process preference listener, so
 * the host polls this snapshot on its request seams instead and re-applies the projection
 * when the value differs.
 *
 * [plan] is the derived projection ([RegionTitleRequestPolicy]); the raw switch values stay
 * on the snapshot because HLE's `shouldOverrideAccountLanguage` needs the switch itself,
 * not the already-and-ed selection.
 */
data class RegionMetadataConfiguration(
    /** The 将Apple Music改成其他地区 picker only; it never gates or implies the switches. */
    val region: RegionSelection = RegionSelection.NONE,
    /** HLE's 歌曲信息替换至设定地区语言 switch, before the region gate is applied. */
    val overrideAccountLanguage: Boolean = false,
    /** HLE's 替换中日韩歌曲信息为原地区原名 switch. */
    val restoreCjkOriginalMetadata: Boolean = false,
    /** Persist region/original metadata lookups in SQLite across cold starts. */
    val localizedMetadataCache: Boolean = true,
) {
    /** The three controls projected onto the request seams; see [RegionTitleRequestPolicy]. */
    val plan: RegionTitleRequestPlan
        get() = RegionTitleRequestPolicy.plan(
            region = region,
            overrideAccountLanguage = overrideAccountLanguage,
            restoreCjkOriginalMetadata = restoreCjkOriginalMetadata,
        )

    /**
     * HLE's `AppleOrchestratorCatalogLanguageAssembly.shouldOverrideAccountLanguage`:
     * the selection the caller is looking at decides, and 不开启 never overrides — only then
     * does the switch matter.
     */
    fun shouldOverrideAccountLanguage(selection: Int): Boolean =
        selection != RegionSelection.NONE.contentUiLanguageSelection && overrideAccountLanguage

    /** HLE's `isRestoreCjkOriginalMetadataEnabled()`: true means "may probe the original name". */
    val isRestoreOriginalMetadataEnabled: Boolean
        get() = restoreCjkOriginalMetadata
}

/** One action the host owes the user after [RegionMetadataConfiguration] changed. */
enum class RegionMetadataRefreshAction {
    /**
     * Re-run the region engine: MediaApi storefront, request language and the persistent
     * cache switch.  HLE does this through `applyConfiguredContentUiLanguage`; without it the
     * storefront keeps pointing at the previous region until Apple Music restarts.
     */
    APPLY_REGION,

    /**
     * HLE's `AppleMetadataConfigurationDispatcher.dispatch()`: clear every surface's memoised
     * state, restore the models we overwrote and re-resolve the current item so the new
     * display language is visible immediately instead of on the next page load.
     */
    DISPATCH_CONFIGURATION_CHANGE,
}

/**
 * Pure decision of what a configuration change costs.  Kept separate from the host so the
 * whole truth table is verifiable on the JVM.
 *
 * Mirrors HLE's listener (`AppleOrchestratorContentUiLanguage.kt`):
 * - the content-UI language and the persistent-cache key re-run `applyConfiguredContentUiLanguage`;
 * - the content-UI language, the override switch and the restore switch all
 *   `metadataConfigurationDispatcher.dispatch()`.
 *
 * The first observation is a seed, not a change: the caller applied that configuration while
 * installing, so re-dispatching there would only throw away a freshly resolved surface.
 */
object RegionMetadataRefreshPolicy {
    fun actionsFor(
        previous: RegionMetadataConfiguration?,
        next: RegionMetadataConfiguration,
    ): List<RegionMetadataRefreshAction> {
        if (previous == null || previous == next) return emptyList()
        return buildList {
            // HLE: KEY_HOOK_APPLE_MUSIC_CONTENT_UI_LANGUAGE / ..._LOCALIZED_METADATA_CACHE
            // -> applyConfiguredContentUiLanguage(changed).
            if (previous.region != next.region ||
                previous.localizedMetadataCache != next.localizedMetadataCache
            ) {
                add(RegionMetadataRefreshAction.APPLY_REGION)
            }
            // HLE: the two switches and the language selection all dispatch the
            // configuration change; the persistent-cache key alone does not.
            if (previous.region != next.region ||
                previous.overrideAccountLanguage != next.overrideAccountLanguage ||
                previous.restoreCjkOriginalMetadata != next.restoreCjkOriginalMetadata
            ) {
                add(RegionMetadataRefreshAction.DISPATCH_CONFIGURATION_CHANGE)
            }
        }
    }
}

/** A detected change, carrying the actions it owes so the host never re-derives them. */
data class RegionMetadataConfigurationChange(
    val previous: RegionMetadataConfiguration,
    val current: RegionMetadataConfiguration,
    val actions: List<RegionMetadataRefreshAction>,
) {
    val isEmpty: Boolean get() = actions.isEmpty()
}

/**
 * Short-TTL snapshot of a [RegionMetadataConfiguration] source.
 *
 * The host reads the plan on hot paths (every MediaApi request, every surface resolution) and
 * the source is a cross-process preference map, so the value is cached for [ttlMillis].  A
 * request seam calls [observe] to learn about a change; anything that only needs the current
 * value calls [current].  Both are cheapest-case allocation-free and never block on the source
 * twice within one TTL window.
 *
 * The clock is a parameter so the TTL behaviour stays testable without an Android runtime.
 */
class RegionMetadataConfigurationWatcher(
    private val read: () -> RegionMetadataConfiguration,
    private val clock: () -> Long,
    private val ttlMillis: Long = DEFAULT_TTL_MILLIS,
) {
    private val lock = Any()

    /** The value reads are answered from; refreshed at most once per TTL window. */
    @Volatile
    private var value: RegionMetadataConfiguration? = null

    /**
     * The value the last reported change was applied against.
     *
     * Kept apart from [value] on purpose: a plain [current] read must never swallow a pending
     * notification, otherwise a change that lands while the host is refreshing would be lost.
     */
    @Volatile
    private var reported: RegionMetadataConfiguration? = null

    @Volatile
    private var readAtMillis: Long = Long.MIN_VALUE

    /**
     * Bumped by every [invalidate], so a refresh that overlapped one is not mistaken for the
     * read that invalidation asked for.
     */
    @Volatile
    private var generation: Long = 0L

    /** The current configuration, re-read from the source at most once per TTL window. */
    fun current(): RegionMetadataConfiguration {
        refreshIfStale()
        return value ?: RegionMetadataConfiguration()
    }

    /**
     * Samples the source and reports a change when the value differs from the last one the
     * caller accepted.
     *
     * [onChanged] runs on the calling thread and returns whether the change was taken over;
     * a caller that must not block posts it itself and returns true, a caller that is already
     * busy returns false so the change is offered again on the next seam instead of being
     * dropped.  The first sample only seeds the baseline, so the configuration the runtime
     * installed with is never re-dispatched.
     */
    fun observe(
        onChanged: (RegionMetadataConfigurationChange) -> Boolean,
    ): RegionMetadataConfiguration {
        refreshIfStale()
        val next = value ?: return RegionMetadataConfiguration()
        val previous = reported
        if (previous == null) {
            reported = next
            return next
        }
        if (previous == next) return next
        val actions = RegionMetadataRefreshPolicy.actionsFor(previous, next)
        if (actions.isEmpty()) {
            reported = next
            return next
        }
        val change = RegionMetadataConfigurationChange(previous, next, actions)
        val accepted = runCatching { onChanged(change) }.getOrDefault(false)
        if (accepted) reported = next
        return next
    }

    /** Marks [current] as applied, so the next [observe] only reports a real change. */
    fun seed() {
        reported = current()
    }

    /**
     * Drops the cached value so the next read hits the source, e.g. right after install.
     *
     * Runs under the same lock as the refresh: resetting [readAtMillis] outside it could be
     * overwritten by a refresh that was already in flight, which silently cancelled the
     * invalidation.
     */
    fun invalidate() {
        synchronized(lock) {
            generation += 1
            readAtMillis = Long.MIN_VALUE
        }
    }

    private fun refreshIfStale() {
        val observedGeneration = generation
        if (value != null && isFresh(clock())) return
        synchronized(lock) {
            // A refresh that started before an invalidate() must not serve it: without the
            // generation check a concurrent timestamp write would look fresh here.
            if (observedGeneration == generation && value != null && isFresh(clock())) return
            // A failed read must not look like a configuration change: keep the last value.
            val loaded = runCatching(read).getOrElse { value ?: RegionMetadataConfiguration() }
            value = loaded
            // An invalidate() that landed while we were reading still owes a fresh read.
            readAtMillis = if (observedGeneration == generation) clock() else Long.MIN_VALUE
        }
    }

    private fun isFresh(now: Long): Boolean {
        val readAt = readAtMillis
        if (readAt == Long.MIN_VALUE) return false
        return now - readAt in 0 until ttlMillis
    }

    companion object {
        /**
         * 750 ms: long enough that a burst of requests samples the preference map once, short
         * enough that flipping a switch in the settings page is visible within a frame or two.
         */
        const val DEFAULT_TTL_MILLIS = 750L
    }
}
