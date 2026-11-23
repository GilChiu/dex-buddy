package dev.dexbuddy.client

import dev.dexbuddy.client.brain.SentenceSplitter
import dev.dexbuddy.client.knowledge.Names
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class SplitterAndNamesTest {
    private fun split(vararg chunks: String): List<String> {
        val out = mutableListOf<String>()
        val s = SentenceSplitter { out += it }
        chunks.forEach { s.feed(it) }
        s.flush()
        return out
    }

    @Test
    fun `emits each sentence as soon as the next token shows it is complete`() {
        val out = mutableListOf<String>()
        val s = SentenceSplitter { out += it }
        s.feed("Garchomp is weak to Ice")
        assertTrue(out.isEmpty())
        s.feed(" four times.")
        assertTrue(out.isEmpty(), "waits to see what follows the period")
        s.feed(" Also Dragon")
        assertEquals(listOf("Garchomp is weak to Ice four times."), out)
        s.flush()
        assertEquals(listOf("Garchomp is weak to Ice four times.", "Also Dragon"), out)
    }

    @Test
    fun `decimals and token-split punctuation do not break sentences`() {
        assertEquals(listOf("It weighs 95.0 kilograms!", "Wow?"), split("It weighs 95", ".0 kilo", "grams", "! Wo", "w?"))
    }

    @Test
    fun `long run-on sentences split at a comma after twelve words`() {
        val out = split("one two three four five six seven eight nine ten eleven twelve, thirteen fourteen.")
        assertEquals(listOf("one two three four five six seven eight nine ten eleven twelve,", "thirteen fourteen."), out)
    }

    @Test
    fun `short commas do not split`() {
        assertEquals(listOf("Bzzt, hello there!"), split("Bzzt, hello there!"))
    }

    @Test
    fun `names normalize accents, punctuation and case`() {
        assertEquals("flabebe", Names.normalize("Flabébé"))
        assertEquals("hooh", Names.normalize("Ho-Oh"))
        assertEquals("mrmime", Names.normalize("Mr. Mime"))
        assertEquals("farfetchd", Names.normalize("Farfetch’d"))
    }

    @Test
    fun `sound-alike skeletons match what speech-to-text actually wrote`() {
        val nuzleaf = Names.skeleton("Nuzleaf")
        assertEquals(nuzleaf, Names.skeleton("anna's leaf"))
        assertEquals(nuzleaf, Names.skeleton("annas leaf"))
        assertTrue(Names.skeleton("another leaf") != nuzleaf, "a real English phrase stays different")
        assertEquals(Names.skeleton("Garchomp"), Names.skeleton("gar chomp"))
        assertEquals(Names.skeleton("Pikachu"), Names.skeleton("peek a chew"))
    }

    @Test
    fun `fuzzy budget allows speech-to-text slips on longer names only`() {
        assertEquals(1, Names.levenshtein("garchom", "garchomp", 2))
        assertEquals(0, Names.fuzzBudget(4))
        assertEquals(1, Names.fuzzBudget(7))
        assertEquals(2, Names.fuzzBudget(9))
        assertTrue(Names.levenshtein("blorbazor", "garchomp", 2) > 2)
    }
}
