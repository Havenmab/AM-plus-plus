package dev.amenhancer.module.lyrics.online

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Pins QQ's QRC envelope to a fixed hex body produced with the JDK's
 * `DESede/ECB/NoPadding` + zlib, so the parser and the cipher are checked
 * against bytes rather than against the encoder under test.
 *
 * HLE's hand-rolled 3DES never reproduced the JDK result (it disagrees with a
 * single-DES vector too); [QmCrypto] therefore uses the JDK cipher, and the
 * `decrypts the pinned original QRC envelope` case is what guards the swap.
 */
class QrcParserTest {

    @Test
    fun `decrypts the pinned original QRC envelope into the xml body`() {
        val decrypted = QmCrypto.decryptQrc(QRC_ORIGINAL_HEX)

        assertTrue(decrypted.contains("<Lyric_1 LyricType="))
        assertTrue(decrypted.contains("[730,1817]遗(730,216)憾(946,240)要(1186,305)"))
    }

    @Test
    fun `an empty or non-hex envelope decrypts to nothing`() {
        assertEquals("", QmCrypto.decryptQrc(""))
        assertEquals("", QmCrypto.decryptQrc("  "))
        assertEquals("", QmCrypto.decryptQrc("zzzz"))
        assertEquals("", QmCrypto.decryptQrc("00"))
    }

    @Test
    fun `parses QRC words and keeps the line span`() {
        val result = QrcParser.parse(QmCrypto.decryptQrc(QRC_ORIGINAL_HEX))

        assertEquals(2, result.original.size)
        assertEquals(0L, result.original[0].start)
        assertEquals(152L, result.original[0].end)

        // HLE's cursor-based word loop leaves the leading pre-marker text as a
        // zero-width word at the line start and a zero-width tail word at the
        // line end; the port keeps both so the two parse identically.
        val first = result.original[0].words
        assertEquals(listOf("金", "童", "子", ""), first.map(LyricsWord::text))
        assertEquals(0L, first[0].start)
        assertEquals(0L, first[0].end)
        assertEquals(0L, first[1].start)
        assertEquals(50L, first[1].end)
        assertEquals(50L, first[2].start)
        assertEquals(100L, first[2].end)
        assertEquals(100L, first[3].start)
        assertEquals(152L, first[3].end)

        val second = result.original[1].words
        assertEquals(listOf("遗", "憾", "要", "告", "诉", "你", ""), second.map(LyricsWord::text))
        assertEquals(730L, second[1].start)
        assertEquals(946L, second[1].end)
        assertEquals(1754L, second[5].start)
        assertEquals(2003L, second[5].end)
        assertEquals(2003L, second[6].start)
        assertEquals(2547L, second[6].end)
    }

    @Test
    fun `parses QQ QRC romanization instead of treating it as LRC`() {
        val result = QrcParser.parse(
            original = QmCrypto.decryptQrc(QRC_ORIGINAL_HEX),
            romanization = QmCrypto.decryptQrc(QRC_ROMANIZATION_HEX),
        )

        val romanization = result.romanization!!
        assertEquals(2, romanization.size)
        assertEquals(730L, romanization[1].start)
        assertEquals(
            "wai han yiu gou sou nei",
            romanization[1].words.joinToString("") { it.text },
        )
    }

    @Test
    fun `keeps plain LRC auxiliary lyrics compatible`() {
        val result = QrcParser.parse(
            original = QRC_BARE_LINE,
            translated = "[00:00.730]遗憾要告诉你",
            romanization = "[00:00.730]wai han yiu gou sou nei",
        )

        assertEquals("遗憾要告诉你", result.translated!!.single().words.single().text)
        assertEquals("wai han yiu gou sou nei", result.romanization!!.single().words.single().text)
    }

    @Test
    fun `inserts one space between QRC pronunciation units`() {
        val result = QrcParser.parse(
            original = QRC_BARE_LINE,
            romanization = QRC_BARE_ROMANIZATION,
        )

        assertEquals(
            "wai han yiu gou sou nei",
            result.romanization!!.single().words.single().text,
        )
    }

    @Test
    fun `a plain LRC body is parsed through the LRC fallback`() {
        val result = QrcParser.parse(
            original = "[00:00.730]遗憾要告诉你",
            type = "lrc",
        )

        assertEquals(1, result.original.size)
        assertEquals(730L, result.original.single().start)
    }

    @Test
    fun `an empty body yields a result with no lines`() {
        val result = QrcParser.parse(original = null)

        assertTrue(result.original.isEmpty())
        assertEquals(null, result.translated)
        assertEquals(null, result.romanization)
    }

    private companion object {
        /** `[730,1817]遗(730,216)…你(2003,544)` wrapped in QQ's QRC XML. */
        const val QRC_BARE_LINE =
            "[730,1817]遗(730,216)憾(946,240)要(1186,305)告(1491,263)诉(1754,249)你(2003,544)"

        const val QRC_BARE_ROMANIZATION =
            "[730,1817]wai(730,216)han(946,240)yiu(1186,305)gou(1491,263)sou(1754,249)nei(2003,544)"

        const val QRC_ORIGINAL_HEX =
            "9849C4081E5879AEE5D98CE3CBD1B1F23B9CCEBC5D96ACA92A2AF60155BD0E579F79CC80BAF6D614" +
                "F516EA2428CDBF3C275A49B10FBFB69E24C0F66D9E1CB8CBC193C501837FCC156C67C91749E167DC" +
                "A52637B5B5CBE0F2626552345D5C53582FD737B8FF5F0D34A96697E642AB656FACF2B336ADCC693" +
                "F252A4E70A756A797BDC194F45F78C8DB7424A9D35385AD1DD050B621BE8E3207775F284BE5B6067" +
                "00A0D372A6FDA5DF7EA3A8A707E2D4A18BD40C67DEF51C8F89E0A2636E73B125367A753488EFBE21" +
                "4B28B0F5BF53C6D51788C485C5CA8562A4530F69381BD5BFADF645FD183710D4521D31ACCC49C250" +
                "F2E43BE1848FDD62F63DB225C1D9FF330EF4DE974A3B97328"

        const val QRC_ROMANIZATION_HEX =
            "7CCC72B63A61EDD261BB58C8F1F727571DFD404B0F4A13B882FBA4E73B8067DB7A1D7F8A86CD102" +
                "20125E6ABF1B2F865DC0BFF2FD07B9ADF34E89544DB43002740AFAE30261F557C6DCFD4D5CCA8F3" +
                "57E6D89465CF80C773760ABEA33FEC5740F8657E4621EC5CE56ABD0881AC59FBAC8D09FB614C790" +
                "0DB63F7DC875752E3D4BB60355A6DF6939584C50712D02AABCB0D055B321876510933D6BAAD1F2F5" +
                "E6726CE67B0DE3937C1852D7F1D7DE82478DA9930A2F3F689ACD5F3E0FC08CA15CAB06E598A31F75" +
                "E6C172FA715D4AB5E314AAE8230A099B7AF2229DA491732132B8C2FA20F35BCDCA6EDA58F39A3955" +
                "387872BCBEE675C8BC5A9F9143884E43195DADFC681625F30294966DC9657B7810F"
    }
}
