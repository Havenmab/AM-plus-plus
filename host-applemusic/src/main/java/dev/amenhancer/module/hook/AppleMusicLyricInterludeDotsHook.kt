package dev.amenhancer.module.hook

import android.os.SystemClock
import android.util.Log
import android.view.View
import dev.amenhancer.host.applemusic.AppleMusicHostProfile
import dev.amenhancer.module.lyrics.LyricInterludeDotsAction
import dev.amenhancer.module.lyrics.LyricInterludeDotsPolicy
import dev.amenhancer.module.lyrics.LyricInterludeDotsRow
import dev.amenhancer.module.lyrics.LyricInterludeDotsTrack
import org.json.JSONObject

/**
 * 「间奏三圆点」: end the interlude indicator at the line change instead of letting it ride the row.
 *
 * **The problem this removes.** In 1626 the three dots of `res/layout/lyrics_line_instrumental.xml`
 * have no exit animation at all. Their only live animation is the interlude *performance* —
 * `A#Y(q8.m9,ZZ)` / `V0#U(q8.m9,Z)`: one `ValueAnimator` per dot sweeping its tint colour across
 * the interlude, plus a 4 s repeating 1.0 → 1.2 → 1.0 scale pulse on the row root — armed from
 * `onBindViewHolder` and re-synced from the adapters' settled-zero-displacement-move callback.
 * Nothing ever hides them: they keep full alpha and keep performing until `onViewRecycled` cancels
 * the animation, which happens only once the row has left the list. So when the interlude ends the
 * dots are still on screen (at whatever scale the pulse left them) while the line-change wave is
 * already bringing the next line onto the anchor, and both are visible at once.
 *
 * **What it does.** At a line change, an interlude row **behind** the new current line is retired:
 * the dots fade out over [LyricInterludeDotsPolicy.RETIRE_MS] (120 ms — well inside the wave's
 * 450 ms settle, so the row is empty before the incoming line arrives). The row that *is* the
 * current line is armed, i.e. its dots go back to full alpha, which is what keeps the next
 * interlude's appearance exactly as the host wrote it. Rows ahead of the current line are never
 * touched, and a song without an interlude never reaches any of this (no instrumental binding is
 * ever seen).
 *
 * **What it deliberately does not do.** It never cancels the host's own animators (`A.L`/`A.M`,
 * `V0.A`/`V0.B`): those are per-adapter, so cancelling for one row could stop another row that is
 * merely visible, and the capture would leave *that* row stuck mid-pulse. Fading `alpha` is enough
 * — an interlude row contains nothing but the three dots, so an empty row that is still breathing
 * is invisible, and `onViewRecycled` plus the next `A#Y`/`V0#U` restart the performance untouched.
 * The only property this feature writes is `alpha` on the three dots, which no live host path
 * writes.
 *
 * Every read and every write is wrapped, and a missing role or profile section installs nothing:
 * this can never turn a line change into a failure.
 */
