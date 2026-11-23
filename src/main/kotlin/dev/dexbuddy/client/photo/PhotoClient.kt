package dev.dexbuddy.client.photo

import com.mojang.blaze3d.platform.NativeImage
import dev.dexbuddy.DexBuddyMod
import dev.dexbuddy.client.DexBuddyClient
import dev.dexbuddy.client.antics.Commands
import dev.dexbuddy.client.entity.CompanionClient
import dev.dexbuddy.client.tts.VoicePlayer
import dev.dexbuddy.entity.DexBuddyEntity
import dev.dexbuddy.net.CompanionCommandPayload
import dev.dexbuddy.net.PhotoChunkPayload
import dev.dexbuddy.net.PhotoSavedPayload
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking
import net.minecraft.client.DeltaTracker
import net.minecraft.client.Minecraft
import net.minecraft.client.Screenshot
import net.minecraft.client.gui.GuiGraphics
import net.minecraft.client.renderer.texture.DynamicTexture
import net.minecraft.resources.ResourceLocation
import net.minecraft.sounds.SoundEvents
import java.awt.RenderingHints
import java.awt.image.BufferedImage
import java.io.ByteArrayOutputStream
import java.nio.file.Files
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter
import java.util.UUID
import javax.imageio.IIOImage
import javax.imageio.ImageIO
import javax.imageio.ImageWriteParam
import kotlin.math.atan2
import kotlin.math.sqrt

/**
 * The camera. Two ways to shoot:
 *  - hand: holding the Dex, it snaps what you see (HUD and hand hidden);
 *  - companion: it flies out to frame you (close-up, portrait or wide), counts down, and the picture is
 *    taken from its own eyes — the game briefly looks through it for a couple of frames, like a flash.
 * Photos become small JPEGs: saved to screenshots/dexbuddy and sent to the server's gallery.
 */
object PhotoClient {
    enum class Kind(val id: String, val label: String) { HAND("hand", "snapshot"), CLOSE("close", "close-up"), PORTRAIT("portrait", "portrait"), WIDE("wide", "wide shot") }

    private const val OUT_W = 640
    private const val OUT_H = 360

    private class Shot(val kind: Kind, val notBefore: Long, val giveUpAt: Long)

    private var shot: Shot? = null
    private var captureTicks = -1
    private var savedHideGui = false
    private var flashAt = 0L
    private var preview: ResourceLocation? = null
    private var previewTex: DynamicTexture? = null
    private var previewUntil = 0L
    private var previewNote = ""

    val busy: Boolean get() = shot != null || captureTicks >= 0

    /** Frames drawn (HUD hidden, from the right viewpoint) since the shutter opened. */
    private var framesReady = 0

    fun register() {
        ClipRecorder.register()
        ClientTickEvents.END_CLIENT_TICK.register { tick(it) }
        // The viewfinder takes its click at the *start* of the tick, before Minecraft turns it into a punch.
        ClientTickEvents.START_CLIENT_TICK.register { if (it.player != null) tickViewfinder(it) }
        net.fabricmc.fabric.api.client.rendering.v1.WorldRenderEvents.END.register { ctx ->
            val s = shot ?: return@register
            if (captureTicks < 0) return@register
            val want = if (s.kind == Kind.HAND) Minecraft.getInstance().player else CompanionClient.mine(Minecraft.getInstance().player ?: return@register)
            if (ctx.camera().entity === want) framesReady++
        }
        // Minecraft never draws your own player unless the camera is you; for the companion's shot, draw them.
        ClientTickEvents.END_CLIENT_TICK.register { tickVideo(it) }
        net.fabricmc.fabric.api.client.rendering.v1.WorldRenderEvents.AFTER_ENTITIES.register { ctx ->
            val s = shot
            val companionCam = (s != null && captureTicks >= 0 && s.kind != Kind.HAND) || ClipRecorder.drawingCompanion
            if (!companionCam) return@register
            val mc = Minecraft.getInstance()
            val p = mc.player ?: return@register
            if (ctx.camera().entity === p) return@register
            val pose = ctx.matrixStack() ?: return@register
            val buffers = ctx.consumers() ?: return@register
            val pt = ctx.tickCounter().getGameTimeDeltaPartialTick(false)
            val cam = ctx.camera().position
            val x = net.minecraft.util.Mth.lerp(pt.toDouble(), p.xOld, p.x) - cam.x
            val y = net.minecraft.util.Mth.lerp(pt.toDouble(), p.yOld, p.y) - cam.y
            val z = net.minecraft.util.Mth.lerp(pt.toDouble(), p.zOld, p.z) - cam.z
            val yaw = net.minecraft.util.Mth.lerp(pt, p.yRotO, p.yRot)
            val d = mc.entityRenderDispatcher
            d.render(p, x, y, z, yaw, pt, pose, buffers, d.getPackedLightCoords(p, pt))
        }
        ClientPlayNetworking.registerGlobalReceiver(PhotoSavedPayload.TYPE) { p, _ ->
            if (p.error.isNotEmpty()) { Commands.reply(p.error); previewNote = "Not saved" }
            else previewNote = "Saved to gallery (${p.count})"
        }
    }

