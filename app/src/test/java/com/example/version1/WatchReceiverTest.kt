package com.example.version1

import org.junit.Assert.*
import org.junit.Test
import java.net.Socket
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.DataInputStream
import java.util.concurrent.atomic.AtomicReference
import org.json.JSONObject
import java.time.Instant
import java.time.ZoneOffset
import java.net.InetSocketAddress
import java.net.SocketException
import java.net.SocketTimeoutException
import java.io.InputStream
import java.util.Collections
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicBoolean

class WatchReceiverTest {
    @Test fun phoneWorkoutQueryStatusNeverPretendsAnEmptyCompletedHistoryOrHealthSample() = withBridge {bridge->
        for(status in listOf("ready","permission_required","query_failed")) {
            val body=JSONObject().put("source","health-connect").put("requestId","owned-status").put("workoutSyncStatus",status)
            assertTrue(request(bridge,"POST","/connect",body.toString()).startsWith("HTTP/1.1 200"))
            assertEquals(status,bridge.diagnostics.workoutSyncStatus)
            assertNull(bridge.diagnostics.historyCount);assertFalse(bridge.connection!!.hasHealthData)
        }
        for(body in listOf(JSONObject().put("source","wear-os-sensor").put("workoutSyncStatus","ready"),
            JSONObject().put("source","health-connect").put("workoutSyncStatus","invented")))
            assertTrue(request(bridge,"POST","/connect",body.toString()).startsWith("HTTP/1.1 400"))
    }
    @Test fun historicalWorkoutListDoesNotBecomeAFreshWorkoutOrHeartRate() = withBridge { bridge ->
        var received: com.example.afterglow.net.WorkoutHistorySnapshot?=null
        var autoWorkout=false
        bridge.onWorkoutHistory={history,_->received=history};bridge.onWorkout={_,_->autoWorkout=true}
        val now=System.currentTimeMillis()
        val record=com.example.afterglow.net.WorkoutHistoryEntry(999,"other",now-23*3_600_000L-60_000,now-23*3_600_000L,"com.example.health",true)
        val history=com.example.afterglow.net.WorkoutHistorySnapshot(now,1,listOf(record))
        assertTrue(request(bridge,"POST","/workout-history",history.json("history-owned").toString()).startsWith("HTTP/1.1 200"))
        assertEquals(listOf(record),received!!.entries);assertFalse(autoWorkout)
        assertNull(bridge.diagnostics.latestWorkout);assertNull(bridge.diagnostics.lastHeartRateMeasuredAtEpochMillis)
        assertEquals(1,bridge.diagnostics.historyCount)
    }
    @Test fun completeEmptyHistoryIsDeliveredAndPartialScanIsRejected() = withBridge { bridge ->
        var count: Int?=null;bridge.onWorkoutHistory={history,_->count=history.entries.size}
        val now=System.currentTimeMillis()
        val empty=com.example.afterglow.net.WorkoutHistorySnapshot(now,0,emptyList()).json("empty-history")
        assertTrue(request(bridge,"POST","/workout-history",empty.toString()).startsWith("HTTP/1.1 200"))
        assertEquals(0,count);assertFalse(bridge.connection!!.hasHealthData)
        assertTrue(request(bridge,"POST","/workout-history",empty.put("complete",false).toString()).startsWith("HTTP/1.1 400"))
        assertEquals(0,bridge.diagnostics.historyCount)
    }

    @Test fun watchWalkingReceiptKeepsRealCounterProvenanceWithoutInventingHeartOrWorkout() = withBridge { bridge ->
        val now=System.currentTimeMillis()
        val record=com.example.afterglow.net.WatchActivityRecord("11111111-1111-4111-8111-111111111111",now-10_000,now-10,
            now-20,8,now-9000,now-100)
        val reply=request(bridge,"POST","/watch-activity",record.json("walking-1").toString())
        assertTrue(reply.startsWith("HTTP/1.1 200"));assertTrue(reply.contains("walking-1"))
        assertEquals(record,bridge.diagnostics.latestWatchActivity)
        assertTrue(bridge.diagnostics.watchActivityReceivedAt>=now)
        assertNull(bridge.diagnostics.latestWorkout);assertNull(bridge.diagnostics.lastHeartRateMeasuredAtEpochMillis)
        assertTrue(bridge.connection!!.hasHealthData)
        assertTrue(request(bridge,"POST","/watch-activity",record.json("replayed").toString()).startsWith("HTTP/1.1 400"))
        bridge.stop();assertNull(bridge.diagnostics.latestWatchActivity)
    }
    @Test fun malformedOrFutureWatchStepsDoNotPublishPartialEvidence() = withBridge { bridge ->
        val now=System.currentTimeMillis()
        val record=com.example.afterglow.net.WatchActivityRecord("11111111-1111-4111-8111-111111111111",now-10_000,now-1)
        for(body in listOf(record.json("bad").put("stepsSinceBaseline",3),record.json("future").put("observedAt",now+60_000),
            record.json("coerced").put("startedAt",(now-10_000).toString()))) {
            assertTrue(request(bridge,"POST","/watch-activity",body.toString()).startsWith("HTTP/1.1 400"))
        }
        assertNull(bridge.connection);assertNull(bridge.diagnostics.latestWatchActivity)
    }

