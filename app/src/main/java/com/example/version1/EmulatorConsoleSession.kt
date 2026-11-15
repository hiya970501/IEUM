package com.example.version1

import android.content.Context
import android.content.pm.ApplicationInfo
import android.os.Build
import kotlinx.coroutines.*
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.util.UUID

/** The development console suspends DDS after 3s without a browser session.
 * A debug emulator app must not depend on browser-tab timer throttling for vehicle readback.
 * No console requests are made on production builds or physical vehicles.
 */
class EmulatorConsoleSession(context: Context) {
    private val enabled = context.applicationInfo.flags and ApplicationInfo.FLAG_DEBUGGABLE != 0 &&
        (Build.HARDWARE == "ranchu" || Build.HARDWARE == "goldfish")
    private val client = "afterglow-app-${UUID.randomUUID()}"
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var job: Job? = null

    private fun request(path: String, post: Boolean = false): JSONObject {
        val connection = URL("http://127.0.0.1:8088$path").openConnection() as HttpURLConnection
        try {
            connection.connectTimeout = 1200; connection.readTimeout = 1200
            connection.instanceFollowRedirects = false
            connection.requestMethod = if (post) "POST" else "GET"
            check(connection.responseCode == 200) { "가상 콘솔 연결 응답 없음" }
            return connection.inputStream.bufferedReader().use { reader ->
                val result = StringBuilder(); val buffer = CharArray(4096)
                while (true) { val count = reader.read(buffer); if (count < 0) break
                    check(result.length + count <= 131072); result.append(buffer, 0, count) }
                JSONObject(result.toString())
            }
        } finally { connection.disconnect() }
    }
    fun start() {
        if (!enabled || job?.isActive == true) return
        job = scope.launch {
            while (isActive) {
                runCatching { request("/browser/session?client=$client", true) }
                delay(800)
            }
        }
    }
    suspend fun requireReady() {
        if (!enabled) return
        start()
        withContext(Dispatchers.IO) {
            var lastFailure: Exception? = null
            repeat(3) {
                try {
                    request("/browser/session?client=$client", true)
                    val state = request("/vehicle/state")
                    check(isControlReady(state)) { "가상 콘솔 공조 송수신 준비 중" }
                    return@withContext
                } catch (cancelled: CancellationException) { throw cancelled }
                catch (error: Exception) { lastFailure = error }
                delay(300)
            }
            throw IllegalStateException("가상 차량 제어 연결이 준비되지 않았어요. 적용하지 않고 대기합니다.", lastFailure)
        }
    }
    fun close() {
        if (!enabled) return
        job?.cancel(); job = null
        scope.launch {
            runCatching { request("/browser/session?client=$client&closing=1", true) }
            scope.cancel()
        }
    }
    companion object {
        internal fun isControlReady(state: JSONObject): Boolean {
            val hvac = state.optJSONObject("hvac") ?: return false
            return state.optJSONObject("gear")?.optBoolean("bridge_ready") == true &&
                hvac.optBoolean("emulator_active") &&
                hvac.optJSONObject("publisher_ready")?.optBoolean("all") == true &&
                hvac.optJSONObject("subscription_ready")?.optBoolean("all") == true
        }
    }
}
