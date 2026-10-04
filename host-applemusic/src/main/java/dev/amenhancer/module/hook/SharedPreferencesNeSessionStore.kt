package dev.amenhancer.module.hook

import android.content.Context
import android.content.SharedPreferences
import dev.amenhancer.module.lyrics.online.NeSession
import dev.amenhancer.module.lyrics.online.NeSessionStore

/**
 * The host's persistent [NeSessionStore], backed by the same
 * [SharedPreferences] mechanism the embedded settings use.
 *
 * Netease's anonymous session is three small values, so a preferences file is
 * the right fit: no new file format, no serialization dependency, and the
 * ~10-day expiry stays in [dev.amenhancer.module.lyrics.online.NeSource], which
 * measures it from the persisted `initializedAtMillis`.
 *
 * Cookies are stored as a `StringSet` of `name=value` entries because
 * preferences have no map type. Any malformed payload — a cookie without a
 * separator, a missing key, or a preferences implementation that throws — reads
 * back as `null`, which the provider treats as "no session" and re-handshakes.
 * [save] and [clear] are best-effort: a storage failure must not break playback.
 */
class SharedPreferencesNeSessionStore internal constructor(
    private val preferences: SharedPreferences,
) : NeSessionStore {

    constructor(context: Context) : this(
        context.applicationContext.getSharedPreferences(
            PREFERENCES_NAME,
            Context.MODE_PRIVATE,
        ),
    )

    override fun load(): NeSession? = runCatching {
        val rawCookies = preferences.getStringSet(KEY_COOKIES, null)
            ?: return@runCatching null
        val cookies = rawCookies.toCookieMap() ?: return@runCatching null
        val session = NeSession(
            cookies = cookies,
            userId = preferences.getLong(KEY_USER_ID, 0L),
            initializedAtMillis = preferences.getLong(KEY_INITIALIZED_AT, 0L),
        )
        // A half-written or empty payload is "no session", not a session with
        // zeroed fields; the provider then performs the anonymous handshake.
        session.takeIf {
            it.userId > 0L && it.initializedAtMillis > 0L && it.cookies.isNotEmpty()
        }
    }.getOrNull()

    override fun save(session: NeSession) {
        runCatching {
            preferences.edit()
                .putStringSet(
                    KEY_COOKIES,
                    session.cookies.entries
                        .map { (name, value) -> "$name=$value" }
                        .toSet(),
                )
                .putLong(KEY_USER_ID, session.userId)
                .putLong(KEY_INITIALIZED_AT, session.initializedAtMillis)
                .commit()
        }
    }

    override fun clear() {
        runCatching {
            preferences.edit()
                .remove(KEY_COOKIES)
                .remove(KEY_USER_ID)
                .remove(KEY_INITIALIZED_AT)
                .commit()
        }
    }

    internal companion object {
        const val PREFERENCES_NAME = "ampp-online-lyric-session"
        const val KEY_COOKIES = "netease-cookies"
        const val KEY_USER_ID = "netease-user-id"
        const val KEY_INITIALIZED_AT = "netease-initialized-at"

        /** Returns null when any entry cannot be split into a non-empty name and a value. */
        fun Set<String>.toCookieMap(): Map<String, String>? {
            val cookies = linkedMapOf<String, String>()
            for (entry in this) {
                val separator = entry.indexOf('=')
                if (separator <= 0) return null
                cookies[entry.substring(0, separator)] = entry.substring(separator + 1)
            }
            return cookies
        }
    }
}
