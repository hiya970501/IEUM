package com.example.version1

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.PowerManager
import androidx.core.content.ContextCompat

/** Public screen-power lifecycle, including a retained top-resumed Activity during screen-off.
 * Does not request wake locks, change power policy, or simulate an OEM suspend acknowledgement.
 */
internal class ScreenPowerMonitor(private val context: Context, private val changed: (Boolean) -> Unit) : AutoCloseable {
    private var closed = false
    private val power = context.getSystemService(PowerManager::class.java)
    private val receiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) { refresh() }
    }
    init {
        ContextCompat.registerReceiver(context, receiver, IntentFilter().apply {
            addAction(Intent.ACTION_SCREEN_OFF); addAction(Intent.ACTION_SCREEN_ON)
        }, ContextCompat.RECEIVER_NOT_EXPORTED)
        refresh()
    }
    fun refresh() { if (!closed) changed(power.isInteractive) }
    override fun close() {
        if (closed) return
        closed = true
        context.unregisterReceiver(receiver)
    }
}
