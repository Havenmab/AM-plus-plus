package dev.amenhancer.module.hook

import dev.amenhancer.host.applemusic.AppleMusicHostProfiles
import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Pins the post-build presentation refresh ported from HLE's
 * `AppleSupplementPlaybackHooks.hookLyricBuildMethod` +
 * `AppleSupplementPresentation.refreshAppleLyricsSupplementPresentation`.
 *
 * The bug it fixes: on 1606 the lyric model is built before Apple advertises
 * `getPronunciationLanguages` and before the online overlay is published, and
 * nothing re-reads the model afterwards, so romanization only appeared after
 * backgrounding Apple Music re-created the lyrics view. HLE evaluates a gate
 * from the build's `after` and re-invokes the result presentation; the fork had
 * no equivalent.
 *
 * These are source/profile pins: the hook itself is reflection over the real
 * Apple Music process and cannot run on the JVM, so the tests fail if the
 * wiring, the primitive or the anti-thrash guard drifts.
 */
class ApplePresentationRefreshWiringTest {

    private val hooks = projectFile(
        "host-applemusic/src/main/java/dev/amenhancer/module/hook/AppleNativeLyricModelHooks.kt",
    )

    private val target = projectFile(
        "host-applemusic/src/main/java/dev/amenhancer/module/hook/AppleMusicCustomLyricsTarget.kt",
    )

    @Test
    fun `the build seam evaluates HLE's gate from an after hook`() {
        // The gate is ported, not re-implemented on the host.
        assertTrue(hooks.contains("NativeLyricModelPolicy.shouldRefreshPresentationAfterBuild("))
        assertTrue(hooks.contains("NativeLyricModelPolicy.presentationRefreshReason("))
        // The decision is made after the model exists, from the build seam's
        // `after`, exactly as HLE does.
        assertTrue(hooks.contains("override fun afterHookedMethod(param: MethodHookParam)"))
        assertTrue(hooks.contains("requestPresentationRefreshAfterBuild(songNative)"))
        // The existing `before` half (the model write) is untouched.
        assertTrue(hooks.contains("ensureNativeModel(songNative, param.thisObject)"))
    }

    @Test
    fun `the refresh skips a module supplement pointer`() {
        // HLE: `if (!supplementPointer && ...)`. The fork's counterpart is a
        // *ready* module replacement, whose own path re-presents. A merely
        // in-flight fetch still shows Apple's document and must not suppress it.
        assertTrue(hooks.contains("isModuleSupplementSong"))
        assertTrue(hooks.contains("!isModuleSupplementSong(songId)"))
        assertTrue(target.contains("isModuleSupplementSong = { appleMusicId ->"))
        assertTrue(target.contains("readyReplacementFor(appleMusicId) != null"))
    }

    @Test
    fun `the refresh re-invokes the app's result presentation on the main handler`() {
        // The primitive: the fork's `lyrics-install-method` (HLE's
        // LYRICS_RESULT_PRESENTATION). It is the method custom lyrics already
        // resolved and hooked, and it is handed to the native-model hooks.
        assertTrue(target.contains("presentationMethod = installMethod"))
        assertTrue(hooks.contains("private val presentationMethod: Method?"))
        assertTrue(hooks.contains("mainHandler.post"))
        assertTrue(hooks.contains("method.invoke(fragment, pointer)"))
        // HLE's expected-song check before touching the page.
        assertTrue(hooks.contains("nativeSongId(songNative) != state.songId"))
    }

    @Test
    fun `the refresh is safe when the lyrics view is absent`() {
        // HLE returns early when no fragment/pointer is bound; so do we, and the
        // decision stays retryable.
        assertTrue(hooks.contains("presentationFragmentRef?.get()"))
        assertTrue(hooks.contains("presentationPointerRef?.get()"))
        assertTrue(hooks.contains("\"not-bound\""))
        assertTrue(hooks.contains("\"no-presentation-method\""))
        assertTrue(hooks.contains("\"pointer-dead\""))
        assertTrue(hooks.contains("\"song-changed\""))
    }

