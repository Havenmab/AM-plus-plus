package dev.amenhancer.module.lyrics.online

import java.io.ByteArrayOutputStream
import java.util.regex.Pattern
import java.util.zip.Inflater
import javax.crypto.Cipher
import javax.crypto.spec.SecretKeySpec

/**
 * QQ Music QRC parsing, ported from HyperLyricsEnhanced's `QrcParser` plus the
 * QRC half of its `CryptoUtils`. QRC word tags are absolute milliseconds, which
 * is why the LRC parser must not see them.
 */
object QrcParser {

    private val QRC_LINE_PATTERN: Pattern = Pattern.compile("^\\[(\\d+),(\\d+)](.*)$")
    private val QRC_WORD_PATTERN: Pattern = Pattern.compile("\\((\\d+),(\\d+)\\)([^()]*)")
    private val QRC_XML_PATTERN = Pattern.compile(
        "<Lyric_1 LyricType=\"1\" LyricContent=\"(.*?)\"/>",
        Pattern.DOTALL,
    )
    private val TAG_PATTERN = Pattern.compile("^\\[(\\w+):([^]]*)]$")

    /**
     * Parses one QQ lyric lane. [type] is `qrc` when [original] is a decrypted
     * QRC body and `lrc` for the plain-LRC fallback; [translated] and
     * [romanization] accept either QRC or LRC.
     */
    fun parse(
        original: String?,
        translated: String? = null,
        romanization: String? = null,
        type: String = "qrc",
    ): LyricsResult {
        val originalLines = when (type) {
            "qrc" -> original?.takeIf(String::isNotEmpty)?.let(::parseQrc) ?: emptyList()
            "lrc" -> original?.takeIf(String::isNotEmpty)?.let(LrcParser::parseLrc) ?: emptyList()
            else -> emptyList()
        }.sortedBy(LyricsLine::start)

        val translatedRaw = parseAuxiliaryLyrics(translated)
        val romanizationRaw = parseAuxiliaryLyrics(romanization)

        return LyricsResult(
            tags = emptyMap(),
            original = originalLines,
            translated = LrcParser.lyricsMerge(originalLines, translatedRaw),
            romanization = LrcParser.lyricsMerge(originalLines, romanizationRaw, wordSeparator = " "),
        )
    }

    /**
     * QQ returns translation/romanization as either QRC XML or plain LRC.
     * Trying QRC first is necessary because QRC timestamps such as `[730,1817]`
     * are intentionally not accepted by the LRC parser.
     */
    private fun parseAuxiliaryLyrics(rawLyrics: String?): List<LyricsLine>? {
        val lyrics = rawLyrics?.takeIf(String::isNotEmpty) ?: return null
        return parseQrc(lyrics).takeIf(List<LyricsLine>::isNotEmpty)
            ?: LrcParser.parseLrc(lyrics)
    }

