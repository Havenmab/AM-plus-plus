package dev.amenhancer.module.hook.player

import android.app.Activity
import android.graphics.Bitmap
import android.view.View
import dev.amenhancer.host.applemusic.AppleMusicHostProfile
import dev.amenhancer.module.config.TargetConfigClient
import dev.amenhancer.module.hook.HookRegistrationScope
import dev.amenhancer.module.hook.LyricsPlayerTarget
import dev.amenhancer.module.hook.ModernMethodHook
import dev.amenhancer.module.hook.ModernXposedRuntime
import dev.amenhancer.module.hook.TargetCapabilityInstall
import java.lang.reflect.Method

/**
 * Host adapter of the full-page self-drawn lyrics player (spec §2/§7).
 *
 * One install = one hook registration scope. Every private member is resolved by
 * [LyricsPlayerContract] before the first hook, so a build whose descriptors drift fails here
 * and reports `Degraded` — the feature then leaves the native lyrics page alone.
 *
 * The capability itself is passive: the hooks only observe the host's lyrics fragment, its
 * media controller and its background artwork. Nothing here replaces host behaviour when the
 * page is not mounted, and `lyrics player degraded: <reason>` is the only trace of a refused
 * mount.
 */
internal class AppleMusicLyricPlayerTarget(
    private val profile: AppleMusicHostProfile,
    private val loader: ClassLoader,
    private val enabled: () -> Boolean = {
        runCatching { TargetConfigClient.currentSettings().lyricsPlayerEnabled }.getOrDefault(false)
    },
    /** Spec §7.3 second source (module-owned document); null keeps the host model as the only source. */
    private val fallback: (() -> dev.amenhancer.module.lyrics.player.PlayerDocument?)? = null,
    /** Imported lyric font (lyrics_font_* slot); null keeps the page on the system font. */
    private val typeface: (() -> android.graphics.Typeface?)? = null,
) : LyricsPlayerTarget {
    private val lock = Any()
    private var registration: HookRegistrationScope? = null
    private var lastFailure: String? = null

    override fun install(): TargetCapabilityInstall = synchronized(lock) { installLocked() }

    private fun installLocked(): TargetCapabilityInstall {
        registration?.takeIf { it.isActive }?.let {
            return TargetCapabilityInstall.Active("Full-page lyrics player already active; ${profile.id}")
        }
        if (profile.document.optJSONObject(SECTION) == null) {
            return TargetCapabilityInstall.Unsupported("Lyrics player has no exact host contract; ${profile.id}")
        }
        val scope = HookRegistrationScope()
        return try {
            val contract = LyricsPlayerContract(profile, loader)
            val port = LyricsPlayerPortImpl(contract, fallback, typeface)
            val session = LyricsPlayerHostSession(port, enabled)
            fun hook(method: Method, callback: ModernMethodHook) {
                check(ModernXposedRuntime.hookMethod(method, callback, scope)) {
                    "hook rejected: ${method.name}"
                }
            }
            // The lyrics fragment owns the page lifetime (spec §7.2).
            hook(contract.fragmentCreateView, object : ModernMethodHook() {
                override fun afterHookedMethod(param: MethodHookParam) {
                    if (param.throwable != null) return
                    val fragment = param.thisObject ?: return
                    val view = param.result as? View
                    guarded("createView") { session.onLyricsViewCreated(fragment, view) }
                }
            })
            hook(contract.fragmentResume, object : ModernMethodHook() {
                override fun afterHookedMethod(param: MethodHookParam) {
                    if (param.throwable != null) return
                    val fragment = param.thisObject ?: return
                    guarded("resume") { session.onLyricsResumed(fragment) }
                }
            })
            hook(contract.fragmentPause, object : ModernMethodHook() {
                override fun afterHookedMethod(param: MethodHookParam) {
                    guarded("pause") { session.onLyricsPaused() }
                }
            })
            hook(contract.fragmentDestroyView, object : ModernMethodHook() {
                override fun afterHookedMethod(param: MethodHookParam) {
                    guarded("destroyView") { session.onLyricsViewDestroyed() }
                }
            })
            hook(contract.activityStop, object : ModernMethodHook() {
                override fun afterHookedMethod(param: MethodHookParam) {
                    val activity = param.thisObject as? Activity ?: return
                    guarded("activityStop") { session.onActivityStopped(activity) }
                }
            })
            // A document install is the first moment the page has something to paint.
            listOf(contract.fragmentInstallDocument, contract.fragmentPresentDocument).forEach { method ->
                hook(method, object : ModernMethodHook() {
                    override fun afterHookedMethod(param: MethodHookParam) {
                        if (param.throwable != null) return
                        val fragment = param.thisObject ?: return
                        guarded("installDocument") { session.onDocumentInstalled(fragment) }
                    }
                })
            }
            // The host paints its own lyrics background with the current cover; that bitmap is
            // the only artwork the bridge can trust (no network, no scale guesses).
            hook(contract.artworkSet, object : ModernMethodHook() {
                override fun afterHookedMethod(param: MethodHookParam) {
                    if (param.throwable != null) return
                    val bitmap = param.args.firstOrNull() as? Bitmap
                    guarded("artwork") { port.onArtwork(bitmap) }
                }
            })
            hook(contract.artworkDetach, object : ModernMethodHook() {
                override fun afterHookedMethod(param: MethodHookParam) {
                    guarded("artworkDetach") { port.onArtwork(null) }
                }
            })
            scope.onClose {
                session.close()
                port.release()
            }
            scope.activate()
            registration = scope
            TargetCapabilityInstall.Active("Full-page lyrics player installed; ${profile.id}; device acceptance pending")
        } catch (error: Throwable) {
            scope.close()
            ModernXposedRuntime.log("lyrics player install failed; native lyrics page kept", error)
            TargetCapabilityInstall.Degraded(
                "Lyrics player contract/registration failed; ${profile.id}: " +
                    (error.message ?: error.javaClass.simpleName),
            )
        }
    }

    private inline fun guarded(where: String, action: () -> Unit) {
        runCatching(action).onFailure { error ->
            val reason = "$where:${error.javaClass.simpleName}"
            if (lastFailure != reason) {
                lastFailure = reason
                ModernXposedRuntime.log("lyrics player callback failed; native lyrics page kept", error)
            }
        }
    }

    private companion object {
        const val SECTION = "lyricsPlayer"
    }
}
