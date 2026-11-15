package com.example.version1

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.cancel

/** Process metadata only: bounded class keys, one producer, no API instances or Activity references. */
@OptIn(ExperimentalCoroutinesApi::class)
internal class BackgroundPreparationCache(
    private val capacity: Int = 8,
    private val operation: (Class<*>) -> Unit
) : AutoCloseable {
    init { require(capacity in 1..16) }
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default.limitedParallelism(1))
    private val tasks = mutableMapOf<Class<*>, Deferred<Unit>>()
    private var closed = false

    @Synchronized fun prepare(type: Class<*>): Deferred<Unit> {
        check(!closed)
        tasks[type]?.let { return it }
        check(tasks.size < capacity) { "Preparation cache capacity reached" }
        return scope.async(start = CoroutineStart.LAZY) { operation(type) }.also { tasks[type] = it }
    }

    @Synchronized override fun close() {
        closed = true; scope.cancel(); tasks.clear()
    }
}

internal class SdkApiPreparation(private val tasks: List<Deferred<Unit>>) {
    suspend fun await() { tasks.forEach { it.await() } }
}