    @Test fun standaloneHealthConnectPulseKeepsProvenanceTimeAndDoesNotInventWorkout() = withBridge { bridge ->
        val received=AtomicReference<Pair<Int,Long>>()
        bridge.onPhoneReading={bpm,at,_-> received.set(bpm to at)}
        val measured=System.currentTimeMillis()-90_000
        val body=JSONObject().put("source","health-connect").put("bpm",88).put("measuredAt",measured).put("requestId","hc-pulse-1")
        val reply=request(bridge,"POST","/health-heart-rate",body.toString())
        assertTrue(reply.startsWith("HTTP/1.1 200"))
        assertEquals(88 to measured,received.get())
        assertNull(bridge.diagnostics.latestWorkout)
        assertEquals("health-connect",bridge.diagnostics.lastHeartRateSource)
        assertEquals(measured,bridge.diagnostics.lastHeartRateMeasuredAtEpochMillis)
        assertFalse(bridge.diagnostics.hasFreshHeartRate(System.currentTimeMillis()))
        assertTrue(bridge.connection!!.hasHealthData)
        body.put("measuredAt",measured-1000).put("requestId","hc-old")
        assertTrue(request(bridge,"POST","/health-heart-rate",body.toString()).startsWith("HTTP/1.1 409"))
        assertEquals(88 to measured,received.get())
    }
    @Test fun standalonePhonePulseRejectsStaleForgedAndFractionalRecords() = withBridge { bridge ->
        val now=System.currentTimeMillis()
        val body=JSONObject().put("source","health-connect").put("bpm",90).put("measuredAt",now-601_000).put("requestId","hc-invalid")
        assertTrue(request(bridge,"POST","/health-heart-rate",body.toString()).startsWith("HTTP/1.1 400"))
        body.put("measuredAt",now).put("source","wear-os-sensor")
        assertTrue(request(bridge,"POST","/health-heart-rate",body.toString()).startsWith("HTTP/1.1 400"))
        body.put("source","health-connect").put("bpm",90.5)
        assertTrue(request(bridge,"POST","/health-heart-rate",body.toString()).startsWith("HTTP/1.1 400"))
        assertNull(bridge.connection);assertNull(bridge.diagnostics.latestWorkout)
    }
    @Test fun deepAuthenticatedInputIsRejectedAndReceiverRemainsAvailable() = withBridge { bridge ->
        val deep="{\"x\":".repeat(1000)+"1"+"}".repeat(1000)
        assertTrue(request(bridge,"POST","/connect",deep).startsWith("HTTP/1.1 400"))
        assertTrue(request(bridge,"GET","/pair",auth=false).startsWith("HTTP/1.1 200"))
    }
    @Test fun cleartextReceiverBindsOnlyToDeviceLoopback() = withBridge { bridge ->
        val field = WatchReceiver::class.java.getDeclaredField("server").apply { isAccessible = true }
        val socket = field.get(bridge) as java.net.ServerSocket
        assertTrue(socket.inetAddress.isLoopbackAddress)
        assertFalse(socket.inetAddress.isAnyLocalAddress)
    }
    @Test fun dockBackpressureIsUnavailableRatherThanAFalseQueuedReceipt() = withBridge { bridge ->
        bridge.onZeroTap = { throw DockReceiverBusy() }
        val json = JSONObject().put("phoneId", "e3d5fb3a-e350-4b93-a2ba-6c802e867849")
            .put("eventId", "8a289849-51d9-4e4b-b5c8-5f32bd22d4c2").put("credential", "a".repeat(64))
            .put("docked", true).put("occurredAt", Instant.now().toString())
        val reply = request(bridge, "POST", "/zero-tap", json.toString())
        assertTrue(reply.startsWith("HTTP/1.1 503")); assertFalse(reply.contains("\"queued\":true"))
        assertNull(bridge.connection)
    }
    @Test fun backgroundDockListenerRejectsEveryHealthAndPairingPathEvenWithAValidBearer() {
        var delivered = false
        val bridge = WatchReceiver(port = 0, dockOnly = true) { delivered = true }
        bridge.onWorkout = { _, _ -> delivered = true }
        bridge.onDriverIdentity = { delivered = true }
        bridge.start()
        try {
            for (path in listOf("/heart-rate", "/health-heart-rate", "/shortcut-heart-rate", "/workout", "/import", "/connect", "/pair-watch", "/watch-activity", "/workout-history")) {
                assertTrue(path, request(bridge, "POST", path).startsWith("HTTP/1.1 404"))
            }
            for (path in listOf("/", "/pair", "/companion.apk")) {
                assertTrue(path, request(bridge, "GET", path).startsWith("HTTP/1.1 404"))
            }
            assertTrue(request(bridge, "GET", "/status", auth = false).startsWith("HTTP/1.1 403"))
            val status = request(bridge, "GET", "/status")
            assertTrue(status.contains("\"dockOnly\":true")); assertFalse(status.contains("latestHeartRate"))
            assertFalse(delivered); assertNull(bridge.connection)
        } finally { bridge.stop(); assertTrue(bridge.awaitTransportStopped(3000)) }
    }
    @Test fun dockReceiptIsQueuedNeverAppliedOrHealthEvidence() = withBridge { bridge ->
        val received = AtomicReference<ZeroTapEvent>()
        bridge.onZeroTap = { received.set(it) }
        val json = JSONObject().put("phoneId", "e3d5fb3a-e350-4b93-a2ba-6c802e867849")
            .put("eventId", "8a289849-51d9-4e4b-b5c8-5f32bd22d4c2").put("credential", "a".repeat(64))
            .put("docked", true).put("occurredAt", Instant.now().toString())
        val response = request(bridge, "POST", "/zero-tap", json.toString())
        assertTrue(response.startsWith("HTTP/1.1 202"))
        assertTrue(response.contains("\"applied\":false"))
        assertNotNull(received.get())
        assertNull(bridge.connection)
        assertEquals(0L, bridge.diagnostics.lastShortcutHealthAtEpochMillis)
        received.set(null)
        assertTrue(request(bridge, "POST", "/zero-tap", json.toString(), auth = false).startsWith("HTTP/1.1 403"))
        assertNull(received.get())
        assertTrue(request(bridge, "POST", "/zero-tap", json.toString(), browser = true).startsWith("HTTP/1.1 400"))
        assertNull(received.get())
    }
    private fun withBridge(test: (WatchReceiver) -> Unit) {
        val bridge = WatchReceiver(port = 0) { }
        bridge.start()
        try { test(bridge) } finally {
            bridge.stop()
            assertTrue("Receiver transport threads must be reclaimed", bridge.awaitTransportStopped(3000))
        }
    }

