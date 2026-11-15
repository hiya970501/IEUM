package com.example.version1
import org.junit.Assert.*
import org.junit.Test

class WatchPairingWindowTest {
    @Test fun oldDialogDismissDoesNotRevokeNewPairingWindow() {
        val window = WatchPairingWindow()
        window.open(1000)
        val oldGeneration = window.generation()
        val current = window.open(1100)
        window.closeIfCurrent(oldGeneration)
        assertTrue(window.redeem(current, 1200))
        val next = window.open(1300)
        window.closeIfCurrent(window.generation())
        assertFalse(window.redeem(next, 1400))
    }
    @Test fun singleUseAndExplicitWindow() {
        val window=WatchPairingWindow()
        assertFalse(window.redeem("123456",1000))
        val code=window.open(1000)
        assertTrue(code.matches(Regex("[0-9]{6}")))
        assertTrue(window.redeem(code,1100));assertFalse(window.redeem(code,1101))
    }
    @Test fun expiryClockRollbackCloseAndGuessLimit() {
        val w=WatchPairingWindow(); var code=w.open(1000)
        assertFalse(w.redeem(code,121000))
        code=w.open(1000);assertFalse(w.redeem(code,999))
        code=w.open(1000);w.close();assertFalse(w.redeem(code,1100))
        code=w.open(1000);repeat(5) { assertFalse(w.redeem("invalid",1100)) }
        assertFalse(w.redeem(code,1100))
    }
}
