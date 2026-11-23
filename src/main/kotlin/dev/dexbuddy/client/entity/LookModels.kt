package dev.dexbuddy.client.entity

import com.mojang.blaze3d.platform.NativeImage
import com.mojang.blaze3d.vertex.PoseStack
import com.mojang.blaze3d.vertex.VertexConsumer
import dev.dexbuddy.DexBuddyMod
import dev.dexbuddy.entity.DexBuddyEntity
import dev.dexbuddy.entity.Look
import net.minecraft.client.Minecraft
import net.minecraft.client.model.EntityModel
import net.minecraft.client.model.geom.ModelPart
import net.minecraft.client.model.geom.PartPose
import net.minecraft.client.model.geom.builders.CubeListBuilder
import net.minecraft.client.model.geom.builders.LayerDefinition
import net.minecraft.client.model.geom.builders.MeshDefinition
import net.minecraft.client.model.geom.builders.PartDefinition
import net.minecraft.client.renderer.texture.DynamicTexture
import net.minecraft.resources.ResourceLocation
import kotlin.math.PI
import kotlin.math.ceil
import kotlin.math.roundToInt
import kotlin.math.sin

/**
 * The five companion bodies. Each is a stack of colored boxes described below in "up is +y, front is +z"
 * coordinates (the same ones the model picker page used), turned into a Minecraft model at runtime.
 * Every box gets a solid-color patch on a generated texture, and the screen box gets a pixel face drawn
 * on its front, once per expression (idle, blink, talking, happy, bonked).
 */
enum class Face { IDLE, BLINK, TALK0, TALK1, TALK2, HAPPY, BONK }

/** [style]: dot (default), star, fangs, shades, visor, sleepy, fierce, oneeye, happy, ghost. */
class FaceSpec(
    val bg: Int, val eye: Int, val mouth: Int = eye, val shine: Int = 0xFFFFFFFF.toInt(),
    val blush: Boolean = false, val cheek: Int = 0xFFFF7AA2.toInt(), val tall: Boolean = false,
    val style: String = "dot",
)

class BoxSpec(val w: Float, val h: Float, val d: Float, val x: Float, val y: Float, val z: Float, val color: Int, val face: FaceSpec?) {
    var u = 0; var v = 0
}

class PartSpec(val name: String, val x: Float = 0f, val y: Float = 0f, val z: Float = 0f) {
    val boxes = mutableListOf<BoxSpec>()
    val children = mutableListOf<PartSpec>()
    fun box(w: Number, h: Number, d: Number, x: Number, y: Number, z: Number, color: String, face: FaceSpec? = null) {
        boxes += BoxSpec(w.toFloat(), h.toFloat(), d.toFloat(), x.toFloat(), y.toFloat(), z.toFloat(), hex(color), face)
    }
    fun part(name: String, x: Number = 0, y: Number = 0, z: Number = 0, block: PartSpec.() -> Unit): PartSpec =
        PartSpec(name, x.toFloat(), y.toFloat(), z.toFloat()).apply(block).also { children += it }
    fun all(): List<PartSpec> = listOf(this) + children.flatMap { it.all() }
}

fun hex(s: String): Int = (0xFF000000L or s.removePrefix("#").toLong(16)).toInt()

class AnimState(val t: Float, val talk: Boolean, val happy: Boolean)

/** One baked body: its parts by name, its texture layout and its little animations. */
class LookModel(val look: Look, val spec: PartSpec, private val anim: (Map<String, ModelPart>, AnimState) -> Unit) {
    val texW = 64
    var texH = 0
        private set
    val root: ModelPart
    val parts = HashMap<String, ModelPart>()

    init {
        pack()
        val mesh = MeshDefinition()
        add(mesh.root, spec, true)
        val baked = LayerDefinition.create(mesh, texW, texH).bakeRoot()
        root = baked.getChild(spec.name)
        collect(root, spec)
    }

    /** Shelf-packs every box's unwrapped patch onto the texture. */
    private fun pack() {
        val boxes = spec.all().flatMap { it.boxes }.sortedByDescending { it.d + it.h }
        var x = 0; var y = 0; var rowH = 0
        for (b in boxes) {
            val bw = ceil(2 * (b.d + b.w)).toInt() + 1
            val bh = ceil(b.d + b.h).toInt() + 1
            if (x + bw > texW) { x = 0; y += rowH; rowH = 0 }
            b.u = x; b.v = y
            x += bw; rowH = maxOf(rowH, bh)
        }
        texH = y + rowH
    }

