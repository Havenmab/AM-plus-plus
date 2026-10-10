package dev.amenhancer.module.hook

import java.io.File
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Pins the custom-lyrics completion wiring and the invariant it must not break:
 * the automatic path may add lanes to a custom document but a searched source
 * can never replace it.
 *
 * The automatic session is closed for manual/mapped ids precisely because, when
 * enrichment returns null, it falls through to the resolver and a searched
 * document would replace the user's own body. The custom feed added beside it
 * must therefore reuse the same enricher and keep only its native-overlay side
 * effect — never the resolver replacement path.
 */
class CustomLyricsCompletionWiringTest {

    private val target = projectFile(
        "host-applemusic/src/main/java/dev/amenhancer/module/hook/AppleMusicCustomLyricsTarget.kt",
    )

    @Test
    fun `the automatic path still excludes every custom id from its resolver fallback`() {
        assertTrue(target.contains("appleMusicId !in configuredManualIds"))
        assertTrue(target.contains("!session.isMapped(appleMusicId)"))
        assertTrue(target.contains("session.readyReplacementFor(appleMusicId) == null"))
    }

    @Test
    fun `custom tracks are completed by the same enricher the automatic path uses`() {
        assertTrue(target.contains("CustomLyricsCompletionFeed("))
        assertTrue(target.contains("runtime.translationEnricher"))
        assertTrue(target.contains("executor = runtime.executor"))
        assertTrue(
            target.contains("customCompletion?.remember(entry.appleMusicId, entry.sha256, ttml)"),
        )
        assertTrue(target.contains("customCompletion?.onDisplayed(adamId)"))
        assertTrue(
            target.indexOf("val readCustomTtml") < target.indexOf("readTtml = readCustomTtml"),
        )
        // The remembered body feeds the session that publishes the custom pointer.
        assertTrue(target.contains("readTtml = readCustomTtml"))
    }

    @Test
    fun `the completion wiring never touches the resolver replacement path`() {
        val wiring = target.substringAfter("val customCompletion = autoLyricsRuntime?.let")
            .substringBefore("val readCustomTtml")

        assertFalse(wiring.contains("resolverFetch"))
        assertFalse(wiring.contains("resolver::fetch"))
        assertFalse(wiring.contains("AutoLyricsCandidate"))
        // The merged document is forwarded to the custom session only; it never
        // reaches the automatic publisher or the resolver replacement path.
        assertFalse(wiring.contains("AutoLyricsPublisher"))
        assertFalse(wiring.contains("publishResult"))
    }

    @Test
    fun `the core feed forwards the merged document without parsing it itself`() {
        val feed = projectFile(
            "core/src/main/kotlin/dev/amenhancer/module/hook/CustomLyricsCompletionFeed.kt",
        )

        assertTrue(feed.contains("online-translation capture id="))
        assertTrue(feed.contains("online-translation inject id="))
        assertTrue(feed.contains("documents[appleMusicId] = Document(revision, ttml)"))
        // The enriched document is handed to the host callback (which re-checks
        // the body before publishing it) and never parsed, bound or installed by
        // the feed itself.
        assertTrue(feed.contains("private val onMergedDocument: (Long, String, String) -> Unit"))
        assertTrue(feed.contains("onMergedDocument(appleMusicId, document.ttml, merged)"))
        assertFalse(feed.contains("parseTtml"))
        assertFalse(feed.contains("bindAdamId"))
        assertFalse(feed.contains("onReplacementPublished"))
    }

    @Test
    fun `the merged document becomes the session's preferred pointer before the refresh`() {
        // The custom path's half of the face-2 fix: the host hands the merged
        // document to the custom session, which re-parses it as the preferred
        // replacement pointer (body-checked and revision-keyed). The post-overlay
        // refresh then installs it, exactly as the automatic path publishes its
        // merged candidate.
        assertTrue(target.contains("var publishMergedDocument: (Long, String, String) -> Unit = { _, _, _ -> }"))
        assertTrue(
            target.contains("onMergedDocument = { appleMusicId, raw, merged ->"),
        )
        assertTrue(target.contains("publishMergedDocument(appleMusicId, raw, merged)"))
        // Wired after the session exists, so an early completion fails open.
        assertTrue(
            target.indexOf("val customCompletion") <
                target.indexOf("session.publishMerged(appleMusicId, rawTtml, mergedTtml)"),
        )
        assertTrue(target.contains("online-translation merged-publish id="))
        val session = projectFile(
            "core/src/main/kotlin/dev/amenhancer/module/hook/CustomLyricsReplacementSession.kt",
        )
        assertTrue(session.contains("fun publishMerged(appleMusicId: Long, rawTtml: String, mergedTtml: String): Boolean"))
        assertTrue(session.contains("mergedCustomBodyPreserved(rawTtml, mergedTtml)"))
        assertTrue(session.contains("CustomLyricsFilePolicy.sha256(mergedTtml.toByteArray(Charsets.UTF_8))"))
        // The merged pointer wins over the raw one but is never allowed to hide
        // it: a dead/absent merged pointer still falls back to the raw cache.
        assertTrue(session.contains("mergedReplacementFor(appleMusicId)?.let { return it }"))
        assertTrue(session.contains("private val mergedPointers"))
    }

    @Test
    fun `a successful completion asks the native delivery for a post-overlay refresh`() {
        val feed = projectFile(
            "core/src/main/kotlin/dev/amenhancer/module/hook/CustomLyricsCompletionFeed.kt",
        )
        // HLE's supplement path refreshes from its own store update
        // (`AppleSupplementDataReceive` → `refreshAppleLyricsSupplementPresentation`).
        // The fork's equivalent is a per-instance callback handed to the feed and
        // forwarded to the native lyric delivery — never a global — so the overlay
        // write is what requests the re-presentation.
        assertTrue(target.contains("var onCustomOverlayUpdated: (Long) -> Unit = {}"))
        assertTrue(
            target.contains(
                "onOverlayUpdated = { appleMusicId -> onCustomOverlayUpdated(appleMusicId) },",
            ),
        )
        assertTrue(target.contains("nativeLyricDelivery?.onCustomOverlayUpdated(appleMusicId)"))
        // The forward is wired after the delivery exists, so an early completion
        // fails open on the default instead of touching an unassigned reference.
        assertTrue(
            target.indexOf("val customCompletion") <
                target.indexOf("nativeLyricDelivery?.onCustomOverlayUpdated(appleMusicId)"),
        )
        // The callback is a feed parameter with a default, so every caller that
        // does not want it stays a no-op.
        assertTrue(feed.contains("private val onOverlayUpdated: (Long) -> Unit = {}"))
    }

    private fun projectFile(relativePath: String): String = sequenceOf(
        File(relativePath),
        File("../$relativePath"),
    ).firstOrNull(File::isFile)?.readText()
        ?: error("$relativePath was not found from the unit-test working directory")
}
