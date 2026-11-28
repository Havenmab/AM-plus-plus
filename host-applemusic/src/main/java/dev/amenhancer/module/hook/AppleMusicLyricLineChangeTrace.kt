package dev.amenhancer.module.hook

import dev.amenhancer.host.applemusic.AppleMusicHostProfile

/**
 * 「换行打点」: count the host's own line changes, so its moves can be counted against ours.
 *
 * **What it answers.** A device log that shows "the middle line did not move" has two very
 * different causes, and a log of the spring alone cannot tell them apart:
 *
 *  * the host changed the line, the layout produced `animateMove` calls and we refused every one of
 *    them, or
 *  * the host never changed the line in the first place (or changed it on a pass that produced no
 *    move at all), so there was nothing for the spring to take over.
 *
 * The spring's own lines are all downstream of that answer: they can only speak for the moves they
 * were *offered*. This hook pins the upstream fact.
 *
 * **1626 (measured).** `com.apple.android.music.player.fragment.K#b(int,int): void` is the commit
 * point: a small helper holding the lyrics fragment, called from the two lyric adapters' commit
 * paths (`com.apple.android.music.player.A#P` and `V0#P` — the same two adapters that build the
 * interlude exits) and from two observer bridges, before the layout pass that produces the moves.
 * Its body reads the fragment's current/previous line fields, `PlayerLyricsViewModel#setCurrentHighlightedLine`
 * and the animator's `u` flag, i.e. "this line is current now". The two `int` arguments are the
 * highlighted line interval (`-1` is the host's own `NO_POSITION` for "no current line yet").
 *
 * **What it does not do.** Nothing to the host. No argument, no return value, no field is written,
 * and no exception is allowed out: `beforeHookedMethod` only reads the two integers. Hooking a pass
 * that the host uses to decide the highlight is safe precisely because it is read-only — the
 * module's own documentation has said so since the probe (`05-plugin-sdk-v1-capability.md` §(a):
 * "只读观测必须用 observe，不要用 hook").
 *
 * **Every failure is local.** A missing section, a renamed member or a class that does not load all
 * mean "no `line-change` line", never a failed install of the spring.
 */
internal class AppleMusicLyricLineChangeTrace private constructor(
    private val member: java.lang.reflect.Method,
    /** `(start, end)` of the highlighted line interval, as the host passed them. */
    private val onLineChange: (Int, Int) -> Unit,
) {
    fun install(scope: HookRegistrationScope) {
        check(ModernXposedRuntime.hookMethod(member, hook(), scope)) {
            "hook registration refused: " + member
        }
    }

    private fun hook() = object : ModernMethodHook() {
        override fun beforeHookedMethod(param: ModernMethodHook.MethodHookParam) {
            // Before, not after: the point of the line is its *position* in the log relative to the
            // pushes the layout pass is about to produce.
            runCatching {
                val start = param.args.getOrNull(0) as? Int ?: return@runCatching
                val end = param.args.getOrNull(1) as? Int ?: return@runCatching
                onLineChange(start, end)
            }
        }
    }

    companion object {
        /**
         * Resolve `lyricLineMotion`'s line-change role and build the hook, or null when this build
         * cannot support it.
         *
         * Never throws and never returns a half-installed hook: the section is optional in the
         * profile, and [onUnavailable] is called at most once with the reason.
         */
        fun open(
            profile: AppleMusicHostProfile,
            loader: ClassLoader,
            onLineChange: (Int, Int) -> Unit,
            onUnavailable: (String) -> Unit,
        ): AppleMusicLyricLineChangeTrace? {
            val motion = runCatching { profile.document.getJSONObject("lyricLineMotion") }.getOrNull()
                ?: return null
            val roles = runCatching { LyricLineTraceRoles(loader, motion) }.getOrNull()
                ?: return null
            val member = roles.lineChange
            if (member == null) {
                runCatching { onUnavailable(roles.missing().joinToString("; ")) }
                return null
            }
            return runCatching { AppleMusicLyricLineChangeTrace(member, onLineChange) }
                .onFailure { error ->
                    runCatching {
                        onUnavailable("construction failed: " + (error.message ?: error.javaClass.simpleName))
                    }
                }
                .getOrNull()
        }
    }
}
