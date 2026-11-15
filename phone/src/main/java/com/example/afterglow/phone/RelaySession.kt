package com.example.afterglow.phone

import com.example.afterglow.net.PrivateIpv4
import com.example.afterglow.net.SecureRelayEndpoint

/** Live destination; approved persistence is separately encrypted. String rendering redacts secrets. */
class RelayDestination private constructor(val token: String, val bluetoothAddress: String?, private val httpsEndpoint: SecureRelayEndpoint?) {
    constructor(address: String,token: String):this(token,null,SecureRelayEndpoint(address))
    val endpoint get()=checkNotNull(httpsEndpoint) { "HTTPS destination required" }
    val host get()=if(bluetoothAddress!=null) "Bluetooth 차량" else endpoint.host
    init {
        require(token.matches(Regex("[0-9a-f]{32}")))
    }
    override fun toString() = "RelayDestination(redacted)"

    companion object {
        fun bluetooth(address: String,token: String)=RelayDestination(token,com.example.afterglow.net.BluetoothRelayWire.address(address),null)
        fun isPrivateIpv4(host: String): Boolean = PrivateIpv4.isCanonicalPrivate(host)
    }
}

/** Authorization belongs to a consented pairing. Transfers also belong to one foreground epoch. */
class RelaySession {
    class Authorization internal constructor(internal val revision: Long, val destination: RelayDestination)
    class Transfer internal constructor(internal val revision: Long, internal val epoch: Long,
        val destination: RelayDestination)

    private var revision = 0L
    private var epoch = 0L
    private var destination: RelayDestination? = null
    private var consented = false
    private var authorized = false
    private var foreground = false
    @Synchronized fun hasPairing(): Boolean = destination != null
    @Synchronized fun authorizedFor(target:RelayDestination):Boolean = destination===target && consented && authorized

    @Synchronized fun pair(target: RelayDestination) {
        revision++; destination = target; consented = false; authorized = false
    }
    @Synchronized fun consent(value: Boolean) {
        if (value != consented) { revision++; consented = value; authorized = false }
    }
    @Synchronized fun foreground(value: Boolean) {
        if (foreground != value) { epoch++; foreground = value }
    }
    @Synchronized fun beginAuthorization(): Authorization? =
        destination?.takeIf { foreground && consented }?.let { Authorization(revision, it) }

    @Synchronized fun canRequestPermission(ticket: Authorization): Boolean = foreground && consented &&
        ticket.revision == revision && ticket.destination === destination

    /** Permission UI may temporarily stop the Activity. It cannot start background transfers. */
    @Synchronized fun authorize(ticket: Authorization): Boolean {
        if (!consented || ticket.revision != revision || ticket.destination !== destination) return false
        authorized = true
        return true
    }
    @Synchronized fun transfer(): Transfer? = destination?.takeIf {
        foreground && consented && authorized
    }?.let { Transfer(revision, epoch, it) }

    @Synchronized fun isCurrent(ticket: Transfer): Boolean = foreground && consented && authorized &&
        ticket.revision == revision && ticket.epoch == epoch && ticket.destination === destination

    @Synchronized fun stop() { revision++; authorized = false }
    @Synchronized fun clear() { revision++; epoch++; destination = null; consented = false; authorized = false }
}
