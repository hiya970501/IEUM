package com.example.version1

import org.junit.Assert.*
import org.junit.Test

class ProtectedPreferenceValuesTest {
    @Test fun migrationRetainsTypesPrecisionAndUnicodeExactly() {
        val values = linkedMapOf<String, Any>("운전자 \"이름\"" to "한국어 日本語 العربية\n\\",
            "large" to Long.MAX_VALUE - 7, "small" to Long.MIN_VALUE + 1,
            "integer" to Int.MIN_VALUE, "fractional" to 24.5f, "zero" to -0.0f,
            "boolean" to false, "set" to setOf("", "한글", "quote\""))
        val restored = ProtectedPreferenceValues.decode(ProtectedPreferenceValues.encode(values))
        assertEquals(values, restored)
        assertEquals((-0.0f).toRawBits(), (restored["zero"] as Float).toRawBits())
        assertEquals(Long.MAX_VALUE - 7, restored["large"])
    }
    @Test fun malformedTypesDoNotBecomeDefaultConsentOrTruncatedNumbers() {
        for (input in listOf(
            """{"consent":{"t":"b","v":"yes"}}""",
            """{"counter":{"t":"i","v":"2147483648"}}""",
            """{"identity":{"t":"s","v":123}}""",
            """{"items":{"t":"set","v":["one",123]}}""",
            """{"unknown":{"t":"future","v":"x"}}""",
            """{"extra":{"t":"s","v":"x","unexpected":true}}""")) {
            assertTrue(input, runCatching { ProtectedPreferenceValues.decode(input) }.isFailure)
        }
        assertTrue(runCatching { ProtectedPreferenceValues.encode(mapOf("unsupported" to 1.2)) }.isFailure)
    }
}
