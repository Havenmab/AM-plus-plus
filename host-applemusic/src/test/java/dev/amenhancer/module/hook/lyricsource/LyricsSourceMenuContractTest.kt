package dev.amenhancer.module.hook.lyricsource

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * The popup contract is read out of the host profile, so a build can be
 * registered without touching code. These cases pin the parsing rules: every
 * field is optional except the entry point itself, and anything malformed must
 * read as "no contract" — which makes the target fall back to its generic popup
 * path instead of hooking the wrong class.
 */
class LyricsSourceMenuContractTest {

    private fun profile(targetsJson: String): JSONObject =
        JSONObject("{\"schemaVersion\":1,\"hookTargets\":{\"LYRICS_SOURCE_MENU_CLICK_LISTENER\":" + targetsJson + "}}")

    private val complete = """
        [{
          "className":"com.apple.android.music.player.fragment.e0",
          "methodName":"onClick",
          "parameterCount":1,
          "runtimeMemberNames":{
            "LYRICS_SOURCE_MENU_FRAGMENT_CLASS":"com.apple.android.music.player.fragment.PlayerLyricsViewFragment",
            "LYRICS_SOURCE_MENU_FRAGMENT_FIELD":"a"
          }
        }]
    """.trimIndent()

    @Test
    fun `a complete entry yields the listener and the fragment accessors`() {
        val parsed = LyricsSourceMenuContract.from(profile(complete))
        assertEquals("com.apple.android.music.player.fragment.e0", parsed?.listenerClassName)
        assertEquals("onClick", parsed?.methodName)
        assertEquals(
            "com.apple.android.music.player.fragment.PlayerLyricsViewFragment",
            parsed?.fragmentClassName,
        )
        assertEquals("a", parsed?.fragmentFieldName)
    }

    @Test
    fun `an entry without runtime members still resolves the listener`() {
        val parsed = LyricsSourceMenuContract.from(
            profile("""[{"className":"com.apple.android.music.player.fragment.d0","methodName":"onClick"}]"""),
        )
        assertEquals("com.apple.android.music.player.fragment.d0", parsed?.listenerClassName)
        assertNull(parsed?.fragmentClassName)
        assertNull(parsed?.fragmentFieldName)
    }

    @Test
    fun `an empty candidate list means the build has no exact contract`() {
        assertNull(LyricsSourceMenuContract.from(profile("[]")))
    }

    @Test
    fun `a malformed entry is ignored instead of half-hooked`() {
        assertNull(LyricsSourceMenuContract.from(profile("""[{"className":""}]""")))
        assertNull(LyricsSourceMenuContract.from(profile("""[{}]""")))
    }

    @Test
    fun `a profile without the hookTargets block is tolerated`() {
        assertNull(LyricsSourceMenuContract.from(JSONObject("""{"schemaVersion":1}""")))
    }
}
