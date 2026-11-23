package dev.dexbuddy.client.knowledge

import java.text.Normalizer

object Names {
    private val marks = Regex("\\p{M}+")
    private val nonAlnum = Regex("[^a-z0-9]")

    /** "Flabébé" -> "flabebe", "Ho-Oh" -> "hooh", "Mr. Mime" -> "mrmime". */
    fun normalize(s: String): String =
        nonAlnum.replace(marks.replace(Normalizer.normalize(s.lowercase(), Normalizer.Form.NFD), ""), "")

    /**
     * Consonant skeleton of a spoken name, for sound-alike matching: speech-to-text keeps the consonants
     * of a word it doesn't know but swaps vowels and splits words ("Nuzleaf" heard as "anna's leaf":
     * both are "nslf"). Similar-sounding consonants share a letter; vowels, w, h and y are dropped.
     */
    fun skeleton(s: String): String {
        var t = normalize(s).filter { it in 'a'..'z' }
        t = t.replace("ph", "f").replace("ck", "k").replace("gh", "g").replace("qu", "kw").replace("x", "ks").replace("th", "t")
        val out = StringBuilder()
        for (i in t.indices) {
            val c = t[i]
            val mapped = when (c) {
                'a', 'e', 'i', 'o', 'u', 'y', 'w', 'h' -> null
                'c' -> if (t.getOrNull(i + 1) in setOf('e', 'i', 'y')) 's' else 'k'
                'q' -> 'k'
                'z' -> 's'
                'b' -> 'p'
                'd' -> 't'
                'v' -> 'f'
                else -> c
            } ?: continue
            if (out.lastOrNull() != mapped) out.append(mapped)
        }
        return out.toString()
    }

    fun levenshtein(a: String, b: String, max: Int): Int {
        if (kotlin.math.abs(a.length - b.length) > max) return max + 1
        var prev = IntArray(b.length + 1) { it }
        var cur = IntArray(b.length + 1)
        for (i in 1..a.length) {
            cur[0] = i
            var rowMin = cur[0]
            for (j in 1..b.length) {
                val cost = if (a[i - 1] == b[j - 1]) 0 else 1
                cur[j] = minOf(prev[j] + 1, cur[j - 1] + 1, prev[j - 1] + cost)
                if (cur[j] < rowMin) rowMin = cur[j]
            }
            if (rowMin > max) return max + 1
            val t = prev; prev = cur; cur = t
        }
        return prev[b.length]
    }

    /** Edits allowed when fuzzy-matching a spoken name of this length. */
    fun fuzzBudget(len: Int): Int = when {
        len >= 8 -> 2
        len >= 5 -> 1
        else -> 0
    }
}
