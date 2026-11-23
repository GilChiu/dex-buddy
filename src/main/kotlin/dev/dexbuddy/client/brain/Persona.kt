package dev.dexbuddy.client.brain

import dev.dexbuddy.client.BuddyConfig
import dev.dexbuddy.client.DexBuddyClient
import dev.dexbuddy.client.context.Snapshot
import java.nio.file.Files
import java.nio.file.Path

object Persona {
    private const val RESOURCE = "/assets/dexbuddy/persona.txt"

    /** Built-in persona, or the file at personaOverridePath when that is set and readable. */
    fun template(): String {
        val override = BuddyConfig.settings.personaOverridePath.trim()
        if (override.isNotEmpty()) {
            try {
                return Files.readString(Path.of(override))
            } catch (e: Exception) {
                DexBuddyClient.LOG.warn("[dexbuddy] persona override not readable ({}); using the built-in persona", e.javaClass.simpleName)
            }
        }
        return Persona::class.java.getResourceAsStream(RESOURCE)?.use { String(it.readAllBytes(), Charsets.UTF_8) }
            ?: "You are {companion_name}, a cheerful plasma spirit living in your Trainer's Dex. Keep replies short.\n{auto_context}\n{data_cards}"
    }

    fun render(snap: Snapshot, cards: String): String {
        val s = BuddyConfig.settings
        val length = when (s.replyLength.lowercase()) {
            "normal" -> "Reply length: 2 to 4 sentences unless asked for more."
            "chatty" -> "Reply length: you may chat a little more, up to 5 or 6 sentences."
            else -> "Reply length: 1 to 2 short sentences. Pick the one or two facts that answer the question; never recite stat lists, heights or every ability unless the Trainer asks for details."
        }
        val extra = "\n\n$length\nAlways reply in ${s.language}."
        return template()
            .replace("{companion_name}", dev.dexbuddy.client.entity.CompanionClient.companionName)
            .replace("{player_name}", snap.playerName)
            .replace("{preferred_name}", snap.preferredName)
            .replace("{auto_context}", snap.autoContext() + "\n" + snap.pokedex)
            .replace("{data_cards}", cards.ifBlank { "(none for this question; call a tool if you need data)" }) + extra
    }
}
