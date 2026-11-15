package com.example.version1

import java.io.InputStream
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import java.security.MessageDigest
import java.security.SecureRandom
import java.time.OffsetDateTime
import java.util.LinkedHashMap
import java.util.concurrent.RejectedExecutionException
import java.util.concurrent.ScheduledThreadPoolExecutor
import java.util.concurrent.Semaphore
import java.util.concurrent.SynchronousQueue
import java.util.concurrent.ThreadFactory
import java.util.concurrent.ThreadPoolExecutor
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import org.json.JSONObject

internal class DockReceiverBusy : IllegalStateException()

data class BridgeConnection(
    val sourceLabel: String,
    val connectedAtEpochMillis: Long,
    val lastReceivedAtEpochMillis: Long,
    val hasHealthData: Boolean
) {
    fun isRecent(nowEpochMillis: Long): Boolean = nowEpochMillis - lastReceivedAtEpochMillis in 0..60_000
}

data class BridgeWorkoutReceipt(
    val requestId: String?,
    val source: String,
    val endedAtEpochMillis: Long,
    val receivedAtEpochMillis: Long,
    val manual: Boolean,
    val automation: Boolean = false
)

/** In-memory delivery evidence. No health history, secrets or full request bodies are retained. */
data class BridgeDiagnostics(
    val lastAcceptedRequestAtEpochMillis: Long = 0,
    val lastNativeHealthAtEpochMillis: Long = 0,
    val lastShortcutHealthAtEpochMillis: Long = 0,
    val latestShortcutHeartRateRequestId: String? = null,
    val latestWorkout: BridgeWorkoutReceipt? = null,
    val lastHeartRateMeasuredAtEpochMillis: Long? = null,
    val lastHeartRateReceivedAtEpochMillis: Long = 0,
    val lastHeartRateRequestId: String? = null,
    val lastHeartRateSource: String? = null,
    val latestWatchActivity: com.example.afterglow.net.WatchActivityRecord? = null,
    val watchActivityReceivedAt: Long = 0,
    val historyWindowEnd: Long? = null,
    val historyReceivedAt: Long = 0,
    val historyCount: Int? = null,
    val historyTotal: Int? = null,
    val workoutSyncStatus: String? = null,
    val lastWatchSensorReceivedAt: Long = 0,
    val identity: PairedDriverIdentity? = null
) {
    /** Keep the user's 60s grace period. History/heartbeat never renew this sensor lease. */
    fun hasLiveWatchSensor(nowEpochMillis: Long): Boolean =
        lastWatchSensorReceivedAt > 0 && nowEpochMillis - lastWatchSensorReceivedAt in 0 until 60_000

    fun heartRateAgeSeconds(nowEpochMillis: Long): Long? = lastHeartRateMeasuredAtEpochMillis
        ?.takeIf { it in 1..nowEpochMillis }?.let { (nowEpochMillis - it) / 1000 }

    fun hasFreshHeartRate(nowEpochMillis: Long): Boolean =
        heartRateAgeSeconds(nowEpochMillis)?.let { it < ComfortPolicy.FRESH_FOR_SECONDS } == true
}

