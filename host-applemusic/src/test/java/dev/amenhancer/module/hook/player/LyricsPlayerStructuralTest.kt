package dev.amenhancer.module.hook.player

import java.io.File
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Structural and pure unit coverage of the host bridge (spec §7).
 *
 * The reflection layer can only be exercised on a device, so these tests lock the parts that
 * decide *whether* it is allowed to run at all: the profile contract stays in sync with the
 * code, the degradation path is preserved, and the pure text mapper behaves like the host's
 * own `Html.fromHtml(...).toString()` conversion.
 */
class LyricsPlayerStructuralTest {
    @Test
    fun `profile section declares every role the contract resolves`() {
        val section = profile().getJSONObject("lyricsPlayer")
        assertEquals(1, section.getInt("contractVersion"))
        val methods = section.getJSONObject("methods")
        val fields = section.getJSONObject("fields")
        REQUIRED_METHODS.forEach { role ->
            assertTrue("missing method role $role", methods.has(role))
            val descriptor = methods.getJSONObject(role)
            assertTrue(descriptor.getString("owner").isNotEmpty())
            assertTrue(descriptor.getString("name").isNotEmpty())
            assertTrue(descriptor.getString("returns").isNotEmpty())
            assertFalse(descriptor.getBoolean("static"))
        }
        REQUIRED_FIELDS.forEach { role ->
            assertTrue("missing field role $role", fields.has(role))
            val descriptor = fields.getJSONObject(role)
            assertTrue(descriptor.getString("type").isNotEmpty())
        }
        val classes = section.getJSONObject("classes")
        listOf("fragment", "lineAdapter", "wordAdapter", "binding", "recycler", "mediaController",
            "mediaMetadata", "reader", "timing", "artworkLayer", "activity").forEach { role ->
            assertTrue("missing class role $role", classes.has(role))
        }
    }

    @Test
    fun `the contract requires exactly the roles the profile declares`() {
        val source = source("LyricsPlayerContract.kt")
        REQUIRED_METHODS.forEach { role -> assertTrue("contract does not require $role", source.contains("\"$role\"")) }
        REQUIRED_FIELDS.forEach { role -> assertTrue("contract does not require $role", source.contains("\"$role\"")) }
    }

    @Test
    fun `a missing contract degrades instead of mounting anything`() {
        val target = source("AppleMusicLyricPlayerTarget.kt")
        assertTrue(target.contains("optJSONObject(SECTION) == null"))
        assertTrue(target.contains("TargetCapabilityInstall.Unsupported"))
        assertTrue(target.contains("TargetCapabilityInstall.Degraded"))
        assertTrue(target.contains("lyrics player"))
        // A failed install closes its registration scope and reports the diagnostic.
        assertTrue(target.contains("scope.close()"))
    }

    @Test
    fun `the page is only mounted when the host actually has a document`() {
        val session = source("LyricsPlayerHostSession.kt")
        assertTrue(session.contains("val document = port.document()"))
        assertTrue(session.contains("lyrics document unavailable"))
        // Not-yet-bound is logged separately so a healthy page never reads as a degradation.
        assertTrue(session.contains("lyrics player waiting: "))
        assertTrue(session.contains("hostDocumentAvailable()"))
        // The document check runs before the decor addView, so a blank page cannot appear.
        assertTrue(session.indexOf("port.document()") < session.indexOf("decor.addView"))
        assertTrue(session.contains("\"lyrics player degraded: "))
        assertTrue(session.contains("removeView"))
        assertTrue(session.contains("setSafeInsets"))
    }

    @Test
    fun `the port implements every page-facing member and reports unsupported ones`() {
        val port = source("LyricsPlayerPortImpl.kt")
        listOf("override fun document", "override fun positionMs", "override fun isPlaying",
            "override fun geometry", "override fun seekTo", "override fun track", "override fun artwork",
            "override fun playPause", "override fun skipNext", "override fun skipPrevious",
            "override fun closePage", "override fun more", "override fun preferences").forEach { member ->
            assertTrue("missing $member", port.contains(member))
        }
        assertTrue(port.contains("override fun skipNext(): Boolean = contract.skipNext(controller())"))
        assertTrue(port.contains("override fun skipPrevious(): Boolean = contract.skipPrevious(controller())"))
        assertTrue(port.contains("override fun more(): Boolean = false"))
        // Live settings read: no install-time snapshot of the five sliders.
        assertTrue(port.contains("TargetConfigClient.currentSettings()"))
        assertTrue(port.contains("lyricsPlayerMotionPercent"))
    }

