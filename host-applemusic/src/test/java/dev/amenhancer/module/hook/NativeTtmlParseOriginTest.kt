package dev.amenhancer.module.hook

import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The capture hook must be able to tell Apple's parses apart from the module's
 * own replacement parses; otherwise the translation pass would enrich the
 * document the module is about to install.
 */
class NativeTtmlParseOriginTest {

    @Test
    fun `a module parse is marked and unwinds to Apple origin`() {
        assertFalse(NativeTtmlParseOrigin.isModuleInitiated())

        val seen = NativeTtmlParseOrigin.moduleInitiated {
            NativeTtmlParseOrigin.isModuleInitiated()
        }

        assertTrue(seen)
        assertFalse(NativeTtmlParseOrigin.isModuleInitiated())
    }

    @Test
    fun `nested module parses stay marked until the outermost returns`() {
        var inner = false

        NativeTtmlParseOrigin.moduleInitiated {
            NativeTtmlParseOrigin.moduleInitiated {
                inner = NativeTtmlParseOrigin.isModuleInitiated()
            }
            assertTrue(NativeTtmlParseOrigin.isModuleInitiated())
        }

        assertTrue(inner)
        assertFalse(NativeTtmlParseOrigin.isModuleInitiated())
    }

    @Test
    fun `a failing module parse still unwinds the marker`() {
        runCatching {
            NativeTtmlParseOrigin.moduleInitiated<Unit> {
                throw IllegalStateException("native parse failed")
            }
        }

        assertFalse(NativeTtmlParseOrigin.isModuleInitiated())
    }

    @Test
    fun `another thread keeps an Apple-origin parse`() {
        val appleOrigin = AtomicBoolean(true)
        val started = CountDownLatch(1)
        val released = CountDownLatch(1)
        val worker = Thread {
            started.countDown()
            released.await(5, TimeUnit.SECONDS)
            appleOrigin.set(NativeTtmlParseOrigin.isModuleInitiated())
        }
        worker.start()
        assertTrue(started.await(5, TimeUnit.SECONDS))

        NativeTtmlParseOrigin.moduleInitiated {
            assertTrue(NativeTtmlParseOrigin.isModuleInitiated())
        }
        released.countDown()
        worker.join(5_000)

        assertFalse(appleOrigin.get())
    }
}
