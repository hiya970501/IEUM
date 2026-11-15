package com.example.version1

import android.app.Activity
import android.os.SystemClock
import androidx.test.core.app.ActivityScenario

/** Test teardown cannot count a detached TextureView as a destroyed native engine. */
internal fun <T : Activity> ActivityScenario<T>.closeAndAwaitNativeRelease() {
    val context = androidx.test.platform.app.InstrumentationRegistry.getInstrumentation().targetContext
    val caller = Thread.currentThread()
    val finished = java.util.concurrent.CountDownLatch(1)
    val phase = java.util.concurrent.atomic.AtomicReference("activityScenario.close")
    val stacks = org.json.JSONArray()
    val watcher = Thread({
        repeat(10) { second ->
            if (finished.await(1, java.util.concurrent.TimeUnit.SECONDS)) return@Thread
            stacks.put(org.json.JSONObject().put("afterSeconds", second + 1).put("phase", phase.get())
                .put("nativeReady", NativeRenderLifecycle.nativeReady)
                .put("testStack", caller.stackTrace.joinToString("\n"))
                .put("mainStack", android.os.Looper.getMainLooper().thread.stackTrace.joinToString("\n")))
            java.io.File(context.filesDir, "native-close-stack.json").writeText(stacks.toString(2))
        }
    }, "NativeCloseDiagnostic")
    watcher.start()
    try {
        close()
        phase.set("awaitActualNativeRelease")
        val deadline = SystemClock.elapsedRealtime() + 20000
        while (!NativeRenderLifecycle.nativeReady && SystemClock.elapsedRealtime() < deadline) Thread.sleep(20)
        check(NativeRenderLifecycle.nativeReady) { "Native engine destruction was not confirmed" }
        phase.set("complete")
    } finally {
        finished.countDown()
        watcher.join(1500)
        java.io.File(context.filesDir, "native-close-result.json").writeText(org.json.JSONObject()
            .put("phase", phase.get()).put("nativeReady", NativeRenderLifecycle.nativeReady).toString(2))
    }
}
