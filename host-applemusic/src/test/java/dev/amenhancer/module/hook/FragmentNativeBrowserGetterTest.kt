package dev.amenhancer.module.hook

import org.junit.Assert.*
import org.junit.Test

class FragmentNativeBrowserGetterTest {
    @Test fun nativeDefaultInterfaceMethodIsResolvedWithoutAFragmentDeclaration() {
        val song = NativeBrowserProviderFixture.Song()
        assertTrue(runCatching { song.javaClass.getDeclaredMethod("getMediaBrowser") }.isFailure)
        val getter = resolveFragmentNativeBrowserGetter(song.javaClass)
        assertEquals(NativeBrowserProviderFixture::class.java, getter.declaringClass)
        assertSame(song, getter.invoke(song))
    }

    @Test fun restoredSubclassStillUsesTheNativeDefaultProvider() {
        val song = NativeBrowserProviderFixture.SongSubclass()
        assertSame(song, resolveFragmentNativeBrowserGetter(song.javaClass).invoke(song))
    }
}
