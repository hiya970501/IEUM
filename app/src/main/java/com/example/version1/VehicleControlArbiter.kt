package com.example.version1

/** Process-wide admission, separate from the durable journal's request/ACK ownership.
 * A background lease covers admission, writes, readbacks and failure restoration.
 * A screen may return during that lease, but cannot start its own control/restore.
 */
internal class VehicleControlArbiter {
    private data class Screen(val foreground: Boolean, val operating: Boolean, val dock: ((ZeroTapEvent) -> Unit)?)
    private val screens = mutableMapOf<Any, Screen>()
    private var background: Any? = null
    @Synchronized fun screen(owner: Any, foreground: Boolean, operating: Boolean, dock: ((ZeroTapEvent) -> Unit)?) {
        screens[owner] = Screen(foreground, operating, dock.takeIf { foreground })
    }
    @Synchronized fun removeScreen(owner: Any) { screens.remove(owner) }
    @Synchronized fun foregroundDock(): ((ZeroTapEvent) -> Unit)? =
        screens.values.filter { it.foreground }.singleOrNull()?.dock
    @Synchronized fun acquireBackground(): Any? {
        if (background != null || screens.values.any { it.foreground || it.operating }) return null
        return Any().also { background = it }
    }
    @Synchronized fun owns(lease: Any): Boolean = background === lease
    @Synchronized fun release(lease: Any) { if (background === lease) background = null }
    val backgroundActive: Boolean @Synchronized get() = background != null
}

internal val vehicleControlArbiter = VehicleControlArbiter()
