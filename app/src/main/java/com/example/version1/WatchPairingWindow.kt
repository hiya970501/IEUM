package com.example.version1

import java.security.MessageDigest
import java.security.SecureRandom

/** Explicit, single-use window; five guesses per displayed code. */
class WatchPairingWindow {
    private var code: String? = null
    private var openedAt = 0L
    private var attempts = 0
    private var revision = 0L
    @Synchronized fun generation(): Long = revision
    @Synchronized fun open(now:Long): String {
        revision++
        openedAt=now; attempts=0
        return "%06d".format(java.util.Locale.ROOT,SecureRandom().nextInt(1_000_000)).also { code=it }
    }
    @Synchronized fun close() { revision++; code=null; attempts=5 }
    @Synchronized fun closeIfCurrent(expectedRevision: Long) {
        if (revision == expectedRevision) close()
    }
    @Synchronized fun redeem(candidate:String,now:Long): Boolean {
        val expected=code ?: return false
        if (now-openedAt !in 0..119_999 || attempts>=5) { close(); return false }
        attempts++
        val matches=candidate.matches(Regex("[0-9]{6}")) && MessageDigest.isEqual(candidate.toByteArray(),expected.toByteArray())
        if (matches || attempts>=5) close()
        return matches
    }
}
