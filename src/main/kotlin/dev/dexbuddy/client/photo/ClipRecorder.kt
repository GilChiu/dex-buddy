package dev.dexbuddy.client.photo

import com.mojang.blaze3d.platform.NativeImage
import dev.dexbuddy.client.DexBuddyClient
import dev.dexbuddy.client.antics.Commands
import dev.dexbuddy.net.PhotoChunkPayload
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking
import net.minecraft.client.Minecraft
import net.minecraft.client.Screenshot
import net.minecraft.client.resources.sounds.SimpleSoundInstance
import net.minecraft.sounds.SoundEvents
import java.awt.image.BufferedImage
import java.io.ByteArrayOutputStream
import java.io.DataOutputStream
import java.util.UUID
import java.util.concurrent.Executors
import javax.imageio.IIOImage
import javax.imageio.ImageIO
import javax.imageio.ImageWriteParam

/**
 * Video clips with the Dex camera: hold the shutter in the viewfinder to record (up to [MAX_SECONDS]),
 * let go to stop. Frames are the world as you see it (no HUD or hand), 10 a second at 480x270, saved as
 * small JPEGs and sent to the gallery like a photo. Silent. Client thread, encoding on a worker.
 */
object ClipRecorder {
    const val FPS = 10
    const val MAX_SECONDS = 15
    private const val W = 480
    private const val H = 270

    var recording = false; private set
    private var startedAt = 0L
    private var lastFrameAt = 0L
    private val frames = ArrayList<ByteArray>()
    private val queuedFrames = java.util.concurrent.atomic.AtomicInteger()
    private var queued: Int
        get() = queuedFrames.get()
        set(v) { queuedFrames.set(v) }
    private val encoder = Executors.newSingleThreadExecutor { r -> Thread(r, "dexbuddy-clip").apply { isDaemon = true } }

    /** Chunks waiting to go to the server (sent a few per tick so a clip doesn't flood the connection). */
    private val outbox = ArrayDeque<PhotoChunkPayload>()

    val seconds: Int get() = if (recording) ((System.currentTimeMillis() - startedAt) / 1000).toInt() else 0

    fun available(): Boolean = try { ClientPlayNetworking.canSend(PhotoChunkPayload.TYPE) } catch (_: Exception) { false }

    fun register() {
        // Frames are grabbed at the end of the world pass, before the HUD and your hand are drawn.
        net.fabricmc.fabric.api.client.rendering.v1.WorldRenderEvents.LAST.register { capture() }
        ClientTickEvents.END_CLIENT_TICK.register { mc ->
            repeat(4) { outbox.removeFirstOrNull()?.let { ClientPlayNetworking.send(it) } }
            if (recording && (mc.player == null || System.currentTimeMillis() - startedAt >= MAX_SECONDS * 1000L)) stop()
        }
    }

    /** Filming from the companion's eyes (frames are drawn from there, your own view stays yours). */
    @Volatile var fromCompanion = false; private set
    /** True while the extra companion-view frame is being drawn. */
    @Volatile var drawingCompanion = false; private set

    /**
     * Called at the start of every frame. When a companion-view frame is due, the world is drawn once
     * from its eyes (first person, its eye height) and grabbed; then everything is put back and the
     * normal frame is drawn from your eyes, over it.
     */
    @JvmStatic
    fun companionFrame(gr: net.minecraft.client.renderer.GameRenderer, dt: net.minecraft.client.DeltaTracker) {
        if (!recording || !fromCompanion || drawingCompanion) return
        if (System.currentTimeMillis() - lastFrameAt < 1000L / FPS) return
        val mc = Minecraft.getInstance()
        val p = mc.player ?: return
        val b = dev.dexbuddy.client.entity.CompanionClient.mine(p) ?: return
        val cam = gr.mainCamera as dev.dexbuddy.mixin.CameraAccessor
        val eh = cam.`dexbuddy$getEyeHeight`(); val eho = cam.`dexbuddy$getEyeHeightOld`()
        val type = mc.options.cameraType
        try {
            mc.options.cameraType = net.minecraft.client.CameraType.FIRST_PERSON
            mc.setCameraEntity(b)
            cam.`dexbuddy$setEyeHeight`(b.eyeHeight); cam.`dexbuddy$setEyeHeightOld`(b.eyeHeight)
            drawingCompanion = true
            gr.renderLevel(dt)
        } catch (e: Exception) {
            DexBuddyClient.LOG.warn("[dexbuddy] companion video frame failed: {}", e.toString())
        } finally {
            drawingCompanion = false
            mc.setCameraEntity(p)
            mc.options.cameraType = type
            cam.`dexbuddy$setEyeHeight`(eh); cam.`dexbuddy$setEyeHeightOld`(eho)
        }
    }

