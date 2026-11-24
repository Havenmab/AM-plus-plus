package dev.amenhancer.module.hook

import dev.amenhancer.host.applemusic.AppleMusicHostProfiles
import io.github.proify.lyricon.amprovider.xposed.AppleMusicHookPoint
import io.github.proify.lyricon.amprovider.xposed.AppleMusicHookProfiles
import io.github.proify.lyricon.amprovider.xposed.AppleMusicVersion
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Records the pronunciation gap on 7.0.2 (1624) and 7.0.3 (1626) instead of leaving it implicit.
 *
 * The upstream 1.7.x sync shipped both profiles with every pronunciation `hookTargets` entry
 * empty, exactly like 1622. `AppleMusicNativeLyricsTarget.install()` therefore returns
 * Degraded, so the module's word readings never reach the native model on those builds.
 *
 * The two things that do not need that seam are still present, and the tests below pin them so a
 * later sync cannot quietly remove them either: the pair layout (centring) and the result
 * submission contract. Filling the seams needs the matching `base.apk` DEX evidence — the 1609
 * and 1614 re-derivations are the template — so the emptiness is asserted deliberately: a future
 * change that fills them will fail here and must update this test with the evidence.
 */
class AppleMusic702703PronunciationGapTest {

    private val builds = listOf(
        Triple("7.0.2", 1624L, AppleMusicVersion("7.0.2", 1624L)),
        Triple("7.0.3", 1626L, AppleMusicVersion("7.0.3", 1626L)),
    )

    @Test
    fun `the pronunciation install seam is still unresolved on 1624 and 1626`() {
        builds.forEach { (_, _, version) ->
            assertNotNull(
                "missing profile for ${version.displayName}",
                AppleMusicHookProfiles.profileFor(version),
            )
            assertTrue(
                "1624/1626 gained a pronunciation target; re-derive it from the APK and update this test",
                AppleMusicHookProfiles.exactTargets(version, AppleMusicHookPoint.LYRICS_VIEW_MODEL_LOAD).isEmpty(),
            )
        }
    }

    @Test
    fun `the pair layout and the result submission contract survive the gap`() {
        builds.forEach { (name, code, _) ->
            val profile = AppleMusicHostProfiles.find("com.apple.android.music", name, code)
            assertNotNull("missing host profile for $name", profile)
            val document = profile!!.document
            val pairLayout = document.optJSONObject("layoutVariants")
                ?.optString("lyricPronunciationPairLayout").orEmpty()
            assertTrue("pair layout is missing on $name", pairLayout.isNotBlank())
            assertNotNull(
                "result submission is missing on $name",
                document.optJSONObject("lyricResultSubmission"),
            )
        }
    }
}
