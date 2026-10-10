package dev.amenhancer.module.lyrics.online

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Pure-JVM coverage for the decisions HLE's native-model pronunciation
 * delivery makes: the third-party fallback language, Apple's language
 * selection, and the `has*`/`set*` availability resolution. These are the
 * halves that must stay identical to HLE; the reflection halves only resolve
 * the app's members and call in here.
 */
class NativeLyricModelPolicyTest {

    private fun fallback(
        system: String?,
        enabled: Boolean = true,
        hasOnline: Boolean = true,
        hideMandarin: Boolean = false,
        languages: Collection<String> = listOfNotNull(system),
        genre: String? = null,
    ) = NativeLyricModelPolicy.thirdPartyPronunciationFallbackLanguage(
        systemLanguage = system,
        enabled = enabled,
        hasOnlinePronunciation = hasOnline,
        hideMandarinPinyin = hideMandarin,
        pronunciationLanguages = languages,
        genre = genre,
    )

    @Test
    fun `the fallback is the system language only when it is a Latin tag`() {
        assertEquals("ja-Latn", fallback(system = "ja-Latn"))
        assertEquals("und-Latn", fallback(system = "ja"))
        assertEquals("und-Latn", fallback(system = "en"))
        assertEquals("und-Latn", fallback(system = null))
    }

    @Test
    fun `the fallback is gated by the switch the store and the Mandarin rule`() {
        assertNull(fallback(system = "ja-Latn", enabled = false))
        assertNull(fallback(system = "ja-Latn", hasOnline = false))
        // A Mandarin pronunciation language hides it outright.
        assertNull(fallback(system = "cmn-Latn", hideMandarin = true))
        // A Mandarin genre hides it; a Cantonese genre never does.
        assertNull(fallback(system = "en", hideMandarin = true, genre = "Mandopop"))
        assertEquals("und-Latn", fallback(system = "en", hideMandarin = true, genre = "Cantopop"))
        // Unknown genre hides nothing.
        assertEquals("und-Latn", fallback(system = "en", hideMandarin = true, genre = null))
    }

    private fun select(
        system: String?,
        apple: List<String>,
        onlineFallback: String?,
    ) = NativeLyricModelPolicy.selectLanguage(
        systemMatch = system,
        appleLanguages = apple,
        onlineFallbackLanguage = onlineFallback,
    )

    @Test
    fun `language selection keeps the system match then Apple then the online fallback`() {
        assertEquals(
            "ja-Latn",
            select(system = "ja-Latn", apple = listOf("ko-Latn"), onlineFallback = "und-Latn"),
        )
        assertEquals(
            "ko-Latn",
            select(system = "ja", apple = listOf("ja", "ko-Latn"), onlineFallback = "und-Latn"),
        )
        assertEquals(
            "und-Latn",
            select(system = "ja", apple = emptyList(), onlineFallback = "und-Latn"),
        )
        assertNull(select(system = "ja", apple = emptyList(), onlineFallback = null))
    }

    private fun trackLanguage(apple: List<String>, fallback: String?) =
        NativeLyricModelPolicy.selectPronunciationLanguage(
            appleLanguages = apple,
            thirdPartyFallbackLanguage = fallback,
        )

    @Test
    fun `an Apple-advertised Latin lane is Apple's own and never the placeholder`() {
        assertTrue(NativeLyricModelPolicy.isOfficialPronunciationLanguage("ja-Latn"))
        assertTrue(NativeLyricModelPolicy.isOfficialPronunciationLanguage("ko-Latn"))
        assertTrue(NativeLyricModelPolicy.isOfficialPronunciationLanguage("zh-Hans-Latn"))
        // HLE's script-neutral third-party tag is not an Apple lane.
        assertFalse(NativeLyricModelPolicy.isOfficialPronunciationLanguage("und-Latn"))
        assertFalse(NativeLyricModelPolicy.isOfficialPronunciationLanguage("UND-LATN"))
        // A non-Latin tag, a blank and null carry no romanization.
        assertFalse(NativeLyricModelPolicy.isOfficialPronunciationLanguage("ja"))
        assertFalse(NativeLyricModelPolicy.isOfficialPronunciationLanguage("cmn-Hans"))
        assertFalse(NativeLyricModelPolicy.isOfficialPronunciationLanguage("  "))
        assertFalse(NativeLyricModelPolicy.isOfficialPronunciationLanguage(null))
    }

    @Test
    fun `the first Apple lane wins and the placeholder is skipped`() {
        assertEquals(
            "ja-Latn",
            NativeLyricModelPolicy.officialPronunciationLanguage(listOf("ja", "ja-Latn", "und-Latn")),
        )
        assertEquals(
            "ko-Latn",
            NativeLyricModelPolicy.officialPronunciationLanguage(listOf("und-Latn", "ko-Latn")),
        )
        assertNull(NativeLyricModelPolicy.officialPronunciationLanguage(listOf("und-Latn")))
        assertNull(NativeLyricModelPolicy.officialPronunciationLanguage(emptyList()))
    }

