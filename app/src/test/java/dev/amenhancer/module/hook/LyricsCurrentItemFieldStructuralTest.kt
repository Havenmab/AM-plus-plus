package dev.amenhancer.module.hook

import com.apple.android.music.model.BaseContentItem
import dev.amenhancer.module.ModuleConstants
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Structural-resolution coverage for [AppleMusicSymbols.LyricsCurrentItemField] on Apple Music
 * 6.5.2 (1586). The profile pins `com.apple.android.music.player.fragment.m`, which names nothing
 * on that build, so resolution falls through to the structural fallback. The fragment ancestry
 * there declares two `BaseContentItem` fields (`fragment.e#U` and `fragment.l#c`), and the
 * verified field name `c` — the contract `isLyricsCurrentItemField` already enforces — breaks the
 * tie. These tests reuse the real loader seam rather than asserting source text.
 */
class LyricsCurrentItemFieldStructuralTest {
    private val build652 = TargetBuild(ModuleConstants.TARGET_PACKAGE, "6.5.2", 1586L)

    @Test
    fun `structural fallback prefers the verified field name over the inherited ambiguity`() {
        // Ancestry mirrors the device: fragment.l#c satisfies the verified contract, the inherited
        // fragment.e#U does not. Both are live structural candidates, so only the name tiebreaker
        // can pick one; the 6.5.2 pin (fragment.m) is deliberately mapped to nothing.
        val source = LyricsCurrentItemFakeClassSource(
            mapOf(
                "com.apple.android.music.player.fragment.PlayerLyricsViewFragment" to
                    AmbiguousLyricsFragmentFixture::class.java,
                "com.apple.android.music.player.fragment.l" to
                    VerifiedLyricsItemHolderFixture::class.java,
                "com.apple.android.music.player.fragment.e" to
                    LegacyLyricsItemHolderFixture::class.java,
            ),
        )

        val resolution = IndexedTargetSymbolResolver(build652, source)
            .resolve(AppleMusicSymbols.LyricsCurrentItemField)

        assertTrue(resolution is TargetResolution.Found)
        resolution as TargetResolution.Found
        assertEquals(SymbolMatch.STRUCTURAL_FALLBACK, resolution.match)
        assertEquals("c", resolution.value.name)
        // The fake maps a device class name onto a real JVM fixture, so the declaring class is
        // asserted by identity: the verified holder, never the inherited `U` holder.
        assertEquals(VerifiedLyricsItemHolderFixture::class.java, resolution.value.declaringClass)
    }

    @Test
    fun `a renamed field still fails closed instead of binding the wrong field`() {
        // Same shape, but the inherited holder was renamed too, so no candidate carries the
        // verified name. The fix must not fall back to the first/only structural hit: the two
        // candidates stay ambiguous and the capability keeps degrading loudly.
        val source = LyricsCurrentItemFakeClassSource(
            mapOf(
                "com.apple.android.music.player.fragment.PlayerLyricsViewFragment" to
                    RenamedLyricsFragmentFixture::class.java,
                "com.apple.android.music.player.fragment.l" to
                    RenamedLyricsItemHolderFixture::class.java,
                "com.apple.android.music.player.fragment.e" to
                    RenamedLegacyLyricsItemHolderFixture::class.java,
            ),
        )

        val resolution = IndexedTargetSymbolResolver(build652, source)
            .resolve(AppleMusicSymbols.LyricsCurrentItemField)

        assertTrue(resolution is TargetResolution.Ambiguous)
        assertEquals(2, (resolution as TargetResolution.Ambiguous).candidates.size)
    }

    @Test
    fun `a sole verified field resolves without an ambiguity to break`() {
        // Regression guard: the tiebreaker must not require two candidates. An owner that
        // declares only `c` resolves exactly as it did before the change.
        val source = LyricsCurrentItemFakeClassSource(
            mapOf(
                "com.apple.android.music.player.fragment.PlayerLyricsViewFragment" to
                    SingleCandidateLyricsFragmentFixture::class.java,
                "com.apple.android.music.player.fragment.l" to
                    SoleVerifiedLyricsItemHolderFixture::class.java,
            ),
        )

        val resolution = IndexedTargetSymbolResolver(build652, source)
            .resolve(AppleMusicSymbols.LyricsCurrentItemField)

        assertTrue(resolution is TargetResolution.Found)
        resolution as TargetResolution.Found
        assertEquals(SymbolMatch.STRUCTURAL_FALLBACK, resolution.match)
        assertEquals("c", resolution.value.name)
    }
}

private class LyricsCurrentItemFakeClassSource(
    private val classes: Map<String, Class<*>>,
) : TargetClassSource {
    override fun classNames(): List<String> = classes.keys.toList()

    override fun loadClass(name: String): Class<*>? = classes[name]
}

/** Holds the verified `c` field that the 6.5.1 profile pins as `com.apple...fragment.l`. */
private open class VerifiedLyricsItemHolderFixture {
    @Suppress("unused")
    private val c: BaseContentItem = BaseContentItem()
}

/** The unrelated `U` field the 6.5.2 device log reports alongside `fragment.l#c`. */
private open class LegacyLyricsItemHolderFixture : VerifiedLyricsItemHolderFixture() {
    @Suppress("unused")
    private val U: BaseContentItem = BaseContentItem()
}

/** The lyrics fragment: `fragment.e` (U) then `fragment.l` (c) in its ancestry. */
private class AmbiguousLyricsFragmentFixture : LegacyLyricsItemHolderFixture()

/** Fail-closed variant: the holder's field was renamed away from the verified name. */
private open class RenamedLyricsItemHolderFixture {
    @Suppress("unused")
    private val z: BaseContentItem = BaseContentItem()
}

private open class RenamedLegacyLyricsItemHolderFixture : RenamedLyricsItemHolderFixture() {
    @Suppress("unused")
    private val U: BaseContentItem = BaseContentItem()
}

private class RenamedLyricsFragmentFixture : RenamedLegacyLyricsItemHolderFixture()

/** Single-candidate variant: only the verified field exists in the ancestry. */
private open class SoleVerifiedLyricsItemHolderFixture {
    @Suppress("unused")
    private val c: BaseContentItem = BaseContentItem()
}

private class SingleCandidateLyricsFragmentFixture : SoleVerifiedLyricsItemHolderFixture()
