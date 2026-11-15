package com.example.afterglow.phone

import com.example.afterglow.net.SecureRelayEndpoint
import com.example.afterglow.net.BoundedRelayJson
import org.junit.Assert.*
import org.junit.Test

class SecureRelayEndpointTest {
    @Test fun publicHttpsEndpointHasFixedOperationPathsAndRedactedDiagnostics() {
        val endpoint=SecureRelayEndpoint("https://relay.example/ingest/native")
        assertEquals("relay.example",endpoint.host)
        assertEquals("https://relay.example/ingest/native/pair-watch",endpoint.url("/pair-watch").toString())
        assertFalse(endpoint.toString().contains("relay.example"))
        assertThrows(IllegalArgumentException::class.java) { endpoint.url("/zero-tap") }
    }
    @Test fun rejectsPlaintextUrlSecretsEncodedPathsAndUntrustedPorts() {
        listOf("http://relay.example", "https://127.0.0.1", "https://user:secret@relay.example",
            "https://relay.example?token=secret", "https://relay.example#token=secret",
            "https://relay.example:8080", "https://relay.example/a/../b", "https://relay.example/%2e%2e",
            "https://relay.example/", " https://relay.example", "https://relay.example\n").forEach { raw ->
            assertThrows(Exception::class.java) { SecureRelayEndpoint(raw) }
        }
    }
    @Test fun rejectsDeepAndOversizeRepliesBeforeTreeParsing() {
        BoundedRelayJson.requireObject("{\"quoted\":\"[[[\"}",100,2)
        assertThrows(IllegalArgumentException::class.java) {
            BoundedRelayJson.requireObject("{\"a\":{\"b\":{}}}",100,2)
        }
        assertThrows(IllegalArgumentException::class.java) { BoundedRelayJson.requireObject(" ".repeat(1001),1000,2) }
    }
}
