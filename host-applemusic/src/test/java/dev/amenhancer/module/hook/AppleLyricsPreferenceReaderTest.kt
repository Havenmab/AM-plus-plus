package dev.amenhancer.module.hook

import io.github.proify.lyricon.amprovider.xposed.AppleMusicHookTarget
import io.github.proify.lyricon.amprovider.xposed.AppleMusicRuntimeMember
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * Pins HLE's `readAppleLyricsPreference` order onto the fork's reader:
 * getter → cache field → snapshot → key/store read. The store read is the path
 * HLE explicitly avoids repeating in a lyric getter (it can block on DataStore),
 * so the order is the behaviour that must not drift.
 */
class AppleLyricsPreferenceReaderTest {

    @Test
    fun `the pronunciation cache field wins over the snapshot and the store`() {
        FakePreferences.reset(cached = true, key = "pronunciation")
        val selected = AppleLyricsPreferenceReader.read(
            clazz = FakePreferences::class.java,
            target = cacheTarget(),
            pronunciation = true,
            snapshot = false,
        )
        assertEquals(true, selected)
        // Cache-first: neither the snapshot nor the store was consulted.
        assertEquals(0, FakeStore.reads)
    }

    @Test
    fun `a null cache falls back to the snapshot without touching the store`() {
        FakePreferences.reset(cached = null, key = "pronunciation")
        val selected = AppleLyricsPreferenceReader.read(
            clazz = FakePreferences::class.java,
            target = cacheTarget(),
            pronunciation = true,
            snapshot = false,
        )
        assertEquals(false, selected)
        assertEquals(0, FakeStore.reads)
    }

    @Test
    fun `with no cache and no snapshot the typed key and store are read once`() {
        FakePreferences.reset(cached = null, key = "pronunciation")
        val selected = AppleLyricsPreferenceReader.read(
            clazz = FakePreferences::class.java,
            target = cacheTarget(),
            pronunciation = true,
            snapshot = null,
        )
        assertEquals(true, selected)
        assertEquals(1, FakeStore.reads)
    }

    @Test
    fun `the resolver getter is used first when the profile pins one`() {
        FakePreferences.reset(cached = false, key = "pronunciation", getterValue = true)
        val selected = AppleLyricsPreferenceReader.read(
            clazz = FakePreferences::class.java,
            target = getterTarget(),
            pronunciation = true,
            snapshot = null,
        )
        assertEquals(true, selected)
        assertEquals(0, FakeStore.reads)
    }

    @Test
    fun `translation reads the pinned getter and never the pronunciation cache`() {
        FakePreferences.reset(cached = true, key = "pronunciation", getterValue = false)
        val selected = AppleLyricsPreferenceReader.read(
            clazz = FakePreferences::class.java,
            target = getterTarget(),
            pronunciation = false,
            snapshot = null,
        )
        assertEquals(false, selected)
    }

    @Test
    fun `an absent member is unknown rather than a wrong value`() {
        assertNull(
            AppleLyricsPreferenceReader.read(
                clazz = FakePreferences::class.java,
                target = AppleMusicHookTarget("fake"),
                pronunciation = true,
                snapshot = null,
            ),
        )
    }

    private fun cacheTarget() = AppleMusicHookTarget(
        className = "fake",
        runtimeMemberNames = mapOf(
            AppleMusicRuntimeMember.LYRICS_PREFERENCES_PRONUNCIATION_CACHE_FIELD to "s",
            AppleMusicRuntimeMember.LYRICS_PREFERENCES_PRONUNCIATION_KEY_FIELD to "k",
            AppleMusicRuntimeMember.LYRICS_PREFERENCES_STORE_GETTER to "h",
            AppleMusicRuntimeMember.LYRICS_PREFERENCES_STORE_READ_METHOD to "d",
        ),
    )

    private fun getterTarget() = AppleMusicHookTarget(
        className = "fake",
        runtimeMemberNames = mapOf(
            AppleMusicRuntimeMember.LYRICS_PREFERENCES_TRANSLATION_GETTER to
                "isLyricsTranslationSelected",
            AppleMusicRuntimeMember.LYRICS_PREFERENCES_PRONUNCIATION_GETTER to
                "isLyricsPronunciationSelected",
        ),
    )

    /** The 1606-shaped store: `ja.i0.h()` returns it, `d(key, default)` reads it. */
    object FakeStore {
        @Volatile
        var reads: Int = 0

        fun d(key: Any?, default: Boolean): Boolean {
            reads += 1
            return true
        }
    }

    /**
     * The 1606 `ja.i0` shape: a `Boolean` cache field, a key field, a store
     * getter and the two stable-profile static getters.
     */
    class FakePreferences private constructor() {
        companion object {
            @JvmField
            var s: Boolean? = null

            @JvmField
            var k: Any? = null

            private var getterValue: Boolean = false

            @JvmStatic
            fun h(): Any = FakeStore

            @JvmStatic
            fun isLyricsPronunciationSelected(): Boolean = getterValue

            @JvmStatic
            fun isLyricsTranslationSelected(): Boolean = getterValue

            fun reset(cached: Boolean?, key: Any?, getterValue: Boolean = false) {
                s = cached
                k = key
                this.getterValue = getterValue
                FakeStore.reads = 0
            }
        }
    }
}