    private fun request(bridge: WatchReceiver, method: String, path: String,
        body: String = "{}", auth: Boolean = true, browser: Boolean = false): String =
        Socket("127.0.0.1", bridge.boundPort).use { socket ->
            socket.soTimeout = 4000
            val bytes = body.toByteArray()
            val host = "127.0.0.1:${bridge.boundPort}"
            val headers = "$method $path HTTP/1.1\r\nHost: $host\r\n" +
                (if (auth) "Authorization: Bearer ${bridge.token}\r\n" else "") +
                (if (browser) "Origin: http://$host\r\n" else "") +
                "Content-Type: application/json\r\nContent-Length: ${bytes.size}\r\n\r\n"
            socket.getOutputStream().apply { write(headers.toByteArray()); write(bytes); flush() }
            readResponse(socket)
        }

    private fun readResponse(socket: Socket): String {
        // Read the HTTP-framed body. Early rejection may close with unread request
        // data and reset TCP on Windows after the complete response was delivered.
        val input = socket.getInputStream()
        val headerBytes = ByteArrayOutputStream()
        var delimiter = 0
        while (delimiter != 4) {
            val next = input.read()
            check(next >= 0 && headerBytes.size() < 8192)
            headerBytes.write(next)
            delimiter = when {
                next == (if (delimiter % 2 == 0) 13 else 10) -> delimiter + 1
                next == 13 -> 1
                else -> 0
            }
        }
        val header = headerBytes.toString(Charsets.US_ASCII)
        val length = Regex("(?im)^Content-Length: (\\d+)").find(header)!!.groupValues[1].toInt()
        check(length in 0..1_048_576)
        val payload = ByteArray(length)
        DataInputStream(input).readFully(payload)
        return header + payload.toString(Charsets.UTF_8)
    }

    private fun waitUntil(label: String, timeoutMillis: Long = 2500, condition: () -> Boolean) {
        val deadline = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(timeoutMillis)
        while (!condition() && System.nanoTime() < deadline) Thread.sleep(5)
        assertTrue(label, condition())
    }

    private fun pendingHeader(bridge: WatchReceiver): Socket = Socket("127.0.0.1", bridge.boundPort).apply {
        soTimeout = 2000
        getOutputStream().apply { write("GET /status HTTP/1.1\r\n".toByteArray()); flush() }
    }

    private fun pendingWorkoutBody(bridge: WatchReceiver): Socket = Socket("127.0.0.1", bridge.boundPort).apply {
        soTimeout = 2000
        getOutputStream().apply {
            write(("POST /workout HTTP/1.1\r\nHost: localhost\r\n" +
                "Authorization: Bearer ${bridge.token}\r\nContent-Length: 2\r\n\r\n{").toByteArray())
            flush()
        }
    }

    private fun assertClosed(socket: Socket) {
        try { assertEquals(-1, socket.getInputStream().read()) }
        catch (_: SocketException) { /* Windows can report reset instead of EOF. */ }
        catch (error: SocketTimeoutException) { throw AssertionError("Expected closed transport", error) }
    }

    @Test fun landingPageDoesNotContainPairingSecret() = withBridge { bridge ->
        val response = request(bridge, "GET", "/pair", auth = false)
        assertTrue(response.startsWith("HTTP/1.1 200"))
        assertTrue(response.contains("Health Connect"))
        assertFalse(response.contains(bridge.token))
        assertFalse(response.contains("#token="))
        assertFalse(response.contains("location.hash"))
        assertFalse(response.contains("afterglow://"))
    }

    @Test fun companionDownloadStreamsExactBytesWithoutAnAvailableLength() = withBridge { bridge ->
        val payload = ByteArray(100_001) { (it % 251).toByte() }
        bridge.companionApkProvider = {
            object : ByteArrayInputStream(payload) { override fun available() = 0 }
        }
        Socket("127.0.0.1", bridge.boundPort).use { socket ->
            socket.soTimeout = 4000
            socket.getOutputStream().apply {
                write("GET /companion.apk HTTP/1.1\r\nHost: localhost\r\n\r\n".toByteArray()); flush()
            }
            val response = socket.getInputStream().readBytes()
            val separator = "\r\n\r\n".toByteArray()
            val bodyStart = (0..response.size - separator.size).first { offset ->
                separator.indices.all { response[offset + it] == separator[it] }
            } + separator.size
            val header = response.copyOfRange(0, bodyStart).toString(Charsets.US_ASCII)
            assertTrue(header.startsWith("HTTP/1.1 200"))
            assertTrue(header.contains("Connection: close"))
            assertTrue(header.contains("application/vnd.android.package-archive"))
            assertFalse(header.contains("Content-Length:"))
            assertArrayEquals(payload, response.copyOfRange(bodyStart, response.size))
        }
    }

