package com.example.afterglow.phone

import org.junit.Assert.*
import org.junit.Test

class RelaySessionTest {
    private fun destination(host: String = "https://relay.example/ingest/native") = RelayDestination(host, "a".repeat(32))
    private fun ready(): RelaySession = RelaySession().apply {
        pair(destination()); foreground(true); consent(true)
        assertTrue(authorize(beginAuthorization()!!))
    }

    @Test fun cannotReadBeforeConsentOrAuthorization() {
        val session = RelaySession()
        session.pair(destination()); session.foreground(true)
        assertNull(session.beginAuthorization()); assertNull(session.transfer())
        session.consent(true)
        assertNull(session.transfer())
    }
    @Test fun withdrawalInvalidatesInFlightAndPendingPermission() {
        val session = ready()
        val transfer = session.transfer()!!
        val authorization = session.beginAuthorization()!!
        session.consent(false)
        assertFalse(session.isCurrent(transfer)); assertFalse(session.authorize(authorization))
        assertNull(session.transfer())
    }
    @Test fun RecheckingConsentDoesNotReactivateOldPermissionRequest() {
        val session = ready()
        val authorization = session.beginAuthorization()!!
        session.consent(false); session.consent(true)
        assertFalse(session.authorize(authorization)); assertNull(session.transfer())
    }
    @Test fun newQrRequiresNewConsentAndRejectsOldCompletions() {
        val session = ready()
        val authorization = session.beginAuthorization()!!
        val transfer = session.transfer()!!
        session.pair(destination("https://other.example/ingest/native"))
        assertFalse(session.authorize(authorization)); assertFalse(session.isCurrent(transfer))
        assertNull(session.beginAuthorization()); assertNull(session.transfer())
        session.consent(true)
        assertTrue(session.authorize(session.beginAuthorization()!!))
        assertEquals("other.example", session.transfer()!!.destination.host)
        assertEquals("relay.example", transfer.destination.host)
    }
    @Test fun backgroundStopsTransfersAndOldResultsStayInvalidAfterResume() {
        val session = ready()
        val old = session.transfer()!!
        session.foreground(false)
        assertFalse(session.isCurrent(old)); assertNull(session.transfer())
        session.foreground(true)
        assertFalse(session.isCurrent(old)); assertTrue(session.isCurrent(session.transfer()!!))
    }
    @Test fun permissionResultDuringPermissionUiCannotStartBackgroundTransfer() {
        val session = RelaySession().apply { pair(destination()); foreground(true); consent(true) }
        val authorization = session.beginAuthorization()!!
        session.foreground(false)
        assertTrue(session.authorize(authorization)); assertNull(session.transfer())
        session.foreground(true)
        assertNotNull(session.transfer())
    }
    @Test fun backgroundPermissionQueryCannotOpenANewPermissionDialog() {
        val session = RelaySession().apply { pair(destination()); foreground(true); consent(true) }
        val ticket = session.beginAuthorization()!!
        assertTrue(session.canRequestPermission(ticket))
        session.foreground(false)
        assertFalse(session.canRequestPermission(ticket))
        session.consent(false); session.foreground(true)
        assertFalse(session.canRequestPermission(ticket))
    }
    @Test fun stopCancelsPendingPermissionAndNeverAutoResumes() {
        val session = ready()
        val authorization = session.beginAuthorization()!!
        session.stop(); session.foreground(false); session.foreground(true)
        assertFalse(session.authorize(authorization)); assertNull(session.transfer())
    }
    @Test fun destroyRemovesDestinationAndConsent() {
        val session = ready(); val transfer = session.transfer()!!
        session.clear(); session.foreground(true)
        assertFalse(session.isCurrent(transfer)); assertNull(session.beginAuthorization())
    }
    @Test fun pairingKeyNeverAppearsInDefaultDiagnosticString() {
        assertFalse(destination().toString().contains("a".repeat(32)))
    }
    @Test fun publicAndMalformedDestinationsRejected() {
        listOf("127.0.0.1", "8.8.8.8", "192.168.1.256", "10.0.0.1/evil", "example.com",
            "010.0.0.1", "192.168.001.1", "+10.0.0.1", "10.0.0.1\n").forEach {
            assertThrows(IllegalArgumentException::class.java) { destination(it) }
        }
    }
}
