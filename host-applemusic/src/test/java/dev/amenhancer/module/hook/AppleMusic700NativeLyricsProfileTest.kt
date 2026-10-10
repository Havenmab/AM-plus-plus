package dev.amenhancer.module.hook

import io.github.proify.lyricon.amprovider.xposed.AppleMusicHookPoint
import io.github.proify.lyricon.amprovider.xposed.AppleMusicHookProfiles
import io.github.proify.lyricon.amprovider.xposed.AppleMusicRuntimeMember
import io.github.proify.lyricon.amprovider.xposed.AppleMusicVersion
import io.github.proify.lyricon.amprovider.xposed.expandAppleLyricsPronunciationLanguages
import io.github.proify.lyricon.amprovider.xposed.expandAppleLyricsTranslationLanguages
import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Pins HLE's model-level pronunciation delivery onto the exact 7.0.0-beta
 * (1606) profile.
 *
 * The document-lane injection does not make Apple render romanization on 1606
 * (device-confirmed), so the fork also writes through the app's own lyric
 * model. That path needs the profile's `LYRICS_VIEW_MODEL_LOAD`
 * `runtimeMemberNames` dictionary, the native-model seam, and the language
 * surfaces; these tests fail if any of them drifts.
 */
class AppleMusic700NativeLyricsProfileTest {

    private val version = AppleMusicVersion("7.0.0-beta", 1606L)

    private fun target(point: AppleMusicHookPoint) =
        AppleMusicHookProfiles.exactTargets(version, point).single()

    @Test
    fun `1606 pins HLE's native lyric-model member dictionary verbatim`() {
        val load = target(AppleMusicHookPoint.LYRICS_VIEW_MODEL_LOAD)

        assertEquals(
            "com.apple.android.music.player.viewmodel.PlayerLyricsViewModel",
            load.className,
        )
        assertEquals("loadLyrics", load.methodName)
        assertEquals(1, load.parameterCount)

        fun member(name: AppleMusicRuntimeMember) = load.runtimeMemberNames[name]
        assertEquals("setPronunciation", member(AppleMusicRuntimeMember.LYRICS_NATIVE_SET_PRONUNCIATION_METHOD))
        assertEquals("hasPronunciation", member(AppleMusicRuntimeMember.LYRICS_NATIVE_HAS_PRONUNCIATION_METHOD))
        assertEquals("setTranslation", member(AppleMusicRuntimeMember.LYRICS_NATIVE_SET_TRANSLATION_METHOD))
        assertEquals("hasTranslation", member(AppleMusicRuntimeMember.LYRICS_NATIVE_HAS_TRANSLATION_METHOD))
        assertEquals(
            "getHtmlPronunciationLineText",
            member(AppleMusicRuntimeMember.LYRICS_NATIVE_PRONUNCIATION_TEXT_METHOD),
        )
        assertEquals(
            "getHtmlTranslationLineText",
            member(AppleMusicRuntimeMember.LYRICS_NATIVE_TRANSLATION_TEXT_METHOD),
        )
        assertEquals("getHtmlLineText", member(AppleMusicRuntimeMember.LYRICS_NATIVE_LINE_TEXT_METHOD))
        assertEquals(
            "getPronunciationLanguages",
            member(AppleMusicRuntimeMember.LYRICS_NATIVE_SONG_PRONUNCIATION_LANGUAGES_METHOD),
        )
        assertEquals(
            "getTranslationLanguages",
            member(AppleMusicRuntimeMember.LYRICS_NATIVE_SONG_TRANSLATION_LANGUAGES_METHOD),
        )
        assertEquals(
            "getCurrentSystemLyricsLanguage",
            member(AppleMusicRuntimeMember.LYRICS_VIEW_MODEL_CURRENT_LANGUAGE_METHOD),
        )
        assertEquals("get", member(AppleMusicRuntimeMember.LYRICS_NATIVE_POINTER_GET_METHOD))
        assertEquals("get", member(AppleMusicRuntimeMember.LYRICS_NATIVE_VECTOR_GET_METHOD))
        assertEquals("size", member(AppleMusicRuntimeMember.LYRICS_NATIVE_VECTOR_SIZE_METHOD))
        assertEquals("getSections", member(AppleMusicRuntimeMember.LYRICS_NATIVE_SONG_SECTIONS_METHOD))
        assertEquals("getLines", member(AppleMusicRuntimeMember.LYRICS_NATIVE_SECTION_LINES_METHOD))
        assertEquals("getBegin", member(AppleMusicRuntimeMember.LYRICS_NATIVE_BEGIN_METHOD))
        assertEquals("getEnd", member(AppleMusicRuntimeMember.LYRICS_NATIVE_END_METHOD))
        // The word-level getters the line-level PRs never referenced. Apple
        // renders most lyrics per word, so these are the delivery surface.
        assertEquals(
            "getPronunciationWords",
            member(AppleMusicRuntimeMember.LYRICS_NATIVE_PRONUNCIATION_WORDS_METHOD),
        )
        assertEquals(
            "getPronunciationBackgroundWords",
            member(AppleMusicRuntimeMember.LYRICS_NATIVE_PRONUNCIATION_BACKGROUND_WORDS_METHOD),
        )
        assertEquals(
            "getHtmlPronunciationBackgroundVocalsLineText",
            member(AppleMusicRuntimeMember.LYRICS_NATIVE_PRONUNCIATION_BACKGROUND_TEXT_METHOD),
        )
        assertEquals("getWords", member(AppleMusicRuntimeMember.LYRICS_NATIVE_WORDS_METHOD))
        assertEquals(
            "getBackgroundWords",
            member(AppleMusicRuntimeMember.LYRICS_NATIVE_BACKGROUND_WORDS_METHOD),
        )
        assertEquals(
            "com.apple.android.music.ttml.javanative.model.LyricsWordVector",
            member(AppleMusicRuntimeMember.LYRICS_WORD_VECTOR_CLASS_NAME),
        )
        assertEquals(39, load.runtimeMemberNames.size)
    }

