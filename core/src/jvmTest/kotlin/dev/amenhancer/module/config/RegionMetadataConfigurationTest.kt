package dev.amenhancer.module.config

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Behaviour tests for the call-time metadata configuration: HLE's three gates read off one
 * snapshot, the refresh decision that replaces HLE's preference listener, and the short-TTL
 * cache the request seams poll.
 *
 * Everything here is pure Kotlin, so the whole truth table runs on the JVM without a device.
 */
class RegionMetadataConfigurationTest {
    private val switches = listOf(false, true)

    // ---------------------------------------------------------------- HLE's gates

    @Test
    fun `the account-language override needs a selection and the switch`() {
        val none = RegionSelection.NONE.contentUiLanguageSelection
        switches.forEach { override ->
            switches.forEach { restore ->
                val configuration = RegionMetadataConfiguration(
                    region = RegionSelection.NONE,
                    overrideAccountLanguage = override,
                    restoreCjkOriginalMetadata = restore,
                )
                // 不开启 never overrides, whatever the switch says.
                assertFalse(configuration.shouldOverrideAccountLanguage(none))

                RegionSelection.values().filter { it != RegionSelection.NONE }.forEach { region ->
                    val selected = configuration.copy(region = region)
                    // The caller's selection decides, not the configured region: HLE's
                    // shouldOverrideAccountLanguage(selection) is a pure function of both.
                    assertEquals(override, selected.shouldOverrideAccountLanguage(region.contentUiLanguageSelection))
                    assertFalse(selected.shouldOverrideAccountLanguage(none))
                }
            }
        }
    }

    @Test
    fun `the original-name restore is independent of the region`() {
        switches.forEach { restore ->
            RegionSelection.values().forEach { region ->
                switches.forEach { override ->
                    val configuration = RegionMetadataConfiguration(
                        region = region,
                        overrideAccountLanguage = override,
                        restoreCjkOriginalMetadata = restore,
                    )
                    assertEquals(restore, configuration.isRestoreOriginalMetadataEnabled)
                    assertEquals(restore, configuration.plan.probesOriginalMetadata)
                    // regionReplacementEnabled is the and of region and override only.
                    assertEquals(
                        region.replacesRegion && override,
                        configuration.plan.overrideAccountLanguage,
                    )
                }
            }
        }
    }

    @Test
    fun `the snapshot projects exactly like the pure policy`() {
        RegionSelection.values().forEach { region ->
            switches.forEach { override ->
                switches.forEach { restore ->
                    val configuration = RegionMetadataConfiguration(
                        region = region,
                        overrideAccountLanguage = override,
                        restoreCjkOriginalMetadata = restore,
                    )
                    assertEquals(
                        RegionTitleRequestPolicy.plan(
                            region = region,
                            overrideAccountLanguage = override,
                            restoreCjkOriginalMetadata = restore,
                        ),
                        configuration.plan,
                    )
                }
            }
        }
    }

    @Test
    fun `the snapshot decodes the four keys the runtime polls`() {
        RegionSelection.values().forEach { region ->
            switches.forEach { override ->
                switches.forEach { restore ->
                    switches.forEach { cache ->
                        val values = mapOf(
                            "region_selection" to region.storageValue,
                            "override_account_language" to override,
                            "restore_cjk_original_metadata" to restore,
                            "localized_metadata_cache" to cache,
                        )
                        assertEquals(
                            RegionMetadataConfiguration(region, override, restore, cache),
                            ModuleSettingsSchema.metadataConfiguration(values),
                        )
                    }
                }
            }
        }
        // A store that predates the switches reads through the same migration defaults as
        // the full decode, so the polled snapshot can never disagree with the install path.
        val legacy = mapOf<String, Any>(
            "title_correction_mode" to RegionSelection.JAPAN.storageValue,
            "title_correction_enabled" to true,
        )
        assertEquals(
            ModuleSettingsSchema.decode(legacy).let {
                RegionMetadataConfiguration(
                    region = it.regionSelection,
                    overrideAccountLanguage = it.overrideAccountLanguage,
                    restoreCjkOriginalMetadata = it.restoreCjkOriginalMetadata,
                    localizedMetadataCache = it.localizedMetadataCache,
                )
            },
            ModuleSettingsSchema.metadataConfiguration(legacy),
        )
    }

    // ------------------------------------------------------- refresh decision