    @Test
    fun `html line text is converted like the host renderer`() {
        assertEquals("hello world", LyricsPlaybackText.plainText("hello <b>world</b>"))
        assertEquals("a & b", LyricsPlaybackText.plainText("a &amp; b"))
        assertEquals("\"quoted\"", LyricsPlaybackText.plainText("&quot;quoted&quot;"))
        assertEquals("line one line two", LyricsPlaybackText.plainText("line one<br/>line two"))
        assertEquals("\u4f60\u597d", LyricsPlaybackText.plainText("&#20320;&#22909;"))
        assertEquals("\u2026", LyricsPlaybackText.plainText("&hellip;"))
        assertEquals("&unknown;", LyricsPlaybackText.plainText("&unknown;"))
        assertEquals("", LyricsPlaybackText.plainText(null))
    }

    @Test
    fun `word text keeps its own spacing while lines collapse it`() {
        // Apple's word vector carries the separator inside the word text; the karaoke width relies on it.
        assertEquals("hello ", LyricsPlaybackText.plainText("hello ", trim = false))
        assertEquals("hello", LyricsPlaybackText.plainText("  hello   "))
        assertEquals("a b", LyricsPlaybackText.plainText("a\u00A0 b"))
    }

    @Test
    fun `the factory wires the target and its fallback source exactly once`() {
        val factory = moduleSource("AppleMusicHostFactory.kt")
        assertTrue(factory.contains("import dev.amenhancer.module.hook.player.LyricsFallbackDocument"))
        assertTrue(factory.contains("lyricsPlayer = AppleMusicLyricPlayerTarget("))
        // One instance, built at compose() time, so the fallback's per-song cache survives polling.
        assertTrue(factory.contains("LyricsFallbackDocument(config, currentSong)"))
        assertEquals(1, Regex("LyricsFallbackDocument\\(").findAll(factory).count())
    }

    private fun moduleSource(relative: String): String = listOf(
        File("src/main/java/dev/amenhancer/module/hook/$relative"),
        File("../host-applemusic/src/main/java/dev/amenhancer/module/hook/$relative"),
    ).firstOrNull(File::isFile)?.readText() ?: error("$relative was not found")

    private fun source(relative: String): String = listOf(
        File("src/main/java/dev/amenhancer/module/hook/player/$relative"),
        File("../host-applemusic/src/main/java/dev/amenhancer/module/hook/player/$relative"),
    ).firstOrNull(File::isFile)?.readText() ?: error("$relative was not found")

    private fun profile(): JSONObject = JSONObject(
        listOf(
            File("src/main/resources/host-profiles/7.0.0-beta-1609.json"),
            File("../host-applemusic/src/main/resources/host-profiles/7.0.0-beta-1609.json"),
        ).firstOrNull(File::isFile)?.readText() ?: error("1609 profile was not found"),
    )

    private companion object {
        val REQUIRED_METHODS = listOf(
            "fragmentCreateView", "fragmentResume", "fragmentPause", "fragmentDestroyView",
            "fragmentInstallDocument", "fragmentPresentDocument",
            "activityStop",
            "mediaBrowser", "mediaPosition", "mediaPlaying", "mediaPlaybackState",
            "mediaDuration", "mediaSeekTo", "mediaPlay", "mediaPause",
            "mediaSkipNext", "mediaSkipPrevious",
            "mediaMetadataPrimary", "mediaMetadataAlternate",
            "lineAdapterReader", "wordAdapterReader", "readerCount", "readerLine",
            "lineResolve", "lineText", "lineTranslation", "lineBackgroundText",
            "lineWords", "lineBackgroundWords", "lineId", "lineBegin", "lineEnd",
            "wordSize", "wordGet", "wordResolve", "wordText", "wordBegin", "wordEnd",
            "wordWhitespace",
            "artworkSet", "artworkDetach",
        )
        val REQUIRED_FIELDS = listOf(
            "fragmentAdapter", "fragmentBinding", "bindingRecycler",
            "metadataTitle", "metadataArtist", "metadataBundle",
        )
    }
}
