package dev.dexbuddy.client.antics

import com.cobblemon.mod.common.client.CobblemonClient
import dev.dexbuddy.client.BuddyConfig
import dev.dexbuddy.client.entity.CompanionClient
import net.minecraft.client.Minecraft
import net.minecraft.core.particles.ParticleTypes
import net.minecraft.world.entity.monster.Monster

/**
 * How the companion feels right now. Moods color its idle chatter, its voice pitch, how it floats, and
 * the tone of its answers (the model is told the mood). Client thread.
 */
object Mood {
    enum class State(val pitch: Double, val describe: String) {
        HAPPY(1.0, "happy and chipper"),
        EXCITED(1.07, "super excited, sparking with energy"),
        BORED(0.97, "bored and a little dramatic about it"),
        SLEEPY(0.94, "sleepy and yawny"),
        SCARED(1.05, "nervous and jumpy"),
    }

    @Volatile var state: State = State.HAPPY
        private set

    private var lastActivity = System.currentTimeMillis()
    private var lastIdleLine = System.currentTimeMillis()
    private var tick = 0

    /** Anything the Trainer does with the companion (asking, petting, a reaction) resets boredom. */
    fun noteActivity() { lastActivity = System.currentTimeMillis() }

    fun tick() {
        val mc = Minecraft.getInstance()
        val player = mc.player ?: return
        val level = mc.level ?: return
        if (++tick % 40 != 0) { snoreParticles(); return }
        if (!BuddyConfig.settings.personality.moods) { state = State.HAPPY; return }
        val now = System.currentTimeMillis()
        val idle = now - lastActivity
        val t = (level.dayTime % 24000).toInt()
        val night = t in 13000..23000
        val monsterNear = level.getEntitiesOfClass(Monster::class.java, player.boundingBox.inflate(10.0)).isNotEmpty()
        state = when {
            Radar.target != null || CobblemonClient.battle != null -> State.EXCITED
            night && monsterNear -> State.SCARED
            night && idle > 60_000 -> State.SLEEPY
            idle > 5 * 60_000 -> State.BORED
            else -> State.HAPPY
        }
        // Idle chatter, at most every few minutes.
        if (now - lastIdleLine > 200_000 && idle > 90_000) {
            val said = when (state) {
                State.BORED -> Speaker.say("mood", Speaker.pick(Lines.bored), 180_000)
                State.SLEEPY -> Speaker.say("mood", Speaker.pick(Lines.sleepy), 180_000)
                State.SCARED -> Speaker.say("mood", Speaker.pick(Lines.scared), 180_000)
                else -> false
            }
            if (said) lastIdleLine = now
        }
    }

    /** Little "z" puffs over a snoozing companion that's out of the Dex. */
    private fun snoreParticles() {
        if (state != State.SLEEPY || tick % 25 != 0) return
        val mc = Minecraft.getInstance()
        val player = mc.player ?: return
        val buddy = CompanionClient.mine(player) ?: return
        mc.level?.addParticle(ParticleTypes.CLOUD, buddy.x + 0.15, buddy.y + buddy.bbHeight + 0.15, buddy.z, 0.0, 0.03, 0.0)
    }
}
