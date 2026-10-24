package dev.amenhancer.module.hook

import android.content.SharedPreferences
import dev.amenhancer.module.config.TargetConfigClient
import dev.amenhancer.module.model.FeatureState
import java.lang.reflect.Proxy
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class LyricsPlayerFeatureTest {
    @Test
    fun `the switch being off leaves the host lyrics page untouched`() {
        var installed = false
        val result = LyricsPlayerFeature().install(
            HookContext(
                config = config(false),
                target = target {
                    installed = true
                    TargetCapabilityInstall.Active("installed")
                },
            ),
        )

        assertEquals(FeatureState.DISABLED, result.state)
        assertFalse(installed)
    }

    @Test
    fun `a missing setting defaults to off`() {
        var installed = false
        val result = LyricsPlayerFeature().install(
            HookContext(
                config = config(null),
                target = target {
                    installed = true
                    TargetCapabilityInstall.Active("installed")
                },
            ),
        )

        assertEquals(FeatureState.DISABLED, result.state)
        assertFalse(installed)
    }

    @Test
    fun `the switch being on installs the player through the target seam`() {
        var installed = false
        val result = LyricsPlayerFeature().install(
            HookContext(
                config = config(true),
                target = target {
                    installed = true
                    TargetCapabilityInstall.Active("mounted")
                },
            ),
        )

        assertEquals(FeatureState.ACTIVE, result.state)
        assertEquals("mounted", result.message)
        assertTrue(installed)
    }

    @Test
    fun `an unusable host degrades instead of mounting a blank page`() {
        val result = LyricsPlayerFeature().install(
            HookContext(
                config = config(true),
                target = target {
                    TargetCapabilityInstall.Degraded("lyrics player degraded: no geometry")
                },
            ),
        )

        assertEquals(FeatureState.DEGRADED, result.state)
        assertEquals("lyrics player degraded: no geometry", result.message)
    }

    private fun target(install: () -> TargetCapabilityInstall): TargetAdaptation =
        TargetAdaptation(
            identity = "test",
            dualPane = DualPaneTarget { TargetCapabilityInstall.Active("unused") },
            editorialVideo = EditorialVideoTarget { TargetCapabilityInstall.Active("unused") },
            bidirectionalLyricBlur = BidirectionalLyricBlurTarget {
                TargetCapabilityInstall.Active("unused")
            },
            lyricsPlayer = LyricsPlayerTarget(install),
        )

    private fun config(enabled: Boolean?): TargetConfigClient = TargetConfigClient(
        Proxy.newProxyInstance(
            SharedPreferences::class.java.classLoader,
            arrayOf(SharedPreferences::class.java),
        ) { _, method, _ ->
            when (method.name) {
                "getAll" -> enabled?.let {
                    mapOf<String, Any>("lyrics_player_enabled" to it)
                } ?: emptyMap<String, Any>()
                "toString" -> "lyrics-player-feature-test-preferences"
                "hashCode" -> 1
                "equals" -> false
                else -> null
            }
        } as SharedPreferences,
    )
}
