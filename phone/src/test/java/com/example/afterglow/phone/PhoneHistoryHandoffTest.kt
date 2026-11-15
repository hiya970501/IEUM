package com.example.afterglow.phone

import kotlinx.coroutines.*
import kotlinx.coroutines.sync.withLock
import org.junit.Assert.*
import org.junit.Test

class PhoneHistoryHandoffTest {
    @Test fun foregroundHandoffWaitsForPriorOwnerAndRejectsBackgroundDelivery()=runBlocking {
        val previous=PhoneHistorySyncControl.visible
        val entered=CompletableDeferred<Unit>();val release=CompletableDeferred<Unit>()
        var backgroundDelivered=false;var foregroundEntered=false
        try {
            PhoneHistorySyncControl.visible=false
            val background=launch {
                PhoneHistorySyncControl.gate.withLock {
                    entered.complete(Unit);release.await()
                    if(!PhoneHistorySyncControl.visible) backgroundDelivered=true
                }
            }
            entered.await();PhoneHistorySyncControl.visible=true
            val foreground=launch {PhoneHistorySyncControl.gate.withLock {foregroundEntered=true}}
            yield();assertFalse(foregroundEntered)
            release.complete(Unit);withTimeout(1000) {background.join();foreground.join()}
            assertTrue(foregroundEntered);assertFalse(backgroundDelivered)
        } finally {PhoneHistorySyncControl.visible=previous;release.complete(Unit)}
    }
}
