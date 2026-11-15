package com.example.afterglow.wear

import android.Manifest
import android.app.Activity
import android.content.pm.PackageManager
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.os.SystemClock
import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextView
import java.util.UUID
import java.util.concurrent.SynchronousQueue
import java.util.concurrent.ThreadPoolExecutor
import java.util.concurrent.TimeUnit

/** A small explicit foreground test, no Samsung history reads, daily totals, or hidden sessions. */
internal class WalkingPanel(
    private val owner: Activity, layout: LinearLayout, private val wifi: WatchWifiLink,
    private val destination: () -> WatchDestination?, private val foreground: () -> Boolean
) : SensorEventListener, AutoCloseable {
    private val sensors = owner.getSystemService(SensorManager::class.java)
    private val text = TextView(owner).apply {
        setTextColor(android.graphics.Color.rgb(184,239,215)); textSize=13f
        gravity=android.view.Gravity.CENTER; text="걸음·걷기 세션: 시작 전"
    }
    private val start = Button(owner).apply { text="걷기 측정 시작"; setOnClickListener { start() } }
    private val end = Button(owner).apply { text="걷기 종료·전송"; setOnClickListener { end() } }
    private val send = Button(owner).apply { text="활동 현재값 전송"; setOnClickListener { transmit() } }
    private val worker = ThreadPoolExecutor(1,1,0,TimeUnit.MILLISECONDS,SynchronousQueue(),ThreadPoolExecutor.AbortPolicy())
    private val transport = HeartRateTransport(open={ wifi.open(it.endpoint.url("/watch-activity")) })
    private var observation: WalkingObservation? = null
    private var ended = false
    private var busy = false
    @Volatile private var revision = 0L
    private var sensorMessage = ""
    val active: Boolean get() = (observation != null && !ended) || busy
    init {
        layout.addView(text); layout.addView(start);layout.addView(end);layout.addView(send)
        layout.addView(TextView(owner).apply {
            text="센서 기준점 이후 걸음·사용자 걷기 타이머\n삼성 헬스 이력·오늘 총걸음 아님\n걷기는 이 화면을 열어 둔 동안만 측정"
            textSize=11f;gravity=android.view.Gravity.CENTER;setTextColor(android.graphics.Color.LTGRAY)
        })
        controls()
    }
    fun start() {
        if (!foreground() || active) return
        if (owner.checkSelfPermission(Manifest.permission.ACTIVITY_RECOGNITION)!=PackageManager.PERMISSION_GRANTED) {
            owner.requestPermissions(arrayOf(Manifest.permission.ACTIVITY_RECOGNITION),12);return
        }
        revision++; ended=false
        observation=WalkingObservation(System.currentTimeMillis(),SystemClock.elapsedRealtime())
        val sensor=sensors.getDefaultSensor(Sensor.TYPE_STEP_COUNTER)
        val registered=sensor?.let { sensors.registerListener(this,it,SensorManager.SENSOR_DELAY_NORMAL) } == true
        sensorMessage=if(registered) "걸음 센서 표본 대기" else "걸음 센서 미지원 · 걷기 타이머만 기록"
        text.text="$sensorMessage\n사용자 걷기 타이머 시작";controls()
    }
    private fun end() {
        if (busy || ended || !foreground()) return
        val current=observation ?: return
        runCatching { current.snapshot(System.currentTimeMillis(),SystemClock.elapsedRealtime(),end=true) }
            .onSuccess { ended=true;sensors.unregisterListener(this);text.text="걷기 타이머 종료 · 전송 준비";controls();transmit() }
            .onFailure { text.text=it.message ?: "기록 시각 확인 필요" }
    }
    private fun transmit() {
        if (!foreground() || busy) return
        val current=observation ?: return
        val target=destination() ?: run { text.text="상단 차량 연결을 먼저 설정하세요";return }
        val record=runCatching { current.snapshot(System.currentTimeMillis(),SystemClock.elapsedRealtime()) }
            .getOrElse { text.text=it.message ?: "기록 시각 확인 필요";return }
        val ticket=revision;busy=true;text.text="활동 전송 중 · 차량 확인 대기";controls()
        runCatching { worker.execute {
            val result=runCatching { transport.postActivity(target,UUID.randomUUID().toString(),record) { foreground() && ticket==revision } }
            owner.runOnUiThread {
                if(ticket!=revision || !foreground()) return@runOnUiThread
                busy=false
                text.text=if(result.isSuccess) "활동 차량 수신 확인\n${record.stepsSinceBaseline?.let { "기준점 이후 ${it}걸음" } ?: "걸음 표본 없음"} · ${if(ended) "걷기 종료" else "걷기 진행"}"
                    else "활동 전송 실패 · HTTPS 연결 확인\n종료 기록은 화면을 나가기 전 재전송 가능"
                controls()
            }
        } }.onFailure { busy=false;text.text="이전 활동 전송 정리 중 · 잠시 후 재시도";controls() }
    }
    override fun onSensorChanged(event: SensorEvent) {
        if (!foreground() || ended || event.sensor.type!=Sensor.TYPE_STEP_COUNTER || event.accuracy==SensorManager.SENSOR_STATUS_UNRELIABLE) return
        val current=observation ?: return
        current.counter(event.values.firstOrNull() ?: return,event.timestamp,SystemClock.elapsedRealtimeNanos(),System.currentTimeMillis())
        if(!busy) runCatching { current.snapshot(System.currentTimeMillis(),SystemClock.elapsedRealtime()) }
            .onSuccess { text.text=it.stepsSinceBaseline?.let { count -> "센서 기준점 이후 ${count}걸음\n걷기 타이머 진행 중" } ?: sensorMessage }
            .onFailure { text.text=it.message ?: "센서 확인 필요" }
    }
    override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) = Unit
    private fun controls() {
        start.isEnabled=!active; end.isEnabled=observation!=null && !ended && !busy
        send.isEnabled=observation!=null && !busy
    }
    fun pause() {
        revision++;transport.cancel();sensors.unregisterListener(this)
        observation=null;ended=false;busy=false
        text.text="걸음·걷기 세션 중지 · 새 측정을 시작하세요";controls()
    }
    override fun close() { pause();transport.close();worker.shutdownNow() }
}