internal class AppleMusicLyricInterludeDotsHook private constructor(
    private val roles: LyricInterludeDotsRoles,
    private val diagnostics: LyricInterludeDotsDiagnostics,
    private val enabled: () -> Boolean,
    private val clock: () -> Long,
) {
    private val tracks = LyricInterludeDotsTrack()
    private val bindingClass: Class<*> = checkNotNull(roles.instrumentalBinding) { "instrumentalBinding" }

    /**
     * The current line as the last line change declared it.
     *
     * [LyricInterludeDotsPolicy.NO_POSITION] means "unknown", which is also what a `start = -1` line
     * callback leaves behind: the host skips its re-anchor for that one, so nothing may be retired
     * from it.
     */
    private var currentLine: Int = LyricInterludeDotsPolicy.NO_POSITION

    fun install(scope: HookRegistrationScope) {
        register(requireNotNull(roles.lineChange) { "lineChange" }, lineChangeHook(), scope)
        register(requireNotNull(roles.wordBind) { "wordBind" }, bindHook(), scope)
        register(requireNotNull(roles.lineBind) { "lineBind" }, bindHook(), scope)
        register(requireNotNull(roles.wordSettled) { "wordSettled" }, settledHook(), scope)
        register(requireNotNull(roles.lineSettled) { "lineSettled" }, settledHook(), scope)
    }

    private fun register(member: java.lang.reflect.Method, hook: ModernMethodHook, scope: HookRegistrationScope) {
        check(ModernXposedRuntime.hookMethod(member, hook, scope)) { "hook registration refused: " + member }
    }

    // ---- the hooks ------------------------------------------------------------------------

    /** `K#b(int,int)`, before: remember the new current line so the move pass can classify rows. */
    private fun lineChangeHook() = object : ModernMethodHook() {
        override fun beforeHookedMethod(param: ModernMethodHook.MethodHookParam) {
            runCatching {
                if (isOn()) {
                    val start = param.args.getOrNull(0) as? Int
                    if (start != null) {
                        currentLine = if (start >= 0) start else LyricInterludeDotsPolicy.NO_POSITION
                    }
                }
            }.onFailure { diagnostics.failed("lineChange", it) }
        }
    }

    /** `A#l` / `V0#l`, before: a freshly bound interlude row shows its dots, as the host left them. */
    private fun bindHook() = object : ModernMethodHook() {
        override fun beforeHookedMethod(param: ModernMethodHook.MethodHookParam) {
            onRow(param.args.getOrNull(0), forced = true)
        }
    }

    /** `A#H` / `V0#H`, before: a settled zero-displacement row — classify it against the current line. */
    private fun settledHook() = object : ModernMethodHook() {
        override fun beforeHookedMethod(param: ModernMethodHook.MethodHookParam) {
            onRow(param.args.getOrNull(0), forced = false)
        }
    }

    // ---- the work -------------------------------------------------------------------------

    /** Called from the spring's `animateMove` hook: every visible row of a line change passes through. */
    fun onMove(holder: Any?) {
        onRow(holder, forced = false)
    }

    /**
     * Classify one holder against the current line and apply the verdict.
     *
     * `forced` is the bind path, which arms unconditionally: a row that was just bound is the row
     * the adapter decided to show, so its dots must look the way the host would have left them.
     */
    private fun onRow(holder: Any?, forced: Boolean) {
        if (holder == null) return
        runCatching {
            if (!isOn()) return@runCatching
            // Every row of the list walks through here, so the interlude test comes first: a text
            // row must not allocate a track entry (the track is bounded, and a screenful of text
            // rows would evict the interlude it is supposed to remember).
            val dots = dotsOf(holder) ?: return@runCatching
            val now = clock()
            val position = positionOf(holder)
            if (forced) {
                tracks.arm(holder, now)?.let { apply(dots, it.action, it.alpha, position) }
            } else {
                for (change in tracks.observe(listOf(LyricInterludeDotsRow(holder, position)), currentLine, now)) {
                    apply(dots, change.action, change.alpha, position)
                }
            }
        }.onFailure { diagnostics.failed("row", it) }
    }

    private fun isOn(): Boolean = runCatching { enabled() }.getOrDefault(false)

    private fun positionOf(holder: Any): Int =
        runCatching { roles.holderPosition?.invoke(holder) as? Int }.getOrNull()
            ?: LyricInterludeDotsPolicy.NO_POSITION

    /**
     * Write one row's dots.
     *
     * `ARM` cancels any fade in flight and puts the dots back at full alpha in the same frame — a
     * half-faded interlude that became the current line again must not stay half-faded.
     * `RETIRE` cancels the previous fade and starts a *shorter* one from the current alpha, so a
     * second line change inside the same fade can only make the dots leave sooner, never later.
     */
    private fun apply(dots: List<View>, action: LyricInterludeDotsAction, alpha: Float, position: Int) {
        if (action == LyricInterludeDotsAction.NONE) return
        val alphaNow = alpha.coerceIn(0f, 1f)
        when (action) {
            LyricInterludeDotsAction.ARM -> {
                for (dot in dots) {
                    runCatching { dot.animate().cancel() }
                    runCatching { dot.alpha = 1f }
                }
                diagnostics.armed(position)
            }
            LyricInterludeDotsAction.RETIRE -> {
                val remainingMs = (LyricInterludeDotsPolicy.RETIRE_MS * alphaNow).toLong()
                for (dot in dots) {
                    runCatching { dot.animate().cancel() }
                    if (remainingMs <= 0L) {
                        runCatching { dot.alpha = 0f }
                    } else {
                        runCatching { dot.animate().alpha(0f).setDuration(remainingMs).start() }
                    }
                }
                diagnostics.retired(position, remainingMs.toInt())
            }
            LyricInterludeDotsAction.NONE -> Unit
        }
    }

    /** The three dots of this holder, or null when it is not the interlude row. */
    private fun dotsOf(holder: Any): List<View>? = runCatching {
        val binding = roles.holderBinding?.get(holder)
        if (binding == null || !bindingClass.isInstance(binding)) {
            null
        } else {
            val dots = ArrayList<View>(3)
            for (field in listOfNotNull(roles.dot1, roles.dot2, roles.dot3)) {
                (field.get(binding) as? View)?.let { dots += it }
            }
            if (dots.isEmpty()) null else dots
        }
    }.getOrNull()

    companion object {
        /**
         * Resolve the profile section and build the hook, or null when this build cannot support it.
         *
         * Everything here is optional on purpose: a missing `lyricLineMotion.interlude` section, a
         * renamed member, or a class that does not load all mean "leave the dots alone", never a
         * failed install of the spring this lives next to.
         */
        fun open(
            profile: AppleMusicHostProfile,
            loader: ClassLoader,
            enabled: () -> Boolean,
            clock: () -> Long = { SystemClock.uptimeMillis() },
        ): AppleMusicLyricInterludeDotsHook? {
            val diagnostics = LyricInterludeDotsDiagnostics(clock)
            val section: JSONObject = runCatching {
                profile.document.getJSONObject("lyricLineMotion").getJSONObject("interlude")
            }.getOrNull() ?: return null
            val roles = runCatching { LyricInterludeDotsRoles(loader, section) }.getOrNull()
            if (roles == null || !roles.isComplete()) {
                diagnostics.unavailable(
                    "contract incomplete: " + (roles?.missing()?.joinToString("; ") ?: "roles"),
                )
                return null
            }
            return runCatching {
                AppleMusicLyricInterludeDotsHook(roles, diagnostics, enabled, clock)
            }.onFailure { diagnostics.unavailable("construction failed: " + it.message) }.getOrNull()
        }
    }
}

