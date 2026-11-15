package com.example.version1

import androidx.test.ext.junit.runners.AndroidJUnit4
import com.example.afterglow.net.WatchActivityRecord
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.net.Socket

/** Owned synthetic loopback data on actual Android, never physical watch verification. */
@RunWith(AndroidJUnit4::class)
class GalaxyWatchIngressDeviceTest {
    @Test fun bluetoothWireUsesRealAndroidValidatorAndMatchingReceiptWithoutRadioClaim() {
        val receiver=WatchReceiver(port=0) {}
        var count:Int?=null
        receiver.onWorkoutHistory={snapshot,_->count=snapshot.entries.size}
        receiver.start()
        try {
            fun wire(request:com.example.afterglow.net.BluetoothRelayWire.Request):com.example.afterglow.net.BluetoothRelayWire.Reply {
                val bytes=java.io.ByteArrayOutputStream().apply {com.example.afterglow.net.BluetoothRelayWire.write(this,request.json())}
                val decoded=com.example.afterglow.net.BluetoothRelayWire.request(com.example.afterglow.net.BluetoothRelayWire.read(java.io.ByteArrayInputStream(bytes.toByteArray())))
                return BluetoothHealthGateway.forward(receiver.boundPort,decoded)
            }
            val code=receiver.openWatchPairing()
            val paired=wire(com.example.afterglow.net.BluetoothRelayWire.Request("/pair-watch",JSONObject().put("code",code).toString()))
            assertEquals(200,paired.status)
            val token=JSONObject(paired.body).getString("token")
            assertEquals(403,wire(com.example.afterglow.net.BluetoothRelayWire.Request("/pair-watch",JSONObject().put("code",code).toString())).status)
            val now=System.currentTimeMillis()-100
            val history=com.example.afterglow.net.WorkoutHistorySnapshot(now,0,emptyList()).json("owned-bt-history")
            assertEquals(403,wire(com.example.afterglow.net.BluetoothRelayWire.Request("/workout-history",history.toString(),"0".repeat(32))).status)
            assertNull(count)
            val accepted=wire(com.example.afterglow.net.BluetoothRelayWire.Request("/workout-history",history.toString(),token))
            assertEquals(200,accepted.status)
            assertEquals("owned-bt-history",JSONObject(accepted.body).getString("requestId"))
            assertEquals(0,count)
            assertFalse(receiver.connection!!.hasHealthData)
        } finally {receiver.stop();assertTrue(receiver.awaitTransportStopped(5000))}
    }
    @Test fun completeHistoryIncludingEmptyDoesNotInventLiveHealthOrWorkout() {
        var pulse=false
        var workout=false
        var received: com.example.afterglow.net.WorkoutHistorySnapshot?=null
        val receiver=WatchReceiver(port=0) { pulse=true }
        receiver.onWorkout={_,_->workout=true}
        receiver.onWorkoutHistory={snapshot,_->received=snapshot}
        receiver.start()
        fun post(body:JSONObject):String {
            val bytes=body.toString().toByteArray(Charsets.UTF_8)
            return Socket("127.0.0.1",receiver.boundPort).use { socket ->
                socket.soTimeout=4000
                val header="POST /workout-history HTTP/1.1\r\nHost: 127.0.0.1\r\nAuthorization: Bearer ${receiver.token}\r\nContent-Type: application/json\r\nContent-Length: ${bytes.size}\r\n\r\n"
                socket.getOutputStream().apply { write(header.toByteArray());write(bytes);flush() }
                socket.getInputStream().bufferedReader(Charsets.UTF_8).readText()
            }
        }
        try {
            val now=System.currentTimeMillis()-100
            val entry=com.example.afterglow.net.WorkoutHistoryEntry(999,"other",now-84_000_000,now-83_000_000,"owned.synthetic",true)
            val history=com.example.afterglow.net.WorkoutHistorySnapshot(now,1,listOf(entry))
            assertTrue(post(history.json("owned-history")).startsWith("HTTP/1.1 200"))
            assertEquals(listOf(entry),received?.entries)
            assertFalse(pulse);assertFalse(workout)
            val empty=com.example.afterglow.net.WorkoutHistorySnapshot(now+1,0,emptyList())
            assertTrue(post(empty.json("owned-empty")).startsWith("HTTP/1.1 200"))
            assertEquals(0,received?.totalMatches)
            assertFalse(pulse);assertFalse(workout)
            assertTrue(post(empty.json("owned-incomplete").put("complete",false)).startsWith("HTTP/1.1 400"))
        } finally {
            receiver.stop();assertTrue(receiver.awaitTransportStopped(5000))
            assertEquals(0,receiver.transportResources().activeSockets)
        }
    }
    @Test fun activityAndPulseStaySeparateAndStopClearsOwnedReceipt() {
        var deliveredPulse: Int? = null
        var inventedWorkout = false
        val receiver=WatchReceiver(port=0) { deliveredPulse=it }
        receiver.onWorkout={_,_-> inventedWorkout=true }
        receiver.start()
        fun post(path:String,body:JSONObject):String {
            val bytes=body.toString().toByteArray(Charsets.UTF_8)
            return Socket("127.0.0.1",receiver.boundPort).use { socket ->
                socket.soTimeout=4000
                val header="POST $path HTTP/1.1\r\nHost: 127.0.0.1\r\nAuthorization: Bearer ${receiver.token}\r\nContent-Type: application/json\r\nContent-Length: ${bytes.size}\r\n\r\n"
                socket.getOutputStream().apply { write(header.toByteArray());write(bytes);flush() }
                socket.getInputStream().bufferedReader(Charsets.UTF_8).readText()
            }
        }
        try {
            val now=System.currentTimeMillis()
            val record=WatchActivityRecord("11111111-1111-4111-8111-111111111111",now-5000,now-1,now-2,7,now-4000,now-100)
            val reply=post("/watch-activity",record.json("owned-activity"))
            assertTrue(reply.startsWith("HTTP/1.1 200"))
            val ack=JSONObject(reply.substringAfter("\r\n\r\n"))
            assertTrue(ack.getBoolean("ok"));assertEquals("owned-activity",ack.getString("requestId"))
            assertTrue(ack.getLong("receivedAt")>=now)
            assertEquals(record,receiver.diagnostics.latestWatchActivity)
            assertNull(deliveredPulse);assertFalse(inventedWorkout)
            assertTrue(post("/watch-activity",record.json("replayed")).startsWith("HTTP/1.1 400"))
            val heart=JSONObject().put("source","wear-os-sensor").put("bpm",81)
                .put("measuredAt",System.currentTimeMillis()).put("requestId","owned-heart")
            assertTrue(post("/heart-rate",heart).startsWith("HTTP/1.1 200"))
            assertEquals(81,deliveredPulse);assertEquals(record,receiver.diagnostics.latestWatchActivity)
            assertFalse(inventedWorkout);assertNull(receiver.diagnostics.latestWorkout)
        } finally {
            receiver.stop();assertTrue(receiver.awaitTransportStopped(5000))
            assertNull(receiver.diagnostics.latestWatchActivity)
            assertEquals(0,receiver.transportResources().activeSockets)
        }
    }
}
