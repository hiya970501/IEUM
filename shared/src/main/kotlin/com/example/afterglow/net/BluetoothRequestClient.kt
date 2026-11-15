package com.example.afterglow.net

import java.io.Closeable
import java.io.InputStream
import java.io.OutputStream
import java.io.InterruptedIOException
import java.util.Timer
import java.util.TimerTask

interface BluetoothByteChannel : Closeable {
    fun connect()
    val input: InputStream
    val output: OutputStream
}

/** Closing the socket bounds connect/read/write. At most one channel, never retained health data. */
class BluetoothRequestClient(private val open: (String)->BluetoothByteChannel, private val deadlineMillis: Long=8000) {
    init { require(deadlineMillis in 1..10_000) }
    private val lock=Any()
    private var generation=0L
    private var active: BluetoothByteChannel?=null
    fun cancel() {
        val old=synchronized(lock) { generation++;active.also { active=null } }
        runCatching { old?.close() }
    }
    fun request(address: String, request: BluetoothRelayWire.Request, allowed: ()->Boolean): BluetoothRelayWire.Reply {
        BluetoothRelayWire.address(address)
        val revision=synchronized(lock) { generation }
        if(!allowed()) throw InterruptedIOException("Transfer stopped")
        val channel=open(address)
        val timer=Timer("ieum-bt-deadline",true)
        try {
            synchronized(lock) {
                if(revision!=generation || !allowed()) throw InterruptedIOException("Transfer stopped")
                check(active==null);active=channel
            }
            timer.schedule(object: TimerTask() {
                override fun run() {
                    val expired=synchronized(lock) {
                        if(generation==revision && active===channel) {generation++;active=null;true} else false
                    }
                    if(expired) runCatching { channel.close() }
                }
            },deadlineMillis)
            fun current() {
                if(!allowed() || synchronized(lock) { revision!=generation || active!==channel })
                    throw InterruptedIOException("Transfer stopped")
            }
            current();channel.connect();current()
            BluetoothRelayWire.write(channel.output,request.json());current()
            val reply=BluetoothRelayWire.reply(BluetoothRelayWire.read(channel.input));current()
            return reply
        } finally {
            timer.cancel();synchronized(lock) {if(active===channel) active=null};runCatching {channel.close()}
        }
    }
}
