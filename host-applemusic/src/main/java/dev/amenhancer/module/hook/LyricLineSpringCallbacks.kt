package dev.amenhancer.module.hook

import java.lang.reflect.Method

/**
 * Invoke one of the host's own callbacks that 「换行弹簧」replays on its behalf.
 *
 * Every notification a takeover owes is a host method held by reflection — the per-row announcement
 * (`PlayerLyricsViewFragment$u#s`) and the "this animator has nothing left" dispatch
 * (`mc.y#u` / `RecyclerView$k#d`) — and all of them follow the same three rules, which is why they
 * share one call site:
 *
 *  * **a missing role is not an error.** The spring animates exactly the same without it; only that
 *    one callback is lost, and the install has already said so in a single line.
 *  * **the member is invoked on the animator instance**, so the instance has to really be of the
 *    declaring class: a role pinned on a subclass (`$u`) and invoked on a different `mc.y` would
 *    throw `IllegalArgumentException` on every call.
 *  * **a throwing host method is swallowed.** The host's adapter may be mid-rebind or already gone;
 *    the lyric list must not go down because a diagnostic callback did.
 *
 * Returns true only when the call really went through — the diagnostics print that as `ok=1`.
 */
internal fun invokeHostCallback(member: Method?, target: Any, vararg arguments: Any?): Boolean {
    if (member == null) return false
    return runCatching {
        require(member.declaringClass.isInstance(target)) {
            target.javaClass.name + " is not a " + member.declaringClass.name
        }
        member.invoke(target, *arguments)
    }.isSuccess
}
