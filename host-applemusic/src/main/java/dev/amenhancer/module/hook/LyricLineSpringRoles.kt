package dev.amenhancer.module.hook

import org.json.JSONObject
import java.lang.reflect.Field
import java.lang.reflect.Method
import java.lang.reflect.Modifier

/**
 * 「换行弹簧」's member inventory for Apple Music 7.0.3, resolved from the version profile.
 *
 * Unlike the probe, this one is **all-or-nothing**: answering an `animateMove` without being able to
 * finish it leaves RecyclerView's pending-move ledger permanently open (the row is never dispatched
 * as finished, and `isRunning()` never settles), which is a hang rather than a wrong look. So every
 * role is resolved here, [missing] reports each failure with its reason, and the target installs
 * nothing at all unless [isComplete].
 *
 * Two shapes are pinned beyond the descriptors, because both fail *silently*:
 *
 *  * the four **hooked** members must be declared on the animator class itself. 1626 implements
 *    `isRunning`/`endAnimation`/`endAnimations` on `mc.y` and leaves the same names abstract on
 *    `RecyclerView$k`; a role pinned to the abstract declaration resolves through
 *    `getDeclaredMethod` and then never fires;
 *  * the **called** members must be reachable from the animator instance
 *    (`declaringClass.isAssignableFrom(animator)`), which is how the hook gets to them at all.
 */
