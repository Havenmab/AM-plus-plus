package io.github.proify.lyricon.amprovider.xposed

import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Pins [AppleReflection.hierarchyFields], the cached replacement for the
 * row-bind path's `generateSequence { superclass }.flatMap { declaredFields }`.
 *
 * Static measure: resolving a holder's item view used to allocate one
 * `Class[]`/`Field[]` walk per row bind; the cache builds the field list once per
 * class (the second call returns the same immutable list) and keeps hierarchy
 * order, so field-selection behavior is unchanged.
 */
class AppleReflectionHierarchyFieldsTest {

    @Test
    fun `the second lookup returns the cached list`() {
        val first = AppleReflection.hierarchyFields(Child::class.java)
        val second = AppleReflection.hierarchyFields(Child::class.java)

        assertSame(first, second)
    }

    @Test
    fun `fields keep subclass-before-superclass order`() {
        val names = AppleReflection.hierarchyFields(Child::class.java).map { it.name }

        assertTrue(names.indexOf("childField") >= 0)
        assertTrue(names.indexOf("parentField") >= 0)
        assertTrue(names.indexOf("childField") < names.indexOf("parentField"))
    }

    @Test
    fun `distinct classes have distinct cached lists`() {
        val child = AppleReflection.hierarchyFields(Child::class.java)
        val parent = AppleReflection.hierarchyFields(Parent::class.java)

        assertTrue(child.size > parent.size)
        assertTrue(child !== parent)
    }

    @Suppress("unused")
    private open class Parent {
        @JvmField
        val parentField: String = "parent"
    }

    @Suppress("unused")
    private class Child : Parent() {
        @JvmField
        val childField: String = "child"
    }
}
