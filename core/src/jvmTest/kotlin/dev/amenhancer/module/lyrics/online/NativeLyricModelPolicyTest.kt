package dev.amenhancer.module.lyrics.online

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Pure-JVM coverage for the Apple-only native-model pronunciation decisions:
 * the language selection, the availability resolution, and the presentation
 * refresh machinery. The third-party fallback language no longer exists, so the
 * selection can only ever pick a language Apple itself advertises. The
 * reflection halves only resolve the app's members and call in here.
 */
class NativeLyricModelPolicyTest {

    private fun select(system: String?, apple: List<String>) =
        NativeLyricModelPolicy.selectLanguage(systemMatch = system, appleLanguages = apple)

    @Test
    fun `language selection keeps the system match then Apple's own Latin tag`() {
        assertEquals("ja-Latn", select(system = "ja-Latn", apple = listOf("ko-Latn")))
        assertEquals("ko-Latn", select(system = "ja", apple = listOf("ja", "ko-Latn")))
        // No third-party language is ever substituted.
        assertNull(select(system = "ja", apple = emptyList()))
        assertNull(select(system = "ja", apple = listOf("ja")))
        assertNull(select(system = null, apple = emptyList()))
    }

    private fun trackLanguage(apple: List<String>) =
        NativeLyricModelPolicy.selectPronunciationLanguage(appleLanguages = apple)

    @Test
    fun `only an Apple-advertised Latin lane is selected`() {
        assertEquals("ja-Latn", trackLanguage(listOf("ja", "ja-Latn")))
        assertEquals("ko-Latn", trackLanguage(listOf("ko-Latn")))
        assertNull(trackLanguage(listOf("ja")))
        assertNull(trackLanguage(emptyList()))
    }

    @Test
    fun `only a Latin script tag counts as Apple's own pronunciation lane`() {
        assertTrue(NativeLyricModelPolicy.isOfficialPronunciationLanguage("ja-Latn"))
        assertTrue(NativeLyricModelPolicy.isOfficialPronunciationLanguage("ko-Latn"))
        assertTrue(NativeLyricModelPolicy.isOfficialPronunciationLanguage("zh-Hans-Latn"))
        // The old third-party placeholder is just another Latin tag now.
        assertTrue(NativeLyricModelPolicy.isOfficialPronunciationLanguage("und-Latn"))
        // A non-Latin tag, a blank and null carry no romanization.
        assertFalse(NativeLyricModelPolicy.isOfficialPronunciationLanguage("ja"))
        assertFalse(NativeLyricModelPolicy.isOfficialPronunciationLanguage("cmn-Hans"))
        assertFalse(NativeLyricModelPolicy.isOfficialPronunciationLanguage("  "))
        assertFalse(NativeLyricModelPolicy.isOfficialPronunciationLanguage(null))
    }

    @Test
    fun `the first Apple Latin lane wins`() {
        assertEquals(
            "ja-Latn",
            NativeLyricModelPolicy.officialPronunciationLanguage(listOf("ja", "ja-Latn", "ko-Latn")),
        )
        assertEquals(
            // `und-Latn` is no longer this module's placeholder (the third-party
            // lane is gone), so an Apple-advertised Latin tag is taken at face
            // value — the first one wins.
            "und-Latn",
            NativeLyricModelPolicy.officialPronunciationLanguage(listOf("und-Latn", "ko-Latn")),
        )
        assertNull(
            NativeLyricModelPolicy.officialPronunciationLanguage(listOf("ja", "cmn-Hans")),
        )
        assertNull(NativeLyricModelPolicy.officialPronunciationLanguage(emptyList()))
    }

    private fun plan(apple: List<String>) =
        NativeLyricModelPolicy.planPronunciationSelection(appleLanguages = apple)

