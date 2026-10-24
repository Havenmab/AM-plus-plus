package dev.amenhancer.module.hook

/**
 * Capability seam of the full-page self-drawn lyrics player (LyricsBlossom-style).
 *
 * The feature switch and the five settings live in `:app`; everything host specific — symbol
 * resolution, hook installation, the mount decision and the data port — stays behind this
 * seam in `:host-applemusic`. A host that cannot provide the contract returns
 * [TargetCapabilityInstall.Unsupported] or [TargetCapabilityInstall.Degraded] and the user
 * keeps Apple Music's own lyrics page; no overlay is ever mounted from a failed install
 * (spec D7: never show a blank lyrics page).
 */
fun interface LyricsPlayerTarget {
    fun install(): TargetCapabilityInstall
}

/** Task-spec spelling; prefer [LyricsPlayerTarget] in new code. */
@Deprecated("Use LyricsPlayerTarget", ReplaceWith("LyricsPlayerTarget"))
typealias LyricPlayerTarget = LyricsPlayerTarget
