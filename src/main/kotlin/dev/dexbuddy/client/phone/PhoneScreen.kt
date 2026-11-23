package dev.dexbuddy.client.phone

import dev.dexbuddy.DexBuddyMod
import dev.dexbuddy.client.antics.Commands
import dev.dexbuddy.client.entity.CompanionClient
import dev.dexbuddy.client.entity.FaceSpec
import dev.dexbuddy.client.entity.LookModels
import dev.dexbuddy.client.music.MusicClient
import dev.dexbuddy.entity.DexBuddyEntity
import dev.dexbuddy.entity.Look
import net.minecraft.ChatFormatting
import net.minecraft.client.Minecraft
import net.minecraft.client.gui.GuiGraphics
import net.minecraft.client.gui.screens.Screen
import net.minecraft.core.component.DataComponents
import net.minecraft.nbt.CompoundTag
import net.minecraft.network.chat.Component
import net.minecraft.world.item.ItemStack
import net.minecraft.world.item.component.CustomData
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import kotlin.math.roundToInt
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * The Dex phone. A modern phone with your companion's face on the home screen: it blinks and floats,
 * and the whole screen follows the world's clock (a starry violet night, or a sunny sky by day).
 * Home has the apps (Gallery, Wardrobe, Feed, Friends, Quiz, Profile) plus a flashlight (your
 * companion's glow) and the camera.
 */
class PhoneScreen(private var page: Tab = Tab.HOME) : Screen(Component.literal("Dex Phone")) {
    enum class Tab { HOME, GALLERY, WARDROBE, FEED, FRIENDS, PROFILE, SETTINGS, POST, CHATS, CHAT, GUIDE, MUSIC }

    private class Hit(val x0: Int, val y0: Int, val x1: Int, val y1: Int, val action: () -> Unit) {
        fun contains(x: Double, y: Double) = x >= x0 && x < x1 && y >= y0 && y < y1
    }

    /** Colors for the time of day. */
    private class Theme(
        val night: Boolean, val bg: Int, val text: Int, val sub: Int, val tile: Int, val tileEdge: Int,
        val button: Int, val bar: Int,
    )

    private val nightTheme = Theme(true, 0xFF1A1433.toInt(), 0xFFF3EEFF.toInt(), 0xFFC9BEEA.toInt(), 0x12FFFFFF, 0x1FFFFFFF, 0x24FFFFFF, 0xFFFFFFFF.toInt())
    private val dayTheme = Theme(false, 0xFFBFE3FF.toInt(), 0xFF22305A.toInt(), 0xFF3F5384.toInt(), 0x9EFFFFFF.toInt(), 0xFFFFFFFF.toInt(), 0x2922305A, 0xFF22305A.toInt())

    private val hits = mutableListOf<Hit>()
    private var viewing: Gallery.Entry? = null
    private var confirmDelete = false
    private var scroll = 0.0
    private var maxScroll = 0.0
    private var toast = ""
    private var toastUntil = 0L

    private var pw = 0; private var ph = 0; private var px = 0; private var py = 0
    private var sx = 0; private var sy = 0; private var sw = 0; private var sh = 0

    /** The phone is laid out at one fixed size and scaled to fit the screen. */
    private var k = 1f
    private var ox = 0f; private var oy = 0f

    override fun init() {
        pw = 192; ph = 352
        px = 0; py = 0
        val bezel = 5
        sx = px + bezel; sy = py + bezel; sw = pw - bezel * 2; sh = ph - bezel * 2
        k = minOf((height - 10) / ph.toFloat(), (width - 10) / pw.toFloat(), 1.25f)
        ox = (width - pw * k) / 2f; oy = (height - ph * k) / 2f
        Gallery.refresh()
        SocialClient.send("feed"); SocialClient.send("friends"); SocialClient.send("profile"); SocialClient.send("chats")
        SocialClient.onNotice = { say(it) }
        chatBox = net.minecraft.client.gui.components.EditBox(font, 0, 0, 100, 14, Component.literal("Message")).apply {
            setMaxLength(200)
            visible = false
        }
        addWidget(chatBox!!)
        testPhoto?.let { chatPhoto = it; chatPhotoMine = false; testPhoto = null }
        captionBox = net.minecraft.client.gui.components.EditBox(font, 0, 0, 100, 14, Component.literal("Caption")).apply {
            setMaxLength(80)
            setHint(Component.literal("Say something...").withStyle(ChatFormatting.GRAY))
            visible = false
        }
        addWidget(captionBox!!)
    }

    override fun removed() { SocialClient.onNotice = null; SocialClient.closeChat(); VoltyChat.open = false; super.removed() }

    /** The box at the bottom of a post (comments) or a chat (messages). */
    private var chatBox: net.minecraft.client.gui.components.EditBox? = null
    /** Where an open post was opened from (Back returns there). */
    private var postFrom = Tab.FEED
    /** Picking a gallery photo to send in the open chat. */
    private var pickForChat = false
    /** A photo from a chat, shown big (and whether you sent it). */
    private var chatPhoto: String? = null
    private var chatPhotoMine = false
    private var seenChatVersion = -1
    private fun typing() = page == Tab.POST || page == Tab.MUSIC || (page == Tab.CHAT && chatPhoto == null)

    // Posting a photo: a caption box over the photo viewer.
    private var captionBox: net.minecraft.client.gui.components.EditBox? = null
    private var captioning = false
    /** Picking a gallery photo for your profile picture. */
    private var pickAvatar = false

    private fun vx(x: Double) = (x - ox) / k
    private fun vy(y: Double) = (y - oy) / k

    override fun isPauseScreen() = false

    // ---- state from the world ----------------------------------------------------------------------

    private fun theme(): Theme {
        val t = (Minecraft.getInstance().level?.dayTime ?: 6000L) % 24000L
        return if (t in 12800L..23199L) nightTheme else dayTheme
    }

    private fun greeting(): String {
        val t = (Minecraft.getInstance().level?.dayTime ?: 6000L) % 24000L
        val name = Minecraft.getInstance().player?.gameProfile?.name ?: "Trainer"
        val hello = when (t) { in 12800L..23199L -> "Night"; in 0L..5999L, in 23200L..23999L -> "Morning"; else -> "Hey" }
        return "$hello, $name!"
    }

    private fun buddy() = Minecraft.getInstance().player?.let { CompanionClient.mine(it) }

    private fun currentLook(): Look =
        buddy()?.look ?: dev.dexbuddy.client.context.GameContext.heldDexBuddy()?.let { DexBuddyEntity.lookOf(it) } ?: Look.DEFAULT

    private fun costumeBits(): Int =
        buddy()?.costumeBits ?: dev.dexbuddy.client.context.GameContext.heldDexBuddy()?.let { DexBuddyEntity.costumesOf(it) } ?: 0

    private fun face(): FaceSpec = LookModels.get(currentLook()).spec.all().flatMap { it.boxes }.firstNotNullOfOrNull { it.face }
        ?: FaceSpec(0xFF2A2F45.toInt(), 0xFFFFE066.toInt())

    private fun say(msg: String) { toast = msg; toastUntil = System.currentTimeMillis() + 2_500 }

    // ---- drawing helpers ---------------------------------------------------------------------------

    /** A filled rectangle with round corners (drawn row by row so see-through colors stay even). */
    private fun rfill(g: GuiGraphics, x: Int, y: Int, w: Int, h: Int, radius: Int, c: Int) {
        if (w <= 0 || h <= 0) return
        val r = minOf(radius, w / 2, h / 2)
        if (r <= 0) { g.fill(x, y, x + w, y + h, c); return }
        g.fill(x, y + r, x + w, y + h - r, c)
        for (i in 0 until r) {
            val dy = r - i - 0.5
            val inset = (r - sqrt(maxOf(0.0, r * r - dy * dy))).roundToInt()
            g.fill(x + inset, y + i, x + w - inset, y + i + 1, c)
            g.fill(x + inset, y + h - 1 - i, x + w - inset, y + h - i, c)
        }
    }

    /** A rounded tile with a 1-pixel edge. */
    private fun tile(g: GuiGraphics, x: Int, y: Int, w: Int, h: Int, r: Int, th: Theme, hover: Boolean) {
        rfill(g, x, y, w, h, r, th.tileEdge)
        rfill(g, x + 1, y + 1, w - 2, h - 2, r - 1, if (hover) (th.tile and 0x00FFFFFF) or (minOf(255, (th.tile ushr 24) + 40) shl 24) else th.tile)
    }

    private fun text(g: GuiGraphics, s: Component, cx: Int, y: Int, scale: Float, color: Int, center: Boolean = true) {
        g.pose().pushPose()
        g.pose().translate(cx.toFloat(), y.toFloat(), 0f)
        g.pose().scale(scale, scale, 1f)
        g.drawString(font, s, if (center) -font.width(s) / 2 else 0, 0, color, false)
        g.pose().popPose()
    }

    private fun text(g: GuiGraphics, s: String, cx: Int, y: Int, scale: Float, color: Int, center: Boolean = true) =
        text(g, Component.literal(s), cx, y, scale, color, center)

    /** Pixel art on a 12x12 grid. */
    private class Px(val x: Int, val y: Int, val w: Int, val h: Int, val c: Int)

    private fun px(x: Int, y: Int, w: Int, h: Int, c: Long) = Px(x, y, w, h, c.toInt())

    private fun sprite(g: GuiGraphics, art: List<Px>, x: Int, y: Int, cell: Int) =
        art.forEach { p -> g.fill(x + p.x * cell, y + p.y * cell, x + (p.x + p.w) * cell, y + (p.y + p.h) * cell, p.c) }

    private fun icon(name: String, th: Theme): List<Px> {
        val n = th.night
        return when (name) {
            "gallery" -> listOf(px(1, 2, 10, 8, if (n) 0xFFFFFFFF else 0xFF22305A), px(2, 3, 8, 4, 0xFF8EC9FF), px(2, 7, 8, 2, if (n) 0xFF7FD8BE else 0xFF5FC6A6),
                px(7, 3, 2, 2, if (n) 0xFFFFE27A else 0xFFFFC93C), px(3, 6, 2, 1, if (n) 0xFF7FD8BE else 0xFF5FC6A6))
            "wardrobe" -> { val c = if (n) 0xFFB9A2FF else 0xFF9A7FF0
                listOf(px(3, 4, 6, 6, c), px(1, 3, 3, 3, c), px(8, 3, 3, 3, c), px(4, 3, 4, 1, c), px(5, 3, 2, 1, if (n) 0xFF1A1433 else 0xFFE6F3FF), px(5, 6, 2, 2, 0xFFFFC93C)) }
            "feed" -> { val c = if (n) 0xFFFF8FB8 else 0xFFF2669A
                listOf(px(2, 3, 3, 1, c), px(7, 3, 3, 1, c), px(1, 4, 10, 2, c), px(2, 6, 8, 1, c), px(3, 7, 6, 1, c), px(4, 8, 4, 1, c), px(5, 9, 2, 1, c), px(2, 4, 1, 1, 0xFFFFFFFF)) }
            "friends" -> listOf(px(2, 2, 3, 3, if (n) 0xFFFFD9B8 else 0xFFE8B48F), px(1, 6, 5, 4, if (n) 0xFF7FD8BE else 0xFF5FC6A6),
                px(7, 3, 3, 3, if (n) 0xFFFFD9B8 else 0xFFE8B48F), px(6, 7, 5, 3, if (n) 0xFF8EC9FF else 0xFF6FAEF0))
            "quiz" -> { val d = if (n) 0xFF1A1433 else 0xFF22305A
                listOf(px(1, 1, 10, 10, if (n) 0xFFFFE27A else 0xFFE0A800), px(2, 2, 8, 8, 0xFFFFC93C), px(4, 3, 4, 1, d), px(7, 4, 1, 2, d), px(6, 6, 1, 1, d), px(5, 7, 1, 1, d), px(5, 9, 1, 1, d)) }
            "profile" -> listOf(px(4, 1, 4, 4, 0xFFFFD9B8), px(4, 1, 4, 1, 0xFF8B5A2B), px(2, 6, 8, 5, if (n) 0xFFB9A2FF else 0xFF9A7FF0))
            "messages" -> { val c = if (n) 0xFF7FD8BE else 0xFF3FB58E; val d = if (n) 0xFF1A1433 else 0xFF1F5E4A
                // A round speech bubble with a little tail and three "typing" dots.
                listOf(px(3, 1, 6, 1, c), px(2, 2, 8, 1, c), px(1, 3, 10, 4, c), px(2, 7, 8, 1, c), px(3, 8, 6, 1, c),
                    px(2, 8, 2, 1, c), px(1, 9, 2, 1, c), px(1, 10, 1, 1, c),
                    px(3, 2, 2, 1, 0x80FFFFFF),
                    px(2, 4, 2, 2, d), px(5, 4, 2, 2, d), px(8, 4, 2, 2, d)) }
            "gear" -> { val c = if (n) 0xFFFFFFFF else 0xFF22305A
                listOf(px(4, 0, 4, 12, c), px(0, 4, 12, 4, c), px(1, 1, 3, 3, c), px(8, 1, 3, 3, c), px(1, 8, 3, 3, c), px(8, 8, 3, 3, c),
                    px(2, 2, 8, 8, c), px(4, 4, 4, 4, if (n) 0xFF2A2350 else 0xFFA9CFEF)) }
            "flashlight" -> { val c = if (n) 0xFFFFFFFF else 0xFF22305A
                listOf(px(3, 1, 6, 3, c), px(4, 4, 4, 1, c), px(5, 5, 2, 6, c), px(4, 2, 4, 1, 0xFFFFE27A)) }
            else -> { val c = if (n) 0xFFFFFFFF else 0xFF22305A // camera
                listOf(px(1, 4, 10, 6, c), px(4, 3, 4, 1, c), Px(4, 5, 4, 4, th.bg), px(5, 6, 2, 2, c), px(9, 5, 1, 1, 0xFFFFC93C)) }
        }
    }

    // ---- render -------------------------------------------------------------------------------------

    override fun render(g: GuiGraphics, rawX: Int, rawY: Int, partialTick: Float) {
        super.render(g, rawX, rawY, partialTick)
        hits.clear()
        val mouseX = vx(rawX.toDouble()).toInt(); val mouseY = vy(rawY.toDouble()).toInt()
        g.pose().pushPose()
        g.pose().translate(ox, oy, 0f)
        g.pose().scale(k, k, 1f)
        drawPhone(g, mouseX, mouseY)
        g.pose().popPose()
        // The caption box lives in screen coordinates, over its spot on the phone.
        captionBox?.let { box ->
            box.visible = captioning && page == Tab.GALLERY && viewing != null
            if (box.visible) {
                val bx = sx + 6; val by = cTop() + (sw - 12) * 9 / 16 + 8
                box.x = (ox + bx * k).toInt(); box.y = (oy + by * k).toInt()
                box.width = ((sw - 12) * k).toInt(); box.height = maxOf(12, (14 * k).toInt())
                box.render(g, rawX, rawY, partialTick)
            } else box.isFocused = false
        }
        chatBox?.let { box ->
            box.visible = typing()
            if (box.visible) {
                box.setMaxLength(if (page == Tab.POST) 120 else if (page == Tab.MUSIC) 400 else 200)
                box.setHint(Component.literal(when (page) { Tab.POST -> "Add a comment..."; Tab.MUSIC -> "Song or YouTube link..."; else -> "Message..." }).withStyle(ChatFormatting.GRAY))
                val bx = sx + 6; val by = cBottom() - 17
                box.x = (ox + bx * k).toInt(); box.y = (oy + by * k).toInt()
                box.width = ((sw - 12 - sendBarRight()) * k).toInt(); box.height = maxOf(12, (14 * k).toInt())
                box.render(g, rawX, rawY, partialTick)
            } else box.isFocused = false
        }
    }

    /** Room the Send (and Photo) buttons take at the right of the input bar. */
    private fun sendBarRight() = if ((page == Tab.CHAT && !voltyOpen) || page == Tab.MUSIC) 62 else 32

    private fun submitBox(queueIt: Boolean = false) {
        val box = chatBox ?: return
        val t = box.value.trim()
        if (t.isEmpty()) return
        when (page) {
            Tab.MUSIC -> MusicClient.play(t, queueIt)
            Tab.POST -> SocialClient.openPostId?.let { SocialClient.send("comment:$it:$t") }
            Tab.CHAT -> if (voltyOpen) VoltyChat.send(t) else SocialClient.chatId?.let { SocialClient.send("msg:$it:$t") }
            else -> return
        }
        box.value = ""
    }

    /** The input bar under a post or chat: the box is drawn by render(), the buttons here. */
    private fun drawSendBar(g: GuiGraphics, th: Theme, mx: Int, my: Int) {
        val by = cBottom() - 19
        g.fill(sx, by - 2, sx + sw, cBottom(), th.bg)
        val sw2 = 28
        val sxB = sx + sw - 6 - sw2
        rfill(g, sxB, by + 1, sw2, 15, 7, if (mx in sxB until sxB + sw2 && my in by until by + 17) 0xFF2E9E7A.toInt() else 0xD02E9E7A.toInt())
        text(g, if (page == Tab.MUSIC) "Play" else "Send", sxB + sw2 / 2, by + 5, 0.7f, 0xFFFFFFFF.toInt())
        hits += Hit(sxB, by, sxB + sw2, by + 17) { submitBox() }
        if (page == Tab.MUSIC) {
            val qx = sxB - 4 - 26
            rfill(g, qx, by + 1, 26, 15, 7, if (mx in qx until qx + 26 && my in by until by + 17) 0xFF7B5CD6.toInt() else 0xD07B5CD6.toInt())
            text(g, "Queue", qx + 13, by + 5, 0.6f, 0xFFFFFFFF.toInt())
            hits += Hit(qx, by, qx + 26, by + 17) { submitBox(queueIt = true) }
        }
        if (page == Tab.CHAT && !voltyOpen) {
            val pxB = sxB - 4 - 26
            rfill(g, pxB, by + 1, 26, 15, 7, if (mx in pxB until pxB + 26 && my in by until by + 17) 0xFF7B5CD6.toInt() else 0xD07B5CD6.toInt())
            text(g, "Photo", pxB + 13, by + 5, 0.62f, 0xFFFFFFFF.toInt())
            hits += Hit(pxB, by, pxB + 26, by + 17) { pickForChat = true; page = Tab.GALLERY; viewing = null; scroll = 0.0; Gallery.refresh() }
        }
    }

    private fun wrap(s: String, maxW: Int, scale: Float) = font.split(Component.literal(s), (maxW / scale).toInt())

    /** Draws wrapped lines; returns the height used. */
    private fun lines(g: GuiGraphics, ls: List<net.minecraft.util.FormattedCharSequence>, x: Int, y: Int, scale: Float, color: Int, lh: Int): Int {
        ls.forEachIndexed { i, l ->
            g.pose().pushPose(); g.pose().translate(x.toFloat(), (y + i * lh).toFloat(), 0f); g.pose().scale(scale, scale, 1f)
            g.drawString(font, l, 0, 0, color, false); g.pose().popPose()
        }
        return ls.size * lh
    }

    private fun drawPhone(g: GuiGraphics, mouseX: Int, mouseY: Int) {
        val th = theme()
        // The phone: a black body, the screen in the time-of-day color, and the camera pill.
        rfill(g, px, py, pw, ph, 24, 0xFF0B0915.toInt())
        rfill(g, sx, sy, sw, sh, 19, th.bg)
        when (page) {
            Tab.HOME -> drawHome(g, th, mouseX, mouseY)
            Tab.POST -> drawSub(g, th, "Post", mouseX, mouseY) { drawPost(g, th, mouseX, mouseY) }
            Tab.CHATS -> drawSub(g, th, "Messages", mouseX, mouseY) { drawChats(g, th, mouseX, mouseY) }
            Tab.CHAT -> drawSub(g, th, if (voltyOpen) CompanionClient.companionName else SocialClient.chatWith?.name ?: "Chat", mouseX, mouseY) { drawChat(g, th, mouseX, mouseY) }
            Tab.GALLERY -> drawSub(g, th, if (pickAvatar) "Pick a picture" else if (pickForChat) "Send a picture" else if (viewing != null) "Photo" else "Gallery", mouseX, mouseY) {
                if (viewing != null) drawViewer(g, th, viewing!!, mouseX, mouseY) else drawGallery(g, th, mouseX, mouseY)
            }
            Tab.WARDROBE -> drawSub(g, th, "Wardrobe", mouseX, mouseY) { drawWardrobe(g, th, mouseX, mouseY) }
            Tab.FEED -> drawSub(g, th, "Feed", mouseX, mouseY) { drawFeed(g, th, mouseX, mouseY) }
            Tab.FRIENDS -> drawSub(g, th, "Friends", mouseX, mouseY) { drawFriends(g, th, mouseX, mouseY) }
            Tab.PROFILE -> drawSub(g, th, "Profile", mouseX, mouseY) { drawProfile(g, th, mouseX, mouseY) }
            Tab.SETTINGS -> drawSub(g, th, "Settings", mouseX, mouseY) { drawSettings(g, th, mouseX, mouseY) }
            Tab.GUIDE -> drawSub(g, th, "Quick guide", mouseX, mouseY) { drawGuide(g, th, mouseX, mouseY) }
            Tab.MUSIC -> drawSub(g, th, "Music", mouseX, mouseY) { drawMusic(g, th, mouseX, mouseY) }
        }
        rfill(g, sx + sw / 2 - 20, sy + 5, 40, 11, 5, 0xFF000000.toInt())
        rfill(g, sx + sw / 2 - 26, sy + sh - 6, 52, 2, 1, th.bar)
        if (System.currentTimeMillis() < toastUntil) {
            val w = (font.width(toast) * 0.8f).toInt() + 12
            val tx = sx + (sw - w) / 2; val ty = sy + sh - 52
            rfill(g, tx, ty, w, 14, 7, 0xE0101020.toInt())
            text(g, toast, sx + sw / 2, ty + 4, 0.8f, 0xFFFFFFFF.toInt())
        }
    }

    private fun drawSky(g: GuiGraphics, th: Theme, t: Double) {
        if (th.night) {
            // Twinkling stars and a couple of sparkles.
            // Only in open sky: beside the face, and in the space above the bottom buttons.
            val stars = listOf(0.1 to 0.15, 0.82 to 0.16, 0.9 to 0.24, 0.08 to 0.27, 0.2 to 0.78, 0.62 to 0.84, 0.82 to 0.75)
            stars.forEachIndexed { i, (fx, fy) ->
                val a = (0.25 + 0.75 * (0.5 + 0.5 * sin(t * 2.2 + i * 1.7))).coerceIn(0.0, 1.0)
                val col = listOf(0xFFFFFF, 0xFFE27A, 0xB9A2FF, 0x8EC9FF)[i % 4]
                val x = sx + (sw * fx).toInt(); val y = sy + (sh * fy).toInt()
                val c = ((a * 255).toInt() shl 24) or col
                if (i % 3 == 0) { g.fill(x - 2, y, x + 3, y + 1, c); g.fill(x, y - 2, x + 1, y + 3, c) } else g.fill(x, y, x + 2, y + 2, c)
            }
        } else {
            // A glowing sun and two drifting clouds.
            val cx = sx + sw - 26; val cy = sy + 66
            val pulse = 0.5 + 0.5 * sin(t * 1.6)
            rfill(g, cx - 16, cy - 16, 32, 32, 16, (((0.18 + 0.12 * pulse) * 255).toInt() shl 24) or 0xFFE27A)
            rfill(g, cx - 11, cy - 11, 22, 22, 11, 0xFFFFE27A.toInt())
            for ((i, base) in listOf(sx + 10 to sy + 58, sx + sw - 46 to sy + 150).withIndex()) {
                val x = base.first + (sin(t * 0.5 + i * 2) * 6).toInt(); val y = base.second
                rfill(g, x, y, 32, 10, 5, 0xF0FFFFFF.toInt())
                rfill(g, x + 8, y - 6, 15, 12, 6, 0xF0FFFFFF.toInt())
            }
        }
    }

    private fun drawHome(g: GuiGraphics, th: Theme, mx: Int, my: Int) {
        val t = System.currentTimeMillis() / 1000.0
        drawSky(g, th, t)
        val cx = sx + sw / 2
        // Your companion's face: floats, blinks, and glows at night.
        val f = face()
        val fw = 78; val fh = 60
        val fx = cx - fw / 2
        val fy0 = sy + 28
        val fy = fy0 + (sin(t * 1.95) * 2.5).roundToInt()
        if (th.night) {
            val pulse = 0.5 + 0.5 * sin(t * 1.95)
            for (k in 3 downTo 1) rfill(g, fx - k * 3, fy - k * 3, fw + k * 6, fh + k * 6, 14 + k * 3,
                ((((0.07 + 0.05 * pulse) / k) * 255).toInt() shl 24) or (f.eye and 0xFFFFFF))
        } else {
            rfill(g, fx - 2, fy + 3, fw + 4, fh + 2, 16, 0x2622305A)
            rfill(g, fx - 3, fy - 3, fw + 6, fh + 6, 17, 0xFFFFFFFF.toInt())
        }
        rfill(g, fx, fy, fw, fh, 14, f.bg)
        val cell = 4
        val ox = fx + (fw - 16 * cell) / 2; val oy = fy + (fh - 12 * cell) / 2
        fun p(x: Int, y: Int, w: Int, h: Int, c: Int) = g.fill(ox + x * cell, oy + y * cell, ox + (x + w) * cell, oy + (y + h) * cell, c)
        val blink = (t % 4.5) > 4.32
        for (ex in intArrayOf(4, 10)) if (blink) p(ex, 5, 2, 1, f.eye) else { p(ex, 3, 2, 3, f.eye); p(ex, 3, 1, 1, f.shine) }
        p(6, 8, 1, 1, f.mouth); p(7, 9, 2, 1, f.mouth); p(9, 8, 1, 1, f.mouth)
        p(1, 7, 2, 1, f.cheek); p(13, 7, 2, 1, f.cheek)

        // Greeting.
        val gy = fy0 + fh + 10
        text(g, Component.literal(greeting()).withStyle(ChatFormatting.BOLD), cx, gy, 1f, th.text)
        text(g, if (th.night) "Perfect hour for a starry photo" else "Great light for a photo today", cx, gy + 13, 0.75f, th.sub)

        // Apps.
        val margin = 9; val gap = 5; val cols = 3
        val tw = (sw - margin * 2 - gap * (cols - 1)) / cols; val tH = tw + 6
        val top = gy + 28
        val apps = listOf("gallery" to "Gallery", "wardrobe" to "Wardrobe", "feed" to "Feed", "friends" to "Friends", "quiz" to "Quiz", "profile" to "Profile")
        apps.forEachIndexed { i, (id, label0) ->
            val x = sx + margin + (i % cols) * (tw + gap); val y = top + (i / cols) * (tH + gap)
            val hover = mx >= x && mx < x + tw && my >= y && my < y + tH
            // While a quiz is running, the Quiz tile turns red and stops it.
            val stopQuiz = id == "quiz" && Commands.quizOpen
            val label = if (stopQuiz) "Stop quiz" else label0
            if (stopQuiz) { rfill(g, x, y, tw, tH, 9, if (hover) 0xFFE8335F.toInt() else 0xD0C2185B.toInt()) } else tile(g, x, y, tw, tH, 9, th, hover)
            val icx = x + tw / 2 - 12; val icy = y + 7
            val photo = if (id == "profile") (SocialClient.me?.avatar ?: Gallery.entries.firstOrNull()?.id)?.let { Gallery.texture(it) } else null
            if (photo != null) {
                rfill(g, icx - 1, icy - 1, 26, 26, 6, if (th.night) 0xFFFFE27A.toInt() else 0xFFFFC93C.toInt())
                g.blit(photo, icx + 1, icy + 1, 22, 22, 140f, 0f, 360, 360, 640, 360)
            } else sprite(g, icon(id, th), icx, icy, 2)
            text(g, label, x + tw / 2, y + tH - 10, 0.7f, if (stopQuiz) 0xFFFFFFFF.toInt() else th.text)
            val badge = when (id) { "feed" -> SocialClient.newPosts; "friends" -> SocialClient.incoming.size; else -> 0 }
            if (badge > 0) {
                rfill(g, x + tw - 13, y - 3, 16, 12, 6, 0xFFE8335F.toInt())
                text(g, if (badge > 9) "9+" else "$badge", x + tw - 5, y, 0.7f, 0xFFFFFFFF.toInt())
            }
            hits += Hit(x, y, x + tw, y + tH) { if (stopQuiz) { Commands.stopQuiz(); say("Quiz stopped") } else openApp(id) }
        }

        // Flashlight (your companion's glow) and camera, like a lock screen.
        val by = sy + sh - 40
        val glowing = flashlightOn
        circleButton(g, th, sx + 14, by, mx, my, glowing) { toggleGlow() }
        sprite(g, icon("flashlight", th).let { if (glowing) it.map { p -> Px(p.x, p.y, p.w, p.h, if (p.c == 0xFFFFE27A.toInt()) p.c else 0xFF1A1433.toInt()) } else it }, sx + 14 + 7, by + 7, 1)
        circleButton(g, th, sx + sw - 14 - 26, by, mx, my, false) { onClose(); dev.dexbuddy.client.photo.PhotoClient.openViewfinder() }
        sprite(g, icon("camera", th), sx + sw - 14 - 26 + 7, by + 7, 1)

        // Music: a "now playing" pill under the apps (tap for the Music page).
        run {
            val mx0 = sx + margin; val mw = sw - margin * 2; val my0 = top + 2 * (tH + gap) + 2; val mh = 22
            val hover = mx in mx0 until mx0 + mw && my in my0 until my0 + mh
            val song = MusicClient.current
            val playing = MusicClient.isPlaying
            if (song != null) rfill(g, mx0, my0, mw, mh, 11, if (hover) 0xFF8A6BE6.toInt() else 0xE07B5CD6.toInt()) else tile(g, mx0, my0, mw, mh, 11, th, hover)
            val ink = if (song != null) 0xFFFFFFFF.toInt() else th.text
            // A note, with bouncing bars while it plays.
            rfill(g, mx0 + 4, my0 + 3, 16, 16, 8, if (song != null) 0x40FFFFFF else th.button)
            g.fill(mx0 + 13, my0 + 6, mx0 + 14, my0 + 14, ink); g.fill(mx0 + 13, my0 + 6, mx0 + 16, my0 + 7, ink); g.fill(mx0 + 10, my0 + 13, mx0 + 14, my0 + 16, ink)
            val label = song?.title ?: "Music: play a song"
            text(g, font.plainSubstrByWidth(label, ((mw - 54) / 0.68f).toInt()), mx0 + 24, my0 + 8, 0.68f, ink, center = false)
            if (song != null) {
                for (b in 0 until 3) {
                    val h = if (playing) (3 + (4 * (0.5 + 0.5 * sin(t * 7 + b * 1.9))).roundToInt()) else 2
                    g.fill(mx0 + mw - 24 + b * 4, my0 + 15 - h, mx0 + mw - 22 + b * 4, my0 + 15, ink)
                }
                // Play/pause on the right end.
                val bx = mx0 + mw - 12
                if (playing) { g.fill(bx, my0 + 7, bx + 2, my0 + 15, ink); g.fill(bx + 4, my0 + 7, bx + 6, my0 + 15, ink) }
                else for (i in 0 until 4) g.fill(bx + i, my0 + 7 + i, bx + i + 1, my0 + 15 - i, ink)
            }
            hits += Hit(mx0, my0, mx0 + mw, my0 + mh) { openApp("music") }
            if (song != null) hits += Hit(mx0 + mw - 16, my0, mx0 + mw, my0 + mh) { MusicClient.togglePause() }
        }

        // Small corner buttons: settings (gear) top-left, messages (chat bubble) top-right.
        cornerButton(g, th, "gear", sx + 10, sy + 18, mx, my, 0) { openApp("gear") }
        cornerButton(g, th, "messages", sx + sw - 34, sy + 18, mx, my, SocialClient.unreadTotal + VoltyChat.unread) { openApp("messages") }
    }

    private fun cornerButton(g: GuiGraphics, th: Theme, icon: String, x: Int, y: Int, mx: Int, my: Int, badge: Int, action: () -> Unit) {
        val s = 24
        val hover = mx >= x && mx < x + s && my >= y && my < y + s
        rfill(g, x, y, s, s, s / 2, if (hover) (th.button or 0x20000000) else th.button)
        // The 12x12 icon drawn 1.5x so its details (dots, teeth) stay readable.
        g.pose().pushPose(); g.pose().translate(x + 3f, y + 3f, 0f); g.pose().scale(1.5f, 1.5f, 1f)
        sprite(g, icon(icon, th), 0, 0, 1)
        g.pose().popPose()
        if (badge > 0) {
            rfill(g, x + 14, y - 3, 12, 10, 5, 0xFFE8335F.toInt())
            text(g, if (badge > 9) "9+" else "$badge", x + 20, y - 1, 0.6f, 0xFFFFFFFF.toInt())
        }
        hits += Hit(x, y, x + s, y + s, action)
    }

    /** One row with an on/off switch. */
    private fun switchRow(g: GuiGraphics, th: Theme, label: String, on: Boolean, x: Int, y: Int, w: Int, mx: Int, my: Int, action: () -> Unit) {
        if (y + 20 <= cTop() || y >= cBottom()) return
        val hover = mx >= x && mx < x + w && my >= y && my < y + 20 && my in cTop() until cBottom()
        tile(g, x, y, w, 20, 6, th, hover)
        text(g, font.plainSubstrByWidth(label, ((w - 36) / 0.72f).toInt()), x + 6, y + 7, 0.72f, th.text, center = false)
        val kx = x + w - 26; val ky = y + 5
        rfill(g, kx, ky, 20, 10, 5, if (on) 0xFF2E9E7A.toInt() else 0xFF6B6B80.toInt())
        rfill(g, if (on) kx + 11 else kx + 1, ky + 1, 8, 8, 4, 0xFFFFFFFF.toInt())
        hits += Hit(x, maxOf(y, cTop()), x + w, minOf(y + 20, cBottom())) { action(); Minecraft.getInstance().soundManager.play(
            net.minecraft.client.resources.sounds.SimpleSoundInstance.forUI(net.minecraft.sounds.SoundEvents.UI_BUTTON_CLICK.value(), if (on) 1.2f else 1.5f, 0.4f)) }
    }

    /** A row with a value and - / + buttons (the value text is clipped to fit). */
    private fun stepRow(g: GuiGraphics, th: Theme, label: String, value: String, x: Int, y: Int, w: Int, mx: Int, my: Int, minus: () -> Unit, plus: () -> Unit) {
        if (y + 20 <= cTop() || y >= cBottom()) return
        tile(g, x, y, w, 20, 6, th, false)
        text(g, label, x + 6, y + 7, 0.72f, th.text, center = false)
        val labelW = (font.width(label) * 0.72f).toInt() + 12
        val bw = 14
        val valueW = (w - labelW - bw * 2 - 12).coerceAtLeast(24)
        val px = x + w - 4 - bw
        val valueX0 = px - 2 - valueW
        val mxB = valueX0 - 2 - bw
        text(g, font.plainSubstrByWidth(value, (valueW / 0.65f).toInt()), valueX0 + valueW / 2, y + 7, 0.65f, th.sub)
        for ((bx, sign, act) in listOf(Triple(mxB, "-", minus), Triple(px, "+", plus))) {
            val hov = mx >= bx && mx < bx + bw && my >= y + 3 && my < y + 17 && my in cTop() until cBottom()
            rfill(g, bx, y + 3, bw, 14, 7, if (hov) (th.button or 0x30000000) else th.button)
            text(g, sign, bx + bw / 2, y + 6, 0.8f, th.text)
            hits += Hit(bx, maxOf(y + 3, cTop()), bx + bw, minOf(y + 17, cBottom())) {
                act(); Minecraft.getInstance().soundManager.play(net.minecraft.client.resources.sounds.SimpleSoundInstance.forUI(net.minecraft.sounds.SoundEvents.UI_BUTTON_CLICK.value(), 1.4f, 0.4f))
            }
        }
    }

    /** A short line at the new volume (if the voice is on and ready). */
    private fun previewVoice() {
        val tts = dev.dexbuddy.client.tts.Tts
        if (!tts.enabled || !tts.isReady()) return
        tts.beginReply(System.nanoTime(), null)
        tts.say("Bzzt! Like this?") {}
    }

    /** "" (automatic) plus every mic Java can record from; read once per phone opening. */
    private var micList: List<String>? = null
    private fun micChoices(): List<String> = micList ?: (listOf("") + try { dev.dexbuddy.client.voice.MicRecorder.inputNames() } catch (_: Exception) { emptyList() })
        .distinct().also { micList = it }

    private fun pickMic(mics: List<String>, dir: Int) {
        val s = dev.dexbuddy.client.BuddyConfig.settings
        val i = mics.indexOfFirst { it.equals(s.voice.micDevice.trim(), true) }.let { if (it < 0) 0 else it }
        s.voice.micDevice = mics[Math.floorMod(i + dir, mics.size)]
        dev.dexbuddy.client.BuddyConfig.save()
        dev.dexbuddy.client.voice.WakeWord.reopenMic()
        say(if (s.voice.micDevice.isEmpty()) "Mic: automatic" else "Mic set")
    }

    // ---- Quick guide -------------------------------------------------------------------------------

    /** Where each section starts in the guide (for the table of contents), from the last frame. */
    private val guideSections = LinkedHashMap<String, Int>()

    /** Small pictures for the guide (12x12 pixel art); the app icons are reused where they fit. */
    private fun guideIcon(name: String, th: Theme): List<Px> {
        val n = th.night
        val ink = if (n) 0xFFFFFFFF else 0xFF22305A
        val dark = if (n) 0xFF1A1433 else 0xFFE6F3FF
        return when (name) {
            "mic" -> listOf(px(4, 1, 4, 6, 0xFF7FD8BE), px(2, 5, 1, 2, ink), px(9, 5, 1, 2, ink), px(3, 7, 6, 1, ink), px(5, 8, 2, 2, ink), px(3, 10, 6, 1, ink))
            "eye" -> listOf(px(2, 4, 8, 4, 0xFFFFFFFF), px(1, 5, 1, 2, 0xFFFFFFFF), px(10, 5, 1, 2, 0xFFFFFFFF), px(5, 4, 2, 4, 0xFF4A6FA5), px(5, 5, 1, 1, 0xFF111111))
            "chat" -> icon("messages", th)
            "stop" -> listOf(px(3, 1, 6, 10, 0xFFE8335F), px(1, 3, 10, 6, 0xFFE8335F), px(2, 2, 8, 8, 0xFFE8335F), px(3, 5, 6, 2, 0xFFFFFFFF))
            "moon" -> listOf(px(5, 1, 4, 1, 0xFFFFE27A), px(3, 2, 4, 1, 0xFFFFE27A), px(2, 3, 3, 6, 0xFFFFE27A), px(3, 9, 4, 1, 0xFFFFE27A), px(5, 10, 4, 1, 0xFFFFE27A), px(9, 3, 1, 1, ink), px(8, 5, 2, 1, ink))
            "scan" -> listOf(px(1, 1, 3, 1, 0xFF7FE8FF), px(1, 1, 1, 3, 0xFF7FE8FF), px(8, 1, 3, 1, 0xFF7FE8FF), px(10, 1, 1, 3, 0xFF7FE8FF),
                px(1, 10, 3, 1, 0xFF7FE8FF), px(1, 8, 1, 3, 0xFF7FE8FF), px(8, 10, 3, 1, 0xFF7FE8FF), px(10, 8, 1, 3, 0xFF7FE8FF), px(4, 4, 4, 4, 0xFFF7C948))
            "phone" -> listOf(px(3, 1, 6, 10, 0xFFF2603B), px(4, 2, 4, 7, dark), px(5, 4, 1, 1, 0xFFFFE066), px(7, 4, 1, 1, 0xFFFFE066))
            "tripod" -> listOf(px(3, 1, 6, 4, ink), px(5, 2, 2, 2, dark), px(5, 5, 2, 2, ink), px(4, 7, 1, 2, ink), px(7, 7, 1, 2, ink), px(3, 9, 1, 2, ink), px(8, 9, 1, 2, ink), px(5, 7, 2, 4, ink))
            "film" -> listOf(px(1, 2, 10, 8, 0xFF333333), px(2, 3, 1, 1, 0xFFFFFFFF), px(2, 8, 1, 1, 0xFFFFFFFF), px(9, 3, 1, 1, 0xFFFFFFFF), px(9, 8, 1, 1, 0xFFFFFFFF), px(4, 4, 4, 4, 0xFFE8335F))
            "paw" -> listOf(px(4, 6, 4, 4, 0xFFB9A2FF), px(2, 3, 2, 2, 0xFFB9A2FF), px(5, 2, 2, 2, 0xFFB9A2FF), px(8, 3, 2, 2, 0xFFB9A2FF))
            "bulb" -> listOf(px(4, 1, 4, 5, 0xFFFFE27A), px(3, 2, 6, 3, 0xFFFFE27A), px(5, 6, 2, 1, 0xFFFFE27A), px(4, 7, 4, 2, 0xFF9A9A9A), px(5, 9, 2, 1, 0xFF9A9A9A))
            "heart" -> icon("feed", th)
            "music" -> listOf(px(5, 1, 6, 1, 0xFF7B5CD6), px(5, 1, 1, 8, 0xFF7B5CD6), px(10, 1, 1, 7, 0xFF7B5CD6), px(3, 8, 3, 3, 0xFF7B5CD6), px(8, 7, 3, 3, 0xFF7B5CD6), px(5, 3, 6, 1, 0xFF7B5CD6))
            "shirt" -> icon("wardrobe", th)
            "people" -> icon("friends", th)
            "quiz" -> icon("quiz", th)
            "camera" -> icon("camera", th)
            else -> icon("gear", th)
        }
    }

    private fun drawGuide(g: GuiGraphics, th: Theme, mx: Int, my: Int) {
        val tricks = dev.dexbuddy.client.antics.Tricks
        val x = sx + 8; val w = sw - 16
        var y = cTop() - scroll.toInt()
        val bottom = cBottom()
        fun vis(top: Int, h: Int) = top + h > cTop() && top < bottom
        // Progress.
        val found = tricks.discoveredCount; val total = tricks.ALL.size
        if (vis(y, 22)) {
            text(g, "$found of $total tricks discovered", x + 2, y + 2, 0.72f, th.text, center = false)
            rfill(g, x, y + 13, w, 5, 2, th.button)
            rfill(g, x, y + 13, maxOf(4, w * found / total), 5, 2, 0xFF2E9E7A.toInt())
        }
        y += 24
        // Table of contents: tap a section to jump there.
        val groups = tricks.ALL.map { it.group }.distinct()
        var cx = x
        var rowY = y
        for (grp in groups) {
            val bw = (font.width(grp) * 0.66f).toInt() + 12
            if (cx + bw > x + w) { cx = x; rowY += 16 }
            if (vis(rowY, 14)) {
                val hover = mx in cx until cx + bw && my in rowY until rowY + 14 && my in cTop() until bottom
                rfill(g, cx, rowY, bw, 14, 7, if (hover) 0xFF7B5CD6.toInt() else th.button)
                text(g, grp, cx + bw / 2, rowY + 4, 0.66f, if (hover) 0xFFFFFFFF.toInt() else th.text)
                val target = guideSections[grp]
                if (target != null) hits += Hit(cx, maxOf(rowY, cTop()), cx + bw, minOf(rowY + 14, bottom)) { scroll = target.toDouble().coerceIn(0.0, maxScroll) }
            }
            cx += bw + 4
        }
        y = rowY + 22
        // The tricks, by section.
        for (grp in groups) {
            guideSections[grp] = y + scroll.toInt() - cTop()
            if (vis(y, 12)) text(g, Component.literal(grp).withStyle(ChatFormatting.BOLD), x + 2, y + 2, 0.8f, th.text, center = false)
            y += 14
            for (t in tricks.ALL.filter { it.group == grp }) {
                val ls = wrap(t.how, w - 40, 0.6f)
                val h = 26 + ls.size * 7
                if (vis(y, h)) {
                    val got = tricks.isDiscovered(t.id)
                    tile(g, x, y, w, h, 7, th, false)
                    rfill(g, x + 4, y + 4, 28, 28, 7, if (th.night) 0x28FFFFFF else 0x2222305A)
                    sprite(g, guideIcon(t.icon, th), x + 6, y + 6, 2)
                    text(g, Component.literal(t.title).withStyle(ChatFormatting.BOLD), x + 36, y + 4, 0.7f, th.text, center = false)
                    val badge = if (got) "Found" else "New"
                    val bw2 = (font.width(badge) * 0.55f).toInt() + 8
                    rfill(g, x + w - bw2 - 4, y + 4, bw2, 9, 4, if (got) 0xFF2E9E7A.toInt() else 0xFFE8335F.toInt())
                    text(g, badge, x + w - bw2 / 2 - 4, y + 6, 0.55f, 0xFFFFFFFF.toInt())
                    text(g, font.plainSubstrByWidth(t.say, ((w - 44) / 0.62f).toInt()), x + 36, y + 13, 0.62f, if (th.night) 0xFFFFE27A.toInt() else 0xFFB07800.toInt(), center = false)
                    lines(g, ls, x + 36, y + 22, 0.6f, th.sub, 7)
                }
                y += h + 4
            }
            y += 4
        }
        maxScroll = maxOf(0.0, (y + scroll.toInt() - bottom).toDouble())
    }

    private fun drawSettings(g: GuiGraphics, th: Theme, mx: Int, my: Int) {
        val s = dev.dexbuddy.client.BuddyConfig.settings
        val name = CompanionClient.companionName
        val x = sx + 8; val w = sw - 16
        var y = cTop() - scroll.toInt()
        fun header(t: String) { if (y + 10 > cTop() && y < cBottom()) text(g, t, x + 2, y + 2, 0.72f, th.sub, center = false); y += 13 }
        // The Quick guide: every trick it knows, with pictures.
        if (y + 30 > cTop() && y < cBottom()) {
            val hover = mx in x until x + w && my in y until y + 30 && my in cTop() until cBottom()
            rfill(g, x, y, w, 30, 8, if (hover) 0xFF3F7FBF.toInt() else 0xE03F7FBF.toInt())
            rfill(g, x + 6, y + 5, 20, 20, 10, 0xFFFFFFFF.toInt())
            text(g, Component.literal("?").withStyle(ChatFormatting.BOLD), x + 16, y + 11, 1f, 0xFF3F7FBF.toInt())
            text(g, Component.literal("Quick guide").withStyle(ChatFormatting.BOLD), x + 32, y + 6, 0.8f, 0xFFFFFFFF.toInt(), center = false)
            text(g, "${dev.dexbuddy.client.antics.Tricks.discoveredCount} of ${dev.dexbuddy.client.antics.Tricks.ALL.size} tricks discovered", x + 32, y + 18, 0.62f, 0xFFDDEBFF.toInt(), center = false)
            hits += Hit(x, maxOf(y, cTop()), x + w, minOf(y + 30, cBottom())) { page = Tab.GUIDE; scroll = 0.0 }
        }
        y += 36
        header("Voice")
        switchRow(g, th, "Talk out loud", s.tts.enabled, x, y, w, mx, my) {
            s.tts.enabled = !s.tts.enabled; dev.dexbuddy.client.BuddyConfig.save()
            if (!s.tts.enabled) dev.dexbuddy.client.tts.Tts.stop()
        }; y += 23
        switchRow(g, th, "$name speaks up on its own", s.personality.reactions, x, y, w, mx, my) {
            s.personality.reactions = !s.personality.reactions; dev.dexbuddy.client.BuddyConfig.save()
        }; y += 23
        val vol = (s.tts.volume.coerceIn(0.0, 1.0) * 100).roundToInt()
        stepRow(g, th, "Voice volume", "$vol%", x, y, w, mx, my,
            minus = { s.tts.volume = ((vol - 10).coerceAtLeast(0)) / 100.0; dev.dexbuddy.client.BuddyConfig.save(); previewVoice() },
            plus = { s.tts.volume = ((vol + 10).coerceAtMost(100)) / 100.0; dev.dexbuddy.client.BuddyConfig.save(); previewVoice() })
        y += 23
        y += 4
        header("Microphone")
        val mics = micChoices()
        val cur = s.voice.micDevice.trim()
        val curLabel = if (cur.isEmpty()) "Automatic" else cur
        stepRow(g, th, "Mic", curLabel, x, y, w, mx, my,
            minus = { pickMic(mics, -1) }, plus = { pickMic(mics, 1) })
        y += 23
        // Live input level, so you can see the right mic is picked.
        if (y + 8 > cTop() && y < cBottom()) {
            val lvl = maxOf(dev.dexbuddy.client.voice.MicRecorder.level, dev.dexbuddy.client.voice.MicRecorder.idleLevel)
            rfill(g, x + 6, y + 1, w - 12, 5, 2, th.button)
            rfill(g, x + 6, y + 1, ((w - 12) * lvl.coerceIn(0f, 1f)).toInt().coerceAtLeast(0), 5, 2, 0xFF2E9E7A.toInt())
        }
        y += 9
        if (y + 10 > cTop() && y < cBottom()) text(g, "Automatic = voice chat's mic, else the PC default.", sx + sw / 2, y + 1, 0.55f, th.sub)
        y += 12
        y += 4
        header("Speak up when...")
        val master = s.personality.reactions
        for (c in dev.dexbuddy.client.antics.SpeakCategory.entries) {
            switchRow(g, th, c.label, master && c.on, x, y, w, mx, my) {
                if (!master) say("Turn on \"speaks up\" first") else c.toggle()
            }
            y += 23
        }
        if (y + 10 > cTop() && y < cBottom()) text(g, "Answers to your questions always play.", sx + sw / 2, y + 2, 0.6f, th.sub)
        y += 14
        maxScroll = maxOf(0.0, (y + scroll.toInt() - cBottom()).toDouble())
    }

    private fun mmss(ms: Long): String { val s = (ms / 1000).coerceAtLeast(0); return "${s / 60}:${"%02d".format(s % 60)}" }

    private fun drawMusic(g: GuiGraphics, th: Theme, mx: Int, my: Int) {
        val ms = dev.dexbuddy.client.BuddyConfig.settings.music
        val x = sx + 8; val w = sw - 16
        val bottom = listBottom()
        var y = cTop() - scroll.toInt()
        fun vis(h: Int) = y + h > cTop() && y < bottom
        fun button(label: String, bx: Int, bw: Int, color: Int, action: () -> Unit) {
            val hov = mx in bx until bx + bw && my in y until y + 18 && my in cTop() until bottom
            rfill(g, bx, y, bw, 18, 9, if (hov) color else (color and 0x00FFFFFF) or 0xD0000000.toInt())
            text(g, label, bx + bw / 2, y + 5, 0.72f, 0xFFFFFFFF.toInt())
            hits += Hit(bx, maxOf(y, cTop()), bx + bw, minOf(y + 18, bottom), action)
        }
        val me = Minecraft.getInstance().player?.uuid
        val song = MusicClient.current
        val s = me?.let { MusicClient.sessions[it] }
        // Now playing.
        if (vis(52)) {
            tile(g, x, y, w, 52, 9, th, false)
            val st = MusicClient.statusLine
            if (song == null) {
                val err = st != null && !MusicClient.loading
                text(g, if (MusicClient.loading) "Finding it..." else if (err) "Couldn't play it" else "Nothing playing", sx + sw / 2, y + 12, 0.8f, if (err) 0xFFE8335F.toInt() else th.text)
                wrap(if (err) st!! else "Type a song or paste a YouTube link below.", w - 12, 0.58f).take(2).forEachIndexed { i, l ->
                    g.pose().pushPose(); g.pose().translate((sx + sw / 2 - font.width(l) * 0.58f / 2), (y + 26 + i * 8).toFloat(), 0f); g.pose().scale(0.58f, 0.58f, 1f)
                    g.drawString(font, l, 0, 0, th.sub, false); g.pose().popPose()
                }
            } else {
                // Bold letters are a pixel wider each: trim with room to spare so the title stays inside the card.
                val maxW = ((w - 12) / 0.75f).toInt()
                var title = song.title
                while (title.isNotEmpty() && font.width(Component.literal(title).withStyle(ChatFormatting.BOLD)) > maxW) title = title.dropLast(1)
                if (title.length < song.title.length) title = title.dropLast(2).trimEnd() + "..."
                text(g, Component.literal(title).withStyle(ChatFormatting.BOLD), x + 6, y + 6, 0.75f, th.text, center = false)
                text(g, font.plainSubstrByWidth(song.author, ((w - 12) / 0.62f).toInt()), x + 6, y + 17, 0.62f, th.sub, center = false)
                val pos = if (MusicClient.starting) 0L else s?.position() ?: 0L
                val dur = song.durationMs
                rfill(g, x + 6, y + 31, w - 12, 4, 2, th.button)
                if (dur > 0) rfill(g, x + 6, y + 31, ((w - 12) * (pos.toDouble() / dur).coerceIn(0.0, 1.0)).toInt().coerceAtLeast(2), 4, 2, 0xFF7B5CD6.toInt())
                val label = if (MusicClient.starting) (st ?: "Starting...") else mmss(pos)
                text(g, font.plainSubstrByWidth(label, ((w - 40) / 0.58f).toInt()), x + 6, y + 39, 0.58f, if (MusicClient.starting && st?.startsWith("YouTube") == true) 0xFFFFB347.toInt() else th.sub, center = false)
                if (dur > 0 && dur < Long.MAX_VALUE / 2) text(g, mmss(dur), x + w - 6 - (font.width(mmss(dur)) * 0.58f).toInt(), y + 39, 0.58f, th.sub, center = false)
            }
        }
        y += 56
        if (song != null) {
            val bw = (w - 8) / 3
            if (vis(18)) {
                button(if (MusicClient.isPlaying) "Pause" else "Play", x, bw, 0xFF2E9E7A.toInt()) { MusicClient.togglePause() }
                button("Skip", x + bw + 4, bw, 0xFF3F7FBF.toInt()) { MusicClient.skip() }
                button("Stop", x + (bw + 4) * 2, bw, 0xFFD32F2F.toInt()) { MusicClient.stop() }
            }
            y += 24
        }
        val vol = (ms.volume.coerceIn(0.0, 1.0) * 100).roundToInt()
        stepRow(g, th, "Music volume", "$vol%", x, y, w, mx, my,
            minus = { ms.volume = ((vol - 10).coerceAtLeast(0)) / 100.0; dev.dexbuddy.client.BuddyConfig.save() },
            plus = { ms.volume = ((vol + 10).coerceAtMost(100)) / 100.0; dev.dexbuddy.client.BuddyConfig.save() })
        y += 23
        switchRow(g, th, "Nearby players hear it", ms.share, x, y, w, mx, my) {
            ms.share = !ms.share; dev.dexbuddy.client.BuddyConfig.save(); MusicClient.shareChanged()
        }; y += 23
        switchRow(g, th, "Hear other Dexes' music", ms.hearOthers, x, y, w, mx, my) {
            ms.hearOthers = !ms.hearOthers; dev.dexbuddy.client.BuddyConfig.save()
        }; y += 23
        if (vis(10)) text(g, "Loud up close, fades with distance, silent far away.", sx + sw / 2, y + 1, 0.55f, th.sub)
        y += 13
        // YouTube sign-in: YouTube blocks most songs for signed-out players.
        val login = dev.dexbuddy.client.music.YouTubeLogin
        when (login.state) {
            dev.dexbuddy.client.music.YouTubeLogin.State.SIGNED_IN -> {
                if (vis(20)) {
                    tile(g, x, y, w, 20, 6, th, false)
                    text(g, "YouTube: signed in", x + 6, y + 7, 0.68f, 0xFF2E9E7A.toInt(), center = false)
                    val bw = 46; val bx = x + w - bw - 3
                    val hov = mx in bx until bx + bw && my in y + 3 until y + 17 && my in cTop() until bottom
                    rfill(g, bx, y + 3, bw, 14, 7, if (hov) (th.button or 0x30000000) else th.button)
                    text(g, "Sign out", bx + bw / 2, y + 7, 0.6f, th.text)
                    hits += Hit(bx, maxOf(y + 3, cTop()), bx + bw, minOf(y + 17, bottom)) { login.signOut(); say("Signed out of YouTube") }
                }
                y += 24
            }
            dev.dexbuddy.client.music.YouTubeLogin.State.WAITING -> {
                if (vis(66)) {
                    rfill(g, x, y, w, 66, 9, 0xFF2B2B3A.toInt())
                    if (login.userCode.isEmpty()) text(g, "Getting a sign-in code...", sx + sw / 2, y + 28, 0.7f, 0xFFFFFFFF.toInt())
                    else {
                        text(g, "On your phone or PC, go to", sx + sw / 2, y + 5, 0.6f, 0xFFC9BEEA.toInt())
                        text(g, login.verifyUrl.removePrefix("https://").removePrefix("www."), sx + sw / 2, y + 14, 0.68f, 0xFF8EC9FF.toInt())
                        text(g, "and enter this code:", sx + sw / 2, y + 23, 0.6f, 0xFFC9BEEA.toInt())
                        text(g, Component.literal(login.userCode).withStyle(ChatFormatting.BOLD), sx + sw / 2, y + 33, 1.1f, 0xFFFFE27A.toInt())
                        // Tap the code to copy it.
                        hits += Hit(x, maxOf(y + 30, cTop()), x + w, minOf(y + 45, bottom)) {
                            Minecraft.getInstance().keyboardHandler.clipboard = login.userCode; say("Code copied!")
                        }
                    }
                }
                val bw = (w - 16) / 2; val by = y + 47
                if (vis(66) && login.userCode.isNotEmpty()) {
                    for ((i, pair) in listOf("Open page" to 0xFF3F7FBF.toInt(), "Cancel" to 0xFF6B6B80.toInt()).withIndex()) {
                        val bx = x + 5 + i * (bw + 6)
                        val hov = mx in bx until bx + bw && my in by until by + 15 && my in cTop() until bottom
                        rfill(g, bx, by, bw, 15, 7, if (hov) pair.second else (pair.second and 0x00FFFFFF) or 0xD0000000.toInt())
                        text(g, pair.first, bx + bw / 2, by + 4, 0.65f, 0xFFFFFFFF.toInt())
                        hits += Hit(bx, maxOf(by, cTop()), bx + bw, minOf(by + 15, bottom)) {
                            if (i == 0) {
                                Minecraft.getInstance().keyboardHandler.clipboard = login.userCode
                                net.minecraft.Util.getPlatform().openUri(login.verifyUrl); say("Code copied: paste it on the page")
                            } else login.cancel()
                        }
                    }
                }
                y += 70
            }
            else -> {
                if (vis(34)) {
                    val failed = login.state == dev.dexbuddy.client.music.YouTubeLogin.State.FAILED
                    val hov = mx in x until x + w && my in y until y + 20 && my in cTop() until bottom
                    rfill(g, x, y, w, 20, 10, if (hov) 0xFFE8335F.toInt() else 0xD0C4302B.toInt())
                    text(g, Component.literal(if (failed) "Try signing in again" else "Sign in to YouTube").withStyle(ChatFormatting.BOLD), sx + sw / 2, y + 6, 0.72f, 0xFFFFFFFF.toInt())
                    hits += Hit(x, maxOf(y, cTop()), x + w, minOf(y + 20, bottom)) { login.begin() }
                    text(g, if (failed) login.error else "Most songs need it. A spare Google account is safest.", sx + sw / 2, y + 24, 0.55f, if (failed) 0xFFE8335F.toInt() else th.sub)
                }
                y += 36
            }
        }
        // Up next.
        if (MusicClient.queue.isNotEmpty()) {
            if (vis(10)) text(g, "Up next (${MusicClient.queue.size})", x + 2, y + 2, 0.72f, th.sub, center = false)
            y += 13
            for ((i, q) in MusicClient.queue.withIndex().take(30)) {
                if (vis(18)) {
                    tile(g, x, y, w, 18, 6, th, false)
                    text(g, font.plainSubstrByWidth("${i + 1}. ${q.title}", ((w - 26) / 0.62f).toInt()), x + 6, y + 6, 0.62f, th.text, center = false)
                    val rx = x + w - 16
                    val hov = mx in rx until rx + 12 && my in y until y + 18 && my in cTop() until bottom
                    text(g, "x", rx + 6, y + 5, 0.75f, if (hov) 0xFFE8335F.toInt() else th.sub)
                    hits += Hit(rx, maxOf(y, cTop()), rx + 12, minOf(y + 18, bottom)) { if (i < MusicClient.queue.size) MusicClient.queue.removeAt(i) }
                }
                y += 21
            }
        }
        // Other Dexes playing nearby.
        val others = MusicClient.sessions.values.filter { it.owner != me }
        if (others.isNotEmpty()) {
            if (vis(10)) text(g, "Playing nearby", x + 2, y + 2, 0.72f, th.sub, center = false)
            y += 13
            for (o in others) {
                if (vis(18)) {
                    tile(g, x, y, w, 18, 6, th, false)
                    text(g, font.plainSubstrByWidth("${o.name}: ${o.title}", ((w - 12) / 0.62f).toInt()), x + 6, y + 6, 0.62f, th.text, center = false)
                }
                y += 21
            }
        }
        maxScroll = maxOf(0.0, (y + scroll.toInt() - bottom).toDouble())
        drawSendBar(g, th, mx, my)
    }

    private fun circleButton(g: GuiGraphics, th: Theme, x: Int, y: Int, mx: Int, my: Int, on: Boolean, action: () -> Unit) {
        val hover = mx >= x && mx < x + 26 && my >= y && my < y + 26
        rfill(g, x, y, 26, 26, 13, if (on) 0xFFFFE27A.toInt() else if (hover) (th.button or 0x20000000) else th.button)
        hits += Hit(x, y, x + 26, y + 26, action)
    }

    private fun openApp(id: String) {
        when (id) {
            "gallery" -> { page = Tab.GALLERY; scroll = 0.0; viewing = null; Gallery.refresh() }
            "wardrobe" -> { page = Tab.WARDROBE; scroll = 0.0 }
            "quiz" -> { onClose(); Commands.startQuiz() }
            "feed" -> { page = Tab.FEED; scroll = 0.0; SocialClient.send("feed"); SocialClient.markFeedSeen() }
            "friends" -> { page = Tab.FRIENDS; scroll = 0.0; SocialClient.send("friends") }
            "profile" -> { page = Tab.PROFILE; scroll = 0.0; SocialClient.send("profile") }
            "messages" -> { page = Tab.CHATS; scroll = 0.0; SocialClient.send("chats") }
            "gear" -> { page = Tab.SETTINGS; scroll = 0.0; micList = null }
            "music" -> { page = Tab.MUSIC; scroll = 0.0 }
        }
        if (id in setOf("feed", "friends", "profile") && !SocialClient.available) say("Needs the Dex network on this server")
    }

    private fun openPost(p: SocialClient.Post, from: Tab) {
        postFrom = from; page = Tab.POST; scroll = 0.0
        SocialClient.viewPost(p.id)
    }

    private fun openChat(p: SocialClient.Person) {
        page = Tab.CHAT; scroll = 0.0; chatPhoto = null; seenChatVersion = -1
        SocialClient.openChat(p)
    }

    // ---- post, messages, chat ---------------------------------------------------------------------------

    private fun listBottom() = cBottom() - 21

    private fun drawPost(g: GuiGraphics, th: Theme, mx: Int, my: Int) {
        val p = SocialClient.openPost
        if (SocialClient.postGone || p == null) {
            text(g, if (SocialClient.postGone) "This post was removed." else "Loading...", sx + sw / 2, cTop() + 30, 0.8f, th.sub); return
        }
        val margin = 6; val cw = sw - margin * 2; val ih = cw * 9 / 16
        val x = sx + margin
        var y = cTop() - scroll.toInt()
        val bottom = listBottom()
        fun visible(top: Int, h: Int) = top + h > cTop() && top < bottom
        if (visible(y, 16)) { avatar(g, p.name, p.avatar, x, y + 1, 12); text(g, p.name, x + 16, y + 4, 0.75f, th.text, center = false)
            val ago = timeAgo(p.time); text(g, ago, x + cw - (font.width(ago) * 0.65f).toInt(), y + 4, 0.65f, th.sub, center = false) }
        y += 16
        if (visible(y, ih)) media(g, th, p.id, x, y, cw, ih, play = true)
        y += ih + 5
        if (visible(y, 12)) {
            val heart = icon("feed", th).map { if (!p.liked && it.c != 0xFFFFFFFF.toInt()) Px(it.x, it.y, it.w, it.h, th.sub) else it }
            sprite(g, heart, x + 1, y, 1)
            text(g, "${p.likes}", x + 16, y + 2, 0.7f, th.text, center = false)
            if (y >= cTop() && y + 12 <= bottom) hits += Hit(x - 2, y - 2, x + 30, y + 13) { SocialClient.like(p) }
            // A friend's photo: keep a copy in your own gallery.
            if (p.owner != Minecraft.getInstance().player?.uuid?.toString() && y + 12 <= bottom) {
                val sw2 = (font.width("Save to gallery") * 0.7f).toInt() + 10
                pill(g, "Save to gallery", x + cw - sw2, y, 0xFF3F7FBF.toInt(), mx, my) { Gallery.save(p.id) }
            }
        }
        y += 15
        if (p.caption.isNotBlank()) { val ls = wrap(p.caption, cw, 0.72f); if (visible(y, ls.size * 9)) lines(g, ls, x, y, 0.72f, th.text, 9); y += ls.size * 9 + 4 }
        if (visible(y, 10)) text(g, if (SocialClient.comments.isEmpty()) "No comments yet. Say something nice!" else "Comments (${SocialClient.comments.size})", x, y, 0.68f, th.sub, center = false)
        y += 13
        for (c in SocialClient.comments) {
            val ls = wrap(c.text, cw - 20, 0.68f)
            val h = 10 + ls.size * 8 + 4
            if (visible(y, h)) {
                avatar(g, c.name, c.avatar, x, y, 12)
                text(g, Component.literal(c.name).withStyle(ChatFormatting.BOLD), x + 16, y, 0.65f, th.text, center = false)
                text(g, timeAgo(c.time), x + 16 + (font.width(Component.literal(c.name).withStyle(ChatFormatting.BOLD)) * 0.65f).toInt() + 4, y, 0.6f, th.sub, center = false)
                lines(g, ls, x + 16, y + 9, 0.68f, th.text, 8)
                if (c.canDelete) {
                    val dx = x + cw - 8
                    text(g, "x", dx + 2, y, 0.7f, if (mx in dx until dx + 8 && my in y until y + 9) 0xFFE8335F.toInt() else th.sub)
                    if (y >= cTop() && y + 9 <= bottom) hits += Hit(dx - 2, y - 1, dx + 8, y + 9) { SocialClient.send("uncomment:${p.id}:${c.id}"); say("Comment deleted") }
                }
            }
            y += h
        }
        maxScroll = maxOf(0.0, (y + scroll.toInt() - bottom + 4).toDouble())
        drawSendBar(g, th, mx, my)
    }

    private fun drawChats(g: GuiGraphics, th: Theme, mx: Int, my: Int) {
        val x = sx + 8; val w = sw - 16
        var y = cTop() - scroll.toInt()
        // Pinned first: your companion, with its face as the avatar.
        if (y + 24 > cTop() && y < cBottom()) {
            val hover = mx in x until x + w && my in y until y + 24 && my in cTop() until cBottom()
            tile(g, x, y, w, 24, 7, th, hover)
            val f = face()
            rfill(g, x + 3, y + 4, 16, 16, 5, f.bg)
            g.fill(x + 7, y + 9, x + 9, y + 12, f.eye); g.fill(x + 13, y + 9, x + 15, y + 12, f.eye); g.fill(x + 10, y + 14, x + 12, y + 15, f.eye)
            text(g, Component.literal(CompanionClient.companionName).withStyle(ChatFormatting.BOLD), x + 23, y + 4, 0.75f, th.text, center = false)
            val last = VoltyChat.lines.lastOrNull()
            val preview = last?.let { (if (it.mine) "You: " else "") + it.text } ?: "Your Dex · ask me anything"
            text(g, font.plainSubstrByWidth(preview, ((w - 52) / 0.62f).toInt()), x + 23, y + 14, 0.62f, th.sub, center = false)
            text(g, "Pinned", x + w - 4 - (font.width("Pinned") * 0.6f).toInt(), y + 4, 0.6f, if (th.night) 0xFFFFE27A.toInt() else 0xFFE0A800.toInt(), center = false)
            if (VoltyChat.unread > 0) { rfill(g, x + w - 16, y + 12, 12, 9, 4, 0xFFE8335F.toInt()); text(g, if (VoltyChat.unread > 9) "9+" else "${VoltyChat.unread}", x + w - 10, y + 13, 0.6f, 0xFFFFFFFF.toInt()) }
            hits += Hit(x, maxOf(y, cTop()), x + w, minOf(y + 24, cBottom())) { openVolty() }
        }
        y += 27
        if (!SocialClient.available) { text(g, "Texting friends needs the Dex network on this server.", sx + sw / 2, y + 10, 0.6f, th.sub); return }
        val list = SocialClient.threads
        if (!SocialClient.threadsLoaded) { text(g, "Loading...", sx + sw / 2, y + 10, 0.8f, th.sub); return }
        if (list.isEmpty()) {
            listOf("No friends to text yet!", "Add friends in the Friends app,", "then chat and send photos here.").forEachIndexed { i, l -> text(g, l, sx + sw / 2, y + 10 + i * 11, 0.7f, th.sub) }
            return
        }
        for (t in list) {
            if (y + 24 > cTop() && y < cBottom()) {
                val hover = mx in x until x + w && my in y until y + 24 && my in cTop() until cBottom()
                tile(g, x, y, w, 24, 7, th, hover)
                avatar(g, t.with.name, t.with.avatar, x + 3, y + 4, 16)
                text(g, Component.literal(t.with.name).let { if (t.unread > 0) it.withStyle(ChatFormatting.BOLD) else it }, x + 23, y + 4, 0.75f, th.text, center = false)
                val preview = t.last ?: "Say hi!"
                text(g, font.plainSubstrByWidth(preview, ((w - 52) / 0.62f).toInt()), x + 23, y + 14, 0.62f, if (t.unread > 0) th.text else th.sub, center = false)
                if (t.time > 0) { val ago = timeAgo(t.time); text(g, ago, x + w - 4 - (font.width(ago) * 0.6f).toInt(), y + 4, 0.6f, th.sub, center = false) }
                if (t.unread > 0) { rfill(g, x + w - 16, y + 12, 12, 9, 4, 0xFFE8335F.toInt()); text(g, if (t.unread > 9) "9+" else "${t.unread}", x + w - 10, y + 13, 0.6f, 0xFFFFFFFF.toInt()) }
                hits += Hit(x, maxOf(y, cTop()), x + w, minOf(y + 24, cBottom())) { openChat(t.with) }
            }
            y += 27
        }
        maxScroll = maxOf(0.0, (y + scroll.toInt() - cBottom()).toDouble())
    }

    private fun drawChat(g: GuiGraphics, th: Theme, mx: Int, my: Int) {
        chatPhoto?.let { id ->
            // One photo, big. A friend's can be saved to your gallery; tap anywhere else to go back.
            val iw = sw - 8; val ih = iw * 9 / 16; val y0 = cTop() + 30
            hits += Hit(sx, cTop(), sx + sw, cBottom()) { chatPhoto = null }
            media(g, th, id, sx + 4, y0, iw, ih, play = true)
            if (!chatPhotoMine) {
                val bw = sw - 24; val by = y0 + ih + 10
                val hover = mx in sx + 12 until sx + 12 + bw && my in by until by + 18
                rfill(g, sx + 12, by, bw, 18, 9, if (hover) 0xFF3F7FBF.toInt() else 0xD03F7FBF.toInt())
                text(g, "Save to my gallery", sx + sw / 2, by + 5, 0.8f, 0xFFFFFFFF.toInt())
                hits += Hit(sx + 12, by, sx + 12 + bw, by + 18) { Gallery.save(id); chatPhoto = null }
                text(g, "Then post it or make it your profile picture.", sx + sw / 2, by + 24, 0.6f, th.sub)
            }
            text(g, "Tap the photo to close", sx + sw / 2, y0 - 12, 0.65f, th.sub)
            return
        }
        val msgs = if (voltyOpen) VoltyChat.lines.map { SocialClient.Message(it.mine, it.text, null, it.time) } else SocialClient.messages
        val loaded = voltyOpen || SocialClient.chatLoaded
        val bottom = listBottom() - (if (voltyOpen) 18 else 0)
        if (voltyOpen) VoltyChat.markRead()
        if (!loaded) text(g, "Loading...", sx + sw / 2, cTop() + 30, 0.8f, th.sub)
        else if (msgs.isEmpty()) text(g, if (voltyOpen) "Ask me anything! Or tap a button below." else "Say hi to ${SocialClient.chatWith?.name ?: "your friend"}!", sx + sw / 2, cTop() + 30, 0.75f, th.sub)
        // Lay out first (heights), then draw from the top with the newest at the bottom.
        val maxBubble = (sw * 0.72).toInt()
        val sizes = msgs.map { m ->
            if (m.photo != null) Triple(96, 54, emptyList()) else {
                val ls = wrap(m.text, maxBubble - 10, 0.7f)
                val w = (ls.maxOfOrNull { font.width(it) } ?: 0) * 0.7f
                Triple(w.toInt() + 10, ls.size * 8 + 7, ls)
            }
        }
        val typingDots = voltyOpen && dev.dexbuddy.client.brain.BuddyBrain.isBusy
        val total = sizes.sumOf { it.second + 4 } + (if (typingDots) 18 else 0)
        val view = bottom - cTop()
        maxScroll = maxOf(0.0, (total - view + 4).toDouble())
        val ver = (if (voltyOpen) VoltyChat.version else SocialClient.chatVersion) * 2 + (if (typingDots) 1 else 0)
        if (seenChatVersion != ver) { seenChatVersion = ver; scroll = maxScroll }
        var y = cTop() + maxOf(0, view - total - 4) - scroll.toInt() + 2
        msgs.forEachIndexed { i, m ->
            val (w, h, ls) = sizes[i]
            val x = if (m.mine) sx + sw - 6 - w else sx + 6
            if (y + h > cTop() && y < bottom) {
                if (m.photo != null) {
                    val tex = Gallery.texture(m.photo)
                    rfill(g, x - 1, y - 1, w + 2, h + 2, 6, if (m.mine) 0xFF3F7FBF.toInt() else th.tileEdge)
                    if (tex != null) g.blit(tex, x, y, w, h, 0f, 0f, 640, 360, 640, 360) else rfill(g, x, y, w, h, 5, th.tile)
                    if (y >= cTop() && y + h <= bottom) hits += Hit(x, y, x + w, y + h) { chatPhoto = m.photo; chatPhotoMine = m.mine }
                } else {
                    rfill(g, x, y, w, h, 6, if (m.mine) 0xFF3F7FBF.toInt() else if (th.night) 0x30FFFFFF else 0xFFFFFFFF.toInt())
                    lines(g, ls, x + 5, y + 4, 0.7f, if (m.mine) 0xFFFFFFFF.toInt() else th.text, 8)
                }
            }
            y += h + 4
        }
        if (typingDots && y + 14 > cTop() && y < bottom) {
            // "..." while it thinks.
            rfill(g, sx + 6, y, 26, 14, 7, if (th.night) 0x30FFFFFF else 0xFFFFFFFF.toInt())
            val ph = ((System.currentTimeMillis() / 250) % 3).toInt()
            for (i in 0..2) rfill(g, sx + 11 + i * 6, y + 5, 4, 4, 2, if (i == ph) th.text else th.sub)
        }
        if (voltyOpen) {
            // Quick asks above the message box.
            g.fill(sx, bottom, sx + sw, bottom + 18, th.bg)
            var qx = sx + 6
            for ((label, ask) in listOf("What's this?" to "What is this?", "Quiz me" to "quiz me", "Take a photo" to "take a photo of us", "Follow me" to "follow me")) {
                val w = (font.width(label) * 0.62f).toInt() + 10
                if (qx + w > sx + sw - 4) break
                rfill(g, qx, bottom + 3, w, 13, 6, th.button)
                text(g, label, qx + w / 2, bottom + 6, 0.62f, th.text)
                hits += Hit(qx, bottom + 3, qx + w, bottom + 16) { VoltyChat.send(ask) }
                qx += w + 4
            }
        }
        drawSendBar(g, th, mx, my)
    }

    /** The pinned chat with your companion. */
    private var voltyOpen = false

    private fun openVolty() {
        page = Tab.CHAT; scroll = 0.0; chatPhoto = null; seenChatVersion = -1
        voltyOpen = true; VoltyChat.open = true; VoltyChat.markRead()
    }

    // ---- social pages -------------------------------------------------------------------------------

    private fun timeAgo(ms: Long): String {
        val s = (System.currentTimeMillis() - ms) / 1000
        return when { s < 60 -> "now"; s < 3600 -> "${s / 60}m"; s < 86_400 -> "${s / 3600}h"; else -> "${s / 86_400}d" }
    }

    /** A square avatar: their profile picture, or their initials on a color from their name. */
    private fun avatar(g: GuiGraphics, name: String, photo: String?, x: Int, y: Int, size: Int) {
        val tex = photo?.let { Gallery.texture(it) }
        if (tex != null) {
            g.blit(tex, x, y, size, size, 140f, 0f, 360, 360, 640, 360)
        } else {
            val hues = intArrayOf(0xFF3F7FBF.toInt(), 0xFF2E9E7A.toInt(), 0xFF7B5CD6.toInt(), 0xFFE8473F.toInt(), 0xFFE08A00.toInt())
            rfill(g, x, y, size, size, size / 3, hues[Math.floorMod(name.hashCode(), hues.size)])
            text(g, name.take(2).uppercase(), x + size / 2, y + size / 2 - 3, (size / 18f).coerceIn(0.6f, 1.2f), 0xFFFFFFFF.toInt())
        }
    }

    /**
     * A photo, or a clip playing (when [play] and it's a clip). Clips that aren't playing get a small
     * play badge. Falls back to a plain tile while loading.
     */
    private fun media(g: GuiGraphics, th: Theme, id: String, x: Int, y: Int, w: Int, h: Int, play: Boolean) {
        val clip = if (play) Gallery.clipTexture(id) else null
        if (clip != null) g.blit(clip, x, y, w, h, 0f, 0f, 480, 270, 480, 270)
        else Gallery.texture(id)?.let { g.blit(it, x, y, w, h, 0f, 0f, 640, 360, 640, 360) } ?: g.fill(x, y, x + w, y + h, th.tile)
        if (Gallery.isClip(id) == true && clip == null) {
            val s = minOf(14, h / 3); val cx = x + w / 2; val cy = y + h / 2
            rfill(g, cx - s, cy - s, s * 2, s * 2, s, 0xA0000000.toInt())
            // Play triangle.
            val t = s * 2 / 3
            for (i in 0 until t) g.fill(cx - t / 3 + i / 2, cy - t / 2 + i / 2, cx - t / 3 + i / 2 + 1, cy + t / 2 - i / 2, 0xFFFFFFFF.toInt())
        }
    }

    /** A small rounded button. Returns its width. */
    private fun pill(g: GuiGraphics, label: String, x: Int, y: Int, color: Int, mx: Int, my: Int, action: () -> Unit): Int {
        val w = (font.width(label) * 0.7f).toInt() + 10
        val hover = mx >= x && mx < x + w && my >= y && my < y + 12 && my in cTop() until cBottom()
        rfill(g, x, y, w, 12, 6, if (hover) color else (color and 0x00FFFFFF) or 0xC0000000.toInt())
        text(g, label, x + w / 2, y + 3, 0.7f, 0xFFFFFFFF.toInt())
        if (y + 12 > cTop() && y < cBottom()) hits += Hit(x, y, x + w, y + 12, action)
        return w
    }

    private fun drawFeed(g: GuiGraphics, th: Theme, mx: Int, my: Int) {
        val posts = SocialClient.feed
        val note = when {
            !SocialClient.available -> "The feed needs the\nDex network on this server."
            !SocialClient.feedLoaded -> "Loading..."
            posts.isEmpty() -> "Nothing here yet!\nAdd friends, or post a photo\nfrom your gallery."
            else -> null
        }
        if (note != null) { note.split('\n').forEachIndexed { i, l -> text(g, l, sx + sw / 2, cTop() + 30 + i * 11, 0.8f, th.sub) }; return }
        val margin = 6; val cw = sw - margin * 2; val ih = cw * 9 / 16
        val cardH = 20 + ih + 22; val gap = 8
        maxScroll = maxOf(0.0, (posts.size * (cardH + gap) - (cBottom() - cTop())).toDouble())
        scroll = scroll.coerceIn(0.0, maxScroll)
        posts.forEachIndexed { i, p ->
            val x = sx + margin; val y = cTop() + i * (cardH + gap) - scroll.toInt()
            if (y + cardH < cTop() || y > cBottom()) return@forEachIndexed
            tile(g, x, y, cw, cardH, 9, th, false)
            avatar(g, p.name, p.avatar, x + 5, y + 4, 12)
            text(g, p.name, x + 21, y + 7, 0.75f, th.text, center = false)
            val ago = timeAgo(p.time)
            text(g, ago, x + cw - 6 - (font.width(ago) * 0.65f).toInt(), y + 7, 0.65f, th.sub, center = false)
            // Clips play right in the feed.
            media(g, th, p.id, x, y + 20, cw, ih, play = true)
            // Tap the photo (or the comment count) to open the post and comment.
            val iy0 = maxOf(y + 20, cTop()); val iy1 = minOf(y + 20 + ih, cBottom())
            if (iy1 > iy0) hits += Hit(x, iy0, x + cw, iy1) { openPost(p, Tab.FEED) }
            // Like: a pixel heart, filled when you've liked it.
            val hy = y + 20 + ih + 5
            val heart = icon("feed", th).map { if (!p.liked && it.c != 0xFFFFFFFF.toInt()) Px(it.x, it.y, it.w, it.h, th.sub) else it }
            sprite(g, heart, x + 5, hy, 1)
            text(g, "${p.likes}", x + 20, hy + 2, 0.7f, th.text, center = false)
            if (hy - 2 >= cTop() && hy + 13 <= cBottom()) hits += Hit(x + 2, hy - 2, x + 34, hy + 13) { SocialClient.like(p) }
            // Comments: a small speech bubble and the count.
            sprite(g, icon("messages", th).map { if (it.c == 0xFF7FD8BE.toInt() || it.c == 0xFF3FB58E.toInt()) Px(it.x, it.y, it.w, it.h, th.sub) else it }, x + 36, hy, 1)
            text(g, "${p.comments}", x + 51, hy + 2, 0.7f, th.text, center = false)
            if (hy - 2 >= cTop() && hy + 13 <= cBottom()) hits += Hit(x + 34, hy - 2, x + 64, hy + 13) { openPost(p, Tab.FEED) }
            if (p.caption.isNotBlank()) text(g, font.plainSubstrByWidth(p.caption, ((cw - 74) / 0.7f).toInt()), x + 66, hy + 2, 0.7f, th.sub, center = false)
        }
    }

    private fun drawFriends(g: GuiGraphics, th: Theme, mx: Int, my: Int) {
        if (!SocialClient.available) { text(g, "Needs the Dex network on this server.", sx + sw / 2, cTop() + 30, 0.75f, th.sub); return }
        var y = cTop() - scroll.toInt()
        val x = sx + 8; val w = sw - 16
        fun header(s: String) { text(g, s, x, y + 2, 0.75f, th.sub, center = false); y += 13 }
        fun row(p: SocialClient.Person, extra: String?, buttons: (Int) -> Unit) {
            if (y + 20 > cTop() && y < cBottom()) {
                tile(g, x, y, w, 20, 6, th, false)
                avatar(g, p.name, p.avatar, x + 3, y + 3, 14)
                text(g, p.name, x + 21, y + (if (extra != null) 3 else 7), 0.75f, th.text, center = false)
                if (extra != null) text(g, extra, x + 21, y + 11, 0.6f, th.sub, center = false)
                buttons(y + 4)
            }
            y += 23
        }
        if (SocialClient.incoming.isNotEmpty()) {
            header("Friend requests")
            SocialClient.incoming.forEach { p -> row(p, "wants to be friends") { by ->
                val nw = (font.width("No") * 0.7f).toInt() + 10
                pill(g, "No", x + w - 4 - nw, by, 0xFF6B6B80.toInt(), mx, my) { SocialClient.send("decline:${p.uuid}") }
                val aw = (font.width("Accept") * 0.7f).toInt() + 10
                pill(g, "Accept", x + w - 8 - nw - aw, by, 0xFF2E9E7A.toInt(), mx, my) { SocialClient.send("accept:${p.uuid}") }
            } }
        }
        header("Find friends nearby")
        if (SocialClient.nearby.isEmpty()) { text(g, "No one nearby. Get close to a player!", x + 2, y + 2, 0.65f, th.sub, center = false); y += 14 }
        SocialClient.nearby.forEach { p ->
            val pending = SocialClient.outgoing.any { it.uuid == p.uuid }
            row(p, if (pending) "request sent" else null) { by ->
                if (!pending) { val aw = (font.width("Add") * 0.7f).toInt() + 10
                    pill(g, "Add", x + w - 4 - aw, by, 0xFF3F7FBF.toInt(), mx, my) { SocialClient.send("request:${p.uuid}"); say("Request sent!") } }
            }
        }
        header("Friends (${SocialClient.friends.size})")
        if (SocialClient.friends.isEmpty()) { text(g, "Tip: hold your Dex, sneak + right-click a player", x + 2, y + 2, 0.65f, th.sub, center = false); y += 14 }
        SocialClient.friends.forEach { p -> row(p, if (p.online) "online" else null) { by ->
            val rw = (font.width("Remove") * 0.7f).toInt() + 10
            pill(g, "Remove", x + w - 4 - rw, by, 0xFF6B6B80.toInt(), mx, my) { SocialClient.send("unfriend:${p.uuid}") }
            val tw2 = (font.width("Text") * 0.7f).toInt() + 10
            pill(g, "Text", x + w - 8 - rw - tw2, by, 0xFF2E9E7A.toInt(), mx, my) { openChat(p) }
        } }
        maxScroll = maxOf(0.0, (y + scroll.toInt() - cBottom()).toDouble())
    }

    private fun drawProfile(g: GuiGraphics, th: Theme, mx: Int, my: Int) {
        if (!SocialClient.available) { text(g, "Needs the Dex network on this server.", sx + sw / 2, cTop() + 30, 0.75f, th.sub); return }
        val name = Minecraft.getInstance().player?.gameProfile?.name ?: "Trainer"
        var y = cTop() - scroll.toInt()
        val cx = sx + sw / 2
        rfill(g, cx - 28, y, 56, 56, 12, if (th.night) 0xFFFFE27A.toInt() else 0xFFFFC93C.toInt())
        avatar(g, name, SocialClient.me?.avatar, cx - 26, y + 2, 52)
        y += 62
        text(g, Component.literal(name).withStyle(ChatFormatting.BOLD), cx, y, 1f, th.text); y += 12
        text(g, "${SocialClient.myPosts.size} posts  ·  ${SocialClient.friendCount} friends  ·  ${SocialClient.likeTotal} likes", cx, y, 0.65f, th.sub); y += 12
        val lw = (font.width("Change picture") * 0.7f).toInt() + 10
        pill(g, "Change picture", cx - lw / 2, y, 0xFF7B5CD6.toInt(), mx, my) {
            pickAvatar = true; page = Tab.GALLERY; viewing = null; scroll = 0.0; Gallery.refresh()
        }
        y += 18
        val posts = SocialClient.myPosts
        if (posts.isEmpty()) text(g, "No posts yet. Open a photo in your gallery and tap Post!", cx, y + 6, 0.6f, th.sub)
        val gap = 3; val tw = (sw - 16 - gap * 2) / 3
        posts.forEachIndexed { i, p ->
            val x = sx + 8 + (i % 3) * (tw + gap); val ty = y + (i / 3) * (tw + gap)
            if (ty + tw < cTop() || ty > cBottom()) return@forEachIndexed
            val tex = Gallery.texture(p.id)
            if (tex != null) g.blit(tex, x, ty, tw, tw, 140f, 0f, 360, 360, 640, 360) else g.fill(x, ty, x + tw, ty + tw, th.tile)
            if (p.likes > 0) text(g, "♥${p.likes}", x + tw - 3 - (font.width("♥${p.likes}") * 0.6f).toInt(), ty + tw - 8, 0.6f, 0xFFFFFFFF.toInt(), center = false)
            val t0 = maxOf(ty, cTop()); val t1 = minOf(ty + tw, cBottom())
            if (t1 > t0) hits += Hit(x, t0, x + tw, t1) { openPost(p, Tab.PROFILE) }
        }
        maxScroll = maxOf(0.0, (y + ((posts.size + 2) / 3) * (tw + gap) + scroll.toInt() - cBottom()).toDouble())
    }

    /** A real flashlight: light follows you around, with or without your companion out. */
    private fun toggleGlow() {
        if (!Commands.canUseCompanionCommands()) { say("Needs the Dex network on this server"); return }
        flashlightOn = !flashlightOn
        net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking.send(dev.dexbuddy.net.CompanionCommandPayload(if (flashlightOn) "flashlight_on" else "flashlight_off"))
        Minecraft.getInstance().soundManager.play(net.minecraft.client.resources.sounds.SimpleSoundInstance.forUI(net.minecraft.sounds.SoundEvents.LEVER_CLICK, if (flashlightOn) 1.6f else 1.2f, 0.5f))
        say(if (flashlightOn) "Flashlight on" else "Flashlight off")
    }

    /** A sub-page: a back arrow and title, with its content below. */
    private fun drawSub(g: GuiGraphics, th: Theme, title: String, mx: Int, my: Int, content: () -> Unit) {
        val hy = sy + 22
        val hoverBack = mx >= sx + 4 && mx < sx + 40 && my >= hy - 3 && my < hy + 12
        text(g, "< Back", sx + 8, hy, 0.8f, if (hoverBack) th.text else th.sub, center = false)
        hits += Hit(sx + 4, hy - 3, sx + 40, hy + 12) { back() }
        text(g, Component.literal(title).withStyle(ChatFormatting.BOLD), sx + sw / 2, hy + 12, 1f, th.text)
        // The clip area is in screen coordinates, so scale it like the phone.
        g.enableScissor((ox + sx * k).toInt(), (oy + cTop() * k).toInt(), (ox + (sx + sw) * k).toInt(), (oy + cBottom() * k).toInt())
        content()
        g.disableScissor()
    }

    private fun cTop() = sy + 48
    private fun cBottom() = sy + sh - 14

    private fun back() {
        when {
            captioning -> captioning = false
            pickAvatar -> { pickAvatar = false; page = Tab.PROFILE; scroll = 0.0 }
            pickForChat -> { pickForChat = false; page = Tab.CHAT; scroll = 0.0; seenChatVersion = -1 }
            page == Tab.CHAT && chatPhoto != null -> chatPhoto = null
            page == Tab.CHAT && voltyOpen -> { voltyOpen = false; VoltyChat.open = false; page = Tab.CHATS; scroll = 0.0 }
            page == Tab.CHAT -> { page = Tab.CHATS; scroll = 0.0; SocialClient.closeChat() }
            page == Tab.POST -> { page = postFrom; scroll = 0.0 }
            viewing != null -> { viewing = null; confirmDelete = false }
            page == Tab.GUIDE -> { page = Tab.SETTINGS; scroll = 0.0 }
            page == Tab.SETTINGS || page == Tab.CHATS -> { page = Tab.HOME; scroll = 0.0 }
            page != Tab.HOME -> { page = Tab.HOME; scroll = 0.0 }
            else -> onClose()
        }
    }

    private fun drawGallery(g: GuiGraphics, th: Theme, mx: Int, my: Int) {
        val list = Gallery.entries
        val note = when {
            !Gallery.available -> "The gallery needs the\nDex network on this server."
            !Gallery.loaded -> "Loading..."
            list.isEmpty() -> "No photos yet!\nTap the camera to snap one."
            else -> null
        }
        if (note != null) {
            note.split('\n').forEachIndexed { i, line -> text(g, line, sx + sw / 2, cTop() + 30 + i * 11, 0.8f, th.sub) }
            return
        }
        val gap = 4; val margin = 8
        val tw = (sw - margin * 2 - gap) / 2; val tH = tw * 9 / 16
        val rows = (list.size + 1) / 2
        maxScroll = maxOf(0.0, (rows * (tH + gap) - (cBottom() - cTop())).toDouble())
        scroll = scroll.coerceIn(0.0, maxScroll)
        list.forEachIndexed { i, e ->
            val x = sx + margin + (i % 2) * (tw + gap)
            val y = cTop() + (i / 2) * (tH + gap) - scroll.toInt()
            if (y + tH < cTop() || y > cBottom()) return@forEachIndexed
            media(g, th, e.id, x, y, tw, tH, play = false)
            if (mx >= x && mx < x + tw && my >= y && my < y + tH && my in cTop() until cBottom()) g.renderOutline(x - 1, y - 1, tw + 2, tH + 2, 0xFFFFE27A.toInt())
            hits += Hit(x, maxOf(y, cTop()), x + tw, minOf(y + tH, cBottom())) {
                if (pickAvatar) {
                    SocialClient.send("avatar:${e.id}"); pickAvatar = false; page = Tab.PROFILE; scroll = 0.0
                } else if (pickForChat) {
                    SocialClient.chatId?.let { SocialClient.send("msgphoto:$it:${e.id}") }
                    pickForChat = false; page = Tab.CHAT; scroll = 0.0; seenChatVersion = -1; say("Photo sent!")
                } else { viewing = e; confirmDelete = false; captioning = false }
            }
        }
    }

    private fun drawViewer(g: GuiGraphics, th: Theme, e: Gallery.Entry, mx: Int, my: Int) {
        val margin = 6
        val iw = sw - margin * 2; val ih = iw * 9 / 16
        val y0 = cTop()
        media(g, th, e.id, sx + margin, y0, iw, ih, play = true)
        val bw = sw - margin * 2
        if (captioning) {
            // The caption box (drawn over this spot), then Share / Cancel.
            val by2 = y0 + ih + 8 + 20
            val half = (bw - 6) / 2
            rfill(g, sx + margin, by2, half, 18, 9, 0xFF2E9E7A.toInt())
            text(g, "Share", sx + margin + half / 2, by2 + 5, 0.8f, 0xFFFFFFFF.toInt())
            hits += Hit(sx + margin, by2, sx + margin + half, by2 + 18) {
                val caption = captionBox?.value.orEmpty().replace(":", " ")
                SocialClient.send("post:${e.id}:$caption"); captioning = false; captionBox?.value = ""; say("Posted to your friends!")
            }
            rfill(g, sx + margin + half + 6, by2, half, 18, 9, 0x806B6B80.toInt())
            text(g, "Cancel", sx + margin + half + 6 + half / 2, by2 + 5, 0.8f, 0xFFFFFFFF.toInt())
            hits += Hit(sx + margin + half + 6, by2, sx + margin + bw, by2 + 18) { captioning = false }
            return
        }
        val date = DateTimeFormatter.ofPattern("MMM d, yyyy  h:mm a").withZone(ZoneId.systemDefault()).format(Instant.ofEpochMilli(e.time))
        val kind = when (e.mode) { "close" -> "Close-up"; "portrait" -> "Portrait"; "wide" -> "Wide shot"; "saved" -> "Saved from a friend"; "clip" -> "Video clip"; else -> "Snapshot" }
        val place = e.dim.substringAfter(':').replace('_', ' ').replaceFirstChar { it.uppercase() }
        var ty = y0 + ih + 8
        // Photos saved from friends carry no place (where they took it stays private).
        val rows = listOf(kind to th.text, date to th.sub) + if (e.dim.isNotEmpty()) listOf("$place  ${e.x}, ${e.y}, ${e.z}" to th.sub) else emptyList()
        for ((s, c) in rows) { text(g, s, sx + margin + 2, ty, 0.8f, c, center = false); ty += 11 }
        // Share it, or make it your profile picture.
        if (SocialClient.available) {
            val posted = SocialClient.isPosted(e.id)
            val py2 = cBottom() - 68
            rfill(g, sx + margin, py2, bw, 18, 9, if (posted) 0x806B6B80.toInt() else 0xFF3F7FBF.toInt())
            text(g, if (posted) "Posted  ·  Unpost" else "Post to friends", sx + sw / 2, py2 + 5, 0.8f, 0xFFFFFFFF.toInt())
            hits += Hit(sx + margin, py2, sx + margin + bw, py2 + 18) {
                if (posted) { SocialClient.send("unpost:${e.id}"); say("Removed from your posts") }
                else { captioning = true; captionBox?.let { setFocused(it); it.isFocused = true } }
            }
            val isAvatar = SocialClient.me?.avatar == e.id
            rfill(g, sx + margin, py2 + 22, bw, 18, 9, if (isAvatar) 0x806B6B80.toInt() else 0xFF7B5CD6.toInt())
            text(g, if (isAvatar) "Your profile picture" else "Set as profile picture", sx + sw / 2, py2 + 27, 0.8f, 0xFFFFFFFF.toInt())
            if (!isAvatar) hits += Hit(sx + margin, py2 + 22, sx + margin + bw, py2 + 40) { SocialClient.send("avatar:${e.id}") }
        }
        val by = cBottom() - 24
        val hover = mx >= sx + margin && mx < sx + margin + bw && my >= by && my < by + 18
        rfill(g, sx + margin, by, bw, 18, 9, if (confirmDelete) 0xFFD32F2F.toInt() else if (hover) 0xC0D32F2F.toInt() else 0x80D32F2F.toInt())
        text(g, if (confirmDelete) "Tap again to delete" else "Delete", sx + sw / 2, by + 5, 0.8f, 0xFFFFFFFF.toInt())
        hits += Hit(sx + margin, by, sx + margin + bw, by + 18) {
            if (confirmDelete) { Gallery.delete(e.id); viewing = null; confirmDelete = false; say("Photo deleted") } else confirmDelete = true
        }
    }

    private fun drawWardrobe(g: GuiGraphics, th: Theme, mx: Int, my: Int) {
        val bits = costumeBits()
        val wearing = currentLook()
        val gap = 5; val margin = 8
        val cw = (sw - margin * 2 - gap) / 2; val ch = 58
        maxScroll = maxOf(0.0, (((Look.entries.size + 1) / 2) * (ch + gap) - (cBottom() - cTop())).toDouble())
        scroll = scroll.coerceIn(0.0, maxScroll)
        Look.entries.forEachIndexed { i, look ->
            val x = sx + margin + (i % 2) * (cw + gap)
            val y = cTop() + (i / 2) * (ch + gap) - scroll.toInt()
            val unlocked = DexBuddyEntity.hasCostume(bits, look)
            val hover = mx >= x && mx < x + cw && my >= y && my < y + ch
            tile(g, x, y, cw, ch, 9, th, hover && unlocked)
            if (look == wearing) { rfill(g, x, y, cw, 2, 1, 0xFFFFC93C.toInt()) }
            g.pose().pushPose()
            g.pose().translate((x + cw / 2).toFloat(), (y + 20).toFloat(), 0f)
            g.pose().scale(1.8f, 1.8f, 1f)
            g.renderItem(previewStack(look), -8, -8)
            g.pose().popPose()
            if (!unlocked) {
                // In front of the 3D model (items draw at a raised depth).
                g.pose().pushPose(); g.pose().translate(0f, 0f, 300f)
                rfill(g, x + 1, y + 1, cw - 2, ch - 2, 8, if (th.night) 0xA0141026.toInt() else 0x90BFE3FF.toInt())
                g.pose().popPose()
            }
            text(g, look.displayName, x + cw / 2, y + ch - 19, 0.75f, if (unlocked) th.text else th.sub)
            val sub = when { look == wearing -> "Wearing"; unlocked -> "Tap to wear"; else -> unlockHint(look) }
            text(g, sub, x + cw / 2, y + ch - 10, 0.65f, if (look == wearing) 0xFFE0A800.toInt() else th.sub)
            if (y + ch > cTop()) hits += Hit(x, maxOf(y, cTop()), x + cw, minOf(y + ch, cBottom())) {
                when {
                    !unlocked -> say("Locked: ${unlockHint(look)}")
                    look == wearing -> say("Already wearing it!")
                    else -> { Commands.changeLook(look); say("Now wearing: ${look.displayName}") }
                }
            }
        }
    }

    private fun unlockHint(look: Look) = look.hint

    private val previews = HashMap<Look, ItemStack>()
    private fun previewStack(look: Look): ItemStack = previews.getOrPut(look) {
        ItemStack(DexBuddyMod.DEX_BUDDY).also { s ->
            if (look != Look.DEFAULT) s.set(DataComponents.CUSTOM_DATA, CustomData.of(CompoundTag().apply { putString("look", look.id) }))
        }
    }

    // ---- input --------------------------------------------------------------------------------------

    // A press that turns into a drag scrolls the page (like a phone); a press without moving is a tap.
    private var pressHit: Hit? = null
    private var pressing = false
    private var dragged = false
    private var dragTotal = 0.0

    override fun mouseClicked(mouseX: Double, mouseY: Double, button: Int): Boolean {
        if (button == 0) {
            pressing = true; dragged = false; dragTotal = 0.0
            pressHit = hits.lastOrNull { it.contains(vx(mouseX), vy(mouseY)) }
            if (pressHit != null) return true
        }
        return super.mouseClicked(mouseX, mouseY, button)
    }

    override fun mouseDragged(mouseX: Double, mouseY: Double, button: Int, dragX: Double, dragY: Double): Boolean {
        if (button == 0 && pressing && page != Tab.HOME) {
            dragTotal += kotlin.math.abs(dragY)
            if (dragTotal > 3) {
                dragged = true
                scroll = (scroll - dragY / k).coerceIn(0.0, maxScroll)
                return true
            }
        }
        return super.mouseDragged(mouseX, mouseY, button, dragX, dragY)
    }

    override fun mouseReleased(mouseX: Double, mouseY: Double, button: Int): Boolean {
        if (button == 0 && pressing) {
            pressing = false
            val hit = pressHit
            pressHit = null
            if (hit != null && !dragged) {
                Minecraft.getInstance().soundManager.play(net.minecraft.client.resources.sounds.SimpleSoundInstance.forUI(net.minecraft.sounds.SoundEvents.UI_BUTTON_CLICK.value(), 1.4f, 0.4f))
                hit.action()
                return true
            }
            if (dragged) return true
        }
        return super.mouseReleased(mouseX, mouseY, button)
    }

    override fun mouseScrolled(mouseX: Double, mouseY: Double, scrollX: Double, scrollY: Double): Boolean {
        scroll = (scroll - scrollY * 20).coerceIn(0.0, maxScroll)
        return true
    }

    override fun keyPressed(keyCode: Int, scanCode: Int, modifiers: Int): Boolean {
        // Esc goes back a page first, then closes the phone.
        if (keyCode == org.lwjgl.glfw.GLFW.GLFW_KEY_ESCAPE && chatBox?.isFocused == true) { chatBox?.isFocused = false; return true }
        if (keyCode == org.lwjgl.glfw.GLFW.GLFW_KEY_ESCAPE && page != Tab.HOME) { back(); return true }
        // Enter sends the comment or message.
        if ((keyCode == org.lwjgl.glfw.GLFW.GLFW_KEY_ENTER || keyCode == org.lwjgl.glfw.GLFW.GLFW_KEY_KP_ENTER) && chatBox?.isFocused == true) { submitBox(); return true }
        // The inventory key (E) closes it too, like any container, unless you're typing.
        if (Minecraft.getInstance().options.keyInventory.matches(keyCode, scanCode) && captionBox?.isFocused != true && chatBox?.isFocused != true) { onClose(); return true }
        return super.keyPressed(keyCode, scanCode, modifiers)
    }

    companion object {
        /** The flashlight stays on after the phone closes (until you turn it off or leave the world). */
        var flashlightOn = false

        /** Self-test only: open this chat photo big when the phone opens on a chat. */
        var testPhoto: String? = null

        fun open(tab: Tab = Tab.HOME) = Minecraft.getInstance().let { mc -> mc.execute { mc.setScreen(PhoneScreen(tab)) } }
    }
}
