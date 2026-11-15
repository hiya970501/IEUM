package com.example.afterglow.wear

import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.net.HttpURLConnection
import java.net.URL
import java.util.concurrent.CancellationException
import java.util.concurrent.ScheduledThreadPoolExecutor
import java.util.concurrent.TimeUnit

/** One live sample, bounded receipt and whole-request deadline. Cancellation
 * closes the connection; bytes already delivered cannot be recalled. */
internal class HeartRateTransport(
    private val deadlineMillis: Long = 8000,
    private val open: (WatchDestination) -> HttpURLConnection = {
        (it.endpoint.url("/heart-rate").openConnection() as javax.net.ssl.HttpsURLConnection).apply {
            sslSocketFactory=com.example.afterglow.net.RelayTlsSocketFactory()
        }
    }
) : AutoCloseable {
    private val lock = Any()
    private val timer = ScheduledThreadPoolExecutor(1).apply { removeOnCancelPolicy = true }
    private var generation = 0L
    private var active: HttpURLConnection? = null
    private var closed = false
    init { require(deadlineMillis in 1..10_000) }

    fun cancel() {
        val previous = synchronized(lock) { generation++; active.also { active = null } }
        runCatching { previous?.disconnect() }
    }
    override fun close() {
        synchronized(lock) { closed = true }
        cancel(); timer.shutdownNow()
    }
    fun post(target: WatchDestination, requestId: String, sample: LiveHeartRate, allowed: () -> Boolean) {
        require(requestId.matches(Regex("[A-Za-z0-9_-]{1,80}")))
        require(sample.bpm in 40..220 && sample.measuredAt > 0 && sample.sensorTimestampNanos > 0)
        val bytes = JSONObject().put("bpm", sample.bpm).put("source", "wear-os-sensor")
            .put("measuredAt", sample.measuredAt).put("requestId", requestId).toString().toByteArray(Charsets.UTF_8)
        send(target, requestId, bytes, allowed)
    }
    fun postActivity(target: WatchDestination, requestId: String, sample: com.example.afterglow.net.WatchActivityRecord, allowed: () -> Boolean) {
        sample.validate(System.currentTimeMillis())
        send(target, requestId, sample.json(requestId).toString().toByteArray(Charsets.UTF_8), allowed)
    }
    private fun send(target: WatchDestination, requestId: String, bytes: ByteArray, allowed: () -> Boolean) {
        require(bytes.size in 1..1024)
        val revision = synchronized(lock) { check(!closed); generation }
        if (!allowed()) throw CancellationException()
        val connection = open(target)
        var deadline: java.util.concurrent.ScheduledFuture<*>? = null
        try {
            connection.apply {
                requestMethod = "POST"; connectTimeout = 3000; readTimeout = 3000
                instanceFollowRedirects = false; doOutput = true
                setRequestProperty("Content-Type", "application/json")
                setRequestProperty("Authorization", "Bearer ${target.token}")
                setFixedLengthStreamingMode(bytes.size)
            }
            synchronized(lock) {
                if (closed || generation != revision || !allowed()) throw CancellationException()
                check(active == null)
                active = connection
                deadline = timer.schedule({
                    val expired = synchronized(lock) {
                        if (active === connection && generation == revision) {
                            generation++; active = null; true
                        } else false
                    }
                    if (expired) runCatching { connection.disconnect() }
                }, deadlineMillis, TimeUnit.MILLISECONDS)
            }
            fun checkCurrent() {
                if (!allowed() || synchronized(lock) { closed || revision != generation || active !== connection })
                    throw CancellationException()
            }
            checkCurrent()
            connection.outputStream.use { checkCurrent(); it.write(bytes) }
            checkCurrent()
            check(connection.responseCode == 200) { "Heart-rate relay rejected" }
            val body = ByteArrayOutputStream()
            connection.inputStream.use { input ->
                val buffer = ByteArray(512)
                while (true) {
                    checkCurrent()
                    val count = input.read(buffer)
                    if (count < 0) break
                    check(body.size() + count <= 4096) { "Receipt too large" }
                    body.write(buffer, 0, count)
                }
            }
            checkCurrent()
            val raw=body.toString(Charsets.UTF_8.name())
            com.example.afterglow.net.BoundedRelayJson.requireObject(raw,4096,4)
            val receipt = JSONObject(raw)
            check(receipt.optBoolean("ok") && receipt.optString("requestId") == requestId) { "Receipt mismatch" }
        } finally {
            deadline?.cancel(false)
            synchronized(lock) { if (active === connection) active = null }
            connection.disconnect()
        }
    }
}
