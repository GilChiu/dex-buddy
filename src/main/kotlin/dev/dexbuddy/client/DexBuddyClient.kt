package dev.dexbuddy.client

import com.mojang.brigadier.arguments.StringArgumentType
import dev.dexbuddy.client.brain.BuddyBrain
import dev.dexbuddy.client.context.GameContext
import dev.dexbuddy.client.knowledge.DataCards
import dev.dexbuddy.client.knowledge.KnowledgeIndex
import dev.dexbuddy.client.knowledge.NameMatcher
import dev.dexbuddy.client.ui.BuddyHud
import dev.dexbuddy.client.tts.Tts
import dev.dexbuddy.client.tts.VoicePlayer
import net.fabricmc.fabric.api.`object`.builder.v1.client.model.FabricModelPredicateProviderRegistry
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientLifecycleEvents
import dev.dexbuddy.client.voice.MicRecorder
import dev.dexbuddy.client.voice.PushToTalk
import dev.dexbuddy.client.voice.SpeechToText
import dev.dexbuddy.client.llm.LlmClient
import dev.dexbuddy.client.ui.Subtitles
import net.fabricmc.api.ClientModInitializer
import net.fabricmc.fabric.api.client.command.v2.ClientCommandManager.argument
import net.fabricmc.fabric.api.client.command.v2.ClientCommandManager.literal
import net.fabricmc.fabric.api.client.command.v2.ClientCommandRegistrationCallback
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayConnectionEvents
import net.fabricmc.fabric.api.client.rendering.v1.HudRenderCallback
import net.minecraft.ChatFormatting
import net.minecraft.network.chat.Component
import org.slf4j.Logger
import org.slf4j.LoggerFactory

object DexBuddyClient : ClientModInitializer {
    val LOG: Logger = LoggerFactory.getLogger("dexbuddy")

    /**
     * Whether what you say and what the companion says goes in the game log. Off for players (people post
     * their logs when asking for help); on in development and with "debugTimings" in the config.
     */
    val logTalk: Boolean get() = net.fabricmc.loader.api.FabricLoader.getInstance().isDevelopmentEnvironment || BuddyConfig.settings.debugTimings

    private fun setRequireItem(source: net.fabricmc.fabric.api.client.command.v2.FabricClientCommandSource, on: Boolean): Int {
        BuddyConfig.settings.voice.requireItem = on
        BuddyConfig.save()
        val name = dev.dexbuddy.client.entity.CompanionClient.companionName
        source.sendFeedback(Component.literal(
            if (on) "[Dex Buddy] You now need a Dex Buddy in your hotbar or offhand to talk to $name."
            else "[Dex Buddy] You can talk to $name without holding a Dex Buddy.",
        ).withStyle(ChatFormatting.GREEN))
        return 1
    }

