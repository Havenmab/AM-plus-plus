package dev.amenhancer.module.hook

import io.github.proify.lyricon.amprovider.xposed.AppleMusicHookPoint
import io.github.proify.lyricon.amprovider.xposed.AppleMusicHookProfiles
import io.github.proify.lyricon.amprovider.xposed.AppleMusicRuntimeMember
import io.github.proify.lyricon.amprovider.xposed.AppleMusicVersion
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Pins the pronunciation layer onto the exact 7.0.0-beta (1609) profile.
 *
 * 1609 is a `fragment-content` build, so `AppleMusicHookProfile.strict` is
 * true and `AppleMusicHookProfiles.candidates` collapses to the exact profile:
 * an empty pronunciation `hookTargets` entry is not a compatibility fallback,
 * it is "no hook at all". The 1.6.5 sync landed upstream's 1609 profile with
 * every pronunciation point empty, which silently disabled both the
 * Apple-sourced and the custom-lyrics delivery. These tests fail if the 1609
 * re-derivation regresses.
 *
 * Every value here was resolved from the 1609 `base.apk` DEX (see the profile's
 * `evidence.pronunciationTargets`); nothing is copied from 1606 without a
 * descriptor check.
 */
class AppleMusic7001609PronunciationProfileTest {

    private val version = AppleMusicVersion("7.0.0-beta", 1609L)

    private fun target(point: AppleMusicHookPoint) =
        AppleMusicHookProfiles.exactTargets(version, point).single()

    @Test
    fun `1609 is a strict profile, so empty targets mean disabled hooks`() {
        val profile = AppleMusicHookProfiles.profileFor(version)
        assertNotNull(profile)
        assertTrue(profile!!.strict)
    }

    @Test
    fun `1609 pins the native lyric-model member dictionary`() {
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
        // Same 39-entry dictionary shape as 1606: the native model member names
        // are app-authored and stable across the 1606 -> 1609 obfuscation bump.
        assertEquals(39, load.runtimeMemberNames.size)
    }

    @Test
    fun `1609 redirects the shared-preferences facade and keeps HLE's member names`() {
        // 1609 repurposed ja.i0 into a Kotlin coroutine class. The facade is
        // ja.h0: m(Z)V stores the pronunciation flag in the static Boolean cache
        // s, k is the key built from key_player_lyrics_pronunciation_selected,
        // h() returns the ja.D0 store whose d(O2.e$a;Object) reads it.
        val preferences = target(AppleMusicHookPoint.APPLE_SHARED_PREFERENCES_CLASS)
        assertEquals("ja.h0", preferences.className)
        fun member(name: AppleMusicRuntimeMember) = preferences.runtimeMemberNames[name]
        assertEquals("g", member(AppleMusicRuntimeMember.LYRICS_PREFERENCES_TRANSLATION_GETTER))
        assertEquals("s", member(AppleMusicRuntimeMember.LYRICS_PREFERENCES_PRONUNCIATION_CACHE_FIELD))
        assertEquals("k", member(AppleMusicRuntimeMember.LYRICS_PREFERENCES_PRONUNCIATION_KEY_FIELD))
        assertEquals("h", member(AppleMusicRuntimeMember.LYRICS_PREFERENCES_STORE_GETTER))
        assertEquals("d", member(AppleMusicRuntimeMember.LYRICS_PREFERENCES_STORE_READ_METHOD))

        val preferenceTarget = target(AppleMusicHookPoint.LYRICS_PRONUNCIATION_PREFERENCE)
        assertEquals("ja.h0", preferenceTarget.className)
        assertEquals("m", preferenceTarget.methodName)
        assertEquals(1, preferenceTarget.parameterCount)
        assertEquals(listOf("boolean"), preferenceTarget.parameterTypeNames)
        assertEquals("void", preferenceTarget.returnTypeName)
        assertEquals(true, preferenceTarget.isStatic)
    }

