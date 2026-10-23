package dev.amenhancer.module.hook

import dev.amenhancer.module.config.TargetConfigClient

/** Early resource binding shared with the later typeface capability; no host reflection escapes. */
interface LyricsTypefaceResourceBinding {
    fun registerResources(config: TargetConfigClient)
}

/**
 * Early binding for the player lyric typography (font size and line spacing scales).
 *
 * Registered independently of the lyrics typeface capability and of the online-lyrics features:
 * at 100% it must perform no change at all, so it is always safe to register.
 */
interface LyricsTypographyResourceBinding {
    fun registerResources(config: TargetConfigClient)
}

/** Logical observation lifetime; closing detaches observers, not framework method hooks. */
fun interface HostSubscription : AutoCloseable {
    override fun close()
}

/** Native lyric payload identity, intentionally exposing no host object or member. */
interface NativeLyricsHandle