    @Test
    fun `1606 pins the word render adapter and the native vector class`() {
        val adapters = AppleMusicHookProfiles.exactTargets(
            version,
            AppleMusicHookPoint.LYRICS_WORD_RENDER_ADAPTER,
        )
        assertTrue(adapters.isNotEmpty())
        // HLE's 1606 profile inherits player.A from the 6.5.x line. player.C is
        // HLE's *1607* override, not a 1606 value: pinning it made the exact 1606
        // profile resolve a class the verified build may not have. The render scan
        // itself requires a LyricsWordVector -> ArrayMap method, so a same-named
        // unrelated class is never hooked anyway.
        assertEquals(
            listOf("com.apple.android.music.player.A"),
            adapters.map { it.className },
        )
        assertTrue(adapters.none { it.allowFirstMatch })

        val vector = target(AppleMusicHookPoint.LYRICS_WORD_VECTOR_CLASS)
        assertEquals(
            "com.apple.android.music.ttml.javanative.model.LyricsWordVector",
            vector.className,
        )
        assertNull(vector.methodName)
    }

    @Test
    fun `1606 pins HLE's preference and lyrics-view surfaces`() {
        // HLE's 1606 APPLE_SHARED_PREFERENCES_CLASS override: ja.i0 with the
        // cache-first preference members the fork's AppleLyricsPreferenceReader
        // ports. g/s/k/h/d are HLE's exact runtime names.
        val preferences = target(AppleMusicHookPoint.APPLE_SHARED_PREFERENCES_CLASS)
        assertEquals("ja.i0", preferences.className)
        fun member(name: AppleMusicRuntimeMember) = preferences.runtimeMemberNames[name]
        assertEquals("g", member(AppleMusicRuntimeMember.LYRICS_PREFERENCES_TRANSLATION_GETTER))
        assertEquals("s", member(AppleMusicRuntimeMember.LYRICS_PREFERENCES_PRONUNCIATION_CACHE_FIELD))
        assertEquals("k", member(AppleMusicRuntimeMember.LYRICS_PREFERENCES_PRONUNCIATION_KEY_FIELD))
        assertEquals("h", member(AppleMusicRuntimeMember.LYRICS_PREFERENCES_STORE_GETTER))
        assertEquals("d", member(AppleMusicRuntimeMember.LYRICS_PREFERENCES_STORE_READ_METHOD))

        // HLE's pronunciation preference setter: ja.i0#m(boolean) static void.
        val preferenceTarget = target(AppleMusicHookPoint.LYRICS_PRONUNCIATION_PREFERENCE)
        assertEquals("ja.i0", preferenceTarget.className)
        assertEquals("m", preferenceTarget.methodName)
        assertEquals(1, preferenceTarget.parameterCount)
        assertEquals(listOf("boolean"), preferenceTarget.parameterTypeNames)
        assertEquals("void", preferenceTarget.returnTypeName)
        assertEquals(true, preferenceTarget.isStatic)

        // HLE's early fragment registration: onCreateView(3) plus the 1606-only
        // binding field n0 and the recycler accessor getRecyclerView.
        val create = target(AppleMusicHookPoint.LYRICS_UI_ON_CREATE_VIEW)
        assertEquals(
            "com.apple.android.music.player.fragment.PlayerLyricsViewFragment",
            create.className,
        )
        assertEquals("onCreateView", create.methodName)
        assertEquals(3, create.parameterCount)
        assertEquals(
            listOf("android.view.LayoutInflater", "android.view.ViewGroup", "android.os.Bundle"),
            create.parameterTypeNames,
        )
        assertEquals("android.view.View", create.returnTypeName)
        assertEquals(false, create.isStatic)
        assertEquals(
            "getRecyclerView",
            create.runtimeMemberNames[AppleMusicRuntimeMember.LYRICS_UI_RECYCLER_VIEW_METHOD],
        )
        assertEquals(
            "n0",
            create.runtimeMemberNames[AppleMusicRuntimeMember.LYRICS_UI_BINDING_FIELD],
        )

        val destroy = target(AppleMusicHookPoint.LYRICS_UI_ON_DESTROY_VIEW)
        assertEquals(
            "com.apple.android.music.player.fragment.PlayerLyricsViewFragment",
            destroy.className,
        )
        assertEquals("onDestroyView", destroy.methodName)
        assertEquals(0, destroy.parameterCount)
    }

