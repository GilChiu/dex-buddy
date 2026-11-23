package dev.dexbuddy.client.scan

import com.cobblemon.mod.common.entity.pokemon.PokemonEntity
import dev.dexbuddy.DexBuddyMod
import dev.dexbuddy.client.antics.Commands
import dev.dexbuddy.client.context.GameContext
import dev.dexbuddy.client.phone.PhoneScreen
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents
import net.fabricmc.fabric.api.client.rendering.v1.HudRenderCallback
import net.minecraft.ChatFormatting
import net.minecraft.client.Minecraft
import net.minecraft.client.gui.GuiGraphics
import net.minecraft.client.resources.language.I18n
import net.minecraft.client.resources.sounds.SimpleSoundInstance
import net.minecraft.network.chat.Component
import net.minecraft.sounds.SoundEvents
import net.minecraft.util.Mth
import net.minecraft.world.entity.player.Player
import net.minecraft.world.entity.projectile.ProjectileUtil
import net.minecraft.world.level.ClipContext
import net.minecraft.world.phys.HitResult

/**
 * The Dex as a scanner. Holding the Dex Buddy: a quick right-click opens the phone; holding right-click
 * zooms in (like a spyglass) and scans the Cobblemon you're aiming at. When the scan fills up, a card
 * with what it is pops up and your companion tells you about it. Client thread.
 */
object ScanClient {
    private const val TAP_TICKS = 5
    private const val RANGE = 32.0
    private const val SCAN_TICKS = 24

    private var holding = false
    private var heldTicks = 0
    private var zoom = 0f
    private var prevZoom = 0f
    private var target: PokemonEntity? = null
    private var progress = 0f
    private var doneFor: Int = -1

    /** The result tag: floats above the scanned Cobblemon's head and follows it. */
    private class Card(val entity: PokemonEntity, val title: String, val level: String, val lines: List<Pair<String, Int>>, val until: Long)
    private var card: Card? = null

    private const val HOLO = 0xFF7FE8FF.toInt()
    private const val HOLO_DIM = 0x667FE8FF
    private const val PANEL = 0xDC0C283C.toInt()
    private const val TYPE_COL = 0xFFF7C948.toInt()
    private const val SUB_COL = 0xFFC9D8E8.toInt()
    private const val NEW_COL = 0xFF9FF0C8.toInt()

    // The camera of the last frame, to place the box and tag over the Cobblemon on screen.
    private val view = org.joml.Matrix4f()
    private val proj = org.joml.Matrix4f()
    private var camPos = net.minecraft.world.phys.Vec3.ZERO

    val scanning: Boolean get() = holding && heldTicks >= TAP_TICKS

    /** Called on every right-click "use" with the Dex in hand (repeats while held). */
    fun pressed() { if (!holding) { holding = true; heldTicks = 0 } }

    fun register() {
        ClientTickEvents.END_CLIENT_TICK.register { tick(it) }
        net.fabricmc.fabric.api.client.rendering.v1.WorldRenderEvents.LAST.register { ctx ->
            view.set(ctx.positionMatrix()); proj.set(ctx.projectionMatrix()); camPos = ctx.camera().position
        }
        HudRenderCallback.EVENT.register { g, delta -> render(g, delta.getGameTimeDeltaPartialTick(true)) }
    }

    /** The camera's field of view multiplier: smoothly zoomed in while scanning. */
    @JvmStatic
    fun fovFactor(partial: Float): Double = 1.0 - 0.55 * Mth.lerp(partial, prevZoom, zoom)

    private fun holdsDex(p: Player) = p.mainHandItem.`is`(DexBuddyMod.DEX_BUDDY) || p.offhandItem.`is`(DexBuddyMod.DEX_BUDDY)

