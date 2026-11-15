package com.example.version1

import android.annotation.SuppressLint
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothManager
import android.bluetooth.BluetoothServerSocket
import android.bluetooth.BluetoothSocket
import android.content.Context
import com.example.afterglow.net.BluetoothRelayWire
import java.net.Socket
import java.util.Timer
import java.util.TimerTask

/** Explicit foreground-only listener. One client, bounded 8-second request, no fallback or logging of health. */
@SuppressLint("MissingPermission")
internal class BluetoothHealthServer(private val context: Context,private val port: Int,private val status:(String)->Unit) {
    private val lock=Any()
    @Volatile private var stopped=false
    val isStopped get()=stopped
    private var listener: BluetoothServerSocket?=null
    private var client: BluetoothSocket?=null
    private var ipc: Socket?=null
    private val deadline=Timer("ieum-bt-server-deadline",true)
    private var started=false
    fun start() {
        synchronized(lock) {check(!started && !stopped);started=true}
        Thread({
            try {
                val adapter=checkNotNull(context.getSystemService(BluetoothManager::class.java)?.adapter)
                check(adapter.isEnabled)
                val server=adapter.listenUsingRfcommWithServiceRecord("IEUM Health",BluetoothRelayWire.SERVICE_UUID)
                synchronized(lock) {if(stopped) {server.close();return@Thread};listener=server}
                status("Bluetooth 수신 대기 · 아직 건강 기록 수신 전")
                while(!stopped) {
                    val socket=server.accept()
                    val accepted=synchronized(lock) {if(stopped) {socket.close();false} else {client=socket;true}}
                    if(!accepted) break
                    val timeout=object: TimerTask() {override fun run() {runCatching {socket.close()};synchronized(lock){if(client===socket) runCatching {ipc?.close()}}}}
                    try {
                        deadline.schedule(timeout,8000)
                        check(socket.remoteDevice.bondState==BluetoothDevice.BOND_BONDED)
                        val request=BluetoothRelayWire.request(BluetoothRelayWire.read(socket.inputStream))
                        val reply=BluetoothHealthGateway.forward(port,request) { value ->
                            synchronized(lock) {if(stopped) {value?.close();error("Stopped")};ipc=value}
                        }
                        if(!stopped) BluetoothRelayWire.write(socket.outputStream,reply.json())
                        if(!stopped) status(if(reply.status==200) "Bluetooth 요청 처리 완료 · 건강 수신은 기록별 확인" else "Bluetooth 요청 거절 · 코드·권한·표본 시각을 확인하세요")
                    } catch(_: Exception) {
                        if(!stopped) status("Bluetooth 전송 실패 · 연결과 코드를 확인하세요")
                    } finally {
                        timeout.cancel();deadline.purge();runCatching {socket.close()}
                        synchronized(lock) {if(client===socket) client=null;runCatching {ipc?.close()};ipc=null}
                    }
                }
            } catch(_: Exception) {if(!stopped) status("Bluetooth 수신을 열지 못했어요. 기기 지원·권한·켜짐 상태를 확인하세요.")}
            finally {stop()}
        },"ieum-bt-server").apply {isDaemon=true;start()}
    }
    fun stop() {
        synchronized(lock) {
            stopped=true;runCatching {listener?.close()};listener=null
            runCatching {client?.close()};client=null;runCatching {ipc?.close()};ipc=null
        }
        deadline.cancel()
    }
}
