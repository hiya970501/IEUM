package com.example.version1

/** Visible summaries only. Provider metadata and opaque playback IDs remain unchanged. */
internal object MusicDisplayText {
    private val words = Regex("[\\p{IsLatin}0-9]+(?:['’][\\p{IsLatin}0-9]+)*")
    private val japaneseScripts = setOf(Character.UnicodeScript.HAN,
        Character.UnicodeScript.HIRAGANA, Character.UnicodeScript.KATAKANA)

    fun title(value: CharSequence?, browsable: Boolean = false): String {
        val original = MusicText.sessionTitle(value)
        if (original.isBlank()) return ""
        // Reject unpaired UTF-16 fragments in the visible summary. In particular,
        // a provider's bounded canonical title may end halfway through an emoji.
        val valid = buildString {
            original.codePoints().forEach { if (it !in 0xD800..0xDFFF) appendCodePoint(it) }
        }.trim()
        if (valid.isBlank()) return ""
        val suffix = if (browsable) " ›" else ""
        val limit = if (valid.codePoints().anyMatch { Character.UnicodeScript.of(it) in japaneseScripts }) 30 else 80
        val matches = words.findAll(valid).take(25).toList()
        val wordBounded = if (matches.size > 24) valid.substring(0, matches[23].range.last + 1) else valid
        val points = wordBounded.codePoints().toArray()
        val available = limit - suffix.codePointCount(0, suffix.length)
        val truncated = wordBounded != valid || points.size > available
        val count = minOf(points.size, available - if (truncated) 1 else 0)
        val summary = String(points, 0, count).trimEnd()
        return summary + (if (truncated) "…" else "") + suffix
    }

    fun status(message: String, canonicalTitle: String, playback: String): String =
        // State must remain ahead of variable external metadata when a narrow
        // three-line control has to ellipsize the title.
        listOf(message, playback, title(canonicalTitle)).filter { it.isNotBlank() }.joinToString("\n")

    fun accessibleStatus(message: String, canonicalTitle: String, playback: String): String =
        listOf(message, playback, MusicText.sessionTitle(canonicalTitle))
            .filter { it.isNotBlank() }.joinToString("\n")
}
