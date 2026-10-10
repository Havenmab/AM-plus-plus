package dev.amenhancer.module.hook

import dev.amenhancer.module.lyrics.online.NativeLyricOverlayStore
import io.github.proify.lyricon.amprovider.xposed.AppleMusicHookPoint
import io.github.proify.lyricon.amprovider.xposed.AppleMusicHookProfiles
import io.github.proify.lyricon.amprovider.xposed.AppleMusicHookResolver
import java.lang.reflect.Method

/**
 * Apple-native lyric lane capability. This is deliberately separate from the
 * custom lyric replacement capability: HLE installs pronunciation/translation
 * model hooks even when no online source or custom document is enabled.
 */
internal class AppleMusicNativeLyricsTarget(
    private val resolver: AppleMusicHookResolver,
    private val overlay: NativeLyricOverlayStore,
    private val currentSong: CurrentSongIdentityCache,
    private val hideMandarinPinyin: Boolean,
    private val genreFor: (Long) -> String?,
    private val presentationMethod: Method? = null,
) : NativeLyricsTarget {
    private val registration = HookRegistrationScope()
    private var installed: TargetCapabilityInstall? = null
    private var delivery: AppleNativeLyricModelHooks? = null
    private var supplementSongResolver: (Long) -> Boolean = { false }
    @Volatile
    private var lastPublishedSongId: Long? = currentSong.current()?.details?.appleMusicId

    @Synchronized
    override fun install(): TargetCapabilityInstall {
        installed?.let { return it }
        val loadTarget = io.github.proify.lyricon.amprovider.xposed.AppleMusicHookProfiles
            .exactTargets(resolver.version, AppleMusicHookPoint.LYRICS_VIEW_MODEL_LOAD)
            .firstOrNull()
        if (loadTarget == null) {
            return TargetCapabilityInstall.Degraded(
                "Native lyrics profile is unavailable for ${resolver.version.displayName}",
            ).also { installed = it }
        }
        val delivery = AppleNativeLyricModelHooks(
            resolver = resolver,
            overlay = overlay,
            enabled = true,
            hideMandarinPinyin = hideMandarinPinyin,
            genreFor = genreFor,
            scope = registration,
            presentationMethod = presentationMethod,
            isModuleSupplementSong = { songId -> supplementSongResolver(songId) },
        )
        return runCatching {
            delivery.install()
            this.delivery = delivery
            val subscription = currentSong.addListener { song ->
                val nextSongId = song?.details?.appleMusicId
                if (nextSongId != lastPublishedSongId) {
                    overlay.clear()
                    lastPublishedSongId = nextSongId
                }
            }
            registration.onClose(subscription::close)
            registration.activate()
            TargetCapabilityInstall.Active(
                "Apple native lyric pronunciation/translation hooks installed: " +
                    "${loadTarget.className}#${loadTarget.methodName}",
            )
        }.getOrElse { error ->
            registration.close()
            TargetCapabilityInstall.Degraded(
                "Apple native lyric hooks failed: ${error.message ?: error.javaClass.simpleName}",
            )
        }.also { installed = it }
    }

    override fun onLyricsPointer(pointer: Any?) {
        delivery?.onLyricsPointer(pointer)
    }

    override fun onLyricsPresentation(fragment: Any?, pointer: Any?) {
        delivery?.onLyricsPresentation(fragment, pointer)
    }

    override fun onCustomOverlayUpdated(songId: Long) {
        delivery?.onCustomOverlayUpdated(songId)
    }

    override fun setModuleSupplementSongResolver(resolver: (Long) -> Boolean) {
        supplementSongResolver = resolver
    }
}
