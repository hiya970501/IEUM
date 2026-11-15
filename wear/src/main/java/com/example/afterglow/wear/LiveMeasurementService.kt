package com.example.afterglow.wear

import android.Manifest
import android.app.*
import android.content.Intent
import android.content.pm.PackageManager
import android.hardware.*
import android.os.*
import java.util.UUID
import java.util.concurrent.SynchronousQueue
import java.util.concurrent.ThreadPoolExecutor
import java.util.concurrent.TimeUnit

internal object WatchLiveState {
    @Volatile var running = false
    @Volatile var bpm: Int? = null
    @Volatile var measuredAt = 0L
    @Volatile var deliveredAt = 0L
    @Volatile var message = "측정 시작 전"
}

/** User-started health foreground service. One sensor, one request, no sample history. */
class LiveMeasurementService : Service(), SensorEventListener {
    private lateinit var sensors: SensorManager
    private lateinit var wifi:WatchWifiLink
    private val session = MeasurementSession()
    private var measurement: MeasurementSession.Measurement? = null
    private val transport = HeartRateTransport(open = { wifi.open(it.endpoint.url("/heart-rate")) })
    private val worker = ThreadPoolExecutor(1,1,0,TimeUnit.MILLISECONDS,SynchronousQueue(),ThreadPoolExecutor.AbortPolicy())
    private val main = Handler(Looper.getMainLooper())
    private var wake: PowerManager.WakeLock? = null
    private val renewWake = object : Runnable {
        override fun run() {
            if (!WatchLiveState.running) return
            wake?.let { if (it.isHeld) it.release(); it.acquire(10 * 60 * 1000L) }
            main.postDelayed(this,5 * 60 * 1000L)
        }
    }
    override fun onBind(intent: Intent?) = null
    override fun onCreate() {
        super.onCreate(); sensors=getSystemService(SensorManager::class.java)
        wifi=WatchWifiLink(this)
        getSystemService(NotificationManager::class.java).createNotificationChannel(
            NotificationChannel("live-heart", "워치 심박 전송", NotificationManager.IMPORTANCE_LOW))
    }
    override fun onStartCommand(intent: Intent?, flags:Int, startId:Int): Int {
        if (intent?.action == "STOP") { stopSelf(); return START_NOT_STICKY }
        if (WatchLiveState.running) return START_NOT_STICKY
        // Must enter foreground promptly, even when validation subsequently fails.
        startForeground(1, notification("심박 센서 연결 중"))
        try {
            check(checkSelfPermission(Manifest.permission.BODY_SENSORS)==PackageManager.PERMISSION_GRANTED) { "센서 권한 필요" }
            wifi.start()
            val destination=WatchDestination(intent?.getStringExtra("host").orEmpty(),intent?.getStringExtra("token").orEmpty())
            session.configure(destination); session.foreground(true); session.requestStart(true)
            measurement=session.takeStart() ?: error("측정 시작 실패")
            val sensor=sensors.getDefaultSensor(Sensor.TYPE_HEART_RATE,true) ?: sensors.getDefaultSensor(Sensor.TYPE_HEART_RATE) ?: error("심박 센서 없음")
            check(sensors.registerListener(this,sensor,SensorManager.SENSOR_DELAY_NORMAL)) { "센서 등록 실패" }
            WatchLiveState.running=true; WatchLiveState.bpm=null; WatchLiveState.deliveredAt=0
            WatchLiveState.message="실제 심박 표본 대기"
            if (!sensor.isWakeUpSensor) {
                wake=getSystemService(PowerManager::class.java).newWakeLock(PowerManager.PARTIAL_WAKE_LOCK,"afterglow:heart").apply { setReferenceCounted(false) }
                renewWake.run()
            }
        } catch (e:Exception) { WatchLiveState.message=e.message ?: "측정 시작 실패"; stopSelf() }
        return START_NOT_STICKY
    }
    override fun onSensorChanged(event:SensorEvent) {
        val ticket=measurement ?: return
        if (!WatchLiveState.running || event.accuracy==SensorManager.SENSOR_STATUS_UNRELIABLE) return
        val sample=LiveHeartRate.from(event.values.firstOrNull() ?: return,event.timestamp,SystemClock.elapsedRealtimeNanos(),System.currentTimeMillis()) ?: return
        WatchLiveState.bpm=sample.bpm; WatchLiveState.measuredAt=sample.measuredAt
        val transfer=session.claim(ticket,SystemClock.elapsedRealtime()) ?: return
        runCatching { worker.execute {
            val result=runCatching { transport.post(ticket.target,UUID.randomUUID().toString(),sample) {
                session.isCurrent(transfer) && sample.isFresh(SystemClock.elapsedRealtimeNanos())
            } }
            main.post {
                if (session.complete(transfer)) {
                    if(result.isSuccess) WatchLiveState.deliveredAt=System.currentTimeMillis()
                    WatchLiveState.message=if(result.isSuccess) "차량 수신 확인" else "전송 실패 · 인터넷과 HTTPS 중계 확인"
                    getSystemService(NotificationManager::class.java).notify(1,notification(WatchLiveState.message))
                }
            }
        } }.onFailure { session.complete(transfer) }
    }
    override fun onAccuracyChanged(sensor:Sensor?,accuracy:Int) = Unit
    private fun notification(message:String): Notification {
        val open=PendingIntent.getActivity(this,0,Intent(this,WatchDirectActivity::class.java),PendingIntent.FLAG_IMMUTABLE)
        val stop=PendingIntent.getService(this,1,Intent(this,LiveMeasurementService::class.java).setAction("STOP"),PendingIntent.FLAG_IMMUTABLE)
        return Notification.Builder(this,"live-heart").setSmallIcon(com.example.afterglow.wear.R.drawable.ic_afterglow)
            .setContentTitle("IEUM 심박 전송 중").setContentText(message).setContentIntent(open)
            .setOngoing(true).addAction(Notification.Action.Builder(null,"중지",stop).build()).build()
    }
    override fun onDestroy() {
        measurement=null; session.stop(); session.foreground(false); transport.close(); worker.shutdownNow()
        wifi.close()
        sensors.unregisterListener(this); main.removeCallbacksAndMessages(null)
        wake?.let { if(it.isHeld) it.release() }; wake=null
        WatchLiveState.running=false; WatchLiveState.bpm=null; WatchLiveState.measuredAt=0
        stopForeground(STOP_FOREGROUND_REMOVE); super.onDestroy()
    }
}