    @Test
    fun `1606 pins the native model seam and the language surfaces`() {
        val build = target(AppleMusicHookPoint.LYRICS_VIEW_MODEL_BUILD)
        assertEquals(
            "com.apple.android.music.player.viewmodel.PlayerLyricsViewModel",
            build.className,
        )
        assertEquals("buildTimeRangeToLyricsMap", build.methodName)
        assertEquals(1, build.parameterCount)

        val preferred = target(AppleMusicHookPoint.LYRICS_PREFERRED_LANGUAGES_REQUEST)
        assertEquals(
            "com.apple.android.music.player.viewmodel.PlayerLyricsViewModel\$f",
            preferred.className,
        )
        assertNull(preferred.methodName)

        val match = target(AppleMusicHookPoint.LYRICS_OFFICIAL_PRONUNCIATION_MATCH)
        assertEquals("com.apple.android.music.playback.util.LocaleUtil", match.className)
        assertEquals("matchToSystemLyricsScript", match.methodName)
        assertEquals(1, match.parameterCount)
    }

    @Test
    fun `every 1606 lyrics target stays exact, unambiguous and first-match free`() {
        val profile = AppleMusicHookProfiles.profileFor(version)
        assertNotNull(profile)
        assertTrue(profile!!.strict)
        listOf(
            AppleMusicHookPoint.LYRICS_VIEW_MODEL_LOAD,
            AppleMusicHookPoint.LYRICS_VIEW_MODEL_BUILD,
            AppleMusicHookPoint.LYRICS_PREFERRED_LANGUAGES_REQUEST,
            AppleMusicHookPoint.LYRICS_OFFICIAL_PRONUNCIATION_MATCH,
            AppleMusicHookPoint.LYRICS_WORD_RENDER_ADAPTER,
            AppleMusicHookPoint.LYRICS_WORD_VECTOR_CLASS,
        ).forEach { point ->
            assertEquals(
                AppleMusicHookProfiles.exactTargets(version, point),
                AppleMusicHookProfiles.candidates(version, point),
            )
            assertTrue(AppleMusicHookProfiles.exactTargets(version, point).none { it.allowFirstMatch })
        }
    }

    @Test
    fun `HLE's language expansion adds the Latn tags`() {
        assertEquals(
            listOf("ja", "ja-Latn", "ko-Latn", "zh-Latn"),
            expandAppleLyricsPronunciationLanguages(listOf("ja")),
        )
        assertEquals(
            listOf("ja-Latn", "ko-Latn", "zh-Latn"),
            expandAppleLyricsPronunciationLanguages(emptyList()),
        )
        // An already-expanded list stays stable.
        assertEquals(
            listOf("ja", "ja-Latn", "ko-Latn", "zh-Latn"),
            expandAppleLyricsPronunciationLanguages(
                listOf("ja", "ja-Latn", "ko-Latn", "zh-Latn"),
            ),
        )
        assertTrue(
            expandAppleLyricsTranslationLanguages(listOf("zh-Hans")).contains("zh-Hans-CN"),
        )
        assertTrue(
            expandAppleLyricsTranslationLanguages(listOf("zh-Hant-HK")).contains("zh-HK"),
        )
    }

