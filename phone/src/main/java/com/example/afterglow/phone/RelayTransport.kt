package com.example.afterglow.phone

import kotlinx.coroutines.CancellationException
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.net.HttpURLConnection
import java.net.URL
import javax.net.ssl.HttpsURLConnection
import com.example.afterglow.net.SecureRelayEndpoint
import com.example.afterglow.net.RelayTlsSocketFactory
import com.example.afterglow.net.BoundedRelayJson

/** One bounded foreground request. Cancellation cannot retract bytes already delivered. */
internal class RelayTransport(private val deadlineMillis:Long = 8000,
    private val open: (SecureRelayEndpoint, String) -> HttpURLConnection = { target, path ->
    (target.url(path).openConnection() as HttpsURLConnection).apply { sslSocketFactory=RelayTlsSocketFactory() }
}) {
    private val lock = Any()
    private var generation = 0L
    private var inFlight: HttpURLConnection? = null
    init { require(deadlineMillis in 1..10_000) }

    private fun armDeadline(timer:java.util.Timer,connection:HttpURLConnection,revision:Long) {
        timer.schedule(object:java.util.TimerTask() {
            override fun run() {
                val expired=synchronized(lock) {
                    if(generation==revision && inFlight===connection) {
                        generation++;inFlight=null;true
                    } else false
                }
                if(expired) runCatching { connection.disconnect() }
            }
        },deadlineMillis)
    }

    fun cancel() {
        val connection = synchronized(lock) { generation++; inFlight.also { inFlight = null } }
        runCatching { connection?.disconnect() }
    }
    fun pair(endpoint:SecureRelayEndpoint,code:String,allowed:()->Boolean):RelayDestination {
        require(code.matches(Regex("[0-9]{6}")))
        val revision=synchronized(lock) { generation }
        if(!allowed()) throw CancellationException()
        val connection=open(endpoint,"/pair-watch")
        val deadline=java.util.Timer(true)
        try {
            synchronized(lock) {
                if(revision!=generation || !allowed()) throw CancellationException()
                check(inFlight==null);inFlight=connection
            }
            armDeadline(deadline,connection,revision)
            fun current() { if(!allowed() || synchronized(lock){revision!=generation || inFlight!==connection}) throw CancellationException() }
            val bytes=JSONObject().put("code",code).toString().toByteArray(Charsets.UTF_8)
            connection.apply {
                requestMethod="POST";connectTimeout=3500;readTimeout=3500;instanceFollowRedirects=false;doOutput=true
                setRequestProperty("Content-Type","application/json");setFixedLengthStreamingMode(bytes.size)
            }
            current();connection.outputStream.use { current();it.write(bytes) };current()
            check(connection.responseCode==200)
            val body=ByteArrayOutputStream()
            connection.inputStream.use { input ->
                val buffer=ByteArray(256)
                while(true) {current();val n=input.read(buffer);if(n<0)break;check(body.size()+n<=1024);body.write(buffer,0,n)}
            }
            current()
            val raw=body.toString(Charsets.UTF_8.name());BoundedRelayJson.requireObject(raw,1024,2)
            val response=JSONObject(raw);check(response.optBoolean("ok"))
            return RelayDestination(endpoint.address,response.getString("token"))
        } finally {
            deadline.cancel();synchronized(lock){if(inFlight===connection)inFlight=null};connection.disconnect()
        }
    }

    fun post(target: RelayDestination, requestId: String, json: String, path: String = "/workout", allowed: () -> Boolean) {
        require(path in setOf("/workout", "/connect", "/health-heart-rate", "/workout-history"))
        val revision = synchronized(lock) { generation }
        if (!allowed()) throw CancellationException()
        val connection = open(target.endpoint, path)
        val deadline=java.util.Timer(true)
        try {
            val bytes = json.toByteArray(Charsets.UTF_8)
            require(bytes.size in 1..8192)
            connection.apply {
                requestMethod = "POST"; connectTimeout = 3500; readTimeout = 3500
                instanceFollowRedirects = false; doOutput = true
                setRequestProperty("Content-Type", "application/json")
                setRequestProperty("Authorization", "Bearer ${target.token}")
                setFixedLengthStreamingMode(bytes.size)
            }
            synchronized(lock) {
                if (revision != generation || !allowed()) throw CancellationException()
                check(inFlight == null)
                inFlight = connection
            }
            armDeadline(deadline,connection,revision)
            fun checkCurrent() {
                if (!allowed() || synchronized(lock) { revision != generation || inFlight !== connection })
                    throw CancellationException()
            }
            checkCurrent()
            connection.outputStream.use { checkCurrent(); it.write(bytes) }
            checkCurrent()
            check(connection.responseCode == 200) { "Relay request rejected" }
            val body = ByteArrayOutputStream()
            connection.inputStream.use { input ->
                val buffer = ByteArray(1024)
                while (true) {
                    checkCurrent()
                    val count = input.read(buffer)
                    if (count < 0) break
                    check(body.size() + count <= 4096) { "Relay receipt too large" }
                    body.write(buffer, 0, count)
                }
            }
            checkCurrent()
            val raw=body.toString(Charsets.UTF_8.name())
            BoundedRelayJson.requireObject(raw,4096,4)
            val receipt = JSONObject(raw)
            check(receipt.optBoolean("ok") && receipt.optString("requestId") == requestId) {
                "Relay receipt does not match request"
            }
        } finally {
            deadline.cancel()
            synchronized(lock) { if (inFlight === connection) inFlight = null }
            connection.disconnect()
        }
    }
}
