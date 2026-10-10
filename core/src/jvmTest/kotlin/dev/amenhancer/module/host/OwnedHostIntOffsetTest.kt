package dev.amenhancer.module.host

import org.junit.Assert.*
import org.junit.Test

class OwnedHostIntOffsetTest {
    @Test fun tabletMiniMaterialAndSheetStayAlignedAcrossGapAndInsetChanges() {
        for (height in listOf(48, 64)) {
            var margin = 34 // native 10dp spacing + 24px navigation inset, density 1
            var peek = height + margin
            val material = OwnedHostIntOffset({ margin }, { margin = it })
            lateinit var sheet: OwnedHostIntOffset
            sheet = OwnedHostIntOffset({ peek }, { peek = sheet.hostWrite(it) ?: it })
            for (gap in listOf(16, 0, 48, 16, 16)) {
                material.setOffset(gap - 10)
                sheet.setOffset(gap - 10)
                assertEquals(24 + gap, margin)
                assertEquals(height + 24 + gap, peek)
                assertEquals(1600 - margin - height, 1600 - peek)
                assertFalse(material.setOffset(gap - 10))
                assertFalse(sheet.setOffset(gap - 10))
            }
            // Rotation changes the native inset to 40; the host setter must not erase the gap.
            margin = 50
            peek = sheet.hostWrite(height + 50)!!
            material.setOffset(6)
            sheet.setOffset(6)
            assertEquals(56, margin)
            assertEquals(height + 56, peek)
            material.close(); sheet.close()
            assertEquals(50, margin)
            assertEquals(height + 50, peek)
        }
    }

    @Test fun nativeWriteEqualToTheModuleValueIsStillAChangedBaseline() {
        var peek = 100
        val sheet = OwnedHostIntOffset({ peek }, { peek = it })
        sheet.setOffset(6)
        assertEquals(106, peek)
        peek = sheet.hostWrite(106)!!
        assertEquals(112, peek)
        sheet.setOffset(6)
        assertEquals(112, peek)
        sheet.close()
        assertEquals(106, peek)
    }

    @Test fun autoPeekAndNoItemKeepTheirNativeSentinels() {
        var peek = 100
        lateinit var sheet: OwnedHostIntOffset
        sheet = OwnedHostIntOffset({ peek }, { peek = sheet.hostWrite(it) ?: it },
            { native, offset -> if (native > 0) (native + offset).coerceAtLeast(0) else native })
        sheet.setOffset(38)
        peek = sheet.hostWrite(0)!!
        sheet.setOffset(38)
        assertEquals(0, peek)
        peek = sheet.hostWrite(-1)!!
        sheet.setOffset(-10)
        assertEquals(-1, peek)
        sheet.close()
        assertEquals(-1, peek)
        peek = sheet.hostWrite(90)!!
        assertEquals(90, peek)
    }

    @Test fun releasePreservesUnhookedNativeUpdatesAndRepeatedReleaseIsInert() {
        var margin = 34
        val material = OwnedHostIntOffset({ margin }, { margin = it })
        material.setOffset(6)
        margin = 50
        material.close()
        assertEquals(50, margin)
        margin = 60
        material.close()
        assertEquals(60, margin)
        material.setOffset(6)
        assertEquals(66, margin)
        material.close()
        assertEquals(60, margin)
    }
}