    @Test
    fun `the native delivery is wired from the runtime through the custom-lyrics target`() {
        val hooks = projectFile(
            "host-applemusic/src/main/java/dev/amenhancer/module/hook/AppleNativeLyricModelHooks.kt",
        )
        assertTrue(hooks.contains("LYRICS_VIEW_MODEL_BUILD"))
        assertTrue(hooks.contains("LYRICS_NATIVE_SET_PRONUNCIATION_METHOD"))
        assertTrue(hooks.contains("LYRICS_NATIVE_HAS_PRONUNCIATION_METHOD"))
        assertTrue(hooks.contains("LYRICS_PREFERRED_LANGUAGES_REQUEST"))
        assertTrue(hooks.contains("LYRICS_OFFICIAL_PRONUNCIATION_MATCH"))
        assertTrue(hooks.contains("online-translation native-write"))
        assertTrue(hooks.contains("expandAppleLyricsPronunciationLanguages"))

        val target = projectFile(
            "host-applemusic/src/main/java/dev/amenhancer/module/hook/AppleMusicCustomLyricsTarget.kt",
        )
        assertTrue(target.contains("nativeLyricDelivery: NativeLyricsTarget? = null"))
        assertTrue(target.contains("nativeLyricDelivery?.onLyricsPointer(original)"))
        assertTrue(target.contains("nativeLyricDelivery?.onLyricsPresentation(param.thisObject, original)"))

        val factory = projectFile(
            "host-applemusic/src/main/java/dev/amenhancer/module/hook/AppleMusicHostFactory.kt",
        )
        assertTrue(factory.contains("AppleMusicVersion(build.versionName, build.versionCode)"))
        assertTrue(factory.contains("nativeLyrics = nativeLyricsTarget"))
        assertTrue(factory.contains("AppleMusicNativeLyricsTarget("))

        val runtime = projectFile(
            "app/src/main/java/dev/amenhancer/module/hook/AutoLyricsReplacementSession.kt",
        )
        assertTrue(runtime.contains("nativeLyricOverlay: NativeLyricOverlayStore"))
        assertTrue(runtime.contains("nativeLyricOverlay = nativeLyricOverlay"))
        assertTrue(runtime.contains("overlay?.update("))
    }

    @Test
    fun `the language selection never gates Apple's own lane behind the build-time probe`() {
        val hooks = projectFile(
            "host-applemusic/src/main/java/dev/amenhancer/module/hook/AppleNativeLyricModelHooks.kt",
        )
        // Apple's advertised lane wins; an empty vector is not special-cased
        // away from the third-party fallback, which the planner still resolves.
        assertTrue(hooks.contains("NativeLyricModelPolicy.planPronunciationSelection("))
        // The host must not run the un-guarded primitive directly.
        assertFalse(hooks.contains("NativeLyricModelPolicy.selectPronunciationLanguage("))
        // The HLE per-line probe is kept, but only for availability/diagnostics.
        assertTrue(hooks.contains("private fun hasValidOfficialPronunciation("))
        // The stale shape that displaced Apple: firstOrNull()?.takeIf { cached }.
        assertFalse(hooks.contains("takeIf { hasValidOfficialPronunciation }"))
        // The device log must be able to prove the decision.
        assertTrue(hooks.contains("officialAtBuild="))
        assertTrue(hooks.contains("officialLanguage="))
        assertTrue(hooks.contains("selectedLanguage="))
        assertTrue(hooks.contains("appleLanguagesKnown="))
    }

