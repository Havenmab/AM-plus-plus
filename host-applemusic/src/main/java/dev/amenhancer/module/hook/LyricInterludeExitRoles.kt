package dev.amenhancer.module.hook

import org.json.JSONObject
import java.lang.reflect.Field
import java.lang.reflect.Method
import java.lang.reflect.Modifier

/**
 * 「间奏退场」's member inventory for Apple Music 7.0.3, resolved from the version profile.
 *
 * **All roles are optional.** This feature only *reads* the host's own exit animation and *waits*
 * for it; a missing role means "do not wait" (the wave runs as before), never a failed install of
 * the spring it lives next to. [missing] still names every failure so the install message and the
 * one `AMPP-Interlude` line can say why.
 *
 * The four hooked members are the implementations, not base declarations:
 *
 *  * `A#N` / `V0#N` build and start the exit. The base `i1#N` is empty and never fires
 *    (virtual dispatch goes to the override), which is why they are pinned per adapter;
 *  * `A$s#onAnimationEnd` / `V0$b#onAnimationEnd` are that AnimatorSet's own listener — the
 *    exact "the dots are gone" callback. Both classes are constructed only by the exit path, so
 *    nothing else can reach them.
 */
internal class LyricInterludeExitRoles(
    private val loader: ClassLoader,
    private val section: JSONObject,
) {
    private val failures = LinkedHashMap<String, String>()
    private val resolvedRoles = ArrayList<String>()

    /** `q8.m9`: the interlude row, i.e. "does this holder carry three dots at all". */
    val instrumentalBinding: Class<*>? = type("instrumentalBinding")

    /** `A#N(ViewHolder)` / `V0#N(ViewHolder)`: the exit is being (re)built and started. */
    val wordExit: Method? = hooked("wordExit")
    val lineExit: Method? = hooked("lineExit")

    /**
     * `A$s#onAnimationEnd` / `V0$b#onAnimationEnd`: the exit finished.
     *
     * Optional: without them the hold still expires on [dev.amenhancer.module.lyrics.LyricInterludeExitPolicy.EXIT_MS].
     */
    val wordExitEnd: Method? = hooked("wordExitEnd")
    val lineExitEnd: Method? = hooked("lineExitEnd")

    /** `i1$b.u`: holder -> binding, i.e. "is this row an interlude". */
    val holderBinding: Field? = field("holderBinding")

    fun resolved(): List<String> = resolvedRoles.toList()

    fun missing(): List<String> = failures.map { (role, reason) -> role + ": " + reason }

    /** The two start roles and the binding field are what the hold needs; the end hooks only refine it. */
    fun canHold(): Boolean = wordExit != null && lineExit != null &&
        holderBinding != null && instrumentalBinding != null

    /** True when the exact end callback is available; false means "use the book duration". */
    fun hasEndCallback(): Boolean = wordExitEnd != null && lineExitEnd != null

    fun isComplete(): Boolean = canHold()

    // ---- resolution ------------------------------------------------------------------------

    private fun hooked(role: String): Method? = runCatching {
        resolveMethod(role).also { resolvedRoles += "methods." + role }
    }.getOrElse {
        abandon("methods." + role, it)
        null
    }

    private fun resolveMethod(role: String): Method {
        val contract = section.getJSONObject("methods").getJSONObject(role)
        val parameters = contract.getJSONArray("parameters")
        val owner = classNamed(contract.getString("owner"))
        val member = owner.getDeclaredMethod(
            contract.getString("name"),
            *Array(parameters.length()) { classNamed(parameters.getString(it)) },
        )
        check(member.returnType == classNamed(contract.getString("returns"))) { "descriptor mismatch: " + member }
        check(Modifier.isStatic(member.modifiers) == contract.getBoolean("static")) {
            "descriptor mismatch: " + member
        }
        member.isAccessible = true
        return member
    }

    private fun field(role: String): Field? = runCatching {
        val contract = section.getJSONObject("fields").getJSONObject(role)
        val owner = classNamed(contract.getString("owner"))
        val member = owner.getDeclaredField(contract.getString("name"))
        check(member.type == classNamed(contract.getString("type"))) { "descriptor mismatch: " + member }
        check(!Modifier.isStatic(member.modifiers)) { "descriptor mismatch: " + member }
        member.isAccessible = true
        resolvedRoles += "fields." + role
        member
    }.getOrElse {
        abandon("fields." + role, it)
        null
    }

    private fun type(role: String): Class<*>? = runCatching {
        val name = section.getJSONObject("classes").getString(role)
        check(name.isNotBlank()) { "empty class role" }
        loader.loadClass(name).also { resolvedRoles += "classes." + role }
    }.getOrElse {
        abandon("classes." + role, it)
        null
    }

    private fun abandon(role: String, error: Throwable) {
        failures[role] = error.message ?: error.javaClass.simpleName
        resolvedRoles.remove(role)
    }

    private fun classNamed(name: String): Class<*> = when (name) {
        "void" -> Void.TYPE
        "boolean" -> java.lang.Boolean.TYPE
        "int" -> Integer.TYPE
        "long" -> java.lang.Long.TYPE
        "float" -> java.lang.Float.TYPE
        else -> loader.loadClass(name)
    }
}