    /**
     * The reported bug: Apple advertises `ja-Latn`, the build-time per-line probe
     * reads false because 1606 leaves `getHtmlPronunciationLineText` empty until a
     * language has been selected, and the third-party fallback `und-Latn` used to
     * displace Apple's lane. Apple's own advertised lane must win.
     */
    @Test
    fun `Apple's advertised lane beats the third-party fallback even before the line probe is valid`() {
        assertEquals("ja-Latn", trackLanguage(listOf("ja-Latn"), "und-Latn"))
        assertEquals("ko-Latn", trackLanguage(listOf("ko-Latn"), "ja-Latn"))
        // No Apple lane of its own: the third-party fallback still fills the gap.
        assertEquals("und-Latn", trackLanguage(listOf("und-Latn"), "und-Latn"))
        assertEquals("ja-Latn", trackLanguage(listOf("ja"), "ja-Latn"))
        assertNull(trackLanguage(emptyList(), null))
    }

    private fun plan(apple: List<String>, fallback: String?) =
        NativeLyricModelPolicy.planPronunciationSelection(
            appleLanguages = apple,
            thirdPartyFallbackLanguage = fallback,
        )

    /**
     * The regression this change fixes. An empty Apple vector means "Apple has
     * not advertised a lane", not "select nothing": HLE falls through to
     * `thirdPartyPronunciationFallbackLanguage()`. The device log shows the
     * third-party lane was published while the write selected nothing — e.g.
     * id=1531673145 `publish … pronunciationSource=QM pronunciationLines=24`
     * beside `native-write … languages= … selectedLanguage=none deferred=true`.
     */
    @Test
    fun `an empty language list still selects the third-party fallback as HLE does`() {
        val unanswered = plan(emptyList(), "und-Latn")
        assertEquals("und-Latn", unanswered.language)
        assertEquals(NativeLyricModelPolicy.PronunciationSelection.THIRD_PARTY, unanswered.selection)
        assertEquals(NativeLyricModelPolicy.REASON_APPLE_UNANSWERED, unanswered.reason)
        assertFalse(unanswered.appleLanguagesKnown)
        // A Latin system tag is used verbatim, exactly as the fallback does.
        assertEquals("ja-Latn", plan(emptyList(), "ja-Latn").language)
    }

    /**
     * Self-correction: the re-evaluation points hand the plan Apple's advertised
     * lane, so it supersedes a third-party tag that was standing in.
     */
    @Test
    fun `Apple's advertised lane wins and supersedes a standing fallback`() {
        val advertised = plan(listOf("ja-Latn"), "und-Latn")
        assertEquals("ja-Latn", advertised.language)
        assertEquals(NativeLyricModelPolicy.PronunciationSelection.APPLE, advertised.selection)
        assertEquals(NativeLyricModelPolicy.REASON_APPLE_LANE, advertised.reason)
        assertTrue(advertised.appleLanguagesKnown)
        // The lane that arrives second is the one that wins, per the log.
        val korean = plan(listOf("ko-Latn"), "und-Latn")
        assertEquals("ko-Latn", korean.language)
        assertEquals(NativeLyricModelPolicy.PronunciationSelection.APPLE, korean.selection)
    }

    @Test
    fun `Apple answering without a lane of its own still takes the third-party tag`() {
        val answered = plan(listOf("ja"), "und-Latn")
        assertEquals("und-Latn", answered.language)
        assertEquals(NativeLyricModelPolicy.PronunciationSelection.THIRD_PARTY, answered.selection)
        assertEquals(NativeLyricModelPolicy.REASON_APPLE_NO_OWN_LANE, answered.reason)
        assertTrue(answered.appleLanguagesKnown)
    }

    @Test
    fun `nothing is selected only when there is no lane and no legitimate fallback`() {
        val unknown = plan(emptyList(), null)
        assertNull(unknown.language)
        assertEquals(NativeLyricModelPolicy.PronunciationSelection.NONE, unknown.selection)
        assertEquals(NativeLyricModelPolicy.REASON_NO_FALLBACK, unknown.reason)
        assertFalse(unknown.appleLanguagesKnown)
        // Apple answered but there is nothing third-party to add.
        val answered = plan(listOf("und-Latn"), null)
        assertNull(answered.language)
        assertEquals(NativeLyricModelPolicy.PronunciationSelection.NONE, answered.selection)
        assertEquals(NativeLyricModelPolicy.REASON_NO_FALLBACK, answered.reason)
        assertTrue(answered.appleLanguagesKnown)
    }

