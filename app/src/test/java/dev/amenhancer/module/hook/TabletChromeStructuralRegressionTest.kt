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
        // The tablet top bar takes deliberate, defaulted opt-ins on the shared component while
        // staying on the author's default refraction path:
        //  - the accent tint is NOT passed: the library tints only the row the droplet samples, so
        //    the selected label is red only while the droplet covers it — passing our extra
        //    "selected cell is always accent" tint made both the current page and the droplet's
        //    neighbours red at once;
        //  - the cell recording is NOT skipped, so the droplet refracts the labels themselves
        //    (the reference look) instead of only the page behind the bar;
        //  - the press scale is OFF, so pressing does not grow every label and glyph;
        //  - the refraction is scaled with the thumb so the sampled copy magnifies in place.
        assertFalse(
            "the tablet top bar must not tint the selected cell on its own",
            tablet.contains("tintSelectedWithAccent"),
        )
        assertFalse(
            "the tablet top bar must keep the author's cell recording so the droplet refracts the labels",
            tablet.contains("cleanSelectionMask"),
        )
        assertTrue(
            "pressing the top bar must not scale the labels",
            tablet.contains("pressScalesCells = false"),
        )
        assertTrue(
            "the tablet must replace ordinary labels under the refracting thumb",
            tablet.contains("replaceContentUnderThumb = true"),
        )
        assertTrue(navigation.contains("replaceContentUnderThumb: Boolean = false"))
        // The round-7 scaling deviations are gone: the effect constants are the author's absolute
        // values again and the press bloom is not moved onto the thumb. Both were ours, and each
        // one changed the relationship between the sampled layer and the thumb's lens, which is
        // what displaced the refracted copy away from the crisp label.
        assertFalse(
            "the tablet must not scale the component's effect constants",
            tablet.contains("effectReferenceHeight ="),
        )
        assertFalse(
            "the tablet must not move the press bloom onto the thumb",
            tablet.contains("refractionScalesWithThumb ="),
        )
        assertTrue(
            "the top capsule height must come from the tablet policy, not the host bottom row",
            tablet.contains("TOP_CAPSULE_HEIGHT_DP"),
        )
        // The phone and dual-pane bars must not take any of the tablet-only opt-ins.
        assertFalse(
            "the phone session must not take the tablet-only opt-ins",
            phone.contains("cleanSelectionMask") ||
                phone.contains("TOP_CAPSULE_HEIGHT_DP") ||
                phone.contains("pressScalesCells = false") ||
                phone.contains("refractionScalesWithThumb = true") ||
                phone.contains("replaceContentUnderThumb = true"),
        )
    }

    @Test
    fun refractedLabelsReplaceOnlyTheCoveredOrdinaryTextWithoutRemovingTheSampler() {
        val tabs = normalized(glassSource("com/kyant/backdrop/catalog/components/LiquidBottomTabs.kt"))
        assertTrue(tabs.contains("replaceContentUnderThumb: Boolean = false"))
        assertTrue(tabs.contains("cleanSelectionMask || replaceContentUnderThumb"))
        assertTrue(tabs.contains("if (replaceContentUnderThumb && !cleanSelectionMask)"))
        val ordinaryRow = tabs.indexOf("if (replaceContentUnderThumb && !cleanSelectionMask)")
        val sampledRow = tabs.indexOf(".layerBackdrop(tabsBackdrop)", ordinaryRow)
        val mask = tabs.substring(ordinaryRow, sampledRow)
        assertTrue(mask.contains("compositingStrategy = CompositingStrategy.Offscreen"))
        assertTrue(mask.contains("val thumbOutline = Capsule().createOutline("))
        assertTrue(mask.contains(".drawWithCache"))
        assertTrue(mask.contains("drawOutline(thumbOutline, Color.Black"))
        assertTrue(mask.contains("blendMode = BlendMode.DstOut"))
        assertTrue(mask.contains("index = dampedDragAnimation.value"))
        assertTrue(tabs.contains("provides if (replaceContentUnderThumb) tabScale else pressScale"))
        assertTrue(tabs.contains("backdrop = rememberCombinedBackdrop(backdrop, tabsBackdrop)"))
        assertTrue(tabs.substring(sampledRow).contains("content = if (cleanSelectionMask) EmptyTabContent else content"))
    }

    @Test
    fun tabletLabelsGrowWithoutChangingTheSharedPhoneLabelsOrCapsuleHeight() {
        val layout = normalized(source("dev/amenhancer/module/hook/TabletChromeLayoutPolicy.kt"))
        val navigation = normalized(glassSource("dev/amenhancer/glass/GlassNavigation.kt"))
        assertTrue(layout.contains("const val TOP_TAB_LABEL_SIZE_SP = 15f"))
        assertTrue(layout.contains("const val TOP_TAB_CELL_MIN_DP = 52"))
        assertTrue(layout.contains("const val TOP_CAPSULE_HEIGHT_DP = 44"))
        assertTrue(navigation.contains("private const val TAB_LABEL_SIZE_SP = 11f"))
    }

    @Test
    fun miniArtworkIsSlightlySmallerAndRounderWithoutLosingItsMeasuredHandoff() {
        val mini = normalized(glassSource("dev/amenhancer/glass/GlassMiniPlayer.kt"))
        val tablet = normalized(source("dev/amenhancer/module/hook/TabletChromeSession.kt"))
        assertTrue(mini.contains("private const val COVER_SIZE_FRACTION = 0.68f"))
        assertTrue(mini.contains("private const val COVER_CORNER_FRACTION = 0.22f"))
        assertTrue(mini.contains("Box(Modifier.size(coverSize).clip(coverShape))"))
        assertTrue(tablet.contains(".onGloballyPositioned(::recordMiniCoverFrame)"))
    }

    @Test
    fun pageChangesActivelyScheduleSharedBackdropRefreshAndCloseCancelsIt() {
        val tablet = normalized(source("dev/amenhancer/module/hook/TabletChromeSession.kt"))
        assertTrue(tablet.contains("val wanted = topWanted || miniWanted"))
        assertTrue(tablet.contains("if (topSelectedId != selected) { topSelectedId = selected requestBackdropRefresh() }"))
        assertTrue(tablet.contains("topLayoutDirty = true requestBackdropRefresh()"))
        assertTrue(tablet.contains("source.postDelayed(topCaptureCallback, maxOf(0L, scheduled - now))"))
        assertTrue(tablet.contains("topCaptureSource?.removeCallbacks(topCaptureCallback)"))
        assertTrue(tablet.contains("topCaptureRefresh.setVisible(false, SystemClock.uptimeMillis())"))
        assertFalse(tablet.contains("topCaptureSettle"))
    }
}
