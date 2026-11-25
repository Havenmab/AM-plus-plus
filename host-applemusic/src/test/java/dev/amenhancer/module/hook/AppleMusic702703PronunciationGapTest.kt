package dev.amenhancer.module.hook

import dev.amenhancer.host.applemusic.AppleMusicHostProfiles
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
 * Records the pronunciation gap on 7.0.2 (1624) and pins the 7.0.3 (1626) re-derivation.
 *
 * The upstream 1.7.x sync shipped 1622, 1624 and 1626 with every pronunciation
 * hookTargets entry empty, exactly like 1614 and 1609 before them.
 * AppleMusicNativeLyricsTarget.install() therefore returns Degraded, so the
 * module's word readings never reach the native model on those builds. 1626 is
 * now re-derived from its own device split APK (base.apk, versionCode 1626) the
 * way 1606, 1609 and 1614 were; 1624 still has no DEX evidence behind it, so its
 * emptiness stays asserted on purpose.
 *
 * The tests below pin the values that came out of the 1626 DEX (unrenamed
 * semantic names, parameter and return descriptors, static flags, subclass
 * shape and the code references that bind the obfuscated names) so a later sync
 * cannot quietly drop them again. The two things that never needed the install
 * seam - the pair layout (centring) and the result submission contract - stay
 * pinned as well.
 */
class AppleMusic702703PronunciationGapTest {

    private val gap = AppleMusicVersion("7.0.2", 1624L)

    private val pinned = AppleMusicVersion("7.0.3", 1626L)

    private val pronunciationSeams = listOf(
        AppleMusicHookPoint.LYRICS_VIEW_MODEL_LOAD,
        AppleMusicHookPoint.LYRICS_VIEW_MODEL_BUILD,
        AppleMusicHookPoint.LYRICS_NATIVE_PRESENTATION,
        AppleMusicHookPoint.LYRICS_OFFICIAL_PRONUNCIATION_MATCH,
        AppleMusicHookPoint.LYRICS_PRONUNCIATION_PREFERENCE,
        AppleMusicHookPoint.LYRICS_PREFERRED_LANGUAGES_REQUEST,
        AppleMusicHookPoint.LYRICS_RECYCLER_ADAPTER,
        AppleMusicHookPoint.LYRICS_UI_ON_CREATE_VIEW,
        AppleMusicHookPoint.LYRICS_UI_ON_DESTROY_VIEW,
        AppleMusicHookPoint.LYRICS_WORD_RENDER_ADAPTER,
        AppleMusicHookPoint.LYRICS_WORD_VECTOR_CLASS,
        AppleMusicHookPoint.APPLE_SHARED_PREFERENCES_CLASS,
    )

    private fun exact(version: AppleMusicVersion, point: AppleMusicHookPoint) =
        AppleMusicHookProfiles.exactTargets(version, point)

    private fun target(point: AppleMusicHookPoint) = exact(pinned, point).single()

    @Test
    fun `the pronunciation seams are still unresolved on 1624`() {
        assertNotNull(
            "missing profile for " + gap.displayName,
            AppleMusicHookProfiles.profileFor(gap),
        )
        pronunciationSeams.forEach { point ->
            assertTrue(
                "1624 gained $point; re-derive it from the 1624 APK and update this test",
                exact(gap, point).isEmpty(),
            )
        }
    }

    @Test
    fun `1626 resolves every pronunciation seam and forbids a first match`() {
        assertNotNull(
            "missing profile for " + pinned.displayName,
            AppleMusicHookProfiles.profileFor(pinned),
        )
        pronunciationSeams.forEach { point ->
            val targets = exact(pinned, point)
            assertTrue("1626 pins no $point", targets.isNotEmpty())
            assertTrue("$point must reject an ambiguous member", targets.none { it.allowFirstMatch })
            assertEquals(
                "$point must stay exact on a strict fragment-content profile",
                targets,
                AppleMusicHookProfiles.candidates(pinned, point),
            )
        }
    }

    @Test
    fun `1626 pins the native lyric-model member dictionary`() {
        val load = target(AppleMusicHookPoint.LYRICS_VIEW_MODEL_LOAD)
        assertEquals(
            "com.apple.android.music.player.viewmodel.PlayerLyricsViewModel",
            load.className,
        )
        assertEquals("loadLyrics", load.methodName)
        assertEquals(1, load.parameterCount)
        assertEquals(listOf("com.apple.android.music.model.PlaybackItem"), load.parameterTypeNames)
        assertEquals("void", load.returnTypeName)
        assertEquals(false, load.isStatic)

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
        assertEquals("getWords", member(AppleMusicRuntimeMember.LYRICS_NATIVE_WORDS_METHOD))
        assertEquals(
            "getPronunciationWords",
            member(AppleMusicRuntimeMember.LYRICS_NATIVE_PRONUNCIATION_WORDS_METHOD),
        )
        assertEquals(
            "getPronunciationLanguages",
            member(AppleMusicRuntimeMember.LYRICS_NATIVE_SONG_PRONUNCIATION_LANGUAGES_METHOD),
        )
        assertEquals(
            "getCurrentSystemLyricsLanguage",
            member(AppleMusicRuntimeMember.LYRICS_VIEW_MODEL_CURRENT_LANGUAGE_METHOD),
        )
        assertEquals("getLyricsResult", member(AppleMusicRuntimeMember.LYRICS_VIEW_MODEL_RESULT_GETTER))
        assertEquals(
            "com.apple.android.music.ttml.javanative.model.LyricsWordVector",
            member(AppleMusicRuntimeMember.LYRICS_WORD_VECTOR_CLASS_NAME),
        )
        // The native model member names are app-authored and unchanged along the
        // 1606 -> 1609 -> 1614 -> 1626 line: 39 roles, each re-checked on 1626.
        assertEquals(39, load.runtimeMemberNames.size)
    }