    // ---- viewfinder (the phone's Camera button) -------------------------------------------------------

    /** Aiming mode: frame lines on screen; click to shoot, Esc (or any menu) to cancel. */
    var viewfinder = false
        private set

    fun openViewfinder() { if (!busy) { viewfinder = true; Minecraft.getInstance().options.keyAttack.consumeClick() } }

    /** Ticks the shutter (left click) has been held, or -1. A short press is a photo, a hold records a clip. */
    private var shutterTicks = -1

    private fun tickViewfinder(mc: Minecraft) {
        if (!viewfinder) return
        if (mc.screen != null) { viewfinder = false; shutterTicks = -1; ClipRecorder.stop(); return }
        // Swallow clicks so aiming never punches or places anything; a click is the shutter.
        var attack = false; var use = false
        while (mc.options.keyAttack.consumeClick()) attack = true
        while (mc.options.keyUse.consumeClick()) use = true
        if (ClipRecorder.recording) {
            // Recording: let go (or reach the limit) to stop.
            if (!mc.options.keyAttack.isDown) { ClipRecorder.stop(); viewfinder = false }
            return
        }
        if (use) { snap(); return }
        if (attack && shutterTicks < 0) shutterTicks = 0
        if (shutterTicks >= 0) {
            if (mc.options.keyAttack.isDown && ClipRecorder.available()) {
                if (++shutterTicks >= 7) { shutterTicks = -1; ClipRecorder.start(); dev.dexbuddy.client.antics.Tricks.used("clip") }
            } else { shutterTicks = -1; snap() }
        }
    }

    private fun snap() {
        viewfinder = false
        val now = System.currentTimeMillis()
        shot = Shot(Kind.HAND, now, now)
    }

    private fun renderViewfinder(g: GuiGraphics) {
        val w = g.guiWidth(); val h = g.guiHeight()
        // The 16:9 frame the photo will keep, with dimmed bars outside it.
        val fh = minOf(h, w * 9 / 16); val fw = fh * 16 / 9
        val x0 = (w - fw) / 2; val y0 = (h - fh) / 2
        if (y0 > 0) { g.fill(0, 0, w, y0, 0x90000000.toInt()); g.fill(0, y0 + fh, w, h, 0x90000000.toInt()) }
        if (x0 > 0) { g.fill(0, y0, x0, y0 + fh, 0x90000000.toInt()); g.fill(x0 + fw, y0, w, y0 + fh, 0x90000000.toInt()) }
        val white = 0xE0FFFFFF.toInt(); val faint = 0x40FFFFFF
        // Rule-of-thirds grid.
        for (i in 1..2) { g.fill(x0 + fw * i / 3, y0, x0 + fw * i / 3 + 1, y0 + fh, faint); g.fill(x0, y0 + fh * i / 3, x0 + fw, y0 + fh * i / 3 + 1, faint) }
        // Corner brackets.
        val m = 10; val l = 18
        val left = x0 + m; val right = x0 + fw - m; val top = y0 + m; val bottom = y0 + fh - m
        g.fill(left, top, left + l, top + 2, white); g.fill(left, top, left + 2, top + l, white)
        g.fill(right - l, top, right, top + 2, white); g.fill(right - 2, top, right, top + l, white)
        g.fill(left, bottom - 2, left + l, bottom, white); g.fill(left, bottom - l, left + 2, bottom, white)
        g.fill(right - l, bottom - 2, right, bottom, white); g.fill(right - 2, bottom - l, right, bottom, white)
        // Focus square in the middle.
        val c = 8
        g.renderOutline(w / 2 - c, h / 2 - c, c * 2, c * 2, white)
        val font = Minecraft.getInstance().font
        val blink = (System.currentTimeMillis() / 500) % 2 == 0L
        g.fill(x0 + m + 4, y0 + m + 6, x0 + m + 9, y0 + m + 11, if (blink) 0xFFFF4040.toInt() else 0x60FF4040)
        if (ClipRecorder.recording) {
            val s = ClipRecorder.seconds
            g.drawString(font, "REC  0:%02d / 0:%02d".format(s, ClipRecorder.MAX_SECONDS), x0 + m + 13, y0 + m + 5, 0xFFFF6060.toInt(), false)
            // A bar filling toward the limit.
            val bw = fw - 2 * m - 40
            g.fill(x0 + m + 20, y0 + fh - m - 4, x0 + m + 20 + bw, y0 + fh - m - 2, 0x40FFFFFF)
            g.fill(x0 + m + 20, y0 + fh - m - 4, x0 + m + 20 + (bw * minOf(1f, s / ClipRecorder.MAX_SECONDS.toFloat())).toInt(), y0 + fh - m - 2, 0xFFFF4040.toInt())
            g.drawCenteredString(font, "Recording  ·  let go to stop", w / 2, y0 + fh - m - 16, white)
            return
        }
        g.drawString(font, "CAMERA", x0 + m + 13, y0 + m + 5, white, false)
        val hint = if (ClipRecorder.available()) "Click to snap  ·  Hold to record  ·  Esc to cancel" else "Click to snap  ·  Esc to cancel"
        g.drawCenteredString(font, hint, w / 2, y0 + fh - m - 14, white)
    }