    @Test
    fun `the gate and diagnostics carry the device-facing fields`() {
        assertTrue(hooks.contains("online-translation presentation-refresh id="))
        assertTrue(hooks.contains("reason=\$reason"))
        assertTrue(hooks.contains("officialPronunciation=\$hasValidOfficialPronunciation"))
        assertTrue(hooks.contains("onlineTranslation=\$onlineTranslation"))
        assertTrue(hooks.contains("onlinePronunciation=\$onlinePronunciation"))
        assertTrue(hooks.contains("pronunciationSelected=\$pronunciationSelected"))
        assertTrue(hooks.contains("refreshed=\$refreshed"))
        // The overlay revision is one of the anti-thrash state inputs and is
        // therefore visible through `reason=`/`detail=` decisions.
        assertTrue(hooks.contains("overlay.revision()"))
    }

    @Test
    fun `an unchanged state never refreshes twice`() {
        // The anti-thrash and anti-recursion guard: our own re-presentation
        // re-enters the build, the gate holds again, and the recorded state
        // makes the second pass a no-op instead of looping.
        assertTrue(hooks.contains("private data class PresentationRefreshState("))
        assertTrue(hooks.contains("if (state == lastPresentationRefreshState) return"))
        assertTrue(hooks.contains("lastPresentationRefreshState = state"))
        assertTrue(hooks.contains("overlayRevision"))
        assertTrue(hooks.contains("officialLane"))
    }

    @Test
    fun `the binding ignores our own invoke so the refresh cannot re-arm itself`() {
        assertTrue(hooks.contains("presentationInvokeGuard"))
        assertTrue(hooks.contains("if (presentationInvokeGuard.get() == true) return"))
        assertTrue(hooks.contains("presentationInvokeGuard.set(true)"))
        assertTrue(hooks.contains("presentationInvokeGuard.remove()"))
        // The target records Apple's binding and forwards it.
        assertTrue(target.contains("nativeLyricDelivery?.onLyricsPresentation(param.thisObject, original)"))
    }

    /**
     * The primitive is not invented: the 1606 profile pins
     * `lyrics-install-method` to `PlayerLyricsViewFragment#w2(SongInfoPtr)` —
     * the same class/method HLE records for `LYRICS_RESULT_PRESENTATION` on this
     * build. A drift here means the refresh would invoke the wrong method.
     */
    @Test
    fun `1606 pins the result-presentation method the refresh invokes`() {
        val profile = AppleMusicHostProfiles.find("com.apple.android.music", "7.0.0-beta", 1606L)
        assertNotNull(profile)
        val contract = profile!!
            .document.getJSONObject("indexed")
            .getJSONObject("methodContracts")
            .getJSONObject("lyrics-install-method")
        assertEquals(
            "com.apple.android.music.player.fragment.PlayerLyricsViewFragment",
            contract.getString("owner"),
        )
        assertEquals("w2", contract.getString("name"))
        assertEquals("void", contract.getString("returns"))
        val parameters = contract.getJSONArray("parameters")
        assertEquals(1, parameters.length())
        assertEquals(
            "com.apple.android.music.ttml.javanative.model.SongInfo\$SongInfoPtr",
            parameters.getString(0),
        )
    }

    @Test
    fun `the fork does not invent a LYRICS_RESULT_PRESENTATION hook point`() {
        // 1606 is a strict profile and leaves the HLE hook point empty, so the
        // refresh uses the already-pinned `lyrics-install-method` symbol above
        // rather than a guessed method name.
        assertTrue(
            io.github.proify.lyricon.amprovider.xposed.AppleMusicHookProfiles
                .exactTargets(
                    io.github.proify.lyricon.amprovider.xposed.AppleMusicVersion(
                        "7.0.0-beta",
                        1606L,
                    ),
                    io.github.proify.lyricon.amprovider.xposed.AppleMusicHookPoint
                        .LYRICS_RESULT_PRESENTATION,
                )
                .isEmpty(),
        )
    }

    private fun projectFile(relativePath: String): String = sequenceOf(
        File(relativePath),
        File("../$relativePath"),
        File("../../$relativePath"),
    ).firstOrNull(File::isFile)?.readText()
        ?: error("$relativePath was not found from the unit-test working directory")
}