    @Test
    fun `the Mandarin rule hides the lane and selects nothing`() {
        val hidden = NativeLyricModelPolicy.hiddenPronunciationSelection(appleLanguagesKnown = true)
        assertNull(hidden.language)
        assertEquals(NativeLyricModelPolicy.PronunciationSelection.NONE, hidden.selection)
        assertEquals(NativeLyricModelPolicy.REASON_MANDARIN_HIDDEN, hidden.reason)
        assertTrue(hidden.appleLanguagesKnown)
    }

    /**
     * A transient empty read must not withdraw a lane Apple already advertised
     * (#9's invariant): the selection plans against the last non-empty
     * advertisement, so the tag can never take over a lane Apple was seen to
     * offer.
     */
    @Test
    fun `a transient empty read keeps the last non-empty Apple advertisement`() {
        assertEquals(
            listOf("ja-Latn"),
            NativeLyricModelPolicy.advertisedPronunciationLanguages(
                live = emptyList(),
                remembered = listOf("ja-Latn"),
            ),
        )
        assertEquals(
            listOf("ko-Latn"),
            NativeLyricModelPolicy.advertisedPronunciationLanguages(
                live = listOf("ko-Latn"),
                remembered = listOf("ja-Latn"),
            ),
        )
        assertEquals(
            emptyList<String>(),
            NativeLyricModelPolicy.advertisedPronunciationLanguages(
                live = emptyList(),
                remembered = emptyList(),
            ),
        )
        // With the remembered lane the plan stays on Apple's lane even though the
        // live read was empty.
        val remembered = NativeLyricModelPolicy.advertisedPronunciationLanguages(
            live = emptyList(),
            remembered = listOf("ja-Latn"),
        )
        val resolved = plan(remembered, "und-Latn")
        assertEquals("ja-Latn", resolved.language)
        assertEquals(NativeLyricModelPolicy.PronunciationSelection.APPLE, resolved.selection)
    }

    @Test
    fun `Apple's advertised lane keeps pronunciation available without the online lane`() {
        fun available(systemMatch: Boolean, languages: List<String>) =
            NativeLyricModelPolicy.hasPronunciationAvailability(
                original = true,
                enabled = false,
                hasOnlinePronunciation = false,
                hasValidOfficialPronunciation = systemMatch ||
                    NativeLyricModelPolicy.officialPronunciationLanguage(languages) != null,
                mandarinHidden = false,
            )

        // The old build-time probe alone withdrew Apple's own value.
        assertFalse(available(systemMatch = false, languages = emptyList()))
        // Apple's advertised ja-Latn keeps it visible before the line probe is valid.
        assertTrue(available(systemMatch = false, languages = listOf("ja-Latn")))
    }

    private fun translationAvailability(
        original: Boolean,
        enabled: Boolean,
        online: Boolean,
    ) = NativeLyricModelPolicy.hasTranslationAvailability(
        original = original,
        enabled = enabled,
        hasOnlineTranslation = online,
    )

    @Test
    fun `translation availability never withdraws Apple's own value`() {
        assertTrue(translationAvailability(original = true, enabled = false, online = false))
        assertTrue(translationAvailability(original = true, enabled = true, online = false))
        assertTrue(translationAvailability(original = false, enabled = true, online = true))
        assertFalse(translationAvailability(original = false, enabled = true, online = false))
    }

    private fun pronunciationAvailability(
        original: Boolean,
        enabled: Boolean = true,
        online: Boolean,
        validOfficial: Boolean,
        mandarinHidden: Boolean,
    ) = NativeLyricModelPolicy.hasPronunciationAvailability(
        original = original,
        enabled = enabled,
        hasOnlinePronunciation = online,
        hasValidOfficialPronunciation = validOfficial,
        mandarinHidden = mandarinHidden,
    )

    @Test
    fun `pronunciation availability hides Mandarin and advertises the online lane`() {
        assertFalse(
            pronunciationAvailability(original = true, online = true, validOfficial = true, mandarinHidden = true),
        )
        assertTrue(
            pronunciationAvailability(original = false, online = true, validOfficial = false, mandarinHidden = false),
        )
        assertFalse(
            pronunciationAvailability(original = false, online = false, validOfficial = false, mandarinHidden = false),
        )
        // Apple's own valid romanization survives even with the feature off.
        assertTrue(
            pronunciationAvailability(
                original = true,
                enabled = false,
                online = false,
                validOfficial = true,
                mandarinHidden = false,
            ),
        )
        assertFalse(
            pronunciationAvailability(
                original = true,
                enabled = false,
                online = false,
                validOfficial = false,
                mandarinHidden = false,
            ),
        )
    }

    @Test
    fun `the third-party tag is HLE's script-neutral Latin tag`() {
        assertEquals("und-Latn", NativeLyricModelPolicy.THIRD_PARTY_PRONUNCIATION_LANGUAGE)
    }

