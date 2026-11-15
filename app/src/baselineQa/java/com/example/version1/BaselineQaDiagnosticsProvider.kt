package com.example.version1

import android.Manifest
import android.app.Activity
import android.app.Application
import android.content.ContentProvider
import android.content.ContentValues
import android.content.Context
import android.content.pm.ApplicationInfo
import android.database.Cursor
import android.database.MatrixCursor
import android.net.Uri
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.Process
import android.os.SystemClock
import org.json.JSONObject
import org.json.JSONArray
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import java.lang.ref.WeakReference
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference

/** Not compiled into Debug/Release. Read-only same-process QA facts for the shell,
 * avoiding instrumentation's conflicting optimized Kotlin class loader. Never returns
 * health, profile names/IDs, journal payloads, credentials or vehicle setting values. */
@OptIn(ExperimentalCoroutinesApi::class)
class BaselineQaDiagnosticsProvider : ContentProvider() {
    private var activity = WeakReference<MainActivity>(null)
    private var started = false
    private val handler = Handler(Looper.getMainLooper())

    override fun onCreate(): Boolean {
        val ctx = checkNotNull(context)
        check(ctx.packageName == "com.example.version1.baselineqa")
        check(ctx.applicationInfo.flags and ApplicationInfo.FLAG_DEBUGGABLE == 0)
        (ctx.applicationContext as Application).registerActivityLifecycleCallbacks(object : Application.ActivityLifecycleCallbacks {
            override fun onActivityCreated(a: Activity, state: Bundle?) {
                if (a is MainActivity) activity = WeakReference(a)
            }
            override fun onActivityStarted(a: Activity) { if (a === activity.get()) started = true }
            override fun onActivityStopped(a: Activity) { if (a === activity.get()) started = false }
            override fun onActivityDestroyed(a: Activity) {
                if (a === activity.get()) { started = false; activity.clear() }
            }
            override fun onActivityResumed(a: Activity) = Unit
            override fun onActivityPaused(a: Activity) = Unit
            override fun onActivitySaveInstanceState(a: Activity, state: Bundle) = Unit
        })
        return true
    }