    private fun tick(mc: Minecraft) {
        val p = mc.player
        prevZoom = zoom
        if (p == null) { holding = false; zoom = 0f; return }
        if (holding) {
            if (mc.options.keyUse.isDown && holdsDex(p) && mc.screen == null) heldTicks++
            else {
                // A tap (not a hold) opens the phone.
                if (heldTicks < TAP_TICKS && mc.screen == null && holdsDex(p)) {
                    PhoneScreen.open()
                    dev.dexbuddy.client.antics.Tricks.used("phone")
                    dev.dexbuddy.client.antics.Tricks.nudge("phone_open", 3, "messages", "Psst! You can text me in Messages. I'm pinned at the top!")
                }
                holding = false; heldTicks = 0; target = null; progress = 0f; doneFor = -1
            }
        }
        // The view zooms while the phone grows to fill it (from 0.2 s in).
        val since = heldTicks - TAP_TICKS
        zoom += ((if (scanning && since >= 4) 1f else 0f) - zoom) * 0.3f
        if (!scanning) {
            // Holding the Dex and staring at a Cobblemon for a few seconds: suggest scanning it (once ever).
            if (holdsDex(p) && mc.screen == null && ++lookTicks % 10 == 0) {
                lookCount = if (aimed(p) != null) lookCount + 1 else 0
                if (lookCount >= 6) dev.dexbuddy.client.antics.Tricks.suggest("scan", "Ooh! Hold right-click with me to scan that Cobblemon!")
            }
            return
        }
        val t = aimed(p)
        if (t?.id != target?.id) { target = t; progress = 0f }
        if (t == null || doneFor == t.id) return
        // The scan itself starts once the phone has turned into the brackets.
        if (since < (ANIM_END * 20).toInt()) return
        progress = (progress + 1f / SCAN_TICKS).coerceAtMost(1f)
        if (progress >= 1f) { doneFor = t.id; complete(t) }
    }

    /** The Cobblemon under the crosshair, up to [RANGE] blocks away and not behind a wall. */
    private fun aimed(p: Player): PokemonEntity? {
        val eye = p.getEyePosition(1f)
        val look = p.getViewVector(1f)
        val end = eye.add(look.scale(RANGE))
        val box = p.boundingBox.expandTowards(look.scale(RANGE)).inflate(1.0)
        val hit = ProjectileUtil.getEntityHitResult(p, eye, end, box, { it is PokemonEntity && !it.isInvisible }, RANGE * RANGE) ?: return null
        val block = p.level().clip(ClipContext(eye, end, ClipContext.Block.COLLIDER, ClipContext.Fluid.NONE, p))
        if (block.type != HitResult.Type.MISS && eye.distanceToSqr(block.location) < eye.distanceToSqr(hit.location)) return null
        return hit.entity as? PokemonEntity
    }

    private var lookTicks = 0
    private var lookCount = 0

    private fun complete(e: PokemonEntity) {
        dev.dexbuddy.client.antics.Tricks.used("scan")
        val mc = Minecraft.getInstance()
        mc.soundManager.play(SimpleSoundInstance.forUI(SoundEvents.AMETHYST_BLOCK_CHIME, 1.6f, 1f))
        val pk = e.pokemon
        val sp = pk.species
        val name = sp.translatedName.string
        val types = pk.types.map { it.displayName.string }
        val owner = GameContext.ownerOf(e)
        val whose = when (owner) {
            null -> "Wild"
            mc.player?.uuid -> "Yours"
            else -> (mc.level?.getPlayerByUUID(owner)?.gameProfile?.name ?: "A Trainer") + "'s"
        }
        val desc = sp.pokedex.firstOrNull()?.let { if (I18n.exists(it)) I18n.get(it) else null }?.replace(Regex("\\s+"), " ")
            ?.replace(Regex("\\bPok[eé]mon\\b", RegexOption.IGNORE_CASE), "Cobblemon")
        // Shared with the Cobblemon Pokédex: what it already knows, and this scan registers there too.
        val known = try {
            com.cobblemon.mod.common.client.CobblemonClient.clientPokedexData.getKnowledgeForSpecies(sp.resourceIdentifier)
        } catch (_: Throwable) { null }
        val isNew = known == null || known == com.cobblemon.mod.common.api.pokedex.PokedexEntryProgress.NONE
        val status = when (known) {
            com.cobblemon.mod.common.api.pokedex.PokedexEntryProgress.CAUGHT -> "In your Pokédex · caught"
            com.cobblemon.mod.common.api.pokedex.PokedexEntryProgress.ENCOUNTERED -> "In your Pokédex · seen"
            else -> "New! Added to your Pokédex"
        }
        if (net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking.canSend(dev.dexbuddy.net.CompanionCommandPayload.TYPE))
            net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking.send(dev.dexbuddy.net.CompanionCommandPayload("dexscan:${e.id}"))
        val lines = mutableListOf<Pair<String, Int>>()
        lines += "${types.joinToString(" / ")} · #${sp.nationalPokedexNumber}" to TYPE_COL
        lines += "$whose${if (pk.shiny) " · Shiny!" else ""} · %.1f m · %.1f kg".format(sp.height / 10f, sp.weight / 10f) to SUB_COL
        lines += (if (isNew) "New · added to your Pokédex" else status) to (if (isNew) NEW_COL else SUB_COL)
        card = Card(e, pk.nickname?.string?.takeIf { it.isNotBlank() }?.let { "$it ($name)" } ?: name, "Lv ${pk.level}", lines, System.currentTimeMillis() + 10_000)
        // Your companion tells you about it (and it lands in your chat with it).
        val kind = types.joinToString(" and ")
        val first = desc?.substringBefore(". ")?.trimEnd('.')?.let { " $it." } ?: ""
        val who = when (whose) { "Wild" -> "a wild"; "Yours" -> "your"; else -> "$whose" }
        val article = if (kind.firstOrNull()?.lowercaseChar() in setOf('a', 'e', 'i', 'o', 'u')) "an" else "a"
        Commands.reply("${if (isNew) "New Pokédex entry!" else "Scan complete!"} That's $who $name, $article $kind type.$first")
        GameContext.noteScanned(e)
    }

