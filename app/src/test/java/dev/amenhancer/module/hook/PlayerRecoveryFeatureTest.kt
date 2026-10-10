package dev.amenhancer.module.hook

import android.content.SharedPreferences
import dev.amenhancer.module.config.TargetConfigClient
import dev.amenhancer.module.model.FeatureState
import org.junit.Assert.*
import org.junit.Test
import java.lang.reflect.Proxy

class PlayerRecoveryFeatureTest {
    @Test fun `native repairs install without enabling dual pane glass or a plugin`() {
        var calls = 0
        val context = context(PlayerRecoveryTarget {
            calls++
            TargetCapabilityInstall.Active("Native repairs registered")
        })
        assertFalse(context.config.settings().dualPaneEnabled)
        assertFalse(context.config.settings().phoneLiquidGlassEnabled)
        assertEquals(FeatureState.ACTIVE, PlayerRecoveryFeature().install(context).state)
        assertEquals(1, calls)
    }

    @Test fun `an unsupported player remains unsupported rather than reporting active`() {
        assertEquals(FeatureState.UNSUPPORTED, PlayerRecoveryFeature().install(context(PlayerRecoveryTarget {
            TargetCapabilityInstall.Unsupported("No verified Fragment recovery contract")
        })).state)
    }

    private fun context(recovery: PlayerRecoveryTarget): HookContext {
        val preferences = Proxy.newProxyInstance(SharedPreferences::class.java.classLoader,
            arrayOf(SharedPreferences::class.java)) { _, method, args ->
            when (method.name) {
                "getAll" -> mapOf("dual_pane_enabled" to false, "phone_liquid_glass_enabled" to false)
                "contains" -> false
                else -> args?.lastOrNull()
            }
        } as SharedPreferences
        return HookContext(TargetConfigClient(preferences), TargetAdaptation(
            identity = "fixture", dualPane = DualPaneTarget { TargetCapabilityInstall.Active("unused") },
            editorialVideo = EditorialVideoTarget { TargetCapabilityInstall.Active("unused") },
            bidirectionalLyricBlur = BidirectionalLyricBlurTarget { TargetCapabilityInstall.Active("unused") },
            playerRecovery = recovery,
        ))
    }
}
