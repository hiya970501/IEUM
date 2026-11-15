package com.example.version1

import android.content.Context
import android.os.Build
import java.io.File
import java.io.RandomAccessFile

/** Read-only map inspection, including Android's direct APK native loading. */
internal object NativeLibraryMappingTestSupport {
    fun isMapped(context: Context, library: String): Boolean {
        val maps = File("/proc/self/maps").readLines()
        if (maps.any { it.substringAfterLast('/').trim() == library && it.split(Regex("\\s+"))[1].contains('x') }) return true
        val app = context.applicationInfo
        val paths = listOf(app.sourceDir) + app.splitSourceDirs.orEmpty()
        for (path in paths) {
            val ranges = Build.SUPPORTED_ABIS.mapNotNull { abi -> entryRange(path, "lib/$abi/$library") }
            for (line in maps) {
                val columns = line.trim().split(Regex("\\s+"), limit = 6)
                if (columns.size != 6 || !columns[1].contains('x') || columns[5] != path) continue
                val offset = columns[2].toLong(16)
                if (ranges.any { offset in it }) return true
            }
        }
        return false
    }

    private fun entryRange(path: String, entry: String): LongRange? = RandomAccessFile(path, "r").use { file ->
        fun u16(): Int = file.readUnsignedByte() or (file.readUnsignedByte() shl 8)
        fun u32(): Long = u16().toLong() or (u16().toLong() shl 16)
        var eocd = -1L
        for (position in (file.length() - 22) downTo maxOf(0L, file.length() - 65_557)) {
            file.seek(position)
            if (u32() == 0x06054b50L) {
                file.seek(position + 20)
                if (position + 22 + u16() == file.length()) { eocd = position; break }
            }
        }
        check(eocd >= 0) { "APK end record missing" }
        file.seek(eocd + 10); val count = u16()
        file.seek(eocd + 16); var position = u32()
        repeat(count) {
            file.seek(position); check(u32() == 0x02014b50L) { "Invalid APK central directory" }
            file.seek(position + 10); val method = u16()
            file.seek(position + 20); val size = u32()
            file.seek(position + 28); val nameSize = u16(); val extraSize = u16(); val commentSize = u16()
            file.seek(position + 42); val local = u32()
            file.seek(position + 46); val name = ByteArray(nameSize).also(file::readFully).toString(Charsets.UTF_8)
            if (name == entry) {
                check(method == 0) { "Native APK mapping requires an uncompressed entry" }
                file.seek(local); check(u32() == 0x04034b50L)
                file.seek(local + 26); val localName = u16(); val localExtra = u16()
                val start = local + 30 + localName + localExtra
                return start until (start + size)
            }
            position += 46 + nameSize + extraSize + commentSize
        }
        null
    }
}
