package dev.dexbuddy.client.phone

import dev.dexbuddy.client.BuddyConfig
import dev.dexbuddy.client.brain.BuddyBrain
import dev.dexbuddy.client.entity.CompanionClient
import dev.dexbuddy.client.voice.PushToTalk
import net.minecraft.ChatFormatting
import net.minecraft.client.Minecraft
import net.minecraft.network.chat.Component

/**
 * The phone's pinned chat with your companion: what you *type* to it (in this chat, or "@dex" in game
 * chat) and its answers. Voice questions, scans and its own remarks don't go here. Kept for this play
 * session. Client thread.
 */
object VoltyChat {
    class Line(val mine: Boolean, val text: String, val time: Long)

    /** Where a typed question came from. */
    const val NONE = 0
    const val PHONE = 1
    const val CHAT = 2

    private const val KEEP = 150
    val lines = ArrayList<Line>()
    /** Bumped on every new line (the open chat scrolls to the bottom). */
    var version = 0; private set
    /** Answers that arrived while the chat was closed (the pinned row's badge). */
    var unread = 0; private set

    /** True while the phone's chat with the companion is open. */
    var open = false

    /** The source of the question about to be asked (taken by [take] when BuddyBrain starts on it). */
    private var pending = NONE
    /** Set while a typed question is answered on the spot (a quick command), so its reply is kept. */
    var syncSource = NONE

    private val PREFIX = Regex("^\\s*@(\\S+)\\s*(.*)$", RegexOption.DOT_MATCHES_ALL)

    /** BuddyBrain calls this as it starts on a question: where it was typed (or [NONE] for voice). */
    fun take(): Int { val s = pending; pending = NONE; return s }

    fun user(text: String) = add(Line(true, text, System.currentTimeMillis()), NONE)

    /** An answer to a typed question; answers to "@dex" also go back into game chat. */
    fun answer(text: String, source: Int) {
        if (source == NONE || text.isBlank()) return
        add(Line(false, text, System.currentTimeMillis()), source)
        if (source == CHAT) chat(Component.literal("[${CompanionClient.companionName}] ").withStyle(ChatFormatting.GOLD)
            .append(Component.literal(text).withStyle(ChatFormatting.AQUA)))
    }

    private fun add(l: Line, source: Int) {
        val mc = Minecraft.getInstance()
        if (!mc.isSameThread) { mc.execute { add(l, source) }; return }
        lines += l
        while (lines.size > KEEP) lines.removeAt(0)
        version++
        // Only replies to questions typed on the phone count as unread (@dex answers are already in chat).
        if (!l.mine && !open && source == PHONE) unread++
    }

    fun markRead() { unread = 0 }

    /** Asks from the phone's chat. */
    fun send(text: String) {
        val t = text.trim()
        if (t.isEmpty()) return
        pending = PHONE
        dev.dexbuddy.client.antics.Tricks.used("messages")
        BuddyBrain.ask(t)
    }

    /**
     * A chat line starting with "@dex" (or "@" + your companion's name): sent to your companion instead
     * of the server, so other players never see it. Returns true when it was taken.
     */
    fun fromChatLine(message: String): Boolean {
        val m = PREFIX.find(message) ?: return false
        val tag = m.groupValues[1].lowercase().trimEnd(',', ':', '!', '?', '.')
        val name = CompanionClient.companionName.lowercase().replace(" ", "")
        if (tag != "dex" && tag != name && tag != "volty") return false
        val question = m.groupValues[2].trim()
        val mc = Minecraft.getInstance()
        val player = mc.player ?: return false
        if (question.isEmpty()) {
            chat(Component.literal("Type your question after @dex, like: @dex what is this weak to?").withStyle(ChatFormatting.GRAY)); return true
        }
        if (BuddyConfig.settings.voice.requireItem && !PushToTalk.hasDevice(player)) {
            chat(Component.literal("Carry your Dex Buddy (hotbar or out with you) to ask it things.").withStyle(ChatFormatting.GRAY)); return true
        }
        chat(Component.literal("You -> ${CompanionClient.companionName}: ").withStyle(ChatFormatting.DARK_GRAY)
            .append(Component.literal(question).withStyle(ChatFormatting.GRAY)))
        pending = CHAT
        dev.dexbuddy.client.antics.Tricks.used("at_dex")
        BuddyBrain.ask(question)
        return true
    }

    private fun chat(c: Component) {
        val mc = Minecraft.getInstance()
        mc.execute { mc.gui?.chat?.addMessage(c) }
    }
}
