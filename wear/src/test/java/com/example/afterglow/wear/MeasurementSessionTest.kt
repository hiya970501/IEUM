package com.example.afterglow.wear

import org.junit.Assert.*
import org.junit.Test

class MeasurementSessionTest {
    private fun target(key: Char = 'a') = WatchDestination("https://relay.example/native", key.toString().repeat(32))
    private fun measuring(session: MeasurementSession): MeasurementSession.Measurement {
        session.foreground(true); session.requestStart(true)
        return requireNotNull(session.takeStart())
    }
    @Test fun pairingIsRequiredAndSecretRenderingIsRedacted() {
        val session = MeasurementSession(); session.foreground(true)
        assertNull(session.requestStart(true)); assertNull(session.takeStart())
        assertFalse(target().toString().contains("a".repeat(32)))
        assertThrows(IllegalArgumentException::class.java) { WatchDestination("010.0.0.1", "a".repeat(32)) }
    }
    @Test fun latePermissionAfterStopCannotStartMeasurement() {
        val session = MeasurementSession(); session.configure(target()); session.foreground(true)
        val ticket = requireNotNull(session.requestStart(false)); session.stop()
        assertFalse(session.permissionResult(ticket, true)); assertNull(session.takeStart())
    }
    @Test fun permissionFromOldPairCannotAuthorizeNewPair() {
        val session = MeasurementSession(); session.configure(target()); session.foreground(true)
        val old = requireNotNull(session.requestStart(false)); session.configure(target('b'))
        val current = requireNotNull(session.requestStart(false))
        assertFalse(session.permissionResult(old, true)); assertNull(session.takeStart())
        assertTrue(session.permissionResult(current, true)); assertNotNull(session.takeStart())
    }
    @Test fun permissionDialogPauseCannotStartInBackgroundButCanResumeSameIntent() {
        val session = MeasurementSession(); session.configure(target()); session.foreground(true)
        val ticket = requireNotNull(session.requestStart(false)); session.foreground(false)
        assertTrue(session.permissionResult(ticket, true)); assertNull(session.takeStart())
        session.foreground(true); assertNotNull(session.takeStart()); assertNull(session.takeStart())
    }
    @Test fun stoppedScreenDoesNotResumeAnOldPermissionIntent() {
        val session = MeasurementSession(); session.configure(target()); session.foreground(true)
        val ticket = requireNotNull(session.requestStart(false)); session.foreground(false); session.stop()
        assertFalse(session.permissionResult(ticket, true)); session.foreground(true); assertNull(session.takeStart())
    }
    @Test fun deniedPermissionIsNotReady() {
        val session = MeasurementSession(); session.configure(target()); session.foreground(true)
        val ticket = requireNotNull(session.requestStart(false))
        assertTrue(session.permissionResult(ticket, false)); assertNull(session.takeStart())
        assertFalse(session.permissionResult(ticket, true))
    }
    @Test fun foregroundReturnDoesNotResurrectSensorRegistration() {
        val session = MeasurementSession(); session.configure(target()); val old = measuring(session)
        session.foreground(false); session.foreground(true)
        assertFalse(session.isCurrent(old)); assertNull(session.claim(old, 10000)); assertNull(session.takeStart())
    }
    @Test fun staleCompletionCannotUnlockNewTransfer() {
        val session = MeasurementSession(); session.configure(target()); val first = measuring(session)
        val old = requireNotNull(session.claim(first, 0)); session.stop()
        val second = measuring(session); val current = requireNotNull(session.claim(second, 10))
        assertFalse(session.complete(old)); assertTrue(session.isCurrent(current))
        assertNull(session.claim(second, 10000)); assertTrue(session.complete(current))
    }
    @Test fun onlyOneSampleCanBeInFlightAndAttemptsAreThrottled() {
        val session = MeasurementSession(); session.configure(target()); val current = measuring(session)
        val request = requireNotNull(session.claim(current, 0)); assertNull(session.claim(current, 10000))
        assertTrue(session.complete(request)); assertNull(session.claim(current, 4999))
        assertNotNull(session.claim(current, 5000))
    }
    @Test fun clockRegressionCannotBypassThrottle() {
        val session = MeasurementSession(); session.configure(target()); val current = measuring(session)
        assertNull(session.claim(current, -1))
        val request = requireNotNull(session.claim(current, 6000)); session.complete(request)
        assertNull(session.claim(current, 5999)); assertNotNull(session.claim(current, 11000))
    }
    @Test fun oldSensorRegistrationCannotPublishAfterRestart() {
        val session = MeasurementSession(); session.configure(target()); val old = measuring(session)
        val current = measuring(session)
        assertFalse(session.isCurrent(old)); assertTrue(session.isCurrent(current)); assertNull(session.claim(old, 10000))
    }
    @Test fun permissionIsAcceptedOnlyOnce() {
        val session = MeasurementSession(); session.configure(target()); session.foreground(true)
        val auth = requireNotNull(session.requestStart(false))
        assertTrue(session.permissionResult(auth, true)); assertFalse(session.permissionResult(auth, true))
    }
    @Test fun sensorTimeDefinesMeasurementAndExactExpiryIsRejected() {
        val sample = requireNotNull(LiveHeartRate.from(130f, 1_000_000_000, 3_000_000_000, 1700000000000))
        assertEquals(1699999998000, sample.measuredAt)
        assertTrue(sample.isFresh(30_999_999_999)); assertFalse(sample.isFresh(31_000_000_000))
        assertFalse(sample.isFresh(999_999_999))
    }
    @Test fun invalidAndStaleSensorSamplesAreNotRelayable() {
        listOf(Float.NaN, Float.POSITIVE_INFINITY, Float.NEGATIVE_INFINITY, 39.9f, 220.1f).forEach {
            assertNull(LiveHeartRate.from(it, 1, 2, 1700000000000))
        }
        assertNull(LiveHeartRate.from(130f, 100, 99, 1700000000000))
        assertNull(LiveHeartRate.from(130f, 1, 30_000_000_001, 1700000000000))
        assertNull(LiveHeartRate.from(130f, 0, 2, 1700000000000))
        assertNull(LiveHeartRate.from(130f, 1, 2_000_000_001, 1))
        assertNotNull(LiveHeartRate.from(40f, 1, 1, 1700000000000))
        assertNotNull(LiveHeartRate.from(220f, 1, 1, 1700000000000))
    }
}
