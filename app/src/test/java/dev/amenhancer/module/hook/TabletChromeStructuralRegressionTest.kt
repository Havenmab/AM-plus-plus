package dev.amenhancer.module.hook

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Guards the tablet iPad-style chrome contract:
 *
 * - the style is a sub-option of the liquid-glass bar, so both gates must hold;
 * - the runtime routes the iPad session ahead of the dual-pane tablet session and the dual-pane
 *   branch refuses to run while the iPad style is selected, so the two never write the same
 *   geometry (the original author's dual-pane glass stays byte-for-byte reachable);
 * - the feature adds exactly one configuration key and no glass migration, so an existing store
 *   keeps the shipped behaviour.
 */
class TabletChromeStructuralRegressionTest {
    private fun source(relativePath: String): String = sequenceOf(
        File("src/main/java/$relativePath"),
        File("app/src/main/java/$relativePath"),
    ).firstOrNull(File::isFile)?.readText()
        ?: error("$relativePath was not found from the unit-test working directory")

    /** Collapses line wraps so multi-line expressions can be matched as written prose. */
    private fun normalized(text: String): String = text.replace(Regex("\\s+"), " ")

    /**
     * Reads a source file from the sibling `glass` module. The unit-test working directory is
     * either the `app` module or the repo root depending on how Gradle launches it, so both
     * candidate roots are tried.
     */
    private fun glassSource(relativePath: String): String = sequenceOf(
        File("../glass/src/main/kotlin/$relativePath"),
        File("glass/src/main/kotlin/$relativePath"),
    ).firstOrNull(File::isFile)?.readText()
        ?: error("$relativePath was not found from the unit-test working directory")

    @Test
    fun `routes the iPad session ahead of the dual-pane session and never both`() {
        val runtime = normalized(source("dev/amenhancer/module/hook/PhoneGlassRuntime.kt"))

        val ipadSite = runtime.indexOf("TabletChromeSession(")
        assertTrue("the runtime must construct TabletChromeSession", ipadSite >= 0)
        val ipadGates = runtime.substring(maxOf(0, ipadSite - 600), ipadSite)
        assertTrue(ipadGates.contains("phoneLiquidGlassEnabled"))
        assertTrue(ipadGates.contains("TabletChromeStyle.IPAD"))
        assertTrue(ipadGates.contains("TabletModeQualifier.isOfficialTablet"))
        assertTrue(ipadGates.contains("GlassPolicy.supports"))

        // The iPad branch must come first, and the dual-pane branch must opt out of that style,
        // otherwise the two sessions could own the same collapsed geometry.
        val dualPaneSite = runtime.indexOf("TabletDualPaneGlassSession(")
        assertTrue("the runtime must construct TabletDualPaneGlassSession", dualPaneSite >= 0)
        assertTrue("the iPad branch must be decided before the dual-pane branch", ipadSite < dualPaneSite)
        val dualPaneGates = runtime.substring(maxOf(0, dualPaneSite - 600), dualPaneSite)
        assertTrue(dualPaneGates.contains("TabletChromeStyle.IPAD"))
        assertTrue(dualPaneGates.contains("!="))
    }

    @Test
    fun `gates the feature behind the glass toggle, the iPad style and Android 13`() {
        val feature = normalized(source("dev/amenhancer/module/hook/TabletChromeFeature.kt"))
        assertTrue(feature.contains("phoneLiquidGlassEnabled"))
        assertTrue(feature.contains("tabletChromeStyle != TabletChromeStyle.IPAD"))
        assertTrue(feature.contains("Build.VERSION.SDK_INT < 33"))
        assertTrue(feature.contains("target.tabletChrome"))
    }

    @Test
    fun `adds one style key without touching the glass keys or the schema version`() {
        val schema = source("dev/amenhancer/module/config/ModuleSettingsSchema.kt")
        val constants = source("dev/amenhancer/module/ModuleConstants.kt")
        val models = normalized(source("dev/amenhancer/module/model/ModuleModels.kt"))

        assertTrue(schema.contains("\"tablet_chrome_style\""))
        // The original author's glass surface must stay exactly as it was.
        val glassKeys = Regex("\"(phone_liquid_glass[a-z_]*)\"").findAll(schema)
            .map { it.groupValues[1] }.toSet()
        assertEquals(
            setOf(
                "phone_liquid_glass_enabled",
                "phone_liquid_glass_bottom_gap_dp",
                "phone_liquid_glass_panel_blur_dp",
            ),
            glassKeys,
        )
        assertTrue(constants.contains("const val CONFIG_SCHEMA_VERSION = 15"))
        // Default must be the shipped style so an untouched install renders exactly as before.
        assertTrue(models.contains("val tabletChromeStyle: TabletChromeStyle = TabletChromeStyle.AUTHOR"))
    }

    @Test
    fun `exposes the tablet chrome capability as the command surface plus install`() {
        val adaptation = normalized(source("dev/amenhancer/module/hook/TargetAdaptation.kt"))
        assertTrue(adaptation.contains("interface TabletChromeTarget : TabletChromeCommands"))
        assertTrue(adaptation.contains("fun install(): TargetCapabilityInstall"))
        assertTrue(adaptation.contains("AppleMusicTabletChromeTarget(resolver, build)"))

        val commands = normalized(source("dev/amenhancer/module/hook/TabletChromeCommands.kt"))
        for (member in listOf(
            "fun cycleRepeatMode()",
            "fun expandPlayer(activity: Activity)",
            "fun openLyrics(activity: Activity)",
            "fun openQueue(activity: Activity)",
            "fun addListener(listener: () -> Unit): AutoCloseable",
        )) {
            assertTrue("TabletChromeCommands must declare $member", commands.contains(member))
        }
    }

    @Test
    fun `leaves the shipped phone glass bar on its original rendering`() {
        // The iPad top bar wants an accent-tinted selected label; the shipped phone/dual-pane bar
        // conveys selection with the library thumb alone. The tint must therefore be opt-in, and
        // only the tablet session may opt in — otherwise the original author's bar changes.
        val navigation = normalized(glassSource("dev/amenhancer/glass/GlassNavigation.kt"))
        assertTrue(
            "GlassNavigation must default the accent tint off",
            navigation.contains("tintSelectedWithAccent: Boolean = false"),
        )

        val phone = normalized(source("dev/amenhancer/module/hook/PhoneGlassSession.kt"))
        val phoneCall = phone.indexOf("GlassNavigation(")
        assertTrue("PhoneGlassSession must render GlassNavigation", phoneCall >= 0)
        assertFalse(
            "the phone session must not opt into the accent tint",
            phone.substring(phoneCall, minOf(phone.length, phoneCall + 500))
                .contains("tintSelectedWithAccent"),
        )

        val tablet = normalized(source("dev/amenhancer/module/hook/TabletChromeSession.kt"))
        // The tablet top bar takes two deliberate, defaulted opt-ins on the shared component:
        // cleanSelectionMask now keeps the refraction recording but excludes the cells (so the
        // droplet refracts the page instead of duplicating the labels), and the effect constants
        // scale with the capsule height so a thin bar still looks right. The accent tint stays off
        // — the library's own accentOverride already colours the cells under the droplet.
        assertFalse(
            "the tablet top bar must not opt into the accent tint",
            tablet.contains("tintSelectedWithAccent = true"),
        )
        assertTrue(
            "the tablet top bar must keep the refraction recording without the cells",
            tablet.contains("cleanSelectionMask = true"),
        )
        assertTrue(
            "the top capsule height must come from the tablet policy, not the host bottom row",
            tablet.contains("TOP_CAPSULE_HEIGHT_DP"),
        )
        // The phone and dual-pane bars must not take either opt-in.
        assertFalse(
            "the phone session must not take the tablet-only opt-ins",
            phone.contains("cleanSelectionMask") || phone.contains("TOP_CAPSULE_HEIGHT_DP"),
        )
    }
}
