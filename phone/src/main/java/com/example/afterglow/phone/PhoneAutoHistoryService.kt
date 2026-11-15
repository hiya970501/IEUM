package com.example.afterglow.phone

import android.Manifest
import android.app.*
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import androidx.health.connect.client.HealthConnectClient
import androidx.health.connect.client.HealthConnectFeatures
import androidx.health.connect.client.permission.HealthPermission
import androidx.health.connect.client.records.ExerciseSessionRecord
import androidx.health.connect.client.records.metadata.Metadata
import androidx.health.connect.client.request.ReadRecordsRequest
import androidx.health.connect.client.time.TimeRangeFilter
import com.example.afterglow.net.WorkoutHistoryEntry
import kotlinx.coroutines.*
import kotlinx.coroutines.sync.withLock
import org.json.JSONObject
import java.time.Instant
import java.util.UUID

/** Validation helper: explicitly enabled connected-device FGS. No health storage, boot start or hidden grants. */
class PhoneAutoHistoryService:Service() {
    private val scope=CoroutineScope(SupervisorJob()+Dispatchers.Main.immediate)
    private val transport=RelayTransport()
    private val bluetooth by lazy {PhoneBluetoothTransport(this)}
    @Volatile private var alive=true
    private var job:Job?=null
    override fun onBind(intent:Intent?):IBinder?=null
    override fun onStartCommand(intent:Intent?,flags:Int,startId:Int):Int {
        if(intent?.action==STOP || !enabled(this) || !notificationsAllowed(this)) {stop(this);stopSelf();return START_NOT_STICKY}
        if(job!=null) return START_NOT_STICKY
        try {
            val manager=getSystemService(NotificationManager::class.java)
            manager.createNotificationChannel(NotificationChannel(CHANNEL,"IEUM 운동 기록 자동연동",NotificationManager.IMPORTANCE_LOW))
            val open=PendingIntent.getActivity(this,0,Intent(this,PhoneActivity::class.java),PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
            val stop=PendingIntent.getService(this,1,Intent(this,javaClass).setAction(STOP),PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
            val notification=Notification.Builder(this,CHANNEL).setSmallIcon(android.R.drawable.ic_popup_sync)
                .setContentTitle("IEUM 운동 기록 자동연동")
                .setContentText("승인한 차량에 연결되면 운동 기록을 갱신합니다")
                .setContentIntent(open).setOngoing(true)
                .addAction(Notification.Action.Builder(null,"자동연동 중지",stop).build()).build()
            if(Build.VERSION.SDK_INT>=29) startForeground(901,notification,ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE)
            else startForeground(901,notification)
        } catch(_:Exception) {stop(this);stopSelf();return START_NOT_STICKY}
        PhoneHistorySyncControl.serviceRunning=true
        PhoneHistorySyncControl.status="운동 기록 자동연동 켜짐"
        job=scope.launch {
            var failures=0
            while(isActive && alive && enabled(this@PhoneAutoHistoryService)) {
                try {
                    if(!notificationsAllowed(this@PhoneAutoHistoryService)) {stop(this@PhoneAutoHistoryService);stopSelf();break}
                    if(!PhoneHistorySyncControl.visible) PhoneHistorySyncControl.gate.withLock {
                        if(!PhoneHistorySyncControl.visible && alive) withTimeout(20_000) {syncHistory()}
                    }
                    failures=0
                } catch(error:CancellationException) {
                    transport.cancel();bluetooth.cancel()
                    if(error !is TimeoutCancellationException) throw error
                    failures=(failures+1).coerceAtMost(3)
                    PhoneHistorySyncControl.status="차량 연결 대기 · 자동 재시도 중"
                } catch(_:Exception) {
                    transport.cancel();bluetooth.cancel()
                    failures=(failures+1).coerceAtMost(3)
                    PhoneHistorySyncControl.status="차량 연결 대기 · 자동 재시도 중"
                }
                delay(if(failures==0)20_000 else 20_000L*failures)
            }
        }
        return START_NOT_STICKY
    }
    private suspend fun syncHistory() {
        val client=HealthConnectClient.getOrCreate(this)
        val granted=client.permissionController.getGrantedPermissions()
        if(BACKGROUND !in granted || HealthPermission.getReadPermission(ExerciseSessionRecord::class) !in granted || !backgroundSupported(client)) {
            PhoneHistorySyncControl.status="운동 자동연동 읽기 권한 필요"
            stop(this);stopSelf();return
        }
        val target=withContext(Dispatchers.IO) {PhoneConnectionStore(this@PhoneAutoHistoryService).load()}
        if(target==null) {
            PhoneHistorySyncControl.status="S23 차량 연결 승인 필요"
            stop(this);stopSelf();return
        }
        val identity=withContext(Dispatchers.IO) {AndroidPhoneIdentityStore.get(this@PhoneAutoHistoryService).load()}
        suspend fun post(path:String,json:JSONObject,requestId:String) {
            val body=json.also {identity?.appendTo(it)}.toString()
            withContext(Dispatchers.IO) {
                val coroutine=currentCoroutineContext()
                val allowed={alive && enabled(this@PhoneAutoHistoryService) && coroutine.isActive && !PhoneHistorySyncControl.visible}
                if(target.bluetoothAddress!=null) bluetooth.post(target,requestId,body,path,allowed)
                else transport.post(target,requestId,body,path,allowed)
            }
        }
        val request="auto-connect-${UUID.randomUUID()}"
        // Confirm the approved vehicle is reachable BEFORE reading health records.
        post("/connect",JSONObject().put("source","health-connect").put("requestId",request).put("workoutSyncStatus","ready"),request)
        val now=Instant.now();val history=PagedWorkoutHistory(now.toEpochMilli())
        while(history.canReadNextPage && alive && !PhoneHistorySyncControl.visible) {
            val page=client.readRecords(ReadRecordsRequest(ExerciseSessionRecord::class,
                TimeRangeFilter.between(now.minusSeconds(86_400),now),pageSize=1000,pageToken=history.nextPageToken))
            currentCoroutineContext().ensureActive()
            history.acceptPage(page.records.asSequence().map {record->
                WorkoutHistoryEntry(record.exerciseType,kind(record.exerciseType),record.startTime.toEpochMilli(),record.endTime.toEpochMilli(),
                    record.metadata.dataOrigin.packageName,record.metadata.recordingMethod==Metadata.RECORDING_METHOD_MANUAL_ENTRY)
            },page.pageToken)
        }
        if(!alive || PhoneHistorySyncControl.visible) return
        val snapshot=checkNotNull(history.snapshot()) {"Incomplete history"}
        val requestId="auto-history-${UUID.randomUUID()}"
        post("/workout-history",snapshot.json(requestId),requestId)
        PhoneHistorySyncControl.status="운동 기록 자동 갱신 · 차량 수신 확인"
        // Receipt evidence only: never log health records, device identities or credentials.
        android.util.Log.i("IeumAutoHistory","event=history_receipt_confirmed background=${!PhoneHistorySyncControl.visible}")
    }
    override fun onDestroy() {
        alive=false;transport.cancel();bluetooth.cancel();scope.cancel()
        PhoneHistorySyncControl.serviceRunning=false
        stopForeground(STOP_FOREGROUND_REMOVE);super.onDestroy()
    }
    companion object {
        const val BACKGROUND="android.permission.health.READ_HEALTH_DATA_IN_BACKGROUND"
        private const val CHANNEL="ieum_auto_history"
        private const val STOP="com.example.afterglow.phone.STOP_AUTO_HISTORY"
        fun enabled(context:Context)=context.getSharedPreferences("auto_history",0).getBoolean("enabled",false)
        fun notificationsAllowed(context:Context)=
            (Build.VERSION.SDK_INT<33 || context.checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS)==PackageManager.PERMISSION_GRANTED) &&
                context.getSystemService(NotificationManager::class.java).areNotificationsEnabled()
        fun backgroundSupported(client:HealthConnectClient)=client.features.getFeatureStatus(HealthConnectFeatures.FEATURE_READ_HEALTH_DATA_IN_BACKGROUND)==HealthConnectFeatures.FEATURE_STATUS_AVAILABLE
        fun start(context:Context) {
            check(notificationsAllowed(context))
            check(context.getSharedPreferences("auto_history",0).edit().putBoolean("enabled",true).commit())
            try {context.startForegroundService(Intent(context,PhoneAutoHistoryService::class.java))}
            catch(error:Exception) {stop(context);throw error}
        }
        fun stop(context:Context) {
            context.getSharedPreferences("auto_history",0).edit().putBoolean("enabled",false).commit()
            context.stopService(Intent(context,PhoneAutoHistoryService::class.java))
        }
        internal fun kind(type:Int)=when(type) {
            ExerciseSessionRecord.EXERCISE_TYPE_RUNNING,ExerciseSessionRecord.EXERCISE_TYPE_RUNNING_TREADMILL->"run"
            ExerciseSessionRecord.EXERCISE_TYPE_SWIMMING_POOL,ExerciseSessionRecord.EXERCISE_TYPE_SWIMMING_OPEN_WATER->"swim"
            ExerciseSessionRecord.EXERCISE_TYPE_HIKING->"hike"
            ExerciseSessionRecord.EXERCISE_TYPE_WALKING->"walk"
            ExerciseSessionRecord.EXERCISE_TYPE_BIKING,ExerciseSessionRecord.EXERCISE_TYPE_BIKING_STATIONARY->"cycle"
            ExerciseSessionRecord.EXERCISE_TYPE_STRENGTH_TRAINING->"strength"
            else->"other"
        }
    }
}
