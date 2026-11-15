package com.example.version1

import android.os.Debug
import java.io.File

object ProcessMemoryReader {
    /** Self-only accounting. VmHWM catches resident peaks that fell between external samples. */
    fun read(): ProcessMemorySnapshot? = runCatching {
        var rss = 0L
        var highWater = 0L
        File("/proc/self/status").useLines { lines ->
            lines.forEach { line ->
                if (line.startsWith("VmRSS:") || line.startsWith("VmHWM:")) {
                    val kb = line.substringAfter(':').trim().substringBefore(' ').toLongOrNull() ?: 0L
                    if (line.startsWith("VmRSS:")) rss = kb * 1024L else highWater = kb * 1024L
                }
            }
        }
        val pss = Debug.getPss() * 1024L
        check(rss > 0 && pss > 0)
        ProcessMemorySnapshot(rss, pss, highWater)
    }.getOrNull()
}