    @Test fun unauthenticatedHealthDataIsRejected() = withBridge { bridge ->
        assertTrue(request(bridge, "POST", "/heart-rate", auth = false).startsWith("HTTP/1.1 403"))
        assertNull(bridge.connection)
    }

    @Test fun browserCannotClaimNativeHealthOrigin() = withBridge { bridge ->
        assertTrue(request(bridge, "POST", "/workout", browser = true).startsWith("HTTP/1.1 403"))
        assertNull(bridge.connection)
    }

    @Test fun browserApprovalDoesNotClaimHealthSensorConnection() = withBridge { bridge ->
        assertTrue(request(bridge, "POST", "/connect", browser = true).startsWith("HTTP/1.1 200"))
        assertFalse(bridge.connection!!.hasHealthData)
    }

    @Test fun browserImportAlwaysRemainsManual() = withBridge { bridge ->
        val record = AtomicReference<RecentWorkout>()
        bridge.onWorkout = { value, _ -> record.set(value) }
        val now = System.currentTimeMillis()
        val body = """{"kind":"run","source":"health-connect","endedAt":${now - 1000},"durationMinutes":35,"bpm":120}"""
        assertTrue(request(bridge, "POST", "/import", body, browser = true).startsWith("HTTP/1.1 200"))
        assertEquals(WorkoutRecordSource.MANUAL_IMPORT, record.get().source)
        assertFalse(record.get().canAutoSelect(now))
        assertEquals(HeartRateSource.MANUAL, record.get().heartRate.source)
    }

    @Test fun nativeSessionPreservesSampleAgeAndCanSelectRecentActivity() = withBridge { bridge ->
        val record = AtomicReference<RecentWorkout>()
        bridge.onWorkout = { value, _ -> record.set(value) }
        val now = System.currentTimeMillis()
        val body = """{"kind":"swim","source":"health-connect","endedAt":${now - 1000},"durationMinutes":28,"bpm":105,"bpmMeasuredAt":${now - 45_000}}"""
        assertTrue(request(bridge, "POST", "/workout", body).startsWith("HTTP/1.1 200"))
        assertEquals(WorkoutRecordSource.WATCH_RELAY, record.get().source)
        assertEquals(ActivityKind.WATER, record.get().kind)
        assertTrue(record.get().canAutoSelect(now))
        assertTrue(record.get().heartRate.ageSeconds!! >= 45)
        assertEquals(HeartRateStatus.STALE, record.get().heartRate.status)
    }

    @Test fun staleDirectSensorSampleIsRejected() = withBridge { bridge ->
        val now = System.currentTimeMillis()
        val body = """{"bpm":120,"source":"wear-os-sensor","measuredAt":${now - 31_000}}"""
        assertTrue(request(bridge, "POST", "/heart-rate", body).startsWith("HTTP/1.1 400"))
        assertNull(bridge.connection)
    }

    @Test fun directSensorCallbackPreservesOriginalMeasurementTime() = withBridge { bridge ->
        val received = AtomicReference<Pair<Int, Long>>()
        bridge.onTimedReading = { bpm, measuredAt, _ -> received.set(bpm to measuredAt) }
        val measuredAt = System.currentTimeMillis() - 20_000
        val body = """{"bpm":120,"source":"wear-os-sensor","measuredAt":$measuredAt}"""
        assertTrue(request(bridge, "POST", "/heart-rate", body).startsWith("HTTP/1.1 200"))
        assertEquals(120, received.get().first)
        assertEquals(measuredAt, received.get().second)
    }

    @Test fun appleWorkoutReceiptProvesDeliveryButOldHeartRateRemainsOld() = withBridge { bridge ->
        val now = System.currentTimeMillis()
        val requestId = "eafcfe30-0b77-4e4d-bcc9-1ee0fe6c1398"
        val body = """{"requestId":"$requestId","source":"apple-health","kind":"run","endedAt":${now - 60_000},"bpm":125,"bpmMeasuredAt":${now - 65_000}}"""
        val response = request(bridge, "POST", "/workout", body)
        val receipt = JSONObject(response.substringAfter("\r\n\r\n"))
        assertTrue(receipt.getBoolean("ok"))
        assertEquals(requestId, receipt.getString("requestId"))
        assertTrue(receipt.getLong("receivedAt") >= now)
        val status = JSONObject(request(bridge, "GET", "/status").substringAfter("\r\n\r\n"))
        assertTrue(status.getBoolean("connected"))
        assertFalse(status.getBoolean("heartRateFresh"))
        assertTrue(status.getLong("heartRateAgeSeconds") >= 65)
        assertEquals("apple-health", status.getString("heartRateSource"))
        assertEquals(requestId, status.getJSONObject("latestWorkout").getString("requestId"))
        assertFalse(status.toString().contains(bridge.token))
    }

    @Test fun heartbeatDoesNotInventNewHealthDataOrOverwriteHealthSource() = withBridge { bridge ->
        request(bridge, "POST", "/connect", """{"source":"apple-health"}""")
        assertEquals(0L, bridge.diagnostics.lastNativeHealthAtEpochMillis)
        assertFalse(bridge.connection!!.hasHealthData)
        val now = System.currentTimeMillis()
        request(bridge, "POST", "/workout", """{"source":"apple-health","kind":"run","endedAt":${now - 1000}}""")
        val healthAt = bridge.diagnostics.lastNativeHealthAtEpochMillis
        request(bridge, "POST", "/connect", """{"source":"apple-health"}""")
        assertEquals(healthAt, bridge.diagnostics.lastNativeHealthAtEpochMillis)
        assertEquals("Apple Health 수신", bridge.connection!!.sourceLabel)
        assertNull(bridge.diagnostics.lastHeartRateMeasuredAtEpochMillis)
    }

