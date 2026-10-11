package dev.amenhancer.module.hook

import dev.amenhancer.host.applemusic.AppleMusicHostProfiles
import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
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

    private val nativeTarget = projectFile(
        "host-applemusic/src/main/java/dev/amenhancer/module/hook/AppleMusicNativeLyricsTarget.kt",
    )

    private val factory = projectFile(
        "host-applemusic/src/main/java/dev/amenhancer/module/hook/AppleMusicHostFactory.kt",
    )

    private val rebind = projectFile(
        "host-applemusic/src/main/java/dev/amenhancer/module/hook/AppleLyricsPresentationRebind.kt",
    )

    private val pronunciationPayload = projectFile(
        "host-applemusic/src/main/java/dev/amenhancer/module/hook/AppleLyricsPronunciationPayload.kt",
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
        assertTrue(nativeTarget.contains("isModuleSupplementSong = { songId ->"))
        assertTrue(target.contains("setModuleSupplementSongResolver"))
        assertTrue(target.contains("readyReplacementFor(appleMusicId) != null"))
    }

    @Test
    fun `the refresh re-invokes the app's result presentation on the main handler`() {
        // The primitive: the fork's `lyrics-install-method` (HLE's
        // LYRICS_RESULT_PRESENTATION). It is the method custom lyrics already
        // resolved and hooked, and it is handed to the native-model hooks.
        assertTrue(nativeTarget.contains("presentationMethod = presentationMethod"))
        assertTrue(factory.contains("nativePresentationMethod"))
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
        // `reload=` proves whether the app's own `loadLyrics` was re-invoked
        // (the fresh-model path a foreground uses) and why it was skipped.
        assertTrue(hooks.contains("reload=\$reload"))
        // `trigger=` proves which of the three asks re-presented the page:
        // `build`, `custom-overlay` (the custom completion's post-overlay ask) or
        // `f2-retry` (the binding seam re-dispatching a lost ask).
        assertTrue(hooks.contains("trigger=\$trigger"))
        assertTrue(hooks.contains("\"build\""))
        assertTrue(hooks.contains("\"custom-overlay\""))
        assertTrue(hooks.contains("\"f2-retry\""))
        assertTrue(hooks.contains("adapter=\${adapterName ?: NONE}"))
        assertTrue(hooks.contains("state=\${if (stateCleared) STATE_CLEARED else STATE_LATCHED}"))
        // `payload=`/`adapterE=` are the pronunciation fix's own device proof:
        // the app's payload reaching the live adapter's stale flag.
        assertTrue(hooks.contains("payload=\$payload adapterE=\$adapterE"))
        assertTrue(hooks.contains("payloadToken(payload)"))
        assertTrue(hooks.contains("adapterFlagTransition(payload)"))
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
        assertTrue(hooks.contains("if (state == lastState) return"))
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

    @Test
    fun `the late pronunciation lane requests the same refresh with its own trigger`() {
        // The reported bug: Apple's own lane became available after the build,
        // and a song with no online source has no overlay write to trigger the
        // custom-overlay refresh, so the romanization waited for backgrounding.
        assertTrue(hooks.contains("PresentationRefreshTrigger.LANE_READY"))
        assertTrue(hooks.contains("\"lane-ready\""))
        // The two seams that fire after the lane exists and on the app's own
        // read: the advertisement query hook and the availability override.
        assertTrue(hooks.contains("private fun installPronunciationLanguageQueryHook("))
        assertTrue(hooks.contains("private fun installSongAvailabilityHooks("))
        assertTrue(hooks.contains("private fun onPronunciationLaneReady("))
        val queryHook = hooks
            .substringAfter("private fun installPronunciationLanguageQueryHook(")
            .substringBefore("private fun refreshPronunciationSelectionIfOpen(")
        assertTrue(queryHook.contains("onPronunciationLaneReady("))
        val availability = hooks
            .substringAfter("private fun installSongAvailabilityHooks(")
            .substringBefore("private fun applyAppleNativePronunciationSelection(")
        assertTrue(availability.contains("onPronunciationLaneReady("))
        // The app's own read is the edge; our own selection's setPronunciation
        // re-enters the override and must not count it before the build gate.
        assertTrue(availability.contains("if (pronunciationSelectionGuard.get() != true)"))
        // The lane edge is the (song, lane, per-line probe) triple, so a lane
        // whose text only populated after the build is a new edge while repeated
        // reads of one settled lane are not.
        assertTrue(hooks.contains("NativeLyricModelPolicy.pronunciationLaneReadyKey("))
        assertTrue(hooks.contains("if (key == laneReadyKey) return"))
        assertTrue(hooks.contains("laneReadyKey = key"))
        assertTrue(hooks.contains("pronunciationLaneRevision += 1"))
        // A coincident build for the same lane state must not refresh twice.
        assertTrue(hooks.contains("state.sameLaneRefresh(last)"))
        assertTrue(hooks.contains("fun sameLaneRefresh(other: PresentationRefreshState): Boolean"))
        // The lane-ready reload builds synchronously inside its own attempt, so
        // the gate that follows must not re-ask for the state it just applied —
        // but only a preceding *lane-ready* is suppressed, never a custom-overlay.
        assertTrue(hooks.contains("lastState.trigger == PresentationRefreshTrigger.LANE_READY"))
        assertTrue(hooks.contains("state.sameLaneRefresh(lastState)"))
        // Diagnostics: the decision line names the trigger and the detail.
        assertTrue(hooks.contains("const val DETAIL_LANE_READY = \"lane-ready\""))
        assertTrue(hooks.contains("detail = DETAIL_LANE_READY"))
        assertTrue(
            hooks.contains(
                "performPresentationRefresh(state, PresentationRefreshTrigger.LANE_READY)",
            ),
        )
    }

    @Test
    fun `the lane revision is per song and resets on a track change`() {
        assertTrue(hooks.contains("private var pronunciationLaneRevision: Long = 0L"))
        assertTrue(hooks.contains("private var laneReadyKey: String? = null"))
        val newTrack = hooks
            .substringAfter("if (songId != modelSongId) {")
            .substringBefore("modelSongId = songId")
        assertTrue(newTrack.contains("pronunciationLaneRevision = 0L"))
        assertTrue(newTrack.contains("laneReadyKey = null"))
        // Only Apple's own document: a supplement pointer's lanes are its
        // overlay's job and the custom-overlay trigger owns that re-presentation.
        val laneReady = hooks
            .substringAfter("private fun onPronunciationLaneReady(")
            .substringBefore("private fun refreshReason(")
        assertTrue(laneReady.contains("if (isModuleSupplementSong(songId)) return"))
        assertTrue(laneReady.contains("if (presentationInvokeGuard.get() == true) return"))
    }

    /**
     * Part 1 of the pronunciation fix. `F2` is the only writer of the live
     * adapter's `n1.e` flag and derives it from
     * `LocaleUtil.matchToSystemLyricsScript(model.getPronunciationLanguages())`.
     * On the first play Apple's vector is still empty, so the pure policy returns
     * null and `F2` latches `e=false` even though the module already selected the
     * lane. The after-hook must fall back to that confirmed lane, guarded by the
     * identity of the model being delivered so a previous track's lane can never
     * leak.
     */
    @Test
    fun `the official match hook falls back to the lane already confirmed for this song`() {
        val matchHook = hooks
            .substringAfter("private fun installPronunciationLanguageMatch(")
            .substringBefore("private fun installPronunciationPreferenceHook(")
        assertTrue(matchHook.contains("NativeLyricModelPolicy.selectLanguage("))
        assertTrue(matchHook.contains("?: confirmedPronunciationLanguageForCurrentSong()"))
        // A Mandarin-hidden song still yields null: the hide branch runs first,
        // and the fallback can never resurrect it.
        assertTrue(matchHook.contains("param.result = null"))
        val helper = hooks
            .substringAfter("private fun confirmedPronunciationLanguageForCurrentSong(")
            .substringBefore("private fun isMandarinHidden(")
        // Cross-song leakage guard: the model currently being delivered and the
        // exact instance the native setter accepted.
        assertTrue(helper.contains("val songNative = songNativeRef?.get() ?: return null"))
        assertTrue(helper.contains("if (!isCurrentSong(songNative)) return null"))
        assertTrue(helper.contains("selectedPronunciationSong?.get() === songNative"))
        assertTrue(helper.contains("NativeLyricModelPolicy.isOfficialPronunciationLanguage(it)"))
        // Only a lane we actually confirmed: the selected lane, else Apple's
        // best-known advertisement for this same model.
        assertTrue(helper.contains("NativeLyricModelPolicy.officialPronunciationLanguage("))
        assertTrue(
            helper.contains(
                "advertisedPronunciationLanguages(songPronunciationLanguages(songNative))",
            ),
        )
    }

    /**
     * Part 2 of the pronunciation fix. After the re-presentation (`F2` inside
     * `w2`) and the full rebind, the app's own pronunciation payload is replayed
     * last: `F2` writes `e=false` from the empty vector and
     * `notifyDataSetChanged` cannot repair a row whose memoized strings are
     * unchanged, so the payload must be the final mutation.
     */
    @Test
    fun `the pronunciation payload is replayed after the invoke and the rebind`() {
        val perform = hooks
            .substringAfter("private fun performPresentationRefresh(")
            .substringBefore("/** The `payload=` token of one attempt")
        assertTrue(perform.contains("presentationRebind.rebind(fragment)"))
        assertTrue(perform.contains("pronunciationPayload.apply("))
        val invokeAt = perform.indexOf("method.invoke(fragment, pointer)")
        val rebindAt = perform.indexOf("presentationRebind.rebind(fragment)")
        val payloadAt = perform.indexOf("pronunciationPayload.apply(")
        assertTrue(invokeAt in 0 until payloadAt)
        assertTrue(rebindAt in 0 until payloadAt)
        // Only a confirmed lane the user wants: the same conjunct `F2`'s own
        // `z && t2(...)` uses.
        assertTrue(
            perform.contains(
                "state.officialLane ?: confirmedPronunciationLanguageForCurrentSong()",
            ),
        )
        assertTrue(perform.contains("hasConfirmedOfficialLane = lane != null"))
        assertTrue(perform.contains("pronunciationSelected = state.pronunciationSelected"))
        // The adapter field and flag are the profile pins, with the report's
        // verified 1606 literals as the fallback.
        assertTrue(hooks.contains("AppleMusicRuntimeMember.LYRICS_UI_ADAPTER_FIELD"))
        assertTrue(
            hooks.contains(
                "AppleMusicRuntimeMember.LYRICS_ADAPTER_PRONUNCIATION_SELECTED_FIELD",
            ),
        )
        assertTrue(hooks.contains("FALLBACK_LYRICS_ADAPTER_FIELD"))
        assertTrue(hooks.contains("FALLBACK_PRONUNCIATION_SELECTED_FIELD"))
        assertTrue(hooks.contains("FALLBACK_LYRICS_ADAPTER_FIELD = \"p0\""))
        assertTrue(hooks.contains("FALLBACK_PRONUNCIATION_SELECTED_FIELD = \"e\""))
    }

    @Test
    fun `the payload helper replays the app's own three-member dispatch and fails open`() {
        // Fragment adapter field -> n1.e -> RecyclerView.g#d(int,int,Object).
        assertTrue(pronunciationPayload.contains("readFieldOrNull(fragment, name)"))
        assertTrue(pronunciationPayload.contains("resolved.flag.setBoolean(resolved.adapter, true)"))
        assertTrue(pronunciationPayload.contains("PAYLOAD_POSITION_START"))
        assertTrue(pronunciationPayload.contains("resolved.payloadMethod.invoke("))
        assertTrue(pronunciationPayload.contains("resolved.itemCount"))
        // `d(int,int,Object)` on the observable, resolved by shape as well as by
        // the verified obfuscated name.
        assertTrue(pronunciationPayload.contains("VERIFIED_PAYLOAD_METHOD = \"d\""))
        assertTrue(pronunciationPayload.contains("PAYLOAD_METHOD_PARAMETERS"))
        assertTrue(pronunciationPayload.contains("java.lang.Integer.TYPE"))
        assertTrue(pronunciationPayload.contains("Any::class.java"))
        // The static payload name is found by suffix (`n1.i`), the observable by
        // the verified field `a`, and both fall back safely.
        assertTrue(
            pronunciationPayload.contains(
                "PAYLOAD_SUFFIX = \"PAYLOAD_TOGGLE_PRONUNCIATION\"",
            ),
        )
        assertTrue(pronunciationPayload.contains("VERIFIED_OBSERVABLE_FIELD = \"a\""))
        assertTrue(pronunciationPayload.contains("VERIFIED_PAYLOAD_FIELD = \"i\""))
        assertTrue(pronunciationPayload.contains("getField"))
        assertTrue(pronunciationPayload.contains("runCatching"))
        // Never `n1.f` (it picks the main line's text) and never the translation
        // payload `n1.h`. The full `notifyDataSetChanged` is the rebind's job,
        // not this helper's: only the app's own payload dispatch repairs the flag.
        assertFalse(pronunciationPayload.contains("\"f\""))
        assertFalse(pronunciationPayload.contains("PAYLOAD_TOGGLE_TRANSLATION"))
        assertFalse(pronunciationPayload.contains("notifyDataSetChanged("))
    }

    private fun projectFile(relativePath: String): String = sequenceOf(
        File(relativePath),
        File("../$relativePath"),
        File("../../$relativePath"),
    ).firstOrNull(File::isFile)?.readText()
        ?: error("$relativePath was not found from the unit-test working directory")
}
