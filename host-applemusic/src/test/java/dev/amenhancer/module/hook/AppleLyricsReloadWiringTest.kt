package dev.amenhancer.module.hook

import java.io.File
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Pins the fresh-model half of the presentation refresh: the fork re-invokes
 * Apple's own `PlayerLyricsViewModel#loadLyrics` with the exact (view model,
 * PlaybackItem) pair Apple itself passed (HLE's `AppleLyricsPlaybackBinding`),
 * bounded to once per (song, overlay revision, lane revision), and instruments
 * the native getters so the next exported log proves whether the app
 * re-rendered.
 *
 * The hook itself is reflection over the real Apple Music process and cannot run
 * on the JVM, so these are source/profile pins: they fail if the reload wiring,
 * the anti-loop key, the supplement skip or the render-probe surfaces drift.
 */
class AppleLyricsReloadWiringTest {

    private val hooks = projectFile(
        "host-applemusic/src/main/java/dev/amenhancer/module/hook/AppleNativeLyricModelHooks.kt",
    )

    private val probe = projectFile(
        "core/src/main/kotlin/dev/amenhancer/module/lyrics/online/LyricsRenderProbe.kt",
    )

    @Test
    fun `the reload reuses Apple's own loadLyrics pair instead of inventing a signature`() {
        // The 1606 profile pins LYRICS_VIEW_MODEL_LOAD to
        // PlayerLyricsViewModel#loadLyrics(parameterCount=1) with no parameter
        // type, so the only safe argument is the instance Apple passed.
        assertTrue(hooks.contains("installNativeLoadSeam()"))
        assertTrue(hooks.contains("AppleMusicHookPoint.LYRICS_VIEW_MODEL_LOAD"))
        assertTrue(hooks.contains("private var lyricsLoadMethod: Method?"))
        assertTrue(hooks.contains("private var loadViewModelRef: java.lang.ref.WeakReference<Any>?"))
        assertTrue(hooks.contains("private var loadPlaybackItemRef: java.lang.ref.WeakReference<Any>?"))
        assertTrue(hooks.contains("loadViewModelRef = java.lang.ref.WeakReference(viewModel)"))
        assertTrue(hooks.contains("loadPlaybackItemRef = java.lang.ref.WeakReference(item)"))
        // The HLE binding the reload mirrors, and the exact invoke pair.
        assertTrue(hooks.contains("AppleLyricsPlaybackBinding"))
        assertTrue(hooks.contains("method.invoke(viewModel, item)"))
        // Expected-song check on the remembered item, from HLE's own member.
        assertTrue(hooks.contains("playbackItemSongId(item)"))
        assertTrue(hooks.contains("LYRICS_SONG_ID_METHOD"))
        assertTrue(hooks.contains("RELOAD_SKIPPED_STALE_ITEM"))
    }

    @Test
    fun `the reload skips a module supplement pointer`() {
        // HLE only reloads Apple's own document (recoverBlankNativeLyricsPage)
        // and lets the supplement path own its own re-presentation, because
        // Apple's track refresh on a supplement pointer makes the page twitch.
        assertTrue(hooks.contains("if (!state.sourceIsApple) return RELOAD_SKIPPED_SUPPLEMENT"))
        assertTrue(hooks.contains("const val RELOAD_SKIPPED_SUPPLEMENT = \"skipped(supplement-pointer)\""))
    }

    @Test
    fun `the reload is bounded to one per song, overlay revision and lane revision`() {
        // The hard anti-loop stop: a reload never advances the overlay revision
        // and the key is recorded before the invoke, so a re-entrant build from
        // our own load can never ask for a second reload. The lane revision is in
        // the key so the late lane-ready ask can rebuild the model even when the
        // build gate already reloaded the same overlay revision; it only advances
        // on a real lane edge, so the bound stays finite.
        assertTrue(hooks.contains("private data class ReloadKey("))
        assertTrue(hooks.contains("val laneRevision: Long,"))
        assertTrue(hooks.contains("private var lastReloadKey: ReloadKey?"))
        assertTrue(hooks.contains("if (lastReloadKey == key) return RELOAD_SKIPPED_SAME_REVISION"))
        assertTrue(hooks.contains("lastReloadKey = key"))
        assertTrue(
            hooks.indexOf("lastReloadKey = key") < hooks.indexOf("method.invoke(viewModel, item)"),
        )
        assertTrue(hooks.contains("ReloadKey(state.songId, state.overlayRevision, state.laneRevision)"))
    }

    @Test
    fun `the reload runs on the main handler only after a latched invoke`() {
        // performPresentationRefresh runs on the main handler; the reload is its
        // last step, so our own presentation invoke is finished and the invoke
        // guard is clear before the app's load is re-run.
        val perform = hooks
            .substringAfter("private fun performPresentationRefresh(")
            .substringBefore("private fun logPresentationRefresh(")
        assertTrue(perform.contains("if (outcome.latches)"))
        assertTrue(perform.contains("reloadLyricsForRefresh(state)"))
        assertTrue(perform.contains("reloadSkipped(outcome)"))
        // Every attempt is dispatched through the main handler, so the reload
        // runs there too.
        assertTrue(
            hooks.contains(
                "mainHandler.post { performPresentationRefresh(state, PresentationRefreshTrigger.BUILD) }",
            ),
        )
        assertTrue(
            hooks.contains(
                "mainHandler.post {\n            performPresentationRefresh(pending, PresentationRefreshTrigger.F2_RETRY)\n        }",
            ),
        )
        assertTrue(hooks.contains("if (presentationInvokeGuard.get() == true) return RELOAD_SKIPPED_INVOKE_GUARD"))
        // Arming the probe happens before the invoke so the app's re-read is
        // attributed to this attempt.
        assertTrue(perform.contains("renderProbe.arm(state.songId)"))
        assertTrue(
            perform.indexOf("renderProbe.arm(state.songId)") <
                perform.indexOf("presentationInvokeGuard.set(true)"),
        )
    }

    @Test
    fun `the reload decision and outcome are on the presentation-refresh line`() {
        assertTrue(hooks.contains("reload=\$reload"))
        assertTrue(hooks.contains("const val RELOAD_REQUESTED = \"requested\""))
        assertTrue(hooks.contains("const val RELOAD_INVOKED = \"invoked\""))
        assertTrue(hooks.contains("const val RELOAD_FAILED = \"failed\""))
        // The decision line asks; the attempt line reports the real outcome.
        assertTrue(hooks.contains("reload = reloadIntent(state, shouldRefresh)"))
        assertTrue(hooks.contains("!shouldRefresh -> RELOAD_SKIPPED_GATE"))
        assertTrue(hooks.contains("else -> RELOAD_REQUESTED"))
    }

    @Test
    fun `the render probe covers the line getter, the word getter and the adapter`() {
        assertTrue(hooks.contains("private val renderProbe = LyricsRenderProbe(log)"))
        assertTrue(hooks.contains("LyricsRenderProbe.Phase.GETTER"))
        assertTrue(hooks.contains("LyricsRenderProbe.Phase.WORD_GETTER"))
        assertTrue(hooks.contains("LyricsRenderProbe.Phase.ADAPTER_BIND"))
        assertTrue(hooks.contains("private fun recordRenderProbe("))
        assertTrue(hooks.contains("renderProbe.isArmed(songId)"))
        // The word-render adapter plan consumption is the strongest re-render proof.
        assertTrue(hooks.contains("const val PROBE_RENDER_PLAN = \"render-plan\""))
        assertTrue(hooks.contains("getter = getterName"))
        // The probe line itself is emitted by the core class, on the same visible
        // channel as native-write/presentation-refresh.
        assertTrue(probe.contains("\"online-translation render-probe phase="))
        assertTrue(probe.contains("afterRefresh=true attempt="))
        assertTrue(probe.contains("official=\$official online=\$online"))
        assertTrue(probe.contains("enum class Phase(val token: String)"))
        assertTrue(probe.contains("DEFAULT_MAX_LINES_PER_TRACK"))
    }

    @Test
    fun `the probe is deduped and bounded, and ignores pre-refresh reads`() {
        assertTrue(probe.contains("if (id != currentTrack || attempt <= 0) return"))
        assertTrue(probe.contains("if (emitted >= maxLinesPerTrack) return"))
        assertTrue(probe.contains("if (!seen.add(key)) return"))
        assertTrue(probe.contains("val key = \"\$attempt:\${phase.token}:\$getter:\$result\""))
        assertTrue(probe.contains("fun arm(songId: Long)"))
        assertTrue(probe.contains("fun isArmed(songId: Long): Boolean"))
    }

    @Test
    fun `the reload never changed the existing supplement refresh`() {
        // The pre-existing behaviours the change must not disturb.
        assertTrue(hooks.contains("presentationRebind.rebind(fragment)"))
        assertTrue(hooks.contains("pendingPresentationRefresh"))
        assertTrue(hooks.contains("presentationInvokeGuard"))
        assertTrue(hooks.contains("!isModuleSupplementSong(songId)"))
        assertTrue(hooks.contains("PresentationRefreshTrigger.F2_RETRY"))
        // The reload is an addition, not a replacement: the invoke and rebind
        // still run for every accepted refresh.
        assertTrue(hooks.contains("method.invoke(fragment, pointer)"))
    }

    private fun projectFile(relativePath: String): String = sequenceOf(
        File(relativePath),
        File("../$relativePath"),
        File("../../$relativePath"),
    ).firstOrNull(File::isFile)?.readText()
        ?: error("$relativePath was not found from the unit-test working directory")
}