    private fun add(parent: PartDefinition, p: PartSpec, isRoot: Boolean) {
        val cubes = CubeListBuilder.create()
        // Model space: y points down and the front faces -z, so flip both.
        for (b in p.boxes) cubes.texOffs(b.u, b.v).addBox(b.x - b.w / 2, -b.y - b.h / 2, -b.z - b.d / 2, b.w, b.h, b.d)
        val pose = if (isRoot) PartPose.offset(0f, 16f, 0f) else PartPose.offset(p.x, -p.y, -p.z)
        val def = parent.addOrReplaceChild(p.name, cubes, pose)
        p.children.forEach { add(def, it, false) }
    }

    private fun collect(part: ModelPart, p: PartSpec) {
        parts[p.name] = part
        p.children.forEach { collect(part.getChild(it.name), it) }
    }

    fun animate(s: AnimState) = anim(parts, s)
}

/** One model object for the renderer; it draws whichever body the companion has. */
class LookEntityModel : EntityModel<DexBuddyEntity>() {
    var current: LookModel = LookModels.get(Look.DEFAULT)
    var talking = false

    override fun setupAnim(entity: DexBuddyEntity, limbSwing: Float, limbSwingAmount: Float, ageInTicks: Float, netHeadYaw: Float, headPitch: Float) {
        val root = current.root
        // Face where it's looking (the owner), with a small idle sway.
        root.yRot = Math.toRadians(netHeadYaw.toDouble()).toFloat() * 0.8f
        root.xRot = Math.toRadians(headPitch.toDouble()).toFloat() * 0.5f + sin(ageInTicks * 0.07f) * 0.05f
        root.zRot = sin(ageInTicks * 0.05f) * 0.06f
        // Punched (or a trick): a quick full spin and a wobble.
        val react = entity.reactTicks
        if (react > 0) {
            val p = 1f - react / DexBuddyEntity.REACT_TICKS.toFloat()
            root.yRot += p * (PI * 2).toFloat()
            root.zRot += sin(p * 20f) * 0.25f * (1 - p)
        }
        current.animate(AnimState(ageInTicks / 20f, talking, entity.happyTicks > 0))
    }

    override fun renderToBuffer(poseStack: PoseStack, buffer: VertexConsumer, packedLight: Int, packedOverlay: Int, color: Int) {
        current.root.render(poseStack, buffer, packedLight, packedOverlay, color)
    }
}

object LookModels {
    private val models = HashMap<Look, LookModel>()
    fun get(look: Look): LookModel = models.getOrPut(look) { build(look) }

    private val WHITE = "#FFFFFF"

