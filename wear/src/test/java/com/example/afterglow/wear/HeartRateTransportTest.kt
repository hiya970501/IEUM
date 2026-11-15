package com.example.afterglow.wear

import org.junit.Assert.*
import org.junit.Test
import org.json.JSONObject
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import java.util.concurrent.CancellationException
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference

class HeartRateTransportTest {
    @Test fun activityReceiptUsesSameCancellationAndRequestMatchingWithoutFakePulse() {
        val now=System.currentTimeMillis()
        val record=com.example.afterglow.net.WatchActivityRecord("11111111-1111-4111-8111-111111111111",now-1000,now)
        val connection=Connection()
        HeartRateTransport(open={connection}).use { it.postActivity(target,"sample-1",record) { true } }
        val sent=JSONObject(connection.output.toString(Charsets.UTF_8.name()))
        assertEquals("wear-os-user-session",sent.getString("source"));assertFalse(sent.has("bpm"));assertFalse(sent.has("stepsSinceBaseline"))
        assertEquals(now,sent.getLong("observedAt"));assertTrue(connection.closed)
    }

    private val target = WatchDestination("https://relay.example/native", "a".repeat(32))
    private val sample = LiveHeartRate(130, 1700000000000, 1)
    private class Connection(private val receipt: String = "{\"ok\":true,\"requestId\":\"sample-1\"}",
        private val code: Int = 200) : HttpURLConnection(URL("http://127.0.0.1/")) {
        val output = ByteArrayOutputStream()
        @Volatile var closed = false
        var entered: CountDownLatch? = null
        var release: CountDownLatch? = null
        var beforeReceipt: (() -> Unit)? = null
        override fun connect() {}
        override fun usingProxy() = false
        override fun disconnect() { closed = true; release?.countDown() }
        override fun getOutputStream(): ByteArrayOutputStream {
            entered?.countDown()
            release?.let { check(it.await(3, TimeUnit.SECONDS)); if (closed) throw IOException("closed") }
            return output
        }
        override fun getResponseCode(): Int { beforeReceipt?.invoke(); return code }
        override fun getInputStream() = ByteArrayInputStream(receipt.toByteArray())
    }
    @Test fun requiresMatchingReceiptAndSendsOriginalSampleTime() {
        val connection = Connection()
        HeartRateTransport(open = { connection }).use { it.post(target, "sample-1", sample) { true } }
        val sent = JSONObject(connection.output.toString(Charsets.UTF_8.name()))
        assertEquals(130, sent.getInt("bpm")); assertEquals(sample.measuredAt, sent.getLong("measuredAt"))
        assertEquals("wear-os-sensor", sent.getString("source")); assertEquals("sample-1", sent.getString("requestId"))
        assertFalse(connection.instanceFollowRedirects); assertTrue(connection.closed)
        assertEquals(3000, connection.connectTimeout)
    }
    @Test fun wrongOrMissingReceiptDoesNotCountAsDelivery() {
        listOf("{}", "{\"ok\":false,\"requestId\":\"sample-1\"}",
            "{\"ok\":true,\"requestId\":\"old-sample\"}").forEach { body ->
            val connection = Connection(body)
            HeartRateTransport(open = { connection }).use { transport ->
                assertThrows(IllegalStateException::class.java) { transport.post(target, "sample-1", sample) { true } }
            }
            assertTrue(connection.closed)
        }
    }
    @Test fun redirectsAndErrorsNeverCountAsDelivery() {
        listOf(302, 403, 409, 500).forEach { code ->
            HeartRateTransport(open = { Connection(code = code) }).use { transport ->
                assertThrows(IllegalStateException::class.java) { transport.post(target, "sample-1", sample) { true } }
            }
        }
    }
    @Test fun revokedSessionDoesNotOpenConnection() {
        var opened = false
        HeartRateTransport(open = { opened = true; Connection() }).use { transport ->
            assertThrows(CancellationException::class.java) { transport.post(target, "sample-1", sample) { false } }
        }
        assertFalse(opened)
    }
    @Test fun stopBeforeRegistrationPreventsPayloadWrite() {
        val connection = Connection()
        lateinit var transport: HeartRateTransport
        transport = HeartRateTransport(open = { transport.cancel(); connection })
        transport.use {
            assertThrows(CancellationException::class.java) { it.post(target, "sample-1", sample) { true } }
        }
        assertEquals(0, connection.output.size()); assertTrue(connection.closed)
    }
    @Test fun revokedSessionDuringReceiptDoesNotSucceed() {
        var allowed = true
        val connection = Connection().apply { beforeReceipt = { allowed = false } }
        HeartRateTransport(open = { connection }).use { transport ->
            assertThrows(CancellationException::class.java) { transport.post(target, "sample-1", sample) { allowed } }
        }
    }
    @Test fun receiptAllocationIsBounded() {
        val connection = Connection(" ".repeat(4097))
        HeartRateTransport(open = { connection }).use { transport ->
            assertThrows(IllegalStateException::class.java) { transport.post(target, "sample-1", sample) { true } }
        }
        assertTrue(connection.closed)
    }
    @Test fun deadlineClosesBlockedRequestWithoutWaitingForReadTimeout() {
        val connection = Connection().apply { entered = CountDownLatch(1); release = CountDownLatch(1) }
        val error = AtomicReference<Throwable?>()
        HeartRateTransport(deadlineMillis = 150, open = { connection }).use { transport ->
            val worker = Thread { try { transport.post(target, "sample-1", sample) { true } } catch (failure: Throwable) { error.set(failure) } }
            worker.start(); assertTrue(connection.entered!!.await(2, TimeUnit.SECONDS))
            worker.join(2000); assertFalse(worker.isAlive); assertTrue(connection.closed); assertNotNull(error.get())
        }
    }
    @Test fun explicitStopDisconnectsBlockedConnection() {
        val connection = Connection().apply { entered = CountDownLatch(1); release = CountDownLatch(1) }
        val error = AtomicReference<Throwable?>()
        HeartRateTransport(open = { connection }).use { transport ->
            val worker = Thread { try { transport.post(target, "sample-1", sample) { true } } catch (failure: Throwable) { error.set(failure) } }
            worker.start(); assertTrue(connection.entered!!.await(2, TimeUnit.SECONDS)); transport.cancel()
            worker.join(2000); assertFalse(worker.isAlive); assertTrue(connection.closed); assertNotNull(error.get())
        }
    }
    @Test fun closedTransportAndInvalidSamplesCannotOpenConnections() {
        var opened = false
        val transport = HeartRateTransport(open = { opened = true; Connection() }); transport.close()
        assertThrows(IllegalStateException::class.java) { transport.post(target, "sample-1", sample) { true } }
        assertFalse(opened)
        HeartRateTransport(open = { opened = true; Connection() }).use {
            assertThrows(IllegalArgumentException::class.java) { it.post(target, "sample-1", sample.copy(bpm = 0)) { true } }
        }
        assertFalse(opened)
    }
}
