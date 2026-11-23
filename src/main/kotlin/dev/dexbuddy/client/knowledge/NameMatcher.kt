package dev.dexbuddy.client.knowledge

import com.cobblemon.mod.common.api.abilities.AbilityTemplate
import com.cobblemon.mod.common.api.moves.MoveTemplate
import com.cobblemon.mod.common.api.pokemon.PokemonSpecies
import com.cobblemon.mod.common.pokemon.Species

/**
 * Finds Pokémon, move, ability and item names in what the Trainer said, so their data cards can be put in
 * the prompt before the model is called (no tool round-trip on the hot path).
 */
object NameMatcher {
    sealed class Hit {
        /** [heardAs] is set when only the sound matched ("anna's leaf" for Nuzleaf): a likely guess, not certain. */
        data class Pokemon(val species: Species, val heardAs: String? = null) : Hit()
        data class Move(val move: MoveTemplate) : Hit()
        data class Ability(val ability: AbilityTemplate) : Hit()
        data class Item(val item: KnowledgeIndex.ItemEntry) : Hit()
    }

    private const val MAX_HITS = 5
    private const val MAX_NGRAM = 3

    /** Words that make a one-word move/ability/item name likely meant ("is Protect a good move"). */
    private val hintWords = setOf(
        "move", "moves", "attack", "attacks", "ability", "abilities", "item", "items", "learn", "learns",
        "tm", "tms", "hold", "held", "use", "uses", "teach", "know", "knows", "craft", "recipe",
    )

    /** Common words that are also species names; only matched when nothing else explains them. */
    private val commonWords = setOf("ditto", "mew", "onix", "golem", "slowpoke", "hypno", "mime", "unown", "rotom")

    /** Everyday words a one-letter typo away from a species name; never fuzzy-matched. */
    private val noFuzz = setOf(
        "about", "there", "their", "where", "which", "would", "could", "should", "these", "those", "think",
        "thing", "things", "really", "pokemon", "going", "doing", "today", "right", "great", "place", "water",
        "night", "world", "other", "after", "first", "rotom", "trainer", "please", "thanks", "never", "every",
        "again", "still", "might", "maybe", "being", "while", "around", "nearby", "biome", "spawn", "spawns",
        "evolve", "evolves", "weak", "strong", "against", "level", "levels", "shiny", "legendary", "type",
        "types", "moves", "ability", "school", "today", "rough", "doing", "hello", "sorry", "little", "mylittle",
    )

    private val dexNumber = Regex("(?:#|number |no\\.? ?)(\\d{1,4})", RegexOption.IGNORE_CASE)