    @Test
    fun `the install-time configuration is a seed, not a change`() {
        assertTrue(RegionMetadataRefreshPolicy.actionsFor(null, RegionMetadataConfiguration()).isEmpty())
        val seed = RegionMetadataConfiguration(RegionSelection.JAPAN, overrideAccountLanguage = true)
        assertTrue(RegionMetadataRefreshPolicy.actionsFor(null, seed).isEmpty())
        // Re-observing the same value is not a change either.
        assertTrue(RegionMetadataRefreshPolicy.actionsFor(seed, seed).isEmpty())
    }

    @Test
    fun `each metadata switch maps to HLE's own refresh actions`() {
        val base = RegionMetadataConfiguration(
            region = RegionSelection.JAPAN,
            overrideAccountLanguage = false,
            restoreCjkOriginalMetadata = false,
            localizedMetadataCache = true,
        )

        // The language selection / region re-runs both halves of HLE's listener.
        assertEquals(
            listOf(
                RegionMetadataRefreshAction.APPLY_REGION,
                RegionMetadataRefreshAction.DISPATCH_CONFIGURATION_CHANGE,
            ),
            RegionMetadataRefreshPolicy.actionsFor(base, base.copy(region = RegionSelection.KOREA)),
        )

        // The two switches only dispatch; they never touch the storefront.
        assertEquals(
            listOf(RegionMetadataRefreshAction.DISPATCH_CONFIGURATION_CHANGE),
            RegionMetadataRefreshPolicy.actionsFor(base, base.copy(overrideAccountLanguage = true)),
        )
        assertEquals(
            listOf(RegionMetadataRefreshAction.DISPATCH_CONFIGURATION_CHANGE),
            RegionMetadataRefreshPolicy.actionsFor(
                base,
                base.copy(restoreCjkOriginalMetadata = true),
            ),
        )

        // The persistent-cache key only re-applies, exactly like HLE's listener branch.
        assertEquals(
            listOf(RegionMetadataRefreshAction.APPLY_REGION),
            RegionMetadataRefreshPolicy.actionsFor(base, base.copy(localizedMetadataCache = false)),
        )

        // Turning the region off rewrites the storefront back to the account value too, so it
        // has to re-apply as well as refresh.
        val selected = base.copy(region = RegionSelection.JAPAN)
        assertTrue(
            RegionMetadataRefreshPolicy.actionsFor(selected, selected.copy(region = RegionSelection.NONE))
                .contains(RegionMetadataRefreshAction.APPLY_REGION),
        )
    }

    // ------------------------------------------------------------ TTL snapshot

    private class FakeSource(initial: RegionMetadataConfiguration) {
        var value: RegionMetadataConfiguration = initial
        var reads = 0
        fun read(): RegionMetadataConfiguration {
            reads += 1
            return value
        }
    }

    private class FakeClock {
        var millis: Long = 1_000L
        fun read(): Long = millis
        fun advance(by: Long) { millis += by }
    }

    @Test
    fun `the seams sample the source at most once per ttl window`() {
        val source = FakeSource(RegionMetadataConfiguration(RegionSelection.JAPAN))
        val clock = FakeClock()
        val watcher = RegionMetadataConfigurationWatcher(
            read = source::read,
            clock = clock::read,
            ttlMillis = 500L,
        )

        assertEquals(RegionSelection.JAPAN, watcher.current().region)
        assertEquals(1, source.reads)
        // Every seam inside the window is served from the cache: no extra preference read and
        // therefore no extra cross-process IPC on the hot path.
        repeat(5) {
            clock.advance(90L)
            assertEquals(RegionSelection.JAPAN, watcher.current().region)
        }
        assertEquals(1, source.reads)

        clock.advance(500L)
        watcher.current()
        assertEquals(2, source.reads)
    }

    @Test
    fun `a change is reported once with its actions`() {
        val source = FakeSource(RegionMetadataConfiguration(RegionSelection.JAPAN))
        val clock = FakeClock()
        val watcher = RegionMetadataConfigurationWatcher(source::read, clock::read, ttlMillis = 500L)
        val changes = mutableListOf<RegionMetadataConfigurationChange>()

        // Seeding never reports.
        watcher.observe(changes::add)
        assertTrue(changes.isEmpty())

        source.value = RegionMetadataConfiguration(
            region = RegionSelection.JAPAN,
            restoreCjkOriginalMetadata = true,
        )
        clock.advance(600L)
        watcher.observe(changes::add)
        assertEquals(1, changes.size)
        assertEquals(RegionSelection.JAPAN, changes.single().previous.region)
        assertTrue(changes.single().current.restoreCjkOriginalMetadata)
        assertEquals(
            listOf(RegionMetadataRefreshAction.DISPATCH_CONFIGURATION_CHANGE),
            changes.single().actions,
        )

        // The same value is not reported twice, even after the TTL expires again.
        clock.advance(600L)
        watcher.observe(changes::add)
        assertEquals(1, changes.size)
    }

