package dev.amenhancer.module.hook

import org.json.JSONObject
import java.lang.reflect.Field
import java.lang.reflect.Method
import java.lang.reflect.Modifier

/**
 * The line-change probe's member inventory, resolved **role by role**.
 *
 * Every other contract in this module is all-or-nothing: a single missing member fails the whole
 * install, because half a feature is worse than none. The probe is the opposite — it is a set of
 * independent observations, so one role that this build does not have must cost exactly that one
 * log line and nothing else. Each role therefore resolves inside its own runCatching, a failure is
 * recorded in [missing], and the caller installs only the hooks whose role came back non-null.
 *
 * The declared shape is still checked exactly (owner, name, parameter types, return type,
 * static-ness, field type), so a role can never silently bind to a different member of the same
 * name.
 */
internal class LyricLineProbeRoles(
    private val loader: ClassLoader,
    private val section: JSONObject,
) {
    private val failures = LinkedHashMap<String, String>()
    private val resolvedRoles = ArrayList<String>()

    // ---- classes ---------------------------------------------------------------------------

    /** PlayerLyricsViewFragment$w: the lyrics list manager, used to ignore every other list. */
    val layoutManagerClass: Class<*>? = type("layoutManager")

    /** q8.m9: the instrumental row's binding, i.e. the host's own gate inside A#N / V0#N. */
    val instrumentalBinding: Class<*>? = type("instrumentalBinding")

    // ---- methods ---------------------------------------------------------------------------

    /** PlayerLyricsViewFragment$K#b(int,int): the line-change callback (start,end). */
    val lineChange: Method? = method("lineChange")

    /** LinearLayoutManager#u1(int,int): scrollToPositionWithOffset, the instant re-anchor. */
    val scrollToOffset: Method? = method("scrollToOffset")

    /** mc.y#m(ViewHolder,int,int,int,int): animateMove, the host's zero-displacement branch. */
    val animateMove: Method? = method("animateMove")

    /** PlayerLyricsViewFragment#s2(): the deferred-queue flush. */
    val flushDeferred: Method? = method("flushDeferred")

    /** com.apple.android.music.player.A#N(ViewHolder): the row transition of the word adapter. */
    val lineTransition: Method? = method("lineTransition")

    /** com.apple.android.music.player.V0#N(ViewHolder): the same entry on the line adapter. */
    val lineTransitionLineAdapter: Method? = method("lineTransitionLineAdapter")

    /** LinearLayoutManager#e1(): findFirstVisibleItemPosition. */
    val firstVisible: Method? = method("firstVisible")

    /** LinearLayoutManager#f1(): findLastVisibleItemPosition. */
    val lastVisible: Method? = method("lastVisible")

    /** RecyclerView$D#d(): the holder's layout position. */
    val holderPosition: Method? = method("holderPosition")

    // ---- fields ----------------------------------------------------------------------------

    /** K.a: the fragment the line-change callback belongs to. */
    val lineChangeFragment: Field? = field("lineChangeFragment")

    /** PlayerLyricsViewFragment.q0: the lyrics layout manager instance. */
    val layoutManager: Field? = field("layoutManager")

    /** PlayerLyricsViewFragment.V0: the deferred row transitions (a LinkedList of Runnables). */
    val deferredQueue: Field? = field("deferredQueue")

    /** PlayerLyricsViewFragment.T0: the host has taken the scroll away from the user. */
    val scrollOwner: Field? = field("scrollOwner")

    /** PlayerLyricsViewFragment.U0: the deferred queue is being drained. */
    val deferredDrain: Field? = field("deferredDrain")

    /** PlayerLyricsViewFragment.M0: the current line has been anchored once. */
    val anchored: Field? = field("anchored")

    /** PlayerLyricsViewFragment$u.z: the animator's fragment, for the move hook. */
    val animatorFragment: Field? = field("animatorFragment")

    /** i1$b.u: a holder's ViewDataBinding. */
    val holderBinding: Field? = field("holderBinding")

    /** RecyclerView$D.a: a holder's itemView. */
    val holderItemView: Field? = field("holderItemView")

    /** Roles that resolved, for the install message. */
    fun resolved(): List<String> = resolvedRoles.toList()

    /** Roles that did not, with the reason; each one only costs its own log line. */
    fun missing(): List<String> = failures.map { (role, reason) -> role + ": " + reason }

    // ---- resolution ------------------------------------------------------------------------

    private fun type(role: String): Class<*>? = runCatching {
        val name = section.getJSONObject("classes").getString(role)
        check(name.isNotBlank()) { "empty class role" }
        loader.loadClass(name).also { resolvedRoles += "classes." + role }
    }.getOrElse {
        failures["classes." + role] = it.message ?: it.javaClass.simpleName
        null
    }

    /** A JDK/Android type name from inside a descriptor; primitives are spelled as in the profile. */
    private fun classNamed(name: String): Class<*> = when (name) {
        "void" -> Void.TYPE
        "boolean" -> java.lang.Boolean.TYPE
        "int" -> Integer.TYPE
        "long" -> java.lang.Long.TYPE
        "float" -> java.lang.Float.TYPE
        else -> loader.loadClass(name)
    }

    private fun method(role: String): Method? = runCatching {
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
        resolvedRoles += "methods." + role
        member
    }.getOrElse {
        failures["methods." + role] = it.message ?: it.javaClass.simpleName
        null
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
        failures["fields." + role] = it.message ?: it.javaClass.simpleName
        null
    }
}
