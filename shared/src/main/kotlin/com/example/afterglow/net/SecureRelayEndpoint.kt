package com.example.afterglow.net

import java.net.URI
import java.net.URL

/** Explicit HTTPS destination. No credentials, query, fragment or redirect-based pairing. */
class SecureRelayEndpoint(val address:String) {
    private val uri=try { URI(address) } catch (_:java.net.URISyntaxException) { throw IllegalArgumentException("Invalid HTTPS endpoint") }
    val host:String get()=uri.host
    init {
        require(address.length in 1..2048 && address==address.trim() && address.none { it.isWhitespace() || it.isISOControl() })
        require(uri.scheme=="https" && uri.userInfo==null && uri.rawQuery==null && uri.rawFragment==null)
        require(uri.port==-1 || uri.port==443)
        val name=uri.host ?: error("HTTPS host required")
        require(name.length<=253 && name.contains('.') && !name.matches(Regex("[0-9.]+")) &&
            name.split('.').all { it.matches(Regex("[A-Za-z0-9](?:[A-Za-z0-9-]{0,61}[A-Za-z0-9])?")) })
        require(uri.rawPath.orEmpty().matches(Regex("(?:/[A-Za-z0-9_-]+)*")))
        require(uri.toASCIIString()==address)
    }
    fun url(path:String):URL {
        require(path in setOf("/pair-watch","/connect","/workout","/heart-rate","/health-heart-rate","/watch-activity","/workout-history"))
        return URL(address+path)
    }
    override fun toString()="SecureRelayEndpoint(redacted)"
}
