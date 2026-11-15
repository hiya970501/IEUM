package com.example.afterglow.net
import java.net.Socket
import java.net.InetAddress
import javax.net.ssl.SSLSocket
import javax.net.ssl.SSLSocketFactory

class RelayTlsSocketFactory(
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
