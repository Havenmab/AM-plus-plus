package dev.amenhancer.module.lyrics.source

import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * Pins the seam the sheet and the host share: the [LyricsSourceAccess] singleton
 * lifecycle and the interface's default implementations, so a host that covers
 * only part of the contract still behaves predictably.
 */
class LyricsSourceControllerContractTest {

    @Before
    fun resetAccess() {
        LyricsSourceAccess.controller = null
    }

    @After
    fun clearAccess() {
        LyricsSourceAccess.controller = null
    }

    @Test
    fun `the access singleton installs and removes by identity`() {
        val first = FakeController()
        val second = FakeController()

        assertFalse(LyricsSourceAccess.available)
        assertNull(LyricsSourceAccess.snapshot())

        LyricsSourceAccess.install(first)
        assertTrue(LyricsSourceAccess.available)
        assertSame(first, LyricsSourceAccess.controller)

        LyricsSourceAccess.install(second)
        // A stale host generation must not remove the controller that replaced it.
        LyricsSourceAccess.uninstall(first)
        assertSame(second, LyricsSourceAccess.controller)

        LyricsSourceAccess.uninstall(second)
        assertNull(LyricsSourceAccess.controller)
        assertFalse(LyricsSourceAccess.available)
    }

    @Test
    fun `the snapshot is read through the singleton`() {
        val snapshot = snapshot()
        LyricsSourceAccess.install(FakeController(snapshot = snapshot))

        assertEquals(snapshot, LyricsSourceAccess.snapshot())
    }

    @Test
    fun `clearAll falls back to clearing every lane`() {
        val controller = FakeController()

        val result = controller.clearAll()

        assertTrue(result.ok)
        assertEquals(LyricLane.ALL, controller.clearedLanes)
    }

    @Test
    fun `clearAll reports a rejected lane`() {
        val controller = FakeController(
            clearResult = ApplyResult.failed(LyricSourceErrorCodes.NOT_SUPPORTED, "不支持"),
        )

        val result = controller.clearAll()

        assertFalse(result.ok)
        assertEquals(LyricSourceErrorCodes.NOT_SUPPORTED, result.errorCode)
        assertEquals(LyricLane.ALL, controller.clearedLanes)
    }

    @Test
    fun `preview reports unsupported by default`() {
        val controller = FakeController()
        var outcome: Result<LyricsSourcePreview>? = null

        controller.preview(appleOption(), LyricLane.ORIGINAL) { outcome = it }

        assertTrue(outcome!!.isFailure)
        assertTrue(outcome!!.exceptionOrNull()!!.message!!.isNotBlank())
    }

    @Test
    fun `a preview carries the precision of the document it fetched`() {
        val lines = listOf(LyricsSourcePreviewLine("夜に駆ける", "夜跑"))
        val unknown = LyricsSourcePreview(lines = lines)
        assertFalse(unknown.hasWords)
        assertFalse(unknown.precisionKnown)

        // Field order is (lines, precisionKnown, hasWords); both flags are named here
        // because the sheet builds them positionally in the documented order.
        val known = LyricsSourcePreview(lines, true, true)
        var outcome: Result<LyricsSourcePreview>? = null

        FakeController(previewResult = Result.success(known))
            .preview(appleOption(), LyricLane.ORIGINAL) { outcome = it }

        val preview = outcome!!.getOrNull()!!
        assertEquals(known, preview)
        assertEquals(lines, preview.lines)
        assertTrue(preview.hasWords)
        assertTrue(preview.precisionKnown)
    }

    @Test
    fun `observe returns a closable no-op handle by default`() {
        val controller = FakeController()

        val handle = controller.observe { }

        handle.close()
        handle.close()
    }

    @Test
    fun `search is a callback exactly once`() {
        val controller = FakeController()
        var calls = 0
        var options: List<LyricSourceOption>? = null

        controller.search("夜に駆ける", LyricLane.ORIGINAL) { result ->
            calls += 1
            options = result.getOrNull()
        }

        assertEquals(1, calls)
        assertEquals(emptyList<LyricSourceOption>(), options)
    }

    private fun appleOption(): LyricSourceOption = LyricSourceOption(
        key = LyricSourceKeys.APPLE,
        kind = LyricSourceKind.APPLE,
        providerId = LyricSourceProviders.APPLE,
        label = "Apple Music",
    )

    private fun snapshot(): LyricsSourceSnapshot {
        val option = appleOption()
        return LyricsSourceSnapshot(
            song = LyricSongContext(songId = 42L, title = "夜に駆ける"),
            lanes = LyricLane.ALL.map { lane ->
                LyricLaneState(lane = lane, selected = option, options = listOf(option))
            },
        )
    }

    private class FakeController(
        private val snapshot: LyricsSourceSnapshot? = null,
        private val clearResult: ApplyResult = ApplyResult.APPLIED,
        private val previewResult: Result<LyricsSourcePreview>? = null,
    ) : LyricsSourceController {

        val clearedLanes = mutableListOf<LyricLane>()

        override fun snapshot(): LyricsSourceSnapshot? = snapshot

        override fun search(
            query: String,
            lane: LyricLane,
            onDone: (Result<List<LyricSourceOption>>) -> Unit,
        ) {
            onDone(Result.success(emptyList()))
        }

        override fun apply(option: LyricSourceOption, lane: LyricLane): ApplyResult = ApplyResult.APPLIED

        override fun clear(lane: LyricLane): ApplyResult {
            clearedLanes += lane
            return clearResult
        }

        override fun preview(
            option: LyricSourceOption,
            lane: LyricLane,
            onDone: (Result<LyricsSourcePreview>) -> Unit,
        ) {
            val prepared = previewResult
            if (prepared == null) {
                super.preview(option, lane, onDone)
            } else {
                onDone(prepared)
            }
        }
    }
}
