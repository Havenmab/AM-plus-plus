package dev.amenhancer.module.hook

import io.github.proify.lyricon.amprovider.xposed.AppleMusicHookPoint
import io.github.proify.lyricon.amprovider.xposed.AppleMusicHookProfiles
import io.github.proify.lyricon.amprovider.xposed.AppleMusicRuntimeMember
import io.github.proify.lyricon.amprovider.xposed.AppleMusicVersion
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Pins the exact 7.0.0-beta (1606) profile entries the presentation rebind
 * depends on: HLE's `LYRICS_RECYCLER_ADAPTER` member names (used for the
 * obfuscated `notifyDataSetChanged`/`getItemCount` fallback) and the
 * `LYRICS_NATIVE_PRESENTATION` seam (`F2`, HLE's R2 equivalent on this build)
 * that re-binds the fragment and pointer and retries a lost refresh.
 *
 * A drift here means the rebind would silently fall back to the standard
 * `notifyDataSetChanged` only, or the native-presentation retry would not
 * install at all.
 */
class AppleMusic700PresentationRebindProfileTest {

    private val version = AppleMusicVersion("7.0.0-beta", 1606L)

    @Test
    fun `1606 pins HLE's lyrics recycler adapter member dictionary`() {
        val adapters = AppleMusicHookProfiles
            .exactTargets(version, AppleMusicHookPoint.LYRICS_RECYCLER_ADAPTER)
        assertEquals(
            listOf(
                "com.apple.android.music.player.A",
                "com.apple.android.music.player.Y0",
            ),
            adapters.map { it.className },
        )
        adapters.forEach { target ->
            fun member(name: AppleMusicRuntimeMember) = target.runtimeMemberNames[name]
            assertEquals("w", member(AppleMusicRuntimeMember.LYRICS_ADAPTER_ACTIVE_POSITIONS_METHOD))
            assertEquals("x", member(AppleMusicRuntimeMember.LYRICS_ADAPTER_LYRICS_METHOD))
            assertEquals("f", member(AppleMusicRuntimeMember.LYRICS_ADAPTER_ITEM_VIEW_TYPE_METHOD))
            assertEquals("d", member(AppleMusicRuntimeMember.LYRICS_ADAPTER_ITEM_COUNT_METHOD))
            assertEquals("g", member(AppleMusicRuntimeMember.LYRICS_ADAPTER_NOTIFY_DATA_CHANGED_METHOD))
            assertEquals("O", member(AppleMusicRuntimeMember.LYRICS_ADAPTER_ACTIVE_LINES_UPDATE_METHOD))
            assertFalse(target.allowFirstMatch)
        }
    }

    @Test
    fun `1606 pins HLE's native presentation seam without inventing a second result point`() {
        val seam = AppleMusicHookProfiles
            .exactTargets(version, AppleMusicHookPoint.LYRICS_NATIVE_PRESENTATION)
            .single()
        assertEquals(
            "com.apple.android.music.player.fragment.PlayerLyricsViewFragment",
            seam.className,
        )
        assertEquals("F2", seam.methodName)
        assertEquals(1, seam.parameterCount)
        // HLE's 1606 build maps LYRICS_NATIVE_PRESENTATION to F2; the refresh
        // still re-invokes the pinned `lyrics-install-method` (w2), so the HLE
        // LYRICS_RESULT_PRESENTATION point must stay empty.
        assertTrue(
            AppleMusicHookProfiles
                .exactTargets(version, AppleMusicHookPoint.LYRICS_RESULT_PRESENTATION)
                .isEmpty(),
        )
    }
}
