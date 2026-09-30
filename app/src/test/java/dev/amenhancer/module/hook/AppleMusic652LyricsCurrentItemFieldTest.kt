package dev.amenhancer.module.hook

import com.apple.android.music.model.BaseContentItem
import dev.amenhancer.module.ModuleConstants
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Apple Music 6.5.2 (1586) adaptation: the profile pinned `player.fragment.m`, which declares no
 * matching field on the real APK, so the symbol fell through to the structural scan and failed
 * closed with two candidates (`player.fragment.e#U` and `player.fragment.l#c`). Pin the verified
 * owner so both `current_song_identity` and `custom_lyrics` resolve at the profile layer.
 */
class AppleMusic652LyricsCurrentItemFieldTest {
    private val build652 = TargetBuild(ModuleConstants.TARGET_PACKAGE, "6.5.2", 1586L)

    @Test
    fun `6_5_2 profile pins fragment l instead of the stale fragment m`() {
        // Only `fragment.l` is loadable, so a VERSION_PROFILE hit proves the pin names it; under
        // the stale `fragment.m` pin the profile layer would yield nothing and fall through.
        val source = Lyrics652FakeClassSource(
            mapOf("com.apple.android.music.player.fragment.l" to Lyrics652Fixture::class.java),
        )
        val resolution = IndexedTargetSymbolResolver(build652, source)
            .resolve(AppleMusicSymbols.LyricsCurrentItemField)

        assertTrue(resolution is TargetResolution.Found)
        assertEquals(SymbolMatch.VERSION_PROFILE, (resolution as TargetResolution.Found).match)
        assertTrue(source.loadedNames.contains("com.apple.android.music.player.fragment.l"))
        assertFalse(source.loadedNames.any { it == "com.apple.android.music.player.fragment.m" })
        // A profile hit never touches the structural scan.
        assertEquals(0, source.classNameReads)
    }
}

/** 6.5.2 shape: a single non-static [BaseContentItem] field named `c`. */
@Suppress("unused", "PropertyName")
private class Lyrics652Fixture {
    val c: BaseContentItem = BaseContentItem()
}

private class Lyrics652FakeClassSource(
    private val classes: Map<String, Class<*>>,
) : TargetClassSource {
    val loadedNames = mutableListOf<String>()
    var classNameReads = 0
        private set

    override fun classNames(): List<String> {
        classNameReads++
        return emptyList()
    }

    override fun loadClass(name: String): Class<*>? {
        loadedNames += name
        return classes[name]
    }
}