    @Test
    fun `a failing source keeps the last good configuration`() {
        var fail = false
        val good = RegionMetadataConfiguration(RegionSelection.KOREA)
        val clock = FakeClock()
        val watcher = RegionMetadataConfigurationWatcher(
            read = { if (fail) error("preferences unavailable") else good },
            clock = clock::read,
            ttlMillis = 100L,
        )
        assertEquals(good, watcher.current())
        fail = true
        clock.advance(200L)
        // A transient read failure must not look like "the user turned everything off".
        assertEquals(good, watcher.current())
        var reported = 0
        clock.advance(200L)
        watcher.observe {
            reported += 1
            true
        }
        assertEquals(0, reported)
    }

    @Test
    fun `a plain read never swallows a pending change`() {
        val source = FakeSource(RegionMetadataConfiguration(RegionSelection.JAPAN))
        val clock = FakeClock()
        val watcher = RegionMetadataConfigurationWatcher(source::read, clock::read, ttlMillis = 100L)
        watcher.observe { true }
        source.value = RegionMetadataConfiguration(RegionSelection.KOREA)
        clock.advance(200L)
        // The hot path reads the new value first (the plan has to be current)...
        assertEquals(RegionSelection.KOREA, watcher.current().region)
        // ...but the seam still gets to report it once, so the host can refresh the surfaces.
        val changes = mutableListOf<RegionMetadataConfigurationChange>()
        watcher.observe { changes.add(it) }
        assertEquals(1, changes.size)
        assertEquals(RegionSelection.KOREA, changes.single().current.region)
    }

    @Test
    fun `a change a busy host refuses is offered again`() {
        val source = FakeSource(RegionMetadataConfiguration(RegionSelection.JAPAN))
        val clock = FakeClock()
        val watcher = RegionMetadataConfigurationWatcher(source::read, clock::read, ttlMillis = 100L)
        watcher.observe { true }
        source.value = RegionMetadataConfiguration(RegionSelection.KOREA)
        clock.advance(200L)
        // A host that is already refreshing refuses the change instead of queueing a second run.
        var offered = 0
        watcher.observe {
            offered += 1
            false
        }
        assertEquals(1, offered)
        // The refusal kept it pending: the next seam offers the same change again.
        clock.advance(200L)
        var accepted = 0
        watcher.observe {
            accepted += 1
            true
        }
        assertEquals(1, accepted)
        clock.advance(200L)
        watcher.observe {
            accepted += 1
            true
        }
        assertEquals(1, accepted)
    }

    @Test
    fun `seeding marks the installed configuration as applied`() {
        val source = FakeSource(RegionMetadataConfiguration(RegionSelection.JAPAN))
        val clock = FakeClock()
        val watcher = RegionMetadataConfigurationWatcher(source::read, clock::read, ttlMillis = 100L)
        watcher.seed()
        var offered = 0
        clock.advance(200L)
        watcher.observe {
            offered += 1
            true
        }
        // Nothing changed since install, so nothing is re-dispatched.
        assertEquals(0, offered)
    }

    @Test
    fun `invalidate forces the next read to hit the source`() {
        val source = FakeSource(RegionMetadataConfiguration(RegionSelection.JAPAN))
        val clock = FakeClock()
        val watcher = RegionMetadataConfigurationWatcher(source::read, clock::read, ttlMillis = 5_000L)
        watcher.current()
        assertEquals(1, source.reads)
        watcher.invalidate()
        watcher.current()
        assertEquals(2, source.reads)
    }

    @Test
    fun `the default ttl stays inside the documented short window`() {
        // The task's budget: 500-1000 ms.  A longer window would let a switch flip lag behind;
        // a shorter one would put a preference read on almost every request.
        assertTrue(RegionMetadataConfigurationWatcher.DEFAULT_TTL_MILLIS in 500L..1_000L)
    }
}
