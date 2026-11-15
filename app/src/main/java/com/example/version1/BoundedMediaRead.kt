package com.example.version1

import java.util.concurrent.Future
import java.util.concurrent.FutureTask
import java.util.concurrent.RejectedExecutionException
import java.util.concurrent.SynchronousQueue
import java.util.concurrent.ThreadPoolExecutor
import java.util.concurrent.TimeUnit

/** A cancelled Binder call may still run. Never queue another or grow a worker per dialog. */
internal class BoundedMediaRead {
    private val executor = ThreadPoolExecutor(0, 1, 30, TimeUnit.SECONDS,
        SynchronousQueue(), { work -> Thread(work, "ieum-media-read").apply { isDaemon = true } })

    fun <T> submit(read: () -> T, complete: (Result<T>) -> Unit): Future<*>? {
        val task = FutureTask { complete(runCatching(read)) }
        return try { executor.execute(task); task } catch (_: RejectedExecutionException) { null }
    }

    companion object { val shared = BoundedMediaRead() }
}
