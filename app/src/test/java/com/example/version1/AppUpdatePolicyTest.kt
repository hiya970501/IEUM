package com.example.version1

import org.junit.Assert.*
import org.junit.Test

class AppUpdatePolicyTest {
    @Test fun rejectsLegacyTlsAndWeakCipherSuitesWithoutReplacingTrust() {
        assertArrayEquals(arrayOf("TLSv1.2","TLSv1.3"), ReleaseTlsSocketFactory.protocols(
            arrayOf("SSLv3","TLSv1","TLSv1.1","TLSv1.2","TLSv1.3")))
        assertArrayEquals(arrayOf("TLS_AES_128_GCM_SHA256","TLS_ECDHE_RSA_WITH_AES_128_GCM_SHA256"),
            ReleaseTlsSocketFactory.suites(arrayOf("TLS_RSA_WITH_AES_128_CBC_SHA","TLS_AES_128_GCM_SHA256",
                "TLS_ECDHE_RSA_WITH_AES_128_GCM_SHA256","TLS_RSA_WITH_RC4_128_MD5")))
    }
    private val pkg = "com.example.version1"
    private fun rejected(block: () -> Unit) {
        try { block(); fail("Expected invalid metadata or endpoint to be rejected") }
        catch (_: IllegalArgumentException) { }
    }
    @Test fun offersOnlyNewerVersionAndUsesMarketInsteadOfDownloadingApk() {
        val version = AppUpdatePolicy.parse("""{"packageName":"$pkg","versionCode":14,"versionName":"1.2.1"}""",pkg)
        assertTrue(AppUpdatePolicy.available(13,version))
        assertFalse(AppUpdatePolicy.available(14,version))
        assertFalse(AppUpdatePolicy.available(15,version))
        assertEquals("market://detail?package=$pkg",AppUpdatePolicy.marketUri(pkg))
    }
    @Test fun rejectsWrongAppAndUntrustedVersionFields() {
        for (code in listOf("0", "-1", "1.5", "\"14\"", "2147483648")) rejected {
            AppUpdatePolicy.parse("""{"packageName":"$pkg","versionCode":$code,"versionName":"1.2.1"}""",pkg)
        }
        rejected { AppUpdatePolicy.parse("""{"packageName":"other.app","versionCode":14,"versionName":"1.2.1"}""",pkg) }
        rejected { AppUpdatePolicy.parse("""{"packageName":"$pkg","versionCode":14,"versionName":"<html>"}""",pkg) }
        rejected { AppUpdatePolicy.parse(" ".repeat(17000),pkg) }
    }
    @Test fun releaseMetadataRequiresHttpsAndNoCredentialsOrRedirectTarget() {
        // Host syntax fixture only; this does not claim a publisher server exists at the path.
        assertEquals("document.pleos.ai",AppUpdatePolicy.endpoint("https://document.pleos.ai/app.json").host)
        for (url in listOf("http://releases.example.com/app.json", "https://u:p@releases.example.com/app.json",
            "https://127.0.0.1/v.json", "https://localhost/v.json", "https://releases.example.com:8443/a",
            "https://releases.example.com/a?token=secret", "https://releases.example.com/a#fragment")) rejected {
            AppUpdatePolicy.endpoint(url)
        }
    }
    @Test fun runtimeAndSubmissionShareThePublicEndpointContract() {
        val cases=org.json.JSONArray(java.io.File(checkNotNull(System.getProperty("ieum.updateEndpoint.cases"))).readText())
        assertTrue(cases.length() >= 25)
        for(index in 0 until cases.length()) {
            val row=cases.getJSONObject(index)
            assertEquals("Endpoint case $index",row.getBoolean("accepted"),runCatching {
                AppUpdatePolicy.endpoint(row.getString("value"));true
            }.getOrDefault(false))
        }
    }
}
