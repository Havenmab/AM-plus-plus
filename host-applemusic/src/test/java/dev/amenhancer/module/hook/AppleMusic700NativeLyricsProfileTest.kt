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
        assertEquals(39, load.runtimeMemberNames.size)
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
        // Apple's advertised lane wins; the third-party fallback only fills a gap.
        assertTrue(hooks.contains("NativeLyricModelPolicy.selectPronunciationLanguage("))
        // The HLE per-line probe is kept, but only for availability/diagnostics.
        assertTrue(hooks.contains("private fun hasValidOfficialPronunciation("))
        // The stale shape that displaced Apple: firstOrNull()?.takeIf { cached }.
        assertFalse(hooks.contains("takeIf { hasValidOfficialPronunciation }"))
        // The device log must be able to prove the decision.
        assertTrue(hooks.contains("officialAtBuild="))
        assertTrue(hooks.contains("officialLanguage="))
        assertTrue(hooks.contains("selectedLanguage="))
    }

    private fun projectFile(relativePath: String): String = sequenceOf(
        File(relativePath),
        File("../$relativePath"),
        File("../../$relativePath"),
    ).firstOrNull(File::isFile)?.readText()
        ?: error("$relativePath was not found from the unit-test working directory")
}
