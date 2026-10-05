package dev.amenhancer.module.hook

import org.junit.Assert.*
import org.junit.Test

class FragmentTabletVideoCallScopeTest {
    @Test fun staticCompositorCanResolveTheSameSurfaceTwiceWithoutChangingLaterNativeCalls() {
        val calls = FragmentTabletVideoCallScope()
        val song = Any()
        val surface = Any()
        fun nativeGetter(): Any? = if (calls.allows(song)) surface else null
        assertNull(nativeGetter())
        calls.enter(song, true)
        try {
            // M1 first checks the surface, then E1 queries it again to apply image/video alpha.
            assertSame(surface, nativeGetter())
            assertSame(surface, nativeGetter())
        } finally { calls.leave(song) }
        assertNull(nativeGetter())
    }

    @Test fun nestedVideoAndOtherOwnersDoNotInheritTheStaticFallback() {
        val calls = FragmentTabletVideoCallScope()
        val song = Any()
        val lyrics = Any()
        calls.enter(song, true)
        calls.enter(song, false)
        assertFalse(calls.allows(song))
        calls.leave(song)
        assertTrue(calls.allows(song))
        calls.enter(lyrics, false)
        assertFalse(calls.allows(song))
        assertFalse(calls.allows(lyrics))
        calls.leave(lyrics)
        assertTrue(calls.allows(song))
        calls.leave(song)
        assertFalse(calls.allows(song))
    }

    @Test fun failedNativeCallReleasesItsFallbackBeforeNextInvocation() {
        val calls = FragmentTabletVideoCallScope()
        val song = Any()
        runCatching {
            calls.enter(song, true)
            try { error("native compositor failure") } finally { calls.leave(song) }
        }
        assertFalse(calls.allows(song))
        calls.enter(song, false)
        assertFalse(calls.allows(song))
        calls.leave(song)
    }
}
