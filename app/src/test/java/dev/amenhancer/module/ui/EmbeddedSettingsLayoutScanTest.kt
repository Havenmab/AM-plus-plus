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
}
