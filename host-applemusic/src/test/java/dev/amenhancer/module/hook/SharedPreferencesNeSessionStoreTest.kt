package dev.amenhancer.module.hook

import android.content.SharedPreferences
import dev.amenhancer.module.lyrics.online.NeSession
import dev.amenhancer.module.lyrics.online.NeSource
import dev.amenhancer.module.lyrics.source.LyricHttpResponse
import dev.amenhancer.module.lyrics.source.LyricHttpTransport
import java.lang.reflect.Proxy
import java.util.Random
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Pure-JVM coverage for the persistent Netease session: round-trip across a
 * simulated process restart, expiry enforced by the provider, and every
 * corrupt/throwing payload falling back to "no session" so the anonymous
 * handshake runs again. No network: the transport is a fake.
 */
class SharedPreferencesNeSessionStoreTest {

    @Test
    fun `a saved session round-trips through preferences`() {
        val store = store(fakePreferences())

        store.save(session(userId = 42L, cookies = mapOf("MUSIC_A" to "a1", "WNMCID" to "w")))

        val loaded = store.load()!!
        assertEquals(42L, loaded.userId)
        assertEquals(mapOf("MUSIC_A" to "a1", "WNMCID" to "w"), loaded.cookies)
        assertEquals(NOW, loaded.initializedAtMillis)
    }

    @Test
    fun `a later store over the same preferences sees the persisted session`() {
        val preferences = fakePreferences()
        store(preferences).save(session(userId = 7L, cookies = mapOf("MUSIC_A" to "kept")))

        // A fresh instance stands in for the next process start.
        val loaded = store(preferences).load()!!

        assertEquals(7L, loaded.userId)
        assertEquals("kept", loaded.cookies["MUSIC_A"])
    }

    @Test
    fun `a second save replaces the first instead of merging`() {
        val store = store(fakePreferences())
        store.save(session(userId = 1L, cookies = mapOf("MUSIC_A" to "old", "OLD" to "1")))
        store.save(session(userId = 2L, cookies = mapOf("MUSIC_A" to "new")))

        val loaded = store.load()!!
        assertEquals(2L, loaded.userId)
        assertEquals(mapOf("MUSIC_A" to "new"), loaded.cookies)
    }

    @Test
    fun `clear removes the persisted session`() {
        val store = store(fakePreferences())
        store.save(session())

        store.clear()

        assertNull(store.load())
    }

    @Test
    fun `a missing store reads back as no session`() {
        assertNull(store(fakePreferences()).load())
    }

    @Test
    fun `a cookie without a name separator reads back as no session`() {
        val store = store(fakePreferences())
        // The store trusts its own writer, so writing an unusable cookie is how
        // a torn/foreign payload is simulated.
        store.save(session(cookies = mapOf("" to "value")))

        assertNull(store.load())
    }

    @Test
    fun `a half written payload reads back as no session`() {
        val preferences = fakePreferences(
            mutableMapOf<String, Any>(
                SharedPreferencesNeSessionStore.KEY_COOKIES to setOf("MUSIC_A=a1"),
            ),
        )

        assertNull(store(preferences).load())
    }

    @Test
    fun `a preferences implementation that throws reads back as no session`() {
        assertNull(store(fakePreferences(throwing = true)).load())
    }

    @Test
    fun `an expired persisted session triggers a new anonymous handshake`() {
        val preferences = fakePreferences()
        store(preferences).save(
            session(
                userId = 99L,
                cookies = mapOf("MUSIC_A" to "stale"),
                initializedAtMillis = NOW - ELEVEN_DAYS_MS,
            ),
        )
        val transport = RecordingTransport()

        NeSource(transport, store(preferences), Random(1L), clock = { NOW }).search("x")

        assertTrue(transport.posts.any { it.endsWith("/eapi/register/anonimous") })
    }