    private fun build(look: Look): LookModel = when (look) {
        Look.PHONE -> LookModel(look, PartSpec("root").apply {
            box(9, 15, 1.6, 0, 0, 0, "#F2603B")
            box(8, 12.6, 0.2, 0, -0.4, 0.82, "#151A2B")
            box(7.6, 12, 0.05, 0, -0.4, 0.95, "#2A2F45", FaceSpec(hex("#2A2F45"), hex("#FFE066"), tall = true))
            box(1, 1, 0.3, 0, 6.8, 0.9, "#151A2B")
            box(2.4, 2.4, 0.4, 2.2, 5, -0.95, "#151A2B")
            part("handL", -5.6, -1, 0) { box(1.8, 1.8, 1.8, 0, 0, 0, "#FFD23F") }
            part("handR", 5.6, -1, 0) { box(1.8, 1.8, 1.8, 0, 0, 0, "#FFD23F") }
            for (s in listOf(-1, 1)) { box(1.2, 2.2, 1, 2.6 * s, 8.4, 0, "#FF9F1C"); box(1.2, 1.2, 1, 3.4 * s, 9.8, 0, "#FF9F1C") }
        }) { p, s ->
            val k = if (s.talk) 10f else 3f
            val amp = if (s.talk) 1.2f else 0.4f
            p["handL"]!!.y = 1f - sin(s.t * k) * amp
            p["handR"]!!.y = 1f - sin(s.t * k + PI.toFloat()) * amp
            p["root"]!!.zRot -= sin(s.t * 1.5f) * 0.06f
        }

        Look.CLASSIC -> LookModel(look, PartSpec("root").apply {
            box(10, 13, 3, 0, 0, 0, "#E8473F")
            box(10.2, 2.2, 3.2, 0, -5.4, 0, "#F4F4F4")
            box(4, 0.6, 0.3, 0, -5.4, 1.65, "#9AA3B5")
            box(8.8, 7, 0.3, 0, 2.2, 1.6, "#0D1628")
            box(8, 6, 0.05, 0, 2.2, 1.78, "#1B2A49", FaceSpec(hex("#1B2A49"), hex("#7FE8FF")))
            for ((name, s) in listOf("armL" to -1, "armR" to 1)) part(name, 5 * s, -0.5, 0) {
                box(3, 1.2, 1.2, 1.5 * s, 0, 0, "#FF9F1C"); box(1.2, 3, 1.2, 2.6 * s, 1.4, 0, "#FF9F1C"); box(2.6, 1.2, 1.2, 3.7 * s, 2.6, 0, "#FF9F1C")
            }
            for (s in listOf(-1, 1)) { box(1, 3, 1, 3 * s, 8, 0, "#E8473F"); box(1.6, 1.6, 1.6, 3 * s, 10, 0, WHITE) }
        }) { p, s ->
            val w = if (s.talk) sin(s.t * 14f) * 0.35f else sin(s.t * 2f) * 0.08f
            p["armL"]!!.zRot = w
            p["armR"]!!.zRot = -w
        }

        Look.FLIP -> LookModel(look, PartSpec("root").apply {
            box(11, 8, 3, 0, -3, 0, "#D8362F")
            box(9.4, 6.8, 0.3, 0, -3, 1.6, "#2D3A12")
            box(8.6, 6, 0.05, 0, -3, 1.78, "#9BBC0F", FaceSpec(hex("#9BBC0F"), hex("#0F380F"), shine = hex("#9BBC0F")))
            box(11, 1.2, 1.2, 0, 1, -1, "#9E2420")
            part("lid", 0, 1, -1) {
                box(11, 9, 1.5, 0, 4.5, 0, "#D8362F")
                box(4.4, 4.4, 0.4, -2.8, 5.8, 0.85, WHITE)
                box(3.4, 3.4, 0.6, -2.8, 5.8, 1.0, "#3FA9F5")
                box(1, 1, 0.3, -3.6, 6.6, 1.35, "#E6F6FF")
                box(1, 1, 0.4, 0.8, 7.4, 0.85, "#FF4D4D"); box(1, 1, 0.4, 2.4, 7.4, 0.85, "#FFD23F"); box(1, 1, 0.4, 4, 7.4, 0.85, "#5EDB6A")
            }
            box(2.6, 0.8, 0.3, -3, -6.2, 1.6, "#2A2A2A"); box(0.8, 2.6, 0.3, -3, -6.2, 1.62, "#2A2A2A")
        }) { p, s ->
            var open = 0.35f
            if (s.talk) open += (0.5f + 0.5f * sin(s.t * 14f)) * 0.45f
            if (s.happy) open += 0.25f + sin(s.t * 9f) * 0.15f
            p["lid"]!!.xRot = -open
        }

        Look.BALL -> LookModel(look, PartSpec("root").apply {
            part("body") {
                box(10.4, 1.2, 10.4, 0, 0, 0, "#23262E")
                box(10, 3, 10, 0, 2.1, 0, "#E8473F"); box(8, 1.6, 8, 0, 4.4, 0, "#E8473F"); box(5, 1, 5, 0, 5.7, 0, "#E8473F")
                box(10, 3, 10, 0, -2.1, 0, "#F4F4F4"); box(8, 1.6, 8, 0, -4.4, 0, "#F4F4F4"); box(5, 1, 5, 0, -5.7, 0, "#F4F4F4")
                box(5.6, 4.6, 0.6, 0, 0, 5.1, "#23262E")
                box(4.8, 3.8, 0.4, 0, 0, 5.35, WHITE)
                box(4, 3, 0.05, 0, 0, 5.58, "#1B2A49", FaceSpec(hex("#1B2A49"), hex("#7FE8FF")))
                box(1, 2, 1, 0, 7.2, 0, "#5EDB6A"); box(2, 1, 1, 0.8, 8.4, 0, "#5EDB6A")
                part("tail", 0, 0, -5.2) {
                    box(1.2, 1.2, 2.4, 0, 0, -1.2, "#FF9F1C"); box(1.2, 2.4, 1.2, 0, 1, -2.4, "#FF9F1C"); box(1.2, 1.2, 2, 0, 2.2, -3.4, "#FF9F1C")
                }
            }
        }) { p, s ->
            val sq = sin(s.t * (if (s.happy) 10f else 4f)) * (if (s.happy) 0.1f else 0.05f)
            p["body"]!!.apply { xScale = 1 - sq * 0.6f; yScale = 1 + sq; zScale = 1 - sq * 0.6f }
            p["tail"]!!.yRot = -sin(s.t * (if (s.talk) 12f else 3f)) * 0.5f
        }

        Look.PLUSH -> LookModel(look, PartSpec("root").apply {
            part("body") {
                box(11, 9, 8, 0, 0, 0, "#FFD54A")
                box(9.4, 10.4, 6.6, 0, 0, 0, "#FFD54A")
                box(12, 7.4, 6.4, 0, 0, 0, "#FFD54A")
                box(7.8, 5.8, 0.3, 0, 0.6, 4.02, "#E0A92E")
                box(7, 5.25, 0.05, 0, 0.6, 4.2, "#FFF6D8",
                    FaceSpec(hex("#FFF6D8"), hex("#3B2A20"), shine = hex("#FFF6D8"), blush = true, cheek = hex("#FF8FA8")))
                for ((name, s) in listOf("earL" to -1, "earR" to 1)) part(name, 3.2 * s, 5, 0) {
                    box(1.6, 2.6, 1.6, 0, 1.3, 0, "#FF9F1C"); box(1.6, 1.6, 1.6, 0.9 * s, 2.9, 0, "#FF9F1C"); box(1.6, 2, 1.6, 0.2 * s, 4.4, 0, "#FF9F1C")
                }
                for (s in listOf(-1, 1)) box(2.6, 1.2, 3, 2.6 * s, -5.6, 0.6, "#FF9F1C")
            }
        }) { p, s ->
            val sq = sin(s.t * (if (s.happy) 9f else 3f)) * (if (s.happy) 0.12f else 0.06f)
            p["body"]!!.apply { xScale = 1 - sq * 0.7f; yScale = 1 + sq; zScale = 1 - sq * 0.7f }
            val wig = 0.1f + sin(s.t * (if (s.talk) 12f else 2.5f)) * 0.12f
            p["earL"]!!.zRot = -wig
            p["earR"]!!.zRot = wig
        }

        else -> CostumeModels.build(look)
    }
}