    // ---- video: filmed by the companion, or from your own view ----------------------------------------

    /** The companion filming you: the kind of shot, while it's set up or rolling. */
    private var video: Kind? = null
    private var videoAt = 0L
    private var videoTicks = -1
    /** Recording from your own eyes (Dex in hand), started by asking for a video. */
    private var handVideo = false

    val filming: Boolean get() = video != null || handVideo

    /** "Take a video of me": it flies into position (like a photo), then films you until you say "stop". */
    fun takeCompanionVideo(kind: Kind) {
        if (busy || filming || ClipRecorder.recording) return
        val player = Minecraft.getInstance().player ?: return
        if (CompanionClient.mine(player) == null) { takeHandVideo(); return }
        if (!canSend()) { Commands.reply("Videos need this world's Dex network."); return }
        ClientPlayNetworking.send(CompanionCommandPayload("photo:${kind.id}"))
        Commands.reply("Lights, camera... I'll fly into position! Say stop when you're done.")
        dev.dexbuddy.client.antics.Tricks.used("video")
        video = kind; videoAt = System.currentTimeMillis() + 2_400; videoTicks = -1
    }

    /** A video of what you see (Dex in hand). */
    fun takeHandVideo() {
        if (busy || filming || ClipRecorder.recording) return
        if (!canSend()) { Commands.reply("Videos need this world's Dex network."); return }
        Commands.reply("Recording what you see! Say stop when you're done.")
        dev.dexbuddy.client.antics.Tricks.used("video")
        handVideo = true
        ClipRecorder.start()
    }

    /** "Stop": ends the video (the clip is saved to your gallery). */
    fun stopVideo() {
        if (ClipRecorder.recording) ClipRecorder.stop()
        endVideo()
    }

    private fun endVideo() {
        if (video != null && canSend() && !tripodVideo) ClientPlayNetworking.send(CompanionCommandPayload("photo_done"))
        video = null; videoTicks = -1; handVideo = false; tripodVideo = false
    }

    /** A video on tripod mode: it films from where it's parked (no flying into position). */
    private var tripodVideo = false

    fun takeTripodVideo() {
        if (busy || filming || ClipRecorder.recording) return
        val player = Minecraft.getInstance().player ?: return
        if (CompanionClient.mine(player) == null) return
        Commands.reply("Rolling in three, two, one... action! Say stop when you're done.")
        dev.dexbuddy.client.antics.Tricks.used("video"); dev.dexbuddy.client.antics.Tricks.used("tripod")
        video = Kind.PORTRAIT; tripodVideo = true; videoAt = System.currentTimeMillis() + 2_000; videoTicks = -1
    }

    private fun tickVideo(mc: Minecraft) {
        if (handVideo && !ClipRecorder.recording) { handVideo = false; return }
        val kind = video ?: return
        val player = mc.player ?: run { endVideo(); return }
        val b = CompanionClient.mine(player) ?: run { stopVideo(); return }
        // Keep its eyes on you the whole time; your own view stays yours (frames are drawn from its eyes).
        aim(b, player, kind)
        if (videoTicks < 0) {
            if (System.currentTimeMillis() < videoAt || VoicePlayer.speaking) return
            videoTicks = 0
            ClipRecorder.start(companion = true)
            return
        }
        videoTicks++
        if (videoTicks > 2 && !ClipRecorder.recording) endVideo() // reached the time limit
    }

