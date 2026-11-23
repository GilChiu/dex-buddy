package dev.dexbuddy.client.brain

/**
 * Cuts a token stream into speakable chunks as soon as each one is complete: at . ! ? (followed by a
 * space) or, for long run-on sentences, at a comma once 12+ words have built up.
 */
class SentenceSplitter(private val emit: (String) -> Unit) {
    private val buf = StringBuilder()

    fun feed(text: String) {
        buf.append(text)
        while (true) {
            val cut = boundary() ?: return
            val chunk = buf.substring(0, cut).trim()
            buf.delete(0, cut)
            if (chunk.isNotEmpty()) emit(chunk)
        }
    }

    fun flush() {
        val rest = buf.toString().trim()
        buf.setLength(0)
        if (rest.isNotEmpty()) emit(rest)
    }

    /** Index just past the end of the first complete chunk, or null if none is complete yet. */
    private fun boundary(): Int? {
        var words = 0
        var inWord = false
        for (i in buf.indices) {
            val c = buf[i]
            if (c.isWhitespace()) { inWord = false } else if (!inWord) { inWord = true; words++ }
            val next = buf.getOrNull(i + 1) ?: return null // need to see what follows
            if ((c == '.' || c == '!' || c == '?') && next.isWhitespace()) return i + 1
            if (c == '\n') return i + 1
            if ((c == ',' || c == ';') && next.isWhitespace() && words >= 12) return i + 1
        }
        return null
    }
}
