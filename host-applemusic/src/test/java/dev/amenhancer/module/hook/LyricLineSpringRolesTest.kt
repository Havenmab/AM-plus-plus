package dev.amenhancer.module.hook

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The spring contract's one design rule: all roles or none, and the two silent-failure shapes.
 *
 * The fake hierarchy mirrors Apple Music 7.0.3's real one closely enough to exercise the trap that
 * matters: the animator *implements* isRunning/endAnimation/endAnimations while the RecyclerView base
 * class only *declares* them, so a role pinned to the base would resolve and then never fire.
 */
class LyricLineSpringRolesTest {
    open class ItemAnimatorBase {
        open fun isRunning(): Boolean = false

        open fun endAnimation(holder: Holder) = Unit

        open fun endAnimations() = Unit

        fun dispatchAnimationFinished(holder: Holder) = Unit

        /** 1626: `RecyclerView$k#d`, `dispatchAnimationsFinished()` — the fallback dispatch. */
        fun dispatchAnimationsFinished() = Unit
    }

    open class SimpleItemAnimatorBase : ItemAnimatorBase() {
        fun dispatchMoveStarting(holder: Holder) = Unit

        /**
         * The inherited shape of the row notification (1626: `androidx.recyclerview.widget.F#s`).
         *
         * The real animator overrides it, so pinning the base declaration would also work — the
         * optional role accepts a member declared above *or* below the animator.
         */
        open fun settle(holder: Holder) = Unit

        open fun animateMove(holder: Holder, fromX: Int, fromY: Int, toX: Int, toY: Int): Boolean = true
    }

    class LyricsAnimator : SimpleItemAnimatorBase() {
        @JvmField
        var fragment: Any? = null

        /**
         * The host's own per-row "this move is over" (1626: `PlayerLyricsViewFragment$u#s`).
         *
         * Final, like the real one: the obfuscated override is public final, which is exactly why
         * reflection on this class finds it instead of the base declaration.
         */
        override fun settle(holder: Holder) = Unit

        /**
         * "This animator has nothing left to animate" (1626: `mc.y#u`).
         *
         * Declared here, on the animator, because 1626's `PlayerLyricsViewFragment$u` does *not*
         * override `u()` — it declares only `b/i/p/q/r/s` — so the animator's own implementation is
         * the one the lyrics animator inherits and the host itself calls.
         */
        fun dispatchFinished() = Unit

        override fun animateMove(holder: Holder, fromX: Int, fromY: Int, toX: Int, toY: Int): Boolean = true

        override fun isRunning(): Boolean = false

        override fun endAnimation(holder: Holder) = Unit

        override fun endAnimations() = Unit
    }

    class Holder {
        @JvmField
        var itemView: Any? = null
    }

    private val animator = LyricsAnimator::class.java.name
    private val simple = SimpleItemAnimatorBase::class.java.name
    private val base = ItemAnimatorBase::class.java.name
    private val holder = Holder::class.java.name

    private fun method(owner: String, name: String, parameters: String, returns: String, static: Boolean = false) =
        "{\"owner\":\"" + owner + "\",\"name\":\"" + name + "\",\"parameters\":[" + parameters +
            "],\"returns\":\"" + returns + "\",\"static\":" + static + "}"

    private fun field(owner: String, name: String, type: String) =
        "{\"owner\":\"" + owner + "\",\"name\":\"" + name + "\",\"type\":\"" + type + "\"}"

    /** The real 1626 shape, with the fake class names. */
    private fun section(
        moveOwner: String = animator,
        runningOwner: String = animator,
        endOwner: String = animator,
        endAllOwner: String = animator,
        itemViewOwner: String = holder,
        moveParameters: String = "\"" + holder + "\",\"int\",\"int\",\"int\",\"int\"",
        /** The optional row notification; `null` leaves the role out of the section entirely. */
        rowSettle: String? = method(animator, "settle", "\"" + holder + "\"", "void"),
        /** The optional "nothing left to animate" dispatch (1626: `mc.y#u`). */
        finishDispatch: String? = method(animator, "dispatchFinished", "", "void"),
        /** The optional unconditional dispatch (1626: `RecyclerView$k#d`). */
        finishDispatchFallback: String? = method(base, "dispatchAnimationsFinished", "", "void"),
    ): String = "{" +
        "\"contractVersion\":1," +
        "\"classes\":{\"animator\":\"" + animator + "\",\"holder\":\"" + holder + "\"}," +
        "\"methods\":{" +
        "\"animateMove\":" + method(moveOwner, "animateMove", moveParameters, "boolean") + "," +
        "\"isRunning\":" + method(runningOwner, "isRunning", "", "boolean") + "," +
        "\"endAnimation\":" + method(endOwner, "endAnimation", "\"" + holder + "\"", "void") + "," +
        "\"endAnimations\":" + method(endAllOwner, "endAnimations", "", "void") + "," +
        "\"dispatchMoveStarting\":" + method(simple, "dispatchMoveStarting", "\"" + holder + "\"", "void") + "," +
        "\"dispatchAnimationFinished\":" + method(base, "dispatchAnimationFinished", "\"" + holder + "\"", "void") +
        (rowSettle?.let { ",\"rowSettle\":" + it } ?: "") +
        (finishDispatch?.let { ",\"finishDispatch\":" + it } ?: "") +
        (finishDispatchFallback?.let { ",\"finishDispatchFallback\":" + it } ?: "") +
        "}," +
        "\"fields\":{" +
        "\"holderItemView\":" + field(itemViewOwner, "itemView", "java.lang.Object") + "," +
        "\"animatorFragment\":" + field(animator, "fragment", "java.lang.Object") +
        "}}"