    /** Tripod mode: a photo from where it's parked, after a countdown (no flying into position). */
    fun takeTripodPhoto() {
        if (busy || filming) return
        val player = Minecraft.getInstance().player ?: return
        if (CompanionClient.mine(player) == null) return
        Commands.reply(listOf("Three, two, one... cheese!", "Hold that pose! Three, two, one... snap!", "Smile! Three, two, one... cheese!").random())
        val now = System.currentTimeMillis()
        shot = Shot(Kind.PORTRAIT, now + 2_000, now + 8_000)
    }

    /** Hand photo: what you're looking at, once it has said "cheese". */
    fun takeHandPhoto() {
        if (busy) return
        Commands.reply(listOf("Say cheese!", "Smile! Snap!", "Hold still... cheese!").random())
        val now = System.currentTimeMillis()
        shot = Shot(Kind.HAND, now + 900, now + 5_000)
    }

    /** Companion photo of you: it flies into position and counts down first. */
    fun takeCompanionPhoto(kind: Kind) {
        if (busy) return
        val player = Minecraft.getInstance().player ?: return
        if (CompanionClient.mine(player) == null) { takeHandPhoto(); return }
        if (!canSend()) { Commands.reply("My camera needs this world's Dex network. Hold me and I'll snap what you see!"); return }
        ClientPlayNetworking.send(CompanionCommandPayload("photo:${kind.id}"))
        Commands.reply(when (kind) {
            Kind.CLOSE -> "Ooh, a close-up! Look at me... three, two, one, cheese!"
            Kind.WIDE -> "A wide shot! Let me get some height... three, two, one, cheese!"
            else -> "A portrait! Stepping back... three, two, one, cheese!"
        })
        val now = System.currentTimeMillis()
        shot = Shot(kind, now + 2_200, now + 9_000)
    }

    private fun canSend() = try { ClientPlayNetworking.canSend(PhotoChunkPayload.TYPE) } catch (_: Exception) { false }

    private fun tick(mc: Minecraft) {
        val player = mc.player ?: run { shot = null; viewfinder = false; if (captureTicks >= 0) restore(mc); return }
        val s = shot
        val now = System.currentTimeMillis()
        if (s != null && captureTicks < 0) {
            // Wait until it has finished its "cheese!" (or give up waiting).
            if (now < s.notBefore || ((VoicePlayer.speaking || mc.screen != null) && now < s.giveUpAt)) return
            savedHideGui = mc.options.hideGui
            savedCameraType = mc.options.cameraType
            mc.options.hideGui = true
            if (s.kind != Kind.HAND) CompanionClient.mine(player)?.let { b ->
                // Look straight out of its eyes, even if you're in third person (emotes often switch you there).
                mc.options.cameraType = net.minecraft.client.CameraType.FIRST_PERSON
                aim(b, player); mc.setCameraEntity(b)
            }
            captureTicks = 0
            framesReady = 0
            return
        }
        if (captureTicks < 0 || s == null) return
        // Keep looking through its eyes until a couple of frames have been drawn from there.
        if (s.kind != Kind.HAND) {
            val b = CompanionClient.mine(player)
            if (b != null) { aim(b, player); if (mc.cameraEntity !== b) mc.setCameraEntity(b) }
        }
        captureTicks++
        // The camera eases its height from the old eye height to the new one (half the gap per tick):
        // give it ~6 ticks to settle at the companion's eyes, and make sure frames were drawn.
        val settle = if (s.kind == Kind.HAND) 1 else 7
        if ((framesReady < 2 || captureTicks < settle) && captureTicks < 30) return
        DexBuddyClient.LOG.info("[dexbuddy] photo capture: {} frames from {} after {} ticks", framesReady, mc.cameraEntity?.javaClass?.simpleName, captureTicks)
        val img = try { Screenshot.takeScreenshot(mc.mainRenderTarget) } catch (e: Exception) { null }
        restore(mc)
        shot = null
        if (s.kind != Kind.HAND && canSend()) ClientPlayNetworking.send(CompanionCommandPayload("photo_done"))
        if (img == null) { Commands.reply("Oops, my lens fogged up. Try again?"); return }
        flashAt = now
        mc.soundManager.play(net.minecraft.client.resources.sounds.SimpleSoundInstance.forUI(SoundEvents.UI_CARTOGRAPHY_TABLE_TAKE_RESULT, 1.6f, 0.9f))
        if (s.kind != Kind.HAND) {
            val tricks = dev.dexbuddy.client.antics.Tricks
            if (Commands.tripod) { tricks.used("tripod"); tricks.suggest("video", "You can also say record, and I'll film you from here!") }
            else { tricks.used("photo"); tricks.nudge("companion_photo", 2, "tripod", "Psst! Say tripod mode and I'll stay put while you pose. Then just say cheese!") }
        }
        develop(img, s.kind)
    }

