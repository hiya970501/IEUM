package com.example.version1

import com.example.afterglow.net.PrivateIpv4
import org.json.JSONArray
import org.junit.Assert.*
import org.junit.Test
import java.io.File
import java.net.URI

class PrivateIpv4Test {
    @Test fun sharedCasesMatchBothPolicyAndVehicleQrBoundary() {
        val cases = JSONArray(File(System.getProperty("afterglow.ipv4.cases")).readText())
        assertTrue(cases.length() >= 40)
        repeat(cases.length()) { index ->
            val case = cases.getJSONObject(index)
            val host = case.getString("host")
            val expected = case.getBoolean("allowed")
            assertEquals("Shared case $index", expected, PrivateIpv4.isCanonicalPrivate(host))
            assertEquals("Vehicle QR case $index", expected, PairingAddress.isPrivateIpv4(host))
            if (!expected) assertThrows(IllegalArgumentException::class.java) {
                PairingAddress.landingUrl(host)
            }
        }
    }
    @Test fun privateRangeBoundariesHoldAcrossEveryFirstAndSecondOctet() {
        repeat(256) { octet ->
            assertEquals(octet == 10, PrivateIpv4.isCanonicalPrivate("$octet.0.0.1"))
            assertEquals(octet in 16..31, PrivateIpv4.isCanonicalPrivate("172.$octet.0.1"))
            assertEquals(octet == 168, PrivateIpv4.isCanonicalPrivate("192.$octet.0.1"))
        }
    }
    @Test fun landingAddressNeverCarriesCredentialsInAnyUrlComponent() {
        listOf("10.0.0.1", "172.16.0.1", "192.168.0.1").forEach { host ->
            val uri = URI(PairingAddress.landingUrl(host))
            assertEquals(host, uri.host)
            assertNull(uri.fragment)
            assertNull(uri.query)
        }
    }
}