    // ---- drawing ------------------------------------------------------------------------------------

    /** A world point on the GUI screen, or null when it's behind the camera. */
    private fun toScreen(x: Double, y: Double, z: Double, w: Int, h: Int): Pair<Float, Float>? {
        val v = org.joml.Vector4f((x - camPos.x).toFloat(), (y - camPos.y).toFloat(), (z - camPos.z).toFloat(), 1f)
        view.transform(v); proj.transform(v)
        if (v.w <= 0.05f) return null
        return ((v.x / v.w) * 0.5f + 0.5f) * w to (1f - ((v.y / v.w) * 0.5f + 0.5f)) * h
    }

    /** The Cobblemon's outline on screen: left, top, right, bottom (null if off screen). */
    private fun screenBox(e: PokemonEntity, partial: Float, w: Int, h: Int): FloatArray? {
        val p = e.getPosition(partial)
        val hw = e.bbWidth / 2.0; val bh = e.bbHeight.toDouble()
        var l = Float.MAX_VALUE; var t = Float.MAX_VALUE; var r = -Float.MAX_VALUE; var b = -Float.MAX_VALUE
        for (dx in listOf(-hw, hw)) for (dy in listOf(0.0, bh)) for (dz in listOf(-hw, hw)) {
            val s = toScreen(p.x + dx, p.y + dy, p.z + dz, w, h) ?: return null
            l = minOf(l, s.first); r = maxOf(r, s.first); t = minOf(t, s.second); b = maxOf(b, s.second)
        }
        if (r < 0 || l > w || b < 0 || t > h) return null
        return floatArrayOf(l, t, r, b)
    }

    /** Thin corner brackets around a box. */
    private fun brackets(g: GuiGraphics, l: Int, t: Int, r: Int, b: Int, col: Int) {
        val k = maxOf(5, minOf(r - l, b - t) / 4)
        g.fill(l, t, l + k, t + 1, col); g.fill(l, t, l + 1, t + k, col)
        g.fill(r - k, t, r, t + 1, col); g.fill(r - 1, t, r, t + k, col)
        g.fill(l, b - 1, l + k, b, col); g.fill(l, b - k, l + 1, b, col)
        g.fill(r - k, b - 1, r, b, col); g.fill(r - 1, b - k, r, b, col)
    }

