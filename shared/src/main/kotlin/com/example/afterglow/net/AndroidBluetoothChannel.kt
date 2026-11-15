package com.example.afterglow.net

import android.annotation.SuppressLint
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothManager
import android.content.Context

/** Secure authenticated/encrypted RFCOMM only. OS pairing must be completed by the user first. */
@SuppressLint("MissingPermission")
class AndroidBluetoothChannel(context: Context,address: String): BluetoothByteChannel {
    private val socket=run {
        val adapter=checkNotNull(context.getSystemService(BluetoothManager::class.java)?.adapter)
        check(adapter.isEnabled)
        val device=adapter.getRemoteDevice(BluetoothRelayWire.address(address))
        check(device.bondState==BluetoothDevice.BOND_BONDED)
        device.createRfcommSocketToServiceRecord(BluetoothRelayWire.SERVICE_UUID)
    }
    override fun connect() { socket.connect() }
    override val input get()=socket.inputStream
    override val output get()=socket.outputStream
    override fun close() {socket.close()}
}