    @Test
    fun `1609 pins the line adapter R0 and the adapter flag`() {
        val adapters = AppleMusicHookProfiles.exactTargets(
            version,
            AppleMusicHookPoint.LYRICS_RECYCLER_ADAPTER,
        )
        // 1606's line adapter player.Y0 is a different two-field class on 1609;
        // the line adapter is player.R0. player.A stays the word adapter.
        assertEquals(
            listOf(
                "com.apple.android.music.player.A",
                "com.apple.android.music.player.R0",
            ),
            adapters.map { it.className },
        )
        adapters.forEach { target ->
            fun member(name: AppleMusicRuntimeMember) = target.runtimeMemberNames[name]
            assertEquals("w", member(AppleMusicRuntimeMember.LYRICS_ADAPTER_ACTIVE_POSITIONS_METHOD))
            assertEquals("x", member(AppleMusicRuntimeMember.LYRICS_ADAPTER_LYRICS_METHOD))
            assertEquals("f", member(AppleMusicRuntimeMember.LYRICS_ADAPTER_ITEM_VIEW_TYPE_METHOD))
            assertEquals("d", member(AppleMusicRuntimeMember.LYRICS_ADAPTER_ITEM_COUNT_METHOD))
            assertEquals("g", member(AppleMusicRuntimeMember.LYRICS_ADAPTER_NOTIFY_DATA_CHANGED_METHOD))
            assertEquals("O", member(AppleMusicRuntimeMember.LYRICS_ADAPTER_ACTIVE_LINES_UPDATE_METHOD))
            // The pronunciation flag lives on the shared base player.f1 (1606 n1).
            assertEquals(
                "e",
                member(AppleMusicRuntimeMember.LYRICS_ADAPTER_PRONUNCIATION_SELECTED_FIELD),
            )
            assertFalse(target.allowFirstMatch)
        }
    }

    @Test
    fun `1609 pins the presentation seams and the renamed fragment members`() {
        val seam = target(AppleMusicHookPoint.LYRICS_NATIVE_PRESENTATION)
        assertEquals(
            "com.apple.android.music.player.fragment.PlayerLyricsViewFragment",
            seam.className,
        )
        // F2(SongInfo$SongInfoPtr) still calls LocaleUtil.matchToSystemLyricsScript
        // and then SongInfoNative.setPronunciation, i.e. it is the same seam.
        assertEquals("F2", seam.methodName)
        assertEquals(1, seam.parameterCount)

        val build = target(AppleMusicHookPoint.LYRICS_VIEW_MODEL_BUILD)
        assertEquals(
            "com.apple.android.music.player.viewmodel.PlayerLyricsViewModel",
            build.className,
        )
        assertEquals("buildTimeRangeToLyricsMap", build.methodName)
        assertEquals(1, build.parameterCount)

        val match = target(AppleMusicHookPoint.LYRICS_OFFICIAL_PRONUNCIATION_MATCH)
        assertEquals("com.apple.android.music.playback.util.LocaleUtil", match.className)
        assertEquals("matchToSystemLyricsScript", match.methodName)
        assertEquals(1, match.parameterCount)

        val preferred = target(AppleMusicHookPoint.LYRICS_PREFERRED_LANGUAGES_REQUEST)
        assertEquals(
            "com.apple.android.music.player.viewmodel.PlayerLyricsViewModel\$f",
            preferred.className,
        )
        assertNull(preferred.methodName)

        val create = target(AppleMusicHookPoint.LYRICS_UI_ON_CREATE_VIEW)
        assertEquals("onCreateView", create.methodName)
        assertEquals(3, create.parameterCount)
        fun uiMember(name: AppleMusicRuntimeMember) = create.runtimeMemberNames[name]
        assertEquals("getRecyclerView", uiMember(AppleMusicRuntimeMember.LYRICS_UI_RECYCLER_VIEW_METHOD))
        assertEquals("l0", uiMember(AppleMusicRuntimeMember.LYRICS_UI_BINDING_FIELD))
        assertEquals("d0", uiMember(AppleMusicRuntimeMember.LYRICS_UI_BINDING_RECYCLER_FIELD))
        // The live lyrics adapter the payload replay reads off the fragment
        // (base player.f1), not the 1606 field name p0.
        assertEquals("n0", uiMember(AppleMusicRuntimeMember.LYRICS_UI_ADAPTER_FIELD))
        assertEquals("m1", uiMember(AppleMusicRuntimeMember.LYRICS_UI_VIEW_MODEL_FIELD))

        val destroy = target(AppleMusicHookPoint.LYRICS_UI_ON_DESTROY_VIEW)
        assertEquals("onDestroyView", destroy.methodName)
        assertEquals(0, destroy.parameterCount)
    }

