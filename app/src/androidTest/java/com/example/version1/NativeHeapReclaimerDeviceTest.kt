package com.example.version1

import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.nio.ByteBuffer

@RunWith(AndroidJUnit4::class)
class NativeHeapReclaimerDeviceTest {
    @Test fun publicAllocatorPurgePreservesLiveDirectMemory() {
        // Instrumentation worker; no renderer or vehicle command.
        val live = ByteBuffer.allocateDirect(2 * 1024 * 1024)
        for (offset in 0 until live.capacity() step 4) live.putInt(offset, offset xor 0x5a63d129)
        repeat(3) {
            assertTrue("Public allocator purge unsupported", NativeHeapReclaimer.releaseUnused() in 1..2)
            for (offset in 0 until live.capacity() step 4) assertEquals(offset xor 0x5a63d129, live.getInt(offset))
        }
    }
}