    @Test fun profileClaimNeedsNativeRelayAndDoesNotCreateLocalProfile() = withBridge { bridge ->
        val identity = AtomicReference<PairedDriverIdentity>()
        bridge.onDriverIdentity = { identity.set(it) }
        val body = """{"source":"apple-health","driverId":"e14ddceb-e682-4f1b-a072-a589a9c24576","driverName":"내 iPhone"}"""
        assertTrue(request(bridge, "POST", "/connect", body, browser = true).startsWith("HTTP/1.1 400"))
        assertNull(identity.get())
        assertTrue(request(bridge, "POST", "/connect", body).startsWith("HTTP/1.1 200"))
        assertEquals("내 iPhone", identity.get().displayName)
        assertNull(DriverProfileSettings().automaticSelection(identity.get(), System.currentTimeMillis(), true))
        assertFalse(bridge.connection!!.hasHealthData)
    }

    @Test fun eachWorkoutHasItsOwnIdentityAndCannotReusePriorPhonesClaim() = withBridge { bridge ->
        val currentIdentity = AtomicReference<PairedDriverIdentity>()
        bridge.onWorkout = { _, identity -> currentIdentity.set(identity) }
        val now = System.currentTimeMillis()
        val identified = """{"source":"apple-health","driverId":"e14ddceb-e682-4f1b-a072-a589a9c24576","kind":"run","endedAt":${now - 1000}}"""
        request(bridge, "POST", "/workout", identified)
        assertNotNull(currentIdentity.get())
        request(bridge, "POST", "/workout", """{"source":"apple-health","kind":"run","endedAt":${now - 1000}}""")
        assertNull(currentIdentity.get())
        assertNotNull(bridge.diagnostics.identity)
    }

    @Test fun shortcutsPhoneIdentityStillNeedsAnExplicitLocalProfileBinding() = withBridge { bridge ->
        val identity = AtomicReference<PairedDriverIdentity>()
        bridge.onDriverIdentity = { identity.set(it) }
        request(bridge, "POST", "/connect", """{"source":"apple-shortcuts","driverId":"e14ddceb-e682-4f1b-a072-a589a9c24576"}""")
        assertEquals("apple-shortcuts", identity.get().relaySource)
        assertEquals("연결한 iPhone", identity.get().displayName)
        assertNull(DriverProfileSettings().copy(autoSelectPairedPhone = true)
            .automaticSelection(identity.get(), System.currentTimeMillis(), true))
        assertFalse(bridge.connection!!.hasHealthData)
    }

    @Test fun manualImportCannotPassAsNativeHealthProof() = withBridge { bridge ->
        val now = System.currentTimeMillis()
        request(bridge, "POST", "/import", """{"source":"apple-health","kind":"run","endedAt":${now - 1000}}""", browser = true)
        assertEquals(0L, bridge.diagnostics.lastNativeHealthAtEpochMillis)
        assertFalse(bridge.connection!!.hasHealthData)
        assertTrue(bridge.diagnostics.latestWorkout!!.manual)
        assertEquals("manual-import", bridge.diagnostics.latestWorkout!!.source)
    }

    @Test fun shortcutsAcceptsHistoricalIsoHeartRateWithoutMakingItCurrentOrNative() = withBridge { bridge ->
        val reading = AtomicReference<Pair<Int, Long>>()
        bridge.onShortcutReading = { bpm, measured, _ -> reading.set(bpm to measured) }
        val measured = System.currentTimeMillis() - 65_000
        val iso = Instant.ofEpochMilli(measured).atOffset(ZoneOffset.ofHours(9)).toString()
        val response = request(bridge, "POST", "/shortcut-heart-rate",
            """{"source":"apple-shortcuts","bpm":123,"measuredAtIso":"$iso","requestId":"shortcut-test"}""")
        assertTrue(response.startsWith("HTTP/1.1 200"))
        assertEquals(123, reading.get().first)
        assertEquals(measured, reading.get().second)
        assertEquals(0L, bridge.diagnostics.lastNativeHealthAtEpochMillis)
        assertTrue(bridge.diagnostics.lastShortcutHealthAtEpochMillis >= measured + 65_000)
        assertFalse(bridge.diagnostics.hasFreshHeartRate(System.currentTimeMillis()))
        val status = JSONObject(request(bridge, "GET", "/status").substringAfter("\r\n\r\n"))
        assertEquals("apple-shortcuts", status.getJSONObject("latestHeartRate").getString("source"))
        assertEquals("shortcut-test", status.getJSONObject("latestHeartRate").getString("requestId"))
        assertFalse(status.getJSONObject("latestHeartRate").has("bpm"))
    }

    @Test fun shortcutsRejectsAmbiguousFutureTooOldAndBrowserSamples() = withBridge { bridge ->
        val now = System.currentTimeMillis()
        for (iso in listOf("2026-11-05T12:34:56", Instant.ofEpochMilli(now + 60_000).toString(),
            Instant.ofEpochMilli(now - 601_000).toString())) {
            assertTrue(request(bridge, "POST", "/shortcut-heart-rate",
                """{"source":"apple-shortcuts","bpm":120,"measuredAtIso":"$iso"}""").startsWith("HTTP/1.1 400"))
        }
        assertTrue(request(bridge, "POST", "/shortcut-heart-rate",
            """{"source":"apple-shortcuts","bpm":120,"measuredAtIso":"${Instant.ofEpochMilli(now - 1000)}"}""",
            browser = true).startsWith("HTTP/1.1 403"))
        assertNull(bridge.connection)
    }