    fun start(companion: Boolean = false) {
        if (recording) return
        fromCompanion = companion
        synchronized(frames) { frames.clear() }
        queued = 0
        recording = true
        startedAt = System.currentTimeMillis(); lastFrameAt = 0L
        Minecraft.getInstance().soundManager.play(SimpleSoundInstance.forUI(SoundEvents.UI_BUTTON_CLICK.value(), 1.8f, 0.6f))
    }

    fun stop() {
        if (!recording) return
        recording = false
        val mc = Minecraft.getInstance()
        mc.soundManager.play(SimpleSoundInstance.forUI(SoundEvents.UI_CARTOGRAPHY_TABLE_TAKE_RESULT, 1.4f, 0.9f))
        // After every queued frame is encoded, bundle and send them.
        encoder.execute {
            val all = synchronized(frames) { frames.toList() }
            if (all.size < 3) { mc.execute { Commands.reply("That clip was too short. Hold the shutter a bit longer!") }; return@execute }
            val out = ByteArrayOutputStream()
            DataOutputStream(out).use { d ->
                d.writeBytes("DXC1"); d.writeInt(FPS); d.writeInt(all.size)
                for (f in all) { d.writeInt(f.size); d.write(f) }
            }
            val bytes = out.toByteArray()
            mc.execute { upload(bytes, all.size) }
        }
    }

    private fun capture() {
        if (!recording) return
        // Companion videos only take the frame drawn from its eyes.
        if (fromCompanion && !drawingCompanion) return
        val now = System.currentTimeMillis()
        if (now - lastFrameAt < 1000L / FPS) return
        lastFrameAt = now
        if (queued > FPS * 3) return // the encoder is behind: skip a frame rather than pile up memory
        val mc = Minecraft.getInstance()
        val full = try { Screenshot.takeScreenshot(mc.mainRenderTarget) } catch (_: Exception) { return }
        try {
            // Centre 16:9, shrunk on the GPU-side image; only the small copy leaves the render thread.
            val cropH = minOf(full.height, full.width * 9 / 16); val cropW = cropH * 16 / 9
            val small = NativeImage(W, H, false)
            full.resizeSubRectTo((full.width - cropW) / 2, (full.height - cropH) / 2, cropW, cropH, small)
            val px = IntArray(W * H)
            for (y in 0 until H) for (x in 0 until W) px[y * W + x] = small.getPixelRGBA(x, y)
            small.close()
            queuedFrames.incrementAndGet()
            encoder.execute {
                try {
                    val img = BufferedImage(W, H, BufferedImage.TYPE_INT_RGB)
                    for (i in px.indices) { val p = px[i]; px[i] = ((p and 0xFF) shl 16) or (p and 0xFF00) or ((p shr 16) and 0xFF) }
                    img.setRGB(0, 0, W, H, px, 0, W)
                    val jpeg = toJpeg(img, 0.72f)
                    synchronized(frames) { frames += jpeg }
                } finally { queuedFrames.decrementAndGet() }
            }
        } finally { full.close() }
    }

    private fun toJpeg(img: BufferedImage, quality: Float): ByteArray {
        val out = ByteArrayOutputStream()
        val writer = ImageIO.getImageWritersByFormatName("jpg").next()
        ImageIO.createImageOutputStream(out).use { ios ->
            writer.output = ios
            writer.write(null, IIOImage(img, null, null), writer.defaultWriteParam.apply { compressionMode = ImageWriteParam.MODE_EXPLICIT; compressionQuality = quality })
        }
        writer.dispose()
        return out.toByteArray()
    }

    private fun upload(bytes: ByteArray, count: Int) {
        if (!available()) { Commands.reply("Clips need this world's Dex network."); return }
        val id = UUID.randomUUID().toString()
        val c = PhotoChunkPayload.CHUNK
        val total = (bytes.size + c - 1) / c
        for (i in 0 until total) outbox.addLast(PhotoChunkPayload(id, i, total, "clip", bytes.copyOfRange(i * c, minOf(bytes.size, (i + 1) * c))))
        DexBuddyClient.LOG.info("[dexbuddy] clip recorded ({} frames, {} KB)", count, bytes.size / 1024)
        Minecraft.getInstance().player?.displayClientMessage(net.minecraft.network.chat.Component.literal("Clip saved: ${(count + FPS - 1) / FPS}s · uploading to your gallery..."), true)
    }
}