    fun find(text: String): List<Hit> {
        val idx = KnowledgeIndex.get()
        val hits = LinkedHashSet<Hit>()
        val lower = text.lowercase()
        val words = lower.split(Regex("\\s+")).map { Names.normalize(it) }.filter { it.isNotEmpty() }
        val hinted = words.any { it in hintWords }
        // Talking *to* the companion ("hey Volty") never looks anything up, whatever it's named.
        val companion = Names.normalize(dev.dexbuddy.client.entity.CompanionClient.companionName)
        val used = BooleanArray(words.size) { words[it] == companion }

        dexNumber.findAll(text).forEach { m ->
            val n = m.groupValues[1].toInt()
            PokemonSpecies.species.firstOrNull { it.nationalPokedexNumber == n }?.let { hits += Hit.Pokemon(it) }
        }

        // Exact multi-word first ("thunder punch", "mr mime"), longest windows win.
        for (n in MAX_NGRAM downTo 1) {
            for (i in 0..words.size - n) {
                if ((i until i + n).any { used[it] }) continue
                val key = words.subList(i, i + n).joinToString("")
                val hit = exact(key, idx, n, hinted) ?: continue
                hits += hit
                for (j in i until i + n) used[j] = true
            }
        }

        // Speech-to-text misspells Pokémon names, so species also get a fuzzy pass.
        for (i in words.indices) {
            if (used[i]) continue
            for (n in 2 downTo 1) {
                if (i + n > words.size || (i until i + n).any { used[it] }) continue
                val key = words.subList(i, i + n).joinToString("")
                if (key.length < 5) continue
                // An everyday word never anchors a fuzzy match, alone or as half of a pair ("hey volty").
                if ((i until i + n).any { words[it] in noFuzz || words[it] in commonWords }) continue
                val sp = KnowledgeIndex.fuzzy(key, idx.species) ?: continue
                hits += Hit.Pokemon(sp)
                for (j in i until i + n) used[j] = true
                break
            }
        }

        // Last, sound-alikes: speech-to-text turns names it doesn't know into ordinary words
        // ("Nuzleaf" -> "anna's leaf"). Same consonant skeleton = a likely guess, marked as such.
        val originals = lower.split(Regex("\\s+")).filter { Names.normalize(it).isNotEmpty() }
        var guesses = 0
        for (n in MAX_NGRAM downTo 1) {
            for (i in 0..words.size - n) {
                if (guesses >= MAX_GUESSES) break
                val window = (i until i + n)
                if (window.any { used[it] }) continue
                // A name never starts or ends on a filler word ("what anna's leaf", "i'm going to").
                val edge = listOf(words[i], words[i + n - 1])
                if (edge.any { it in fillerWords || it in noFuzz }) continue
                val sound = Names.skeleton(words.subList(i, i + n).joinToString(""))
                if (sound.length < 4) continue
                val sp = soundMatch(sound, idx) ?: continue
                if (hits.any { it is Hit.Pokemon && it.species == sp }) continue
                hits += Hit.Pokemon(sp, heardAs = window.joinToString(" ") { originals.getOrElse(it) { words[it] } })
                for (j in window) used[j] = true
                guesses++
            }
        }
        return hits.take(MAX_HITS)
    }

    private const val MAX_GUESSES = 2

    /** Short everyday words that never make up a Pokémon name on their own. */
    private val fillerWords = setOf(
        "a", "an", "the", "is", "are", "was", "what", "whats", "who", "how", "why", "do", "does", "you", "your",
        "my", "me", "i", "it", "its", "to", "of", "in", "on", "at", "and", "or", "for", "with", "know", "tell",
        "about", "this", "that", "can", "get", "find", "where", "when", "hey", "hi", "yo", "please", "like", "one",
        "im", "ive", "id", "going", "gonna", "want", "wanna", "have", "had", "be", "been", "will", "would", "should",
        "we", "they", "he", "she", "them", "our", "us", "so", "but", "if", "then", "there", "here", "now", "just",
        "really", "very", "some", "any", "all", "more", "most", "best", "good", "bad", "new", "old", "big", "little",
        "day", "today", "tomorrow", "time", "way", "thing", "think", "said", "say", "see", "look", "go", "come",
    )

    /**
     * Exact skeleton match only. Allowing even one sound of difference turned made-up names into real
     * ones ("Blorbazor" -> Bulbasaur) and chat into Pokémon ("team doing" -> Metang).
     */
    private fun soundMatch(sound: String, idx: KnowledgeIndex.Built): Species? =
        idx.speciesBySound[sound]?.let { list -> list.firstOrNull { it.implemented } ?: list.first() }

    private fun exact(key: String, idx: KnowledgeIndex.Built, wordCount: Int, hinted: Boolean): Hit? {
        idx.species[key]?.let { if (wordCount > 1 || key !in commonWords || hinted) return Hit.Pokemon(it) }
        // One-word move/ability/item names are often ordinary words ("rest", "protect", "apple").
        val allowSingle = wordCount > 1 || hinted || key.length >= 8
        if (!allowSingle) return null
        idx.moves[key]?.let { return Hit.Move(it) }
        idx.abilities[key]?.let { return Hit.Ability(it) }
        idx.items[key]?.let { return Hit.Item(it) }
        return null
    }
}