internal class LyricLineSpringRoles(
    private val loader: ClassLoader,
    private val section: JSONObject,
) {
    private val failures = LinkedHashMap<String, String>()
    private val resolvedRoles = ArrayList<String>()

    // ---- classes ---------------------------------------------------------------------------

    /** `mc.y`: the animator base whose implementations RecyclerView dispatches to. */
    val animatorClass: Class<*>? = type("animator")

    /** `RecyclerView$D`: the view holder every role in this contract speaks about. */
    val holderClass: Class<*>? = type("holder")

    // ---- methods we hook --------------------------------------------------------------------

    /** `mc.y#m(ViewHolder,int,int,int,int): boolean` — animateMove. */
    val animateMove: Method? = hooked("animateMove") { member ->
        val holder = holderClass
        if (holder != null && member.parameterTypes.firstOrNull() != holder) {
            "first parameter is " + member.parameterTypes.firstOrNull() + ", not " + holder.name
        } else {
            null
        }
    }

    /** `mc.y#g(): boolean` — isRunning. */
    val isRunning: Method? = hooked("isRunning")

    /** `mc.y#e(ViewHolder): void` — endAnimation, i.e. recycle / reuse / cancel. */
    val endAnimation: Method? = hooked("endAnimation")

    /** `mc.y#f(): void` — endAnimations. */
    val endAnimations: Method? = hooked("endAnimations")

    // ---- methods we call --------------------------------------------------------------------

    /**
     * `F#q(ViewHolder): void` — SimpleItemAnimator's `dispatchMoveStarting`.
     *
     * This is the "started" notification a real 1626 move emits:
     * `androidx.recyclerview.widget.j#onAnimationStart` (the move animator's listener) calls it.
     * `ItemAnimator.dispatchAnimationStarted` itself does not exist in this build — it was an empty
     * final method and R8 removed it.
     */
    val dispatchMoveStarting: Method? = called("dispatchMoveStarting")

    /** `RecyclerView$k#c(ViewHolder): void` — ItemAnimator's `dispatchAnimationFinished`. */
    val dispatchAnimationFinished: Method? = called("dispatchAnimationFinished")

    // ---- fields -----------------------------------------------------------------------------

    /** `RecyclerView$D.a`: the row's itemView, i.e. the View whose translationY we own. */
    val holderItemView: Field? = field("holderItemView") { member ->
        val holder = holderClass
        if (holder != null && member.declaringClass != holder) {
            "declared on " + member.declaringClass.name + ", not on " + holder.name
        } else {
            null
        }
    }

    /** `PlayerLyricsViewFragment$u.z`: identifies *the lyrics* animator, not any `mc.y` instance. */
    val animatorFragment: Field? = field("animatorFragment")

    // ---- the roster (optional, read-only) ---------------------------------------------------
    //
    // The line change this feature replays is a *bare layout jump*: 1626 settles it with
    // `scrollToPositionWithOffset` + `requestLayout`, and nothing announces it with an
    // `animateMove`. Seeing it needs the rows themselves, and the rows are the list's children, so
    // these two fields lead from the lyrics fragment to the RecyclerView. They are **optional**: a
    // build without them keeps the spring that `animateMove` alone can drive, and loses only the
    // jumps the host never offered.

    /** `PlayerLyricsViewFragment.l0: q8.X4` — the DataBinding root holding the list. */
    val binding: Field? = optionalField("binding")

    /** `q8.X4.d0: androidx.recyclerview.widget.RecyclerView` — the lyric list itself. */
    val recycler: Field? = optionalField("recycler")

    /** Roles that resolved, for the install message. */
    fun resolved(): List<String> = resolvedRoles.toList()

    /** Roles that did not, with the reason. Non-empty means "install nothing". */
    fun missing(): List<String> = failures.map { (role, reason) -> role + ": " + reason }

    /** True when every role this contract needs is present and correctly shaped. */
    fun isComplete(): Boolean = failures.isEmpty() &&
        listOfNotNull(animatorClass, holderClass).size == 2 &&
        listOfNotNull(
            animateMove, isRunning, endAnimation, endAnimations,
            dispatchMoveStarting, dispatchAnimationFinished,
        ).size == 6 &&
        listOfNotNull(holderItemView, animatorFragment).size == 2

    // ---- resolution ------------------------------------------------------------------------

    private fun reachable(declaring: Class<*>, member: Any): String? {
        val animator = animatorClass ?: return null
        return if (declaring.isAssignableFrom(animator)) {
            null
        } else {
            declaring.name + " does not own " + animator.name + " (" + member + ")"
        }
    }

    /** A member we intercept: it must be the implementation, not an inherited declaration. */
    private fun hooked(role: String, verify: (Method) -> String? = { null }): Method? = runCatching {
        val member = resolveMethod(role)
        val animator = animatorClass
        if (animator != null && member.declaringClass != animator) {
            error(member.declaringClass.name + " declares it, but the hook must sit on " + animator.name +
                " or it will never fire")
        }
        verify(member)?.let { error(it) }
        member
    }.getOrElse {
        abandon("methods." + role, it)
        null
    }

    /** A member we invoke on the animator instance; reflection dispatches to the override. */
    private fun called(role: String): Method? = runCatching {
        val member = resolveMethod(role)
        reachable(member.declaringClass, member)?.let { error(it) }
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
        resolvedRoles += "methods." + role
        return member
    }

    /**
     * A role this contract may use but does not need.
     *
     * Resolved exactly like [field], except that a missing or misshapen member is *not* recorded as
     * a failure: [isComplete] keeps passing and the spring keeps installing. Only what the role adds
     * is lost, and the platform degrades one role at a time (see the section's evidence).
     */
    private fun optionalField(role: String): Field? = runCatching {
        val contract = section.getJSONObject("fields").getJSONObject(role)
        val owner = classNamed(contract.getString("owner"))
        val member = owner.getDeclaredField(contract.getString("name"))
        check(member.type == classNamed(contract.getString("type"))) { "descriptor mismatch: " + member }
        check(!Modifier.isStatic(member.modifiers)) { "descriptor mismatch: " + member }
        member.isAccessible = true
        resolvedRoles += "fields." + role
        member
    }.getOrElse { null }

    private fun field(role: String, verify: (Field) -> String? = { null }): Field? = runCatching {
        val contract = section.getJSONObject("fields").getJSONObject(role)
        val owner = classNamed(contract.getString("owner"))
        val member = owner.getDeclaredField(contract.getString("name"))
        check(member.type == classNamed(contract.getString("type"))) { "descriptor mismatch: " + member }
        check(!Modifier.isStatic(member.modifiers)) { "descriptor mismatch: " + member }
        member.isAccessible = true
        verify(member)?.let { error(it) }
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

    /** Record the reason and stop claiming the role resolved. */
    private fun abandon(role: String, error: Throwable) {
        failures[role] = error.message ?: error.javaClass.simpleName
        resolvedRoles.remove(role)
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
}
