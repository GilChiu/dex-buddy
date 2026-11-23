package dev.dexbuddy.client.antics

import com.cobblemon.mod.common.client.CobblemonClient
import dev.dexbuddy.client.BuddyConfig
import net.minecraft.client.Minecraft
import net.minecraft.network.chat.Component
import net.minecraft.network.chat.contents.TranslatableContents
import java.util.UUID

/**
 * Hype-man for Cobblemon battles: listens to the battle log (super effective, critical hits, misses,
 * faints, wins and losses) and cheers, groans and gloats. Client thread.
 */
object BattleCommentary {
    private var battleId: UUID? = null
    private var ended = false

    fun tick() {
        val battle = CobblemonClient.battle
        if (battle == null) { battleId = null; return }
        if (battle.battleId == battleId) return
        battleId = battle.battleId
        ended = false
        if (!BuddyConfig.settings.personality.battleCommentary || battle.spectating) return
        val wild = battle.wildActor?.pokemon?.firstOrNull()?.species?.translatedName?.string
        if (wild != null) Speaker.say("battle_start", Speaker.pick(Lines.battleStartWild, "mon" to wild), 20_000)
        else Speaker.say("battle_start", Speaker.pick(Lines.battleStartTrainer), 20_000)
        val id = battle.battleId
        battle.messages.subscribe { msg -> Minecraft.getInstance().execute { if (battleId == id) onMessage(msg) } }
    }

    private fun onMessage(msg: Component) {
        if (!BuddyConfig.settings.personality.battleCommentary || ended) return
        for ((key, args) in keys(msg)) {
            val arg0 = args.firstOrNull()?.let { if (it is Component) it.string else it.toString() } ?: ""
            when {
                key.startsWith("cobblemon.battle.superEffective") -> Speaker.say("b_super", Speaker.pick(Lines.superEffective), 8_000)
                key.startsWith("cobblemon.battle.crit") -> Speaker.say("b_crit", Speaker.pick(Lines.crit), 8_000)
                key.startsWith("cobblemon.battle.resisted") -> Speaker.say("b_resist", Speaker.pick(Lines.resisted), 25_000)
                key == "cobblemon.battle.immune" -> Speaker.say("b_immune", Speaker.pick(Lines.immune), 15_000)
                key == "cobblemon.battle.missed" -> Speaker.say("b_miss", Speaker.pick(Lines.missed), 15_000)
                key == "cobblemon.battle.fainted" -> {
                    val name = arg0.substringAfterLast("'s ").substringAfter("The opposing ").trim()
                    if (isMine(name)) Speaker.say("b_faint", Speaker.pick(Lines.allyFainted, "mon" to name), 5_000, urgent = true)
                    else Speaker.say("b_faint", Speaker.pick(Lines.enemyFainted, "mon" to name), 5_000, urgent = true)
                }
                key == "cobblemon.battle.win" -> {
                    ended = true
                    val me = Minecraft.getInstance().player?.gameProfile?.name ?: ""
                    if (arg0.contains(me, true)) Speaker.say("b_end", Speaker.pick(Lines.won), 5_000, urgent = true)
                    else Speaker.say("b_end", Speaker.pick(Lines.lost), 5_000, urgent = true)
                }
                key == "cobblemon.battle.flee" -> { ended = true; Speaker.say("b_end", Speaker.pick(Lines.fled), 5_000, urgent = true) }
            }
        }
    }

    /** Is this display name one of the Trainer's own team? */
    private fun isMine(name: String): Boolean =
        CobblemonClient.storage.party.slots.filterNotNull().any {
            it.getDisplayName(false).string.equals(name, true) || it.species.translatedName.string.equals(name, true)
        }

    /** Every translation key in a message (battle lines are often nested), with its arguments. */
    private fun keys(c: Component): List<Pair<String, Array<Any>>> {
        val out = ArrayList<Pair<String, Array<Any>>>()
        fun walk(x: Component) {
            val contents = x.contents
            if (contents is TranslatableContents) {
                out += contents.key to contents.args
                contents.args.forEach { if (it is Component) walk(it) }
            }
            x.siblings.forEach { walk(it) }
        }
        walk(c)
        return out
    }
}