    @Test fun shortcutsHeartRateCarriesOnlyTheCurrentRequestsExplicitPhoneIdentity() = withBridge { bridge ->
        val phoneId = "e14ddceb-e682-4f1b-a072-a589a9c24576"
        val callbackOrder = mutableListOf<String>()
        val currentIdentity = AtomicReference<PairedDriverIdentity>()
        bridge.onDriverIdentity = { callbackOrder += "identity" }
        bridge.onShortcutReading = { _, _, identity ->
            currentIdentity.set(identity)
            callbackOrder += "heart-rate"
        }
        val iso = Instant.ofEpochMilli(System.currentTimeMillis() - 1000).toString()
        val body = """{"source":"apple-shortcuts","driverId":"$phoneId","bpm":120,"measuredAtIso":"$iso"}"""
        assertTrue(request(bridge, "POST", "/shortcut-heart-rate", body).startsWith("HTTP/1.1 200"))
        assertEquals(phoneId, currentIdentity.get().phoneId)
        assertEquals(listOf("identity", "heart-rate"), callbackOrder)
        assertTrue(request(bridge, "POST", "/shortcut-heart-rate",
            """{"source":"apple-shortcuts","bpm":120,"measuredAtIso":"$iso"}""").startsWith("HTTP/1.1 200"))
        assertNull(currentIdentity.get())
        assertEquals(phoneId, bridge.diagnostics.identity!!.phoneId)
    }

    @Test fun shortcutsWorkoutEndIsAnEventAndCannotInventDurationOrSensorHeartRate() = withBridge { bridge ->
        val received = AtomicReference<RecentWorkout>()
        bridge.onWorkout = { value, _ -> received.set(value) }
        val now = System.currentTimeMillis()
        val response = request(bridge, "POST", "/workout",
            """{"source":"apple-shortcuts","kind":"run","endedAtIso":"${Instant.ofEpochMilli(now - 1000)}","durationMinutes":42,"bpm":125,"bpmMeasuredAt":${now - 1000}}""")
        assertTrue(response.startsWith("HTTP/1.1 200"))
        assertEquals(WorkoutRecordSource.PHONE_AUTOMATION, received.get().source)
        assertTrue(received.get().canAutoSelect(System.currentTimeMillis()))
        assertNull(received.get().durationMinutes)
        assertEquals(HeartRateStatus.MISSING, received.get().heartRate.status)
        assertEquals(0L, bridge.diagnostics.lastNativeHealthAtEpochMillis)
        assertFalse(bridge.connection!!.hasHealthData)
        assertTrue(bridge.diagnostics.latestWorkout!!.automation)
    }

    @Test fun oversizedBodyIsRejectedBeforeParsing() = withBridge { bridge ->
        // Reject an excessive declared length before waiting for any body bytes.
        // An unused body races TCP reset against the reply on Windows.
        val response = Socket("127.0.0.1", bridge.boundPort).use { socket ->
            socket.soTimeout = 2000
            val header = "POST /import HTTP/1.1\r\nHost: localhost\r\nAuthorization: Bearer ${bridge.token}\r\nContent-Length: 8193\r\n\r\n"
            socket.getOutputStream().apply { write(header.toByteArray(Charsets.US_ASCII)); flush() }
            readResponse(socket)
        }
        assertTrue(response.startsWith("HTTP/1.1 400"))
        assertNull(bridge.connection)
    }

    @Test fun suppliedPairingSecretSurvivesNewReceiverButInvalidSecretFails() {
        val secret = "a".repeat(32)
        assertEquals(secret, WatchReceiver(pairingToken = secret) { }.token)
        assertThrows(IllegalArgumentException::class.java) { WatchReceiver(pairingToken = "bad") { } }
    }

    @Test fun slowApkDownloadDoesNotBlockStatusOrHealthAndOnlyOneDownloadStreams() = withBridge { bridge ->
        val entered = CountDownLatch(1)
        val providers = AtomicInteger()
        val providerClosed = AtomicBoolean()
        bridge.companionApkProvider = {
            providers.incrementAndGet()
            object : InputStream() {
                override fun read() = 0
                override fun read(bytes: ByteArray, offset: Int, length: Int): Int {
                    entered.countDown()
                    bytes.fill(0, offset, offset + length)
                    return length
                }
                override fun close() { providerClosed.set(true) }
            }
        }
        Socket().use { download ->
            download.receiveBufferSize = 1024
            download.connect(InetSocketAddress("127.0.0.1", bridge.boundPort))
            download.getOutputStream().apply {
                write("GET /companion.apk HTTP/1.1\r\nHost: localhost\r\n\r\n".toByteArray()); flush()
            }
            assertTrue(entered.await(2, TimeUnit.SECONDS))
            assertTrue(request(bridge, "GET", "/status").startsWith("HTTP/1.1 200"))
            val measured = System.currentTimeMillis() - 1000
            assertTrue(request(bridge, "POST", "/shortcut-heart-rate",
                """{"source":"apple-shortcuts","bpm":82,"measuredAtIso":"${Instant.ofEpochMilli(measured)}"}""")
                .startsWith("HTTP/1.1 200"))
            assertEquals(measured, bridge.diagnostics.lastHeartRateMeasuredAtEpochMillis)
            assertTrue(request(bridge, "GET", "/companion.apk", auth = false).startsWith("HTTP/1.1 503"))
            assertEquals(1, providers.get())
            val resources = bridge.transportResources()
            assertTrue(resources.workerThreads <= WatchReceiver.MAX_CONNECTIONS)
            assertEquals(0, resources.queuedTasks)
            bridge.stop()
            assertTrue("Stop interrupts a blocked download write", bridge.awaitTransportStopped(2500))
            assertTrue("Stop closes the companion asset stream", providerClosed.get())
            assertEquals(0, bridge.transportResources().workerThreads)
            assertEquals(0, bridge.transportResources().activeSockets)
        }
    }

