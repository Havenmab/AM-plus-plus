package dev.amenhancer.module.hook
import android.app.Application
import dev.amenhancer.module.config.TargetConfigClient
import dev.amenhancer.module.lyrics.online.OnlineLyricSourcePolicy
import dev.amenhancer.module.model.CustomLyricsEntry
import io.github.proify.lyricon.amprovider.xposed.MediaMetadataCache

internal fun assembleAppleMusicTarget(
    config: TargetConfigClient,
    application: Application,
    classLoader: ClassLoader,
    lyricsTypefaceSession: LyricsTypefaceResourceBinding,
    currentSong: CurrentSongIdentityCache = CurrentSongIdentityCache(),
): TargetAdaptation {
    val settings = config.settings()
    // One capture registry serves the whole target: the composite reads the
    // displayed document back from it to enforce the timed-Apple-lyrics rule,
    // and the custom-lyrics target records into the same instance.
    val timingObservations = TtmlTimingObservationRegistry()
    val automatic = if (settings.customLyricsEnabled && settings.automaticLyricsEnabled) {
        val suppressed = runCatching { config.customLyricsManifest().entries
            .filterNot { it.enabled }.mapTo(mutableSetOf(), CustomLyricsEntry::appleMusicId)
        }.getOrDefault(emptySet())
        createAutoLyricsRuntime(
            application = application,
            suppressedIds = suppressed,
            onlineLyricsSupplementEnabled = settings.onlineLyricsSupplementEnabled,
            onlineLyricsTranslationEnabled = settings.onlineLyricsTranslationEnabled,
            hideMandarinPronunciation = settings.onlineLyricsHideMandarinPinyinEnabled,
            // HLE's shouldHideMandarinPronunciation resolves the genre from the
            // metadata cache; the document lane cannot reach that cache from
            // `core`, so the host resolves it and passes it in. Fails open: an
            // unresolved genre hides nothing.
            genreFor = { appleMusicId ->
                runCatching {
                    MediaMetadataCache.getMetadataById(appleMusicId.toString())?.genre
                }.getOrNull()
            },
            onlineLyricsSelection = OnlineLyricSourcePolicy.resolve(settings),
            currentTrack = { currentSong.current()?.details },
            logger = ModernXposedRuntime::log,
            displayedTtml = timingObservations::rawTtmlOfAppleMusicId,
        )
    } else null
    return AppleMusicHostFactory.appleMusic(
        config,
        application,
        classLoader,
        lyricsTypefaceSession,
        currentSong,
        automatic,
        timingObservations,
    )
}
