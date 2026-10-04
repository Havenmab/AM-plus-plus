package dev.amenhancer.module.lyrics.source

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * Pins the shared transport identity: providers that do not ask for an
 * override keep the AM++ headers byte-for-byte, and a per-request override
 * applies only to that request.
 */
class HttpLyricTransportTest {

    @Test
    fun `the shared default headers are unchanged`() {
        val headers = HttpLyricTransport().effectiveRequestHeaders()

        assertEquals("AMPlusPlus/1.2.1", headers["User-Agent"])
        assertEquals("text/plain, application/json;q=0.9, */*;q=0.5", headers["Accept"])
        assertNull(headers["Accept-Encoding"])
    }

    @Test
    fun `per-request overrides win for one request only`() {
        val transport = HttpLyricTransport()

        val overridden = transport.effectiveRequestHeaders(
            mapOf("User-Agent" to "okhttp/3.10.0", "Accept-Encoding" to "identity"),
        )

        assertEquals("okhttp/3.10.0", overridden["User-Agent"])
        assertEquals("identity", overridden["Accept-Encoding"])
        assertEquals("AMPlusPlus/1.2.1", transport.effectiveRequestHeaders()["User-Agent"])
    }
}