    private fun refresh(
        sourceIsApple: Boolean = true,
        official: Boolean = false,
        onlineTranslation: Boolean = false,
        onlinePronunciation: Boolean = false,
        pronunciationSelected: Boolean = true,
    ) = NativeLyricModelPolicy.shouldRefreshPresentationAfterBuild(
        sourceIsApple = sourceIsApple,
        hasValidOfficialPronunciation = official,
        hasOnlineTranslation = onlineTranslation,
        hasOnlinePronunciation = onlinePronunciation,
        pronunciationSelected = pronunciationSelected,
    )

    private fun refreshReason(
        sourceIsApple: Boolean = true,
        official: Boolean = false,
        onlineTranslation: Boolean = false,
        onlinePronunciation: Boolean = false,
        pronunciationSelected: Boolean = true,
    ) = NativeLyricModelPolicy.presentationRefreshReason(
        sourceIsApple = sourceIsApple,
        hasValidOfficialPronunciation = official,
        hasOnlineTranslation = onlineTranslation,
        hasOnlinePronunciation = onlinePronunciation,
        pronunciationSelected = pronunciationSelected,
    )

    /**
     * HLE's `shouldRefreshPresentationAfterBuild`, line for line:
     * ```
     * if (!sourceIsApple) return false
     * if (hasOnlineTranslation || hasOnlinePronunciation) return true
     * return pronunciationSelected && hasValidOfficialPronunciation
     * ```
     * The reported stall is the `else` arm: with no online lane yet and no
     * official pronunciation read at the build seam, no refresh is requested, so
     * the page keeps the first render until it is re-created.
     */
    @Test
    fun `the refresh gate is HLE's source-online-official decision`() {
        // A module supplement pointer has its own refresh path and is never the trigger.
        assertFalse(refresh(sourceIsApple = false))
        assertFalse(refresh(sourceIsApple = false, onlineTranslation = true, official = true))
        // Any online lane is enough, regardless of Apple's own lane.
        assertTrue(refresh(onlineTranslation = true))
        assertTrue(refresh(onlinePronunciation = true))
        assertTrue(refresh(onlineTranslation = true, pronunciationSelected = false))
        assertTrue(refresh(onlinePronunciation = true, pronunciationSelected = false))
        // No online lane: only a selected official pronunciation triggers it.
        assertTrue(refresh(official = true, pronunciationSelected = true))
        assertFalse(refresh(official = true, pronunciationSelected = false))
        // Nothing to show: the gate stays closed (the first-play stall).
        assertFalse(refresh(official = false, pronunciationSelected = true))
    }

    @Test
    fun `the reason names the branch the gate accepted`() {
        assertEquals(
            NativeLyricModelPolicy.REFRESH_REASON_NONE,
            refreshReason(sourceIsApple = false),
        )
        assertEquals(
            NativeLyricModelPolicy.REFRESH_REASON_ONLINE_LANE,
            refreshReason(onlineTranslation = true),
        )
        assertEquals(
            NativeLyricModelPolicy.REFRESH_REASON_ONLINE_LANE,
            refreshReason(onlinePronunciation = true),
        )
        // The official branch is named even before the line probe is valid.
        assertEquals(
            NativeLyricModelPolicy.REFRESH_REASON_OFFICIAL_LANE,
            refreshReason(official = true, pronunciationSelected = true),
        )
        assertEquals(
            NativeLyricModelPolicy.REFRESH_REASON_NONE,
            refreshReason(official = true, pronunciationSelected = false),
        )
    }

    /**
     * The reason and the boolean come from one rule: every one of the 2^5 input
     * combinations must agree, so a log can never claim a refresh the gate denied
     * (or vice versa).
     */
    @Test
    fun `the reason never disagrees with the refresh decision`() {
        listOf(true, false).forEach { source ->
            listOf(true, false).forEach { official ->
                listOf(true, false).forEach { onlineTranslation ->
                    listOf(true, false).forEach { onlinePronunciation ->
                        listOf(true, false).forEach { pronunciationSelected ->
                            val decision = refresh(
                                sourceIsApple = source,
                                official = official,
                                onlineTranslation = onlineTranslation,
                                onlinePronunciation = onlinePronunciation,
                                pronunciationSelected = pronunciationSelected,
                            )
                            val reason = refreshReason(
                                sourceIsApple = source,
                                official = official,
                                onlineTranslation = onlineTranslation,
                                onlinePronunciation = onlinePronunciation,
                                pronunciationSelected = pronunciationSelected,
                            )
                            val requested = reason != NativeLyricModelPolicy.REFRESH_REASON_NONE
                            assertEquals(
                                "source=$source official=$official translation=$onlineTranslation " +
                                    "pronunciation=$onlinePronunciation selected=$pronunciationSelected",
                                decision,
                                requested,
                            )
                        }
                    }
                }
            }
        }
    }
}