    @Test
    fun `1626 pins the presentation, preference and shared-preferences seams`() {
        val build = target(AppleMusicHookPoint.LYRICS_VIEW_MODEL_BUILD)
        assertEquals(
            "com.apple.android.music.player.viewmodel.PlayerLyricsViewModel",
            build.className,
        )
        assertEquals("buildTimeRangeToLyricsMap", build.methodName)
        assertEquals(1, build.parameterCount)

        val presentation = target(AppleMusicHookPoint.LYRICS_NATIVE_PRESENTATION)
        assertEquals(
            "com.apple.android.music.player.fragment.PlayerLyricsViewFragment",
            presentation.className,
        )
        // F2(SongInfo$SongInfoPtr) still calls LocaleUtil.matchToSystemLyricsScript,
        // the pronunciation/translation availability tests and
        // SongInfoNative.setPronunciation, so it is the same seam as on 1614.
        assertEquals("F2", presentation.methodName)
        assertEquals(1, presentation.parameterCount)

        val match = target(AppleMusicHookPoint.LYRICS_OFFICIAL_PRONUNCIATION_MATCH)
        assertEquals("com.apple.android.music.playback.util.LocaleUtil", match.className)
        assertEquals("matchToSystemLyricsScript", match.methodName)
        assertEquals(true, match.isStatic)

        val preferred = target(AppleMusicHookPoint.LYRICS_PREFERRED_LANGUAGES_REQUEST)
        assertEquals(
            "com.apple.android.music.player.viewmodel.PlayerLyricsViewModel\$f",
            preferred.className,
        )
        assertNull(preferred.methodName)

        // ja.i0 is still the preference facade on 1626: its class initialiser
        // stores key_player_lyrics_pronunciation_selected into k, and m(Z)V
        // writes k through h() (the ja.E0 store) while caching into s.
        val preferences = target(AppleMusicHookPoint.APPLE_SHARED_PREFERENCES_CLASS)
        assertEquals("ja.i0", preferences.className)
        fun preference(name: AppleMusicRuntimeMember) = preferences.runtimeMemberNames[name]
        assertEquals("g", preference(AppleMusicRuntimeMember.LYRICS_PREFERENCES_TRANSLATION_GETTER))
        assertEquals("s", preference(AppleMusicRuntimeMember.LYRICS_PREFERENCES_PRONUNCIATION_CACHE_FIELD))
        assertEquals("k", preference(AppleMusicRuntimeMember.LYRICS_PREFERENCES_PRONUNCIATION_KEY_FIELD))
        assertEquals("h", preference(AppleMusicRuntimeMember.LYRICS_PREFERENCES_STORE_GETTER))
        assertEquals("d", preference(AppleMusicRuntimeMember.LYRICS_PREFERENCES_STORE_READ_METHOD))

        val preferenceTarget = target(AppleMusicHookPoint.LYRICS_PRONUNCIATION_PREFERENCE)
        assertEquals("ja.i0", preferenceTarget.className)
        assertEquals("m", preferenceTarget.methodName)
        assertEquals(1, preferenceTarget.parameterCount)
        assertEquals(listOf("boolean"), preferenceTarget.parameterTypeNames)
        assertEquals("void", preferenceTarget.returnTypeName)
        assertEquals(true, preferenceTarget.isStatic)
    }

