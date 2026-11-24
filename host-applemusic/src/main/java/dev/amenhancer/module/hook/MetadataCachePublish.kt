package dev.amenhancer.module.hook

import dev.amenhancer.module.config.RegionMetadataConfiguration
import io.github.proify.lyricon.amprovider.xposed.MediaMetadataCache

/**
 * HLE's "no work, no cache" rule for [MediaMetadataCache].
 *
 * HLE installs its runtime unconditionally and lets each callback yield, so nothing is ever
 * cached while every control is off.  AM++ used to get that for free by not installing the
 * runtime at all; now that the install is unconditional, the cache — memory profile included —
 * is what has to be gated.
 *
 * It matters beyond this subsystem: [dev.amenhancer.module.hook.assembleAppleMusicTarget] feeds
 * `AppleMusicAssembly`'s 隐藏普通话拼音 genre probe from this cache, and that probe must see
 * exactly what it saw before the runtime was installed (nothing) while every control is off.
 */
internal object MetadataCachePublish {
    /** Whether the runtime currently has anything to resolve, i.e. may cache metadata. */
    fun isActive(configuration: RegionMetadataConfiguration): Boolean =
        configuration.plan.featureActive

    /**
     * Publishes the region's in-memory profile and answers whether metadata may be cached.
     *
     * False means "publish nothing": the caller must not write, must not warm the persistent
     * stores, and must leave the profile where it is.
     */
    fun activate(configuration: RegionMetadataConfiguration): Boolean {
        if (!isActive(configuration)) return false
        MediaMetadataCache.setProfile(configuration.region.cacheNamespace)
        return true
    }
}
