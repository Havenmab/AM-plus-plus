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
    }

    open class SimpleItemAnimatorBase : ItemAnimatorBase() {
        fun dispatchMoveStarting(holder: Holder) = Unit

        open fun animateMove(holder: Holder, fromX: Int, fromY: Int, toX: Int, toY: Int): Boolean = true
    }

    class LyricsAnimator : SimpleItemAnimatorBase() {
        @JvmField
        var fragment: Any? = null

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
        // Two classes, six methods, two fields. The animator's move duration is deliberately absent:
        // the tempo is AMLL's fixed spring, so nothing reads that field.
        assertEquals(10, roles.resolved().size)
        assertEquals(Boolean::class.javaPrimitiveType, roles.animateMove?.returnType)
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