    @Test fun saturationHasFourConnectionsNoQueueAndRejectsOverflowWithoutGrowingResources() = withBridge { bridge ->
        val occupied = (0 until WatchReceiver.MAX_CONNECTIONS).map { pendingHeader(bridge) }
        try {
            waitUntil("All bounded worker slots occupied") {
                bridge.transportResources().activeSockets == WatchReceiver.MAX_CONNECTIONS
            }
            Socket("127.0.0.1", bridge.boundPort).use { overflow ->
                overflow.soTimeout = 2000
                assertClosed(overflow)
            }
            val resources = bridge.transportResources()
            assertEquals(WatchReceiver.MAX_CONNECTIONS, resources.activeSockets)
            assertTrue(resources.workerThreads <= WatchReceiver.MAX_CONNECTIONS)
            assertEquals(0, resources.queuedTasks)
            assertTrue(resources.scheduledDeadlines <= WatchReceiver.MAX_CONNECTIONS)
        } finally { occupied.forEach { it.close() } }
        waitUntil("Closed clients release connection slots") { bridge.transportResources().activeSockets == 0 }
    }

    @Test fun stopClosesAlreadyAcceptedSocketsAndReclaimsAllTransportThreads() {
        val bridge = WatchReceiver(port = 0) { }
        bridge.start()
        val callbacks = AtomicInteger()
        bridge.onWorkout = { _, _ -> callbacks.incrementAndGet() }
        val pending = listOf(pendingHeader(bridge), pendingWorkoutBody(bridge))
        try {
            waitUntil("Requests accepted before stop") { bridge.transportResources().activeSockets == 2 }
            bridge.stop()
            assertTrue(bridge.awaitTransportStopped(2500))
            pending.forEach { assertClosed(it) }
            assertEquals(0, bridge.transportResources().activeSockets)
            assertEquals(0, bridge.transportResources().workerThreads)
            assertEquals(0, bridge.transportResources().queuedTasks)
            assertEquals(0, bridge.transportResources().scheduledDeadlines)
            assertEquals(0, bridge.transportResources().orderStreams)
            assertNull(bridge.connection)
            assertEquals(BridgeDiagnostics(), bridge.diagnostics)
            assertEquals(0, callbacks.get())
        } finally {
            pending.forEach { it.close() }; bridge.stop()
            assertTrue(bridge.awaitTransportStopped(2500))
        }
    }

    @Test fun absoluteDeadlineReleasesSlowHeadersEvenBeforeReadTimeout() {
        val bridge = WatchReceiver(port = 0, requestTimeoutMillis = 250) { }
        bridge.start()
        try {
            pendingHeader(bridge).use { socket ->
                waitUntil("Slow header accepted") { bridge.transportResources().activeSockets == 1 }
                assertClosed(socket)
                waitUntil("Absolute deadline releases worker socket") { bridge.transportResources().activeSockets == 0 }
                assertEquals(0, bridge.transportResources().queuedTasks)
            }
        } finally { bridge.stop(); assertTrue(bridge.awaitTransportStopped(2500)) }
    }

    @Test fun parallelWorkoutsCarryImmutableIdentitiesAndSerializedCallbackPairs() = withBridge { bridge ->
        val events = Collections.synchronizedList(mutableListOf<String>())
        val now = System.currentTimeMillis()
        val identities = (1..3).map { "e14ddceb-e682-4f1b-a072-${it.toString().padStart(12, '0')}" }
        bridge.onDriverIdentity = { events += "identity:${it.phoneId}" }
        bridge.onWorkout = { record, identity ->
            events += "workout:${identity?.phoneId}:${record.durationMinutes}"
        }
        val ready = CountDownLatch(3)
        val start = CountDownLatch(1)
        val clients = Executors.newFixedThreadPool(3)
        try {
            val results = identities.mapIndexed { index, phoneId -> clients.submit<Boolean> {
                ready.countDown(); assertTrue(start.await(2, TimeUnit.SECONDS))
                request(bridge, "POST", "/workout",
                    """{"source":"apple-health","driverId":"$phoneId","kind":"run","durationMinutes":${10 + index},"endedAt":${now - 1000}}""")
                    .startsWith("HTTP/1.1 200")
            } }
            assertTrue(ready.await(2, TimeUnit.SECONDS)); start.countDown()
            results.forEach { assertTrue(it.get(4, TimeUnit.SECONDS)) }
            assertEquals(6, events.size)
            events.chunked(2).forEach { pair ->
                val phoneId = pair[0].removePrefix("identity:")
                val index = identities.indexOf(phoneId)
                assertTrue(index >= 0)
                assertEquals("workout:$phoneId:${10 + index}", pair[1])
            }
        } finally { clients.shutdownNow(); assertTrue(clients.awaitTermination(2500, TimeUnit.MILLISECONDS)) }
    }