    private fun roles(section: String) = LyricLineSpringRoles(Holder::class.java.classLoader!!, JSONObject(section))

    @Test
    fun `the sixteenth-twenty-six shape resolves every role`() {
        val roles = roles(section())

        assertTrue(roles.missing().toString(), roles.isComplete())
        assertNotNull(roles.animateMove)
        assertNotNull(roles.isRunning)
        assertNotNull(roles.endAnimation)
        assertNotNull(roles.endAnimations)
        assertNotNull(roles.dispatchMoveStarting)
        assertNotNull(roles.dispatchAnimationFinished)
        assertNotNull(roles.holderItemView)
        assertNotNull(roles.animatorFragment)
        // The host's own per-row notification, declared on the animator's own class (1626: `$u#s`).
        assertNotNull(roles.rowSettle)
        // The borrowed isRunning() answer (1626: `mc.y#u`) and the unconditional dispatch behind it.
        assertNotNull(roles.finishDispatch)
        assertNotNull(roles.finishDispatchFallback)
        assertTrue(roles.optionalMissing().toString(), roles.optionalMissing().isEmpty())
        // Two classes, nine methods, two fields. The animator's move duration is deliberately
        // absent: the tempo is AMLL's fixed spring, so nothing reads that field.
        assertEquals(13, roles.resolved().size)
        assertEquals(Boolean::class.javaPrimitiveType, roles.animateMove?.returnType)
    }

    /**
     * The borrowed `isRunning()` answer is optional in both shapes, and the two degrade on their
     * own: a build without the animator's own `u()` still has the dispatch behind it, and a build
     * with neither keeps the spring and only loses the hand-back.
     */
    @Test
    fun `the finish dispatch degrades one role at a time`() {
        // Only the unconditional dispatch is gone: the primary still resolves.
        val noFallback = roles(section(finishDispatchFallback = null))
        assertTrue(noFallback.isComplete())
        assertNotNull(noFallback.finishDispatch)
        assertNull(noFallback.finishDispatchFallback)
        assertTrue(
            noFallback.optionalMissing().toString(),
            noFallback.optionalMissing().any { it.startsWith("methods.finishDispatchFallback:") },
        )

        // Only the animator's own u() is gone: the fallback is still there to hand the answer back.
        val noPrimary = roles(section(finishDispatch = null))
        assertTrue(noPrimary.isComplete())
        assertNull(noPrimary.finishDispatch)
        assertNotNull(noPrimary.finishDispatchFallback)

        // Neither: the spring installs and the hand-back is the only thing lost.
        val neither = roles(section(finishDispatch = null, finishDispatchFallback = null))
        assertTrue(neither.missing().toString(), neither.isComplete())
        assertNull(neither.finishDispatch)
        assertNull(neither.finishDispatchFallback)
        assertEquals(
            setOf("methods.finishDispatch", "methods.finishDispatchFallback"),
            neither.optionalMissing().map { it.substringBefore(":") }.toSet(),
        )
        assertNull(neither.optionalFailure("rowSettle")) // the row notification is unaffected
        assertNotNull(neither.optionalFailure("finishDispatch"))
    }

    /**
     * A dispatch pinned on a class the animator is unrelated to would resolve and then throw on
     * every hand-back: refused, and that role alone is lost.
     */
    @Test
    fun `a finish dispatch off the animator lineage is refused`() {
        val roles = roles(
            section(
                finishDispatch = method(holder, "dispatchAnimationsFinished", "", "void"),
                finishDispatchFallback = method(holder, "dispatchAnimationsFinished", "", "void"),
            ),
        )

        assertTrue(roles.isComplete())
        assertNull(roles.finishDispatch)
        assertNull(roles.finishDispatchFallback)
        assertEquals(
            setOf("methods.finishDispatch", "methods.finishDispatchFallback"),
            roles.optionalMissing().map { it.substringBefore(":") }.toSet(),
        )
    }

