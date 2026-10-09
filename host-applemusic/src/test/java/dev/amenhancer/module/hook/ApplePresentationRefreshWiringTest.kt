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

    private val rebind = projectFile(
        "host-applemusic/src/main/java/dev/amenhancer/module/hook/AppleLyricsPresentationRebind.kt",
    )

    private val feed = projectFile(
        "core/src/main/kotlin/dev/amenhancer/module/hook/CustomLyricsCompletionFeed.kt",
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
        // decision stays retryable. The outcomes (and their latch rule) live in
        // core so a JVM test can pin the retry semantics.
        assertTrue(hooks.contains("presentationFragmentRef?.get()"))
        assertTrue(hooks.contains("presentationPointerRef?.get()"))
        assertTrue(hooks.contains("PresentationRefreshOutcome.NOT_BOUND"))
        assertTrue(hooks.contains("PresentationRefreshOutcome.NO_PRESENTATION_METHOD"))
        assertTrue(hooks.contains("PresentationRefreshOutcome.POINTER_DEAD"))
        assertTrue(hooks.contains("PresentationRefreshOutcome.SONG_CHANGED"))
        assertTrue(hooks.contains("PresentationRefreshOutcome.INVOKE_FAILED"))
    }

    @Test
    fun `a successful invoke rebinds the lyrics adapter`() {
        // HLE: `method.invoke(...).onSuccess { refreshAppleLyricsRecyclerView(...) }`.
        // The adapter reads the pronunciation flags once at bind, so the rebind is
        // what makes a late lane visible.
        assertTrue(hooks.contains("private val presentationRebind = AppleLyricsPresentationRebind("))
        assertTrue(hooks.contains("presentationRebind.rebind(fragment)"))
        assertTrue(hooks.contains("AppleMusicHookPoint.LYRICS_RECYCLER_ADAPTER"))
        assertTrue(
            hooks.contains("AppleMusicRuntimeMember.LYRICS_ADAPTER_NOTIFY_DATA_CHANGED_METHOD"),
        )
        assertTrue(
            hooks.contains("AppleMusicRuntimeMember.LYRICS_ADAPTER_ITEM_COUNT_METHOD"),
        )
        // The RecyclerView accessor is reused, not invented: the profile member
        // first, then the fork's own verified `getRecyclerView`.
        assertTrue(hooks.contains("LYRICS_UI_RECYCLER_VIEW_METHOD"))
        assertTrue(hooks.contains("FALLBACK_RECYCLER_VIEW_METHOD"))
        assertTrue(hooks.contains("\"getRecyclerView\""))
        // The rebind mirrors HLE's `resolveAppleLyricsRecyclerView` →
        // `appleRecyclerNotifyDataSetChanged`: validate the view by class name,
        // take `getAdapter()`, wait out `isComputingLayout` and notify.
        assertTrue(rebind.contains("androidx.recyclerview.widget.RecyclerView"))
        assertTrue(rebind.contains("getAdapter"))
        assertTrue(rebind.contains("notifyDataSetChanged"))
        assertTrue(rebind.contains("isComputingLayout"))
        assertTrue(rebind.contains("postOnAnimation"))
        assertTrue(rebind.contains("AppleReflection.findMethodOrNull"))
    }

    @Test
    fun `the native presentation seam binds and retries a lost refresh`() {
        // HLE's R2/F2 seam is the second binding point: the fork only bound from
        // the install method, so a refresh that ran before the view existed
        // aborted `not-bound` and never asked again.
        assertTrue(hooks.contains("installNativePresentationSeam()"))
        assertTrue(hooks.contains("AppleMusicHookPoint.LYRICS_NATIVE_PRESENTATION"))
        assertTrue(hooks.contains("retryPendingPresentationRefresh()"))
        assertTrue(hooks.contains("pendingPresentationRefresh"))
        // The re-dispatch is named for the seam that made it, so the next log can
        // tell an F2 retry from the build gate and the custom-overlay ask.
        assertTrue(
            hooks.contains(
                "performPresentationRefresh(pending, PresentationRefreshTrigger.F2_RETRY)",
            ),
        )
    }

    @Test
    fun `only a successful invoke latches the dedupe state`() {
        // The audit bug: the state was recorded before the main-handler post and
        // an abort could swallow every later retry. The latch is now driven by the
        // outcome's `latches` flag, and an abort clears the state for retry.
        assertTrue(hooks.contains("outcome.latches"))
        assertTrue(hooks.contains("outcome.cleared"))
        assertTrue(hooks.contains("PresentationRefreshOutcome.REBOUND"))
        assertTrue(hooks.contains("PresentationRefreshOutcome.ADAPTER_UNAVAILABLE"))
        assertTrue(hooks.contains("lastPresentationRefreshState = null"))
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
        // `adapter=` proves whether the rebind resolved one, and `state=` proves
        // a `not-bound` abort cleared the state for a later retry.
        assertTrue(hooks.contains("detail=\$detail"))
        // `trigger=` proves which of the three asks re-presented the page:
        // `build`, `custom-overlay` (the custom completion's post-overlay ask) or
        // `f2-retry` (the binding seam re-dispatching a lost ask).
        assertTrue(hooks.contains("trigger=\$trigger"))
        assertTrue(hooks.contains("\"build\""))
        assertTrue(hooks.contains("\"custom-overlay\""))
        assertTrue(hooks.contains("\"f2-retry\""))
        assertTrue(hooks.contains("adapter=\${adapterName ?: NONE}"))
        assertTrue(hooks.contains("state=\${if (stateCleared) STATE_CLEARED else STATE_LATCHED}"))
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

    @Test
    fun `the build-time supplement skip stays and the custom feed owns its own refresh`() {
        // The build gate keeps skipping a supplement pointer: Apple's track
        // refresh makes the lyrics page twitch (HLE). The supplement path's own
        // refresh is the completion feed's post-overlay ask instead, exactly like
        // HLE's store update (`AppleSupplementDataReceive` → the presentation
        // refresh), so the skip is not lifted.
        assertTrue(hooks.contains("!isModuleSupplementSong(songId)"))
        assertTrue(hooks.contains("!sourceIsApple -> \"supplement\""))
        // The hooks expose the cross-layer hook instead of a new global, and the
        // decision is the store-update rule (a lane exists in the overlay).
        assertTrue(hooks.contains("fun onCustomOverlayUpdated(songId: Long)"))
        assertTrue(hooks.contains("requestCustomOverlayPresentationRefresh(songId)"))
        assertTrue(hooks.contains("PresentationRefreshTrigger.CUSTOM_OVERLAY"))
        assertTrue(
            hooks.contains(
                "NativeLyricModelPolicy.shouldRefreshPresentationAfterCustomOverlay(",
            ),
        )
        assertTrue(
            hooks.contains("val detail = if (shouldRefresh) \"custom-refresh\" else \"gate\""),
        )
        // The feed reports the write; the target forwards it to the delivery it
        // already owns.
        assertTrue(feed.contains("private val onOverlayUpdated: (Long) -> Unit = {}"))
        assertTrue(feed.contains("if (merged != null) {"))
        assertTrue(feed.contains("runCatching { onOverlayUpdated(appleMusicId) }"))
        assertTrue(
            target.contains(
                "onOverlayUpdated = { appleMusicId -> onCustomOverlayUpdated(appleMusicId) },",
            ),
        )
        assertTrue(target.contains("nativeLyricDelivery?.onCustomOverlayUpdated(appleMusicId)"))
    }

    @Test
    fun `the post-overlay refresh is once per song and overlay revision on the main handler`() {
        // The dedupe: the live overlay revision is part of the shared recorded
        // state, and the completion goes through the main handler like every
        // other refresh.
        assertTrue(hooks.contains("if (state == lastPresentationRefreshState) return"))
        assertTrue(hooks.contains("overlayRevision = overlay.revision()"))
        assertTrue(
            hooks.contains("mainHandler.post { requestCustomOverlayPresentationRefresh(songId) }"),
        )
        // Expected-song checks: the model the completion belongs to here, and the
        // bound pointer's native id again before the invoke.
        assertTrue(hooks.contains("if (songId != modelSongId) return"))
        assertTrue(hooks.contains("nativeSongId(songNative) != state.songId"))
        // A build arriving after an aborted overlay ask must not drop it before
        // the F2 binding seam can retry; only the completion's next revision
        // supersedes it.
        assertTrue(
            hooks.contains(
                "pendingPresentationRefresh?.trigger != PresentationRefreshTrigger.CUSTOM_OVERLAY",
            ),
        )
    }

    @Test
    fun `the refresh re-runs HLE's full text-hook sequence before the invoke`() {
        // HLE: `ensureAppleLyricTextHooks(songNative)` then
        // `applyAppleNativeSupplementSelection(songNative)` before
        // `method.invoke`. The fork's `ensureNativeModel` is the first half and
        // used to be missing, so the re-presentation could not pick up a
        // translation lane on a model that predates the overlay — the reported
        // "Apple's translation only appears after backgrounding".
        val perform = hooks
            .substringAfter("private fun performPresentationRefresh(")
            .substringBefore("private fun logPresentationRefresh(")
        assertTrue(perform.contains("ensureNativeModel(songNative, viewModel = null)"))
        assertTrue(perform.contains("applyAppleNativePronunciationSelection("))
        assertTrue(
            perform.indexOf("ensureNativeModel(songNative, viewModel = null)") <
                perform.indexOf("method.invoke(fragment, pointer)"),
        )
        assertTrue(
            perform.indexOf("applyAppleNativePronunciationSelection(") <
                perform.indexOf("method.invoke(fragment, pointer)"),
        )
    }

    private fun projectFile(relativePath: String): String = sequenceOf(
        File(relativePath),
        File("../$relativePath"),
        File("../../$relativePath"),
    ).firstOrNull(File::isFile)?.readText()
        ?: error("$relativePath was not found from the unit-test working directory")
}