    @Test fun slowOlderWorkoutCannotReplaceNewerSamePhoneAndSource() = withBridge { bridge ->
        val received = Collections.synchronizedList(mutableListOf<Long>())
        bridge.onWorkout = { record, _ -> received += record.endedAtEpochMillis!! }
        val now = System.currentTimeMillis()
        val phone = "e14ddceb-e682-4f1b-a072-a589a9c24576"
        val olderBody = """{"source":"apple-health","driverId":"$phone","kind":"run","endedAt":${now - 20_000}}""".toByteArray()
        Socket("127.0.0.1", bridge.boundPort).use { older ->
            older.soTimeout = 4000
            older.getOutputStream().apply {
                write(("POST /workout HTTP/1.1\r\nHost: localhost\r\nAuthorization: Bearer ${bridge.token}\r\n" +
                    "Content-Length: ${olderBody.size}\r\n\r\n").toByteArray())
                write(olderBody, 0, 1); flush()
            }
            waitUntil("Older request body is still being received") { bridge.transportResources().activeSockets == 1 }
            assertTrue(request(bridge, "POST", "/workout",
                """{"source":"apple-health","driverId":"$phone","kind":"run","endedAt":${now - 1000}}""")
                .startsWith("HTTP/1.1 200"))
            older.getOutputStream().apply { write(olderBody, 1, olderBody.size - 1); flush() }
            assertTrue(readResponse(older).startsWith("HTTP/1.1 409"))
            assertEquals(listOf(now - 1000), received)
            assertEquals(now - 1000, bridge.diagnostics.latestWorkout!!.endedAtEpochMillis)
        }
    }

    @Test fun concurrentNativeAndShortcutDeliveriesDoNotLoseDiagnosticFields() = withBridge { bridge ->
        val now = System.currentTimeMillis()
        val start = CountDownLatch(1)
        val clients = Executors.newFixedThreadPool(2)
        try {
            val native = clients.submit<Boolean> {
                assertTrue(start.await(2, TimeUnit.SECONDS))
                request(bridge, "POST", "/workout",
                    """{"source":"apple-health","kind":"run","endedAt":${now - 2000},"requestId":"native-concurrent"}""")
                    .startsWith("HTTP/1.1 200")
            }
            val shortcut = clients.submit<Boolean> {
                assertTrue(start.await(2, TimeUnit.SECONDS))
                request(bridge, "POST", "/shortcut-heart-rate",
                    """{"source":"apple-shortcuts","bpm":82,"measuredAtIso":"${Instant.ofEpochMilli(now - 1000)}","requestId":"shortcut-concurrent"}""")
                    .startsWith("HTTP/1.1 200")
            }
            start.countDown()
            assertTrue(native.get(4, TimeUnit.SECONDS))
            assertTrue(shortcut.get(4, TimeUnit.SECONDS))
            assertTrue(bridge.diagnostics.lastNativeHealthAtEpochMillis >= now)
            assertTrue(bridge.diagnostics.lastShortcutHealthAtEpochMillis >= now)
            assertEquals("native-concurrent", bridge.diagnostics.latestWorkout!!.requestId)
            assertEquals("shortcut-concurrent", bridge.diagnostics.lastHeartRateRequestId)
            assertEquals(now - 1000, bridge.diagnostics.lastHeartRateMeasuredAtEpochMillis)
        } finally { clients.shutdownNow(); assertTrue(clients.awaitTermination(2500, TimeUnit.MILLISECONDS)) }
    }

    @Test fun orderingMetadataIsBoundedAndOlderHeartRateDoesNotOverwriteNewerReceipt() = withBridge { bridge ->
        val now = System.currentTimeMillis()
        val phone = "e14ddceb-e682-4f1b-a072-a589a9c24576"
        val newest = Instant.ofEpochMilli(now - 1000).toString()
        val old = Instant.ofEpochMilli(now - 20_000).toString()
        assertTrue(request(bridge, "POST", "/shortcut-heart-rate",
            """{"source":"apple-shortcuts","driverId":"$phone","bpm":82,"measuredAtIso":"$newest","requestId":"new"}""")
            .startsWith("HTTP/1.1 200"))
        assertTrue(request(bridge, "POST", "/shortcut-heart-rate",
            """{"source":"apple-shortcuts","driverId":"$phone","bpm":81,"measuredAtIso":"$old","requestId":"old"}""")
            .startsWith("HTTP/1.1 409"))
        assertEquals("new", bridge.diagnostics.lastHeartRateRequestId)
        for (index in 1..WatchReceiver.MAX_STREAMS + 2) {
            val identity = "e14ddceb-e682-4f1b-a072-${index.toString().padStart(12, '0')}"
            assertTrue(request(bridge, "POST", "/workout",
                """{"source":"apple-health","driverId":"$identity","kind":"run","endedAt":${now - 1000}}""")
                .startsWith("HTTP/1.1 200"))
        }
        assertEquals(WatchReceiver.MAX_STREAMS, bridge.transportResources().orderStreams)
    }

    @Test fun publicLandingAddressOnlyAcceptsPrivateIpv4WithoutAnySecret() {
        assertTrue(PairingAddress.isPrivateIpv4("192.168.0.15"))
        assertTrue(PairingAddress.isPrivateIpv4("172.31.0.2"))
        assertFalse(PairingAddress.isPrivateIpv4("172.32.0.2"))
        assertFalse(PairingAddress.isPrivateIpv4("8.8.8.8"))
        assertFalse(PairingAddress.isPrivateIpv4("127.0.0.1"))
        assertFalse(PairingAddress.isPrivateIpv4("192.168.0.999"))
        assertFalse(PairingAddress.isPrivateIpv4("192.168.0.1/evil"))
        val url = PairingAddress.landingUrl("192.168.0.15")
        assertEquals("http://192.168.0.15:8765/pair",url)
        assertFalse(url.contains("token"))
    }
}
