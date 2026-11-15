package com.example.afterglow.phone

import com.example.afterglow.net.*
import java.io.*
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class BluetoothWireTest {
    private val address="AA:BB:CC:DD:EE:FF"
    private fun frame(json:JSONObject)=ByteArrayOutputStream().apply {BluetoothRelayWire.write(this,json)}.toByteArray()
    @Test fun fragmentedUnicodeFrameRoundTripsWithoutDroppingTrailingRequest() {
        val request=BluetoothRelayWire.Request("/connect",JSONObject().put("driverName","이음").toString(),"a".repeat(32))
        val bytes=frame(request.json())+frame(request.json())
        val input=object:ByteArrayInputStream(bytes) {override fun read(b:ByteArray,off:Int,len:Int)=super.read(b,off,minOf(len,1))}
        repeat(2) {assertEquals(request.body,BluetoothRelayWire.request(BluetoothRelayWire.read(input)).body)}
        assertEquals(0,input.available())
    }
    @Test fun oversizedNegativeTruncatedAndDeepFramesFailBeforeUse() {
        for(length in listOf(-1,0,BluetoothRelayWire.MAX_FRAME+1)) {
            val bytes=ByteArrayOutputStream().apply {DataOutputStream(this).writeInt(length)}.toByteArray()
            assertThrows(IllegalArgumentException::class.java) {BluetoothRelayWire.read(ByteArrayInputStream(bytes))}
        }
        assertThrows(EOFException::class.java) {BluetoothRelayWire.read(ByteArrayInputStream(byteArrayOf(0,0,0,20,123)))}
        assertThrows(IllegalArgumentException::class.java) {BluetoothRelayWire.read(ByteArrayInputStream(frame(JSONObject().put("deep",JSONObject().put("a",JSONObject().put("b",JSONObject()))))))}
    }
    @Test fun onlyExplicitHealthPathsAndValidAuthReachGateway() {
        for(path in listOf("/zero-tap","/import","/status","/companion.apk","/connect\r\nInjected: yes"))
            assertThrows(IllegalArgumentException::class.java) {BluetoothRelayWire.Request(path,"{}","a".repeat(32))}
        assertThrows(IllegalArgumentException::class.java) {BluetoothRelayWire.Request("/connect","{}")}
        assertThrows(IllegalArgumentException::class.java) {BluetoothRelayWire.Request("/pair-watch","{}","a".repeat(32))}
        assertThrows(IllegalArgumentException::class.java) {BluetoothRelayWire.request(JSONObject().put("v","1").put("path","/pair-watch").put("body","{}"))}
        assertThrows(IllegalArgumentException::class.java) {BluetoothRelayWire.request(JSONObject().put("v",1).put("path","/pair-watch").put("body","{}").put("host","other"))}
    }
    @Test fun destinationCannotFallbackToHttpAndSecretsAreRedacted() {
        val target=RelayDestination.bluetooth(address,"a".repeat(32))
        assertEquals(address,target.bluetoothAddress)
        assertThrows(IllegalStateException::class.java) {target.endpoint}
        assertFalse(target.toString().contains(address));assertFalse(target.toString().contains("a".repeat(32)))
        assertThrows(IllegalArgumentException::class.java) {RelayDestination.bluetooth("invalid","a".repeat(32))}
        val session=RelaySession().apply {pair(target);foreground(true);consent(true)}
        assertNull(session.transfer());assertTrue(session.authorize(session.beginAuthorization()!!))
        val ticket=session.transfer()!!;session.foreground(false);assertFalse(session.isCurrent(ticket))
    }
    @Test fun boundedClientReturnsReceiptAndAlwaysClosesSocket() {
        var closed=false;val output=ByteArrayOutputStream()
        val response=BluetoothRelayWire.Reply(200,"{\"ok\":true,\"requestId\":\"owned\"}")
        val client=BluetoothRequestClient({object:BluetoothByteChannel {
            override fun connect() {}
            override val input=ByteArrayInputStream(frame(response.json()))
            override val output=output
            override fun close(){closed=true}
        }})
        val result=client.request(address,BluetoothRelayWire.Request("/connect","{}","a".repeat(32))) {true}
        assertEquals(200,result.status);assertTrue(closed)
        assertEquals("/connect",BluetoothRelayWire.request(BluetoothRelayWire.read(ByteArrayInputStream(output.toByteArray()))).path)
    }
    @Test fun deadlineUnblocksStalledConnectAndCannotReturnSuccess() {
        val closed=CountDownLatch(1)
        val client=BluetoothRequestClient({object:BluetoothByteChannel {
            override fun connect(){assertTrue(closed.await(3,TimeUnit.SECONDS))}
            override val input=ByteArrayInputStream(byteArrayOf())
            override val output=ByteArrayOutputStream()
            override fun close(){closed.countDown()}
        }},30)
        assertThrows(InterruptedIOException::class.java) {client.request(address,BluetoothRelayWire.Request("/pair-watch","{}")){true}}
        assertEquals(0,closed.count)
    }
    @Test fun cancelDuringConnectClosesChannelAndInvalidatesRequest() {
        val closed=CountDownLatch(1)
        lateinit var client:BluetoothRequestClient
        client=BluetoothRequestClient({object:BluetoothByteChannel {
            override fun connect(){client.cancel()}
            override val input=ByteArrayInputStream(byteArrayOf())
            override val output=ByteArrayOutputStream()
            override fun close(){closed.countDown()}
        }})
        assertThrows(InterruptedIOException::class.java) {client.request(address,BluetoothRelayWire.Request("/pair-watch","{}")){true}}
        assertEquals(0,closed.count)
    }
    @Test fun noConsentDoesNotEvenOpenBluetoothSocket() {
        var opens=0
        val client=BluetoothRequestClient({opens++;error("Must not open")})
        assertThrows(InterruptedIOException::class.java) {client.request(address,BluetoothRelayWire.Request("/pair-watch","{}")){false}}
        assertEquals(0,opens)
    }
}
