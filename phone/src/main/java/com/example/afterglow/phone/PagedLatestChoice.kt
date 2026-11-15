package com.example.afterglow.phone

/** Retains one candidate, never an accumulated health history. A partial scan is not authoritative. */
internal class PagedLatestChoice<T>(private val maxPages: Int = 5, private val timestamp: (T) -> Long?) {
    init { require(maxPages in 1..10) }
    private var candidate: T? = null
    private var candidateTime: Long? = null
    private val seenTokens = HashSet<String>()
    var pagesRead = 0
        private set
    var nextPageToken: String? = null
        private set
    var complete = false
        private set
    var repeatedToken = false
        private set

    val canReadNextPage: Boolean get() = !complete && !repeatedToken && pagesRead < maxPages
    val latest: T? get() = if (complete) candidate else null

    fun acceptPage(values: Sequence<T>, followingToken: String?) {
        check(canReadNextPage)
        values.forEach { value ->
            val time = timestamp(value)
            if (time != null && (candidateTime == null || time > candidateTime!!)) {
                candidate = value; candidateTime = time
            }
        }
        pagesRead++
        // Health Connect providers can return either null or an empty terminal token.
        nextPageToken = followingToken?.takeIf { it.isNotEmpty() }
        complete = nextPageToken == null
        nextPageToken?.let { repeatedToken = !seenTokens.add(it) }
    }
}
