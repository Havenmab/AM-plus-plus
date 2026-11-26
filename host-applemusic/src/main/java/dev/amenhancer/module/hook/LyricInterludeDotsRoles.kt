package dev.amenhancer.module.hook

import org.json.JSONObject
import java.lang.reflect.Field
import java.lang.reflect.Method
import java.lang.reflect.Modifier

/**
 * 「间奏三圆点」's member inventory for Apple Music 7.0.3, resolved from the version profile.
 *
 * **All-or-nothing, but optional.** The spring beside it must not install half a contract, because
 * answering a move without being able to finish it leaves RecyclerView's ledger open. This one is
 * the opposite: it only *reads* rows the host already laid out and writes one `alpha` per dot, so a
 * missing role is a missed nicety, never a hang. [missing] still names every failure and the target
 * installs nothing when [isComplete] is false — the difference is that the spring keeps working.
 *
 * The two shapes pinned beyond the descriptors are the ones that fail silently:
 *
 *  * the five hooked members are the implementations on the adapters (and the fragment's line
 *    callback), so a role pinned to a base-class declaration would resolve and never fire;
 *  * the binding and position fields must be declared on the classes the holder's own type walks
 *    (the holder carries the binding, the dots are fields of the binding).
 */
internal class LyricInterludeDotsRoles(
    private val loader: ClassLoader,
    private val section: JSONObject,
) {
    private val failures = LinkedHashMap<String, String>()
    private val resolvedRoles = ArrayList<String>()

    // ---- classes ---------------------------------------------------------------------------

    /** `q8.m9`: the empty interlude row whose whole content is three dots. */
    val instrumentalBinding: Class<*>? = type("instrumentalBinding")

    // ---- methods we hook --------------------------------------------------------------------

    /** `K#b(int,int)`: the line change itself, i.e. the new current line index. */
    val lineChange: Method? = hooked("lineChange")

    /** `A#l(ViewHolder,int)` / `V0#l(ViewHolder,int)`: a row was bound — its dots are visible. */
    val wordBind: Method? = hooked("wordBind")
    val lineBind: Method? = hooked("lineBind")

    /** `A#H(ViewHolder)` / `V0#H(ViewHolder)`: a zero-displacement row settled. */
    val wordSettled: Method? = hooked("wordSettled")
    val lineSettled: Method? = hooked("lineSettled")

    // ---- fields -----------------------------------------------------------------------------

    /** `i1$b.u`: the row's `ViewDataBinding`, i.e. how a holder says which row it is. */
    val holderBinding: Field? = field("holderBinding")

    /** `RecyclerView$D.d()`: the row's layout position — the only thing that says "past" here. */
    val holderPosition: Method? = called("holderPosition")

    /** The three dots of `q8.m9`; only their alpha is ever written. */
    val dot1: Field? = field("dot1")
    val dot2: Field? = field("dot2")
    val dot3: Field? = field("dot3")

    fun resolved(): List<String> = resolvedRoles.toList()

    fun missing(): List<String> = failures.map { (role, reason) -> role + ": " + reason }

    fun isComplete(): Boolean = failures.isEmpty() &&
        instrumentalBinding != null &&
        listOfNotNull(lineChange, wordBind, lineBind, wordSettled, lineSettled).size == 5 &&
        listOfNotNull(holderBinding, holderPosition, dot1, dot2, dot3).size == 5

    // ---- resolution ------------------------------------------------------------------------

    private fun hooked(role: String): Method? = runCatching {
        val member = resolveMethod(role)
        resolvedRoles += "methods." + role
        member
    }.getOrElse {
        abandon("methods." + role, it)
        null
    }

    private fun called(role: String): Method? = runCatching {
        val member = resolveMethod(role)
        resolvedRoles += "methods." + role
        member
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
