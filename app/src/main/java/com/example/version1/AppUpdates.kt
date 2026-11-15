package com.example.version1

import java.net.URI
import javax.net.ssl.HttpsURLConnection
import javax.net.ssl.SSLSocket
import javax.net.ssl.SSLSocketFactory
import java.net.Socket
import java.net.InetAddress
import org.json.JSONObject

/** Public release metadata only. No vehicle, profile, health or device identifiers leave the app. */
internal data class PublishedAppVersion(val code: Long, val name: String)
internal object AppUpdatePolicy {
    const val MAX_RESPONSE_BYTES = 16 * 1024
    fun endpoint(value: String): URI {
        require(value.length in 1..2048 && value == value.trim() && value.none { it.isWhitespace() || it.isISOControl() })
        val uri = try { URI(value) } catch (_: java.net.URISyntaxException) { throw IllegalArgumentException("Invalid release metadata endpoint") }
        require(uri.scheme == "https" && !uri.host.isNullOrBlank() && uri.userInfo == null &&
            uri.fragment == null && uri.query == null && uri.port in listOf(-1, 443))
        val host = uri.host.lowercase()
        val labels = host.split('.')
        require(host.length <= 253 && labels.size >= 2 && labels.all {
            it.matches(Regex("[A-Za-z0-9](?:[A-Za-z0-9-]{0,61}[A-Za-z0-9])?"))
        } && labels.last().any { it.isLetter() })
        val reserved = listOf("localhost", "local", "localdomain", "internal", "invalid", "test", "example",
            "home.arpa", "onion", "example.com", "example.net", "example.org")
        require(reserved.none { host == it || host.endsWith(".$it") })
        val path = uri.rawPath.orEmpty()
        require(path.matches(Regex("(?:/[A-Za-z0-9._~-]+)*/?")) && path.split('/').none { it == "." || it == ".." })
        require(uri.toASCIIString() == value)
        return uri
    }
    fun parse(body: String, packageName: String): PublishedAppVersion {
        require(body.toByteArray(Charsets.UTF_8).size <= MAX_RESPONSE_BYTES)
        JsonIngress.requireObject(body, MAX_RESPONSE_BYTES, 1)
        val json = JSONObject(body)
        require(json.getString("packageName") == packageName)
        val codeValue = json.get("versionCode")
        require(codeValue is Int || codeValue is Long)
        val code = (codeValue as Number).toLong()
        val name = json.getString("versionName")
        require(code in 1..Int.MAX_VALUE.toLong() && name.length in 1..48 &&
            name.all { it.isLetterOrDigit() || it in ".-+_" })
        return PublishedAppVersion(code, name)
    }
    fun available(installedCode: Long, published: PublishedAppVersion) = published.code > installedCode
    fun marketUri(packageName: String): String {
        require(packageName.matches(Regex("[A-Za-z][A-Za-z0-9_]*(\\.[A-Za-z][A-Za-z0-9_]*)+")))
        return "market://detail?package=$packageName"
    }
}

internal class AppUpdateClient(private val endpoint: String, private val packageName: String) {
    /** Suspendable IO with a whole-request deadline and prompt socket cancellation. */
    suspend fun fetch(): PublishedAppVersion = AppUpdatePolicy.parse(
        PublicHttpsJsonClient().get(AppUpdatePolicy.endpoint(endpoint).toURL(),AppUpdatePolicy.MAX_RESPONSE_BYTES,1),packageName)
}

/** API 28 must also reject TLS 1.0/1.1. Keep platform trust and hostname verification. */
internal class ReleaseTlsSocketFactory(
    private val platform: SSLSocketFactory = SSLSocketFactory.getDefault() as SSLSocketFactory
) : SSLSocketFactory() {
    companion object {
        fun protocols(supported: Array<String>) = supported.filter { it == "TLSv1.2" || it == "TLSv1.3" }.toTypedArray()
        fun suites(enabled: Array<String>) = enabled.filter {
            (it.startsWith("TLS_AES_") || it == "TLS_CHACHA20_POLY1305_SHA256" || it.startsWith("TLS_ECDHE_")) &&
                (it.contains("_GCM_") || it.contains("CHACHA20_POLY1305"))
        }.toTypedArray()
    }
    private fun secure(socket: Socket): Socket {
        val ssl = socket as SSLSocket
        val versions = protocols(ssl.supportedProtocols)
        val ciphers = suites(ssl.enabledCipherSuites)
        require(versions.isNotEmpty() && ciphers.isNotEmpty())
        ssl.enabledProtocols = versions
        ssl.enabledCipherSuites = ciphers
        return ssl
    }
    override fun getDefaultCipherSuites() = suites(platform.defaultCipherSuites)
    override fun getSupportedCipherSuites() = suites(platform.supportedCipherSuites)
    override fun createSocket(s: Socket, host: String, port: Int, autoClose: Boolean) = secure(platform.createSocket(s,host,port,autoClose))
    override fun createSocket(host: String, port: Int) = secure(platform.createSocket(host,port))
    override fun createSocket(host: String, port: Int, local: InetAddress, localPort: Int) = secure(platform.createSocket(host,port,local,localPort))
    override fun createSocket(host: InetAddress, port: Int) = secure(platform.createSocket(host,port))
    override fun createSocket(host: InetAddress, port: Int, local: InetAddress, localPort: Int) = secure(platform.createSocket(host,port,local,localPort))
    override fun createSocket() = secure(platform.createSocket())
}
