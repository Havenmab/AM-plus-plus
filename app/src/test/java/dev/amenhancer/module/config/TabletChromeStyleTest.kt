package dev.amenhancer.module.config

import org.junit.Assert.assertEquals
import org.junit.Test

class TabletChromeStyleTest {
    @Test
    fun `a missing value falls back to the shipped author style`() {
        assertEquals(TabletChromeStyle.AUTHOR, TabletChromeStyle.decode(null))
    }

    @Test
    fun `an empty value falls back to the shipped author style`() {
        assertEquals(TabletChromeStyle.AUTHOR, TabletChromeStyle.decode(""))
    }

    @Test
    fun `an unknown value falls back to the shipped author style`() {
        assertEquals(TabletChromeStyle.AUTHOR, TabletChromeStyle.decode("bogus"))
    }

    @Test
    fun `the ipad storage value decodes to the ipad style`() {
        assertEquals(TabletChromeStyle.IPAD, TabletChromeStyle.decode("ipad"))
    }

    @Test
    fun `decoding trims and ignores case`() {
        assertEquals(TabletChromeStyle.AUTHOR, TabletChromeStyle.decode(" AUTHOR "))
    }

    @Test
    fun `every enum constant round-trips through its storage value`() {
        TabletChromeStyle.values().forEach { style ->
            assertEquals(style, TabletChromeStyle.decode(style.storageValue))
        }
    }
}
