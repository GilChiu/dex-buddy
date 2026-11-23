package dev.dexbuddy.client.antics

import dev.dexbuddy.client.BuddyConfig
import dev.dexbuddy.client.brain.BuddyBrain
import dev.dexbuddy.client.tts.Tts
import dev.dexbuddy.client.tts.VoicePlayer
import dev.dexbuddy.client.ui.Subtitles
import dev.dexbuddy.client.voice.PushToTalk
import net.minecraft.client.Minecraft

/**
 * The companion speaking up on its own (reactions, commentary, moods). Keeps it charming, not spammy:
 * one global gap between remarks, a cooldown per kind of remark, and it never talks over an answer,
 * over you, or while you're mid-question. Client thread.
 */
object Speaker {
    private var lastAny = 0L
    private val lastByKind = HashMap<String, Long>()

    private val settings get() = BuddyConfig.settings.personality

    /** 0 = silent, 1 = normal, 2 = very chatty. Shortens or stretches every cooldown. */
    private fun chattiness() = settings.chattiness.coerceIn(0.0, 2.0)

    fun ready(kind: String, cooldownMs: Long): Boolean {
        val c = chattiness()
        if (c <= 0.0 || !settings.reactions) return false
        val now = System.currentTimeMillis()
        val gap = (9_000 / c).toLong()
        return now - lastAny >= gap && now - (lastByKind[kind] ?: 0L) >= (cooldownMs / c).toLong()
    }

    /**
     * Says [line] if the moment is right. [urgent] lines (a shiny! you died!) skip the global gap and
     * interrupt idle chatter, but still never interrupt a real answer.
     */
    fun say(kind: String, line: String, cooldownMs: Long = 60_000, urgent: Boolean = false): Boolean {
        val mc = Minecraft.getInstance()
        val player = mc.player ?: return false
        if (!settings.reactions || chattiness() <= 0.0) return false
        if (!SpeakCategory.allowed(kind)) return false
        // Told to be quiet for a while: no chiming in (answers to your questions still show as text).
        if (dev.dexbuddy.client.tts.Hush.active) return false
        if (!urgent && !ready(kind, cooldownMs)) return false
        if (urgent && System.currentTimeMillis() - (lastByKind[kind] ?: 0L) < cooldownMs / 3) return false
        if (BuddyBrain.isBusy || PushToTalk.state != PushToTalk.State.IDLE) return false
        if (VoicePlayer.speaking && !urgent) return false
        // The companion lives in the Dex: it only chimes in when the Dex is with you or out.
        if (!PushToTalk.hasDevice(player)) return false
        val now = System.currentTimeMillis()
        lastAny = now
        lastByKind[kind] = now
        Mood.noteActivity()
        val shown = line.replace(Regex("^\\s*Bzzt[!.]*\\s*", RegexOption.IGNORE_CASE), "").ifBlank { line }
        Tts.beginReply(System.nanoTime(), null)
        if (!Tts.say(line) { Subtitles.push(shown) }) Subtitles.push(shown)
        return true
    }

    fun pick(lines: List<String>, vararg args: Pair<String, String>): String {
        var l = lines.random()
        for ((k, v) in args) l = l.replace("{$k}", v)
        return l.replace("{name}", dev.dexbuddy.client.entity.CompanionClient.companionName)
            .replace("{trainer}", BuddyConfig.settings.preferredName.ifBlank { Minecraft.getInstance().player?.gameProfile?.name ?: "Trainer" })
    }
}
