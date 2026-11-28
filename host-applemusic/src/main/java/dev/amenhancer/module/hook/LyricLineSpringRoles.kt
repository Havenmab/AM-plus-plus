package dev.amenhancer.module.hook

import org.json.JSONObject
import java.lang.reflect.Field
import java.lang.reflect.Method
import java.lang.reflect.Modifier

/**
 * 「换行弹簧」's member inventory for Apple Music 7.0.3, resolved from the version profile.
 *
 * Unlike the probe, this one is **all-or-nothing** for the roles it cannot animate without: answering
 * an `animateMove` without being able to finish it leaves RecyclerView's pending-move ledger
 * permanently open (the row is never dispatched as finished, and `isRunning()` never settles), which
 * is a hang rather than a wrong look. So every such role is resolved here, [missing] reports each
 * failure with its reason, and the target installs nothing at all unless [isComplete].
 *
 * One role is deliberately outside that wall: [rowSettle] is the host's own per-row notification to
 * its adapter. Losing it costs that callback and nothing else the spring does, so it is reported
 * through [optionalMissing] and the spring installs anyway.
 *
 * Two shapes are pinned beyond the descriptors, because both fail *silently*:
 *
 *  * the four **hooked** members must be declared on the animator class itself. 1626 implements
 *    `isRunning`/`endAnimation`/`endAnimations` on `mc.y` and leaves the same names abstract on
 *    `RecyclerView$k`; a role pinned to the abstract declaration resolves through
 *    `getDeclaredMethod` and then never fires;
 *  * the **called** members must be reachable from the animator instance
 *    (`declaringClass.isAssignableFrom(animator)`), which is how the hook gets to them at all;
 *    the one optional role is checked the other way round, because it is declared on the lyric
 *    animator's *subclass* and invoked on the instance.
 */
internal class LyricLineSpringRoles(
    private val loader: ClassLoader,
    private val section: JSONObject,
) {
    private val failures = LinkedHashMap<String, String>()
    private val optionalFailures = LinkedHashMap<String, String>()
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

    /**
     * `PlayerLyricsViewFragment$u#s(ViewHolder): void` — the host's own "this row's move is over".
     *
     * A real 1626 move announces itself from the animator's per-row listeners (`mc.y#j` builds
     * them; the base class's `androidx.recyclerview.widget.h` does the same for the other
     * families), and the lyrics animator's override of that dispatch forwards to its adapter:
     * `$u#s(holder)` → `$u.z` (the fragment) → `i1#N(holder)`. A takeover that answers
     * `animateMove` without ever animating skips those listeners, so the spring replays the
     * announcement when the row really comes to rest — see `LyricLineSpringWave.onSettled`.
     *
     * **Optional, unlike every role above.** A spring that runs and cannot announce its rest is
     * still a correct spring: RecyclerView's ledger is closed by [dispatchAnimationFinished]. Only
     * this one notification is lost, so the failure is reported through [optionalMissing] and never
     * fails [isComplete].
     */
    val rowSettle: Method? = optional("rowSettle")

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

    /** Roles that resolved, for the install message. */
    fun resolved(): List<String> = resolvedRoles.toList()

    /** Roles that did not, with the reason. Non-empty means "install nothing". */
    fun missing(): List<String> = failures.map { (role, reason) -> role + ": " + reason }

    /**
     * Optional roles that did not resolve, with the reason: "lose this one callback", not "install
     * nothing".
     *
     * Reported once at install time, because the interesting fact on a device is that the spring is
     * running *without* the host's own per-row notification: an animation that looks right while
     * something the host normally does per row simply does not happen.
     */
    fun optionalMissing(): List<String> =
        optionalFailures.map { (role, reason) -> role + ": " + reason }

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

    /**
     * A member we invoke on the animator instance but that the feature survives without.
     *
     * Two differences from [called]: a failure lands in [optionalFailures] instead of [failures]
     * (so the install proceeds), and the member may live **above or below** the animator rather than
     * only above it — 1626's row notification is declared on `PlayerLyricsViewFragment$u`, a
     * subclass of the animator `mc.y`, and invoked on the instance (its superclass declaration
     * `F#s` would work just as well, by virtual dispatch). Being declared on a subclass is only
     * sound for a real instance of that subclass, which the caller guarantees by taking moves over
     * for the lyric list's own animator and re-checks per call; a mismatch throws inside the
     * caller's `runCatching` and costs the one notification, never the animation.
     */
    private fun optional(role: String): Method? = runCatching {
        val member = resolveMethod(role)
        val animator = animatorClass
        if (
            animator != null &&
            !member.declaringClass.isAssignableFrom(animator) &&
            !animator.isAssignableFrom(member.declaringClass)
        ) {
            error(
                member.declaringClass.name + " is neither " + animator.name + ", a subclass of it, nor " +
                    "one of its ancestors (" + member + ")",
            )
        }
        member
    }.getOrElse {
        abandonOptional("methods." + role, it)
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

    /** The same, for a role the feature can run without: it only loses this one callback. */
    private fun abandonOptional(role: String, error: Throwable) {
        optionalFailures[role] = error.message ?: error.javaClass.simpleName
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
