package com.example.afterglow.wear

import android.Manifest
import android.app.Activity
import android.app.AlertDialog
import android.content.pm.PackageManager
import android.graphics.Color
import android.hardware.*
import android.os.Bundle
import android.os.SystemClock
import android.view.Gravity
import android.view.WindowManager
import android.widget.*
import java.util.UUID
import java.util.concurrent.SynchronousQueue
import java.util.concurrent.ThreadPoolExecutor
import java.util.concurrent.TimeUnit
import java.util.concurrent.RejectedExecutionException
import com.example.afterglow.net.PrivateIpv4

/** Foreground-only live measurement. Does not read Samsung Health workout history. */
class WatchActivity : Activity() {
    private lateinit var sensors: SensorManager
    private lateinit var bpmView: TextView
    private lateinit var status: TextView
    private lateinit var toggle: Button
    private var endpoint = ""
    private var token = ""
    private var measuring = false
    private var resumed = false
    private val session = MeasurementSession()
    private val transport = HeartRateTransport()
    private var permissionRequest: MeasurementSession.Authorization? = null
    private var listener: SensorEventListener? = null
    // Drop rather than queue health samples while a cancelled request drains.
    private val executor = ThreadPoolExecutor(1, 1, 0, TimeUnit.MILLISECONDS,
        SynchronousQueue(), ThreadPoolExecutor.AbortPolicy())
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        sensors = getSystemService(SensorManager::class.java)
        val prefs = getPreferences(MODE_PRIVATE)
        endpoint = prefs.getString("endpoint", "") ?: ""
        val layout = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL; gravity = Gravity.CENTER
            setPadding(32, 45, 32, 45); setBackgroundColor(Color.rgb(16,27,27))
        }
        fun text(value: String, size: Float) = TextView(this).apply {
            text = value; textSize = size; gravity = Gravity.CENTER; setTextColor(Color.rgb(205,245,138))
        }
        layout.addView(text("afterglow", 23f))
        bpmView = text("-- bpm", 34f); layout.addView(bpmView)
        status = text("차량 연결을 설정하세요", 12f); layout.addView(status)
        layout.addView(Button(this).apply { text = "차량 연결 설정"; setOnClickListener { configure() } })
        toggle = Button(this).apply {
            text = "심박 측정 시작"
            setOnClickListener { if (measuring) stopMeasurement() else begin() }
        }
        layout.addView(toggle)
        layout.addView(text("화면을 열어 둔 동안만\n측정 · 전송합니다", 11f))
        setContentView(ScrollView(this).apply { addView(layout) })
    }
    private fun configure() {
        stopMeasurement()
        val fields = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(20,0,20,0) }
        val host = EditText(this).apply { hint = "PC의 Wi-Fi IPv4 (192.168… 등)"; setText(endpoint) }
        val key = EditText(this).apply { hint = "차량 앱의 연결 코드"; setText(token); inputType = 129 }
        fields.addView(host); fields.addView(key)
        val dialog = AlertDialog.Builder(this).setTitle("동일 Wi-Fi 데모 연결")
            .setView(fields).setNegativeButton("취소", null).setPositiveButton("저장", null).create()
        dialog.setOnShowListener {
            dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
                val address = host.text.toString().trim()
                val valid = PrivateIpv4.isCanonicalPrivate(address)
                val secret = key.text.toString().trim()
                when {
                    !valid -> host.error = "앞자리0 없이 사설 IPv4 주소를 입력하세요"
                    !secret.matches(Regex("[0-9a-f]{32}")) -> key.error = "32자리 연결 코드를 입력하세요"
                    else -> {
                        endpoint = address; token = secret
                        session.configure(WatchDestination(address, secret))
                        getPreferences(MODE_PRIVATE).edit().putString("endpoint", endpoint).apply()
                        status.text = "설정 완료 · 측정을 시작하세요"; dialog.dismiss()
                    }
                }
            }
        }
        dialog.show()
    }
    private fun begin() {
        if (!PrivateIpv4.isCanonicalPrivate(endpoint) || token.isEmpty()) { configure(); return }
        if (!resumed) return
        if (permissionRequest != null) { status.text = "센서 권한 확인 중이에요"; return }
        val granted = checkSelfPermission(Manifest.permission.BODY_SENSORS) == PackageManager.PERMISSION_GRANTED
        val ticket = session.requestStart(granted) ?: return
        if (!granted) {
            permissionRequest = ticket
            requestPermissions(arrayOf(Manifest.permission.BODY_SENSORS), 10)
        } else startAuthorized()
    }
    private fun startAuthorized() {
        val ticket = session.takeStart() ?: return
        if (!resumed || checkSelfPermission(Manifest.permission.BODY_SENSORS) != PackageManager.PERMISSION_GRANTED) {
            stopMeasurement(); return
        }
        val heart = sensors.getDefaultSensor(Sensor.TYPE_HEART_RATE)
        if (heart == null) { session.stop(); status.text = "심박 센서가 없는 기기예요"; return }
        val registration = object : SensorEventListener {
            override fun onSensorChanged(event: SensorEvent) = acceptSample(ticket, event)
            override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) {}
        }
        listener = registration
        measuring = runCatching { sensors.registerListener(registration, heart, SensorManager.SENSOR_DELAY_NORMAL) }
            .getOrDefault(false)
        if (measuring) {
            window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
            status.text = "워치를 착용하고 기다려 주세요"; toggle.text = "측정 중지"
        } else { session.stop(); listener = null; status.text = "센서 등록 실패" }
    }
    private fun acceptSample(ticket: MeasurementSession.Measurement, event: SensorEvent) {
        if (!measuring || !session.isCurrent(ticket) || event.accuracy == SensorManager.SENSOR_STATUS_UNRELIABLE) return
        val sample = LiveHeartRate.from(event.values.firstOrNull() ?: return, event.timestamp,
            SystemClock.elapsedRealtimeNanos(), System.currentTimeMillis()) ?: return
        bpmView.text = "${sample.bpm} bpm"
        val transfer = session.claim(ticket, SystemClock.elapsedRealtime()) ?: return
        try {
            executor.execute {
                val result = runCatching {
                    transport.post(ticket.target, UUID.randomUUID().toString(), sample) {
                        session.isCurrent(transfer) && sample.isFresh(SystemClock.elapsedRealtimeNanos())
                    }
                }
                runOnUiThread {
                    if (session.complete(transfer)) status.text =
                        if (result.isSuccess) "차량 수신 확인됨" else "연결 실패 · IP/코드/포트 확인"
                }
            }
        } catch (_: RejectedExecutionException) {
            if (session.complete(transfer)) status.text = "이전 전송 정리 중 · 잠시 후 재시도"
        }
    }
    private fun stopMeasurement(clearIntent: Boolean = true) {
        if (clearIntent) session.stop()
        transport.cancel()
        listener?.let(sensors::unregisterListener); listener = null; measuring = false
        window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        if (::toggle.isInitialized) {
            toggle.text = "심박 측정 시작"; status.text = "측정 중지 · 데이터 저장 없음"; bpmView.text = "-- bpm"
        }
    }
    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, results: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, results)
        if (requestCode != 10) return
        val ticket = permissionRequest ?: return
        permissionRequest = null
        val granted = results.firstOrNull() == PackageManager.PERMISSION_GRANTED
        if (session.permissionResult(ticket, granted)) {
            if (granted) startAuthorized()
            else if (resumed) status.text = "심박 센서 권한이 필요해요"
        } else if (resumed && !measuring) status.text = "권한 확인 후 측정 시작을 다시 눌러 주세요"
    }
    override fun onResume() { super.onResume(); resumed = true; session.foreground(true); startAuthorized() }
    override fun onPause() {
        resumed = false; session.foreground(false); stopMeasurement(clearIntent = false); super.onPause()
    }
    override fun onStop() { session.stop(); super.onStop() }
    override fun onDestroy() { session.stop(); transport.close(); executor.shutdownNow(); super.onDestroy() }
}
