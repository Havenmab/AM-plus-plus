package dev.amenhancer.lyricsplayer

import android.graphics.Typeface
import android.os.Build
import android.util.Log
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.text.font.FontFamily

/**
 * The four text roles of the page, derived from one user-imported lyric font.
 *
 * The module never bundles Apple fonts: the host opens the user's imported face (settings
 * keys lyrics_font_*, through TargetConfigClient.openFileDescriptor) and hands the base
 * Typeface to the page; the page synthesises Regular/Medium/Semibold/Bold from it. When no
 * font is imported everything falls back to the system family.
 */
internal class LyricsPlayerFonts(
    val regular: FontFamily,
    val medium: FontFamily,
    val semibold: FontFamily,
    val bold: FontFamily,
) {
    companion object {
        val SYSTEM = LyricsPlayerFonts(
            regular = FontFamily.Default,
            medium = FontFamily.Default,
            semibold = FontFamily.Default,
            bold = FontFamily.Default,
        )
    }
}

internal object ImportedLyricsFonts {
    private const val TAG = "lyrics player"

    @Volatile
    private var cached: LyricsPlayerFonts? = null

    /**
     * Builds the roles from [base] (the imported face). RE: the current line is the bold role
     * (reference uses Hiragino W7), neighbours are regular (W5); a single imported face gets
     * its weights synthesised with Typeface.create(base, weight, false) on API 28+.
     */
    fun from(base: Typeface?): LyricsPlayerFonts {
        cached?.let { return it }
        if (base == null) {
            Log.i(TAG, "lyrics player font: system (no-import)")
            return LyricsPlayerFonts.SYSTEM
        }
        return synchronized(this) {
            cached ?: runCatching { build(base) }.getOrElse { error ->
                Log.i(TAG, "lyrics player font: system (parse-failed: " + error.javaClass.simpleName + ")")
                LyricsPlayerFonts.SYSTEM
            }.also { cached = it }
        }
    }

    private fun build(base: Typeface): LyricsPlayerFonts {
        fun weight(targetWeight: Int): Typeface = runCatching {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                Typeface.create(base, targetWeight, false)
            } else if (targetWeight >= 600) {
                Typeface.create(base, Typeface.BOLD)
            } else {
                base
            }
        }.getOrDefault(base)
        val loaded = LyricsPlayerFonts(
            regular = FontFamily(weight(400)),
            medium = FontFamily(weight(500)),
            semibold = FontFamily(weight(600)),
            bold = FontFamily(weight(700)),
        )
        Log.i(TAG, "lyrics player font: imported (weights synthesized)")
        return loaded
    }
}

/**
 * Loads the imported face from the host port (cheap: the host caches the Typeface). Until it
 * resolves the page uses the system font; the state flip triggers one recomposition and one
 * re-measure, never more.
 */
@Composable
internal fun rememberLyricsPlayerFonts(port: LyricsPlayerPort): LyricsPlayerFonts {
    var fonts by remember { mutableStateOf(LyricsPlayerFonts.SYSTEM) }
    LaunchedEffect(port) {
        val base = runCatching { port.importedTypeface() }.getOrNull()
        fonts = ImportedLyricsFonts.from(base)
    }
    return fonts
}