    private fun parseQrc(qrc: String): List<LyricsLine> {
        val result = mutableListOf<LyricsLine>()
        // Strip the XML envelope when present; a bare body is parsed as-is.
        val content = QRC_XML_PATTERN.matcher(qrc).let { matcher ->
            if (matcher.find()) matcher.group(1).orEmpty() else qrc
        }

        for (rawLine in content.lines()) {
            val line = rawLine.trim()
            if (line.isEmpty()) continue
            if (TAG_PATTERN.matcher(line).matches()) continue

            val lineMatcher = QRC_LINE_PATTERN.matcher(line)
            if (!lineMatcher.matches()) continue

            val lineStart = lineMatcher.group(1)?.toLongOrNull() ?: continue
            val lineDuration = lineMatcher.group(2)?.toLongOrNull() ?: 0L
            val lineEnd = lineStart + lineDuration
            val lineContent = lineMatcher.group(3).orEmpty()

            val words = mutableListOf<LyricsWord>()
            val wordMatcher = QRC_WORD_PATTERN.matcher(lineContent)
            var lastWordEnd = lineStart
            var currentPos = 0
            while (wordMatcher.find(currentPos)) {
                val wordText = wordMatcher.group(3).orEmpty()
                val wordStart = wordMatcher.group(1)?.toLongOrNull() ?: 0L
                val wordDuration = wordMatcher.group(2)?.toLongOrNull() ?: 0L
                val wordEnd = wordStart + wordDuration

                val preWordText = lineContent.substring(currentPos, wordMatcher.start())
                if (preWordText.isNotBlank()) {
                    words.add(LyricsWord(lastWordEnd, wordStart, preWordText.trimStart()))
                }
                words.add(LyricsWord(start = wordStart, end = wordEnd, text = wordText))
                lastWordEnd = wordEnd
                currentPos = wordMatcher.end()
            }

            val remainingText = lineContent.substring(currentPos)
            if (remainingText.isNotBlank()) {
                words.add(LyricsWord(lastWordEnd, lineEnd, remainingText.trim()))
            }
            if (words.isEmpty() && lineContent.isNotBlank()) {
                words.add(LyricsWord(lineStart, lineEnd, lineContent.trim()))
            } else if (words.isEmpty() && lineContent.isBlank()) {
                words.add(LyricsWord(lineStart, lineEnd, ""))
            }
            result.add(LyricsLine(lineStart, lineEnd, words))
        }
        return result
    }
}

/**
 * QQ's QRC envelope: hex text, 3DES/ECB with a fixed 24-byte ASCII key, then a
 * zlib stream. Ported from `QmCryptoUtils#decryptQrc`, with the JDK's
 * `DESede` replacing HLE's hand-rolled 3DES that never matched it (see the
 * `QrcParserTest` fidelity test). `android.util.Base64` is not used anywhere.
 */
object QmCrypto {
    private const val QRC_KEY_STR = "!@#)(*$%123ZXC!@!@#)(NHL"

    /** Returns the inflated lyric body, or `""` when the envelope is unusable. */
    fun decryptQrc(rawHexString: String): String = runCatching {
        val hexString = rawHexString.replace(Regex("[^0-9A-Fa-f]"), "")
        if (hexString.isEmpty()) return@runCatching ""
        val encryptedBytes = hexToBytes(hexString)
        if (encryptedBytes.isEmpty() || encryptedBytes.size % 8 != 0) return@runCatching ""

        val cipher = Cipher.getInstance("DESede/ECB/NoPadding")
        cipher.init(Cipher.DECRYPT_MODE, SecretKeySpec(QRC_KEY_STR.toByteArray(), "DESede"))
        val decrypted = cipher.doFinal(encryptedBytes)
        if (decrypted.isEmpty()) return@runCatching ""
        inflate(decrypted)
    }.getOrDefault("")

    private fun hexToBytes(value: String): ByteArray {
        val data = ByteArray(value.length / 2)
        var index = 0
        while (index + 1 < value.length) {
            val high = Character.digit(value[index], 16)
            val low = Character.digit(value[index + 1], 16)
            if (high == -1 || low == -1) {
                index += 2
                continue
            }
            data[index / 2] = ((high shl 4) + low).toByte()
            index += 2
        }
        return data
    }

    /** `Inflater(false)` = zlib-wrapped, the shape QQ emits. */
    private fun inflate(data: ByteArray): String {
        val inflater = Inflater(false)
        inflater.setInput(data)
        val output = ByteArrayOutputStream(data.size * 2)
        try {
            val buffer = ByteArray(4096)
            while (!inflater.finished()) {
                val count = inflater.inflate(buffer)
                if (count == 0) {
                    if (inflater.needsInput() || inflater.needsDictionary()) break
                }
                output.write(buffer, 0, count)
            }
        } finally {
            output.close()
            inflater.end()
        }
        return output.toString("UTF-8")
    }
}
