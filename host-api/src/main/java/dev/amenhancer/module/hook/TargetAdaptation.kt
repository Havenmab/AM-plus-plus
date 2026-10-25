package dev.amenhancer.module.hook

/**
 * The complete target-specific seam used by feature hooks.
 *
 * Each feature receives only its own capability, while symbol discovery and
 * reflective hook installation remain private to the Apple Music adapters.
 */
data class TargetAdaptation(
    val identity: String,
    val build: TargetBuild = TargetBuild.UNKNOWN,
    val currentSong: CurrentSongIdentityCache = CurrentSongIdentityCache(),
    val dualPane: DualPaneTarget,
    val editorialVideo: EditorialVideoTarget,
    val bidirectionalLyricBlur: BidirectionalLyricBlurTarget,
    val cjkKaraokeAnimation: CjkKaraokeAnimationTarget = CjkKaraokeAnimationTarget {
        TargetCapabilityInstall.Degraded("CJK karaoke animation target was not configured")
    },
    val lyricsTypeface: LyricsTypefaceTarget = LyricsTypefaceTarget {
        TargetCapabilityInstall.Degraded("Lyrics typeface target was not configured")
    },
    val customLyrics: CustomLyricsTarget = CustomLyricsTarget {
        TargetCapabilityInstall.Degraded("Custom lyrics target was not configured")
    },
    val nativeLyrics: NativeLyricsTarget = object : NativeLyricsTarget {
        override fun install(): TargetCapabilityInstall =
            TargetCapabilityInstall.Degraded("Native lyrics target was not configured")
    },
    val currentSongIdentity: CurrentSongIdentityTarget = CurrentSongIdentityTarget {
        TargetCapabilityInstall.Degraded("Current song identity target was not configured")
    },
    /** Retained only for compatibility; the former global target is never installed. */
    val catalogLanguage: CatalogLanguageTarget = CatalogLanguageTarget {
        TargetCapabilityInstall.Degraded("Catalog language target is intentionally disabled")
    },
    val hleMetadata: HleMetadataTarget = HleMetadataTarget {
        TargetCapabilityInstall.Degraded("HLE metadata target was not configured")
    },
    val cellularDataEntry: CellularDataEntryTarget = CellularDataEntryTarget {
        TargetCapabilityInstall.Degraded("Cellular data entry target was not configured")
    },
    val playerRecovery: PlayerRecoveryTarget = PlayerRecoveryTarget {
        TargetCapabilityInstall.Unsupported("Native player recovery is not available for this host")
    },
    val lyricWrapSweep: LyricWrapSweepTarget = LyricWrapSweepTarget {
        TargetCapabilityInstall.Unsupported("Wrapped lyric sweep has no exact host contract")
    },
    val lyricBackgroundHighlight: LyricBackgroundHighlightTarget = LyricBackgroundHighlightTarget {
        TargetCapabilityInstall.Unsupported("Native lyric background highlighting has no exact host contract")
    },
    val startupPagePrewarm: StartupPagePrewarmTarget = StartupPagePrewarmTarget {
        TargetCapabilityInstall.Unsupported("Startup page prewarming has no exact host contract")
    },
    /**
     * The 「歌词源…」 entry inside Apple Music's own lyrics "more" popup plus the
     * controller behind the sheet. Independent of every other lyrics capability:
     * a missing menu contract only degrades this entry, never the native rows.
     */
    val lyricsSource: LyricsSourceTarget = LyricsSourceTarget {
        TargetCapabilityInstall.Unsupported("Lyrics source sheet has no exact host contract")
    },
) {
}

fun interface DualPaneTarget {
    fun install(): TargetCapabilityInstall
}

fun interface EditorialVideoTarget {
    fun install(): TargetCapabilityInstall
}

fun interface BidirectionalLyricBlurTarget {
    fun install(): TargetCapabilityInstall
}

fun interface CjkKaraokeAnimationTarget {
    fun install(): TargetCapabilityInstall
}

fun interface LyricWrapSweepTarget {
    fun install(): TargetCapabilityInstall
}

fun interface LyricBackgroundHighlightTarget {
    fun install(): TargetCapabilityInstall
}

fun interface LyricsTypefaceTarget {
    fun install(): TargetCapabilityInstall
}

fun interface CustomLyricsTarget {
    fun install(): TargetCapabilityInstall
}

interface NativeLyricsTarget {
    fun install(): TargetCapabilityInstall
    fun onLyricsPointer(pointer: Any?) = Unit
    fun onLyricsPresentation(fragment: Any?, pointer: Any?) = Unit
    fun onCustomOverlayUpdated(songId: Long) = Unit
    fun setModuleSupplementSongResolver(resolver: (Long) -> Boolean) = Unit
}

fun interface CurrentSongIdentityTarget {
    fun install(): TargetCapabilityInstall
}

fun interface HleMetadataTarget {
    fun install(): TargetCapabilityInstall
}

fun interface CellularDataEntryTarget {
    fun install(): TargetCapabilityInstall
}

fun interface PlayerRecoveryTarget {
    fun install(): TargetCapabilityInstall
}

fun interface StartupPagePrewarmTarget {
    fun install(): TargetCapabilityInstall
}

sealed interface TargetCapabilityInstall {
    val message: String

    data class Active(override val message: String) : TargetCapabilityInstall {
        init {
            require(message.isNotBlank()) { "Target capability diagnostic must not be blank" }
        }
    }

    data class Degraded(override val message: String) : TargetCapabilityInstall {
        init {
            require(message.isNotBlank()) { "Target capability diagnostic must not be blank" }
        }
    }

    data class Unsupported(override val message: String) : TargetCapabilityInstall {
        init {
            require(message.isNotBlank()) { "Target capability diagnostic must not be blank" }
        }
    }
}
