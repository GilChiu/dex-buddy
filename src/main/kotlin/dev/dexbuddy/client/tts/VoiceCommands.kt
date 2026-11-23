package dev.dexbuddy.client.tts

import com.mojang.brigadier.arguments.DoubleArgumentType
import com.mojang.brigadier.arguments.IntegerArgumentType
import com.mojang.brigadier.arguments.StringArgumentType
import com.mojang.brigadier.builder.LiteralArgumentBuilder
import com.mojang.brigadier.context.CommandContext
import dev.dexbuddy.client.BuddyConfig
import net.fabricmc.fabric.api.client.command.v2.ClientCommandManager.argument
import net.fabricmc.fabric.api.client.command.v2.ClientCommandManager.literal
import net.fabricmc.fabric.api.client.command.v2.FabricClientCommandSource
import net.minecraft.ChatFormatting
import net.minecraft.network.chat.Component

/**
 * /dexbuddy voice ...: switch and tune the companion's voice in game. Every change is saved and
 * immediately demonstrated with a sample line.
 */
object VoiceCommands {
    fun build(): LiteralArgumentBuilder<FabricClientCommandSource> = literal("voice")
        .executes { show(it) }
        .then(literal("test").executes { Tts.sample(); 1 })
        .then(literal("off").executes { set(it, "voice off") { tts -> tts.enabled = false }; Tts.stop(); 1 })
        .then(literal("on").executes { set(it, "voice on") { tts -> tts.enabled = true }; Tts.sample(); 1 })
        .then(literal("pitch").then(argument("value", DoubleArgumentType.doubleArg(0.7, 1.8)).executes { c ->
            val v = DoubleArgumentType.getDouble(c, "value"); set(c, "pitch $v") { it.pitch = v }; Tts.sample(); 1 }))
        .then(literal("buzz").then(argument("value", DoubleArgumentType.doubleArg(0.0, 1.0)).executes { c ->
            val v = DoubleArgumentType.getDouble(c, "value"); set(c, "buzz $v") { it.buzz = v }; Tts.sample(); 1 }))
        .then(literal("speed").then(argument("value", DoubleArgumentType.doubleArg(0.6, 1.6)).executes { c ->
            val v = DoubleArgumentType.getDouble(c, "value"); set(c, "speed $v") { it.speed = v }; Tts.sample(); 1 }))
        .then(literal("expressiveness").then(argument("value", DoubleArgumentType.doubleArg(0.0, 2.0)).executes { c ->
            val v = DoubleArgumentType.getDouble(c, "value"); set(c, "expressiveness $v") { it.expressiveness = v }; Tts.sample(); 1 }))
        .then(literal("pause").then(argument("ms", IntegerArgumentType.integer(0, 1500)).executes { c ->
            val v = IntegerArgumentType.getInteger(c, "ms"); set(c, "sentence pause ${v}ms") { it.sentencePauseMs = v }; Tts.sample(); 1 }))
        // Teach the voice a word: /dexbuddy voice pronounce Nuzleaf Nuzz leaf
        .then(literal("pronounce").then(argument("word", StringArgumentType.string()).then(argument("soundsLike", StringArgumentType.greedyString()).executes { c ->
            val word = StringArgumentType.getString(c, "word").trim()
            val how = StringArgumentType.getString(c, "soundsLike").trim()
            set(c, "\"$word\" now sounds like \"$how\"") { it.pronunciations[word] = how }
            Tts.beginReply(System.nanoTime(), null)
            Tts.say("Let me try that. $word! Did I say $word right?") { dev.dexbuddy.client.ui.Subtitles.push("$word!") }
            1
        })))
        .then(literal("volume").then(argument("value", DoubleArgumentType.doubleArg(0.0, 1.0)).executes { c ->
            val v = DoubleArgumentType.getDouble(c, "value"); set(c, "volume $v") { it.volume = v }; Tts.sample(); 1 }))
        .then(literal("speaker").then(argument("id", IntegerArgumentType.integer(0, 903)).executes { c ->
            val id = IntegerArgumentType.getInteger(c, "id")
            val libri = VoiceCatalog.presets.getValue("libritts")
            set(c, "LibriTTS speaker $id") {
                if (VoiceCatalog.speakerCount(it.voice) <= 1) { it.voice = libri.voice; it.pitch = libri.pitch; it.buzz = libri.buzz }
                it.speaker = id
            }
            Tts.sample(); 1
        }))
        .then(argument("preset", StringArgumentType.word())
            .suggests { _, b -> VoiceCatalog.presets.keys.forEach { b.suggest(it) }; b.buildFuture() }
            .executes { c ->
                val name = StringArgumentType.getString(c, "preset").lowercase()
                val p = VoiceCatalog.presets[name]
                if (p == null) {
                    c.source.sendFeedback(Component.literal("[Dex Buddy] unknown voice \"$name\". Try: ${VoiceCatalog.presets.keys.joinToString()}").withStyle(ChatFormatting.RED))
                    return@executes 0
                }
                set(c, "voice $name (${p.about}; ${p.license})") {
                    it.voice = p.voice; it.speaker = p.speaker; it.pitch = p.pitch; it.buzz = p.buzz; it.enabled = true
                }
                Tts.sample(); 1
            })

    private fun show(c: CommandContext<FabricClientCommandSource>): Int {
        val t = BuddyConfig.settings.tts
        val current = VoiceCatalog.presets.entries.firstOrNull { (_, p) -> p.voice == t.voice && (p.speaker == t.speaker || VoiceCatalog.speakerCount(t.voice) > 1) }?.key ?: t.voice
        msg(c, "voice: $current${if (t.speaker >= 0) " (speaker ${t.speaker})" else ""}, pitch ${t.pitch}, buzz ${t.buzz}, speed ${t.speed}, volume ${t.volume}${if (!t.enabled) ", OFF" else ""}", ChatFormatting.AQUA)
        VoiceCatalog.presets.forEach { (k, p) -> msg(c, "  /dexbuddy voice $k  - ${p.about}", ChatFormatting.WHITE) }
        msg(c, "  expressiveness ${t.expressiveness}, pause ${t.sentencePauseMs}ms", ChatFormatting.AQUA)
        msg(c, "  /dexbuddy voice pitch|buzz|speed|volume|expressiveness <n>, pause <ms>, speaker <0-903>, test, on, off", ChatFormatting.GRAY)
        return 1
    }

    private fun set(c: CommandContext<FabricClientCommandSource>, what: String, change: (dev.dexbuddy.client.TtsSettings) -> Unit) {
        change(BuddyConfig.settings.tts)
        BuddyConfig.save()
        Tts.reset()
        msg(c, "[Dex Buddy] $what", ChatFormatting.GREEN)
    }

    private fun msg(c: CommandContext<FabricClientCommandSource>, s: String, color: ChatFormatting) =
        c.source.sendFeedback(Component.literal(s).withStyle(color))
}