/**
 * Bounded, text-only observability for 「间奏三圆点」.
 *
 * `adb logcat -s AMPP-Interlude` answers the three questions a device run asks: did the dots get
 * armed (a fresh interlude) or retired (the interlude ended) and with how much fade left, and did
 * the feature refuse to install (and why). Repeats are rate limited and the session stops after
 * [MAX_LINES], so a long song cannot flood logcat.
 */
internal class LyricInterludeDotsDiagnostics(
    private val clock: () -> Long = { SystemClock.uptimeMillis() },
) {
    private val reported = HashMap<String, Long>()
    private var lastAt = Long.MIN_VALUE
    private var lastKey = ""
    private var emitted = 0

    /** A row's dots were shown again; `pos` is its layout position, or -1 when unreadable. */
    fun armed(pos: Int) = debounced("armed/" + pos, "armed pos=" + pos)

    /** A row's dots were retired: `left` is the fade in ms, 0 when they were already gone. */
    fun retired(pos: Int, left: Int) =
        debounced("retired/" + pos, "retired pos=" + pos + " fade=" + left + "ms")

    /** The contract or the install is unusable; one line, ever. The spring beside it keeps working. */
    fun unavailable(reason: String) = once("unavailable", "interlude dots unavailable: " + reason)

    /** A hook failed; one line per call site, ever. */
    fun failed(where: String, error: Throwable) =
        once(
            "failed:" + where,
            "interlude dots " + where + " failed: " + (error.message ?: error.javaClass.simpleName),
        )

    /** Same key, same moment: drop the repeat so one burst of rows cannot fill the budget. */
    private fun debounced(key: String, line: String) {
        val now = now()
        synchronized(reported) {
            if (key == lastKey && now - lastAt < WINDOW_MS) return
            lastKey = key
            lastAt = now
        }
        emit(line)
    }

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
        const val MAX_LINES = 120

        /** One line per identical key inside this window. */
        const val WINDOW_MS = 250L
    }
}