    /** A small ring of dots; the first [p] of it lit. */
    private fun ring(g: GuiGraphics, cx: Int, cy: Int, p: Float) {
        val n = 16; val rad = 6.0
        for (i in 0 until n) {
            val a = -Math.PI / 2 + i * 2 * Math.PI / n
            val x = cx + (kotlin.math.cos(a) * rad).toInt(); val y = cy + (kotlin.math.sin(a) * rad).toInt()
            g.fill(x - 1, y - 1, x + 1, y + 1, if (i < (p * n).toInt()) HOLO else 0x55FFFFFF)
        }
    }

    // ---- raising the phone (the start of a scan) ----------------------------------------------------

    private const val ANIM_END = 0.8f

    /** Seconds since the scan started (the hold passed a tap), or -1. */
    private fun animTime(partial: Float): Float = if (scanning) (heldTicks - TAP_TICKS + partial) / 20f else -1f

    /** While scanning, the hand and the held Dex aren't drawn: the phone has left your hand. */
    @JvmStatic
    fun hideHands(): Boolean = scanning

    private fun smooth(x: Float): Float { val c = x.coerceIn(0f, 1f); return c * c * (3 - 2 * c) }
    private fun lerp(a: Float, b: Float, t: Float) = a + (b - a) * t
    private fun lerpColor(a: Int, b: Int, t: Float): Int {
        fun ch(s: Int) = ((((a shr s) and 0xFF) * (1 - t)) + (((b shr s) and 0xFF) * t)).toInt() shl s
        return ch(24) or ch(16) or ch(8) or ch(0)
    }

    /**
     * The phone lifts out of your hand to the middle, grows to fill your view, then its body fades and
     * its four corners shrink onto the Cobblemon you're aiming at, becoming the scanner's brackets.
     */
    private fun drawRaise(g: GuiGraphics, t: Float, w: Int, h: Int, end: FloatArray) {
        // Rectangles as centre x, centre y, width, height.
        val hand = floatArrayOf(w * 0.80f, h * 0.88f, w * 0.07f, h * 0.20f)
        val mid = floatArrayOf(w / 2f, h / 2f, h * 0.30f, h * 0.56f)
        val full = floatArrayOf(w / 2f, h / 2f, h * 0.58f, h * 0.98f)
        val tgt = floatArrayOf((end[0] + end[2]) / 2, (end[1] + end[3]) / 2, end[2] - end[0], end[3] - end[1])
        val (from, to, k) = when {
            t < 0.2f -> Triple(hand, mid, smooth(t / 0.2f))
            t < 0.42f -> Triple(mid, full, smooth((t - 0.2f) / 0.22f))
            else -> Triple(full, tgt, smooth((t - 0.42f) / (ANIM_END - 0.42f)))
        }
        val cx = lerp(from[0], to[0], k); val cy = lerp(from[1], to[1], k)
        val rw = lerp(from[2], to[2], k); val rh = lerp(from[3], to[3], k)
        val l = (cx - rw / 2).toInt(); val tp = (cy - rh / 2).toInt(); val r = (cx + rw / 2).toInt(); val b = (cy + rh / 2).toInt()
        val bodyA = 1f - smooth((t - 0.42f) / 0.14f)
        if (bodyA > 0.01f) {
            // The phone: orange frame, dark screen with Volty's face.
            val frame = ((bodyA * 255).toInt() shl 24) or 0xF2603B
            val screen = ((bodyA * 150).toInt() shl 24) or 0x2A2F45
            val bw = maxOf(2, (rw * 0.05f).toInt())
            g.fill(l, tp, r, b, screen)
            g.fill(l, tp, r, tp + bw, frame); g.fill(l, b - bw, r, b, frame)
            g.fill(l, tp, l + bw, b, frame); g.fill(r - bw, tp, r, b, frame)
            val eye = ((bodyA * 255).toInt() shl 24) or 0xFFE066
            val u = maxOf(1, (rw / 16).toInt())
            val fx = cx.toInt(); val fy = cy.toInt()
            g.fill(fx - 3 * u, fy - 2 * u, fx - 2 * u, fy, eye); g.fill(fx + 2 * u, fy - 2 * u, fx + 3 * u, fy, eye)
            g.fill(fx - u, fy + u, fx + u, fy + u + maxOf(1, u / 2), eye)
        }
        // The four corners: orange while they're the phone's, cyan once they're the scanner's.
        val col = lerpColor(0xFFF2603B.toInt(), HOLO, smooth((t - 0.5f) / 0.3f))
        brackets(g, l, tp, r, b, col)
    }

