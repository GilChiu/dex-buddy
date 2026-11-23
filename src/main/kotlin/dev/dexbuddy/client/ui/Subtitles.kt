package dev.dexbuddy.client.ui

import dev.dexbuddy.client.BuddyConfig
import net.minecraft.client.DeltaTracker
import net.minecraft.client.Minecraft
import net.minecraft.client.gui.GuiGraphics
import net.minecraft.network.chat.Component
import net.minecraft.util.Mth

/** What the companion is saying, shown in the upper middle of the screen. Each line stays up for a time based on its length. */
object Subtitles {
    private class Line(val text: String, val shownAt: Long, val millis: Long)

    private val lines = ArrayDeque<Line>()
    private const val MAX_LINES = 2

    /** Client thread only. */
    fun push(text: String) {
        if (dev.dexbuddy.client.DexBuddyClient.logTalk) dev.dexbuddy.client.DexBuddyClient.LOG.info("[dexbuddy] says: {}", text)
        val now = System.currentTimeMillis()
        // A line starts its timer when the one before it ends, so a fast stream still reads in order.
        val start = maxOf(now, lines.lastOrNull()?.let { it.shownAt + it.millis - 400 } ?: now)
        lines.addLast(Line(text, start, 2500L + text.length * 55L))
        while (lines.size > 6) lines.removeFirst()
    }

    fun clear() = lines.clear()

    fun render(g: GuiGraphics, @Suppress("UNUSED_PARAMETER") delta: DeltaTracker) {
        if (!BuddyConfig.settings.showSubtitles || lines.isEmpty()) return
        val mc = Minecraft.getInstance()
        if (mc.options.hideGui) return
        val now = System.currentTimeMillis()
        lines.removeAll { now > it.shownAt + it.millis }
        val visible = lines.filter { now >= it.shownAt }.takeLast(MAX_LINES)
        if (visible.isEmpty()) return

        val font = mc.font
        val maxWidth = (g.guiWidth() * 0.6).toInt()
        // Upper middle: clear of chat (bottom left), the hotbar, and Cobblemon's party bar (left).
        var y = (g.guiHeight() * 0.16).toInt()
        for (line in visible) {
            val wrapped = font.split(Component.literal("${dev.dexbuddy.client.entity.CompanionClient.companionName}: ").withStyle { it.withColor(0xFFB347) }
                .append(Component.literal(line.text).withStyle { it.withColor(0xFFFFFF) }), maxWidth)
            val left = line.shownAt + line.millis - now
            val alpha = Mth.clamp(left / 400f, 0f, 1f)
            val a = (alpha * 255).toInt().coerceAtLeast(8)
            for (seq in wrapped) {
                val w = font.width(seq)
                val x = (g.guiWidth() - w) / 2
                g.fill(x - 3, y - 2, x + w + 3, y + 9, (a * 0.55f).toInt() shl 24)
                g.drawString(font, seq, x, y, (a shl 24) or 0xFFFFFF, false)
                y += 12
            }
            y += 2
        }
    }
}
