package dev.amenhancer.module.hook

import android.animation.ValueAnimator
import android.util.Size
import android.view.ViewGroup
import android.widget.FrameLayout
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.LooperMode

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33], manifest = Config.NONE)
@LooperMode(LooperMode.Mode.PAUSED)
class FragmentPlayerArtworkRecoveryTest {
    private lateinit var f: PlayerRecoveryFixture
    @Before fun setUp() { f = PlayerRecoveryFixture(); f.installArtwork() }
    @After fun tearDown() { f.scope.close(); f.activity.finish() }

    @Test fun staleChildAndCachedTargetAreRepairedOnceUsingTheNativeSlot() {
        f.pane.target = Size(400, 400)
        f.draw()
        assertEquals(Size(400, 400), f.pane.baseline)
        assertEquals(Size(400, 400), f.pane.lastSize)
        assertNull(f.pane.targetAtResize)
        assertEquals(400, f.image.layoutParams.width)
        assertEquals(ViewGroup.LayoutParams.MATCH_PARENT, f.cover.layoutParams.width)
        repeat(20) { f.draw() }
        assertEquals(1, f.pane.resizes)
        f.layout(480)
        f.draw()
        assertEquals(Size(480, 480), f.pane.lastSize)
        assertEquals(2, f.pane.resizes)
    }

    @Test fun settledFramesReuseResourceAndFragmentBindingsButReadLiveState() {
        f.draw()
        val identifiers = f.resources.identifiers
        val parents = f.pane.parentReads
        repeat(20) { f.draw() }
        assertEquals(identifiers, f.resources.identifiers)
        assertEquals(parents, f.pane.parentReads)
        f.main.behavior.state = 4
        val reads = f.pane.viewReads
        repeat(20) { f.draw() }
        assertEquals(reads, f.pane.viewReads)
        f.image.layoutParams.width = 328
        f.main.behavior.state = 3
        f.draw()
        assertEquals(2, f.pane.resizes)
        f.main.entering = true
        f.image.layoutParams.width = 328
        f.draw()
        assertEquals(2, f.pane.resizes)
        f.main.entering = false
        f.main.sharedElement = true
        f.draw()
        assertEquals(2, f.pane.resizes)
        f.main.sharedElement = false
        f.draw()
        assertEquals(3, f.pane.resizes)
    }

    @Test fun videoAndSizeAnimationsRestoreNativeLayoutEvenWhenSheetIsCollapsed() {
        f.draw()
        f.main.behavior.state = 4
        f.pane.videoMode = true
        f.draw()
        assertEquals(ViewGroup.LayoutParams.WRAP_CONTENT, f.cover.layoutParams.width)
        f.pane.videoMode = false
        f.main.behavior.state = 3
        f.draw()
        val animator = ValueAnimator.ofFloat(0f, 1f).apply { duration = 10000; start() }
        f.pane.sizeAnimation = animator
        f.main.behavior.state = 4
        f.draw()
        assertEquals(ViewGroup.LayoutParams.WRAP_CONTENT, f.cover.layoutParams.width)
        animator.cancel()
    }

    @Test fun repeatedResumeAndReattachKeepOneDrawObserverAndDestroyRemovesIt() {
        val reads = f.pane.viewReads
        f.draw()
        val perDraw = f.pane.viewReads - reads
        repeat(3) { f.resume() }
        val afterResume = f.pane.viewReads
        f.draw()
        assertEquals(perDraw, f.pane.viewReads - afterResume)
        val host = f.root.parent as ViewGroup
        host.removeView(f.root)
        host.addView(f.root)
        f.layout()
        val afterAttach = f.pane.viewReads
        f.draw()
        assertEquals(perDraw, f.pane.viewReads - afterAttach)
        f.destroyMain()
        val afterDestroy = f.pane.viewReads
        f.image.layoutParams.width = 328
        f.draw()
        assertEquals(afterDestroy, f.pane.viewReads)
        assertEquals(1, f.pane.resizes)
        assertEquals(ViewGroup.LayoutParams.WRAP_CONTENT, f.cover.layoutParams.width)
    }

    @Test fun replacedPaneInvalidatesCoverImageAndMainBindings() {
        f.draw()
        val previousLookups = f.resources.identifiers
        f.destroyPane()
        f.root.removeView(f.paneRoot)
        val replacementRoot = FrameLayout(f.context)
        val slot = FrameLayout(f.context).apply { id = PlayerRecoveryFixture.CONTAINER }
        val cover = FrameLayout(f.context).apply { id = PlayerRecoveryFixture.CARD }
        val image = android.widget.ImageView(f.context).apply { id = PlayerRecoveryFixture.IMAGE }
        f.root.addView(replacementRoot, FrameLayout.LayoutParams(400, 400))
        replacementRoot.addView(slot, FrameLayout.LayoutParams(400, 400))
        slot.addView(cover, FrameLayout.LayoutParams(-2, -2))
        cover.addView(image, FrameLayout.LayoutParams(280, 280))
        val pane = PlayerRecoveryFixture.Pane().apply { rootView = replacementRoot; parent = f.main }
        f.hooks.after(f.contract.paneViewCreated, pane, arrayOf(replacementRoot))
        f.layout()
        slot.layout(0, 0, 400, 400)
        f.draw()
        assertEquals(1, pane.resizes)
        assertEquals(400, image.layoutParams.width)
        assertTrue(f.resources.identifiers > previousLookups)
        assertEquals(ViewGroup.LayoutParams.WRAP_CONTENT, f.cover.layoutParams.width)
        assertEquals(1, f.pane.resizes)
    }

    @Test fun destroyedPaneIgnoresAnAlreadyCapturedAnimationCompletion() {
        f.draw()
        val animator = ValueAnimator.ofFloat(0f, 1f).apply { duration = 10000; start() }
        f.pane.sizeAnimation = animator
        f.hooks.after(f.contract.resizeArtwork, f.pane, arrayOf(false, Size(400, 400)))
        f.hooks.after(f.contract.resizeArtwork, f.pane, arrayOf(false, Size(400, 400)))
        val listeners = animator.listeners!!.toList()
        assertEquals(1, listeners.size)
        f.destroyPane()
        assertTrue(animator.listeners.isNullOrEmpty())
        f.image.layoutParams.width = 328
        listeners.forEach { it.onAnimationEnd(animator) }
        f.draw()
        assertEquals(1, f.pane.resizes)
        assertEquals(328, f.image.layoutParams.width)
        animator.cancel()
    }

    @Test fun closedScopeRestoresLayoutAndIgnoresFurtherDraws() {
        f.draw()
        f.scope.close()
        f.image.layoutParams.width = 328
        f.draw()
        assertEquals(1, f.pane.resizes)
        assertEquals(ViewGroup.LayoutParams.WRAP_CONTENT, f.cover.layoutParams.width)
    }
}
