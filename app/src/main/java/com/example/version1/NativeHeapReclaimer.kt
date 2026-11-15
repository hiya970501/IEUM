package com.example.version1

import android.os.Looper

/** One-shot pressure recovery, never presumed free memory. Caller must re-read
 * whole-process memory with all existing reserves. Exhaustive purge stays off UI.
 */
internal object NativeHeapReclaimer {
    private val available: Boolean by lazy {
        try {
            System.loadLibrary("afterglow_memory")
            true
        } catch (_: UnsatisfiedLinkError) {
            false
        } catch (_: SecurityException) {
            false
        }
    }

    /** 0 unsupported, 1 public M_PURGE, 2 public API34+ M_PURGE_ALL. */
    fun releaseUnused(): Int {
        check(Looper.myLooper() != Looper.getMainLooper())
        return if (available) nativePurgeUnused() else 0
    }

    private external fun nativePurgeUnused(): Int
}