    @Test
    fun `1609 pins the word render adapter and the native vector class`() {
        val adapters = AppleMusicHookProfiles.exactTargets(
            version,
            AppleMusicHookPoint.LYRICS_WORD_RENDER_ADAPTER,
        )
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
    fun `every 1609 pronunciation target stays exact and first-match free`() {
        listOf(
            AppleMusicHookPoint.LYRICS_VIEW_MODEL_LOAD,
            AppleMusicHookPoint.LYRICS_VIEW_MODEL_BUILD,
            AppleMusicHookPoint.LYRICS_NATIVE_PRESENTATION,
            AppleMusicHookPoint.LYRICS_PREFERRED_LANGUAGES_REQUEST,
            AppleMusicHookPoint.LYRICS_OFFICIAL_PRONUNCIATION_MATCH,
            AppleMusicHookPoint.LYRICS_PRONUNCIATION_PREFERENCE,
            AppleMusicHookPoint.LYRICS_WORD_RENDER_ADAPTER,
            AppleMusicHookPoint.LYRICS_WORD_VECTOR_CLASS,
            AppleMusicHookPoint.LYRICS_RECYCLER_ADAPTER,
            AppleMusicHookPoint.LYRICS_UI_ON_CREATE_VIEW,
            AppleMusicHookPoint.LYRICS_UI_ON_DESTROY_VIEW,
            AppleMusicHookPoint.APPLE_SHARED_PREFERENCES_CLASS,
        ).forEach { point ->
            val exact = AppleMusicHookProfiles.exactTargets(version, point)
            assertTrue("$point must be pinned on 1609", exact.isNotEmpty())
            assertEquals(exact, AppleMusicHookProfiles.candidates(version, point))
            assertTrue("$point must not allow a first match", exact.none { it.allowFirstMatch })
        }
    }

    @Test
    fun `no 1609 pronunciation target still points at the repurposed 1606 classes`() {
        val classes = listOf(
            AppleMusicHookPoint.LYRICS_VIEW_MODEL_LOAD,
            AppleMusicHookPoint.LYRICS_VIEW_MODEL_BUILD,
            AppleMusicHookPoint.LYRICS_NATIVE_PRESENTATION,
            AppleMusicHookPoint.LYRICS_PREFERRED_LANGUAGES_REQUEST,
            AppleMusicHookPoint.LYRICS_OFFICIAL_PRONUNCIATION_MATCH,
            AppleMusicHookPoint.LYRICS_PRONUNCIATION_PREFERENCE,
            AppleMusicHookPoint.LYRICS_WORD_RENDER_ADAPTER,
            AppleMusicHookPoint.LYRICS_WORD_VECTOR_CLASS,
            AppleMusicHookPoint.LYRICS_RECYCLER_ADAPTER,
            AppleMusicHookPoint.LYRICS_UI_ON_CREATE_VIEW,
            AppleMusicHookPoint.LYRICS_UI_ON_DESTROY_VIEW,
            AppleMusicHookPoint.APPLE_SHARED_PREFERENCES_CLASS,
        ).flatMap { AppleMusicHookProfiles.exactTargets(version, it) }
            .mapTo(mutableSetOf()) { it.className }
        // The 1606 preference facade and line adapter names are reused by
        // unrelated 1609 classes; pinning them would hook the wrong code.
        assertFalse("ja.i0 must not be pinned on 1609", "ja.i0" in classes)
        assertFalse(
            "player.Y0 must not be pinned as the 1609 line adapter",
            "com.apple.android.music.player.Y0" in classes,
        )
    }
}
