package io.github.proify.lyricon.amprovider.xposed

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Pins the already-rendered path added by HLE v8.2.1 (`1a665f69`): when the row
 * already shows the requested title/subtitle, a full `invalidateAll` is skipped
 * and the new [DataBindingRefreshStrategy.ALREADY_RENDERED] value is reported.
 *
 * Existing callers compare against `FULL_INVALIDATE`, so the new value is not
 * treated as an invalidation by any of them.
 */
class AppleDataBindingRefreshStrategyTest {

    @Test
    fun `an already rendered row is detected before invalidating`() {
        assertTrue(
            dataBindingAliasAlreadyRendered(
                expectedTitle = "Correct Title",
                expectedSubtitle = "Correct Artist",
                renderedTexts = listOf("Correct Title", "Correct Artist", "3:42"),
            ),
        )
        assertFalse(
            dataBindingAliasAlreadyRendered(
                expectedTitle = "Correct Title",
                expectedSubtitle = "Correct Artist",
                renderedTexts = listOf("Wrong Title", "Correct Artist"),
            ),
        )
    }

    @Test
    fun `a blank expectation is not a reason to skip the invalidation`() {
        assertFalse(
            shouldSkipDataBindingInvalidate(
                expectedTitle = null,
                expectedSubtitle = null,
                renderedTexts = listOf("anything"),
            ),
        )
        assertTrue(
            shouldSkipDataBindingInvalidate(
                expectedTitle = "Correct Title",
                expectedSubtitle = null,
                renderedTexts = listOf("Correct Title"),
            ),
        )
    }

    @Test
    fun `the already rendered value never reads as full invalidation`() {
        assertTrue(
            DataBindingRefreshStrategy.ALREADY_RENDERED !=
                DataBindingRefreshStrategy.FULL_INVALIDATE,
        )
    }
}
