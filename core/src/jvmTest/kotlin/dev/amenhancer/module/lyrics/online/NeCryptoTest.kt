package dev.amenhancer.module.lyrics.online

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import javax.crypto.Cipher
import javax.crypto.spec.SecretKeySpec

/**
 * Pins the eapi envelope to a fixed input vector and the YRC parser to fixed
 * lyric bodies. The `encryptParams` vector was produced with the JDK's
 * `AES/ECB/PKCS5Padding` from the documented
 * `nobody{path}use{json}md5forencrypt` digest, so the digest construction is
 * checked against the protocol rather than against the encoder under test.
 */
class NeCryptoTest {

    @Test
    fun `encrypts the pinned eapi params vector`() {
        val encrypted = NeCrypto.encryptParams(PATH, JSON)

        assertEquals(
            "04AE33D34A93FE3EC22DA8FA305D290AB337D0FE5F36D211DE0D338CC6AA89D0" +
                "BC8FAF0071602A9F78700181DB1C89821AB2481A488A273595FC916D63D51E9E" +
                "AC755B0E09943047F91BF356292B69E229456A0E5FA9E948CC39E751D79170FB",
            NeCrypto.toUpperHex(encrypted),
        )
    }

    @Test
    fun `digest is the documented nobody-use envelope`() {
        assertEquals(
            "nobody/api/song/lyric/v1use{\"id\":1,\"lv\":\"-1\"}md5forencrypt",
            "nobody${PATH}use${JSON}md5forencrypt",
        )
        assertEquals(
            NeCrypto.md5("nobody${PATH}use${JSON}md5forencrypt"),
            NeCrypto.md5(String.format("nobody%suse%smd5forencrypt", PATH, JSON)),
        )
    }

    @Test
    fun `aes decrypt reverses encryptParams`() {
        val encrypted = NeCrypto.encryptParams(PATH, JSON)

        val plain = NeCrypto.aesDecrypt(encrypted)

        assertTrue(plain.startsWith("$PATH-36cd479b6b5-$JSON-36cd479b6b5-"))
    }

    @Test
    fun `aes decrypt rejects a body that is not a block multiple`() {
        assertEquals("", NeCrypto.aesDecrypt(byteArrayOf(1, 2, 3)))
    }

    @Test
    fun `builds the documented form body for a fixed input`() {
        val body = NeApiProtocol.buildFormBody(
            path = "/eapi/song/lyric/v1",
            cookies = mapOf("os" to "pc", "appver" to NeApiProtocol.APP_VER),
            clientSign = "sign",
            deviceId = "device",
            requestId = 1L,
            params = linkedMapOf("id" to 1L, "lv" to "-1"),
            eR = null,
        )

        assertTrue(body.startsWith("params="))
        val parsed = NeApiProtocol.parseFormBody(body)!!
        assertEquals("/api/song/lyric/v1", parsed.first)
        val params = org.json.JSONObject(parsed.second)
        assertEquals(1L, params.getLong("id"))
        assertEquals("-1", params.getString("lv"))
        assertTrue(params.has("header"))
        assertTrue(params.getString("header").contains("\"deviceId\":\"device\""))
    }

    @Test
    fun `anonymous username is the base64 of deviceId and its xored md5`() {
        assertEquals(
            "YWJjZGVmMDEyMzQ1Njc4OWFiY2RlZjAxMjM0NTY3ODkgbjE4ZUJNdzJxU3JOQzArcnRyckk0QT09",
            NeSource.anonymousUsername("abcdef0123456789abcdef0123456789"),
        )
    }

    @Test
    fun `parses YRC word timing and aligns translation and romanization`() {
        val result = YrcParser.parse(
            yrc = YRC,
            lrc = null,
            tlyric = TLYRIC,
            romalrc = ROMALRC,
        )

        assertEquals(2, result?.original?.size)
        val first = result!!.original[0]
        assertEquals(0L, first.start)
        assertEquals(1_500L, first.end)
        assertEquals(listOf("瞳", "映る"), first.words.map(LyricsWord::text))
        assertEquals(0L, first.words[0].start)
        assertEquals(500L, first.words[0].end)
        assertEquals(500L, first.words[1].start)
        assertEquals(1_500L, first.words[1].end)

        assertEquals("瞳孔映照着 寂静的世界", result.translated!!.first().words.single().text)
        assertEquals("hitomi utsuru shizuka na sekai", result.romanization!!.first().words.single().text)
    }

    @Test
    fun `falls back to LRC when the YRC body is absent`() {
        val result = YrcParser.parse(
            yrc = null,
            lrc = "[00:00.000]Hello\n[00:02.500]World",
            tlyric = "[00:00.000]你好",
        )

        assertEquals(listOf("Hello", "World"), result?.original?.map(::lineText))
        assertEquals("你好", result?.translated?.first()?.words?.single()?.text)
    }

    @Test
    fun `returns null when both lyric bodies are absent or empty`() {
        assertNull(YrcParser.parse(yrc = null, lrc = null))
        assertNull(YrcParser.parse(yrc = "", lrc = ""))
    }

    @Test
    fun `a yrc body without word tags keeps the line text`() {
        val result = YrcParser.parse(yrc = "[0,1500]瞳映る")

        assertEquals("瞳映る", result?.original?.single()?.words?.single()?.text)
        assertEquals(0L, result?.original?.single()?.start)
        assertEquals(1_500L, result?.original?.single()?.end)
    }

    private fun lineText(line: LyricsLine): String = line.words.joinToString("") { it.text }

    private companion object {
        const val PATH = "/api/song/lyric/v1"
        const val JSON = "{\"id\":1,\"lv\":\"-1\"}"

        const val YRC =
            "[0,1500](0,500,0)瞳(500,1000,0)映る\n" +
                "[20370,1760](20370,1000,0)静かな(21370,760,0)世界"

        const val TLYRIC =
            "[00:00.000]瞳孔映照着 寂静的世界\n" +
                "[00:20.370]安静的世界"

        const val ROMALRC =
            "[00:00.000]hitomi utsuru shizuka na sekai\n" +
                "[00:20.370]shizuka na sekai"
    }
}

/** Encrypts a test response body with the fixed eapi key. */
internal fun aesEncryptBytes(text: String): ByteArray {
    val cipher = Cipher.getInstance("AES/ECB/PKCS5Padding")
    cipher.init(
        Cipher.ENCRYPT_MODE,
        SecretKeySpec("e82ckenh8dichen8".toByteArray(), "AES"),
    )
    return cipher.doFinal(text.toByteArray())
}