    override fun query(uri: Uri, projection: Array<out String>?, selection: String?, selectionArgs: Array<out String>?, sortOrder: String?): Cursor {
        val ctx = checkNotNull(context)
        ctx.enforceCallingOrSelfPermission(Manifest.permission.DUMP, "Shell QA diagnostics only")
        require(uri.path == "/state" && selection == null && selectionArgs == null && sortOrder == null)
        val answer = AtomicReference<String>()
        val done = CountDownLatch(1)
        val sample = Runnable {
            val value = JSONObject().put("package", ctx.packageName).put("pid", Process.myPid())
                .put("elapsedMs", SystemClock.elapsedRealtime()).put("cpuMs", Process.getElapsedCpuTime())
                .put("nativeReady", false).put("pendingKnownEmpty", false)
                .put("vehicleCommandsIssuedByProbe", false)
            try {
                val memory = checkNotNull(ProcessMemoryReader.read())
                value.put("rssBytes", memory.rssBytes).put("pssBytes", memory.pssBytes).put("hwmBytes", memory.peakRssBytes)
                value.put("nativeReady", NativeRenderLifecycle.nativeReady).put("activityStarted", started)
                value.put("debuggable", false).put("researchCatalogEmpty", VehicleVisualCatalog.load(ctx).isEmpty())
                value.put("onlineProcessorsContext", Runtime.getRuntime().availableProcessors())
                val owner = activity.get()
                if (owner != null) {
                    fun field(name: String) = MainActivity::class.java.getDeclaredField(name).apply { isAccessible = true }.get(owner)
                    val controller = field("controller") as? PleosRecoveryController
                    value.put("controllerReady", controller != null)
                    if (controller != null) value.put("pendingKnownEmpty", !controller.hasPending)
                    if (controller != null) {
                        val preparation = PleosRecoveryController::class.java.getDeclaredField("gearPreparation")
                            .apply { isAccessible = true }.get(controller)
                        @Suppress("UNCHECKED_CAST")
                        val tasks = SdkApiPreparation::class.java.getDeclaredField("tasks")
                            .apply { isAccessible = true }.get(preparation) as List<Deferred<Unit>>
                        value.put("gearMetadataTasks", JSONArray().apply {
                            tasks.forEach { task ->
                                val state = JSONObject().put("completed", task.isCompleted).put("cancelled", task.isCancelled)
                                if (task.isCompleted) task.getCompletionExceptionOrNull()?.let { failure ->
                                    // Exception types and code locations only; never exception messages/arguments.
                                    state.put("failureType", failure.javaClass.name)
                                    state.put("stackSymbols", JSONArray(failure.stackTrace.take(8).map {
                                        "${it.className}.${it.methodName}:${it.lineNumber}"
                                    }))
                                    state.put("causes", JSONArray().apply {
                                        var cause = failure.cause
                                        repeat(4) {
                                            val current = cause ?: return@repeat
                                            put(JSONObject().put("type", current.javaClass.name)
                                                .put("stackSymbols", JSONArray(current.stackTrace.take(6).map {
                                                    "${it.className}.${it.methodName}:${it.lineNumber}"
                                                })))
                                            cause = current.cause
                                        }
                                    })
                                }
                                put(state)
                            }
                        })
                    }
                    value.put("automaticActive", field("active") as Boolean)
                    value.put("foreground", field("foreground") as Boolean)
                    value.put("startupBlocked", field("startupBlocked") as Boolean)
                    value.put("parkedConfirmed", field("parked") as Boolean)
                    value.put("uxAllowed", (field("safety") as CarSafetyGate).allowed)
                    value.put("actualParkReady", MainActivity::class.java.getDeclaredMethod("safeToAct")
                        .apply { isAccessible = true }.invoke(owner) as Boolean)
                    val scene = field("scene") as? PremiumCabinView
                    val gate = scene?.let { PremiumCabinView::class.java.getDeclaredField("presentation")
                        .apply { isAccessible = true }.get(it) as PreviewPresentationGate }
                    value.put("actualPreviewPresented", gate != null && !gate.covered && !gate.failed)
                    value.put("sceneAttached", scene?.isAttachedToWindow == true)
                } else {
                    value.put("foreground", false).put("controllerReady", false).put("actualPreviewPresented", false)
                }
                if (!value.getBoolean("pendingKnownEmpty")) {
                    // Same canonical empty/first-install representation used by the
                    // real recovery journal. An unreadable/nonempty value stays unknown.
                    val journal = ctx.getSharedPreferences("recovery_restore", Context.MODE_PRIVATE)
                    val raw = journal.getString("pending", null)
                    value.put("pendingKnownEmpty", (raw == null && !journal.contains("pending")) || raw == "[]")
                }
            } catch (error: Throwable) {
                value.put("probeFailureType", error.javaClass.simpleName)
                value.put("pendingKnownEmpty", false)
            } finally {
                answer.set(value.toString()); done.countDown()
            }
        }
        if (!handler.post(sample) || !done.await(500, TimeUnit.MILLISECONDS)) {
            handler.removeCallbacks(sample)
            return MatrixCursor(arrayOf("json")).apply {
                addRow(arrayOf(JSONObject().put("probeFailureType", "MainProbeTimeout")
                    .put("nativeReady", false).put("pendingKnownEmpty", false).toString()))
            }
        }
        return MatrixCursor(arrayOf("json")).apply { addRow(arrayOf(answer.get())) }
    }
    override fun getType(uri: Uri) = "vnd.android.cursor.item/vnd.ieum.baselineqa"
    override fun insert(uri: Uri, values: ContentValues?): Uri? = throw UnsupportedOperationException("Read only")
    override fun update(uri: Uri, values: ContentValues?, selection: String?, selectionArgs: Array<out String>?) = throw UnsupportedOperationException("Read only")
    override fun delete(uri: Uri, selection: String?, selectionArgs: Array<out String>?) = throw UnsupportedOperationException("Read only")
}
