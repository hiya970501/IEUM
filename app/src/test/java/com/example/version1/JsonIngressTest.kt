package com.example.version1

import org.junit.Assert.assertThrows
import org.junit.Test

class JsonIngressTest {
    @Test fun escapedQuotesAndBracketsInStringsDoNotChangeContainerDepth() {
        JsonIngress.requireObject("""{"label":"escaped \" [ { ] }","a":[{"n":1}]}""",8192,3)
    }
    @Test fun rejectsDeepDocumentsBeforeRecursiveJsonParserRuns() {
        assertThrows(IllegalArgumentException::class.java) {
            JsonIngress.requireObject("{\"x\":".repeat(1000)+"1"+"}".repeat(1000),8192,8)
        }
    }
    @Test fun rejectsTrailingDocumentsMismatchedBracketsAndUtf8Oversize() {
        for(raw in listOf("{}{}","{]","{\"x\":\"unfinished}","[]"))
            assertThrows(IllegalArgumentException::class.java) { JsonIngress.requireObject(raw,8192,8) }
        assertThrows(IllegalArgumentException::class.java) {
            JsonIngress.requireObject("{\"x\":\""+"가".repeat(30)+"\"}",64,8)
        }
    }
}
