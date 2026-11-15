package com.example.version1

import android.os.SharedMemory
import java.io.Closeable
import java.nio.ByteBuffer
import java.nio.ByteOrder

/** Process-private staging only: no descriptor is exported. Release does not wait for GC. */
internal class MappedModelInput private constructor(
    private val memory: SharedMemory,
    val buffer: ByteBuffer
) : Closeable {
    private var closed = false

    override fun close() {
        if (closed) return
        closed = true
        try { SharedMemory.unmap(buffer) } finally { memory.close() }
    }

    companion object {
        fun allocate(size: Long): MappedModelInput {
            require(size in 20L..MemoryBudgetPolicy.MAX_GLB_BYTES)
            val memory = SharedMemory.create("vehicle-model-input", size.toInt())
            try {
                return MappedModelInput(memory, memory.mapReadWrite().order(ByteOrder.nativeOrder()))
            } catch (error: Throwable) {
                memory.close()
                throw error
            }
        }
    }
}