    @Test
    fun `1626 pins both lyric adapters on the shared base`() {
        val adapters = exact(pinned, AppleMusicHookPoint.LYRICS_RECYCLER_ADAPTER)
        // 1614's player.x / player.S0 pair became player.A / player.V0 here,
        // both still under one shared base (player.i1), which is where the d/e
        // selected flags live.
        assertEquals(
            listOf(
                "com.apple.android.music.player.A",
                "com.apple.android.music.player.V0",
            ),
            adapters.map { it.className },
        )
        adapters.forEach { adapter ->
            fun member(name: AppleMusicRuntimeMember) = adapter.runtimeMemberNames[name]
            assertEquals("d", member(AppleMusicRuntimeMember.LYRICS_ADAPTER_ITEM_COUNT_METHOD))
            assertEquals("g", member(AppleMusicRuntimeMember.LYRICS_ADAPTER_NOTIFY_DATA_CHANGED_METHOD))
            assertEquals("f", member(AppleMusicRuntimeMember.LYRICS_ADAPTER_ITEM_VIEW_TYPE_METHOD))
            assertEquals("y", member(AppleMusicRuntimeMember.LYRICS_ADAPTER_LYRICS_METHOD))
            assertEquals("d", member(AppleMusicRuntimeMember.LYRICS_ADAPTER_TRANSLATION_SELECTED_FIELD))
            assertEquals(
                "e",
                member(AppleMusicRuntimeMember.LYRICS_ADAPTER_PRONUNCIATION_SELECTED_FIELD),
            )
            assertNull(adapter.methodName)
        }

        // Only the word adapter renders a LyricsWordVector into an ArrayMap.
        val word = exact(pinned, AppleMusicHookPoint.LYRICS_WORD_RENDER_ADAPTER)
        assertEquals(listOf("com.apple.android.music.player.A"), word.map { it.className })
        assertNull(word.single().methodName)

        val vector = target(AppleMusicHookPoint.LYRICS_WORD_VECTOR_CLASS)
        assertEquals(
            "com.apple.android.music.ttml.javanative.model.LyricsWordVector",
            vector.className,
        )
        assertNull(vector.methodName)
    }

    @Test
    fun `1626 pins the fragment members the adapter payload is read from`() {
        val create = target(AppleMusicHookPoint.LYRICS_UI_ON_CREATE_VIEW)
        assertEquals("onCreateView", create.methodName)
        assertEquals(3, create.parameterCount)
        fun uiMember(name: AppleMusicRuntimeMember) = create.runtimeMemberNames[name]
        assertEquals("getRecyclerView", uiMember(AppleMusicRuntimeMember.LYRICS_UI_RECYCLER_VIEW_METHOD))
        assertEquals("l0", uiMember(AppleMusicRuntimeMember.LYRICS_UI_BINDING_FIELD))
        assertEquals("d0", uiMember(AppleMusicRuntimeMember.LYRICS_UI_BINDING_RECYCLER_FIELD))
        assertEquals("n0", uiMember(AppleMusicRuntimeMember.LYRICS_UI_ADAPTER_FIELD))
        assertEquals("m1", uiMember(AppleMusicRuntimeMember.LYRICS_UI_VIEW_MODEL_FIELD))

        val destroy = target(AppleMusicHookPoint.LYRICS_UI_ON_DESTROY_VIEW)
        assertEquals("onDestroyView", destroy.methodName)
        assertEquals(0, destroy.parameterCount)
    }

    @Test
    fun `1626 declares every lyricPronunciation role it is resolved with`() {
        // FragmentProfileMembers resolves every declared role and throws when any
        // one fails, so a wrong owner here disables the whole node.
        val profile = AppleMusicHostProfiles.find("com.apple.android.music", "7.0.3", 1626L)
        assertNotNull("missing host profile for 7.0.3", profile)
        val methods = profile!!.document
            .getJSONObject("lyricPronunciation")
            .getJSONObject("methods")
        assertEquals(
            "com.apple.android.music.ttml.f",
            methods.getJSONObject("requestLyrics").getString("owner"),
        )
        assertEquals("e", methods.getJSONObject("requestLyrics").getString("name"))
        assertEquals(
            "gi.c",
            methods.getJSONObject("requestLyrics").getJSONArray("parameters").getString(4),
        )
        assertEquals(
            "com.apple.android.music.player.fragment.PlayerLyricsViewFragment",
            methods.getJSONObject("evaluate").getString("owner"),
        )
        assertEquals("F2", methods.getJSONObject("evaluate").getString("name"))
        assertEquals(
            "com.apple.android.music.playback.util.LocaleUtil",
            methods.getJSONObject("match").getString("owner"),
        )
        listOf(
            "evaluate", "match", "songInfo", "address", "songLanguage", "setTranslation",
            "pronunciationLanguages", "vectorSize", "vectorGet",
        ).forEach { role ->
            assertTrue(
                "lyricPronunciation.methods." + role + " is required by LyricsPronunciationMatching",
                methods.has(role),
            )
        }
        assertFalse(methods.has("preference"))
    }

    @Test
    fun `the pair layout and the result submission contract survive the gap`() {
        listOf(gap, pinned).forEach { version ->
            val profile = AppleMusicHostProfiles.find(
                "com.apple.android.music",
                version.versionName,
                version.versionCode,
            )
            assertNotNull("missing host profile for " + version.displayName, profile)
            val document = profile!!.document
            val pairLayout = document.optJSONObject("layoutVariants")
                ?.optString("lyricPronunciationPairLayout").orEmpty()
            assertTrue("pair layout is missing on " + version.displayName, pairLayout.isNotBlank())
            assertNotNull(
                "result submission is missing on " + version.displayName,
                document.optJSONObject("lyricResultSubmission"),
            )
        }
    }
}
