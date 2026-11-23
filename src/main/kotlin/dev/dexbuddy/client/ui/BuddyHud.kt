package dev.dexbuddy.client.ui

import dev.dexbuddy.DexBuddyMod
import dev.dexbuddy.client.BuddyConfig
import dev.dexbuddy.client.tts.VoicePlayer
import net.minecraft.world.item.ItemStack
import kotlin.math.sin
import dev.dexbuddy.client.brain.BuddyBrain
import dev.dexbuddy.client.voice.MicRecorder
import dev.dexbuddy.client.voice.PushToTalk
import net.minecraft.client.DeltaTracker
import net.minecraft.client.Minecraft
import net.minecraft.client.gui.GuiGraphics

/** Small status pill above the subtitles: listening (with a mic level meter) or thinking. */
object BuddyHud {
    private const val BG = 0xA0101820.toInt()
    private const val LISTEN = 0xFF45D06B.toInt()
    private const val THINK = 0xFFFFB347.toInt()

    private val icon by lazy { ItemStack(DexBuddyMod.DEX_BUDDY) }

    /**
     * The companion's Dex, drawn big above the subtitles: it bobs gently, hops with the loudness of its
     * voice, and its screen face animates (the item swaps to its talking texture while it speaks).
     */
    private fun drawCompanion(g: GuiGraphics, listening: Boolean, speaking: Boolean) {
        val t = System.currentTimeMillis() / 1000.0
        val bob = sin(t * (if (speaking) 7.0 else 2.5)) * (if (speaking) 1.6 else 1.0)
        val hop = if (speaking) -VoicePlayer.level * 5.0 else if (listening) -MicRecorder.level * 3.0 else 0.0
        val tilt = if (speaking) sin(t * 5.3) * 5.0 else 0.0
        val scale = 2.5f
        val size = 16 * scale
        val x = g.guiWidth() / 2f - size / 2f
        val y = (g.guiHeight() * 0.16f) - 26f - size + (bob + hop).toFloat()
        g.pose().pushPose()
        g.pose().translate(x + size / 2f, y + size / 2f, 0f)
        g.pose().mulPose(com.mojang.math.Axis.ZP.rotationDegrees(tilt.toFloat()))
        g.pose().scale(scale, scale, 1f)
        g.renderItem(icon, -8, -8)
        g.pose().popPose()
    }

    /** "Charged 4:32" above the hotbar's right end while your companion has redstone charge. */
    private fun drawCharge(g: GuiGraphics) {
        val mc = Minecraft.getInstance()
        val player = mc.player ?: return
        val secs = dev.dexbuddy.client.entity.CompanionClient.mine(player)?.chargeSeconds ?: return
        if (secs <= 0) return
        val text = "Charged %d:%02d".format(secs / 60, secs % 60)
        val x = g.guiWidth() / 2 + 96
        val y = g.guiHeight() - 34
        val w = mc.font.width(text) + 14
        g.fill(x, y, x + w, y + 12, 0x90101820.toInt())
        val pulse = (0.6 + 0.4 * sin(System.currentTimeMillis() / 300.0)).toFloat()
        g.fill(x + 3, y + 3, x + 8, y + 9, ((pulse * 255).toInt() shl 24) or 0xFFD54F)
        g.drawString(mc.font, text, x + 11, y + 2, 0xFFFFE082.toInt(), false)
    }

    fun render(g: GuiGraphics, @Suppress("UNUSED_PARAMETER") delta: DeltaTracker) {
        val mc = Minecraft.getInstance()
        if (mc.options.hideGui) return
        drawCharge(g)
        val name = dev.dexbuddy.client.entity.CompanionClient.companionName
        val listening = PushToTalk.state == PushToTalk.State.LISTENING
        val thinking = PushToTalk.state == PushToTalk.State.TRANSCRIBING || BuddyBrain.thinking
        val speaking = VoicePlayer.speaking
        if (listening || thinking || speaking) drawCompanion(g, listening, speaking)
        if (!listening && !thinking) return

        val dots = ".".repeat(((System.currentTimeMillis() / 350) % 4).toInt())
        val label = if (listening) "$name is listening" else "$name is thinking$dots"
        val color = if (listening) LISTEN else THINK
        val font = mc.font
        val meterW = if (listening) 40 else 0
        val w = 14 + font.width(label) + (if (listening) meterW + 6 else font.width("...") - font.width(dots)) + 8
        val x = (g.guiWidth() - w) / 2
        val y = (g.guiHeight() * 0.16).toInt() - 20

        g.fill(x, y, x + w, y + 14, BG)
        // Pulsing dot.
        val pulse = if (listening) 0.6f + 0.4f * MicRecorder.level else 0.5f + 0.5f * kotlin.math.sin(System.currentTimeMillis() / 180.0).toFloat()
        val a = (pulse.coerceIn(0.3f, 1f) * 255).toInt()
        g.fill(x + 5, y + 5, x + 9, y + 9, (a shl 24) or (color and 0xFFFFFF))
        g.drawString(font, label, x + 14, y + 3, 0xFFFFFFFF.toInt(), false)
        if (listening) {
            val mx = x + 14 + font.width(label) + 6
            g.fill(mx, y + 5, mx + meterW, y + 9, 0x60FFFFFF)
            g.fill(mx, y + 5, mx + (meterW * MicRecorder.level).toInt(), y + 9, color)
        }
    }
}