/** Generated textures, one per body and expression, made the first time they're needed. */
object LookTextures {
    private const val S = 8 // texture pixels per model unit
    private val cache = HashMap<String, ResourceLocation>()

    fun get(model: LookModel, face: Face): ResourceLocation {
        val key = "${model.look.id}_${face.name.lowercase()}"
        return cache.getOrPut(key) {
            val loc = DexBuddyMod.id("generated/look_$key")
            Minecraft.getInstance().textureManager.register(loc, DynamicTexture(draw(model, face)))
            loc
        }
    }

    private fun abgr(argb: Int): Int =
        (argb and 0xFF00FF00.toInt()) or ((argb shr 16) and 0xFF) or ((argb and 0xFF) shl 16)

    private fun darker(argb: Int, f: Float): Int {
        val r = (((argb shr 16) and 0xFF) * f).toInt(); val g = (((argb shr 8) and 0xFF) * f).toInt(); val b = ((argb and 0xFF) * f).toInt()
        return (0xFF shl 24) or (r shl 16) or (g shl 8) or b
    }

    private fun draw(model: LookModel, face: Face): NativeImage {
        val img = NativeImage(model.texW * S, model.texH * S, true)
        fun fill(x0: Int, y0: Int, x1: Int, y1: Int, argb: Int) {
            val c = abgr(argb)
            for (y in y0.coerceAtLeast(0) until y1.coerceAtMost(img.height)) for (x in x0.coerceAtLeast(0) until x1.coerceAtMost(img.width)) img.setPixelRGBA(x, y, c)
        }
        fun rect(ux: Float, vy: Float, w: Float, h: Float) = intArrayOf((ux * S).roundToInt(), (vy * S).roundToInt(), ((ux + w) * S).roundToInt(), ((vy + h) * S).roundToInt())
        for (b in model.spec.all().flatMap { it.boxes }) {
            val u = b.u.toFloat(); val v = b.v.toFloat()
            val faces = listOf(
                rect(u + b.d, v, b.w, b.d), rect(u + b.d + b.w, v, b.w, b.d),
                rect(u, v + b.d, b.d, b.h), rect(u + b.d, v + b.d, b.w, b.h),
                rect(u + b.d + b.w, v + b.d, b.d, b.h), rect(u + 2 * b.d + b.w, v + b.d, b.w, b.h),
            )
            val edge = darker(b.color, 0.82f)
            for (r in faces) {
                fill(r[0], r[1], r[2], r[3], b.color)
                // A thin darker rim on each side gives the boxes a crisp, blocky look.
                if (r[2] - r[0] >= 4 && r[3] - r[1] >= 4 && b.face == null) {
                    fill(r[0], r[1], r[2], r[1] + 1, edge); fill(r[0], r[3] - 1, r[2], r[3], edge)
                    fill(r[0], r[1], r[0] + 1, r[3], edge); fill(r[2] - 1, r[1], r[2], r[3], edge)
                }
            }
            b.face?.let { drawFace(faces[3], it, face, ::fill) }
        }
        return img
    }

