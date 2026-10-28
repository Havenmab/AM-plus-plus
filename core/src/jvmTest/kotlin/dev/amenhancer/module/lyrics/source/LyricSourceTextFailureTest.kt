package dev.amenhancer.module.lyrics.source

import java.util.Locale
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * What the sheet is allowed to say when something failed.
 *
 * The device regression this pins: a failed search rendered the throwable's own
 * text, which happened to be the fully-qualified name of a scoring class, in the
 * result area next to 「重试」. Every failure now maps to an action sentence plus,
 * at most, a reason a human can read - never a class name, never a stack frame.
 */
class LyricSourceTextFailureTest {

    private val zh: Locale = Locale.SIMPLIFIED_CHINESE
    private val en: Locale = Locale.US

    @Test
    fun `a class name message never reaches the search failure text`() {
        val text = LyricSourceText.searchFailed(
            zh,
            NoClassDefFoundError("dev.amenhancer.module.lyrics.online.LyricMatchPolicy"),
        )

        assertEquals("搜索失败，请重试", text)
        assertFalse(text.contains("dev.amenhancer"))
        assertFalse(text.contains("LyricMatchPolicy"))
        assertFalse(text.contains("NoClassDefFound"))
    }

    @Test
    fun `a class name message is recognised as a diagnostic`() {
        assertNull(LyricSourceText.readableReason(IllegalStateException("dev.amenhancer.module.lyrics.online.LyricMatchPolicy")))
        assertNull(LyricSourceText.readableReason(IllegalStateException("java.lang.NullPointerException")))
        assertNull(LyricSourceText.readableReason(IllegalStateException("at dev.amenhancer.Foo.bar(Foo.kt:12)")))
        assertNull(LyricSourceText.readableReason(IllegalStateException("SocketTimeoutException: timeout")))
        assertNull(LyricSourceText.readableReason(IllegalStateException()))
        assertNull(LyricSourceText.readableReason(null))
        assertTrue(LyricSourceText.isDiagnosticText("first" + "\n" + "second"))
    }

    @Test
    fun `a readable reason is kept beside the action sentence`() {
        assertEquals(
            "搜索失败，请重试：网络不可用",
            LyricSourceText.searchFailed(zh, IllegalStateException("网络不可用")),
        )
        assertEquals(
            "Search failed, please retry: network unreachable",
            LyricSourceText.searchFailed(en, IllegalStateException("network unreachable")),
        )
        assertEquals("读取歌词源失败，请重试", LyricSourceText.snapshotFailed(zh, IllegalStateException("")))
        assertEquals("无法切换歌词源", LyricSourceText.operationFailed(zh, null))
    }

    @Test
    fun `a failure the host already mapped is not wrapped twice`() {
        assertEquals(
            "搜索失败，请重试",
            LyricSourceText.searchFailed(zh, IllegalStateException(LyricSourceText.searchFailed(zh))),
        )
        assertEquals(
            "搜索失败，请重试：网络不可用",
            LyricSourceText.searchFailed(
                zh,
                IllegalStateException(LyricSourceText.searchFailed(zh, IllegalStateException("网络不可用"))),
            ),
        )
    }
}