    /**
     * The reported bug: Apple advertises `ja-Latn`, the build-time per-line probe
     * reads false because 1606 leaves `getHtmlPronunciationLineText` empty until a
     * language has been selected, and the old third-party fallback used to
     * displace Apple's lane. Apple's own advertised lane must win.
     */
    @Test
    fun `Apple's advertised lane is selected even before the line probe is valid`() {
        val advertised = plan(listOf("ja-Latn"))
        assertEquals("ja-Latn", advertised.language)
        assertEquals(NativeLyricModelPolicy.PronunciationSelection.APPLE, advertised.selection)
        assertEquals(NativeLyricModelPolicy.REASON_APPLE_LANE, advertised.reason)
        assertTrue(advertised.appleLanguagesKnown)
        // The lane that arrives second is the one that wins, per the log.
        assertEquals("ko-Latn", plan(listOf("ko-Latn")).language)
    }

    /**
     * The Apple-only correction. An empty Apple vector means "Apple has not
     * advertised a lane yet", so nothing is selected; the host keeps the
     * selection open and re-runs it at every later entry point, which is what
     * lets Apple's own lane appear as soon as it is advertised.
     */
    @Test
    fun `an empty language list selects nothing and stays open`() {
        val unanswered = plan(emptyList())
        assertNull(unanswered.language)
        assertEquals(NativeLyricModelPolicy.PronunciationSelection.NONE, unanswered.selection)
        assertEquals(NativeLyricModelPolicy.REASON_APPLE_UNANSWERED, unanswered.reason)
        assertFalse(unanswered.appleLanguagesKnown)
    }

    @Test
    fun `Apple answering without a Latin lane selects nothing`() {
        val answered = plan(listOf("ja"))
        assertNull(answered.language)
        assertEquals(NativeLyricModelPolicy.PronunciationSelection.NONE, answered.selection)
        assertEquals(NativeLyricModelPolicy.REASON_APPLE_NO_OWN_LANE, answered.reason)
        assertTrue(answered.appleLanguagesKnown)
    }

    @Test
    fun `the selection never reports a third-party lane`() {
        assertEquals(
            listOf("apple", "none"),
            NativeLyricModelPolicy.PronunciationSelection.entries.map { it.token },
        )
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
     * advertisement, so a lane Apple was seen to offer is never lost.
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
        val resolved = plan(remembered)
        assertEquals("ja-Latn", resolved.language)
        assertEquals(NativeLyricModelPolicy.PronunciationSelection.APPLE, resolved.selection)
    }

