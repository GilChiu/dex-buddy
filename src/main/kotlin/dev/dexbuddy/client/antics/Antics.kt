package dev.dexbuddy.client.antics

import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents
import dev.dexbuddy.net.QuizLinePayload
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayConnectionEvents
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking

/** Hooks up everything the companion does on its own. */
object Antics {
    fun register() {
        ClientTickEvents.END_CLIENT_TICK.register { mc ->
            if (mc.player == null || mc.isPaused) return@register
            Reactions.tick()
            BattleCommentary.tick()
            Radar.tick()
            Mood.tick()
            CompanionAntics.tick()
        }
        ClientPlayConnectionEvents.JOIN.register { _, _, _ -> Reactions.reset(); Mood.noteActivity(); Commands.quizOpen = false }
        ClientPlayNetworking.registerGlobalReceiver(QuizLinePayload.TYPE) { payload, _ ->
            dev.dexbuddy.client.ui.QuizHud.update(payload.species, payload.open, payload.reveal)
            Commands.onQuizLine(payload.line, payload.open)
        }
        // The charged companion found something for us.
        ClientPlayNetworking.registerGlobalReceiver(dev.dexbuddy.net.GiftPayload.TYPE) { payload, _ ->
            val name = net.minecraft.client.resources.language.I18n.get(payload.itemKey)
            val what = if (payload.count > 1) "${payload.count} $name" else "a $name"
            Speaker.say("gift", Speaker.pick(listOf("Look what I found! $what! It's yours, {trainer}!", "Bzzt! I dug up $what for you!", "Ooh, shiny! I found $what! Present for you!")), 5_000, urgent = true)
        }
    }
}
