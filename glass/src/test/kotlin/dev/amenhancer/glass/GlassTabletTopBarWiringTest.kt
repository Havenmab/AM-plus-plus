package dev.amenhancer.glass

import java.io.File
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * AM++: the tablet top bar's content-hugging geometry lived only in the Compose wiring, so a
 * wholesale upstream sync of LiquidBottomTab / LiquidBottomTabs / GlassNavigation silently dropped
 * it while every pure-geometry test kept passing. This pins the wiring itself: the renderer must
 * measure the labels, derive the drawn capsule width from the same rule as the cells, and hand
 * per-cell weights into the row. It is a source-structure test, like the app module's liquid-glass
 * regressions, because the wiring cannot be exercised by plain JVM unit tests.
 */
class GlassTabletTopBarWiringTest {
    private fun source(relativePath: String): String = sequenceOf(
        File("src/main/kotlin/$relativePath"),
        File("../glass/src/main/kotlin/$relativePath"),
        File("glass/src/main/kotlin/$relativePath"),
    ).firstOrNull(File::isFile)?.readText()
        ?: error("$relativePath was not found from the unit-test working directory")

    @Test fun tabletRendererMeasuresLabelsAndDrawsTheContentCapsule() {
        val navigation = source("dev/amenhancer/glass/GlassNavigation.kt")
        assertTrue(navigation.contains("rememberTextMeasurer()"))
        assertTrue(navigation.contains("huggedCapsuleWidth("))
        assertTrue(navigation.contains("insetToDrawnWidth(drawnWidthPx"))
        assertTrue(navigation.contains("tabContentWidths = tabContentWidths"))
        assertTrue(navigation.contains("tabIconOnly = tabIconOnly"))
        // The typography that matched the reference, and the compact tablet height.
        assertTrue(navigation.contains("FontWeight.SemiBold"))
        assertTrue(navigation.contains("15.sp"))
        assertTrue(navigation.contains("GlassPolicy.TABLET_NAV_HEIGHT_FRACTION"))
        // Only the tablet style may opt in; the phone bar keeps the anchor and equal cells.
        assertTrue(navigation.contains("style == GlassNavigationStyle.TabletLabels"))
    }

    @Test fun tabRowHonoursTheMeasuredCellWidths() {
        val tabs = source("com/kyant/backdrop/catalog/components/LiquidBottomTabs.kt")
        assertTrue(tabs.contains("tabContentWidths: List<androidx.compose.ui.unit.Dp>? = null"))
        assertTrue(tabs.contains("tabIconOnly: List<Boolean>? = null"))
        assertTrue(tabs.contains("content: @Composable RowScope.(index: Int, weight: Float) -> Unit"))
        assertTrue(tabs.contains("geometry.cellWeight(index)"))
        assertTrue(tabs.contains("pillTranslation("))
        assertTrue(tabs.contains("pillWidthAt("))
        val tab = source("com/kyant/backdrop/catalog/components/LiquidBottomTab.kt")
        assertTrue(tab.contains("weight: Float = 1f"))
        assertTrue(tab.contains(".weight(weight)"))
    }

    @Test fun theDrawnWidthComesFromTheSameRuleAsTheCells() {
        val geometry = source("dev/amenhancer/glass/GlassTabGeometry.kt")
        assertTrue(geometry.contains("fun huggedCapsuleWidth("))
        assertTrue(geometry.contains("fun cellWeight("))
        assertTrue(geometry.contains("val contentHugging"))
    }
}