    @Test
    fun `Apple's advertised lane keeps pronunciation available without any online lane`() {
        fun available(systemMatch: Boolean, languages: List<String>) =
            NativeLyricModelPolicy.hasPronunciationAvailability(
                original = true,
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
        validOfficial: Boolean,
        mandarinHidden: Boolean,
    ) = NativeLyricModelPolicy.hasPronunciationAvailability(
        original = original,
        hasValidOfficialPronunciation = validOfficial,
        mandarinHidden = mandarinHidden,
    )

    @Test
    fun `pronunciation availability hides Mandarin and needs Apple's own value`() {
        assertFalse(
            pronunciationAvailability(original = true, validOfficial = true, mandarinHidden = true),
        )
        // The module adds no lane any more: a song without Apple's own value is
        // not advertised.
        assertFalse(
            pronunciationAvailability(original = false, validOfficial = true, mandarinHidden = false),
        )
        assertFalse(
            pronunciationAvailability(original = true, validOfficial = false, mandarinHidden = false),
        )
        assertTrue(
            pronunciationAvailability(original = true, validOfficial = true, mandarinHidden = false),
        )
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

    private fun customRefresh(
        onlineTranslation: Boolean = false,
        onlinePronunciation: Boolean = false,
    ) = NativeLyricModelPolicy.shouldRefreshPresentationAfterCustomOverlay(
        hasOnlineTranslation = onlineTranslation,
        hasOnlinePronunciation = onlinePronunciation,
    )

    private fun customRefreshReason(
        onlineTranslation: Boolean = false,
        onlinePronunciation: Boolean = false,
    ) = NativeLyricModelPolicy.customOverlayRefreshReason(
        hasOnlineTranslation = onlineTranslation,
        hasOnlinePronunciation = onlinePronunciation,
    )

    /**
     * HLE's supplement store update (`AppleSupplementDataReceive`) refreshes on
     * `displayContentChanged` alone; the fork's custom overlay write is the same
     * signal, so the post-overlay trigger must accept whenever a lane exists —
     * including on the supplement pointer the build gate deliberately skips.
     */
    @Test
    fun `the custom overlay trigger accepts any lane despite the supplement skip`() {
        assertFalse(customRefresh())
        assertTrue(customRefresh(onlineTranslation = true))
        assertTrue(customRefresh(onlinePronunciation = true))
        assertTrue(customRefresh(onlineTranslation = true, onlinePronunciation = true))
        // The build gate denies exactly this state (`sourceIsApple=false`); the
        // post-overlay trigger is the refresh the supplement path owns instead.
        assertFalse(
            NativeLyricModelPolicy.shouldRefreshPresentationAfterBuild(
                sourceIsApple = false,
                hasValidOfficialPronunciation = false,
                hasOnlineTranslation = true,
                hasOnlinePronunciation = true,
                pronunciationSelected = true,
            ),
        )
    }

    @Test
    fun `the custom overlay reason names the lane branch and never disagrees`() {
        assertEquals(NativeLyricModelPolicy.REFRESH_REASON_NONE, customRefreshReason())
        assertEquals(
            NativeLyricModelPolicy.REFRESH_REASON_CUSTOM_OVERLAY,
            customRefreshReason(onlineTranslation = true),
        )
        assertEquals(
            NativeLyricModelPolicy.REFRESH_REASON_CUSTOM_OVERLAY,
            customRefreshReason(onlinePronunciation = true),
        )
        listOf(true, false).forEach { translation ->
            listOf(true, false).forEach { pronunciation ->
                val reason = customRefreshReason(
                    onlineTranslation = translation,
                    onlinePronunciation = pronunciation,
                )
                assertEquals(
                    "translation=$translation pronunciation=$pronunciation",
                    customRefresh(
                        onlineTranslation = translation,
                        onlinePronunciation = pronunciation,
                    ),
                    reason != NativeLyricModelPolicy.REFRESH_REASON_NONE,
                )
            }
        }
    }

    @Test
    fun `the lane-ready edge is the song, the Apple lane and the per-line probe`() {
        fun key(lane: String?, probe: Boolean, songId: Long = 42L) =
            NativeLyricModelPolicy.pronunciationLaneReadyKey(
                songId = songId,
                officialLane = lane,
                hasValidOfficialPronunciation = probe,
            )

        // Apple advertising a lane the build never saw is a new edge.
        assertNotEquals(key(null, false), key("ja-Latn", false))
        // So is the lane whose per-line text only populated after the build: the
        // reported `officialAtBuild=false` then `true` sequence.
        assertNotEquals(key("ja-Latn", false), key("ja-Latn", true))
        // A different song is always a different edge.
        assertNotEquals(key("ja-Latn", true), key("ja-Latn", true, songId = 43L))
        // Repeated reads of one settled lane are the same key: the anti-thrash
        // half that keeps a per-bind availability query from re-asking.
        assertEquals(key("ja-Latn", true), key("ja-Latn", true))
        assertEquals(key(null, false), key(null, false))
        // A lane-less song with no probe never produces a ready key that looks
        // like a lane: the host additionally requires probe || lane != null.
        assertNotEquals(key(null, true), key("", false))
    }

    @Test
    fun `the word-ready edge is one per song, not one per line`() {
        // The late-word ask is deliberately per song: a whole song's word getters
        // run during one bind, and a per-line key would request a refresh for
        // every line (thrash). One key per song bounds the correction to at most
        // one extra re-presentation per track, and the host records it before
        // asking so the rebuild cannot bump it again.
        assertEquals(
            NativeLyricModelPolicy.pronunciationWordReadyKey(42L),
            NativeLyricModelPolicy.pronunciationWordReadyKey(42L),
        )
        assertNotEquals(
            NativeLyricModelPolicy.pronunciationWordReadyKey(42L),
            NativeLyricModelPolicy.pronunciationWordReadyKey(43L),
        )
        // It must not be mistakable for a lane-ready key, so a word edge can never
        // suppress or be suppressed by a language edge.
        assertNotEquals(
            NativeLyricModelPolicy.pronunciationWordReadyKey(42L),
            NativeLyricModelPolicy.pronunciationLaneReadyKey(
                songId = 42L,
                officialLane = "words",
                hasValidOfficialPronunciation = true,
            ),
        )
    }

    private fun payloadOutcome(
        lane: Boolean,
        selected: Boolean,
        adapterFlag: Boolean?,
    ) = NativeLyricModelPolicy.pronunciationPayloadOutcome(
        hasConfirmedOfficialLane = lane,
        pronunciationSelected = selected,
        adapterPronunciationSelected = adapterFlag,
    )

    /**
     * The post-refresh payload decision — the reported fix's own truth table: a
     * confirmed Apple lane plus the user's preference must reach a live adapter
     * whose flag is still `false`. A song without a confirmed lane, a user who
     * switched pronunciation off, a flag that is already true and an unreadable
     * adapter each keep their old outcome.
     */
    @Test
    fun `the payload is dispatched only for a wanted confirmed lane on a stale flag`() {
        assertEquals(
            PronunciationPayloadOutcome.DISPATCHED,
            payloadOutcome(lane = true, selected = true, adapterFlag = false),
        )
        // A song with no confirmed Apple lane is unchanged.
        assertEquals(
            PronunciationPayloadOutcome.NOT_AVAILABLE,
            payloadOutcome(lane = false, selected = true, adapterFlag = false),
        )
        // A user who switched the pronunciation row off must not see it appear.
        assertEquals(
            PronunciationPayloadOutcome.PREFERENCE_OFF,
            payloadOutcome(lane = true, selected = false, adapterFlag = false),
        )
        // The rows were already told; the replay is skipped.
        assertEquals(
            PronunciationPayloadOutcome.ALREADY_TRUE,
            payloadOutcome(lane = true, selected = true, adapterFlag = true),
        )
        // The live adapter could not be read: never silently no-op.
        assertEquals(
            PronunciationPayloadOutcome.ADAPTER_UNAVAILABLE,
            payloadOutcome(lane = true, selected = true, adapterFlag = null),
        )
    }

    @Test
    fun `the payload tokens are the device diagnostic values`() {
        assertEquals(
            listOf(
                "dispatched",
                "skipped(not-available)",
                "skipped(preference-off)",
                "skipped(already-true)",
                "skipped(adapter-unavailable)",
            ),
            PronunciationPayloadOutcome.entries.map { it.token },
        )
    }

    private fun payloadLane(
        stateLane: String? = null,
        selectedLane: String? = null,
        advertisedLane: String? = null,
        probe: Boolean = false,
        systemLyricsLanguage: String? = null,
        supplementLane: String? = null,
    ) = NativeLyricModelPolicy.pronunciationPayloadLane(
        stateOfficialLane = stateLane,
        selectedLane = selectedLane,
        advertisedLane = advertisedLane,
        hasOfficialLineProbe = probe,
        systemLyricsLanguage = systemLyricsLanguage,
        supplementOverlayLane = supplementLane,
    )

    /**
     * The payload lane resolution is what lets a **custom lyrics** (module
     * supplement) track reach the guaranteed payload dispatch: its document is
     * parsed into its own model, so the lane has to be confirmed from that model
     * (its advertisement, the lane the setter accepted, or the per-line probe)
     * rather than from the Apple advertisement the build gate read before the
     * document was installed.
     */
    @Test
    fun `the payload lane is confirmed from the strongest evidence and names its source`() {
        // The accepted ask's own lane wins.
        val state = payloadLane(stateLane = "ko-Latn", selectedLane = "ja-Latn", probe = true)
        assertTrue(state.confirmed)
        assertEquals("ko-Latn", state.language)
        assertEquals(PronunciationLaneSource.STATE_OFFICIAL_LANE, state.source)

        // Then the lane the native setter accepted (F2's own `z` evidence).
        val selected = payloadLane(selectedLane = "ja-Latn", advertisedLane = "ko-Latn")
        assertTrue(selected.confirmed)
        assertEquals("ja-Latn", selected.language)
        assertEquals(PronunciationLaneSource.CONFIRMED_SELECTION, selected.source)

        // Then the model's live advertisement (the custom document's own lane).
        val advertised = payloadLane(advertisedLane = "ko-Latn", probe = true)
        assertTrue(advertised.confirmed)
        assertEquals("ko-Latn", advertised.language)
        assertEquals(PronunciationLaneSource.ADVERTISED_VECTOR, advertised.source)

        // A partially-populated document: only the per-line probe confirms it, and
        // the neutral Latin tag is synthesized from the system lyrics language.
        val probe = payloadLane(probe = true)
        assertTrue(probe.confirmed)
        assertEquals(NativeLyricModelPolicy.FALLBACK_PRONUNCIATION_LANGUAGE, probe.language)
        assertEquals("und-Latn", probe.language)
        assertEquals(PronunciationLaneSource.LINE_PROBE, probe.source)

        // The system lyrics language is reused when it is already a Latin tag, so
        // the revealed track matches what the app expects; otherwise the neutral
        // tag stands in. The text is always Apple's own getter output.
        assertEquals("ja-Latn", payloadLane(probe = true, systemLyricsLanguage = "ja-Latn").language)
        assertEquals("und-Latn", payloadLane(probe = true, systemLyricsLanguage = "ja").language)
        assertEquals(
            "und-Latn",
            NativeLyricModelPolicy.probeConfirmedPronunciationLanguage(null),
        )
        assertEquals(
            "ko-Latn",
            NativeLyricModelPolicy.probeConfirmedPronunciationLanguage("ko-Latn"),
        )
    }

    /**
     * The supplement document's own lane, as the completion feed publishes it into
     * the overlay, is a first-class payload-lane confirmation.
     *
     * The bridge (1.6.6) removed the out-of-band Fragment re-entry that used to
     * force a fresh pointer presentation after a completion, and the app's install
     * method latches on pointer identity before it recomputes the pronunciation
     * flag. A custom document whose lane Apple's model has not advertised
     * therefore had no evidence left for the payload: every Apple-side source is
     * empty and the per-line probe is false on 1606/1609 until a language has
     * already been selected. The overlay lane is the document's real
     * `<transliteration xml:lang>` tag, so it resolves the lane without touching
     * Apple's data.
     */
    @Test
    fun `the supplement document's published lane confirms the payload`() {
        val supplement = payloadLane(supplementLane = "ja-Latn")
        assertTrue(supplement.confirmed)
        assertEquals("ja-Latn", supplement.language)
        assertEquals(PronunciationLaneSource.SUPPLEMENT_OVERLAY, supplement.source)

        // Apple-side evidence still wins over the supplement overlay, so an Apple
        // source's own lane is never displaced by a document this module wrote.
        assertEquals(
            "ko-Latn",
            payloadLane(advertisedLane = "ko-Latn", supplementLane = "ja-Latn").language,
        )
        assertEquals(
            PronunciationLaneSource.ADVERTISED_VECTOR,
            payloadLane(advertisedLane = "ko-Latn", supplementLane = "ja-Latn").source,
        )
        // The overlay lane is a real language tag, so it wins over the synthesized
        // probe tag (which exists only to name a lane Apple would not).
        val overlay = payloadLane(supplementLane = "ja-Latn", probe = true)
        assertEquals("ja-Latn", overlay.language)
        assertEquals(PronunciationLaneSource.SUPPLEMENT_OVERLAY, overlay.source)
        assertEquals(
            PronunciationLaneSource.LINE_PROBE,
            payloadLane(probe = true).source,
        )
        // A non-Latin tag is never a lane, exactly like every other source.
        assertFalse(payloadLane(supplementLane = "ja").confirmed)
    }

    @Test
    fun `the payload lane is never confirmed without evidence`() {
        val none = payloadLane()
        assertFalse(none.confirmed)
        assertNull(none.language)
        assertEquals(PronunciationLaneSource.NONE, none.source)
        // A non-Latin tag confirms nothing, exactly like the availability rule.
        assertFalse(payloadLane(stateLane = "ja").confirmed)
        assertFalse(payloadLane(selectedLane = "ja").confirmed)
        assertFalse(payloadLane(advertisedLane = "cmn-Hans").confirmed)
        assertEquals(
            PronunciationLaneSource.NONE,
            payloadLane(stateLane = "ja", selectedLane = "ja", advertisedLane = "ja").source,
        )
    }

    @Test
    fun `the payload lane source tokens are the device diagnostic values`() {
        assertEquals(
            listOf("state", "confirmed", "advertised", "supplement", "line-probe", "none"),
            PronunciationLaneSource.entries.map { it.token },
        )
        // The custom path's late-lane reason is its own, not the build gate's none.
        assertEquals("custom-lane", NativeLyricModelPolicy.REFRESH_REASON_CUSTOM_LANE)
    }

    /**
     * The `<transliteration xml:lang>` tag a document we write must carry. It is
     * the lane's name in the app's own lyric model, so it has to mirror the set
     * HLE asks Apple for — `ja-Latn`, `ko-Latn`, `zh-Latn` — and can only stay
     * neutral when the song's language is genuinely undetermined.
     */
    @Test
    fun `the transliteration tag mirrors HLE's requested language set`() {
        assertEquals("ja-Latn", NativeLyricModelPolicy.transliterationLanguageTag("ja"))
        assertEquals("ja-Latn", NativeLyricModelPolicy.transliterationLanguageTag("ja-JP"))
        assertEquals("ja-Latn", NativeLyricModelPolicy.transliterationLanguageTag("JPN"))
        assertEquals("ko-Latn", NativeLyricModelPolicy.transliterationLanguageTag("ko-KR"))
        assertEquals("ko-Latn", NativeLyricModelPolicy.transliterationLanguageTag("kor"))
        assertEquals("zh-Latn", NativeLyricModelPolicy.transliterationLanguageTag("zh-Hans"))
        assertEquals("zh-Latn", NativeLyricModelPolicy.transliterationLanguageTag("cmn"))
        assertEquals("zh-Latn", NativeLyricModelPolicy.transliterationLanguageTag("yue"))
        assertEquals("zh-Latn", NativeLyricModelPolicy.transliterationLanguageTag("zh_Hant_TW"))
        // `und`, an English song, an empty declaration and a null all keep the
        // neutral tag: naming a language the song is not in would be worse than
        // naming none, and no other language has a Latin romanization lane.
        listOf(null, "", "  ", "und", "und-Latn", "en", "fr-FR").forEach { language ->
            assertEquals(
                "unexpected tag for $language",
                NativeLyricModelPolicy.FALLBACK_PRONUNCIATION_LANGUAGE,
                NativeLyricModelPolicy.transliterationLanguageTag(language),
            )
        }
        assertEquals(
            listOf(null, null, "ja", "ko", "zh", null),
            listOf(null, "und", "ja", "ko", "zh", "en").map(
                NativeLyricModelPolicy::transliterationScriptLanguage,
            ),
        )
    }
}
