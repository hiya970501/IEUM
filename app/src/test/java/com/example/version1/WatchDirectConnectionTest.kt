package com.example.version1

import org.junit.Assert.*
import org.junit.Test
import org.json.JSONObject
import java.net.Socket
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

class WatchDirectConnectionTest {
    private fun request(port:Int,path:String,body:JSONObject,token:String?=null,origin:String?=null): Pair<Int,JSONObject> {
        Socket("127.0.0.1",port).use { socket ->
            socket.soTimeout=4000
            val bytes=body.toString().toByteArray()
            val header="POST $path HTTP/1.1\r\nHost: 127.0.0.1:$port\r\nContent-Type: application/json\r\nContent-Length: ${bytes.size}\r\n"+
                (token?.let { "Authorization: Bearer $it\r\n" } ?: "")+(origin?.let { "Origin: $it\r\n" } ?: "")+"\r\n"
            socket.getOutputStream().apply { write(header.toByteArray());write(bytes);flush() }
            val raw=socket.getInputStream().bufferedReader().readText()
            return raw.substringBefore("\r\n").split(' ')[1].toInt() to JSONObject(raw.substringAfter("\r\n\r\n"))
        }
    }
    @Test fun pairOnceThenDeliverOriginalHeartRateWithReceipt() {
        val latch=CountDownLatch(1);var delivered=0
        val receiver=WatchReceiver(port=0) { delivered=it;latch.countDown() }
        receiver.start()
        try {
            assertEquals(403,request(receiver.boundPort,"/pair-watch",JSONObject().put("code","123456")).first)
            val code=receiver.openWatchPairing()
            assertEquals(403,request(receiver.boundPort,"/pair-watch",JSONObject().put("code",code),origin="http://evil.example").first)
            val paired=request(receiver.boundPort,"/pair-watch",JSONObject().put("code",code))
            assertEquals(200,paired.first);val token=paired.second.getString("token")
            assertEquals(403,request(receiver.boundPort,"/pair-watch",JSONObject().put("code",code)).first)
            val measured=System.currentTimeMillis()-500
            val response=request(receiver.boundPort,"/heart-rate",JSONObject().put("source","wear-os-sensor").put("bpm",117).put("measuredAt",measured).put("requestId","watch-native-check"),token)
            assertEquals(200,response.first);assertEquals("watch-native-check",response.second.getString("requestId"))
            assertTrue(latch.await(2,TimeUnit.SECONDS));assertEquals(117,delivered)
            assertEquals(measured,receiver.diagnostics.lastHeartRateMeasuredAtEpochMillis)
        } finally { receiver.stop() }
    }
}
