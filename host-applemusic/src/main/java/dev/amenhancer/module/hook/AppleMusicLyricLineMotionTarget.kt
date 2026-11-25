package dev.amenhancer.module.hook

import android.os.SystemClock
import android.util.Log
import dev.amenhancer.host.applemusic.AppleMusicHostProfile
import dev.amenhancer.module.lyrics.LyricLineMotionPolicy
import dev.amenhancer.module.model.ModuleSettings

/**
 * 「歌词换行节奏」: scale the duration Apple Music itself computes for a lyric line change.
 *
 * **Where it hooks and why there.** The host's native line callback
 * (`PlayerLyricsViewFragment$f#invoke`) is the one place that *recomputes* the move duration,
 * from the gap to the next line; it then (a) hands the negated duration to the fragment's
 * `suggestLineOffset` bridge and (b) stores it in the lyrics animator's move-duration field.
 * The animator reads that field once per move batch. Scaling at the recomputation point therefore
 * changes only the tempo of the host's own row slide: the re-anchor, the delayed row transition and
 * every other animator field stay exactly as Apple Music wrote them.
 *
 * **Why it cannot compound.** The multiplier is applied to the value the host just wrote, and only
 * when the field still holds exactly that value:
 *
 *  1. the `suggestLineOffset` hook records the duration the host is about to store — it runs
 *     inside the same callback, immediately before the store, so its presence *is* the proof that
 *     this call recomputed the duration (the host skips that whole branch when the offset it would
 *     suggest has not changed);
 *  2. the callback's after-hook refuses to write unless `duration == recorded`, so a stale scaled
 *     value, a second writer, or a version whose branch order changed degrades to a no-op instead
 *     of multiplying twice;
 *  3. [LyricLineMotionPolicy.scaledDurationMs] returns null for a neutral multiplier.
 *
 * Anything unexpected (missing member, wrong type, an exception in the hook) is swallowed and
 * reported once: the line change itself, the re-anchor and the row transition never depend on us.
 */
internal class AppleMusicLyricLineMotionTarget(
    private val profile: AppleMusicHostProfile,
    private val loader: ClassLoader,
    private val settings: () -> ModuleSettings,
) : LyricLineMotionTarget {
    /** One host duration write, recorded between the offset bridge and the callback's return. */
    private class HostWrite(val fragment: Any, val durationMs: Long)

    private val pendingWrite = ThreadLocal<HostWrite>()
    private val diagnostics = LyricLineMotionDiagnostics()
    private var registration: HookRegistrationScope? = null

    @Synchronized
    override fun install(): TargetCapabilityInstall {
        registration?.takeIf { it.isActive }?.let {
            return TargetCapabilityInstall.Active("Lyric line-change rhythm already active; ${profile.id}")
        }
        val names = profile.document.optJSONObject("lyricLineMotion")
            ?: return TargetCapabilityInstall.Unsupported("Lyric line-change rhythm has no exact contract; ${profile.id}")
        val scope = HookRegistrationScope()
        return try {
            val native = LyricLineMotionContract(profile, loader, names)
            check(ModernXposedRuntime.hookMethod(native.lineCallback, object : ModernMethodHook() {
                override fun beforeHookedMethod(param: MethodHookParam) {
                    // A new line event starts here; nothing recorded outside this call may count.
                    pendingWrite.remove()
                }

                override fun afterHookedMethod(param: MethodHookParam) {
                    val write = pendingWrite.get() ?: return
                    pendingWrite.remove()
                    if (param.throwable != null) return
                    guarded { applyScale(native, param.thisObject, write) }
                }
            }, scope)) { "line callback could not be intercepted" }
            check(ModernXposedRuntime.hookMethod(native.lineOffset, object : ModernMethodHook() {
                override fun beforeHookedMethod(param: MethodHookParam) {
                    val fragment = param.args.getOrNull(0) ?: return
                    val suggested = param.args.getOrNull(1) as? Int ?: return
                    // The host passes the negated duration; anything else is not that write.
                    if (suggested >= 0) return
                    pendingWrite.set(HostWrite(fragment, -suggested.toLong()))
                }
            }, scope)) { "line offset bridge could not be intercepted" }
            scope.activate()
            registration = scope
            TargetCapabilityInstall.Active("Lyric line-change rhythm installed; ${profile.id}")
        } catch (error: Throwable) {
            scope.close()
            pendingWrite.remove()
            val reason = error.message ?: error.javaClass.simpleName
            diagnostics.unavailable(reason)
            TargetCapabilityInstall.Degraded(
                "Lyric line-change rhythm contract/registration failed; ${profile.id}: $reason",
            )
        }
    }

    /** One multiply, on the host's freshly recomputed value, or nothing at all. */
    private fun applyScale(native: LyricLineMotionContract, callback: Any?, write: HostWrite) {
        val scale = LyricLineMotionPolicy.appliedScale(settings()) ?: return
        val fragment = native.fragmentOf(callback ?: return) ?: return
        if (fragment !== write.fragment) return
        val animator = native.animatorOf(fragment) ?: return
        val current = native.durationOf(animator)
        // Anti-compounding: only ever replace the host's own value for this very call.
        if (current != write.durationMs) {
            diagnostics.refused(host = write.durationMs, current = current)
            return
        }
        val scaled = LyricLineMotionPolicy.scaledDurationMs(write.durationMs, scale) ?: return
        native.writeDuration(animator, scaled)
        diagnostics.applied(host = write.durationMs, applied = scaled, scale = scale)
    }

    /** A hook must never take the lyric list down with it. */
    private inline fun guarded(block: () -> Unit) {
        runCatching(block).onFailure { diagnostics.unavailable(it.message ?: it.javaClass.simpleName) }
    }
}

/**
 * Bounded, text-only observability for the line-change rhythm.
 *
 * Logcat (`adb logcat -s AMPP-LineMotion`) shows what the host asked for and what the animator
 * received, which is how the switch and the slider are verified on a device. Repeated values are
 * rate-limited and the session stops after [MAX_LINES], so a long song cannot flood the log.
 */
internal class LyricLineMotionDiagnostics {
    private var lastApplied = Long.MIN_VALUE
    private var lastAt = 0L
    private var emitted = 0
    private var refused = false
    private var unavailable = false

    fun applied(host: Long, applied: Long, scale: Float) {
        val now = SystemClock.uptimeMillis()
        if (applied == lastApplied && now - lastAt < REPEAT_WINDOW_MS) return
        if (emitted >= MAX_LINES) return
        emitted += 1
        lastApplied = applied
        lastAt = now
        emit("line-motion host=${host}ms x$scale -> ${applied}ms")
    }

    fun refused(host: Long, current: Long) {
        if (refused) return
        refused = true
        emit("line-motion unavailable: animator duration did not hold the host value " +
            "(host=${host}ms current=${current}ms)")
    }

    fun unavailable(reason: String) {
        if (unavailable) return
        unavailable = true
        emit("line-motion unavailable: $reason")
    }

    private fun emit(line: String) {
        runCatching { Log.i(TAG, line) }
        runCatching { ModernXposedRuntime.log("$TAG $line") }
    }

    private companion object {
        const val TAG = "AMPP-LineMotion"
        const val MAX_LINES = 60
        const val REPEAT_WINDOW_MS = 500L
    }
}
