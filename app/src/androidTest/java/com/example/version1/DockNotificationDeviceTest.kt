package com.example.version1

import android.app.NotificationManager
import android.content.Intent
import android.os.SystemClock
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

/** Invoke the actual notification PendingIntent, not the service implementation directly.
 * Requires the explicitly enrolled harness phone; never disables a real user's registration.
 */
@RunWith(AndroidJUnit4::class)
class DockNotificationDeviceTest {
    @Test fun actualNotificationStopActionDisablesDockingAndRemovesItsNotification() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val context = instrumentation.targetContext
        val fixturePhone = InstrumentationRegistry.getArguments().getString("fixturePhoneId")
        require(!fixturePhone.isNullOrBlank()) { "A harness-owned phone fixture is required" }
        val store = ZeroTapStore(context)
        require(store.config("default")?.optString("phoneId") == fixturePhone) { "Preserve another phone's registration" }
        require(store.config("default")?.optBoolean("backgroundEnabled") == true) { "Enable the harness fixture through UI first" }
        require(ZeroTapBackgroundService.notificationsAllowed(context)) { "Approve notification permission through UI first" }
        require(context.getSharedPreferences("recovery_restore", 0).getString("pending", "[]") == "[]")

        context.startActivity(Intent(context, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        val manager = context.getSystemService(NotificationManager::class.java)
        val deadline = SystemClock.elapsedRealtime() + 20_000
        var action: android.app.Notification.Action? = null
        while (SystemClock.elapsedRealtime() < deadline) {
            action = manager.activeNotifications.singleOrNull { it.id == 412 }?.notification?.actions
                ?.singleOrNull { it.title.toString() == "거치 연결 끄기" }
            if (action != null) break
            SystemClock.sleep(100)
        }
        assertNotNull("An actual ongoing dock notification must expose its stop action", action)
        action!!.actionIntent.send()
        val stopDeadline = SystemClock.elapsedRealtime() + 10_000
        while (SystemClock.elapsedRealtime() < stopDeadline &&
            (ZeroTapBackgroundService.running || manager.activeNotifications.any { it.id == 412 } ||
                store.config("default")?.optBoolean("backgroundEnabled") == true)) {
            SystemClock.sleep(100)
        }
        assertFalse(store.config("default")!!.getBoolean("backgroundEnabled"))
        assertFalse(ZeroTapBackgroundService.running)
        assertFalse(manager.activeNotifications.any { it.id == 412 })
        assertFalse(vehicleControlArbiter.backgroundActive)
        assertEquals("[]", context.getSharedPreferences("recovery_restore", 0).getString("pending", null))
    }
}
