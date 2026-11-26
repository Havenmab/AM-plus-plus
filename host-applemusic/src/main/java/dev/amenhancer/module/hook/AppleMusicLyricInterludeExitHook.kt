package dev.amenhancer.module.hook

import android.os.SystemClock
import android.util.Log
import dev.amenhancer.host.applemusic.AppleMusicHostProfile
import dev.amenhancer.module.lyrics.LyricInterludeExitPolicy
import org.json.JSONObject

/**
 * 「间奏退场」: wait for the three-dot exit animation instead of racing it.
 *
 * **What the host does when an interlude ends (1626, measured on a device).** The dots grow together
 * to 1.2x, shrink while they fade to nothing, and only then does the next line come in. That is
 * `V0#N(ViewHolder)` / `A#N(ViewHolder)`:
 *
 *     grow   SCALE_X/Y [current, 1.2]   PathInterpolator(.25,.1,.25,1)   750 ms
 *     shrink SCALE_X/Y -> 0.5                                             250 ms   ) together,
 *     fade   ALPHA     -> 0                                               250 ms   ) after grow
 *     AnimatorSet finished by V0$b#onAnimationEnd / A$s#onAnimationEnd
 *
 * **Who starts it.** Not the move path: the exit is rebuilt by the *change* animation that
 * RecyclerView runs on the row that stopped being current —
 * `mc.y#l` (animateChange) -> its listener's `onAnimationStart` -> `F#s(holder)` ->
 * `$u#s` -> `i1#N` -> `V0#N`/`A#N`. That chain runs when the pending animations are
 * started, i.e. a frame **after** the layout pass that asked the item animator to move the rows, and
 * it is the reason the dots are still on screen while our wave is already moving.
 *
 * **What this hook does.** Nothing to the dots. It only reports two moments to the spring: "an
 * interlude exit just started" (→ park the wave for [LyricInterludeExitPolicy.EXIT_MS], or until the
 * end callback) and "it just finished" (→ let the stagger run). The dots, their scale and their alpha
 * stay entirely the host's property; the only thing this module changes is *when* the rows move.
 *
 * Every role is optional and every call is wrapped: a build without these members simply never
 * waits, which is exactly the behaviour before this feature existed.
 */
internal class AppleMusicLyricInterludeExitHook private constructor(
    private val roles: LyricInterludeExitRoles,
    private val diagnostics: LyricInterludeExitDiagnostics,
    private val listener: Listener,
    private val clock: () -> Long,
) {
    /** The two moments the spring needs. Implemented by the spring session. */
    interface Listener {
        /** An interlude row's exit animation was just (re)started. */
        fun onInterludeExitStarted()

        /** That animation finished; the rows may move now. */
        fun onInterludeExitFinished()
    }

    private val bindingClass: Class<*> = checkNotNull(roles.instrumentalBinding) { "instrumentalBinding" }

    fun install(scope: HookRegistrationScope) {
        register(requireNotNull(roles.wordExit) { "wordExit" }, startHook(), scope)
        register(requireNotNull(roles.lineExit) { "lineExit" }, startHook(), scope)
        roles.wordExitEnd?.let { register(it, endHook(), scope) }
        roles.lineExitEnd?.let { register(it, endHook(), scope) }
    }

    private fun register(member: java.lang.reflect.Method, hook: ModernMethodHook, scope: HookRegistrationScope) {
        check(ModernXposedRuntime.hookMethod(member, hook, scope)) { "hook registration refused: " + member }
    }

    private fun startHook() = object : ModernMethodHook() {
        override fun beforeHookedMethod(param: ModernMethodHook.MethodHookParam) {
            runCatching {
                if (!isInterludeRow(param.args.getOrNull(0))) return@runCatching
                listener.onInterludeExitStarted()
                diagnostics.started(SystemClock.uptimeMillis())
            }.onFailure { diagnostics.failed("exitStart", it) }
        }
    }

    private fun endHook() = object : ModernMethodHook() {
        override fun afterHookedMethod(param: ModernMethodHook.MethodHookParam) {
            runCatching {
                if (param.throwable != null) return@runCatching
                listener.onInterludeExitFinished()
                diagnostics.finished()
            }.onFailure { diagnostics.failed("exitEnd", it) }
        }
    }

    /** True when [holder] carries the three-dot interlude binding; false for every text row. */
    private fun isInterludeRow(holder: Any?): Boolean = runCatching {
        if (holder == null) return@runCatching false
        val binding = roles.holderBinding?.get(holder) ?: return@runCatching false
        bindingClass.isInstance(binding)
    }.getOrDefault(false)

    companion object {
        /**
         * Resolve `lyricLineMotion.interlude` and build the hook, or null when this build cannot
         * support it. A missing section, a renamed member or a class that does not load all mean
         * "never wait", never a failed install of the spring.
         */
        fun open(
            profile: AppleMusicHostProfile,
            loader: ClassLoader,
            listener: Listener,
            clock: () -> Long = { SystemClock.uptimeMillis() },
        ): AppleMusicLyricInterludeExitHook? {
            val diagnostics = LyricInterludeExitDiagnostics(clock)
            val section: JSONObject = runCatching {
                profile.document.getJSONObject("lyricLineMotion").getJSONObject("interlude")
            }.getOrNull() ?: return null
            val roles = runCatching { LyricInterludeExitRoles(loader, section) }.getOrNull()
            if (roles == null || !roles.isComplete()) {
                diagnostics.unavailable("contract incomplete: " + (roles?.missing()?.joinToString("; ") ?: "roles"))
                return null
            }
            if (!roles.hasEndCallback()) {
                diagnostics.degraded(
                    "no exit end callback; parking for the book duration " +
                        LyricInterludeExitPolicy.EXIT_MS + " ms",
                )
            }
            return runCatching {
                AppleMusicLyricInterludeExitHook(roles, diagnostics, listener, clock)
            }.onFailure { diagnostics.unavailable("construction failed: " + it.message) }.getOrNull()
        }
    }
}

