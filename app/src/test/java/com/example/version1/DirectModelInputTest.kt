package com.example.version1

import java.io.ByteArrayInputStream
import java.io.InputStream
import java.nio.ByteOrder
import java.nio.ByteBuffer
import org.junit.Assert.*
import org.junit.Test

class DirectModelInputTest {
    @Test fun fillsOwnedBufferAndRejectsNonExactWindowsBeforeReading() {
        val bytes = ByteArray(37) { it.toByte() }
        val owned = ByteBuffer.allocateDirect(bytes.size)
        assertSame(owned, DirectModelInput.fill(ByteArrayInputStream(bytes), 37, owned))
        assertEquals(0, owned.position())
        repeat(bytes.size) { assertEquals(bytes[it], owned.get(it)) }
        for (invalid in listOf(ByteBuffer.allocate(37), ByteBuffer.allocateDirect(38),
            ByteBuffer.allocateDirect(37).apply { position(1) },
            ByteBuffer.allocateDirect(37).apply { limit(36) },
            ByteBuffer.allocateDirect(37).asReadOnlyBuffer())) {
            val unread = ByteArrayInputStream(bytes)
            assertThrows(IllegalArgumentException::class.java) { DirectModelInput.fill(unread, 37, invalid) }
            assertEquals(bytes.size, unread.available())
        }
    }
    @Test fun streamsExactPayloadThroughBoundedReadsIntoOneTouchedDirectBuffer() {
        val size = 200_003
        var offset = 0
        var largestRead = 0
        val input = object : InputStream() {
            override fun read(): Int = if (offset == size) -1 else (offset++ % 251)
            override fun read(b: ByteArray, off: Int, len: Int): Int {
                largestRead = maxOf(largestRead, len)
                if (offset == size) return -1
                val count = minOf(len, 7919, size - offset)
                repeat(count) { b[off + it] = (offset++ % 251).toByte() }
                return count
            }
        }
        val buffer = DirectModelInput.read(input, size.toLong())
        assertTrue(buffer.isDirect)
        assertEquals(ByteOrder.nativeOrder(), buffer.order())
        assertEquals(0, buffer.position()); assertEquals(size, buffer.limit())
        assertTrue(largestRead <= 16 * 1024)
        repeat(size) { assertEquals((it % 251).toByte(), buffer.get(it)) }
    }

    @Test fun rejectsTruncatedOversizedAndUnboundedInputs() {
        assertThrows(IllegalStateException::class.java) { DirectModelInput.read(ByteArrayInputStream(ByteArray(19)), 20) }
        assertThrows(IllegalStateException::class.java) { DirectModelInput.read(ByteArrayInputStream(ByteArray(21)), 20) }
        assertThrows(IllegalArgumentException::class.java) {
            DirectModelInput.read(ByteArrayInputStream(byteArrayOf()), MemoryBudgetPolicy.MAX_GLB_BYTES + 1)
        }
    }
}