    /** The pixel face (16 wide, 12 tall; 24 tall on the phone with the face in the middle). */
    private fun drawFace(r: IntArray, f: FaceSpec, face: Face, fill: (Int, Int, Int, Int, Int) -> Unit) {
        val cols = 16f; val rows = if (f.tall) 24f else 12f; val oy = if (f.tall) 6 else 0
        val w = r[2] - r[0]; val h = r[3] - r[1]
        fill(r[0], r[1], r[2], r[3], f.bg)
        fun px(x: Int, y: Int, cw: Int = 1, ch: Int = 1, c: Int) {
            val yy = y + oy
            fill(r[0] + (x * w / cols).toInt(), r[1] + (yy * h / rows).toInt(), r[0] + ((x + cw) * w / cols).toInt(), r[1] + ((yy + ch) * h / rows).toInt(), c)
        }
        val e = f.eye; val m = f.mouth; val st = f.style
        val dark = hex("#111111")
        when {
            face == Face.BONK -> { px(4, 3, c = e); px(5, 4, c = e); px(4, 5, c = e); px(11, 3, c = e); px(10, 4, c = e); px(11, 5, c = e) }
            // Shades and the visor stay on whatever the mood (the visor still blinks).
            st == "shades" -> { px(3, 3, 4, 3, dark); px(9, 3, 4, 3, dark); px(7, 3, 2, 1, dark); px(3, 3, c = hex("#8A8A8A")); px(9, 3, c = hex("#8A8A8A")) }
            st == "visor" -> { if (face == Face.BLINK) px(2, 4, 12, 1, e) else { px(2, 3, 12, 3, e); px(3, 3, 2, 1, 0xFFFFFFFF.toInt()) } }
            face == Face.HAPPY || st == "happy" -> for (ex in intArrayOf(4, 10)) { px(ex - 1, 4, c = e); px(ex, 3, 2, 1, e); px(ex + 2, 4, c = e) }
            face == Face.BLINK || st == "sleepy" -> for (ex in intArrayOf(4, 10)) { if (!(st == "oneeye" && ex == 4)) px(ex, 5, 2, 1, e) }
            else -> {
                for (ex in intArrayOf(4, 10)) when {
                    st == "oneeye" && ex == 4 -> {} // hidden under the fringe
                    st == "star" -> { px(ex - 1, 4, 4, 1, e); px(ex, 3, 2, 3, e); px(ex, 2, c = e); px(ex + 1, 6, c = e); px(ex, 3, c = f.shine) }
                    st == "ghost" -> { px(ex - 1, 2, 3, 4, e); px(ex - 1, 2, c = f.shine) }
                    else -> { px(ex, 3, 2, 3, e); px(ex, 3, c = f.shine) }
                }
                if (st == "fierce") { px(3, 1, 2, 1, e); px(5, 2, c = e); px(11, 1, 2, 1, e); px(10, 2, c = e) }
            }
        }
        when (face) {
            Face.TALK0 -> px(6, 8, 4, 1, m)
            Face.TALK1 -> px(7, 8, 2, 1, m)
            Face.TALK2 -> { px(7, 8, 2, 2, m); px(7, 9, 2, 1, hex("#FF7AA2")) }
            Face.HAPPY -> { px(6, 8, 4, 1, m); px(7, 9, 2, 1, m) }
            Face.BONK -> { for (x in intArrayOf(5, 7, 9)) px(x, 9, c = m); for (x in intArrayOf(6, 8, 10)) px(x, 8, c = m) }
            else -> when (st) {
                "fangs" -> { px(6, 8, 4, 1, m); px(6, 9, c = 0xFFFFFFFF.toInt()); px(9, 9, c = 0xFFFFFFFF.toInt()) }
                "ghost" -> px(7, 8, 2, 2, m)
                "sleepy" -> px(7, 9, 2, 1, m)
                else -> { px(6, 8, c = m); px(7, 9, 2, 1, m); px(9, 8, c = m) }
            }
        }
        if (face == Face.HAPPY || f.blush) { px(1, 7, 2, 1, f.cheek); px(13, 7, 2, 1, f.cheek) }
    }
}
