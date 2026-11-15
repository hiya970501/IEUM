package com.example.version1

import android.Manifest
import android.app.*
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import android.util.Log
import androidx.core.app.ServiceCompat
import ai.pleos.playground.vehicle.constant.control.VehicleGear
import kotlinx.coroutines.*
import java.util.concurrent.atomic.AtomicBoolean

/** Explicitly enabled dock-only foreground service. No health callbacks, scene or audio.
 * No boot receiver or automatic background start; the visible screen starts it.
 */
class ZeroTapBackgroundService : Service() {
    private val scope = MainScope()
    private val store by lazy { ZeroTapStore(this) }
    private val profiles by lazy { DriverProfileStore(this) }
    private lateinit var controller: PleosRecoveryController
    private lateinit var safety: CarSafetyGate
    private var reader: DriverSeatReader? = null
    private var driver: DriverSeatBinding? = null
    private var receiver: WatchReceiver? = null
    private var console: EmulatorConsoleSession? = null
    private var operation: Job? = null
    private var restoreRetry: Job? = null
    private var lease: Any? = null
    private var stopping = false
    private var destroying = false
    private var storageBlocked = false
    private val deliveryPending = AtomicBoolean()

    override fun onCreate() {
        super.onCreate()
        if (runCatching {
                listOf("driver_profiles", "zero_tap").forEach { ProtectedPreferences.get(this, it).all }
            }.isFailure) {
            storageBlocked = true; stopSelf(); return
        }
        safety = CarSafetyGate(this) { }
        controller = PleosRecoveryController(this, { driver }, { safety.refreshAllowed() },
            { Log.i("AfterglowDock", it) }, { console?.requireReady() ?: error("거치 제어 연결 없음") })
        reader = DriverSeatReader(this) { driver = (it as? DriverSeatState.Available)?.binding }
        reader!!.start()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (storageBlocked) { stopSelf(); return START_NOT_STICKY }
        if (intent?.action == STOP || !enabled(this) || !notificationsAllowed(this)) {
            runCatching { disableAll(this) }; requestStop(); return START_NOT_STICKY
        }
        if (stopping || destroying) return START_NOT_STICKY
        try {
            val manager = getSystemService(NotificationManager::class.java)
            manager.createNotificationChannel(NotificationChannel(CHANNEL, "휴대폰 거치 연결", NotificationManager.IMPORTANCE_LOW))
            val open = PendingIntent.getActivity(this, 0, Intent(this, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
            val stop = PendingIntent.getService(this, 1, Intent(this, javaClass).setAction(STOP), PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
            val notification = Notification.Builder(this, CHANNEL).setSmallIcon(android.R.drawable.ic_menu_myplaces)
                .setContentTitle("제로탭 거치 연결 켜짐").setContentText("등록 휴대폰 · P단에서만 기본 설정 적용")
                .setContentIntent(open).setOngoing(true).addAction(Notification.Action.Builder(null, "거치 연결 끄기", stop).build()).build()
            ServiceCompat.startForeground(this, 412, notification,
                if (Build.VERSION.SDK_INT >= 29) ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE else 0)
            if (receiver == null) {
                receiver = WatchReceiver(PairingSecretStore(this).getOrCreate(), PORT, dockOnly = true) { }.apply {
                    zeroTapStatus = { store.status(profiles.load().selectedProfileId) }
                    zeroTapReceipt = { store.receipt(profiles.load().selectedProfileId) }
                    onZeroTap = { event ->
                        if (!deliveryPending.compareAndSet(false, true)) throw DockReceiverBusy()
                        scope.launch { try { accept(event) } finally { deliveryPending.set(false) } }
                    }
                    start()
                }
            }
            running = true
        } catch (error: Exception) {
            Log.w("AfterglowDock", "Dock listener unavailable: ${error.javaClass.simpleName}")
            runCatching { disableAll(this) }; requestStop()
        }
        return START_NOT_STICKY
    }

    private fun accept(event: ZeroTapEvent) {
        if (stopping || destroying || !enabled(this)) return
        if (!notificationsAllowed(this)) { runCatching { disableAll(this) }; requestStop(); return }
        // All admission runs on Main. Foreground screens retain their existing P/UX/health
        // boundaries; another foreground screen or operation can never be bypassed.
        vehicleControlArbiter.foregroundDock()?.let { it(event); return }
        if (controller.hasPending) return
        val settings = profiles.load()
        val profile = store.profileFor(event, settings.profiles) ?: return
        if (store.config(profile)?.optBoolean("backgroundEnabled") != true) return
        val owned = vehicleControlArbiter.acquireBackground() ?: return
        lease = owned
        operation = scope.launch {
            var admitted = false
            var applied = false
            try {
                console = EmulatorConsoleSession(this@ZeroTapBackgroundService)
                console!!.requireReady()
                // Fresh actual gear at admission. No deferred replay when gear/driver/UX is unknown.
                check(safety.refreshAllowed() && driver != null && controller.gear() == VehicleGear.GEAR_PARK) { "P단 또는 운전석 확인 필요" }
                val baseline = store.beginEvent(profile, event, allowed(profile, owned), System.currentTimeMillis())
                admitted = true
                profiles.save(settings.select(profile))
                val count = controller.applyZeroTapBaseline(baseline) { allowed(profile, owned) }
                applied = true
                var memoryMessage = ""
                var memoryFailure = false
                val memory = store.config(profile)?.optInt("memorySlot", -1) ?: -1
                if (memory >= 0) {
                    try {
                        controller.requestZeroTapMemory(memory, { allowed(profile, owned) }) {
                            scope.launch {
                                memoryFailure = true
                                publish(profile, event.eventId, "기본 설정 $count 항목 확인 · 메모리 시트 요청 실패")
                            }
                        }
                        memoryMessage = if (memoryFailure) " · 메모리 시트 요청 실패" else " · 메모리 $memory 요청(이동 완료 미확인)"
                    } catch (cancelled: CancellationException) { throw cancelled
                    } catch (_: Exception) { memoryMessage = " · 메모리 시트 요청 실패" }
                }
                publish(profile, event.eventId, "기본 설정 $count 항목 확인$memoryMessage")
            } catch (cancelled: CancellationException) {
                if (admitted && !applied) {
                    publish(profile, event.eventId, "거치 적용 중단 · 복원 확인 필요")
                    withContext(NonCancellable) { runCatching { withTimeout(15_000) { controller.restore() } } }
                }
                throw cancelled
            } catch (error: Exception) {
                if (admitted && !applied) {
                    publish(profile, event.eventId, "적용 중단 · ${error.message}")
                    runCatching { withTimeout(15_000) { controller.restore() } }
                }
            } finally {
                console?.close(); console = null
                vehicleControlArbiter.release(owned); lease = null; operation = null
                if (admitted && !applied && !stopping && !destroying && controller.hasPending) {
                    scheduleRestore(profile, event.eventId)
                }
                if (stopping && !destroying) stopSelf()
            }
        }
    }

    private fun allowed(profile: String, owned: Any): Boolean = !stopping && !destroying &&
        notificationsAllowed(this) &&
        vehicleControlArbiter.owns(owned) && store.config(profile)?.let {
            it.optBoolean("enabled") && it.optBoolean("backgroundEnabled")
        } == true && profiles.load().profiles.any { it.id == profile }

    private fun publish(profile: String, event: String, value: String) {
        // Result persistence failure must not turn a confirmed baseline into a rollback.
        runCatching { store.recordResult(profile, event, value) }
            .onFailure { Log.w("AfterglowDock", "Dock result persistence unconfirmed") }
    }
    private fun scheduleRestore(profile: String, event: String) {
        if (restoreRetry?.isActive == true) return
        restoreRetry = scope.launch {
            try {
                while (isActive && !stopping && enabled(this@ZeroTapBackgroundService) && controller.hasPending) {
                    delay(30_000)
                    if (!controller.hasPending || stopping) break
                    // A foreground screen performs its normal P-only restoration. Never race it.
                    val owned = vehicleControlArbiter.acquireBackground() ?: continue
                    lease = owned
                    try {
                        console = EmulatorConsoleSession(this@ZeroTapBackgroundService)
                        console!!.requireReady()
                        if (withTimeout(15_000) { controller.restore() }) {
                            publish(profile, event, "거치 적용 중단 · 이전 설정 복원 확인")
                        }
                    } catch (cancelled: CancellationException) { throw cancelled }
                    catch (_: Exception) { /* Preserve the durable journal and bounded retry. */ }
                    finally {
                        console?.close(); console = null
                        vehicleControlArbiter.release(owned); lease = null
                    }
                }
            } finally { restoreRetry = null }
        }
    }
    private fun requestStop() {
        stopping = true; running = false; receiver?.stop(); receiver = null
        if (operation == null) stopSelf()
        // In-flight stillAllowed becomes false. It restores only its durable owned writes.
    }
    override fun onBind(intent: Intent?): IBinder? = null
    override fun onDestroy() {
        if (storageBlocked) { running = false; scope.cancel(); super.onDestroy(); return }
        destroying = true; stopping = true; running = false
        receiver?.stop(); receiver = null
        // Unexpected service destruction may cancel a dispatched request. Keep the journal;
        // bounded cleanup owns its lease until restore/release finishes. Process kill relies
        // on the durable journal and next foreground restoration instead.
        scope.launch {
            operation?.cancelAndJoin()
            restoreRetry?.cancelAndJoin()
            lease?.let { owned ->
                runCatching { withTimeout(15_000) { if (controller.hasPending) controller.restore() } }
                vehicleControlArbiter.release(owned)
            }
            reader?.close(); reader = null; controller.release(); safety.close(); console?.close()
            scope.cancel()
        }
        super.onDestroy()
    }

    companion object {
        const val PORT = 8766
        private const val CHANNEL = "zero_tap_connected_device"
        private const val STOP = "com.example.version1.STOP_DOCK"
        @Volatile var running = false
            private set
        fun enabled(context: Context): Boolean = runCatching {
            val store = ZeroTapStore(context)
            DriverProfileStore(context).load().profiles.any { store.config(it.id)?.optBoolean("backgroundEnabled") == true }
        }.getOrDefault(false)
        fun disableAll(context: Context) {
            val store = ZeroTapStore(context)
            DriverProfileStore(context).load().profiles.forEach { profile -> store.config(profile.id)?.let {
                if (it.optBoolean("backgroundEnabled")) store.save(profile.id, it.put("backgroundEnabled", false))
            } }
        }
        fun notificationsAllowed(context: Context): Boolean = Build.VERSION.SDK_INT < 33 ||
            context.checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED
        fun start(context: Context) {
            check(notificationsAllowed(context)) { "거치 연결 알림을 허용해 주세요" }
            context.startForegroundService(Intent(context, ZeroTapBackgroundService::class.java))
        }
        fun stop(context: Context) {
            try { disableAll(context) }
            finally { if (running) context.startService(Intent(context, ZeroTapBackgroundService::class.java).setAction(STOP)) }
        }
    }
}
