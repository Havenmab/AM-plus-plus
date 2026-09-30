package dev.amenhancer.module.ui

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Coverage for the settings-entry discovery cost.
 *
 * The policy runs over every visible view of the main content activity's decor hierarchy on each
 * global layout, and the host throttles how often that happens.  Both pieces are pure enough to
 * test on the JVM without an Android runtime.
 */
class EmbeddedSettingsLayoutScanTest {
    @Test
    fun `settings titles match regardless of case and surrounding whitespace`() {
        listOf("Settings", "settings", "SETTINGS", "  Settings  ", "Account Settings")
            .forEach { title ->
                assertTrue("expected \"$title\" to match", EmbeddedSettingsTextPolicy.isSettingsTitle(title))
            }
        listOf("Preferences", "preference", "通用", "设置", "  设置  ")
            .forEach { title ->
                assertTrue("expected \"$title\" to match", EmbeddedSettingsTextPolicy.isSettingsTitle(title))
            }
    }

    @Test
    fun `unrelated and blank titles do not match`() {
        listOf("", "   ", "Library", "Listen Now", "播放列表")
            .forEach { title ->
                assertFalse("expected \"$title\" not to match", EmbeddedSettingsTextPolicy.isSettingsTitle(title))
            }
        assertFalse(EmbeddedSettingsTextPolicy.isSettingsTitle(null))
    }

    @Test
    fun `matching works on a CharSequence that is not a String`() {
        // The whole point of the allocation-free matcher: text views hand back arbitrary
        // CharSequences, and the previous implementation allocated a normalised String for each.
        assertTrue(EmbeddedSettingsTextPolicy.isSettingsTitle(StringBuilder("General Settings")))
        assertTrue(EmbeddedSettingsTextPolicy.isSettingsTitle(StringBuffer("SETTINGS")))
        assertFalse(EmbeddedSettingsTextPolicy.isSettingsTitle(StringBuilder("Now Playing")))
    }

    @Test
    fun `a marker appearing anywhere in the title counts as a match`() {
        assertTrue(EmbeddedSettingsTextPolicy.containsIgnoreCase("open settings now", "settings"))
        assertTrue(EmbeddedSettingsTextPolicy.containsIgnoreCase("xxpreferenceyy", "preference"))
        assertFalse(EmbeddedSettingsTextPolicy.containsIgnoreCase("setting", "settings"))
        assertFalse(EmbeddedSettingsTextPolicy.containsIgnoreCase("set", "settings"))
        assertTrue(EmbeddedSettingsTextPolicy.containsIgnoreCase("anything", ""))
    }

    @Test
    fun `the layout scan throttle lets the first pass through and then rate limits`() {
        val throttle = EmbeddedLayoutScanThrottle(intervalMs = 250L)

        assertTrue("the first layout must be scanned", throttle.tryAcquire(1_000L))
        assertFalse("a layout inside the interval must be skipped", throttle.tryAcquire(1_100L))
        assertFalse(throttle.tryAcquire(1_249L))
        assertTrue("the interval boundary must pass", throttle.tryAcquire(1_250L))
        assertFalse(throttle.tryAcquire(1_400L))
        assertTrue(throttle.tryAcquire(1_500L))
    }

    @Test
    fun `the scan backs off to the idle cadence once the settings page is gone`() {
        val throttle = EmbeddedLayoutScanThrottle(intervalMs = 250L, idleIntervalMs = 1_000L)

        assertTrue("the first layout must be scanned", throttle.tryAcquire(10_000L))
        throttle.recordResult(foundSettings = false)

        assertFalse("inside the idle interval the pass must be skipped", throttle.tryAcquire(10_500L))
        assertFalse(throttle.tryAcquire(10_999L))
        assertTrue("the idle boundary must pass", throttle.tryAcquire(11_000L))
        assertFalse("and the idle cadence keeps applying", throttle.tryAcquire(11_500L))
    }

    @Test
    fun `the scan returns to the active cadence while the settings page is shown`() {
        val throttle = EmbeddedLayoutScanThrottle(intervalMs = 250L, idleIntervalMs = 1_000L)

        assertTrue(throttle.tryAcquire(20_000L))
        throttle.recordResult(foundSettings = true)

        assertFalse("the faster interval still rate limits", throttle.tryAcquire(20_100L))
        assertTrue("leaving the settings page is noticed within the active interval", throttle.tryAcquire(20_250L))
    }

    @Test
    fun `a throttle that has not seen a result scans at the active cadence`() {
        val throttle = EmbeddedLayoutScanThrottle(intervalMs = 250L, idleIntervalMs = 1_000L)

        assertTrue(throttle.tryAcquire(0L))
        assertTrue("no result yet, so the shorter interval applies", throttle.tryAcquire(300L))
    }
}

/**
 * Coverage for the title traversal itself.  The real [android.view.View] accessors cannot run on
 * the JVM, but the traversal is generic over its node type, so a fake hierarchy exercises the node
 * budget accounting that the decor scan shares.
 */
class EmbeddedSettingsTitleScanTreeTest {
    private class FakeNode(
        val tag: Any? = null,
        val title: CharSequence? = null,
        val visible: Boolean = true,
        val children: MutableList<FakeNode> = mutableListOf(),
    )

    private fun scan(root: FakeNode, ignoredTag: Any?, maxNodes: Int = 1024): Boolean =
        EmbeddedSettingsTextPolicy.scanForSettingsTitle(
            root = root,
            ignoredTag = ignoredTag,
            maxNodes = maxNodes,
            tagOf = { it.tag },
            isCandidate = { it.visible },
            titleOf = { it.title },
            childrenInto = { node, out -> node.children.forEach(out::addLast) },
        )

    @Test
    fun `an ignored-tag node must not consume the node budget`() {
        val ignoredTag = Any()
        val root = FakeNode().apply {
            repeat(1_100) { children += FakeNode(tag = ignoredTag) }
            children += FakeNode(title = "Settings")
        }

        assertFalse(
            "with no ignored tag the title sits past the 1024-node cap",
            scan(root, ignoredTag = null),
        )
        assertTrue(
            "skipping by tag must not move the budget boundary",
            scan(root, ignoredTag = ignoredTag),
        )
    }

    @Test
    fun `an ignored tagged title is not treated as the settings page`() {
        val ignoredTag = Any()
        val root = FakeNode(children = mutableListOf(FakeNode(tag = ignoredTag, title = "Settings")))

        assertTrue("the title is a candidate without the ignore", scan(root, ignoredTag = null))
        assertFalse("the tagged row is skipped", scan(root, ignoredTag = ignoredTag))
    }

    @Test
    fun `the node cap still bounds a hierarchy with no ignored tag`() {
        val root = FakeNode().apply {
            repeat(2_000) { children += FakeNode(visible = false) }
            children += FakeNode(title = "Settings")
        }

        // Only ignored-tag nodes are exempt from the budget; a title past the cap stays unfound.
        assertFalse(scan(root, ignoredTag = null))
    }
}
