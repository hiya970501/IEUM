package com.example.afterglow.net

/** Unambiguous dotted-decimal RFC1918 address; no DNS, numeric aliases or octal-looking octets. */
object PrivateIpv4 {
    private const val OCTET = "(?:0|[1-9][0-9]{0,2})"
    private val dottedDecimal = Regex("$OCTET(?:\\.$OCTET){3}")

    fun isCanonicalPrivate(host: String): Boolean {
        if (host.length !in 7..15 || !dottedDecimal.matches(host)) return false
        val parts = host.split('.').map(String::toInt)
        if (parts.any { it > 255 }) return false
        return parts[0] == 10 || parts[0] == 192 && parts[1] == 168 ||
            parts[0] == 172 && parts[1] in 16..31
    }
}
