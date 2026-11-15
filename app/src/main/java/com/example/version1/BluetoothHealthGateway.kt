package com.example.version1

import com.example.afterglow.net.BluetoothRelayWire
import java.io.DataInputStream
import java.net.Socket

/** Adapts authenticated Bluetooth frames to the SAME vehicle validator; loopback IPC only. */
internal object BluetoothHealthGateway {
    fun forward(port: Int, request: BluetoothRelayWire.Request, track: (Socket?)->Unit = {}): BluetoothRelayWire.Reply {
        require(port in 1..65535)
        val socket=Socket()
        try {
            track(socket)
            socket.connect(java.net.InetSocketAddress("127.0.0.1",port),1000)
            socket.soTimeout=3000
            val bytes=request.body.toByteArray(Charsets.UTF_8)
            val header="POST ${request.path} HTTP/1.1\r\nHost: 127.0.0.1\r\nContent-Type: application/json\r\n"+
                (request.token?.let {"Authorization: Bearer $it\r\n"} ?: "")+"Content-Length: ${bytes.size}\r\n\r\n"
            socket.getOutputStream().apply {write(header.toByteArray(Charsets.US_ASCII));write(bytes);flush()}
            val input=DataInputStream(socket.getInputStream())
            var headerBytes=0
            fun line(): String {
                val value=StringBuilder()
                while(value.length<1024) {
                    val next=input.read();check(next>=0);check(++headerBytes<=4096)
                    if(next==10) return value.toString().trimEnd('\r')
                    value.append(next.toChar())
                }
                error("Invalid receipt")
            }
            val status=line().split(' ')
            check(status.size>=2 && status[0]=="HTTP/1.1")
            val headers=mutableMapOf<String,String>()
            while(true) {
                val value=line();if(value.isEmpty()) break
                check(':' in value)
                val name=value.substringBefore(':').lowercase();check(name !in headers)
                headers[name]=value.substringAfter(':').trim()
            }
            check("transfer-encoding" !in headers)
            val length=checkNotNull(headers["content-length"]?.toIntOrNull());check(length in 1..4096)
            val body=ByteArray(length);input.readFully(body)
            return BluetoothRelayWire.Reply(status[1].toInt(),body.toString(Charsets.UTF_8))
        } finally { runCatching {socket.close()};track(null) }
    }
}
