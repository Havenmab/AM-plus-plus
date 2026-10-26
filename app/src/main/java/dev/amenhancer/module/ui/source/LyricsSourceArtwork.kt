package dev.amenhancer.module.ui.source

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.os.Handler
import android.os.Looper
import android.util.LruCache
import android.widget.ImageView
import java.net.HttpURLConnection
import java.net.URL
import java.util.WeakHashMap
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicInteger

/**
 * The song card's artwork thumbnail.
 *
 * The sheet lives in the Apple Music process, which already has network access,
 * but the module must not add an image library for one 48dp tile. This loader
 * keeps a tiny LRU of decoded thumbnails, decodes at the display size and drops
 * any result whose view was already rebound, so scrolling or a song change can
 * never paint the previous cover.
 */
internal object LyricsSourceArtwork {

    private const val TARGET_PX = 192
    private const val MAX_DOWNLOAD_BYTES = 512 * 1024
    private const val CONNECT_TIMEOUT_MS = 4_000
    private const val READ_TIMEOUT_MS = 6_000

    private val executor = Executors.newSingleThreadExecutor { runnable ->
        Thread(runnable, "ampp-lyrics-source-artwork").apply { isDaemon = true }
    }
    private val mainHandler = Handler(Looper.getMainLooper())
    private val sequence = AtomicInteger()
    private val tokens = WeakHashMap<ImageView, Int>()
    private val cache = object : LruCache<String, Bitmap>(16) {
        override fun sizeOf(key: String, value: Bitmap): Int = 1
    }

    /**
     * Loads [url] into [view]. A blank, malformed or non-HTTP url keeps the
     * placeholder the caller already installed; every failure is silent because
     * the cover is decoration, never content.
     */
    fun load(view: ImageView, url: String?) {
        val key = url?.trim().orEmpty()
        if (!key.startsWith("https://", ignoreCase = true) && !key.startsWith("http://", ignoreCase = true)) return
        cache.get(key)?.let { bitmap ->
            view.setImageBitmap(bitmap)
            return
        }
        val token = sequence.incrementAndGet()
        synchronized(tokens) { tokens[view] = token }
        executor.execute {
            val bitmap = runCatching { download(key) }.getOrNull()
            mainHandler.post {
                val current = synchronized(tokens) { tokens[view] }
                if (current != token || bitmap == null) return@post
                cache.put(key, bitmap)
                view.setImageBitmap(bitmap)
            }
        }
    }

    private fun download(url: String): Bitmap? {
        val bytes = readBytes(url) ?: return null
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
        if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return null
        var sample = 1
        var width = bounds.outWidth
        var height = bounds.outHeight
        while (width / (sample * 2) >= TARGET_PX && height / (sample * 2) >= TARGET_PX) {
            sample *= 2
            width = bounds.outWidth / sample
            height = bounds.outHeight / sample
        }
        val options = BitmapFactory.Options().apply { inSampleSize = sample }
        return runCatching { BitmapFactory.decodeByteArray(bytes, 0, bytes.size, options) }.getOrNull()
    }

    private fun readBytes(url: String): ByteArray? {
        val connection = runCatching { URL(url).openConnection() as HttpURLConnection }.getOrNull() ?: return null
        return try {
            connection.connectTimeout = CONNECT_TIMEOUT_MS
            connection.readTimeout = READ_TIMEOUT_MS
            connection.instanceFollowRedirects = true
            connection.setRequestProperty("Accept", "image/*")
            connection.requestMethod = "GET"
            if (connection.responseCode !in 200..299) return null
            val stream = connection.inputStream ?: return null
            stream.use { input ->
                val buffer = ByteArray(16 * 1024)
                val output = java.io.ByteArrayOutputStream()
                var total = 0
                while (true) {
                    val read = input.read(buffer)
                    if (read <= 0) break
                    total += read
                    if (total > MAX_DOWNLOAD_BYTES) return null
                    output.write(buffer, 0, read)
                }
                return output.toByteArray()
            }
        } catch (error: Throwable) {
            null
        } finally {
            runCatching { connection.disconnect() }
        }
    }
}