    private fun render(g: GuiGraphics, partial: Float) {
        val mc = Minecraft.getInstance()
        if (mc.options.hideGui) return
        val w = g.guiWidth(); val h = g.guiHeight()
        val at = animTime(partial)
        if (at in 0f..ANIM_END) {
            g.fill(0, 0, w, h, ((smooth(at / 0.42f) * 45).toInt() shl 24) or 0x0A0818)
            val end = target?.let { screenBox(it, partial, w, h) }?.let { floatArrayOf(it[0] - 3, it[1] - 3, it[2] + 3, it[3] + 3) }
                ?: floatArrayOf(w / 2f - 30, h / 2f - 30, w / 2f + 30, h / 2f + 30)
            drawRaise(g, at, w, h, end)
        } else if (zoom > 0.02f) {
            // A light tint while the scanner is up.
            g.fill(0, 0, w, h, ((zoom * 45).toInt().coerceIn(0, 255) shl 24) or 0x0A0818)
            if (scanning) {
                val t = target
                val box = t?.let { screenBox(it, partial, w, h) }
                if (box == null) {
                    // Nothing aimed at: a small hint under the crosshair.
                    g.drawCenteredString(mc.font, "Point at a Cobblemon", w / 2, h / 2 + 14, 0xC0FFFFFF.toInt())
                } else {
                    val l = box[0].toInt() - 3; val tp = box[1].toInt() - 3; val r = box[2].toInt() + 3; val b = box[3].toInt() + 3
                    brackets(g, l, tp, r, b, if (progress >= 1f) HOLO else HOLO_DIM or (0xCC shl 24))
                    if (progress < 1f) ring(g, (l + r) / 2, b + 10, progress)
                }
            }
        }
        val c = card ?: return
        // Gone after a while, when the Cobblemon is, or as soon as you put the Dex away.
        val holding = mc.player?.let { holdsDex(it) } ?: false
        if (System.currentTimeMillis() > c.until || !c.entity.isAlive || !holding) { card = null; return }
        val box = screenBox(c.entity, partial, w, h) ?: return
        // The tag floats beside it (clear of Cobblemon's own name label above its head and the
        // subtitles at the top), joined by a thin line; it flips to the left side near the screen edge.
        val font = mc.font
        val title = Component.literal(c.title).withStyle(ChatFormatting.BOLD)
        val tw = maxOf(font.width(title) + font.width(c.level) + 14, c.lines.maxOf { font.width(it.first) } + 12).coerceAtMost(220)
        val th = 14 + c.lines.size * 10 + 4
        val l = box[0].toInt(); val r = box[2].toInt()
        val midY = ((box[1] + box[3]) / 2).toInt()
        val right = r + 14 + tw <= w - 4
        val x0 = if (right) r + 14 else maxOf(4, l - 14 - tw)
        val y0 = (midY - th / 2).coerceIn(4, h - th - 30)
        val lineY = midY.coerceIn(y0 + 2, y0 + th - 2)
        if (right) g.fill(r + 2, lineY, x0, lineY + 1, HOLO) else g.fill(x0 + tw, lineY, l - 2, lineY + 1, HOLO)
        g.fill(x0, y0, x0 + tw, y0 + th, PANEL)
        g.fill(x0, y0, x0 + tw, y0 + 1, HOLO); g.fill(x0, y0 + th - 1, x0 + tw, y0 + th, HOLO)
        g.fill(x0, y0, x0 + 1, y0 + th, HOLO); g.fill(x0 + tw - 1, y0, x0 + tw, y0 + th, HOLO)
        g.drawString(font, title, x0 + 6, y0 + 5, 0xFFF3EEFF.toInt(), false)
        g.drawString(font, c.level, x0 + tw - 6 - font.width(c.level), y0 + 5, HOLO, false)
        c.lines.forEachIndexed { i, (s, col) -> g.drawString(font, font.plainSubstrByWidth(s, tw - 12), x0 + 6, y0 + 17 + i * 10, col, false) }
    }
}
