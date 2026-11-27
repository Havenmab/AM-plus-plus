package dev.amenhancer.module.hook

import org.json.JSONObject
import java.lang.reflect.Method
import java.lang.reflect.Modifier

/**
 * 「换行打点」's member inventory: the host's own "this line is current now" callback.
 *
 * **One optional role, resolved on its own.** A build that moved `K#b` loses the `line-change`
 * line and nothing else: the spring beside it still installs, still takes moves over and still
 * reports. That is why this class never throws and never feeds the spring's all-or-nothing
 * [LyricLineSpringRoles] — the two contracts are deliberately separate.
 *
 * The role may be declared in either place, first match wins:
 *
 *  * `lyricLineMotion.interlude.methods.lineChange` — where a profile that already pinned the
 *    change pass would put it, and
 *  * `lyricLineMotion.trace.methods.lineChange` — the section this diagnostic added for 7.0.3.
 *
 * 1626's member is `com.apple.android.music.player.fragment.K#b(int,int): void`: an instance
 * method on a small helper that holds the lyrics fragment, called from the two lyric adapters'
 * commit paths (`A#P` / `V0#P`) and from two observer bridges, i.e. once per submitted line page —
 * before the layout pass that produces the `animateMove` calls this module takes over.
 */
internal class LyricLineTraceRoles(private val loader: ClassLoader, motion: JSONObject) {
    private val failures = LinkedHashMap<String, String>()

    /** The host's line-change callback, or null when this build does not have it. */
    val lineChange: Method? = optional(
        "lineChange",
        methodsOf(motion, "interlude"),
        methodsOf(motion, "trace"),
    )

    /**
     * The lyrics fragment the callback belongs to, or null.
     *
     * `K#b` is an instance method on a one-field helper (`fragment.K.a`), so the only way to the
     * lyric list — and therefore to the rows a bare layout jump has to be read from — is through
     * that field. Optional on its own: without it the `line-change` line still prints and the
     * spring still runs, only the jumps the host never offered are missed.
     */
    val fragment: java.lang.reflect.Field? = optionalField(
        "fragment",
        fieldsOf(motion, "trace"),
        fieldsOf(motion, "interlude"),
    )

    /** Roles that did not resolve, with the reason; empty when the trace is usable. */
    fun missing(): List<String> = failures.map { (role, reason) -> role + ": " + reason }

    /** True when the one line this diagnostic needs is available. */
    fun isUsable(): Boolean = lineChange != null

    // ---- resolution ------------------------------------------------------------------------

    private fun methodsOf(motion: JSONObject, section: String): JSONObject? = runCatching {
        motion.getJSONObject(section).getJSONObject("methods")
    }.getOrNull()

    private fun fieldsOf(motion: JSONObject, section: String): JSONObject? = runCatching {
        motion.getJSONObject(section).getJSONObject("fields")
    }.getOrNull()

    /**
     * Resolve one optional role from the first inventory that declares it.
     *
     * A role nobody declares is not a failure of this build — it is a profile without the trace —
     * so the reason is still recorded (it is what the one `trace unavailable` line prints) but
     * nothing else is affected.
     */
    private fun optional(role: String, vararg inventories: JSONObject?): Method? = runCatching {
        var contract: JSONObject? = null
        for (inventory in inventories) {
            if (contract != null) break
            contract = inventory?.optJSONObject(role)
        }
        resolve(requireNotNull(contract) { role + " is not declared in this profile" })
    }.getOrElse {
        failures[role] = it.message ?: it.javaClass.simpleName
        null
    }

    /** The field twin of [optional]: first inventory that declares it wins, and null is not a failure. */
    private fun optionalField(role: String, vararg inventories: JSONObject?): java.lang.reflect.Field? =
        runCatching {
            var contract: JSONObject? = null
            for (inventory in inventories) {
                if (contract != null) break
                contract = inventory?.optJSONObject(role)
            }
            val declared = requireNotNull(contract) { role + " is not declared in this profile" }
            val owner = classNamed(declared.getString("owner"))
            val member = owner.getDeclaredField(declared.getString("name"))
            check(member.type == classNamed(declared.getString("type"))) {
                "descriptor mismatch: " + member
            }
            check(!Modifier.isStatic(member.modifiers)) { "descriptor mismatch: " + member }
            member.isAccessible = true
            member
        }.getOrElse { null }

    private fun resolve(contract: JSONObject): Method {
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

    private fun classNamed(name: String): Class<*> = when (name) {
        "void" -> Void.TYPE
        "boolean" -> java.lang.Boolean.TYPE
        "int" -> Integer.TYPE
        "long" -> java.lang.Long.TYPE
        "float" -> java.lang.Float.TYPE
        else -> loader.loadClass(name)
    }
}
