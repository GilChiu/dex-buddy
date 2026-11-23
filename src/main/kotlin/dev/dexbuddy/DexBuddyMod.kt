package dev.dexbuddy

import dev.dexbuddy.net.CompanionCommandPayload
import dev.dexbuddy.net.HelloPayload
import dev.dexbuddy.net.QuizActionPayload
import dev.dexbuddy.net.QuizLinePayload
import dev.dexbuddy.net.SpawnAnswerPayload
import dev.dexbuddy.net.SpawnQueryPayload
import dev.dexbuddy.spawn.SpawnInfo
import dev.dexbuddy.entity.DexBuddyEntity
import dev.dexbuddy.entity.BuddyLink
import net.fabricmc.api.ModInitializer
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerEntityEvents
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerLifecycleEvents
import net.fabricmc.fabric.api.networking.v1.ServerPlayConnectionEvents
import net.fabricmc.fabric.api.`object`.builder.v1.entity.FabricDefaultAttributeRegistry
import net.minecraft.core.particles.ParticleTypes
import net.minecraft.server.level.ServerPlayer
import net.minecraft.sounds.SoundEvents
import net.minecraft.sounds.SoundSource
import net.minecraft.world.entity.EntityType
import net.minecraft.world.entity.MobCategory
import net.minecraft.world.entity.item.ItemEntity
import net.minecraft.world.item.ItemStack
import net.fabricmc.fabric.api.itemgroup.v1.ItemGroupEvents
import net.fabricmc.fabric.api.networking.v1.PayloadTypeRegistry
import net.fabricmc.fabric.api.networking.v1.ServerPlayNetworking
import net.minecraft.core.Registry
import net.minecraft.core.registries.BuiltInRegistries
import net.minecraft.resources.ResourceLocation
import net.minecraft.world.item.CreativeModeTabs
import net.minecraft.world.item.Item
import net.minecraft.world.item.Rarity

/** Runs on both sides: registers the Dex Buddy item and the "this server has Dex Buddy" channel. */
object DexBuddyMod : ModInitializer {
    const val MOD_ID = "dexbuddy"

    fun id(path: String): ResourceLocation = ResourceLocation.fromNamespaceAndPath(MOD_ID, path)

    val DEX_BUDDY: Item = Registry.register(
        BuiltInRegistries.ITEM, id("dex_buddy"),
        Item(Item.Properties().stacksTo(1).rarity(Rarity.UNCOMMON)),
    )

    /** One costume item per unlockable look (the Phone Dex is everyone's from the start). */
    val COSTUMES: Map<dev.dexbuddy.entity.Look, dev.dexbuddy.entity.CostumeItem> =
        dev.dexbuddy.entity.Look.entries.filter { it != dev.dexbuddy.entity.Look.DEFAULT }.associateWith { look ->
            Registry.register(BuiltInRegistries.ITEM, id("costume_${look.id}"),
                dev.dexbuddy.entity.CostumeItem(look, Item.Properties().stacksTo(1).rarity(Rarity.RARE)))
        }

    /** The companion out in the world (thrown Dex Buddy item). */
    val DEX_BUDDY_ENTITY: EntityType<DexBuddyEntity> = Registry.register(
        BuiltInRegistries.ENTITY_TYPE, id("dex_buddy"),
        EntityType.Builder.of({ type: EntityType<DexBuddyEntity>, level -> DexBuddyEntity(type, level) }, MobCategory.MISC)
            .sized(0.5f, 0.55f).clientTrackingRange(10).fireImmune().build("dex_buddy"),
    )