    override fun onInitializeClient() {
        BuddyConfig.load()

        ClientCommandRegistrationCallback.EVENT.register { dispatcher, _ ->
            // /buddy <anything you'd say out loud>
            dispatcher.register(
                literal("buddy").then(
                    argument("message", StringArgumentType.greedyString()).executes { ctx ->
                        BuddyBrain.ask(StringArgumentType.getString(ctx, "message"))
                        1
                    },
                ),
            )
            dispatcher.register(
                literal("dexbuddy")
                    .then(literal("reload").executes { ctx ->
                        BuddyConfig.load()
                        LlmClient.forgetResolvedModel()
                        SpeechToText.forgetResolvedModel()
                        Tts.reset()
                        ctx.source.sendFeedback(Component.literal("[Dex Buddy] config reloaded").withStyle(ChatFormatting.GREEN))
                        1
                    })
                    // Hear the voice without asking anything: /dexbuddy say Hello there!
                    .then(literal("say").then(argument("text", StringArgumentType.greedyString()).executes { ctx ->
                        val text = StringArgumentType.getString(ctx, "text")
                        Tts.beginReply(System.nanoTime(), null)
                        if (!Tts.say(text) { Subtitles.push(text) }) {
                            Subtitles.push(text)
                            ctx.source.sendFeedback(Component.literal("[Dex Buddy] voice not ready yet (off, or still downloading)").withStyle(ChatFormatting.GRAY))
                        }
                        1
                    }))
                    .then(literal("hush").executes { Tts.stop(); 1 })
                    // Whether the talk key needs a Dex Buddy in your hotbar or offhand (default: on).
                    .then(literal("requireitem")
                        .then(literal("on").executes { ctx -> setRequireItem(ctx.source, true) })
                        .then(literal("off").executes { ctx -> setRequireItem(ctx.source, false) }))
                    .then(dev.dexbuddy.client.tts.VoiceCommands.build())
                    // Your companion's body style: /dexbuddy look phone|classic|flip|ball|plush
                    .then(literal("look").apply {
                        executes { ctx ->
                            ctx.source.sendFeedback(Component.literal("[Dex Buddy] " + dev.dexbuddy.client.antics.Commands.wardrobeLine()).withStyle(ChatFormatting.AQUA)); 1
                        }
                        dev.dexbuddy.entity.Look.entries.forEach { l ->
                            then(literal(l.id).executes { ctx ->
                                if (dev.dexbuddy.client.antics.Commands.changeLook(l))
                                    ctx.source.sendFeedback(Component.literal("[Dex Buddy] look: ${l.displayName}").withStyle(ChatFormatting.GREEN))
                                1
                            })
                        }
                    })
                    // The Dex phone (gallery, camera, wardrobe): /dexbuddy phone
                    .then(literal("phone").executes { dev.dexbuddy.client.phone.PhoneScreen.open(); 1 })
                    // Name your Dex: /dexbuddy name Sparky
                    .then(literal("name").then(argument("name", com.mojang.brigadier.arguments.StringArgumentType.greedyString()).executes { ctx ->
                        val n = com.mojang.brigadier.arguments.StringArgumentType.getString(ctx, "name")
                        if (dev.dexbuddy.client.antics.Commands.rename(n))
                            ctx.source.sendFeedback(Component.literal("[Dex Buddy] named: ${n.trim()}").withStyle(ChatFormatting.GREEN))
                        1
                    }))
                    // How often it speaks up on its own: 0 = never, 1 = normal, 2 = very chatty.
                    .then(literal("chatty").then(argument("level", com.mojang.brigadier.arguments.DoubleArgumentType.doubleArg(0.0, 2.0)).executes { ctx ->
                        BuddyConfig.settings.personality.chattiness = com.mojang.brigadier.arguments.DoubleArgumentType.getDouble(ctx, "level")
                        BuddyConfig.save()
                        ctx.source.sendFeedback(Component.literal("[Dex Buddy] chattiness ${BuddyConfig.settings.personality.chattiness}").withStyle(ChatFormatting.GREEN))
                        1
                    }))
                    .then(literal("memory").executes { ctx ->
                        val text = dev.dexbuddy.client.antics.Memory.forPrompt().trim().ifEmpty { "(nothing yet)" }
                        text.lines().forEach { ctx.source.sendFeedback(Component.literal(it).withStyle(ChatFormatting.AQUA)) }
                        1
                    })
                    .then(literal("forget").executes { ctx ->
                        dev.dexbuddy.client.antics.Memory.forgetAll()
                        ctx.source.sendFeedback(Component.literal("[Dex Buddy] memories for this world cleared").withStyle(ChatFormatting.GREEN))
                        1
                    })
                    // Test aid: feed a WAV file through speech-to-text as if you'd said it.
                    .then(literal("hear").then(argument("file", StringArgumentType.greedyString()).executes { ctx ->
                        val err = PushToTalk.hearFile(java.nio.file.Path.of(StringArgumentType.getString(ctx, "file").trim('"')))
                        if (err != null) ctx.source.sendFeedback(Component.literal("[Dex Buddy] $err").withStyle(ChatFormatting.RED))
                        1
                    }))
                    .then(literal("mics").executes { ctx ->
                        val names = MicRecorder.inputNames()
                        ctx.source.sendFeedback(Component.literal("[Dex Buddy] microphones (put part of a name in voice.micDevice):").withStyle(ChatFormatting.AQUA))
                        if (names.isEmpty()) ctx.source.sendFeedback(Component.literal("  none found").withStyle(ChatFormatting.RED))
                        names.forEach { ctx.source.sendFeedback(Component.literal("  $it").withStyle(ChatFormatting.WHITE)) }
                        1
                    })
                    .then(literal("reset").executes { ctx ->
                        BuddyBrain.resetSession()
                        Subtitles.clear()
                        ctx.source.sendFeedback(Component.literal("[Dex Buddy] conversation forgotten").withStyle(ChatFormatting.GREEN))
                        1
                    })
                    .then(literal("status").executes { ctx ->
                        val s = BuddyConfig.settings
                        val idx = KnowledgeIndex.get()
                        val lines = listOf(
                            "provider ${s.llm.baseUrl}",
                            "model ${s.llm.model.ifBlank { "(auto)" }}",
                            "API key ${if (s.llm.apiKey.isBlank()) "NOT SET" else "set"}",
                            "species ${idx.speciesTotal}, moves ${idx.moveTotal}, abilities ${idx.abilities.values.toSet().size}, items ${idx.items.values.toSet().size}",
                            "item sources ${idx.itemNamespaces.joinToString(", ")}",
                            "talk key ${PushToTalk.key.translatedKeyMessage.string}, Dex Buddy required ${if (s.voice.requireItem) "yes" else "no"}, server has Dex Buddy ${if (PushToTalk.serverHasMod()) "yes" else "no"}",
                            "speech-to-text ${s.stt.baseUrl.ifBlank { s.llm.baseUrl }} (${s.stt.model.ifBlank { "auto" }}, language ${s.stt.language.ifBlank { "auto" }})",
                            "debug timings ${if (s.debugTimings) "on" else "off"}",
                        )
                        lines.forEach { ctx.source.sendFeedback(Component.literal("[Dex Buddy] $it").withStyle(ChatFormatting.AQUA)) }
                        1
                    })
                    // What the companion would be told about a name / the world. For checking data without an API key.
                    .then(literal("card").then(argument("name", StringArgumentType.greedyString()).executes { ctx ->
                        val q = StringArgumentType.getString(ctx, "name")
                        val card = KnowledgeIndex.findSpecies(q)?.let { DataCards.pokemon(it) }
                            ?: KnowledgeIndex.findMove(q)?.let { DataCards.move(it) }
                            ?: KnowledgeIndex.findAbility(q)?.let { DataCards.ability(it) }
                            ?: KnowledgeIndex.findItem(q)?.let { DataCards.item(it) }
                            ?: "NO DATA for \"$q\""
                        LOG.info("[dexbuddy] card for '{}':\n{}", q, card)
                        card.lines().forEach { ctx.source.sendFeedback(Component.literal(it).withStyle(ChatFormatting.AQUA)) }
                        1
                    }))
                    .then(literal("match").then(argument("text", StringArgumentType.greedyString()).executes { ctx ->
                        val hits = NameMatcher.find(StringArgumentType.getString(ctx, "text"))
                        val names = hits.joinToString(", ") {
                            when (it) {
                                is NameMatcher.Hit.Pokemon -> "Pokémon " + DataCards.speciesName(it.species)
                                is NameMatcher.Hit.Move -> "move " + it.move.displayName.string
                                is NameMatcher.Hit.Ability -> "ability " + it.ability.name
                                is NameMatcher.Hit.Item -> "item " + it.item.name
                            }
                        }.ifEmpty { "nothing" }
                        LOG.info("[dexbuddy] match: {}", names)
                        ctx.source.sendFeedback(Component.literal("[Dex Buddy] cards: $names").withStyle(ChatFormatting.AQUA))
                        1
                    }))
                    .then(literal("context").executes { ctx ->
                        val snap = GameContext.snapshot()
                        val text = snap.autoContext() + "\n" + snap.pokedex
                        LOG.info("[dexbuddy] context:\n{}", text)
                        text.lines().forEach { ctx.source.sendFeedback(Component.literal(it).withStyle(ChatFormatting.AQUA)) }
                        1
                    })
                    .then(literal("debug").executes { ctx ->
                        BuddyConfig.settings.debugTimings = !BuddyConfig.settings.debugTimings
                        BuddyConfig.save()
                        ctx.source.sendFeedback(Component.literal("[Dex Buddy] debug timings ${if (BuddyConfig.settings.debugTimings) "on" else "off"}").withStyle(ChatFormatting.GREEN))
                        1
                    }),
            )
        }

        PushToTalk.register()
        SpawnClient.register()
        dev.dexbuddy.client.entity.CompanionClient.register()
        dev.dexbuddy.client.voice.WakeWord.register()
        dev.dexbuddy.client.antics.Antics.register()
        // The item is drawn as the companion's 3D body (its look), and talks while the companion speaks.
        dev.dexbuddy.client.entity.LookItemRenderer.register()
        dev.dexbuddy.client.photo.PhotoClient.register()
        dev.dexbuddy.client.phone.Gallery.register()
        dev.dexbuddy.client.phone.SocialClient.register()
        dev.dexbuddy.client.llm.Provider.register()
        // "@dex ..." in chat goes to your companion instead of the server.
        net.fabricmc.fabric.api.client.message.v1.ClientSendMessageEvents.ALLOW_CHAT.register { message ->
            !dev.dexbuddy.client.phone.VoltyChat.fromChatLine(message)
        }
        // Right-click with your Dex Buddy (not sneaking): a tap opens its phone, holding it zooms in to scan.
        net.fabricmc.fabric.api.event.player.UseItemCallback.EVENT.register { player, level, hand ->
            val stack = player.getItemInHand(hand)
            if (!level.isClientSide || !stack.`is`(dev.dexbuddy.DexBuddyMod.DEX_BUDDY) || player.isShiftKeyDown)
                return@register net.minecraft.world.InteractionResultHolder.pass(stack)
            dev.dexbuddy.client.scan.ScanClient.pressed()
            // "consume", not "success": the item is in use, so no arm swing every few ticks while held.
            net.minecraft.world.InteractionResultHolder.consume(stack)
        }
        dev.dexbuddy.client.scan.ScanClient.register()
        dev.dexbuddy.client.music.MusicClient.register()
        net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents.END_CLIENT_TICK.register { dev.dexbuddy.client.antics.Commands.tickPendingPhoto() }
        // The Dex Buddy item's tooltip lists the costumes collected inside it.
        net.fabricmc.fabric.api.client.item.v1.ItemTooltipCallback.EVENT.register { stack, _, _, lines ->
            if (!stack.`is`(dev.dexbuddy.DexBuddyMod.DEX_BUDDY)) return@register
            val bits = dev.dexbuddy.entity.DexBuddyEntity.costumesOf(stack)
            val owned = dev.dexbuddy.entity.Look.entries.filter { dev.dexbuddy.entity.DexBuddyEntity.hasCostume(bits, it) }
            if (stack.has(net.minecraft.core.component.DataComponents.CUSTOM_NAME)) lines += Component.literal("Dex Buddy").withStyle(ChatFormatting.DARK_GRAY)
            lines += Component.literal("Wearing:${dev.dexbuddy.entity.DexBuddyEntity.lookOf(stack).displayName}").withStyle(ChatFormatting.GRAY)
            lines += Component.literal("Costumes: ${owned.size}/${dev.dexbuddy.entity.Look.entries.size}").withStyle(ChatFormatting.AQUA)
            dev.dexbuddy.entity.DexBuddyEntity.friendshipOf(stack).takeIf { it > 0 }?.let {
                lines += Component.literal("Friendship: $it").withStyle(ChatFormatting.LIGHT_PURPLE)
            }
        }
        HudRenderCallback.EVENT.register { g, delta ->
            Subtitles.render(g, delta); BuddyHud.render(g, delta); dev.dexbuddy.client.ui.QuizHud.render(g, delta)
            dev.dexbuddy.client.photo.PhotoClient.render(g, delta)
        }

        ClientPlayConnectionEvents.JOIN.register { _, _, client ->
            KnowledgeIndex.invalidate(); BuddyBrain.resetSession()
            Tts.warmUp()
            // Dev aid: -Ddexbuddy.selftest=<file> logs which cards each line of the file would pull in.
            System.getProperty("dexbuddy.selftest")?.let { file ->
                client.execute {
                    try {
                        val all = java.nio.file.Files.readAllLines(java.nio.file.Path.of(file)).filter { it.isNotBlank() }
                        // "spawn:<name>" lines (blank name = here) ask the server, off the client thread.
                        all.filter { it.startsWith("spawn:") }.takeIf { it.isNotEmpty() }?.let { qs ->
                            Thread({
                                Thread.sleep(4000)
                                qs.forEach { q -> LOG.info("[dexbuddy] selftest {} ->\n{}", q, SpawnClient.request(q.removePrefix("spawn:").trim())) }
                            }, "dexbuddy-selftest").apply { isDaemon = true }.start()
                        }
                        // "say:<text>" lines speak through the real voice pipeline.
                        all.filter { it.startsWith("say:") }.takeIf { it.isNotEmpty() }?.let { says ->
                            Thread({
                                Thread.sleep(6000)
                                says.forEach { s ->
                                    val text = s.removePrefix("say:").trim()
                                    val t = System.nanoTime()
                                    Tts.beginReply(t) { ms -> LOG.info("[dexbuddy] selftest say first audio {}ms: {}", ms, text) }
                                    client.execute { if (!Tts.say(text) {}) LOG.info("[dexbuddy] selftest say: voice not ready") }
                                    Thread.sleep(6000)
                                }
                            }, "dexbuddy-selftest-say").apply { isDaemon = true }.start()
                        }
                        // "ask:<text>" lines are said to the companion one by one (8 s apart), as if spoken.
                        all.filter { it.startsWith("ask:") }.takeIf { it.isNotEmpty() }?.let { asks ->
                            Thread({
                                Thread.sleep(8000)
                                asks.forEach { a ->
                                    val text = a.removePrefix("ask:").trim()
                                    client.execute { LOG.info("[dexbuddy] selftest ask: {}", text); BuddyBrain.ask(text, spoken = true) }
                                    Thread.sleep(8000)
                                }
                            }, "dexbuddy-selftest-ask").apply { isDaemon = true }.start()
                        }
                        // "throw:" makes the test player throw a Dex Buddy (no cheats needed in the test world).
                        if (all.any { it.startsWith("throw:") }) {
                            client.singleplayerServer?.let { server ->
                                server.execute {
                                    server.playerList.players.firstOrNull()?.let { p ->
                                        p.drop(net.minecraft.world.item.ItemStack(dev.dexbuddy.DexBuddyMod.DEX_BUDDY), false, true)
                                        LOG.info("[dexbuddy] selftest threw a Dex Buddy")
                                        Thread({
                                            repeat(4) {
                                                Thread.sleep(1500)
                                                server.execute {
                                                    val b = dev.dexbuddy.entity.DexBuddyEntity.active[p.uuid]
                                                    LOG.info("[dexbuddy] selftest buddy {} player ({}, {}, {}) yaw {} dist {} moving {}",
                                                        b?.position(), "%.1f".format(p.x), "%.1f".format(p.y), "%.1f".format(p.z), p.yRot.toInt(),
                                                        b?.distanceTo(p)?.let { "%.2f".format(it) }, b?.deltaMovement)
                                                }
                                            }
                                        }, "dexbuddy-selftest-pos").apply { isDaemon = true }.start()
                                    }
                                }
                            }
                        }
                        // "dropdex:<seconds>" selects the Dex in your hotbar and drops it (lets the companion out on any server).
                        all.filter { it.startsWith("dropdex:") }.forEach { c ->
                            val sec = c.removePrefix("dropdex:").toDoubleOrNull() ?: return@forEach
                            Thread({
                                Thread.sleep((sec * 1000).toLong())
                                client.execute {
                                    val p = client.player ?: return@execute
                                    val slot = (0 until 9).firstOrNull { p.inventory.getItem(it).`is`(dev.dexbuddy.DexBuddyMod.DEX_BUDDY) } ?: return@execute
                                    p.inventory.selected = slot
                                    p.connection.send(net.minecraft.network.protocol.game.ServerboundSetCarriedItemPacket(slot))
                                    p.drop(true)
                                    LOG.info("[dexbuddy] selftest dropped the Dex from slot {}", slot)
                                }
                            }, "dexbuddy-selftest-drop").apply { isDaemon = true }.start()
                        }
                        // "buddies:<seconds>" logs every companion in the world (owner, linked state).
                        all.filter { it.startsWith("buddies:") }.forEach { c ->
                            val sec = c.removePrefix("buddies:").toDoubleOrNull() ?: return@forEach
                            Thread({
                                Thread.sleep((sec * 1000).toLong())
                                client.singleplayerServer?.let { s -> s.execute {
                                    val p = s.playerList.players.firstOrNull()
                                    LOG.info("[dexbuddy] selftest player {} owns={} out={}", p?.uuid, p?.let { dev.dexbuddy.entity.BuddyLink.owns(it) }, p?.let { dev.dexbuddy.entity.BuddyLink.isOut(it) })
                                    s.allLevels.forEach { l -> l.allEntities.filterIsInstance<dev.dexbuddy.entity.DexBuddyEntity>().forEach { b ->
                                        LOG.info("[dexbuddy] selftest buddy owner={} look={} active={} at {}", b.ownerId, b.look.id, dev.dexbuddy.entity.DexBuddyEntity.active[b.ownerId] === b, b.blockPosition())
                                    } }
                                } }
                            }, "dexbuddy-selftest-buddies").apply { isDaemon = true }.start()
                        }
                        // "social:<seconds>:<action>" sends a social action ({latest} = your newest photo);
                        // "phone:<seconds>:<page>" opens that phone page.
                        all.filter { it.startsWith("social:") || it.startsWith("phone:") }.forEach { c ->
                            val body = c.substringAfter(':')
                            val sec = body.substringBefore(':').toDoubleOrNull() ?: return@forEach
                            val arg = body.substringAfter(':')
                            Thread({
                                Thread.sleep((sec * 1000).toLong())
                                client.execute {
                                    if (c.startsWith("phone:")) dev.dexbuddy.client.phone.PhoneScreen.open(dev.dexbuddy.client.phone.PhoneScreen.Tab.valueOf(arg.uppercase()))
                                    else {
                                        val sc = dev.dexbuddy.client.phone.SocialClient
                                        val action = arg.replace("{latest}", dev.dexbuddy.client.phone.Gallery.entries.firstOrNull()?.id ?: "")
                                            .replace("{nearby}", sc.nearby.firstOrNull()?.uuid ?: "")
                                            .replace("{incoming}", sc.incoming.firstOrNull()?.uuid ?: "")
                                            .replace("{feed}", sc.feed.firstOrNull()?.id ?: "")
                                            .replace("{friend}", sc.friends.firstOrNull()?.uuid ?: "")
                                            .replace("{photo}", dev.dexbuddy.client.phone.Gallery.entries.firstOrNull { it.mode != "clip" }?.id ?: "")
                                        LOG.info("[dexbuddy] selftest social {}", action)
                                        dev.dexbuddy.client.phone.SocialClient.send(action)
                                    }
                                }
                            }, "dexbuddy-selftest-social").apply { isDaemon = true }.start()
                        }
                        // "cliprec:<start s>:<seconds>" records a video clip of what you see, then refreshes the gallery.
                        all.filter { it.startsWith("cliprec:") }.forEach { c ->
                            val parts = c.removePrefix("cliprec:").split(':')
                            val start = parts.getOrNull(0)?.toDoubleOrNull() ?: 30.0
                            val dur = parts.getOrNull(1)?.toDoubleOrNull() ?: 4.0
                            Thread({
                                Thread.sleep((start * 1000).toLong())
                                client.execute { if (client.screen is net.minecraft.client.gui.screens.PauseScreen) client.setScreen(null); dev.dexbuddy.client.photo.ClipRecorder.start(); LOG.info("[dexbuddy] selftest clip recording") }
                                Thread.sleep((dur * 1000).toLong())
                                client.execute { dev.dexbuddy.client.photo.ClipRecorder.stop() }
                                Thread.sleep(5_000)
                                client.execute { dev.dexbuddy.client.phone.Gallery.refresh() }
                            }, "dexbuddy-selftest-cliprec").apply { isDaemon = true }.start()
                        }
                        // "music:<s>:<query>" plays a song on your Dex, then logs what's playing a few times.
                        all.filter { it.startsWith("music:") }.forEach { c ->
                            val sec = c.removePrefix("music:").substringBefore(':').toDoubleOrNull() ?: return@forEach
                            val q = c.removePrefix("music:").substringAfter(':')
                            Thread({
                                Thread.sleep((sec * 1000).toLong())
                                client.execute { if (client.screen is net.minecraft.client.gui.screens.PauseScreen) client.setScreen(null); LOG.info("[dexbuddy] selftest music play {}", q); dev.dexbuddy.client.music.MusicClient.testMute = true; dev.dexbuddy.client.music.MusicClient.play(q) }
                                repeat(4) {
                                    Thread.sleep(6_000)
                                    client.execute { LOG.info("[dexbuddy] selftest music {}", dev.dexbuddy.client.music.MusicClient.debug()) }
                                }
                            }, "dexbuddy-selftest-music").apply { isDaemon = true }.start()
                        }
                        // "chatphoto:<s>" opens the chat with your first friend, showing the last photo/clip they sent big;
                        // "savelast:<s>" saves that photo/clip to your gallery.
                        all.filter { it.startsWith("chatphoto:") || it.startsWith("savelast:") }.forEach { c ->
                            val sec = c.substringAfter(':').toDoubleOrNull() ?: return@forEach
                            Thread({
                                Thread.sleep((sec * 1000).toLong())
                                client.execute {
                                    val sc = dev.dexbuddy.client.phone.SocialClient
                                    val friend = sc.friends.firstOrNull() ?: return@execute
                                    if (c.startsWith("chatphoto:")) {
                                        if (client.screen is net.minecraft.client.gui.screens.PauseScreen) client.setScreen(null)
                                        sc.openChat(friend)
                                        Thread({
                                            Thread.sleep(1500)
                                            client.execute {
                                                val last = sc.messages.lastOrNull { !it.mine && it.photo != null }?.photo
                                                LOG.info("[dexbuddy] selftest chat with {}: {} messages, last photo {}", friend.name, sc.messages.size, last)
                                                dev.dexbuddy.client.phone.PhoneScreen.testPhoto = last
                                                dev.dexbuddy.client.phone.PhoneScreen.open(dev.dexbuddy.client.phone.PhoneScreen.Tab.CHAT)
                                            }
                                        }, "dexbuddy-selftest-chat").apply { isDaemon = true }.start()
                                    } else {
                                        val last = sc.messages.lastOrNull { !it.mine && it.photo != null }?.photo ?: return@execute
                                        LOG.info("[dexbuddy] selftest save {}", last)
                                        dev.dexbuddy.client.phone.Gallery.save(last)
                                    }
                                }
                            }, "dexbuddy-selftest-chatphoto").apply { isDaemon = true }.start()
                        }
                        // "time:<seconds>:<ticks>" sets the world's time that many seconds in (no cheats needed).
                        all.filter { it.startsWith("time:") }.forEach { c ->
                            val parts = c.removePrefix("time:").split(':')
                            val sec = parts.getOrNull(0)?.toDoubleOrNull() ?: return@forEach
                            val ticks = parts.getOrNull(1)?.toLongOrNull() ?: return@forEach
                            Thread({
                                Thread.sleep((sec * 1000).toLong())
                                client.singleplayerServer?.let { s -> s.execute { s.overworld().dayTime = ticks; LOG.info("[dexbuddy] selftest time {}", ticks) } }
                            }, "dexbuddy-selftest-time").apply { isDaemon = true }.start()
                        }
                        // "cmd:<seconds>:<command>" runs a chat command that many seconds in (e.g. to play an emote).
                        all.filter { it.startsWith("cmd:") }.forEach { c ->
                            val sec = c.removePrefix("cmd:").substringBefore(':').toDoubleOrNull() ?: return@forEach
                            val command = c.removePrefix("cmd:").substringAfter(':')
                            Thread({
                                Thread.sleep((sec * 1000).toLong())
                                client.execute { LOG.info("[dexbuddy] selftest cmd: /{}", command); client.player?.connection?.sendCommand(command) }
                            }, "dexbuddy-selftest-cmd").apply { isDaemon = true }.start()
                        }
                        // "costume:<look>" hands that costume to your companion (as if right-clicking it), 12 s in.
                        all.filter { it.startsWith("costume:") }.forEach { c ->
                            val look = dev.dexbuddy.entity.Look.byId(c.removePrefix("costume:")) ?: return@forEach
                            Thread({
                                Thread.sleep(12_000)
                                client.singleplayerServer?.let { server -> server.execute {
                                    val p = server.playerList.players.firstOrNull() ?: return@execute
                                    val b = dev.dexbuddy.entity.DexBuddyEntity.active[p.uuid] ?: return@execute
                                    p.setItemInHand(net.minecraft.world.InteractionHand.MAIN_HAND, net.minecraft.world.item.ItemStack(dev.dexbuddy.DexBuddyMod.COSTUMES[look]!!))
                                    LOG.info("[dexbuddy] selftest costume {} -> {}", look.id, b.interact(p, net.minecraft.world.InteractionHand.MAIN_HAND))
                                } }
                            }, "dexbuddy-selftest-costume").apply { isDaemon = true }.start()
                        }
                        // "scan:<start s>:<hold s>" aims at the nearest Cobblemon and holds right-click with the Dex.
                        all.filter { it.startsWith("scan:") }.forEach { c ->
                            val parts = c.removePrefix("scan:").split(':')
                            val start = parts.getOrNull(0)?.toDoubleOrNull() ?: 30.0
                            val hold = parts.getOrNull(1)?.toDoubleOrNull() ?: 4.0
                            Thread({
                                Thread.sleep((start * 1000).toLong())
                                val until = System.currentTimeMillis() + (hold * 1000).toLong()
                                client.execute {
                                    if (client.screen is net.minecraft.client.gui.screens.PauseScreen) client.setScreen(null)
                                    client.player?.inventory?.selected = (0 until 9).firstOrNull { client.player!!.inventory.getItem(it).`is`(dev.dexbuddy.DexBuddyMod.DEX_BUDDY) } ?: 0
                                }
                                while (System.currentTimeMillis() < until) {
                                    client.execute {
                                        val p = client.player ?: return@execute
                                        p.level().getEntitiesOfClass(com.cobblemon.mod.common.entity.pokemon.PokemonEntity::class.java, p.boundingBox.inflate(24.0))
                                            .minByOrNull { it.distanceToSqr(p) }
                                            ?.let { p.lookAt(net.minecraft.commands.arguments.EntityAnchorArgument.Anchor.EYES, it.position().add(0.0, it.bbHeight / 2.0, 0.0)) }
                                        net.minecraft.client.KeyMapping.set(client.options.keyUse.getDefaultKey(), true)
                                    }
                                    Thread.sleep(50)
                                }
                                client.execute { net.minecraft.client.KeyMapping.set(client.options.keyUse.getDefaultKey(), false); LOG.info("[dexbuddy] selftest scan released") }
                            }, "dexbuddy-selftest-scan").apply { isDaemon = true }.start()
                        }
                        // "showcase:<start s>:<seconds each>" unlocks every costume and wears them one after another.
                        all.filter { it.startsWith("showcase:") }.forEach { c ->
                            val parts = c.removePrefix("showcase:").split(':')
                            val start = parts.getOrNull(0)?.toDoubleOrNull() ?: 20.0
                            val each = parts.getOrNull(1)?.toDoubleOrNull() ?: 4.0
                            Thread({
                                Thread.sleep((start * 1000).toLong())
                                for ((i, look) in dev.dexbuddy.entity.Look.entries.drop(5).withIndex()) {
                                    client.execute { if (client.screen is net.minecraft.client.gui.screens.PauseScreen) client.setScreen(null) }
                                    client.singleplayerServer?.let { server -> server.execute {
                                        val p = server.playerList.players.firstOrNull() ?: return@execute
                                        val b = dev.dexbuddy.entity.DexBuddyEntity.active[p.uuid]?.takeIf { it.isAlive }
                                            ?: dev.dexbuddy.entity.BuddyLink.spawn(p) ?: return@execute
                                        b.unlockCostume(look)
                                        b.command("look:${look.id}", p)
                                        LOG.info("[dexbuddy] selftest showcase {} {}", i, look.id)
                                    } }
                                    Thread.sleep((each * 1000).toLong())
                                }
                            }, "dexbuddy-selftest-showcase").apply { isDaemon = true }.start()
                        }
                        // "look:" keeps the camera on your companion for a minute (to screenshot it).
                        if (all.any { it.startsWith("look:") }) {
                            Thread({
                                repeat(2400) {
                                    Thread.sleep(250)
                                    client.execute {
                                        val p = client.player ?: return@execute
                                        dev.dexbuddy.client.entity.CompanionClient.mine(p, 32.0)?.let { b ->
                                            p.lookAt(net.minecraft.commands.arguments.EntityAnchorArgument.Anchor.EYES, b.position().add(0.0, 0.4, 0.0))
                                        }
                                    }
                                }
                            }, "dexbuddy-selftest-look").apply { isDaemon = true }.start()
                        }
                        // "wake:<wav>" lines run a recording through the "Hey Dex" recognizer (once its model is in).
                        all.filter { it.startsWith("wake:") }.takeIf { it.isNotEmpty() }?.let { ws ->
                            Thread({
                                var tries = 0
                                while (tries++ < 90 && dev.dexbuddy.client.voice.WakeWord.testFile(java.nio.file.Path.of(ws[0].removePrefix("wake:").trim())) == "model not installed yet") Thread.sleep(1000)
                                ws.forEach { w ->
                                    val p = w.removePrefix("wake:").trim()
                                    LOG.info("[dexbuddy] selftest wake {} -> {}", p.substringAfterLast('\\'), dev.dexbuddy.client.voice.WakeWord.testFile(java.nio.file.Path.of(p)))
                                }
                            }, "dexbuddy-selftest-wake").apply { isDaemon = true }.start()
                        }
                        all.filter { !it.startsWith("spawn:") && !it.startsWith("say:") && !it.startsWith("wake:") }.forEach { line ->
                            val hits = NameMatcher.find(line).joinToString(", ") {
                                when (it) {
                                    is NameMatcher.Hit.Pokemon -> DataCards.speciesName(it.species) + (it.heardAs?.let { h -> " (sounds like '$h')" } ?: "")
                                    is NameMatcher.Hit.Move -> "move " + it.move.displayName.string
                                    is NameMatcher.Hit.Ability -> "ability " + it.ability.name
                                    is NameMatcher.Hit.Item -> "item " + it.item.name
                                }
                            }.ifEmpty { "nothing" }
                            LOG.info("[dexbuddy] selftest: \"{}\" -> {}", line, hits)
                        }
                    } catch (e: Exception) {
                        LOG.warn("[dexbuddy] selftest failed: {}", e.toString())
                    }
                }
            }
        }
        ClientPlayConnectionEvents.DISCONNECT.register { _, _ ->
            MicRecorder.stop(); dev.dexbuddy.VoiceState.recording = false
            Tts.stop()
            KnowledgeIndex.invalidate(); BuddyBrain.resetSession(); Subtitles.clear()
        }
        ClientLifecycleEvents.CLIENT_STOPPING.register { Tts.shutdown() }
    }
}
