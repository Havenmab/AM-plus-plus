package dev.amenhancer.module.hook

import java.io.File
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Structural regression cover for the **custom (supplement) pronunciation chain**,
 * which a device build confirmed broken after the ViewModel result pipeline
 * (1.6.6, `LyricsResultSubmissionBridge`) replaced the Fragment install-argument
 * rewrite.
 *
 * The chain the device needs is:
 *
 *  1. the completion feed publishes the **displayed custom document's** own
 *     `<transliteration xml:lang>` lane into `NativeLyricOverlayStore` **before**
 *     it enriches, and after a successful completion asks the native delivery for
 *     a post-overlay refresh;
 *  2. the result bridge selects the prepared custom pointer and the install hook
 *     forwards the pointer it actually installed to the native delivery, which
 *     binds the model and records the fragment/pointer presentation;
 *  3. the per-line/per-word getters serve the overlay's rows for a module
 *     supplement song only (`isModuleSupplementSong` + `overlay.hasPronunciation`),
 *     with the timing/text row keys of that same document;
 *  4. the post-overlay refresh resolves a payload lane from that published lane
 *     (the supplement's content source) and re-runs the app's own presentation
 *     recompute, because the app's install method latches on pointer identity
 *     before it recomputes the pronunciation flag; the app payload is then
 *     dispatched last;
 *  5. Apple-sourced tracks keep using Apple's own data — every supplement branch
 *     is gated on the module-supplement resolver, never on the overlay alone.
 *
 * The runtime seam is native (`PlayerLyricsViewFragment#w2` latches its pointer
 * before `F2`, and the flag/payload surfaces are the app's own), so this pins the
 * wiring and the order of the source contract instead of executing it.
 */
class CustomLyricsPronunciationChainStructuralTest {

    private val target = projectFile(
        "host-applemusic/src/main/java/dev/amenhancer/module/hook/AppleMusicCustomLyricsTarget.kt",
    )

    private val nativeTarget = projectFile(
        "host-applemusic/src/main/java/dev/amenhancer/module/hook/AppleMusicNativeLyricsTarget.kt",
    )

    private val hooks = projectFile(
        "host-applemusic/src/main/java/dev/amenhancer/module/hook/AppleNativeLyricModelHooks.kt",
    )

    private val feed = projectFile(
        "core/src/main/kotlin/dev/amenhancer/module/hook/CustomLyricsCompletionFeed.kt",
    )

    private val policy = projectFile(
        "core/src/main/kotlin/dev/amenhancer/module/lyrics/online/NativeLyricOverlayStore.kt",
    )

    @Test
    fun `the displayed custom document is published before enrichment and asks for the refresh`() {
        val complete = feed
            .substringAfter("private fun complete(appleMusicId: Long, document: Document)")
            .substringBefore("private fun injectLine(")
        val displayedAt = complete.indexOf("onDisplayedDocument(appleMusicId, document.ttml)")
        val enrichAt = complete.indexOf("enrich(appleMusicId, document.ttml)")
        val refreshAt = complete.indexOf("onOverlayUpdated(appleMusicId)")
        // The displayed document's lanes must reach the overlay first; the
        // enricher's merged lanes refine them afterwards, so the overlay always
        // describes the document the host installs.
        assertTrue(displayedAt in 0 until enrichAt)
        assertTrue(refreshAt > 0)
        // Both the merged and the displayed-only completion ask for the refresh:
        // a custom document that already carries its lanes produces no merged
        // document, and that is the ordinary shape.
        assertTrue(complete.contains("if (merged != null) {"))
        assertTrue(complete.contains("} else if (publishedDisplayed) {"))
    }

    @Test
    fun `the custom target feeds the displayed document and the refresh into the native delivery`() {
        // The overlay the getters read is the shared runtime store, published from
        // the displayed document the session actually read.
        // The publish now also names the provider that owns the document; the source is
        // read from the index entry, so it is the stored vocabulary and never a guess.
        assertTrue(target.contains("runtime.nativeLyricOverlay.publishDisplayed("))
        // Three named arguments in the call, the provider coming from the index entry.
        assertTrue(target.contains("                            appleMusicId,"))
        assertTrue(target.contains("                            ttml,"))
        assertTrue(target.contains("                            originalSource = providerSource,"))
        assertTrue(target.contains("?.source"))
        assertTrue(target.contains("onDisplayedDocument = { appleMusicId, ttml ->"))
        // The completion's refresh ask goes to the delivery this target owns.
        assertTrue(
            target.contains(
                "onOverlayUpdated = { appleMusicId ->\n" +
                    "                        nativeLyricDelivery?.onCustomOverlayUpdated(appleMusicId)",
            ),
        )
        assertTrue(target.contains("nativeLyricDelivery?.setModuleSupplementSongResolver"))
        assertTrue(nativeTarget.contains("supplementSongResolver(songId)"))
        assertTrue(nativeTarget.contains("override fun onCustomOverlayUpdated(songId: Long)"))
    }

    @Test
    fun `the bridge-selected pointer is the one forwarded to the model delivery`() {
        // Selection resolves the session's ready custom pointer and unwraps it for
        // the host result channel; no Fragment argument is mutated.
        assertTrue(target.contains("val manual = session.replacementFor(id)"))
        assertTrue(target.contains("parser.unwrap(manual ?: automatic ?: translated)"))
        assertTrue(target.contains("LyricsResultSubmissionBridge.create("))
        // The install seam forwards the pointer that was actually installed
        // (never the pre-selection argument), so the model hooks bind the custom
        // document's model and the refresh re-presents the same pointer.
        val installHook = target
            .substringAfter("val hooked = hook(installMethod")
            .substringBefore("val availabilityResolution")
        assertTrue(installHook.contains("val pointer = param.args.getOrNull(0)"))
        assertTrue(installHook.contains("nativeLyricDelivery?.onLyricsPointer(pointer)"))
        assertTrue(installHook.contains("nativeLyricDelivery?.onLyricsPresentation(fragment, pointer)"))
        assertTrue(installHook.contains("resultBridge.onInstalled(fragment, param.args.getOrNull(0))"))
        // The completion is queued from selection, i.e. exactly when the custom
        // document becomes the one being submitted.
        assertTrue(target.contains("if (manual != null) customCompletion?.onDisplayed(id)"))
    }

    @Test
    fun `overlay rows are served only for a module supplement song`() {
        // The lane gate: a supplement track with a published lane, nothing else.
        assertTrue(
            hooks.contains(
                "id > 0L && isModuleSupplementSong(id) && overlayPronunciationLane(key)",
            ),
        )
        // The lane is replayed from the module's own record before the gate gives up:
        // the store holds one song, so a track change / restart must not turn a
        // published lane into a permanently grey 显示发音 row.
        assertTrue(hooks.contains("private fun overlayPronunciationLane(songId: String): Boolean"))
        assertTrue(hooks.contains("LyricsSourceOverlayCacheBridge.restore(id, overlay)"))
        assertTrue(hooks.contains("overlay.lookupBackground("))
        assertTrue(hooks.contains("supplementPronunciationRow("))
        assertTrue(hooks.contains("hasSupplementPronunciationLine("))
        // The refresh state's supplement pronunciation lane uses the same gate.
        assertTrue(
            hooks.contains(
                "enabled && songId > 0L && isModuleSupplementSong(songId) &&\n" +
                    "            overlayPronunciationLane(songId.toString())",
            ),
        )
        // Apple-sourced tracks never read the overlay for pronunciation content.
        assertTrue(hooks.contains("private fun supplementLaneAvailable(songId: String?): Boolean"))
        // The toggle fallback also accepts the supplement's own lane.
        assertTrue(hooks.contains("supplementOverlayPronunciationLanguage()?.let {"))
        assertTrue(hooks.contains("CurrentPronunciationLane(it, \"overlay\")"))
    }

    @Test
    fun `the post-overlay refresh resolves the supplement's published lane for the payload`() {
        // The lane resolution is the pure policy, fed the overlay lane only for a
        // supplement song.
        assertTrue(policy.contains("fun pronunciationPayloadLane("))
        assertTrue(policy.contains("supplementOverlayLane: String? = null"))
        assertTrue(policy.contains("supplementOverlayLane.isOfficialLane() ->"))
        assertTrue(policy.contains("source = PronunciationLaneSource.SUPPLEMENT_OVERLAY"))
        assertTrue(policy.contains("SUPPLEMENT_OVERLAY(\"supplement\")"))

        val perform = hooks
            .substringAfter("private fun performPresentationRefresh(")
            .substringBefore("private fun recomputeSupplementPresentation(")
        assertTrue(perform.contains("if (state.sourceIsApple) {"))
        assertTrue(perform.contains("supplementOverlayPronunciationLanguage()"))
        assertTrue(perform.contains("supplementOverlayLane = supplementOverlayLane"))
        // The custom-overlay ask keeps the full sequence, and re-running the
        // app's own presentation recompute is what recomputes the flag after the
        // overlay write. `w2` alone latches before `F2`.
        assertTrue(
            hooks.contains(
                "val payloadOnly = !state.sourceIsApple &&\n" +
                    "            trigger != PresentationRefreshTrigger.CUSTOM_OVERLAY",
            ),
        )
        assertTrue(perform.contains("if (!state.sourceIsApple) {"))
        assertTrue(perform.contains("recomputeSupplementPresentation(fragment, pointer)"))
        assertTrue(perform.contains("hasConfirmedOfficialLane = payloadLane.confirmed"))
        assertTrue(perform.contains("pronunciationPayload.apply("))
        // The refresh ask itself is admitted for a supplement whose overlay now
        // carries a pronunciation lane, even with no translation to merge.
        assertTrue(hooks.contains("val shouldRefresh = policyRefresh || laneRefresh"))
        assertTrue(
            hooks.contains(
                "NativeLyricModelPolicy.shouldRefreshPresentationAfterCustomOverlay(",
            ),
        )
    }

    private fun projectFile(relativePath: String): String = sequenceOf(
        File(relativePath),
        File("../$relativePath"),
    ).firstOrNull(File::isFile)?.readText()
        ?: error("$relativePath was not found from the unit-test working directory")
}
