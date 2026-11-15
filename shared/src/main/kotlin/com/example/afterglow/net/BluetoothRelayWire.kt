package com.example.afterglow.net

import org.json.JSONObject
import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.InputStream
import java.io.OutputStream
import java.util.UUID

/** One bounded request/receipt per secure RFCOMM connection. No HTTP/LAN fallback. */
object BluetoothRelayWire {
    val SERVICE_UUID: UUID = UUID.fromString("821b06cf-72cd-4dae-8bac-f582a50a5b93")
    const val MAX_FRAME = 12_288
    val PATHS = setOf("/pair-watch","/connect","/workout","/health-heart-rate","/heart-rate","/watch-activity","/workout-history")
    fun address(value: String): String = value.also { require(it.matches(Regex("[0-9A-F]{2}(:[0-9A-F]{2}){5}"))) }

    class Request(val path: String, val body: String, val token: String? = null) {
        init {
            require(path in PATHS)
            BoundedRelayJson.requireObject(body, if(path=="/pair-watch") 128 else 8192, 8)
            if(path=="/pair-watch") require(token==null)
            else require(token?.matches(Regex("[0-9a-f]{32}"))==true)
        }
        fun json() = JSONObject().put("v",1).put("path",path).put("body",body).apply { token?.let { put("token",it) } }
        override fun toString() = "BluetoothRequest(redacted)"
    }
    class Reply(val status: Int, val body: String) {
        init { require(status in 200..599);BoundedRelayJson.requireObject(body,4096,8) }
        fun json() = JSONObject().put("v",1).put("status",status).put("body",body)
        override fun toString() = "BluetoothReply(redacted)"
    }
    fun read(input: InputStream): JSONObject {
        val stream=DataInputStream(input)
        val count=stream.readInt()
        require(count in 1..MAX_FRAME)
        val bytes=ByteArray(count);stream.readFully(bytes)
        val raw=bytes.toString(Charsets.UTF_8)
        BoundedRelayJson.requireObject(raw,MAX_FRAME,3)
        return JSONObject(raw)
    }
    fun write(output: OutputStream, json: JSONObject) {
        val bytes=json.toString().toByteArray(Charsets.UTF_8)
        require(bytes.size in 1..MAX_FRAME)
        DataOutputStream(output).apply { writeInt(bytes.size);write(bytes);flush() }
    }
    private fun version(json: JSONObject) { require(json.get("v") is Int && json.getInt("v")==1) }
    fun request(json: JSONObject): Request {
        version(json)
        val keys=json.keys().asSequence().toSet()
        require(keys==setOf("v","path","body") || keys==setOf("v","path","body","token"))
        require(json.get("path") is String && json.get("body") is String)
        if(json.has("token")) require(json.get("token") is String)
        return Request(json.getString("path"),json.getString("body"),if(json.has("token")) json.getString("token") else null)
    }
    fun reply(json: JSONObject): Reply {
        version(json)
        require(json.keys().asSequence().toSet()==setOf("v","status","body"))
        require(json.get("status") is Int && json.get("body") is String)
        return Reply(json.getInt("status"),json.getString("body"))
    }
}
