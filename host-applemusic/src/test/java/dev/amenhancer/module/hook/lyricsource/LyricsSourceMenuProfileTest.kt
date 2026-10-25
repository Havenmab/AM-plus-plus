package dev.amenhancer.module.hook.lyricsource

import io.github.proify.lyricon.amprovider.xposed.AppleMusicHookPoint
import io.github.proify.lyricon.amprovider.xposed.AppleMusicHookProfiles
import io.github.proify.lyricon.amprovider.xposed.AppleMusicRuntimeMember
import io.github.proify.lyricon.amprovider.xposed.AppleMusicVersion
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Pins the lyrics-source menu contract on both registered host profiles.
 *
 * 1609 is the build the evidence was taken from; 1613 ships the generic popup
 * path only. These assertions must fail if either profile loses (or silently
 * gains) a symbol the feature depends on.
 */
class LyricsSourceMenuProfileTest {

    private fun profile(versionCode: Long): JSONObject {
        val stream = javaClass.classLoader!!.getResourceAsStream(
            "host-profiles/7.0.0-beta-" + versionCode + ".json",
        )
        assertNotNull("profile resource for " + versionCode, stream)
        return JSONObject(stream!!.use { it.readBytes().toString(Charsets.UTF_8) })
    }

    @Test
    fun `1609 pins the popup-opening click listener verbatim`() {
        val document = profile(1609)
        val contract = LyricsSourceMenuContract.from(document)
        assertNotNull("1609 must carry the lyrics-source menu contract", contract)
        assertEquals("com.apple.android.music.player.fragment.d0", contract!!.listenerClassName)
        assertEquals("onClick", contract.methodName)
        assertEquals(
            "com.apple.android.music.player.fragment.PlayerLyricsViewFragment",
            contract.fragmentClassName,
        )
        // The decompiled 1609 listener keeps its fragment in the synthetic field "a".
        assertEquals("a", contract.fragmentFieldName)

        // and the same entry is visible through the HLE-shaped profile API, so the
        // runtime resolver and this test read one source of truth.
        val target = AppleMusicHookProfiles.exactTargets(
            AppleMusicVersion("7.0.0-beta", 1609L),
            AppleMusicHookPoint.LYRICS_SOURCE_MENU_CLICK_LISTENER,
        ).single()
        assertEquals("com.apple.android.music.player.fragment.d0", target.className)
        assertEquals("onClick", target.methodName)
        assertEquals(
            "com.apple.android.music.player.fragment.PlayerLyricsViewFragment",
            target.runtimeMemberNames[AppleMusicRuntimeMember.LYRICS_SOURCE_MENU_FRAGMENT_CLASS],
        )
        assertEquals(
            "a",
            target.runtimeMemberNames[AppleMusicRuntimeMember.LYRICS_SOURCE_MENU_FRAGMENT_FIELD],
        )
    }

    @Test
    fun `1613 declares the generic path instead of guessing a listener class`() {
        val document = profile(1613)
        // No exact symbol: the build had no install package to derive it from, and
        // a guessed class would hook an unrelated listener.
        assertEquals(0, document.getJSONObject("hookTargets")
            .getJSONArray("LYRICS_SOURCE_MENU_CLICK_LISTENER").length())
        val degradations = document.getJSONArray("knownDegradations")
        val tokens = (0 until degradations.length()).map { degradations.getString(it) }
        assertTrue(tokens.contains("lyrics-source-menu-generic-path"))
        // The generic path still proves the fragment identity, which needs the class
        // name the profile already indexes for its other lyrics targets.
        assertEquals(
            "com.apple.android.music.player.fragment.PlayerLyricsViewFragment",
            document.getJSONObject("indexed").getJSONObject("classes").getString("LYRICS_FRAGMENT"),
        )
    }

    @Test
    fun `both profiles stay schema version 1 with the popup target present`() {
        listOf(1609L, 1613L).forEach { versionCode ->
            val document = profile(versionCode)
            assertEquals(1, document.getInt("schemaVersion"))
            assertTrue(document.getJSONObject("hookTargets").has("LYRICS_SOURCE_MENU_CLICK_LISTENER"))
        }
    }
}
