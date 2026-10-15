package dev.amenhancer.module.lyrics.online

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Covers the background lane's parenthesis normalization: Apple displays a
 * background vocal without the parentheses that wrap it, so the module's own
 * background romanization is stripped the same way. The wrapper is a property of
 * the whole `x-bg` group — its opening on the first slice, its closing on the
 * last — so the group is normalized together and an internal pair is kept.
 */
class BackgroundParenthesisPolicyTest {

    @Test
    fun `strips a wrapper spread across the group`() {
        assertEquals(
            listOf("jibun", "de", "erabu", "michi"),
            BackgroundParenthesisPolicy.stripGroup(
                listOf("(jibun", "de", "erabu", "michi)"),
            ),
        )
        assertEquals(
            listOf("誰", "が", "選ぶ", "道"),
            BackgroundParenthesisPolicy.stripGroup(listOf("(誰", "が", "選ぶ", "道)")),
        )
        // Only the group's first slice loses its opening and only its last loses
        // its closing: a stray parenthesis on a middle slice is left alone.
        assertEquals(
            listOf("a", "b)", "c"),
            BackgroundParenthesisPolicy.stripGroup(listOf("(a", "b)", "c)")),
        )
    }

    @Test
    fun `strips a wrapper that shares one slice`() {
        assertEquals(listOf("boon"), BackgroundParenthesisPolicy.stripGroup(listOf("(boon)")))
        assertEquals(listOf("ボーン！"), BackgroundParenthesisPolicy.stripGroup(listOf("(ボーン！)")))
        assertEquals(listOf("bon"), BackgroundParenthesisPolicy.stripGroup(listOf("（bon）")))
        assertEquals("boon", BackgroundParenthesisPolicy.strip("(boon)"))
    }

    @Test
    fun `strips a lone opening or a lone closing`() {
        assertEquals(listOf("誰"), BackgroundParenthesisPolicy.stripGroup(listOf("(誰")))
        assertEquals(listOf("道"), BackgroundParenthesisPolicy.stripGroup(listOf("道)")))
        assertEquals("自分", BackgroundParenthesisPolicy.strip("（自分"))
        assertEquals("michi", BackgroundParenthesisPolicy.strip("michi）"))
    }

    @Test
    fun `keeps an internal parenthesis`() {
        assertEquals(
            listOf("Re:born (Bon!)"),
            BackgroundParenthesisPolicy.stripGroup(listOf("Re:born (Bon!)")),
        )
        assertEquals("baby (yeah)", BackgroundParenthesisPolicy.strip("baby (yeah)"))
        assertEquals("(a) b (c)", BackgroundParenthesisPolicy.strip("(a) b (c)"))
        assertEquals("plain", BackgroundParenthesisPolicy.strip("plain"))
    }

    @Test
    fun `an empty result stays empty rather than becoming a value`() {
        assertEquals("", BackgroundParenthesisPolicy.strip("()"))
        assertEquals("", BackgroundParenthesisPolicy.strip("( )"))
        assertEquals("", BackgroundParenthesisPolicy.strip(""))
        assertEquals("", BackgroundParenthesisPolicy.strip("   "))
        // Trimming is part of the same step, so an edge space never survives.
        assertEquals("boon", BackgroundParenthesisPolicy.strip(" (boon) "))
        // A slice that is nothing but a wrapper becomes empty, which the caller
        // treats as no content rather than rendering an empty word.
        assertEquals(listOf(""), BackgroundParenthesisPolicy.stripGroup(listOf("()")))
    }
}
