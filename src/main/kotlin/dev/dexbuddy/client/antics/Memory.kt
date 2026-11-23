package dev.dexbuddy.client.antics

import com.google.gson.GsonBuilder
import com.google.gson.reflect.TypeToken
import dev.dexbuddy.client.DexBuddyClient
import dev.dexbuddy.client.tts.PiperInstaller
import net.minecraft.client.Minecraft
import java.nio.file.Files
import java.nio.file.Path
import java.time.LocalDate

/**
 * What the companion remembers about its Trainer, per world (singleplayer) or per server: facts the
 * Trainer shared (saved when the model calls remember_fact) and a short journal of big moments
 * (first shinies, legendary catches, evolutions). Stored in <game dir>/dexbuddy/memory/.
 */
object Memory {
    data class Entry(val text: String, val date: String, val kind: String)

    private const val MAX_FACTS = 30
    private const val MAX_JOURNAL = 25
    private val gson = GsonBuilder().setPrettyPrinting().disableHtmlEscaping().create()
    private val dir: Path = PiperInstaller.root.resolve("memory")

    @Volatile private var loadedFor: String? = null
    private var entries = mutableListOf<Entry>()

    /** This world's or server's memory file name. */
    private fun key(): String? {
        val mc = Minecraft.getInstance()
        val name = mc.singleplayerServer?.worldData?.levelName?.let { "world-$it" }
            ?: mc.currentServer?.ip?.let { "server-$it" } ?: return null
        return name.replace(Regex("[^A-Za-z0-9._-]"), "_").take(80)
    }

    @Synchronized private fun ensure() {
        val k = key() ?: return
        if (k == loadedFor) return
        loadedFor = k
        entries = try {
            val f = dir.resolve("$k.json")
            if (Files.exists(f)) gson.fromJson<MutableList<Entry>>(Files.readString(f), object : TypeToken<MutableList<Entry>>() {}.type) ?: mutableListOf()
            else mutableListOf()
        } catch (e: Exception) {
            DexBuddyClient.LOG.warn("[dexbuddy] couldn't read memory: {}", e.toString()); mutableListOf()
        }
    }

    @Synchronized private fun save() {
        val k = loadedFor ?: return
        try {
            Files.createDirectories(dir)
            Files.writeString(dir.resolve("$k.json"), gson.toJson(entries))
        } catch (e: Exception) {
            DexBuddyClient.LOG.warn("[dexbuddy] couldn't save memory: {}", e.toString())
        }
    }

    /** A fact the Trainer shared ("my favorite is Eevee"). Replaces a near-duplicate. */
    @Synchronized fun rememberFact(fact: String): String {
        ensure()
        val clean = fact.trim().take(160)
        if (clean.isEmpty()) return "Nothing to remember."
        entries.removeAll { it.kind == "fact" && it.text.equals(clean, true) }
        entries += Entry(clean, LocalDate.now().toString(), "fact")
        trim()
        save()
        return "Saved to memory: $clean"
    }

    /** A big moment worth bringing up later. */
    @Synchronized fun note(event: String) {
        ensure()
        entries += Entry(event.take(160), LocalDate.now().toString(), "journal")
        trim()
        save()
    }

    @Synchronized fun forgetAll() { ensure(); entries.clear(); save() }

    private fun trim() {
        val facts = entries.filter { it.kind == "fact" }.takeLast(MAX_FACTS)
        val journal = entries.filter { it.kind == "journal" }.takeLast(MAX_JOURNAL)
        entries = (facts + journal).sortedBy { it.date }.toMutableList()
    }

    /** For the prompt: compact and newest last. Empty when there's nothing yet. */
    @Synchronized fun forPrompt(): String {
        ensure()
        if (entries.isEmpty()) return ""
        val facts = entries.filter { it.kind == "fact" }.joinToString("\n") { "- ${it.text}" }
        val journal = entries.filter { it.kind == "journal" }.takeLast(10).joinToString("\n") { "- ${it.date}: ${it.text}" }
        return buildString {
            append("\n\nTHINGS YOU REMEMBER ABOUT YOUR TRAINER (bring them up naturally sometimes; never list them)\n")
            if (facts.isNotEmpty()) append(facts).append('\n')
            if (journal.isNotEmpty()) append("Adventures together:\n").append(journal)
        }
    }

    fun count(): Int { ensure(); return entries.size }
}
