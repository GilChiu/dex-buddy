package dev.dexbuddy.client.antics

import com.google.gson.GsonBuilder
import com.google.gson.JsonArray
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import dev.dexbuddy.client.DexBuddyClient
import net.fabricmc.loader.api.FabricLoader
import java.nio.file.Files

/**
 * Everything the companion can do that isn't a button: the phone's Quick guide lists them, it suggests
 * one at the right moment (once each), and using one marks it discovered ("12 of 24 tricks").
 * Saved per player in config/dexbuddy-tricks.json. Client thread.
 */
object Tricks {
    /** [say] is what to say or do; [icon] picks the guide's little picture. */
    class Trick(val id: String, val group: String, val title: String, val say: String, val how: String, val icon: String)

    val ALL = listOf(
        Trick("hey_dex", "Talking", "Call your Dex", "\"Hey Dex\" + your question", "Ask anything about Cobblemon, your team, or where you are.", "mic"),
        Trick("hold_talk", "Talking", "Hold to talk", "Hold left-click with the Dex", "Talk while you hold it, let go to send. No \"Hey Dex\" needed.", "mic"),
        Trick("whats_this", "Talking", "What is this?", "\"What Cobblemon is this?\"", "Look at one and just ask. No \"Hey Dex\" needed.", "eye"),
        Trick("at_dex", "Talking", "Type to your Dex", "@dex in chat", "Start a chat line with @dex. Only you see it.", "chat"),
        Trick("messages", "Talking", "Text your Dex", "Messages > your Dex", "Pinned at the top of Messages, with quick buttons.", "chat"),
        Trick("stop", "Talking", "Stop talking", "\"Stop\"", "Cuts it off mid-sentence. No \"Hey Dex\" needed.", "stop"),
        Trick("quiet", "Talking", "Quiet time", "\"Be quiet for 10 minutes\"", "Voice off for a while. \"You can talk now\" brings it back.", "moon"),
        Trick("scan", "Scanner", "Scan a Cobblemon", "Hold right-click with the Dex", "Zooms in and scans; it's added to your Pokédex too.", "scan"),
        Trick("phone", "Scanner", "Open the phone", "Tap right-click with the Dex", "Gallery, Feed, Messages, Wardrobe and more.", "phone"),
        Trick("photo", "Camera", "Photo of you", "\"Take a photo of me\"", "It flies into position: close-up, portrait or wide.", "camera"),
        Trick("tripod", "Camera", "Tripod mode", "\"Tripod mode\"", "It stays put while you pose. \"Cheese\" shoots, \"done\" ends.", "tripod"),
        Trick("video", "Camera", "Video of you", "\"Take a video of me\"", "It films you until you say \"stop\" (15 s max).", "film"),
        Trick("clip", "Camera", "Record a clip", "Camera app: hold the shutter", "Tap for a photo, hold to record what you see.", "film"),
        Trick("follow", "Companion", "Stay and follow", "\"Stay here\" / \"Follow me\"", "It waits where it is, or comes along.", "paw"),
        Trick("light", "Companion", "Flashlight", "\"Light the way\"", "It glows so you can see in caves. Also on the phone.", "bulb"),
        Trick("hide", "Companion", "Hide and seek", "\"Let's play hide and seek\"", "It hides nearby. Find it for a costume bonus!", "paw"),
        Trick("quiz", "Companion", "Who's that Cobblemon?", "\"Quiz me\"", "Guess the silhouette. Build a streak!", "quiz"),
        Trick("music", "Companion", "Play music", "\"Play lofi beats\"", "Or the phone's Music pill. Friends nearby hear it fade with distance.", "music"),
        Trick("name", "Companion", "Name your Dex", "\"Your name is Sparky\"", "It answers to its new name.", "heart"),
        Trick("wear", "Companion", "Change costumes", "\"Wear the goth bat\"", "Or pick one in the Wardrobe app.", "shirt"),
        Trick("pet", "Companion", "Pet and feed", "Sneak + right-click it", "Redstone charges it: gifts and +10% battle EXP.", "heart"),
        Trick("friends", "Social", "Add a friend", "Sneak + right-click a player", "Holding your Dex. Or Friends > Find friends nearby.", "people"),
        Trick("save_photo", "Social", "Keep a friend's photo", "Open it > Save to my gallery", "Then post it or make it your profile picture.", "camera"),
    )

    private val file = FabricLoader.getInstance().configDir.resolve("dexbuddy-tricks.json")
    private val discovered = HashSet<String>()
    private val tipped = HashSet<String>()
    private var loaded = false

    private fun load() {
        if (loaded) return
        loaded = true
        try {
            if (!Files.exists(file)) return
            val o = JsonParser.parseString(Files.readString(file)).asJsonObject
            o.getAsJsonArray("discovered")?.forEach { discovered += it.asString }
            o.getAsJsonArray("tipped")?.forEach { tipped += it.asString }
        } catch (e: Exception) { DexBuddyClient.LOG.warn("[dexbuddy] couldn't read tricks: {}", e.javaClass.simpleName) }
    }

    private fun save() {
        try {
            val o = JsonObject().apply {
                add("discovered", JsonArray().also { a -> discovered.sorted().forEach { a.add(it) } })
                add("tipped", JsonArray().also { a -> tipped.sorted().forEach { a.add(it) } })
            }
            Files.createDirectories(file.parent)
            Files.writeString(file, GsonBuilder().setPrettyPrinting().create().toJson(o))
        } catch (e: Exception) { DexBuddyClient.LOG.warn("[dexbuddy] couldn't save tricks: {}", e.javaClass.simpleName) }
    }

    fun isDiscovered(id: String): Boolean { load(); return id in discovered }
    val discoveredCount: Int get() { load(); return ALL.count { it.id in discovered } }

    /** The Trainer just did it: mark it discovered (no more tips for it). */
    fun used(id: String) {
        load()
        if (discovered.add(id)) save()
    }

    // ---- tips at the right moment ------------------------------------------------------------------

    private val counters = HashMap<String, Int>()

    /** Counts something the Trainer does; after [after] times, suggests [trick] (once, if not discovered). */
    fun nudge(event: String, after: Int, trick: String, line: String) {
        val n = (counters[event] ?: 0) + 1
        counters[event] = n
        if (n >= after) suggest(trick, line)
    }

    /** Says a tip about [trick], once ever, and only if the Trainer hasn't found it themselves. */
    fun suggest(trick: String, line: String): Boolean {
        load()
        if (trick in discovered || trick in tipped) return false
        if (!Speaker.say("tip", line, 0, urgent = false)) return false
        tipped += trick
        save()
        return true
    }

    /** "What can you do?": a short spoken taste, and where to find the rest. */
    fun overview(): String =
        "Lots! Hold right-click with me to scan a Cobblemon, hold left-click to talk, say tripod mode for photos, " +
            "or ask me to take a video. Open my phone's Settings and tap Quick guide for every trick!"
}