/**
 * Bounded, text-only observability for 「间奏退场」.
 *
 * `adb logcat -s AMPP-Interlude` answers the three questions a device run asks: did an interlude
 * exit start (and when), did its end callback arrive or did the hold have to expire, and did the
 * feature refuse to install (and why). Repeats are rate limited and the session stops after
 * [MAX_LINES].
 */
internal class LyricInterludeExitDiagnostics(
    private val clock: () -> Long = { SystemClock.uptimeMillis() },
) {
    private val reported = HashMap<String, Long>()
    private var lastStartAt = Long.MIN_VALUE
    private var lastEndAt = Long.MIN_VALUE
    private var emitted = 0
    private var starts = 0
    private var ends = 0

    /** An interlude row's exit animation started. */
    fun started(nowMs: Long) {
        synchronized(reported) {
            if (nowMs - lastStartAt < WINDOW_MS) return
            lastStartAt = nowMs
            starts += 1
        }
        emit("exit start n=" + starts)
    }

    /** Its end callback arrived; the hold is released now instead of at the book duration. */
    fun finished() {
        synchronized(reported) {
            val now = now()
            if (now - lastEndAt < WINDOW_MS) return
            lastEndAt = now
            ends += 1
        }
        emit("exit end n=" + ends)
    }

    /** The install is usable but degrades to the book duration. One line, ever. */
    fun degraded(reason: String) = once("degraded", "interlude exit degraded: " + reason)

    /** The contract is unusable; one line, ever. The spring beside it keeps working. */
    fun unavailable(reason: String) = once("unavailable", "interlude exit unavailable: " + reason)

    /** A hook failed; one line per call site, ever. */
    fun failed(where: String, error: Throwable) =
        once(
            "failed:" + where,
            "interlude exit " + where + " failed: " + (error.message ?: error.javaClass.simpleName),
        )

    private fun once(key: String, line: String) {
        synchronized(reported) {
            if (reported.containsKey(key)) return
            reported[key] = now()
        }
        emit(line)
    }

    private fun now(): Long = runCatching(clock).getOrDefault(0L)

    private fun emit(line: String) {
        synchronized(reported) {
            if (emitted >= MAX_LINES) return
            emitted += 1
        }
        runCatching { Log.i(TAG, line) }
        runCatching { ModernXposedRuntime.log(TAG + " " + line) }
    }

    private companion object {
        const val TAG = "AMPP-Interlude"

        /** Hard budget for one process. */
        const val MAX_LINES = 160

        /** One line per identical event inside this window. */
        const val WINDOW_MS = 200L
    }
}