    private var savedCameraType = net.minecraft.client.CameraType.FIRST_PERSON

    private fun restore(mc: Minecraft) {
        mc.options.hideGui = savedHideGui
        mc.options.cameraType = savedCameraType
        mc.player?.let { mc.setCameraEntity(it) }
        captureTicks = -1
    }

    /** Points the companion's eyes straight at the Trainer's face. */
    private fun aim(b: DexBuddyEntity, player: net.minecraft.world.entity.player.Player, kind: Kind? = shot?.kind) {
        val from = b.eyePosition
        // Close-ups frame the face; portraits and wide shots frame the whole body.
        val to = if (kind == Kind.CLOSE) player.eyePosition else player.position().add(0.0, 1.0, 0.0)
        val dx = to.x - from.x; val dy = to.y - from.y; val dz = to.z - from.z
        val yaw = (Math.toDegrees(atan2(dz, dx)) - 90.0).toFloat()
        val pitch = (-Math.toDegrees(atan2(dy, sqrt(dx * dx + dz * dz)))).toFloat()
        b.yRot = yaw; b.yRotO = yaw; b.yHeadRot = yaw; b.yHeadRotO = yaw; b.yBodyRot = yaw
        b.xRot = pitch; b.xRotO = pitch
    }

    /** Crops to 16:9, shrinks to 640x360, saves a JPEG locally and to the server, shows a polaroid. */
    private fun develop(img: NativeImage, kind: Kind) {
        // All the pixel work happens off the game thread, so taking a photo doesn't make the game hitch.
        Thread({
            try {
                val w = img.width; val h = img.height
                val cropH = minOf(h, w * 9 / 16); val cropW = cropH * 16 / 9
                val x0 = (w - cropW) / 2; val y0 = (h - cropH) / 2
                val full = BufferedImage(cropW, cropH, BufferedImage.TYPE_INT_RGB)
                try {
                    for (y in 0 until cropH) for (x in 0 until cropW) {
                        val abgr = img.getPixelRGBA(x0 + x, y0 + y)
                        full.setRGB(x, y, ((abgr and 0xFF) shl 16) or (abgr and 0xFF00) or ((abgr shr 16) and 0xFF))
                    }
                } finally { img.close() }
                val small = BufferedImage(OUT_W, OUT_H, BufferedImage.TYPE_INT_RGB)
                small.createGraphics().apply {
                    setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BICUBIC)
                    setRenderingHint(RenderingHints.KEY_RENDERING, RenderingHints.VALUE_RENDER_QUALITY)
                    drawImage(full, 0, 0, OUT_W, OUT_H, null); dispose()
                }
                val jpeg = toJpeg(small, 0.85f)
                val name = LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyy-MM-dd_HH.mm.ss.SSS")) + "_${kind.id}.jpg"
                val dir = Minecraft.getInstance().gameDirectory.toPath().resolve("screenshots").resolve("dexbuddy")
                Files.createDirectories(dir)
                Files.write(dir.resolve(name), jpeg)
                Minecraft.getInstance().execute { showPreview(small); upload(jpeg, kind) }
            } catch (e: Exception) {
                DexBuddyClient.LOG.warn("[dexbuddy] couldn't develop photo: {}", e.toString())
            }
        }, "dexbuddy-photo").apply { isDaemon = true }.start()
    }

    private fun toJpeg(img: BufferedImage, quality: Float): ByteArray {
        val out = ByteArrayOutputStream()
        val writer = ImageIO.getImageWritersByFormatName("jpg").next()
        ImageIO.createImageOutputStream(out).use { ios ->
            writer.output = ios
            val param = writer.defaultWriteParam.apply { compressionMode = ImageWriteParam.MODE_EXPLICIT; compressionQuality = quality }
            writer.write(null, IIOImage(img, null, null), param)
        }
        writer.dispose()
        return out.toByteArray()
    }

    private fun upload(jpeg: ByteArray, kind: Kind) {
        if (!canSend()) { previewNote = "Saved to screenshots/dexbuddy"; return }
        val id = UUID.randomUUID().toString()
        val total = (jpeg.size + PhotoChunkPayload.CHUNK - 1) / PhotoChunkPayload.CHUNK
        previewNote = "Saving..."
        for (i in 0 until total) {
            val part = jpeg.copyOfRange(i * PhotoChunkPayload.CHUNK, minOf(jpeg.size, (i + 1) * PhotoChunkPayload.CHUNK))
            ClientPlayNetworking.send(PhotoChunkPayload(id, i, total, kind.id, part))
        }
        DexBuddyClient.LOG.info("[dexbuddy] photo taken ({}, {} KB)", kind.id, jpeg.size / 1024)
    }

    private fun showPreview(img: BufferedImage) {
        val ni = NativeImage(img.width, img.height, false)
        for (y in 0 until img.height) for (x in 0 until img.width) {
            val rgb = img.getRGB(x, y)
            ni.setPixelRGBA(x, y, (0xFF shl 24) or ((rgb and 0xFF) shl 16) or (rgb and 0xFF00) or ((rgb shr 16) and 0xFF))
        }
        previewTex?.close()
        val tex = DynamicTexture(ni)
        val loc = DexBuddyMod.id("photo_preview")
        Minecraft.getInstance().textureManager.register(loc, tex)
        previewTex = tex; preview = loc
        previewUntil = System.currentTimeMillis() + 5_000
    }

    /** The white flash, then a little polaroid of the shot in the top-right corner. */
    fun render(g: GuiGraphics, @Suppress("UNUSED_PARAMETER") delta: DeltaTracker) {
        if (viewfinder) renderViewfinder(g)
        else if (Commands.tripod && !filming && !busy) {
            // Tripod mode: what you can say.
            val font = Minecraft.getInstance().font
            val label = "TRIPOD  ·  say \"cheese\" for a photo, \"record\" for a video, \"done\" to finish"
            val w = font.width(label) + 12
            val x = (g.guiWidth() - w) / 2
            g.fill(x, 8, x + w, 22, 0xB0000000.toInt())
            g.drawString(font, label, x + 6, 11, 0xFFFFE27A.toInt(), false)
        }
        else if (filming) {
            // Filming by voice: a small REC badge (the HUD isn't in the clip).
            val font = Minecraft.getInstance().font
            val label = if (ClipRecorder.recording) "REC 0:%02d / 0:%02d  ·  say \"stop\"".format(ClipRecorder.seconds, ClipRecorder.MAX_SECONDS) else "Getting into position..."
            val w = font.width(label) + 22
            val x = (g.guiWidth() - w) / 2
            g.fill(x, 8, x + w, 22, 0xB0000000.toInt())
            if (ClipRecorder.recording && (System.currentTimeMillis() / 500) % 2 == 0L) g.fill(x + 6, 12, x + 12, 18, 0xFFFF4040.toInt())
            g.drawString(font, label, x + 16, 11, 0xFFFFFFFF.toInt(), false)
        }
        val now = System.currentTimeMillis()
        val since = now - flashAt
        if (since < 350) {
            val a = ((1f - since / 350f) * 230).toInt().coerceIn(0, 255)
            g.fill(0, 0, g.guiWidth(), g.guiHeight(), (a shl 24) or 0xFFFFFF)
        }
        val loc = preview ?: return
        if (now > previewUntil) return
        val font = Minecraft.getInstance().font
        val w = 128; val h = 72
        val slide = ((now - (previewUntil - 5_000)) / 250f).coerceIn(0f, 1f)
        val x = g.guiWidth() - 12 - (w + 8) * slide.toDouble().let { 1 - (1 - it) * (1 - it) }.toInt()
        val y = 12
        g.pose().pushPose()
        g.pose().translate(x.toFloat() + w / 2f, y.toFloat() + h / 2f, 0f)
        g.pose().mulPose(com.mojang.math.Axis.ZP.rotationDegrees(3f))
        g.pose().translate(-(w / 2f), -(h / 2f), 0f)
        g.fill(-4, -4, w + 4, h + 16, 0xFFF8F4EC.toInt())
        g.blit(loc, 0, 0, w, h, 0f, 0f, OUT_W, OUT_H, OUT_W, OUT_H)
        g.drawString(font, previewNote, 0, h + 4, 0xFF444444.toInt(), false)
        g.pose().popPose()
    }
}
