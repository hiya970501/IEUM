package com.example.version1

internal object MusicText {
    fun sessionTitle(value: CharSequence?): String = value?.take(200)?.map {
        if (it.isISOControl() || it.isWhitespace() || it in '\u202A'..'\u202E' ||
            it in '\u2066'..'\u2069' || it in listOf('\u200E', '\u200F', '\u061C')) ' ' else it
    }?.joinToString("")?.trim().orEmpty()
    fun title(value: CharSequence?): String = sessionTitle(value).ifBlank { "제목 없음" }

    // Media IDs are opaque provider values: reject rather than truncate or reinterpret them.
    fun validId(value: String?): Boolean = value != null && value.isNotEmpty() &&
        value.length <= 4096 && value.toByteArray(Charsets.UTF_8).size <= 4096 &&
        value.none { it.isISOControl() }
}
