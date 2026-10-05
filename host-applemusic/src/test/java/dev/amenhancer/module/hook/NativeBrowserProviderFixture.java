package dev.amenhancer.module.hook;

public interface NativeBrowserProviderFixture {
    default Object getMediaBrowser() { return this; }
    class Song implements NativeBrowserProviderFixture { }
    class SongSubclass extends Song { }
}
