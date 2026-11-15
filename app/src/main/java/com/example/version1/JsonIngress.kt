package com.example.version1

/** Bound parser recursion before allocating JSONObject trees from external input. */
internal object JsonIngress {
    fun requireObject(raw: String, maxBytes: Int, maxDepth: Int) {
        require(maxBytes > 0 && maxDepth in 1..32 && raw.toByteArray(Charsets.UTF_8).size <= maxBytes)
        val stack = ArrayDeque<Char>()
        var quoted=false;var escaped=false;var started=false;var completed=false
        for (c in raw) {
            if(completed){require(c.isWhitespace());continue}
            if(quoted){
                if(escaped) escaped=false
                else if(c=='\\') escaped=true
                else if(c=='"') quoted=false
                continue
            }
            if(!started){
                if(c.isWhitespace()) continue
                require(c=='{');started=true
            }
            when(c){
                '"' -> quoted=true
                '{','[' -> {stack.addLast(c);require(stack.size<=maxDepth)}
                '}',']' -> {
                    require(stack.isNotEmpty() && stack.removeLast()==if(c=='}') '{' else '[')
                    if(stack.isEmpty()) completed=true
                }
            }
        }
        require(started && completed && !quoted && !escaped && stack.isEmpty())
    }
}
