package dev.dexbuddy.client.antics

import dev.dexbuddy.client.BuddyConfig
import dev.dexbuddy.client.entity.CompanionClient
import dev.dexbuddy.entity.DexBuddyEntity
import net.minecraft.client.Minecraft
import net.minecraft.world.entity.player.Player
import java.util.UUID

/** What the floating companion says about being petted, fed, found, and about other Trainers. Client thread. */
object CompanionAntics {
    private val petLines = listOf("Hehe! That tickles!", "Mmm, more head pats please!", "Bzzz... so happy...", "You're the best, {trainer}!")
    private val feedLines = listOf(
        "Yummy, redstone! I'm all charged up! I'll keep my eyes open for goodies for you!",
        "Bzzt! Fully charged! Your team's going to train extra hard now!",
        "Nom nom! Charged and ready! Let's go find some treasure!",
    )
    private val berryLines = listOf("Mmm, a berry! Sweet! Thank you, {trainer}!", "Yummy! Berries are my favorite... after redstone!")
    private val foundLines = listOf("Aww, you found me! Again! Again!", "Noooo, how did you find me so fast?!", "Found! You've got sharp eyes, {trainer}!")
    private val warmer = listOf("Warmer... hehe!", "Ooh, getting warmer!", "Warmer! You're on the right track!")
    private val colder = listOf("Colder... brrr!", "Nope, colder!", "Brr, you're going the wrong way!")
    private val veryClose = listOf("Hehe... I'm super close! Hee hee!", "Shh... don't look over here...", "You're so warm right now!")
    private val milestones = mapOf(
        10 to "Hey {trainer}... I really like being your Dex buddy.",
        25 to "You know what? You're my favorite Trainer in the whole world!",
        50 to "Fifty hearts of friendship! I'd follow you to the End and back!",
        100 to "We're best friends forever, {trainer}. Bzzt! That's a Dex fact!",
    )
    private val greetBuddy = listOf("Oh! Another Dex buddy! Hi there, cousin!", "Bzzt bzzt! That's a fellow Dex! Hiii!", "Look, {trainer}, a friend for me!")
    private val greetTrainer = listOf("Oh, hi {other}!", "Hey look, it's {other}! Hello!", "{other}! Want to see my Dex? It's full of cool stuff!")

    private var lastHappy = 0
    private var lastCharge = 0
    private var lastFriendship = -1
    private var lastCostumes = -1
    private var lastMode = DexBuddyEntity.Mode.FOLLOW
    private var hideCheck = 0L
    private var hideLastDist = -1.0
    private var tick = 0
    private val greeted = HashMap<UUID, Long>()

    fun tick() {
        val mc = Minecraft.getInstance()
        val player = mc.player ?: return
        val buddy = CompanionClient.mine(player, 96.0)
        tick++
        if (buddy != null) {
            // Petted or fed (the server sets a happy timer).
            val happy = buddy.happyTicks
            if (happy > lastHappy && happy >= DexBuddyEntity.PET_TICKS - 2) {
                if (lastMode == DexBuddyEntity.Mode.HIDING && buddy.mode == DexBuddyEntity.Mode.FOLLOW) Speaker.say("found", Speaker.pick(foundLines), 3_000, urgent = true)
                else {
                    Tricks.used("pet") // the Quick guide's "Pet and feed"
                    if (happy >= DexBuddyEntity.FEED_TICKS - 2)
                        // Charge going up means it was redstone; otherwise a berry.
                        Speaker.say("fed", Speaker.pick(if (buddy.chargeSeconds > lastCharge + 30) feedLines else berryLines), 4_000, urgent = true)
                    else Speaker.say("petted", Speaker.pick(petLines), 6_000, urgent = true)
                }
            }
            lastHappy = happy
            lastCharge = buddy.chargeSeconds
            // Friendship milestones (not on the first sync after joining).
            val f = buddy.friendship
            if (lastFriendship >= 0) milestones.entries.firstOrNull { (at, _) -> lastFriendship < at && f >= at }?.let { (_, line) ->
                Speaker.say("friendship", Speaker.pick(listOf(line)), 1_000, urgent = true)
            }
            lastFriendship = f
            // A costume was just unlocked (given by hand, or earned at a milestone).
            val bits = buddy.costumeBits
            if (lastCostumes >= 0 && bits != lastCostumes) {
                dev.dexbuddy.entity.Look.entries.firstOrNull { (bits shr it.ordinal) and 1 == 1 && (lastCostumes shr it.ordinal) and 1 == 0 }?.let { got ->
                    Speaker.say("costume", "Bzzt! New costume: the ${got.displayName}! Say 'wear the ${got.spoken.first()}' anytime!", 1_000, urgent = true)
                }
            }
            lastCostumes = bits
            tickHideAndSeek(player, buddy)
            lastMode = buddy.mode
        } else { lastHappy = 0; lastMode = DexBuddyEntity.Mode.FOLLOW; lastCostumes = -1 }
        if (tick % 40 == 0) banter(player)
    }

    private fun tickHideAndSeek(player: Player, buddy: DexBuddyEntity) {
        if (buddy.mode != DexBuddyEntity.Mode.HIDING) { hideLastDist = -1.0; return }
        val now = System.currentTimeMillis()
        if (now < hideCheck) return
        hideCheck = now + 9_000
        val d = buddy.distanceTo(player).toDouble()
        val line = when {
            d < 7 -> veryClose
            hideLastDist < 0 -> null
            d < hideLastDist - 2 -> warmer
            d > hideLastDist + 2 -> colder
            else -> null
        }
        hideLastDist = d
        line?.let { Speaker.say("hideseek", Speaker.pick(it), 8_000, urgent = true) }
    }

    /** Says hi to other Trainers' companions and to other Trainers nearby (once in a while each). */
    private fun banter(player: Player) {
        if (!BuddyConfig.settings.personality.banter) return
        val level = player.level()
        val now = System.currentTimeMillis()
        val otherBuddy = level.getEntitiesOfClass(DexBuddyEntity::class.java, player.boundingBox.inflate(10.0)) { it.ownerId != player.uuid && it.ownerId != null }.firstOrNull()
        if (otherBuddy != null && now - (greeted[otherBuddy.uuid] ?: 0L) > 600_000) {
            if (Speaker.say("banter", Speaker.pick(greetBuddy), 120_000)) greeted[otherBuddy.uuid] = now
            return
        }
        val other = level.players().firstOrNull { it != player && it.distanceTo(player) < 8 && !it.isSpectator } ?: return
        if (now - (greeted[other.uuid] ?: 0L) < 1_800_000) return
        if (Speaker.say("banter", Speaker.pick(greetTrainer, "other" to other.gameProfile.name), 120_000)) greeted[other.uuid] = now
    }
}
