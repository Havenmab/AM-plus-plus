package dev.amenhancer.module.hook

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Guards the LyricsBlossom-style full-page player wiring: one switch and four sliders under
 * schema 26, a feature that only ever reaches the host through the semantic target seam, and
 * an install order that puts the whole-page replacement after the lyrics data paths.
 */
class LyricsPlayerStructuralRegressionTest {
    private val constants: String by lazy { source("dev/amenhancer/module/ModuleConstants.kt") }
    private val feature: String by lazy {
        source("dev/amenhancer/module/hook/LyricsPlayerFeature.kt")
    }
    private val installation: String by lazy {
        source("dev/amenhancer/module/hook/FeatureInstallation.kt")
    }
    private val pages: String by lazy {
        normalized(source("dev/amenhancer/module/ui/EmbeddedSettingsPages.kt"))
    }
    /** Works whether the unit tests run from :app or from the repository root. */
    private val appBuild: String by lazy {
        sequenceOf(
            File("app/build.gradle.kts"),
            File("build.gradle.kts"),
            File("../app/build.gradle.kts"),
        ).firstOrNull(File::isFile)?.readText()
            ?: error("app/build.gradle.kts was not found from the unit-test working directory")
    }

    private fun source(relativePath: String): String = sequenceOf(
        File("app/src/main/java/$relativePath"),
        File("../app/src/main/java/$relativePath"),
        File("src/main/java/$relativePath"),
        File("core/src/main/kotlin/$relativePath"),
        File("../core/src/main/kotlin/$relativePath"),
        File("host-api/src/main/java/$relativePath"),
        File("../host-api/src/main/java/$relativePath"),
        File("host-applemusic/src/main/java/$relativePath"),
        File("../host-applemusic/src/main/java/$relativePath"),
    ).firstOrNull(File::isFile)?.readRefactorComponent()
        ?: error("$relativePath was not found from the unit-test working directory")

    /** Collapses line wraps so multi-line expressions can be matched as written prose. */
    private fun normalized(text: String): String = text.replace(Regex("\\s+"), " ")

    @Test
    fun `schema 26 carries the player switch, the four sliders and the build tag`() {
        assertTrue(constants.contains("const val CONFIG_SCHEMA_VERSION = 26"))
        assertTrue(constants.contains("const val FEATURE_LYRICS_PLAYER = \"lyrics_player\""))
        assertTrue(
            constants.contains("const val BUILD_TAG = \"2026-10-23-1.6.6-lyrics-player-1609\""),
        )
    }

    @Test
    fun `the feature reads the switch and installs through the semantic target seam`() {
        assertTrue(feature.contains("context.config.settings().lyricsPlayerEnabled"))
        assertTrue(feature.contains("context.target.lyricsPlayer.install().toFeatureInstallResult()"))
        listOf("Class<", "Method", "Field", "TargetSymbolResolver", "context.symbols").forEach { forbidden ->
            assertFalse("feature leaked $forbidden", feature.contains(forbidden))
        }
    }

    @Test
    fun `the player is installed after every lyrics data path it consumes`() {
        val playerAt = installation.indexOf("FeatureInstallationPlan(feature = LyricsPlayerFeature())")
        val nativeAt = installation.indexOf("FeatureInstallationPlan(feature = NativeLyricsFeature())")
        val customAt = installation.indexOf("FeatureInstallationPlan(feature = CustomLyricsFeature())")
        assertTrue("the player plan must be registered", playerAt > 0)
        assertTrue("the player must install after the native lyrics path", playerAt > nativeAt)
        assertTrue("the player must install after the custom lyrics path", playerAt > customAt)
    }

    @Test
    fun `the advanced card holds the switch and the four sliders after the lyrics line spacing`() {
        val lineSpacingAt =
            pages.indexOf("localizedText(ModuleText.LYRICS_LINE_SPACING_SCALE)")
        val toggleAt = pages.indexOf("localizedText(ModuleText.LYRICS_PLAYER)")
        val motionAt = pages.indexOf("localizedText(ModuleText.LYRICS_PLAYER_MOTION)")
        val staggerAt = pages.indexOf("localizedText(ModuleText.LYRICS_PLAYER_STAGGER)")
        val emphasisAt = pages.indexOf("localizedText(ModuleText.LYRICS_PLAYER_EMPHASIS)")
        val fontScaleAt = pages.indexOf("localizedText(ModuleText.LYRICS_PLAYER_FONT_SCALE)")
        val positions = listOf(
            lineSpacingAt,
            toggleAt,
            motionAt,
            staggerAt,
            emphasisAt,
            fontScaleAt,
        )
        assertTrue("all six rows must be present", positions.all { it > 0 })
        assertEquals(
            "line spacing, switch, motion, stagger, emphasis and font size in that order",
            positions.sorted(),
            positions,
        )
    }

    @Test
    fun `each control writes its own setting key and uses the model bounds`() {
        listOf(
            "lyricsPlayerEnabled = it",
            "lyricsPlayerMotionPercent = it",
            "lyricsPlayerStaggerMs = it",
            "lyricsPlayerEmphasisPercent = it",
            "lyricsPlayerFontScalePercent = it",
        ).forEach { write -> assertTrue("missing writer for $write", pages.contains(write)) }
        listOf(
            "MIN_LYRICS_PLAYER_MOTION_PERCENT",
            "MAX_LYRICS_PLAYER_MOTION_PERCENT",
            "DEFAULT_LYRICS_PLAYER_MOTION_PERCENT",
            "MIN_LYRICS_PLAYER_STAGGER_MS",
            "MAX_LYRICS_PLAYER_STAGGER_MS",
            "DEFAULT_LYRICS_PLAYER_STAGGER_MS",
            "MIN_LYRICS_PLAYER_EMPHASIS_PERCENT",
            "MAX_LYRICS_PLAYER_EMPHASIS_PERCENT",
            "DEFAULT_LYRICS_PLAYER_EMPHASIS_PERCENT",
            "MIN_LYRICS_PLAYER_FONT_SCALE_PERCENT",
            "MAX_LYRICS_PLAYER_FONT_SCALE_PERCENT",
            "DEFAULT_LYRICS_PLAYER_FONT_SCALE_PERCENT",
        ).forEach { bound -> assertTrue("missing bound $bound", pages.contains("ModuleSettings.$bound")) }
    }

    @Test
    fun `the app depends on the lyrics player module`() {
        assertTrue(appBuild.contains("implementation(project(\":lyrics-player\"))"))
    }
}
