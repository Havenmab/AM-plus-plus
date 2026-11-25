package dev.amenhancer.module.hook

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The probe's one real design rule: a role that this build does not have costs exactly that
 * observation, never the whole install.
 */
class LyricLineProbeRolesTest {
    /** The reflection target; its shape is fixed so the assertions below stay about resolution. */
    class Sample {
        @JvmField
        val queue: ArrayList<String> = ArrayList()

        @JvmField
        val flag: Boolean = true

        @Suppress("unused")
        fun touch(from: Int, to: Int): Boolean = from <= to

        companion object {
            @JvmStatic
            @Suppress("unused")
            fun staticTouch(from: Int, to: Int): Boolean = from <= to
        }
    }

    private fun roles(section: String): LyricLineProbeRoles =
        LyricLineProbeRoles(Sample::class.java.classLoader!!, JSONObject(section))

    private fun descriptor(name: String, member: String, parameters: String, returns: String): String =
        "{\"owner\":\"" + name + "\",\"name\":\"" + member + "\",\"parameters\":[" +
            parameters + "],\"returns\":\"" + returns + "\",\"static\":false}"

    @Test
    fun `a missing or mismatched role only loses that role`() {
        val name = Sample::class.java.name
        val section = "{" +
            "\"classes\":{\"layoutManager\":\"" + name + "\",\"instrumentalBinding\":\"no.such.Clazz\"}," +
            "\"methods\":{" +
            "\"lineChange\":" + descriptor(name, "touch", "\"int\",\"int\"", "boolean") + "," +
            "\"animateMove\":" + descriptor(name, "absent", "", "int") + "," +
            "\"flushDeferred\":" + descriptor(name, "touch", "\"int\",\"int\"", "int") +
            "}," +
            "\"fields\":{" +
            "\"deferredQueue\":{\"owner\":\"" + name + "\",\"name\":\"queue\",\"type\":\"java.util.ArrayList\"}," +
            "\"anchored\":{\"owner\":\"" + name + "\",\"name\":\"flag\",\"type\":\"int\"}" +
            "}}"
        val roles = roles(section)

        assertNotNull(roles.lineChange)
        assertEquals(Boolean::class.javaPrimitiveType, roles.lineChange?.returnType)
        assertNotNull(roles.deferredQueue)
        assertNotNull(roles.layoutManagerClass)

        // The absent member, the wrong return type, the wrong field type and the missing class.
        assertNull(roles.animateMove)
        assertNull(roles.flushDeferred)
        assertNull(roles.anchored)
        assertNull(roles.instrumentalBinding)

        val missing = roles.missing()
        assertTrue(missing.toString(), missing.any { it.startsWith("methods.animateMove:") })
        assertTrue(missing.toString(), missing.any { it.startsWith("methods.flushDeferred:") })
        assertTrue(missing.toString(), missing.any { it.startsWith("fields.anchored:") })
        assertTrue(missing.toString(), missing.any { it.startsWith("classes.instrumentalBinding:") })

        val resolved = roles.resolved()
        assertTrue(resolved.toString(), resolved.contains("methods.lineChange"))
        assertTrue(resolved.toString(), resolved.contains("fields.deferredQueue"))
        assertTrue(resolved.toString(), resolved.contains("classes.layoutManager"))
        // A role that never resolved must not be reported as available.
        assertTrue(resolved.none { it == "methods.animateMove" })
    }

    @Test
    fun `a section without any group resolves nothing and does not throw`() {
        val roles = roles("{}")

        assertNull(roles.lineChange)
        assertNull(roles.deferredQueue)
        assertTrue(roles.resolved().isEmpty())
        assertTrue(roles.missing().isNotEmpty())
    }

    @Test
    fun `a static member never binds to an instance role`() {
        val name = Sample::class.java.name
        val section = "{\"methods\":{\"lineChange\":" + descriptor(name, "staticTouch", "\"int\",\"int\"", "boolean") + "}}"
        val roles = roles(section)

        // The member exists but is static while the role claims an instance method.
        assertNull(roles.lineChange)
        assertTrue(roles.missing().any { it.startsWith("methods.lineChange:") })
    }
}