    @Test
    fun `an empty Apple vector selects nothing and the late-lane trigger stays armed`() {
        val hooks = projectFile(
            "host-applemusic/src/main/java/dev/amenhancer/module/hook/AppleNativeLyricModelHooks.kt",
        )
        // Apple-only: the planner is handed Apple's advertised languages and
        // nothing else. The third-party fallback language is gone.
        assertTrue(hooks.contains("NativeLyricModelPolicy.planPronunciationSelection("))
        assertTrue(hooks.contains("appleLanguages = advertised,"))
        assertFalse(hooks.contains("thirdPartyFallbackLanguage"))
        assertFalse(hooks.contains("fallbackLanguage("))
        assertFalse(hooks.contains("THIRD_PARTY"))
        // The last non-empty advertisement is kept, so a transient empty read
        // cannot withdraw an Apple lane already seen (PR #9).
        assertTrue(hooks.contains("NativeLyricModelPolicy.advertisedPronunciationLanguages("))
        // Nothing selected yet leaves the selection open, so every later entry
        // point re-runs it and picks Apple's lane up when it appears.
        assertTrue(hooks.contains("pronunciationSelectionOpen"))
        assertTrue(hooks.contains("refreshPronunciationSelectionIfOpen("))
        // The old flag implied "nothing selected" and is gone. (The KDoc still
        // quotes the old log line, so match the emitted field literal, not the
        // bare token.)
        assertFalse(hooks.contains("pronunciationSelectionDeferred"))
        assertFalse(hooks.contains("\"deferred="))
        // The diagnostic names the Apple-only branch honestly instead of
        // implying a stall.
        assertTrue(hooks.contains("selection=\${selection.selection.token}"))
        assertTrue(hooks.contains("reason=\${selection.reason}"))
    }

    @Test
    fun `the selection re-evaluates when Apple advertises its pronunciation lane`() {
        val hooks = projectFile(
            "host-applemusic/src/main/java/dev/amenhancer/module/hook/AppleNativeLyricModelHooks.kt",
        )
        // Re-evaluation points: the song's own language query, the availability
        // overrides, the pronunciation line getter and the preferred-language
        // request must re-run the decision instead of trusting the build pass.
        assertTrue(hooks.contains("LYRICS_NATIVE_SONG_PRONUNCIATION_LANGUAGES_METHOD"))
        assertTrue(hooks.contains("installPronunciationLanguageQueryHook"))
        assertTrue(hooks.contains("refreshPronunciationSelectionIfOpen("))
        assertTrue(hooks.contains("pronunciationSelectionOpen"))
        // The query hook is installed per song class alongside the availability
        // hooks, so an advertisement that arrives late is observed.
        assertTrue(
            hooks.contains("installPronunciationLanguageQueryHook(songNative.javaClass)"),
        )
    }

    @Test
    fun `the native delivery wires HLE's word track instead of only the line getter`() {
        val hooks = projectFile(
            "host-applemusic/src/main/java/dev/amenhancer/module/hook/AppleNativeLyricModelHooks.kt",
        )
        // HLE's three word-level surfaces, none of which the line-level PRs used.
        assertTrue(hooks.contains("LYRICS_NATIVE_PRONUNCIATION_WORDS_METHOD"))
        assertTrue(hooks.contains("LYRICS_NATIVE_PRONUNCIATION_BACKGROUND_WORDS_METHOD"))
        assertTrue(hooks.contains("LYRICS_NATIVE_PRONUNCIATION_BACKGROUND_TEXT_METHOD"))
        // The HLE policy decides the track and aligns the romanization. The host
        // now calls the Apple-first planner (which itself calls `wordTrack`), so
        // Apple's own word vector wins even when Apple left the line text empty.
        assertTrue(hooks.contains("ApplePronunciationPolicy.planPronunciationWords("))
        assertTrue(hooks.contains("ApplePronunciationPolicy.displaySegments("))
        // The render plan is consumed by the app's own word-render adapter, and
        // the vector type comes from the profile, never a guessed signature.
        assertTrue(hooks.contains("LYRICS_WORD_RENDER_ADAPTER"))
        assertTrue(hooks.contains("LYRICS_WORD_VECTOR_CLASS_NAME"))
        assertTrue(hooks.contains("emptyPronunciationWords("))
        // No synthesized native word: the adapter dereferences the parent line.
        assertFalse(hooks.contains("LyricsWordPtr"))
        assertFalse(hooks.contains("pushBack("))
        // The next device log must prove the app asks for word-level data.
        assertTrue(hooks.contains("online-translation pronunciation-words"))
        assertTrue(hooks.contains("track=\${track.name}"))
        assertTrue(hooks.contains("words=\${vectorSize(resolved)}"))
    }

