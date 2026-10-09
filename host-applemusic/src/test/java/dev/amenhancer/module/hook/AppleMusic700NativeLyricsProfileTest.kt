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
        // HLE's 1607 profile overrides the adapter to player.C; its 1606 profile
        // inherits player.A from the 6.5.x line. Both are pinned so the adapter
        // resolves on whichever beta is installed; the render scan itself
        // requires a LyricsWordVector -> ArrayMap method, so a class that is not
        // the adapter is never hooked.
        assertEquals(
            setOf("com.apple.android.music.player.C", "com.apple.android.music.player.A"),
            adapters.map { it.className }.toSet(),
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
        assertTrue(target.contains("hookResolver: AppleMusicHookResolver? = null"))
        assertTrue(target.contains("AppleNativeLyricModelHooks("))
        assertTrue(target.contains("runtime.nativeLyricOverlay"))
        assertTrue(target.contains("nativeLyricDelivery?.onLyricsPointer(original)"))

        val factory = projectFile(
            "host-applemusic/src/main/java/dev/amenhancer/module/hook/AppleMusicHostFactory.kt",
        )
        assertTrue(factory.contains("AppleMusicVersion(build.versionName, build.versionCode)"))
        assertTrue(factory.contains("hookResolver = nativeLyricResolver"))

        val runtime = projectFile(
            "app/src/main/java/dev/amenhancer/module/hook/AutoLyricsReplacementSession.kt",
        )
        assertTrue(runtime.contains("val nativeLyricOverlay = NativeLyricOverlayStore()"))
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
    fun `an empty Apple vector selects the third-party fallback instead of nothing`() {
        val hooks = projectFile(
            "host-applemusic/src/main/java/dev/amenhancer/module/hook/AppleNativeLyricModelHooks.kt",
        )
        // The regression: the planner must be handed the legitimate fallback so a
        // third-party-only song still gets a language selected, exactly as HLE's
        // `?: thirdPartyPronunciationFallbackLanguage()` does.
        assertTrue(hooks.contains("NativeLyricModelPolicy.planPronunciationSelection("))
        assertTrue(hooks.contains("thirdPartyFallbackLanguage = fallbackLanguage("))
        // The last non-empty advertisement is kept, so a transient empty read
        // cannot hand the tag over an Apple lane already seen (PR #9).
        assertTrue(hooks.contains("NativeLyricModelPolicy.advertisedPronunciationLanguages("))
        // The old flag implied "nothing selected" and is gone. (The KDoc still
        // quotes the old log line, so match the emitted field literal, not the
        // bare token.)
        assertFalse(hooks.contains("pronunciationSelectionDeferred"))
        assertFalse(hooks.contains("\"deferred="))
        // The diagnostic names the branch honestly instead of implying a stall.
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
        // The HLE policy decides the track and aligns the romanization.
        assertTrue(hooks.contains("ApplePronunciationPolicy.wordTrack("))
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

    private fun projectFile(relativePath: String): String = sequenceOf(
        File(relativePath),
        File("../$relativePath"),
        File("../../$relativePath"),
    ).firstOrNull(File::isFile)?.readText()
        ?: error("$relativePath was not found from the unit-test working directory")
}
