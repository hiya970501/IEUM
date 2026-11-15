package com.example.version1

import kotlinx.coroutines.*
import java.io.ByteArrayOutputStream
import java.net.SocketTimeoutException
import java.net.URL
import java.nio.ByteBuffer
import java.nio.charset.CodingErrorAction
import java.util.Locale
import javax.net.ssl.HttpsURLConnection

/** Bounded public JSON only. Callers retain their endpoint and semantic policies.
 * Closing the socket on cancellation also stops a server that trickles bytes forever.
 * Platform trust and hostname checks remain enabled; no health/auth headers are used. */
internal class PublicHttpsJsonClient(
    private val deadlineMillis:Long=8000,
    private val open:(URL)->HttpsURLConnection={it.openConnection() as HttpsURLConnection}
) {
    init { require(deadlineMillis in 1..10_000) }
    suspend fun get(url:URL,maxBytes:Int,maxDepth:Int):String=withContext(Dispatchers.IO) {
        require(url.protocol=="https" && url.userInfo==null && url.ref==null && url.port in listOf(-1,443))
        require(maxBytes in 1..65_536 && maxDepth in 1..32)
        try {
            withTimeout(deadlineMillis) {
                coroutineScope {
                    val connection=open(url)
                    val cancellation=launch(Dispatchers.IO,start=CoroutineStart.UNDISPATCHED) {
                        try { awaitCancellation() } finally { runCatching { connection.disconnect() } }
                    }
                    try {
                        ensureActive()
                        connection.apply {
                            sslSocketFactory=ReleaseTlsSocketFactory()
                            connectTimeout=3500;readTimeout=3500
                            instanceFollowRedirects=false;useCaches=false
                            setRequestProperty("Accept","application/json")
                        }
                        check(connection.responseCode==200) { "Public JSON response rejected" }
                        ensureActive()
                        check(connection.contentType?.substringBefore(';')?.trim()?.lowercase(Locale.ROOT)=="application/json")
                        check(connection.contentLengthLong in -1L..maxBytes.toLong())
                        val bytes=connection.inputStream.use { input ->
                            val output=ByteArrayOutputStream(minOf(maxBytes,4096));val buffer=ByteArray(2048)
                            while(true) {
                                ensureActive()
                                val count=input.read(buffer)
                                ensureActive()
                                if(count<0)break
                                check(count>0 && output.size()+count<=maxBytes)
                                output.write(buffer,0,count)
                            }
                            output.toByteArray()
                        }
                        ensureActive()
                        val raw=Charsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT)
                            .onUnmappableCharacter(CodingErrorAction.REPORT).decode(ByteBuffer.wrap(bytes)).toString()
                        JsonIngress.requireObject(raw,maxBytes,maxDepth)
                        ensureActive()
                        raw
                    } finally {
                        cancellation.cancelAndJoin()
                        runCatching { connection.disconnect() }
                    }
                }
            }
        }catch(timeout:TimeoutCancellationException) {
            // An operation's own timeout is a recoverable network error. A parent's
            // cancellation must still abort the caller rather than update a hidden UI.
            currentCoroutineContext().ensureActive()
            throw SocketTimeoutException("Public JSON request deadline exceeded").apply { initCause(timeout) }
        }
    }
}
