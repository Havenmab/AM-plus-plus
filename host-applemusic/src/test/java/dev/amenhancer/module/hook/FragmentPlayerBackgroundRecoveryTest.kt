package dev.amenhancer.module.hook

import android.animation.Animator
import android.animation.AnimatorListenerAdapter
import android.graphics.Bitmap
import android.graphics.BitmapShader
import android.graphics.Shader
import android.graphics.drawable.BitmapDrawable
import android.os.Looper
import android.view.ViewGroup
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.LooperMode

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33], manifest = Config.NONE)
@LooperMode(LooperMode.Mode.PAUSED)
class FragmentPlayerBackgroundRecoveryTest {
    private lateinit var f: PlayerRecoveryFixture
    @Before fun setUp() { f = PlayerRecoveryFixture(); f.installBackground() }
    @After fun tearDown() { f.scope.close(); f.activity.finish() }
    private fun bitmap(size: Int = 8) = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888)
    private fun drain() { shadowOf(Looper.getMainLooper()).idle() }
    private fun resume() { f.resume(); drain() }
    private fun dirtyCaches() {
        f.background.derived = bitmap().apply { recycle() }
        val shader = BitmapShader(bitmap(), Shader.TileMode.CLAMP, Shader.TileMode.CLAMP)
        f.background.shader = shader
        f.background.previousShader = shader
        f.background.paint.shader = shader
        f.background.previousPaint.shader = shader
    }

    @Test fun recoveryCancelsFadeThenClearsEveryCacheBeforeApplyingQueuedArtwork() {
        val current = bitmap()
        val queued = bitmap()
        f.background.source = current
        f.background.queued = queued
        dirtyCaches()
        f.background.fade.addListener(object : AnimatorListenerAdapter() {
            override fun onAnimationCancel(animation: Animator) {
                f.background.events += "cancel"
                assertNotNull(f.background.shader)
                assertNotNull(f.background.paint.shader)
            }
        })
        f.background.fade.duration = 10000
        f.background.fade.start()
        resume()
        assertEquals(listOf("cancel", "artwork"), f.background.events)
        assertSame(queued, f.background.restored)
        resume()
        assertEquals(1, f.background.restores)
    }

    @Test fun recycledQueuedBitmapFallsBackToCurrentBeforeForeground() {
        val current = bitmap()
        f.background.source = current
        f.background.queued = bitmap().apply { recycle() }
        f.image.setImageDrawable(BitmapDrawable(f.resources, bitmap()))
        dirtyCaches()
        resume()
        assertSame(current, f.background.restored)
    }

    @Test fun recycledSourceAndPlaceholderQueueFallBackToForeground() {
        f.background.source = bitmap().apply { recycle() }
        f.background.queued = bitmap(1)
        val foreground = bitmap()
        f.image.setImageDrawable(BitmapDrawable(f.resources, foreground))
        resume()
        assertSame(foreground, f.background.restored)
    }

    @Test fun invalidArtworkKeepsPendingRecoveryUntilAUsableCoverArrives() {
        f.background.source = bitmap().apply { recycle() }
        f.background.queued = bitmap(1)
        f.image.setImageDrawable(BitmapDrawable(f.resources, bitmap().apply { recycle() }))
        f.detachBackground()
        f.attachBackground()
        drain()
        assertEquals(0, f.background.restores)
        val fresh = bitmap()
        f.image.setImageDrawable(BitmapDrawable(f.resources, fresh))
        f.hooks.after(android.widget.ImageView::class.java.getDeclaredMethod("setImageDrawable",
            android.graphics.drawable.Drawable::class.java), f.image)
        drain()
        assertSame(fresh, f.background.restored)
    }

    @Test fun repeatedAttachAndResumeCallbacksConsumePendingRecoveryOnlyOnce() {
        f.background.source = bitmap()
        f.detachBackground()
        repeat(4) { f.attachBackground(); f.resume() }
        drain()
        assertEquals(1, f.background.restores)
        f.attachBackground()
        drain()
        assertEquals(1, f.background.restores)
    }

    @Test fun failedArtworkApplicationLeavesPendingRecoveryForTheNextCallback() {
        f.background.source = bitmap()
        f.detachBackground()
        f.background.failArtwork = true
        resume()
        assertEquals(1, f.background.restores)
        assertNull(f.background.restored)
        f.background.failArtwork = false
        resume()
        assertEquals(2, f.background.restores)
        assertNotNull(f.background.restored)
        resume()
        assertEquals(2, f.background.restores)
    }

    @Test fun destroyedViewIgnoresAlreadyPostedRecovery() {
        f.background.source = bitmap()
        f.detachBackground()
        f.resume()
        (f.root.parent as ViewGroup).removeView(f.root)
        drain()
        assertEquals(0, f.background.restores)
    }

    @Test fun scopeClosureIgnoresAlreadyPostedRecovery() {
        f.background.source = bitmap()
        f.detachBackground()
        f.attachBackground()
        f.scope.close()
        drain()
        assertEquals(0, f.background.restores)
    }

    @Test fun zeroSizedBackgroundDefersRecoveryUntilLayout() {
        f.background.source = bitmap()
        f.detachBackground()
        f.background.layoutParams = f.background.layoutParams.apply { width = 0; height = 0 }
        f.background.layout(0, 0, 0, 0)
        resume()
        assertEquals(0, f.background.restores)
        f.background.layoutParams = f.background.layoutParams.apply { width = 400; height = 400 }
        f.layout()
        resume()
        assertEquals(1, f.background.restores)
    }
}
