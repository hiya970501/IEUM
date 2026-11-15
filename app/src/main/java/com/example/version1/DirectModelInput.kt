package com.example.version1

import java.io.InputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder

/** One fully touched direct input, with only bounded scratch space on the managed heap. */
internal object DirectModelInput {
    fun read(input: InputStream, size: Long): ByteBuffer {
        require(size in 20L..MemoryBudgetPolicy.MAX_GLB_BYTES)
        val result = ByteBuffer.allocateDirect(size.toInt()).order(ByteOrder.nativeOrder())
        return fill(input, size, result)
    }

    fun fill(input: InputStream, size: Long, result: ByteBuffer): ByteBuffer {
        require(size in 20L..MemoryBudgetPolicy.MAX_GLB_BYTES)
        require(result.isDirect && !result.isReadOnly && result.position() == 0 &&
            result.capacity().toLong() == size && result.limit().toLong() == size)
        result.order(ByteOrder.nativeOrder())
        val scratch = ByteArray(minOf(size.toInt(), 16 * 1024))
        while (result.hasRemaining()) {
            val count = input.read(scratch, 0, minOf(scratch.size, result.remaining()))
            check(count > 0) { "Vehicle model ended before its validated size" }
            result.put(scratch, 0, count)
        }
        check(input.read() == -1) { "Vehicle model exceeds its validated size" }
        result.flip()
        return result
    }
}
