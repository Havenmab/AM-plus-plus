package dev.amenhancer.module.hook

import dev.amenhancer.host.applemusic.AppleMusicHostProfile
import dev.amenhancer.module.model.ModuleSettings
import java.lang.reflect.Method

/**
 * Installs the iOS-style lyric line motion for one exact Apple Music build.
 *
 * The hooks are registered unconditionally and stay inert while the settings switch is
 * off; every callback re-reads the live settings, so turning the switch or moving a slider
 * takes effect on the next line change without reinstalling anything.
 */
internal class AppleMusicLyricLineMotionTarget(
    private val profile: AppleMusicHostProfile,
    private val loader: ClassLoader,
    private val settings: () -> ModuleSettings,
) : LyricLineMotionTarget {
    private var registration: HookRegistrationScope? = null

    @Synchronized
    override fun install(): TargetCapabilityInstall {
        registration?.takeIf { it.isActive }?.let {
            return TargetCapabilityInstall.Active("Lyric line motion already active; ${profile.id}")
        }
        if (profile.document.optJSONObject("lyricBlur") == null) {
            return TargetCapabilityInstall.Unsupported("Lyric line motion has no exact contract; ${profile.id}")
        }
        val scope = HookRegistrationScope()
        return try {
            val contract = LyricLineMotionContract(profile, loader)
            val session = LyricLineMotionSession(contract, settings)
            session.install { method: Method, hook: ModernMethodHook ->
                check(ModernXposedRuntime.hookMethod(method, hook, scope))
            }
            scope.onClose { session.close() }
            scope.activate()
            registration = scope
            TargetCapabilityInstall.Active("Spring lyric line motion installed; ${profile.id}")
        } catch (error: Throwable) {
            scope.close()
            ModernXposedRuntime.log("Lyric line motion kept native behavior", error)
            TargetCapabilityInstall.Degraded(
                "Lyric line motion contract/registration failed; ${profile.id}: ${error.message}",
            )
        }
    }
}
