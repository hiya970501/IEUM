package com.example.version1

import kotlinx.coroutines.*
import org.junit.Assert.*
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.IOException
import java.io.InputStream
import java.net.SocketTimeoutException
import java.net.URL
import java.security.cert.Certificate
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import javax.net.ssl.HttpsURLConnection

/** Transport fixtures prove bounds/cancellation, not a real certificate handshake. */
class PublicHttpsJsonClientTest {
    private val url=URL("https://weather.example/data")
    private class Connection(val body:ByteArray="{\"label\":\"심박\"}".toByteArray(),
        val code:Int=200,val mime:String="application/json",val declared:Long=-1):HttpsURLConnection(URL("https://weather.example/data")) {
        val closed=CountDownLatch(1)
        var responseHook:()->Unit={}
        var disconnected:()->Unit={}
        var stream:InputStream?=null
        var streamOpened=false
        init { ReleaseTlsSocketFactory() } // JVM provider startup is outside the timed fixture.
        override fun getResponseCode():Int {responseHook();return code}
        override fun getContentType()=mime
        override fun getContentLengthLong()=declared
        override fun getInputStream():InputStream {streamOpened=true;return stream?:ByteArrayInputStream(body)}
        override fun disconnect(){closed.countDown();disconnected()}
        override fun connect()=Unit
        override fun usingProxy()=false
        override fun getCipherSuite()="TLS_AES_128_GCM_SHA256"
        override fun getLocalCertificates():Array<Certificate>?=null
        override fun getServerCertificates():Array<Certificate> =emptyArray()
    }
    @Test fun utf8JsonKeepsPlatformHostnamePolicyAndDoesNotCacheOrRedirect()=runBlocking {
        val connection=Connection(mime="Application/JSON; charset=utf-8")
        val verifier=connection.hostnameVerifier
        val raw=PublicHttpsJsonClient(open={connection}).get(url,1024,2)
        assertEquals(String(connection.body,Charsets.UTF_8),raw)
        assertSame(verifier,connection.hostnameVerifier)
        assertTrue(connection.sslSocketFactory is ReleaseTlsSocketFactory)
        assertFalse(connection.instanceFollowRedirects);assertFalse(connection.useCaches)
        assertEquals(0,connection.closed.count)
    }
    @Test fun rejectsRedirectMimeDeclaredAndActualOversizeBeforeReturningBody()=runBlocking {
        for(connection in listOf(Connection(code=302),Connection(mime="text/html"),Connection(declared=2048))) {
            try {PublicHttpsJsonClient(open={connection}).get(url,1024,2);fail("Invalid response accepted")}
            catch(_:IllegalStateException){}
            assertFalse(connection.streamOpened);assertEquals(0,connection.closed.count)
        }
        val large=Connection(body=("{\"x\":\""+"a".repeat(100)+"\"}").toByteArray())
        try {PublicHttpsJsonClient(open={large}).get(url,32,2);fail("Oversized body accepted")}
        catch(_:IllegalStateException){}
        assertEquals(0,large.closed.count)
    }
    @Test fun invalidUtf8DeepJsonAndExtraRootNeverReachTheCaller()=runBlocking {
        for(bytes in listOf(byteArrayOf(0xff.toByte()),"{\"x\":{\"y\":{}}}".toByteArray(),"{}{}".toByteArray())) {
            val connection=Connection(body=bytes)
            try {PublicHttpsJsonClient(open={connection}).get(url,1024,2);fail("Invalid bounded JSON accepted")}
            catch(_:java.nio.charset.CharacterCodingException){}
            catch(_:IllegalArgumentException){}
            assertEquals(0,connection.closed.count)
        }
    }
    @Test fun entireDeadlineClosesBlockedBodyAndBecomesRecoverableNetworkError()=runBlocking {
        val connection=Connection();val entered=CountDownLatch(1);val release=CountDownLatch(1)
        connection.disconnected={release.countDown()}
        connection.stream=object:InputStream(){
            override fun read():Int=error("Bulk read expected")
            override fun read(b:ByteArray,off:Int,len:Int):Int {
                entered.countDown();check(release.await(3,TimeUnit.SECONDS));throw IOException("closed")
            }
        }
        try {PublicHttpsJsonClient(200,open={connection}).get(url,1024,2);fail("Deadline did not expire")}
        catch(_:SocketTimeoutException){}
        assertEquals(0,entered.count);assertEquals(0,connection.closed.count)
        assertTrue(currentCoroutineContext().isActive)
    }
    @Test fun lateValidResponseIgnoringDisconnectIsDiscarded()=runBlocking {
        val connection=Connection();var returned=false
        connection.responseHook={check(connection.closed.await(3,TimeUnit.SECONDS));returned=true}
        try {PublicHttpsJsonClient(200,open={connection}).get(url,1024,2);fail("Late response accepted")}
        catch(_:SocketTimeoutException){}
        assertTrue(returned);assertFalse(connection.streamOpened)
    }
    @Test fun parentCancellationClosesSocketAndRemainsCancellation()=runBlocking {
        val connection=Connection();val entered=CountDownLatch(1);val release=CountDownLatch(1)
        connection.disconnected={release.countDown()}
        connection.stream=object:InputStream(){
            override fun read():Int=error("Bulk read expected")
            override fun read(b:ByteArray,off:Int,len:Int):Int {
                entered.countDown();check(release.await(3,TimeUnit.SECONDS));throw IOException("closed")
            }
        }
        val request=async(Dispatchers.Default){PublicHttpsJsonClient(open={connection}).get(url,1024,2)}
        assertTrue(withContext(Dispatchers.IO){entered.await(2,TimeUnit.SECONDS)})
        request.cancel()
        try {request.await();fail("Cancelled operation returned body")}catch(_:CancellationException){}
        assertTrue(withContext(Dispatchers.IO){connection.closed.await(2,TimeUnit.SECONDS)})
        assertTrue(currentCoroutineContext().isActive)
    }
}