    /**
     * Where the row notification may be pinned: on the animator itself (1626), inherited from an
     * ancestor, or declared on a subclass — all three are invocable on the instance.
     */
    @Test
    fun `the row notification may be declared on the animator or on its ancestry`() {
        listOf(
            method(animator, "settle", "\"" + holder + "\"", "void"),
            method(simple, "settle", "\"" + holder + "\"", "void"),
        ).forEach { contract ->
            val roles = roles(section(rowSettle = contract))
            assertNotNull(roles.rowSettle)
            assertTrue(roles.optionalMissing().toString(), roles.optionalMissing().isEmpty())
            assertTrue(roles.isComplete())
        }
    }

    /**
     * The one role outside the all-or-nothing wall: without the host's row notification the spring
     * still animates every row and RecyclerView's ledger is still closed, so the install must go on
     * and only the callback is lost — reported once, on its own list.
     */
    @Test
    fun `a missing row-settle role costs the callback and nothing else`() {
        listOf(
            // Not declared at all (an older or a differently obfuscated build).
            section(rowSettle = null),
            // Declared, but the name no longer resolves.
            section(rowSettle = method(animator, "rowSettled", "\"" + holder + "\"", "void")),
            // Declared on a class that is neither the animator nor one of its subclasses: invoking
            // it on the animator instance could only throw.
            section(rowSettle = method(holder, "settle", "\"" + holder + "\"", "void")),
            // Right class, wrong shape: the adapter notification takes the holder and returns void.
            section(rowSettle = method(animator, "settle", "", "boolean")),
        ).forEach { json ->
            val roles = roles(json)
            assertTrue(roles.missing().toString(), roles.isComplete())
            assertTrue(roles.missing().isEmpty())
            assertNull(roles.rowSettle)
            assertTrue(
                roles.optionalMissing().toString(),
                roles.optionalMissing().any { it.startsWith("methods.rowSettle:") },
            )
            // Everything the animation itself needs is still there, and so is the other optional
            // pair (this list only ever removes the row notification).
            assertNotNull(roles.animateMove)
            assertNotNull(roles.dispatchAnimationFinished)
            assertEquals(12, roles.resolved().size)
        }
    }

    /**
     * The 1626 trap: isRunning/endAnimation/endAnimations are abstract on the RecyclerView base and
     * implemented on the animator. A role pinned to the base resolves, never fires, and would leave
     * RecyclerView convinced the list is idle.
     */
    @Test
    fun `a hooked role declared on an ancestor is refused`() {
        listOf(
            section(runningOwner = base),
            section(endOwner = base),
            section(endAllOwner = base),
            section(moveOwner = simple),
        ).forEach { json ->
            val roles = roles(json)
            assertFalse(roles.isComplete())
            val missing = roles.missing().toString()
            assertTrue(missing, missing.contains("will never fire"))
        }

        val roles = roles(section(endOwner = base))
        assertNull(roles.endAnimation)
        assertTrue(roles.missing().any { it.startsWith("methods.endAnimation:") })
        // Everything else still resolved: the report is per role, even though the install is not.
        assertNotNull(roles.animateMove)
        assertNotNull(roles.dispatchAnimationFinished)
    }

    @Test
    fun `an itemView declared on the wrong class is refused`() {
        val wrongView = roles(section(itemViewOwner = animator))
        assertFalse(wrongView.isComplete())
        assertTrue(wrongView.missing().any { it.startsWith("fields.holderItemView:") })

        val wrongParameter = roles(section(moveParameters = "\"int\",\"int\",\"int\",\"int\",\"int\""))
        assertFalse(wrongParameter.isComplete())
        assertTrue(wrongParameter.missing().any { it.startsWith("methods.animateMove:") })
    }

    @Test
    fun `a missing member or a wrong descriptor only fails its own role`() {
        val json = "{\"contractVersion\":1," +
            "\"classes\":{\"animator\":\"" + animator + "\",\"holder\":\"" + holder + "\"}," +
            "\"methods\":{" +
            "\"animateMove\":" + method(animator, "animateMove", "\"" + holder + "\",\"int\",\"int\",\"int\",\"int\"", "int") + "," +
            "\"isRunning\":" + method(animator, "isRunning", "", "boolean") +
            "}," +
            "\"fields\":{\"holderItemView\":" + field(holder, "itemView", "int") + "}}"
        val roles = roles(json)

        // Wrong return type, wrong field type, absent roles.
        assertNull(roles.animateMove)
        assertNotNull(roles.isRunning)
        assertNull(roles.holderItemView)
        assertNull(roles.dispatchAnimationFinished)
        assertFalse(roles.isComplete())
        assertTrue(roles.missing().any { it.startsWith("methods.animateMove:") })
        assertTrue(roles.missing().any { it.startsWith("methods.dispatchAnimationFinished:") })
        assertTrue(roles.missing().any { it.startsWith("fields.holderItemView:") })
    }

    @Test
    fun `a section without groups resolves nothing and does not throw`() {
        val roles = roles("{}")

        assertNull(roles.animateMove)
        assertNull(roles.holderItemView)
        assertFalse(roles.isComplete())
        assertTrue(roles.resolved().isEmpty())
        assertTrue(roles.missing().isNotEmpty())
    }
}