/** User-enabled local demo bridge. A shared token authorizes a relay, not sensor authenticity. */
class WatchReceiver(pairingToken: String? = null, private val port: Int = 8765,
    private val requestTimeoutMillis: Long = 30_000,
    private val dockOnly: Boolean = false,
    private val onReading: (Int) -> Unit) {
    init { require(requestTimeoutMillis in 100..30_000) }
    val token: String = pairingToken?.also { require(it.matches(Regex("[0-9a-f]{32}"))) }
        ?: ByteArray(16).also { SecureRandom().nextBytes(it) }.joinToString("") { "%02x".format(it) }
    // Callbacks are serialized with delivery commits. Post UI work and return promptly.
    @Volatile var onWorkoutHistory: ((com.example.afterglow.net.WorkoutHistorySnapshot, PairedDriverIdentity?) -> Unit)? = null
    @Volatile var onWorkout: ((RecentWorkout, PairedDriverIdentity?) -> Unit)? = null
    @Volatile var onTimedReading: ((Int, Long, PairedDriverIdentity?) -> Unit)? = null
    @Volatile var onShortcutReading: ((Int, Long, PairedDriverIdentity?) -> Unit)? = null
    @Volatile var onPhoneReading: ((Int, Long, PairedDriverIdentity?) -> Unit)? = null
    @Volatile var onConnection: ((BridgeConnection) -> Unit)? = null
    @Volatile var onDriverIdentity: ((PairedDriverIdentity) -> Unit)? = null
    @Volatile var onZeroTap: ((ZeroTapEvent) -> Unit)? = null
    @Volatile var zeroTapStatus: (() -> String)? = null
    @Volatile var zeroTapReceipt: (() -> JSONObject)? = null
    @Volatile var companionApkProvider: (() -> InputStream)? = null
    @Volatile var connection: BridgeConnection? = null
        private set
    @Volatile var diagnostics = BridgeDiagnostics()
        private set
    @Volatile private var server: ServerSocket? = null
    @Volatile private var running = false
    private var started = false
    private var acceptThread: Thread? = null
    private val lifecycleLock = Any()
    private val stateLock = Any()
    private val watchPairing = WatchPairingWindow()
    fun openWatchPairing(): String = watchPairing.open(System.currentTimeMillis())
    fun closeWatchPairing() = watchPairing.close()
    fun watchPairingGeneration(): Long = watchPairing.generation()
    fun closeWatchPairing(expectedGeneration: Long) = watchPairing.closeIfCurrent(expectedGeneration)
    private val activeSockets = mutableSetOf<Socket>()
    private val downloadPermit = Semaphore(1)
    private val threadNumbers = AtomicInteger()
    private val receiverNumber = nextReceiverNumber.incrementAndGet()
    private val threads = ThreadFactory { task ->
        // A stack-size hint, not an accounting guarantee; measure actual process RSS/PSS.
        Thread(null, task, "afterglow-bridge-$receiverNumber-${threadNumbers.incrementAndGet()}",
            THREAD_STACK_HINT_BYTES).apply { isDaemon = true }
    }
    private val workers = ThreadPoolExecutor(MAX_CONNECTIONS, MAX_CONNECTIONS, 1, TimeUnit.SECONDS,
        SynchronousQueue(), threads, ThreadPoolExecutor.AbortPolicy()).apply { allowCoreThreadTimeOut(true) }
    private val deadlines = ScheduledThreadPoolExecutor(1, threads).apply {
        removeOnCancelPolicy = true
        setExecuteExistingDelayedTasksAfterShutdownPolicy(false)
        setContinueExistingPeriodicTasksAfterShutdownPolicy(false)
    }
    /** Bounded monotonic metadata only; no samples, BPM values, bodies or secrets. */
    private val streamOrder = object : LinkedHashMap<StreamKey, StreamOrder>(16, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<StreamKey, StreamOrder>?) = size > MAX_STREAMS
    }
    val boundPort: Int get() = server?.localPort ?: -1

    fun start() {
        synchronized(lifecycleLock) {
            check(!started) { "Create a new receiver after stop" }
            // External health/device claims enter through an authenticated HTTPS relay.
            // Plain HTTP is trusted-device loopback IPC only, never a LAN listener.
            val listener = ServerSocket(port, MAX_CONNECTIONS, InetAddress.getByName("127.0.0.1"))
            server = listener; started = true; running = true
            acceptThread = threads.newThread {
                while (running) {
                    val socket = runCatching { listener.accept() }.getOrNull() ?: break
                    dispatch(socket)
                }
            }.also { it.start() }
        }
    }

    private fun dispatch(socket: Socket) {
        synchronized(lifecycleLock) {
            if (!running || activeSockets.size >= MAX_CONNECTIONS) {
                runCatching { socket.close() }; return
            }
            activeSockets += socket
            var deadline: java.util.concurrent.ScheduledFuture<*>? = null
            try {
                // Also bounds slow writes: Socket.soTimeout covers reads only.
                val closeDeadline = deadlines.schedule({ runCatching { socket.close() } }, requestTimeoutMillis,
                    TimeUnit.MILLISECONDS)
                deadline = closeDeadline
                workers.execute {
                    try {
                        socket.use {
                            if (running) runCatching { receive(it) }.onFailure { error ->
                                if (running && !it.isClosed) runCatching {
                                    if (error is DockReceiverBusy) respond(it, 503, "{\"error\":\"dock_busy\"}")
                                    else if (error is OutOfOrderDelivery) respond(it, 409, "{\"error\":\"out_of_order\"}")
                                    else respond(it, 400, "{\"error\":\"invalid_request\"}")
                                }
                            }
                        }
                    } finally {
                        closeDeadline.cancel(false)
                        synchronized(lifecycleLock) { activeSockets -= socket }
                    }
                }
            } catch (_: RejectedExecutionException) {
                deadline?.cancel(false)
                activeSockets -= socket
                runCatching { socket.close() }
            }
        }
    }

    private fun receive(socket: Socket) {
        socket.soTimeout = 3000
        socket.sendBufferSize = 16 * 1024
        socket.receiveBufferSize = 16 * 1024
        val input = socket.getInputStream()
        fun line(): String {
            val bytes = ArrayList<Byte>()
            while (bytes.size < 1024) {
                val value = input.read()
                check(value >= 0)
                if (value == 10) return bytes.toByteArray().toString(Charsets.US_ASCII).trimEnd('\r')
                bytes += value.toByte()
            }
            error("Header too large")
        }
        val request = line().split(' ')
        require(request.size == 3 && request[2] == "HTTP/1.1")
        val method = request[0]
        val path = request[1]
        val headers = mutableMapOf<String, String>()
        var total = 0
        while (true) {
            val value = line()
            total += value.length
            require(total < 8192)
            if (value.isEmpty()) break
            require(':' in value)
            val name = value.substringBefore(':').lowercase()
            require(name !in headers)
            headers[name] = value.substringAfter(':').trim()
        }
        // The background listener is not another health ingress or APK/pairing server.
        // Reject before reading a body or invoking any health/identity callback.
        if (dockOnly && !((method == "POST" && path == "/zero-tap") || (method == "GET" && path == "/status"))) {
            respond(socket, 404, "{\"error\":\"dock_only\"}"); return
        }
        if (method == "GET" && (path == "/pair" || path == "/")) {
            respond(socket, 200, PairingPage.html, "text/html; charset=utf-8"); return
        }
        // The signed debug APK contains no personal information or pairing secret.
        if (method == "GET" && path == "/companion.apk") {
            val provider = companionApkProvider
            if (provider == null) { respond(socket, 404, "{\"error\":\"companion_unavailable\"}"); return }
            if (!downloadPermit.tryAcquire()) {
                respond(socket, 503, "{\"error\":\"download_busy\"}"); return
            }
            // The bundled companion is ~11MB. Stream it instead of retaining a growing
            // ByteArray plus its final copy while the vehicle scene is resident.
            try { provider().use { streamCompanion(socket, it) } }
            finally { downloadPermit.release() }
            return
        }
        if (method == "POST" && path == "/pair-watch") {
            if (headers["origin"] != null || headers["transfer-encoding"] != null) {
                respond(socket,403,"{\"error\":\"native_pairing_only\"}"); return
            }
            val length=headers["content-length"]?.toIntOrNull() ?: 0
            if (length !in 1..128) { respond(socket,400,"{\"error\":\"invalid_pairing\"}"); return }
            val body=ByteArray(length)
            var filled=0
            while(filled<length) { val count=input.read(body,filled,length-filled);if(count<0) break;filled+=count }
            if(filled!=length) { respond(socket,400,"{\"error\":\"invalid_pairing\"}"); return }
            val code=runCatching {
                val raw=String(body,Charsets.UTF_8)
                JsonIngress.requireObject(raw,128,1)
                JSONObject(raw).getString("code")
            }.getOrDefault("")
            if(!watchPairing.redeem(code,System.currentTimeMillis())) { respond(socket,403,"{\"error\":\"expired_or_invalid_code\"}"); return }
            respond(socket,200,JSONObject().put("ok",true).put("token",token).toString()); return
        }
        val auth = headers["authorization"].orEmpty()
        if (!MessageDigest.isEqual(auth.toByteArray(), "Bearer $token".toByteArray())) {
            respond(socket, 403, "{\"error\":\"pairing_code_required\"}"); return
        }
        val origin = headers["origin"]
        val host = headers["host"]
        if (origin != null && origin != "http://$host") {
            respond(socket, 403, "{\"error\":\"origin_rejected\"}"); return
        }
        if (method == "GET" && path == "/status") {
            if (dockOnly) {
                respond(socket, 200, JSONObject().put("dockOnly", true).put("hasHealthData", false)
                    .put("zeroTapStatus", zeroTapStatus?.invoke() ?: "unavailable")
                    .put("zeroTapReceipt", zeroTapReceipt?.invoke() ?: JSONObject.NULL).toString()); return
            }
            val (state, diagnostic) = synchronized(stateLock) { connection to diagnostics }
            val now = System.currentTimeMillis()
            val receipt = diagnostic.latestWorkout
            val latestWorkout = receipt?.let {
                JSONObject().put("requestId", it.requestId ?: JSONObject.NULL).put("source", it.source)
                    .put("endedAt", it.endedAtEpochMillis).put("receivedAt", it.receivedAtEpochMillis)
                    .put("manual", it.manual).put("automation", it.automation)
            }
            val latestHeartRate = diagnostic.lastHeartRateMeasuredAtEpochMillis?.let {
                JSONObject().put("measuredAt", it).put("receivedAt", diagnostic.lastHeartRateReceivedAtEpochMillis)
                    .put("requestId", diagnostic.lastHeartRateRequestId ?: JSONObject.NULL)
                    .put("source", diagnostic.lastHeartRateSource ?: "")
            }
            respond(socket, 200, JSONObject().put("connected", state?.isRecent(now) == true)
                .put("hasHealthData", state?.hasHealthData == true)
                .put("source", state?.sourceLabel ?: "")
                .put("lastReceivedAt", state?.lastReceivedAtEpochMillis ?: 0)
                .put("lastNativeHealthAt", diagnostic.lastNativeHealthAtEpochMillis)
                .put("lastShortcutHealthAt", diagnostic.lastShortcutHealthAtEpochMillis)
                .put("latestShortcutHeartRateRequestId", diagnostic.latestShortcutHeartRateRequestId ?: JSONObject.NULL)
                .put("latestWorkout", latestWorkout ?: JSONObject.NULL)
                .put("latestHeartRate", latestHeartRate ?: JSONObject.NULL)
                .put("historyWindowEnd",diagnostic.historyWindowEnd ?: JSONObject.NULL)
                .put("historyReceivedAt",diagnostic.historyReceivedAt)
                .put("historyCount",diagnostic.historyCount ?: JSONObject.NULL)
                .put("historyTotal",diagnostic.historyTotal ?: JSONObject.NULL)
                .put("heartRateMeasuredAt", diagnostic.lastHeartRateMeasuredAtEpochMillis ?: JSONObject.NULL)
                .put("heartRateAgeSeconds", diagnostic.heartRateAgeSeconds(now) ?: JSONObject.NULL)
                .put("heartRateFresh", diagnostic.hasFreshHeartRate(now))
                .put("heartRateSource", diagnostic.lastHeartRateSource ?: "")
                .put("zeroTapStatus", zeroTapStatus?.invoke() ?: "unavailable")
                .put("zeroTapReceipt", zeroTapReceipt?.invoke() ?: JSONObject.NULL).toString()); return
        }
        if (method != "POST" || path !in setOf("/zero-tap", "/heart-rate", "/health-heart-rate", "/shortcut-heart-rate", "/workout", "/import", "/connect", "/watch-activity", "/workout-history")) {
            respond(socket, 404, "{\"error\":\"not_found\"}"); return
        }
        // Browser uploads cannot claim to be an authenticated native health integration.
        if (origin != null && path in setOf("/workout", "/heart-rate", "/health-heart-rate", "/shortcut-heart-rate", "/watch-activity", "/workout-history")) {
            respond(socket, 403, "{\"error\":\"use_manual_import_for_browser_data\"}"); return
        }
        require(headers["transfer-encoding"] == null)
        val size = headers["content-length"]?.toIntOrNull() ?: 0
        require(size in 1..8192)
        val body = ByteArray(size)
        var offset = 0
        while (offset < size) {
            val read = input.read(body, offset, size - offset)
            check(read > 0); offset += read
        }
        val rawJson=body.toString(Charsets.UTF_8)
        JsonIngress.requireObject(rawJson,8192,8)
        val json = JSONObject(rawJson)
        if (path == "/zero-tap") {
            require(origin == null)
            val event = ZeroTapEvent.parse(json)
            val callback = onZeroTap ?: error("zero_tap_unavailable")
            callback(event)
            // Receiving an event does not mean settings were applied. Query /status for outcome.
            respond(socket, 202, JSONObject().put("ok", true).put("queued", true)
                .put("applied", false).put("eventId", event.eventId).toString()); return
        }
        val acknowledgement = synchronized(stateLock) {
            if (!running || socket.isClosed) return
            val now = System.currentTimeMillis()
            val requestId = if (json.has("requestId")) json.getString("requestId").also {
                require(it.matches(Regex("[A-Za-z0-9_-]{1,80}")))
            } else null
            val identity = if (json.has("driverId")) {
                require(origin == null && path in setOf("/connect", "/workout", "/health-heart-rate", "/shortcut-heart-rate", "/workout-history"))
                PairedDriverIdentity.create(json.getString("driverId"),
                    json.optString("driverName").takeIf { it.isNotBlank() }, json.optString("source"), now)
            } else null
            fun acceptIdentity() {
                if (identity != null) {
                    diagnostics = diagnostics.copy(identity = identity)
                    if (running) onDriverIdentity?.invoke(identity)
                }
            }
            when (path) {
                "/connect" -> {
                    if(json.has("workoutSyncStatus")) {
                        require(origin==null && json.optString("source")=="health-connect")
                        val status=json.get("workoutSyncStatus")
                        require(status is String && status in setOf("ready","permission_required","query_failed"))
                        diagnostics=diagnostics.copy(workoutSyncStatus=status)
                    }
                    acceptIdentity()
                    // A heartbeat proves reachability, not a new HealthKit/Health Connect sample.
                    val label = connection?.takeIf { it.hasHealthData }?.sourceLabel
                        ?: "휴대폰 연결 · 건강 기록 대기"
                    updateConnection(label, false, now)
                }
                "/workout-history" -> {
                    require(requestId != null)
                    val history=com.example.afterglow.net.WorkoutHistorySnapshot.parse(json,now)
                    checkOrder(StreamKey("health-connect-history",identity?.phoneId),heartMeasuredAt=history.windowEnd)
                    acceptIdentity()
                    diagnostics=diagnostics.copy(historyWindowEnd=history.windowEnd,historyReceivedAt=now,
                        historyCount=history.entries.size,historyTotal=history.totalMatches,workoutSyncStatus="received")
                    if(running) onWorkoutHistory?.invoke(history,identity)
                    updateConnection("Health Connect 24시간 운동 조회 수신",history.entries.isNotEmpty(),now)
                }
                "/watch-activity" -> {
                    require(requestId != null && identity == null)
                    val record = com.example.afterglow.net.WatchActivityRecord.parse(json, now)
                    val previous = diagnostics.latestWatchActivity
                    // Authenticated connection is scoped to the currently selected driver; no identity claims.
                    if (previous != null) {
                        require(record.observedAt > previous.observedAt)
                        require(record.startedAt >= previous.startedAt)
                        if (record.sessionId == previous.sessionId) {
                            require(record.startedAt == previous.startedAt)
                            require(previous.endedAt == null || record.endedAt == previous.endedAt)
                            previous.stepsSinceBaseline?.let { count ->
                                require(record.stepBaselineAt == previous.stepBaselineAt)
                                require(record.stepsSinceBaseline != null && record.stepsSinceBaseline >= count)
                                require(record.stepsMeasuredAt!! >= previous.stepsMeasuredAt!!)
                            }
                        } else require(record.startedAt > previous.observedAt)
                    }
                    diagnostics = diagnostics.copy(latestWatchActivity = record, watchActivityReceivedAt = now,
                        lastNativeHealthAtEpochMillis = now)
                    // Timed walking evidence is diagnostic only; never invent a detected/Health Connect workout
                    // or alter HVAC automatically from a manually selected walking label.
                    updateConnection("Wear OS 걸음·사용자 걷기 세션 수신", true, now)
                }
                "/heart-rate" -> {
                    val bpm = json.getInt("bpm")
                    require(bpm in 40..220 && json.optString("source") == "wear-os-sensor")
                    val measured = json.getLong("measuredAt")
                    require(now - measured in 0..29_999)
                    checkOrder(StreamKey("wear-os-sensor", null), heartMeasuredAt = measured)
                    diagnostics = diagnostics.copy(lastNativeHealthAtEpochMillis = now,
                        lastWatchSensorReceivedAt = now,
                        lastHeartRateMeasuredAtEpochMillis = measured, lastHeartRateReceivedAtEpochMillis = now,
                        lastHeartRateRequestId = requestId, lastHeartRateSource = "wear-os-sensor")
                    val timed = onTimedReading
                    if (running) { if (timed != null) timed(bpm, measured, null) else onReading(bpm) }
                    updateConnection("Wear OS 심박 센서 수신", true, now)
                }
                "/health-heart-rate" -> {
                    require(json.optString("source") == "health-connect" && requestId != null)
                    val rawBpm=json.get("bpm"); val rawMeasured=json.get("measuredAt")
                    require((rawBpm is Int || rawBpm is Long) && (rawMeasured is Int || rawMeasured is Long))
                    val bpm=(rawBpm as Number).toLong(); val measured=(rawMeasured as Number).toLong()
                    require(bpm in 40L..220L && measured in 1..now && now-measured<=600_000)
                    checkOrder(StreamKey("health-connect",identity?.phoneId),heartMeasuredAt=measured)
                    diagnostics=diagnostics.copy(lastNativeHealthAtEpochMillis=now,
                        lastHeartRateMeasuredAtEpochMillis=measured,lastHeartRateReceivedAtEpochMillis=now,
                        lastHeartRateRequestId=requestId,lastHeartRateSource="health-connect")
                    acceptIdentity()
                    if(running) onPhoneReading?.invoke(bpm.toInt(),measured,identity)
                    updateConnection("Health Connect 심박 수신",true,now)
                }
                "/shortcut-heart-rate" -> {
                    require(json.optString("source") == "apple-shortcuts")
                    val bpm = json.getInt("bpm")
                    require(bpm in 40..220)
                    // OffsetDateTime requires a timezone (Z or an explicit UTC offset).
                    val measured = OffsetDateTime.parse(json.getString("measuredAtIso"))
                        .toInstant().toEpochMilli()
                    require(now - measured in 0..600_000)
                    checkOrder(StreamKey("apple-shortcuts", identity?.phoneId), heartMeasuredAt = measured)
                    diagnostics = diagnostics.copy(lastShortcutHealthAtEpochMillis = now,
                        latestShortcutHeartRateRequestId = requestId,
                        lastHeartRateMeasuredAtEpochMillis = measured, lastHeartRateReceivedAtEpochMillis = now,
                        lastHeartRateRequestId = requestId, lastHeartRateSource = "apple-shortcuts")
                    acceptIdentity()
                    if (running) onShortcutReading?.invoke(bpm, measured, identity)
                    updateConnection("iPhone 단축어 심박 수신", true, now)
                }
                else -> {
                    val manual = path == "/import"
                    val source = json.optString("source")
                    val automation = !manual && source == "apple-shortcuts"
                    require(manual || source in setOf("health-connect", "apple-health", "native-wear", "apple-shortcuts"))
                    val ended = if (automation) OffsetDateTime.parse(json.getString("endedAtIso"))
                        .toInstant().toEpochMilli() else json.getLong("endedAt")
                    require(ended in (now - 7 * 86_400_000L)..now)
                    // A user-configured end trigger is not an HKWorkout record with a duration.
                    val minutes = if (automation) null else json.optInt("durationMinutes", 0).takeIf { it in 1..1440 }
                    val measured = json.optLong("bpmMeasuredAt", -1)
                    val age = if (measured in 1..now) (now - measured) / 1000 else null
                    val bpm = json.optInt("bpm", 0).takeIf { it in 40..220 }
                    val heartRate = if (bpm == null || automation) HeartRateReading.missing()
                        else if (manual) HeartRateReading.manual(bpm)
                        else HeartRateReading.watch(bpm, age)
                    val label = if (manual) "직접 가져온 운동 기록" else when (source) {
                        "apple-shortcuts" -> "단축어 운동 종료 이벤트"
                        "health-connect" -> "Health Connect 수신"
                        "apple-health" -> "Apple Health 수신"
                        else -> "워치 앱 수신"
                    }
                    if (!manual) checkOrder(StreamKey(source, identity?.phoneId), workoutEndedAt = ended,
                        heartMeasuredAt = measured.takeIf { !automation && bpm != null && it in 1..now })
                    // Profile selection is dispatched before the workout so its preferences can
                    // be selected by the vehicle UI. Neither callback applies vehicle settings.
                    diagnostics = diagnostics.copy(
                        lastNativeHealthAtEpochMillis = if (manual || automation) diagnostics.lastNativeHealthAtEpochMillis else now,
                        latestWorkout = BridgeWorkoutReceipt(requestId, if (manual) "manual-import" else source, ended, now, manual, automation),
                        lastHeartRateMeasuredAtEpochMillis = if (!manual && !automation && bpm != null && measured in 1..now) measured
                            else diagnostics.lastHeartRateMeasuredAtEpochMillis,
                        lastHeartRateReceivedAtEpochMillis = if (!manual && !automation && bpm != null && measured in 1..now) now
                            else diagnostics.lastHeartRateReceivedAtEpochMillis,
                        lastHeartRateRequestId = if (!manual && !automation && bpm != null && measured in 1..now) requestId
                            else diagnostics.lastHeartRateRequestId,
                        lastHeartRateSource = if (!manual && !automation && bpm != null && measured in 1..now) source
                            else diagnostics.lastHeartRateSource)
                    acceptIdentity()
                    if (running) onWorkout?.invoke(RecentWorkout(normalizeActivityKind(json.optString("kind")), ended,
                        minutes, when { manual -> WorkoutRecordSource.MANUAL_IMPORT
                            automation -> WorkoutRecordSource.PHONE_AUTOMATION else -> WorkoutRecordSource.WATCH_RELAY },
                        label, heartRate, now), identity)
                    updateConnection(label, !manual && !automation, now)
                }
            }
            if (!running) return
            diagnostics = diagnostics.copy(lastAcceptedRequestAtEpochMillis = now)
            JSONObject().put("ok", true).put("receivedAt", now)
                .put("requestId", requestId ?: JSONObject.NULL).toString()
        }
        if (running && !socket.isClosed) respond(socket, 200, acknowledgement)
    }

    private fun updateConnection(label: String, healthData: Boolean, now: Long) {
        if (!running) return
        val previous = connection
        diagnostics = diagnostics.copy(lastAcceptedRequestAtEpochMillis = now)
        connection = BridgeConnection(label, previous?.connectedAtEpochMillis ?: now, now,
            healthData || previous?.hasHealthData == true)
        onConnection?.invoke(connection!!)
    }

    private fun respond(socket: Socket, code: Int, body: String, type: String = "application/json") =
        respondBytes(socket, code, body.toByteArray(Charsets.UTF_8), type)

    private fun respondBytes(socket: Socket, code: Int, bytes: ByteArray, type: String) {
        socket.getOutputStream().apply { write(responseHeader(code, type, bytes.size.toLong()).toByteArray()); write(bytes); flush() }
    }

    private fun responseHeader(code: Int, type: String, length: Long? = null): String {
        val status = when (code) {
            200 -> "OK"; 403 -> "Forbidden"; 404 -> "Not Found"; 409 -> "Conflict"
            503 -> "Service Unavailable"; else -> "Bad Request"
        }
        return "HTTP/1.1 $code $status\r\nContent-Type: $type\r\n" +
            (length?.let { "Content-Length: $it\r\n" } ?: "") +
            "Cache-Control: no-store\r\nReferrer-Policy: no-referrer\r\nX-Content-Type-Options: nosniff\r\n" +
            "Content-Security-Policy: default-src 'self'; script-src 'unsafe-inline'; style-src 'unsafe-inline'; connect-src 'self'; img-src 'self' data:; frame-ancestors 'none'\r\nConnection: close\r\n\r\n"
    }

    private fun streamCompanion(socket: Socket, input: InputStream) {
        // HTTP permits a close-delimited body; AssetInputStream need not expose a length.
        val output = socket.getOutputStream()
        output.write(responseHeader(200, "application/vnd.android.package-archive").toByteArray())
        val buffer = ByteArray(8192)
        var total = 0L
        while (true) {
            val size = input.read(buffer)
            if (size < 0) break
            total += size
            check(total <= 16L * 1024L * 1024L) { "Companion APK exceeds its asset budget" }
            output.write(buffer, 0, size)
        }
        output.flush()
    }

    private data class StreamKey(val source: String, val phoneId: String?)
    private data class StreamOrder(val workoutEndedAt: Long? = null, val heartMeasuredAt: Long? = null)
    private class OutOfOrderDelivery : RuntimeException()

    /** Called only while stateLock is held. Older parallel completions cannot roll a stream back. */
    private fun checkOrder(key: StreamKey, workoutEndedAt: Long? = null, heartMeasuredAt: Long? = null) {
        val previous = streamOrder[key] ?: StreamOrder()
        if (workoutEndedAt != null && previous.workoutEndedAt != null && workoutEndedAt < previous.workoutEndedAt ||
            heartMeasuredAt != null && previous.heartMeasuredAt != null && heartMeasuredAt < previous.heartMeasuredAt) {
            throw OutOfOrderDelivery()
        }
        streamOrder[key] = StreamOrder(workoutEndedAt ?: previous.workoutEndedAt,
            heartMeasuredAt ?: previous.heartMeasuredAt)
    }

    internal data class TransportResources(val activeSockets: Int, val workerThreads: Int, val activeWorkers: Int,
        val queuedTasks: Int, val scheduledDeadlines: Int, val orderStreams: Int)

    internal fun transportResources(): TransportResources {
        val sockets = synchronized(lifecycleLock) { activeSockets.size }
        val streams = synchronized(stateLock) { streamOrder.size }
        return TransportResources(sockets, workers.poolSize, workers.activeCount, workers.queue.size,
            deadlines.queue.size, streams)
    }

    /** Test/diagnostic wait only. The vehicle UI calls stop() without waiting on network threads. */
    internal fun awaitTransportStopped(timeoutMillis: Long): Boolean {
        val end = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(timeoutMillis)
        fun remaining() = ((end - System.nanoTime()) / 1_000_000).coerceAtLeast(0)
        val workerStopped = workers.awaitTermination(remaining(), TimeUnit.MILLISECONDS)
        val deadlinesStopped = deadlines.awaitTermination(remaining(), TimeUnit.MILLISECONDS)
        acceptThread?.takeIf { it !== Thread.currentThread() && it.isAlive }?.let {
            val wait = remaining()
            if (wait > 0) it.join(wait)
        }
        return workerStopped && deadlinesStopped && acceptThread?.isAlive != true &&
            synchronized(lifecycleLock) { activeSockets.isEmpty() }
    }

    fun stop() {
        val (listener, sockets) = synchronized(lifecycleLock) {
            running = false
            val listener = server; server = null
            val sockets = activeSockets.toList()
            activeSockets.clear()
            listener to sockets
        }
        runCatching { listener?.close() }
        sockets.forEach { runCatching { it.close() } }
        workers.shutdownNow()
        deadlines.shutdownNow()
        acceptThread?.interrupt()
        synchronized(stateLock) {
            connection = null; diagnostics = BridgeDiagnostics(); streamOrder.clear()
        }
    }

    companion object {
        internal const val MAX_CONNECTIONS = 4
        internal const val MAX_STREAMS = 16
        private const val THREAD_STACK_HINT_BYTES = 256L * 1024
        private val nextReceiverNumber = AtomicInteger()
    }
}
