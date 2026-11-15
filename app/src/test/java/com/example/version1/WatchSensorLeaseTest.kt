package com.example.version1

import org.junit.Assert.*
import org.junit.Test

class WatchSensorLeaseTest {
    @Test fun phoneHistoryAndHeartbeatCannotKeepStoppedWatchOn() {
        val received=100_000L
        val watch=BridgeDiagnostics(lastWatchSensorReceivedAt=received)
        assertTrue(watch.hasLiveWatchSensor(received+59_999))
        val phone=watch.copy(historyReceivedAt=received+70_000,
            lastAcceptedRequestAtEpochMillis=received+70_000,
            lastHeartRateSource="health-connect",lastHeartRateReceivedAtEpochMillis=received+70_000)
        assertFalse(phone.hasLiveWatchSensor(received+70_000))
        assertFalse(BridgeDiagnostics(historyReceivedAt=received).hasLiveWatchSensor(received))
        assertFalse(watch.hasLiveWatchSensor(received+60_000))
        assertFalse(watch.hasLiveWatchSensor(received-1))
    }
}