    @Test
    fun `a fresh persisted session skips the handshake and reuses the cookies`() {
        val preferences = fakePreferences()
        store(preferences).save(
            session(userId = 7L, cookies = mapOf("MUSIC_A" to "kept"), initializedAtMillis = NOW - DAY_MS),
        )
        val transport = RecordingTransport()

        NeSource(transport, store(preferences), Random(1L), clock = { NOW }).search("x")

        assertEquals(1, transport.posts.size)
        assertTrue(transport.posts.single().endsWith("/eapi/search/song/list/page"))
        assertTrue(transport.cookieHeaders.single().contains("MUSIC_A=kept"))
    }

    private fun session(
        userId: Long = 1L,
        cookies: Map<String, String> = mapOf("MUSIC_A" to "a1"),
        initializedAtMillis: Long = NOW,
    ): NeSession = NeSession(
        cookies = cookies,
        userId = userId,
        initializedAtMillis = initializedAtMillis,
    )

    private fun store(preferences: SharedPreferences): SharedPreferencesNeSessionStore =
        SharedPreferencesNeSessionStore(preferences)

    private class RecordingTransport : LyricHttpTransport {
        val posts = mutableListOf<String>()
        val cookieHeaders = mutableListOf<String>()

        override fun get(url: String): String? = null

        override fun postFormResponse(
            url: String,
            body: String,
            headers: Map<String, String>,
        ): LyricHttpResponse {
            posts += url
            cookieHeaders += headers["Cookie"].orEmpty()
            return LyricHttpResponse(statusCode = 200, body = ByteArray(0))
        }
    }

    /**
     * Dynamic-proxy [SharedPreferences] over a plain map, the same approach the
     * embedded-storage test uses: the real Android class is never touched.
     */
    private fun fakePreferences(
        values: MutableMap<String, Any> = mutableMapOf(),
        throwing: Boolean = false,
    ): SharedPreferences {
        lateinit var editor: SharedPreferences.Editor
        editor = Proxy.newProxyInstance(
            SharedPreferences.Editor::class.java.classLoader,
            arrayOf(SharedPreferences.Editor::class.java),
        ) { _, method, args ->
            when (method.name) {
                "putStringSet" -> {
                    values[args[0] as String] = (args[1] as Set<*>).filterIsInstance<String>().toSet()
                    editor
                }
                "putLong" -> {
                    values[args[0] as String] = args[1] as Long
                    editor
                }
                "putString", "putInt", "putBoolean", "putFloat" -> {
                    values[args[0] as String] = args[1] as Any
                    editor
                }
                "remove" -> {
                    values.remove(args[0] as String)
                    editor
                }
                "clear" -> {
                    values.clear()
                    editor
                }
                "commit" -> true
                "apply" -> null
                "toString" -> "fake-ne-session-editor"
                else -> editor
            }
        } as SharedPreferences.Editor
        return Proxy.newProxyInstance(
            SharedPreferences::class.java.classLoader,
            arrayOf(SharedPreferences::class.java),
        ) { proxy, method, args ->
            when (method.name) {
                "getStringSet" -> {
                    if (throwing) throw IllegalStateException("preferences unavailable")
                    @Suppress("UNCHECKED_CAST")
                    values[args[0] as String] as? Set<String>
                }
                "getLong" -> (values[args[0] as String] as? Long) ?: (args[1] as Long)
                "getString" -> (values[args[0] as String] as? String) ?: (args[1] as String?)
                "edit" -> editor
                "contains" -> values.containsKey(args[0] as String)
                "getAll" -> values.toMap()
                "toString" -> "fake-ne-session-preferences"
                "hashCode" -> System.identityHashCode(proxy)
                "equals" -> proxy === args[0]
                else -> null
            }
        } as SharedPreferences
    }

    private companion object {
        const val NOW = 1_700_000_000_000L
        const val DAY_MS = 24L * 60 * 60 * 1000
        const val ELEVEN_DAYS_MS = 11L * DAY_MS
    }
}
