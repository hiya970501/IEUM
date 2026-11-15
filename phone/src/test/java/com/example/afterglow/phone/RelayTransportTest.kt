package com.example.afterglow.phone

import org.junit.Assert.*
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference
import kotlinx.coroutines.CancellationException

class RelayTransportTest {
    @Test fun standalonePulseUsesFixedHealthConnectRouteAndMatchingReceipt() {
        val connection=Connection()
        var selected=""
        RelayTransport { _,path-> selected=path;connection }.post(target,"request-1","{}","/health-heart-rate") { true }
        assertEquals("/health-heart-rate",selected)
        assertTrue(connection.closed)
        assertThrows(IllegalArgumentException::class.java) {
            RelayTransport { _,_-> Connection() }.post(target,"request-1","{}","/heart-rate") { true }
        }
    }
    @Test fun expiredDeadlineRejectsLateMatchingReceiptEvenWhenSocketReturnsData() {
        val connection=Connection().apply { afterResponse={ Thread.sleep(150) } }
        assertThrows(CancellationException::class.java) {
            RelayTransport(deadlineMillis=30,open={_,_->connection}).post(target,"request-1","{}") { true }
        }
        assertTrue(connection.closed)
    }
    @Test fun pairingUsesCodeBodyAndAcceptsOnlyBoundedValidApproval() {
        val endpoint=com.example.afterglow.net.SecureRelayEndpoint("https://relay.example/native")
        val connection=Connection("{\"ok\":true,\"token\":\"${"b".repeat(32)}\"}")
        var path=""
        val result=RelayTransport { _, operation -> path=operation;connection }.pair(endpoint,"123456") { true }
        assertEquals("/pair-watch",path)
        assertEquals(endpoint.address,result.endpoint.address)
        assertEquals("{\"code\":\"123456\"}",connection.output.toString())
        assertFalse(connection.instanceFollowRedirects)
        assertTrue(connection.closed)
    }
    @Test fun invalidOrCancelledPairingNeverWritesAndRedirectsAreRejected() {
        val endpoint=com.example.afterglow.net.SecureRelayEndpoint("https://relay.example/native")
        var opened=false
        val transport=RelayTransport { _, _ -> opened=true;Connection() }
        assertThrows(IllegalArgumentException::class.java) { transport.pair(endpoint,"123") { true } }
        assertFalse(opened)
        assertThrows(CancellationException::class.java) { transport.pair(endpoint,"123456") { false } }
        assertFalse(opened)
        val redirect=Connection(code=302)
        assertThrows(IllegalStateException::class.java) { RelayTransport { _, _ -> redirect }.pair(endpoint,"123456") { true } }
        assertTrue(redirect.closed)
    }
    private val target = RelayDestination("https://relay.example/ingest/native", "a".repeat(32))
    private class Connection(private val body: String = "{\"ok\":true,\"requestId\":\"request-1\"}",
        private val code: Int = 200) : HttpURLConnection(URL("http://127.0.0.1/")) {
        val output = ByteArrayOutputStream()
        @Volatile var closed = false
        var entered: CountDownLatch? = null
        var released: CountDownLatch? = null
        var afterResponse: (() -> Unit)? = null
        override fun connect() {}
        override fun usingProxy() = false
        override fun disconnect() { closed = true; released?.countDown() }
        override fun getOutputStream(): ByteArrayOutputStream {
            entered?.countDown()
            released?.let { check(it.await(2, TimeUnit.SECONDS)); if (closed) throw IOException("closed") }
            return output
        }
        override fun getInputStream() = ByteArrayInputStream(body.toByteArray())
        override fun getResponseCode(): Int { afterResponse?.invoke(); return code }
    }
    @Test fun acceptsOnlyMatchingSuccessfulReceipt() {
        val connection = Connection()
        RelayTransport { _, _ -> connection }.post(target, "request-1", "{}") { true }
        assertEquals("{}", connection.output.toString())
        assertTrue(connection.closed)
        assertFalse(connection.instanceFollowRedirects)
        assertEquals(3500, connection.connectTimeout)
    }
    @Test fun connectionHeartbeatUsesSeparateEndpoint() {
        val connection = Connection()
        var actualPath = ""
        RelayTransport { _, path -> actualPath = path; connection }
            .post(target, "request-1", "{}", path = "/connect") { true }
        assertEquals("/connect", actualPath)
    }
    @Test fun arbitraryPathsNeverOpenAConnection() {
        var opened = false
        assertThrows(IllegalArgumentException::class.java) {
            RelayTransport { _, _ -> opened = true; Connection() }
                .post(target, "request-1", "{}", path = "/other") { true }
        }
        assertFalse(opened)
    }
    @Test fun status200WithWrongReceiptIsNotSuccess() {
        listOf("{\"ok\":false,\"requestId\":\"request-1\"}",
            "{\"ok\":true,\"requestId\":\"older-request\"}", "{}").forEach { body ->
            val connection = Connection(body)
            assertThrows(IllegalStateException::class.java) {
                RelayTransport { _, _ -> connection }.post(target, "request-1", "{}") { true }
            }
            assertTrue(connection.closed)
        }
    }
    @Test fun rejectsRedirectOrErrorResponse() {
        listOf(302, 403, 409, 500).forEach { code ->
            val connection = Connection(code = code)
            assertThrows(IllegalStateException::class.java) {
                RelayTransport { _, _ -> connection }.post(target, "request-1", "{}") { true }
            }
            assertTrue(connection.closed)
        }
    }
    @Test fun capsReceiptAndRequestAllocation() {
        val connection = Connection(" ".repeat(4097))
        assertThrows(IllegalStateException::class.java) {
            RelayTransport { _, _ -> connection }.post(target, "request-1", "{}") { true }
        }
        val oversized = Connection()
        assertThrows(IllegalArgumentException::class.java) {
            RelayTransport { _, _ -> oversized }.post(target, "request-1", " ".repeat(8193)) { true }
        }
        assertEquals(0, oversized.output.size()); assertTrue(oversized.closed)
    }
    @Test fun withdrawnConsentPreventsOpeningSocket() {
        var opened = false
        assertThrows(CancellationException::class.java) {
            RelayTransport { _, _ -> opened = true; Connection() }.post(target, "request-1", "{}") { false }
        }
        assertFalse(opened)
    }
    @Test fun cancelBeforeRegistrationPreventsAnyPayloadWrite() {
        val connection = Connection()
        lateinit var transport: RelayTransport
        transport = RelayTransport { _, _ -> transport.cancel(); connection }
        assertThrows(CancellationException::class.java) { transport.post(target, "request-1", "{}") { true } }
        assertEquals(0, connection.output.size()); assertTrue(connection.closed)
    }
    @Test fun cancelDisconnectsBlockedRequestAndDoesNotPoisonNextSession() {
        val connection = Connection().apply { entered = CountDownLatch(1); released = CountDownLatch(1) }
        val fresh = Connection()
        var first = true
        val transport = RelayTransport { _, _ -> if (first) { first = false; connection } else fresh }
        val result = AtomicReference<Throwable?>()
        val worker = Thread {
            try { transport.post(target, "request-1", "{}") { true } }
            catch (error: Throwable) { result.set(error) }
        }
        worker.start()
        try {
            assertTrue(connection.entered!!.await(2, TimeUnit.SECONDS))
            transport.cancel(); worker.join(2000)
            assertFalse(worker.isAlive); assertNotNull(result.get())
            assertEquals(0, connection.output.size())
            transport.post(target, "request-1", "{}") { true }
            assertEquals("{}", fresh.output.toString())
        } finally { transport.cancel(); worker.join(2000) }
    }
    @Test fun delayedAckAfterRevocationCannotBeReportedAsSuccess() {
        val connection = Connection()
        var allowed = true
        connection.afterResponse = { allowed = false }
        val transport = RelayTransport { _, _ -> connection }
        assertThrows(CancellationException::class.java) {
            transport.post(target, "request-1", "{}") { allowed }
        }
        assertTrue(connection.closed)
    }
}