    override fun onInitialize() {
        ItemGroupEvents.modifyEntriesEvent(CreativeModeTabs.TOOLS_AND_UTILITIES).register { e ->
            e.accept(DEX_BUDDY)
            COSTUMES.values.forEach { e.accept(it) }
        }
        FabricDefaultAttributeRegistry.register(DEX_BUDDY_ENTITY, DexBuddyEntity.attributes())

        // Every Trainer's one companion, saved on the player (see BuddyLink).
        BuddyLink.DATA
        // Throwing your Dex (Q / drag out of the inventory) lets your companion out. A Dex on the ground
        // never stays an item: it always becomes its owner's companion (or goes back to them).
        ServerEntityEvents.ENTITY_LOAD.register { entity, level ->
            if (entity !is ItemEntity || !entity.item.`is`(DEX_BUDDY)) return@register
            level.server.execute {
                if (entity.isRemoved) return@execute
                val owner = BuddyLink.ownerOf(entity.item)?.let { level.server.playerList.getPlayer(it) }
                    ?: (entity.owner as? ServerPlayer) ?: return@execute
                BuddyLink.bind(owner, entity.item)
                entity.discard()
                if (owner.level() == level && owner.distanceTo(entity) < 32) BuddyLink.spawn(owner, entity.position().add(0.0, 0.6, 0.0))
                else BuddyLink.spawn(owner)
            }
        }
        // Logging out: the companion waits (saved on you) and comes back out when you log back in.
        ServerPlayConnectionEvents.DISCONNECT.register { handler, _ ->
            val player = handler.player
            DexBuddyEntity.active.remove(player.uuid)?.let { buddy ->
                if (!buddy.isRemoved) { BuddyLink.store(player, buddy); buddy.discard() }
            }
            dev.dexbuddy.games.QuizManager.forget(player.uuid)
            dev.dexbuddy.games.Flashlight.off(player)
            dev.dexbuddy.proxy.ProxyServer.forget(player)
            RateLimit.forget(player.uuid)
        }
        ServerLifecycleEvents.SERVER_STOPPING.register { dev.dexbuddy.games.Flashlight.clearAll() }
        ServerPlayConnectionEvents.JOIN.register { handler, _, server ->
            server.execute {
                if (BuddyLink.isOut(handler.player)) BuddyLink.spawn(handler.player)
                dev.dexbuddy.proxy.ProxyServer.announce(handler.player)
            }
        }
        // Server key mode: players without their own AI key talk through the server's (if the owner allows).
        PayloadTypeRegistry.playC2S().register(dev.dexbuddy.net.ProxyRequestPayload.TYPE, dev.dexbuddy.net.ProxyRequestPayload.CODEC)
        PayloadTypeRegistry.playS2C().register(dev.dexbuddy.net.ProxyResponsePayload.TYPE, dev.dexbuddy.net.ProxyResponsePayload.CODEC)
        PayloadTypeRegistry.playS2C().register(dev.dexbuddy.net.ServerFeaturesPayload.TYPE, dev.dexbuddy.net.ServerFeaturesPayload.CODEC)
        ServerPlayNetworking.registerGlobalReceiver(dev.dexbuddy.net.ProxyRequestPayload.TYPE) { payload, ctx ->
            val player = ctx.player()
            player.server.execute { dev.dexbuddy.proxy.ProxyServer.receive(player, payload) }
        }
        ServerLifecycleEvents.SERVER_STARTING.register { dev.dexbuddy.proxy.ProxyServer.load() }
        // Once a second: one Dex per Trainer, never someone else's, and an out companion is always near.
        var linkTick = 0
        net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents.END_SERVER_TICK.register { server ->
            if (++linkTick % 20 == 0) server.playerList.players.forEach { BuddyLink.check(it) }
            if (linkTick % 2 == 0) dev.dexbuddy.games.Flashlight.tick(server)
        }
        // Your Dex never drops when you die; it's back in your inventory when you respawn.
        net.fabricmc.fabric.api.entity.event.v1.ServerLivingEntityEvents.ALLOW_DEATH.register { entity, _, _ ->
            if (entity is ServerPlayer && !entity.level().gameRules.getBoolean(net.minecraft.world.level.GameRules.RULE_KEEPINVENTORY)) BuddyLink.onDeath(entity)
            true
        }
        net.fabricmc.fabric.api.entity.event.v1.ServerPlayerEvents.AFTER_RESPAWN.register { _, newPlayer, _ -> BuddyLink.onRespawn(newPlayer) }
        // /dexbuddyadmin reset <player>: a fresh start for that Trainer's companion (operators only).
        net.fabricmc.fabric.api.command.v2.CommandRegistrationCallback.EVENT.register { dispatcher, _, _ ->
            dispatcher.register(net.minecraft.commands.Commands.literal("dexbuddyadmin").requires { it.hasPermission(2) }
                .then(net.minecraft.commands.Commands.literal("reset").then(
                    net.minecraft.commands.Commands.argument("player", net.minecraft.commands.arguments.EntityArgument.player()).executes { ctx ->
                        val target = net.minecraft.commands.arguments.EntityArgument.getPlayer(ctx, "player")
                        BuddyLink.reset(target)
                        ctx.source.sendSuccess({ net.minecraft.network.chat.Component.literal("Reset ${target.gameProfile.name}'s Dex Buddy") }, true)
                        1
                    })))
        }

        // Voice commands for the companion ("stay", "light the way", "hide and seek"...).
        PayloadTypeRegistry.playC2S().register(CompanionCommandPayload.TYPE, CompanionCommandPayload.CODEC)
        ServerPlayNetworking.registerGlobalReceiver(CompanionCommandPayload.TYPE) { payload, ctx ->
            val player = ctx.player()
            player.server.execute {
                if (!RateLimit.allow(player.uuid, "cmd", 8, 1_000)) return@execute
                // The phone's flashlight works with or without the companion out.
                if (payload.command == "flashlight_on" || payload.command == "flashlight_off") {
                    dev.dexbuddy.games.Flashlight.set(player, payload.command == "flashlight_on"); return@execute
                }
                // A finished Dex Buddy scan: register it in the Cobblemon Pokédex too.
                if (payload.command.startsWith("dexscan:")) {
                    // A real scan takes over a second of holding: one per second at most.
                    if (!RateLimit.allow(player.uuid, "scan", 1, 1_000)) return@execute
                    payload.command.removePrefix("dexscan:").toIntOrNull()?.let { dev.dexbuddy.games.DexScan.scan(player, it) }
                    return@execute
                }
                val buddy = DexBuddyEntity.active[player.uuid]?.takeIf { it.isAlive }
                if (buddy != null) buddy.command(payload.command, player)
                // Naming works on the item too, while the companion is tucked away.
                else if (payload.command.startsWith("name:")) {
                    val name = DexBuddyEntity.cleanName(payload.command.removePrefix("name:")) ?: return@execute
                    if (!BuddyLink.owns(player)) return@execute
                    BuddyLink.rename(player, name) // the carried Dex shows it on the next check
                }
                // Costumes too: the carried Dex wears it now, and so will the companion when it comes out.
                // Asked for a photo "of us" while it's in your hand: it hops out of the Dex first.
                else if (payload.command == "out") {
                    if (!BuddyLink.owns(player) || BuddyLink.isOut(player)) return@execute
                    for (slot in 0 until player.inventory.containerSize) {
                        if (player.inventory.getItem(slot).`is`(DEX_BUDDY)) player.inventory.setItem(slot, net.minecraft.world.item.ItemStack.EMPTY)
                    }
                    BuddyLink.spawn(player)
                }
                else if (payload.command.startsWith("look:")) {
                    val look = dev.dexbuddy.entity.Look.byId(payload.command.removePrefix("look:")) ?: return@execute
                    if (BuddyLink.owns(player) && BuddyLink.setLook(player, look)) BuddyLink.check(player)
                }
            }
        }

        // Quizzes run on the server, so coin rewards can't be cheated.
        PayloadTypeRegistry.playC2S().register(QuizActionPayload.TYPE, QuizActionPayload.CODEC)
        PayloadTypeRegistry.playS2C().register(QuizLinePayload.TYPE, QuizLinePayload.CODEC)
        PayloadTypeRegistry.playS2C().register(dev.dexbuddy.net.GiftPayload.TYPE, dev.dexbuddy.net.GiftPayload.CODEC)
        dev.dexbuddy.games.Charge.register()
        dev.dexbuddy.games.CostumeUnlocks.register()
        dev.dexbuddy.games.MusicServer.register()
        ServerPlayNetworking.registerGlobalReceiver(QuizActionPayload.TYPE) { payload, ctx ->
            val player = ctx.player()
            player.server.execute { if (RateLimit.allow(player.uuid, "quiz", 4, 1_000)) dev.dexbuddy.games.QuizManager.handle(player, payload.action) }
        }
        ServerLifecycleEvents.SERVER_STOPPED.register { DexBuddyEntity.active.clear() }

        // Photos: uploaded in pieces, kept in the world folder (the gallery and feed read them later).
        PayloadTypeRegistry.playC2S().register(dev.dexbuddy.net.PhotoChunkPayload.TYPE, dev.dexbuddy.net.PhotoChunkPayload.CODEC)
        PayloadTypeRegistry.playS2C().register(dev.dexbuddy.net.PhotoSavedPayload.TYPE, dev.dexbuddy.net.PhotoSavedPayload.CODEC)
        ServerPlayNetworking.registerGlobalReceiver(dev.dexbuddy.net.PhotoChunkPayload.TYPE) { payload, ctx ->
            val player = ctx.player()
            player.server.execute { dev.dexbuddy.photo.PhotoStore.receive(player, payload) }
        }
        // The Dex social network: friends, posts, likes, profile pictures.
        PayloadTypeRegistry.playC2S().register(dev.dexbuddy.net.SocialActionPayload.TYPE, dev.dexbuddy.net.SocialActionPayload.CODEC)
        PayloadTypeRegistry.playS2C().register(dev.dexbuddy.net.SocialDataPayload.TYPE, dev.dexbuddy.net.SocialDataPayload.CODEC)
        PayloadTypeRegistry.playS2C().register(dev.dexbuddy.net.SocialNoticePayload.TYPE, dev.dexbuddy.net.SocialNoticePayload.CODEC)
        ServerPlayNetworking.registerGlobalReceiver(dev.dexbuddy.net.SocialActionPayload.TYPE) { payload, ctx ->
            val player = ctx.player()
            player.server.execute { if (RateLimit.allow(player.uuid, "social", 20, 5_000)) dev.dexbuddy.photo.SocialStore.handle(player, payload.action) }
        }
        ServerLifecycleEvents.SERVER_STARTED.register { dev.dexbuddy.photo.SocialStore.load(it) }
        ServerPlayConnectionEvents.JOIN.register { handler, _, server -> server.execute { dev.dexbuddy.photo.SocialStore.onJoin(handler.player) } }
        PayloadTypeRegistry.playC2S().register(dev.dexbuddy.net.GalleryRequestPayload.TYPE, dev.dexbuddy.net.GalleryRequestPayload.CODEC)
        PayloadTypeRegistry.playS2C().register(dev.dexbuddy.net.GalleryListPayload.TYPE, dev.dexbuddy.net.GalleryListPayload.CODEC)
        PayloadTypeRegistry.playS2C().register(dev.dexbuddy.net.PhotoDataPayload.TYPE, dev.dexbuddy.net.PhotoDataPayload.CODEC)
        PayloadTypeRegistry.playS2C().register(dev.dexbuddy.net.ClipDataPayload.TYPE, dev.dexbuddy.net.ClipDataPayload.CODEC)
        ServerPlayNetworking.registerGlobalReceiver(dev.dexbuddy.net.GalleryRequestPayload.TYPE) { payload, ctx ->
            val player = ctx.player()
            player.server.execute { if (RateLimit.allow(player.uuid, "gallery", 150, 5_000)) dev.dexbuddy.photo.PhotoStore.handle(player, payload.action) }
        }

        // The client checks whether it can send this to learn that the server runs Dex Buddy too.
        PayloadTypeRegistry.playC2S().register(HelloPayload.TYPE, HelloPayload.CODEC)
        ServerPlayNetworking.registerGlobalReceiver(HelloPayload.TYPE) { _, _ -> }

        // Spawn questions: spawn data only exists on the server.
        PayloadTypeRegistry.playC2S().register(SpawnQueryPayload.TYPE, SpawnQueryPayload.CODEC)
        PayloadTypeRegistry.playS2C().register(SpawnAnswerPayload.TYPE, SpawnAnswerPayload.CODEC)
        ServerPlayNetworking.registerGlobalReceiver(SpawnQueryPayload.TYPE) { payload, ctx ->
            val player = ctx.player()
            player.server.execute {
                // Spawn lookups read a lot of data: a few per second at most.
                if (!RateLimit.allow(player.uuid, "spawn", 3, 2_000)) {
                    ServerPlayNetworking.send(player, SpawnAnswerPayload(payload.requestId, "UNAVAILABLE: too many spawn questions at once, try again in a moment."))
                    return@execute
                }
                val text = try {
                    if (payload.query.isBlank()) SpawnInfo.here(player) else SpawnInfo.whereSpawns(payload.query)
                } catch (e: Exception) {
                    LOG.warn("[dexbuddy] spawn lookup failed: {}", e.toString())
                    "UNAVAILABLE: the spawn scanner glitched."
                }
                ServerPlayNetworking.send(player, SpawnAnswerPayload(payload.requestId, text.take(16000)))
            }
        }
    }

    val LOG: org.slf4j.Logger = org.slf4j.LoggerFactory.getLogger("dexbuddy")
}