    @Test
    fun `the word diagnostic explains the decision and the late-word ask is wired`() {
        val hooks = projectFile(
            "host-applemusic/src/main/java/dev/amenhancer/module/hook/AppleNativeLyricModelHooks.kt",
        )
        // Task-4 diagnostics: the next log must carry the decision inputs, not
        // only the winning track. `mainTiming` is gone with the third-party text
        // lane it described.
        assertTrue(hooks.contains("officialWords=\$officialWords"))
        assertTrue(hooks.contains("compat=\$officialWordsCompatible"))
        assertFalse(hooks.contains("mainTiming=\${mainTimingSource.token}"))
        assertFalse(hooks.contains("ApplePronunciationTextSource"))
        assertTrue(hooks.contains("selected=\${track.name}"))

        // Late-word self-correction, mirroring the lane-ready trigger: the first
        // OFFICIAL answer after a line had answered HIDDEN asks for one guarded
        // refresh.
        assertTrue(hooks.contains("PresentationRefreshTrigger.WORD_READY"))
        assertTrue(hooks.contains("\"word-ready\""))
        assertTrue(hooks.contains("private fun onPronunciationWordReady("))
        assertTrue(hooks.contains("announceWordReadyIfAppleWordsArrived("))
        assertTrue(hooks.contains("NativeLyricModelPolicy.pronunciationWordReadyKey("))
        assertTrue(hooks.contains("wordDecisionSawHidden"))
        // The hard anti-loop bound: the per-song key is recorded *before* the ask.
        assertTrue(hooks.contains("if (key == wordReadyKey) return"))
        assertTrue(hooks.contains("wordReadyKey = key"))
        val wordReady = hooks
            .substringAfter("private fun onPronunciationWordReady(")
            .substringBefore("private fun requestPreferencePresentationRefresh(")
        assertTrue(wordReady.contains("if (isModuleSupplementSong(songId)) return"))
        assertTrue(wordReady.contains("wordReadyKey = key"))
    }

    @Test
    fun `the preference surface is hooked and the real preference replaces the substitute`() {
        val hooks = projectFile(
            "host-applemusic/src/main/java/dev/amenhancer/module/hook/AppleNativeLyricModelHooks.kt",
        )
        // HLE's onAppleLyricsDisplayPreferenceChanged(PRONUNCIATION): clear the
        // pending one-shot word plans and re-present.
        assertTrue(hooks.contains("LYRICS_PRONUNCIATION_PREFERENCE"))
        assertTrue(hooks.contains("private fun installPronunciationPreferenceHook("))
        assertTrue(hooks.contains("internal fun onPronunciationPreferenceChanged("))
        assertTrue(hooks.contains("pendingPronunciationRenderPlans.clear()"))
        // The real Apple preference, cache-first, fail-open.
        assertTrue(hooks.contains("AppleLyricsPreferenceReader.isPronunciationSelected("))
        assertTrue(hooks.contains("AppleLyricsPreferenceReader.isTranslationSelected("))
        // The old `enabled && !mandarinHidden` substitute is gone from every gate.
        assertFalse(hooks.contains("pronunciationSelected = enabled && !mandarinHidden,"))
        assertFalse(hooks.contains("pronunciationPreferenceAvailable &&"))

        // HLE's early fragment registration and onDestroyView cleanup.
        assertTrue(hooks.contains("private fun installLyricsViewFragmentSeam("))
        assertTrue(hooks.contains("LYRICS_UI_ON_CREATE_VIEW"))
        assertTrue(hooks.contains("LYRICS_UI_ON_DESTROY_VIEW"))
        assertTrue(hooks.contains("private fun cleanupPresentationBinding("))

        val reader = projectFile(
            "host-applemusic/src/main/java/dev/amenhancer/module/hook/AppleLyricsPreferenceReader.kt",
        )
        // HLE's read order: getter -> cache field -> snapshot -> key/store read.
        val getter = reader.indexOf("LYRICS_PREFERENCES_PRONUNCIATION_GETTER")
        val cache = reader.indexOf("LYRICS_PREFERENCES_PRONUNCIATION_CACHE_FIELD")
        val snapshot = reader.indexOf("if (snapshot != null) return snapshot")
        val store = reader.indexOf("LYRICS_PREFERENCES_STORE_READ_METHOD")
        assertTrue(getter in 0 until cache)
        assertTrue(cache in 0 until snapshot)
        assertTrue(snapshot in 0 until store)
    }

    private fun projectFile(relativePath: String): String = sequenceOf(
        File(relativePath),
        File("../$relativePath"),
        File("../../$relativePath"),
    ).firstOrNull(File::isFile)?.readText()
        ?: error("$relativePath was not found from the unit-test working directory")
}
