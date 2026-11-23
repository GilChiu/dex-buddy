package dev.dexbuddy.client.entity

import dev.dexbuddy.entity.Look
import net.minecraft.client.model.geom.ModelPart
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.sin

/**
 * The twenty collectible costumes (from the costume shop page), all built on one Dex body: a chunky
 * device with a screen face and two little hands, plus each costume's own pieces. Same coordinates as
 * the other looks: up is +y, front is +z.
 */
object CostumeModels {
    private const val WHITE = "#FFFFFF"

    /** The shared body. Hands are parts "handL"/"handR" so they can bob. */
    private fun PartSpec.base(body: String, rim: String, screen: String, face: FaceSpec, hand: String = rim, hands: Boolean = true) {
        box(10, 11, 4, 0, 0, 0, body)
        box(11, 9, 3.6, 0, 0, 0, body)
        box(8.8, 7.4, 0.3, 0, 1, 2.02, rim)
        box(8, 6, 0.05, 0, 1, 2.2, screen, face)
        if (hands) {
            part("handL", -6.4, -1.6, 0) { box(1.8, 1.8, 1.8, 0, 0, 0, hand) }
            part("handR", 6.4, -1.6, 0) { box(1.8, 1.8, 1.8, 0, 0, 0, hand) }
        }
    }

    private fun face(bg: String, eye: String, mouth: String = eye, style: String = "dot", blush: Boolean = false, cheek: String = "#FF7AA2", shine: String = WHITE) =
        FaceSpec(hex(bg), hex(eye), hex(mouth), hex(shine), blush, hex(cheek), style = style)

    /** Little cubes on a ring around the body (sparkles, glitter, petals). */
    private fun PartSpec.ring(name: String, n: Int, r: Double, y: Double, color: String, size: Double, flat: Boolean = false) = part(name, 0, y, 0) {
        for (i in 0 until n) {
            val a = i * 2 * PI / n
            box(size, if (flat) size * 0.3 else size, size, cos(a) * r, sin(i * 1.7) * 1.5, sin(a) * r, color)
        }
    }

    /** Hands bob slowly, or fast while talking (model y points down). */
    private fun bob(p: Map<String, ModelPart>, s: AnimState) {
        val k = if (s.talk) 10f else 3f
        val amp = if (s.talk) 1.1f else 0.35f
        p["handL"]?.y = 1.6f - sin(s.t * k) * amp
        p["handR"]?.y = 1.6f - sin(s.t * k + PI.toFloat()) * amp
    }

    private fun model(look: Look, build: PartSpec.() -> Unit, anim: (Map<String, ModelPart>, AnimState) -> Unit = { _, _ -> }) =
        LookModel(look, PartSpec("root").apply(build)) { p, s -> bob(p, s); anim(p, s) }

    fun build(look: Look): LookModel = when (look) {
        Look.BUBBLEGUM -> model(look, {
            base("#FF8FC8", "#C2185B", "#FFF0F7", face("#FFF0F7", "#FF4FA0", "#C2185B", blush = true, cheek = "#FFB3D9"))
            part("bow", 0, 6.4, 0.6) {
                box(3.4, 2.6, 1.6, -2.2, 0, 0, "#FF4FA0"); box(3.4, 2.6, 1.6, 2.2, 0, 0, "#FF4FA0")
                box(1.4, 1.6, 1.9, 0, 0, 0, "#C2185B"); box(0.9, 1.6, 0.8, -0.9, -1.4, 0.3, "#FF4FA0"); box(0.9, 1.6, 0.8, 0.9, -1.4, 0.3, "#FF4FA0")
            }
            ring("ring", 5, 7.5, 2.0, WHITE, 0.7)
        }) { p, s -> p["bow"]!!.zRot = sin(s.t * 3f) * 0.08f; p["ring"]!!.yRot = s.t * 0.8f }

        Look.GLAM_STAR -> model(look, {
            base("#C9A7FF", "#6B3FD6", "#2A1B4A", face("#2A1B4A", "#FFE066", "#FF8FC8", style = "star"))
            box(11.8, 0.8, 1.2, 0, 6, 0, "#333333")
            for (s in listOf(-1, 1)) box(1, 2.6, 2.6, 5.9 * s, 2.6, 0, "#333333")
            box(0.6, 0.6, 3.2, 5.6, 0.8, 2, "#333333"); box(1.2, 1.2, 1.2, 5, 0.6, 3.6, "#9A9A9A")
            ring("ring", 7, 7.0, 3.0, "#FFD54A", 0.6)
        }) { p, s -> p["ring"]!!.yRot = -s.t * 1.1f }

        Look.STRAWBERRY_MILK -> model(look, {
            base("#FFD1DC", "#E8738F", WHITE, face(WHITE, "#5A2A3A", blush = true, cheek = "#FF8FB8"))
            part("hat", 0, 6.4, 0) {
                box(7.6, 2.4, 5, 0, 0, 0, "#E53950"); box(5.6, 1.4, 3.8, 0, 1.8, 0, "#E53950")
                for ((a, b) in listOf(-2.6 to 0.2, -0.8 to -0.4, 1.2 to 0.3, 2.8 to -0.3)) box(0.5, 0.5, 0.3, a, b, 2.55, "#FFE27A")
                for ((a, b) in listOf(-1.6 to 1.8, 1.0 to 1.9)) box(0.5, 0.5, 0.3, a, b, 1.95, "#FFE27A")
                box(6.4, 0.4, 1.4, 0, 2.6, 0, "#4CAF50"); box(1.4, 0.4, 5, 0, 2.6, 0, "#4CAF50"); box(0.6, 1.4, 0.6, 0, 3.4, 0, "#2E7D32")
            }
        }) { p, s -> p["hat"]!!.zRot = sin(s.t * 2f) * 0.05f }

        Look.GOTH_BAT -> model(look, {
            base("#2B2233", "#120C18", "#3D2B52", face("#3D2B52", "#FF3355", "#FF3355", style = "fangs"), hand = "#4A3A5A")
            for ((name, s) in listOf("wingL" to -1, "wingR" to 1)) part(name, 5.4 * s, 2, -1) {
                box(4.4, 0.8, 0.5, 2.2 * s, 1.6, 0, "#4A3A5A"); box(3.6, 2.8, 0.4, 2.4 * s, 0, 0, "#4A3A5A")
                box(1.2, 1.2, 0.4, 1.2 * s, -1.8, 0, "#4A3A5A"); box(1.2, 1.2, 0.4, 3.6 * s, -1.8, 0, "#4A3A5A")
            }
            for (s in listOf(-1, 1)) { box(1.8, 2, 1.2, 3 * s, 6.4, 0, "#2B2233"); box(0.8, 1, 1, 3.4 * s, 7.8, 0, "#2B2233") }
        }) { p, s ->
            val f = sin(s.t * (if (s.happy) 14f else 5f)) * 0.45f
            p["wingL"]!!.yRot = f; p["wingR"]!!.yRot = -f
        }

        Look.GOTHIC_LOLITA -> model(look, {
            base("#1E1A22", "#000000", "#F4F0F6", face("#F4F0F6", "#6B1A3A", blush = true, cheek = "#E8A0B8"), hand = "#F4F0F6")
            part("hat", 1.4, 5.8, 0) {
                box(6.4, 0.6, 4.6, 0, 0, 0, "#111111"); box(4, 4, 3.2, 0, 2.2, 0, "#111111"); box(4.2, 0.9, 3.4, 0, 0.9, 0, "#8B1E3F")
                box(1.4, 1.4, 1.4, 1.8, 1.2, 1.4, "#E8478A"); box(1, 0.4, 0.8, 2.6, 0.7, 1.4, "#2E7D32")
            }
            for (i in 0 until 6) box(1.2, 1, 0.6, -4.2 + i * 1.7, -5.4, 2.05, WHITE)
            box(10.4, 0.5, 0.4, 0, -4.7, 2.05, WHITE)
        }) { p, s -> p["hat"]!!.zRot = 0.12f + sin(s.t * 2f) * 0.03f }

        Look.EMO_SCENE -> model(look, {
            base("#1A1A1A", "#000000", "#2B2B35", face("#2B2B35", "#8EF0FF", style = "oneeye"), hand = "#2B2B35")
            part("hair", 0, 0, 0) {
                box(11.2, 2.2, 4.8, 0, 6, 0, "#0B0B0B"); box(6.2, 4.2, 0.8, -2.2, 3.6, 2.5, "#0B0B0B"); box(3, 1.6, 0.8, -3.6, 0.9, 2.5, "#0B0B0B")
                box(0.9, 5, 0.9, -0.6, 4.4, 2.6, "#FF4FA0"); box(0.9, 2.2, 4.9, -0.6, 6, 0, "#FF4FA0")
            }
            for (i in 0 until 10) {
                box(1.1, 0.9, 0.3, -4.95 + i * 1.1, -4.6, 2.05, if (i % 2 == 1) WHITE else "#111111")
                box(1.1, 0.9, 0.3, -4.95 + i * 1.1, -3.7, 2.05, if (i % 2 == 1) "#111111" else WHITE)
            }
        }) { p, s -> p["hair"]!!.zRot = sin(s.t * 1.6f) * 0.02f }

        Look.PUNK_ROCKER -> model(look, {
            base("#2E2E2E", "#000000", "#1B2A49", face("#1B2A49", "#FFE066", style = "fierce"), hand = "#C0C0C0")
            part("hawk", 0, 5.5, 0) {
                listOf(1.4, 2.6, 3.4, 3.0, 2.2).forEachIndexed { i, h -> box(1.4, h, 1.2, 0, h / 2, 1.8 - i * 0.9, "#FF2D55") }
            }
            for (i in 0 until 5) box(0.8, 0.8, 0.5, -3.6 + i * 1.8, -4.4, 2.1, "#C9C9C9")
            box(0.3, 2.6, 0.3, 4.8, 1, 2.1, "#C9C9C9"); box(0.8, 0.3, 0.3, 4.8, 2.3, 2.1, "#C9C9C9")
        }) { p, s -> p["hawk"]!!.xRot = sin(s.t * (if (s.talk) 12f else 3f)) * 0.06f }

        Look.ROCKSTAR -> model(look, {
            base("#D32F2F", "#7A1010", "#222222", face("#222222", "#111111", "#FFE066", style = "shades"), hand = "#FFC93C")
            for ((name, s) in listOf("earL" to -1, "earR" to 1)) part(name, 3 * s, 5.5, 0) {
                box(1.3, 2.4, 1.3, 0, 1.2, 0, "#FFC93C"); box(1.3, 1.3, 1.3, 0.9 * s, 2.6, 0, "#FFC93C"); box(1.3, 2.2, 1.3, 1.6 * s, 3.9, 0, "#FFC93C")
            }
            for ((fx, h) in listOf(-3.6 to 1.6, -2.0 to 2.6, -0.4 to 1.8, 1.2 to 2.8, 2.8 to 1.8, 4.0 to 1.2)) {
                box(1.4, h, 0.4, fx, -5.5 + h / 2, 2.05, "#FF7A1A"); box(0.8, h * 0.5, 0.5, fx, -5.5 + h * 0.25, 2.1, "#FFC93C")
            }
        }) { p, s ->
            val w = sin(s.t * (if (s.talk) 12f else 2.5f)) * 0.1f
            p["earL"]!!.zRot = w; p["earR"]!!.zRot = -w
        }

        Look.METALHEAD -> model(look, {
            base("#3A3A3A", "#111111", "#EDEDED", face("#EDEDED", "#111111", style = "fierce", shine = "#EDEDED"))
            for (s in listOf(-1, 1)) {
                box(1.6, 2, 1.6, 4.0 * s, 6.4, 0, "#F5F5F5"); box(1.3, 1.6, 1.3, 4.8 * s, 8.0, 0, "#F5F5F5")
                box(1, 1.4, 1, 5.6 * s, 9.2, 0, "#E0E0E0"); box(0.7, 1, 0.7, 5.9 * s, 10.3, 0, "#CFCFCF")
            }
            box(11.4, 1.2, 4.6, 0, -4.4, 0, "#111111")
            for (i in 0 until 5) box(0.8, 0.8, 1, -3.6 + i * 1.8, -4.4, 2.6, "#C9C9C9")
        })

        Look.KAWAII_CAT -> model(look, {
            val b = "#FFE08A"
            base(b, "#C99A1E", "#FFF8E1", face("#FFF8E1", "#5A3A10", blush = true, cheek = "#FF9FB0"), hand = b)
            for (s in listOf(-1, 1)) {
                box(3, 1.4, 1.6, 3.2 * s, 6.2, 0, b); box(2, 1.2, 1.6, 3.4 * s, 7.4, 0, b); box(1, 1, 1.6, 3.6 * s, 8.4, 0, b)
                box(1.4, 1.2, 0.3, 3.3 * s, 6.6, 0.85, "#FF9FB0")
                box(2.8, 0.3, 0.3, 6.6 * s, 1.4, 1.6, "#5A3A10"); box(2.8, 0.3, 0.3, 6.6 * s, 0.4, 1.6, "#5A3A10")
            }
            part("tail", 0, -3.5, -2) {
                box(1.2, 1.2, 3, 0, 0, -1.4, b); box(1.2, 3, 1.2, 0, 1.5, -2.8, b); box(1.2, 1.2, 1.2, 0, 3.4, -2.8, "#C99A1E")
            }
        }) { p, s -> p["tail"]!!.yRot = sin(s.t * (if (s.happy) 10f else 2.5f)) * 0.6f }

        Look.BUNNY_HOP -> model(look, {
            val b = "#BFF0DF"
            base(b, "#3BAA85", WHITE, face(WHITE, "#1E5A47", blush = true, cheek = "#FF9FC0"), hand = b)
            for ((name, s) in listOf("earL" to -1, "earR" to 1)) part(name, 2.4 * s, 5.4, 0) {
                box(2, 6.4, 1.2, 0, 3.2, 0, b); box(1, 5, 0.3, 0, 3.4, 0.65, "#FF9FC0")
            }
            box(2.4, 2.4, 2.4, 0, -3.4, -2.6, WHITE)
        }) { p, s ->
            val k = if (s.happy) 10f else 2.2f
            p["earL"]!!.zRot = 0.12f + sin(s.t * k) * 0.14f
            p["earR"]!!.zRot = -(0.12f + sin(s.t * k + 1f) * 0.14f)
        }

        Look.CLOUD_DREAM -> model(look, {
            base(WHITE, "#B7C7E8", "#E9F1FF", face("#E9F1FF", "#6B7FB8", style = "sleepy", blush = true, cheek = "#FFB3D9"), hands = false)
            for (s in listOf(-1, 1)) {
                box(3, 4, 3.4, 5.6 * s, -0.6, 0, WHITE); box(2.4, 2.4, 3, 6.2 * s, 2.2, 0, WHITE); box(2.4, 2, 3, 5.0 * s, -3.4, 0.4, "#F1F5FF")
            }
            part("bow", 0, 4.8, -1.2) {
                listOf("#FF6B6B", "#FFA94D", "#FFE066", "#7FD8BE", "#74C0FC").forEachIndexed { k, col ->
                    val r = 7.4 - k * 0.9
                    for (i in 0..12) { val a = i / 12.0 * PI; box(1, 1, 0.8, cos(a) * r, sin(a) * r * 0.55, 0, col) }
                }
            }
        }) { p, s -> p["bow"]!!.y = -(4.8f + sin(s.t * 1.4f) * 0.2f) }

        Look.CYBER_NEON -> model(look, {
            base("#12121F", "#2DE2E6", "#050510", face("#050510", "#FF2BD6", "#2DE2E6", style = "visor"), hand = "#2DE2E6")
            box(10.2, 0.35, 0.3, 0, -4.4, 2.05, "#FF2BD6")
            for (s in listOf(-1, 1)) box(0.35, 8, 0.3, 5.55 * s, 0, 1.2, "#2DE2E6")
            box(0.5, 3.4, 0.5, 3.4, 7.2, 0, "#8A8A9A")
            part("tip", 3.4, 9.2, 0) { box(1.2, 1.2, 1.2, 0, 0, 0, "#FF2BD6") }
        }) { p, s ->
            val k = 1f + abs(sin(s.t * 4f)) * 0.4f
            p["tip"]!!.apply { xScale = k; yScale = k; zScale = k }
        }

        Look.Y2K_FLIP -> model(look, {
            base("#F7B6E0", "#B0558F", "#CFE8FF", face("#CFE8FF", "#B0558F", "#FF4FA0", style = "star"), hand = "#CFE8FF")
            for ((i, pair) in listOf(-2.6 to "#8EC9FF", 2.4 to "#B9A2FF").withIndex()) part("fly$i", pair.first, 6.2, 0.6) {
                box(0.5, 1.8, 0.6, 0, 0, 0, "#333333")
                part("fly${i}l", -1, 0.3, 0) { box(1.6, 1.6, 0.4, 0, 0, 0, pair.second) }
                part("fly${i}r", 1, 0.3, 0) { box(1.6, 1.6, 0.4, 0, 0, 0, pair.second) }
            }
            for (i in 0 until 6) box(0.6, 0.6, 0.4, -3.8 + i * 1.5, -4.4, 2.1, WHITE)
            box(0.8, 3, 0.8, -4, 7, -1, "#B0558F"); box(1.2, 1.2, 1.2, -4, 8.8, -1, "#FF4FA0")
        }) { p, s ->
            val k = 0.5f + abs(sin(s.t * 7f)) * 0.5f
            for (n in listOf("fly0l", "fly0r", "fly1l", "fly1r")) p[n]!!.xScale = k
        }

        Look.WITCHY -> model(look, {
            val h = "#1E1430"
            base("#6B3FA0", "#2D1450", h, face(h, "#7CFF6B"))
            part("hat", 0, 5.8, 0) {
                box(13, 0.6, 7, 0, 0, 0, h); box(7, 2, 5, 0, 1.3, 0, h); box(7.2, 0.9, 5.2, 0, 1, 0, "#7CFF6B")
                box(5, 2, 3.6, 0.3, 3.2, -0.2, h); box(3.2, 2, 2.4, 0.9, 5, -0.4, h); box(1.8, 1.8, 1.6, 1.9, 6.6, -0.6, h); box(1, 1, 1, 3, 7.4, -0.6, h)
            }
            part("charm", 6.2, 0.4, 1.8) {
                box(0.3, 2.2, 0.3, 0, -1.1, 0, "#9A9A9A")
                box(1.6, 0.6, 0.5, 0.2, -2.4, 0, "#FFE27A"); box(0.6, 1.8, 0.5, -0.4, -3.2, 0, "#FFE27A"); box(1.6, 0.6, 0.5, 0.2, -4, 0, "#FFE27A")
            }
        }) { p, s -> p["charm"]!!.zRot = sin(s.t * 2.4f) * 0.3f; p["hat"]!!.zRot = sin(s.t * 1.5f) * 0.04f }

        Look.PUMPKIN_SPICE -> model(look, {
            base("#FF8C2B", "#B3530B", "#3B2410", face("#3B2410", "#FFC93C", "#FFC93C", style = "happy"), hand = "#4CAF50")
            for (s in listOf(-1, 1)) { box(0.6, 10.6, 0.3, 4.6 * s, 0, 2.02, "#D96A12"); box(0.3, 8.6, 3, 5.52 * s, 0, 0, "#D96A12") }
            box(0.6, 2, 0.3, 0, -4.6, 2.02, "#D96A12")
            box(1.4, 2.4, 1.4, 0, 6.6, 0, "#6B4A2B"); box(1, 1, 1, 0.9, 7.8, 0, "#6B4A2B")
            part("leaf", 2, 6.2, 0.4) { box(3.2, 0.4, 2, 0, 0, 0, "#4CAF50") }
        }) { p, s -> p["leaf"]!!.zRot = sin(s.t * 2f) * 0.15f }

        Look.FROST_FAIRY -> model(look, {
            base("#CFEFFF", "#6FB7E0", WHITE, face(WHITE, "#2A6FA8", blush = true, cheek = "#FFB3D9"), hand = WHITE)
            for ((name, s) in listOf("wingL" to -1, "wingR" to 1)) part(name, 1.5 * s, 0.5, -2.4) {
                box(5.4, 5.2, 0.3, 3.6 * s, 2.6, 0, "#DDF3FF"); box(4, 3.6, 0.3, 3.0 * s, -2, 0, "#C4E8FF")
            }
            box(8.4, 0.8, 4.6, 0, 6, 0, "#9BD8FF")
            box(0.6, 2.8, 0.4, 0, 7.6, 1.6, WHITE); box(2.8, 0.6, 0.4, 0, 7.6, 1.6, WHITE)
            for (s in listOf(-1, 1)) box(0.6, 1.4, 0.4, 2.8 * s, 7, 1.6, WHITE)
        }) { p, s ->
            val f = sin(s.t * (if (s.happy) 14f else 6f)) * 0.35f
            p["wingL"]!!.yRot = f; p["wingR"]!!.yRot = -f
        }

        Look.SAKURA -> model(look, {
            base("#FFF5F8", "#E8A0B8", "#FFE3EC", face("#FFE3EC", "#B0456A", style = "happy", blush = true, cheek = "#FF8FB8"), hand = "#FF8FB8")
            for ((fx, fz) in listOf(-4.0 to 1.4, -2.0 to 1.9, 0.0 to 2.1, 2.0 to 1.9, 4.0 to 1.4, -3.0 to -1.6, 3.0 to -1.6)) {
                box(0.8, 0.8, 0.8, fx, 6, fz, "#FFE27A")
                for ((a, b) in listOf(1 to 0, -1 to 0, 0 to 1, 0 to -1)) box(0.9, 0.9, 0.9, fx + a * 0.85, 6, fz + b * 0.85, "#FF8FB8")
            }
            box(9, 0.5, 4.2, 0, 5.6, 0, "#7FB069")
            ring("ring", 7, 7.5, 2.0, "#FFC2D6", 0.8, flat = true)
        }) { p, s -> p["ring"]!!.yRot = s.t * 0.5f; p["ring"]!!.y = -(2f + sin(s.t * 1.2f) * 1.5f) }

        Look.PIXEL_GHOST -> model(look, {
            val g = "#F4F4FF"
            box(9, 10, 7, 0, 1, 0, g); box(11, 7, 6, 0, 0, 0, g); box(7, 1.6, 5.4, 0, 6.6, 0, g)
            for (i in 0 until 5) part("tail$i", -4.4 + i * 2.2, -4.6, 0) { box(2.1, 2, 6, 0, 0, 0, if (i % 2 == 1) "#E6E6F7" else g) }
            box(8, 6, 0.05, 0, 1.6, 3.55, g, face(g, "#1A1A2E", style = "ghost", blush = true, cheek = "#FFB3D9"))
            part("armL", -6, -0.4, 0) { box(1.6, 2.4, 1.6, 0, 0, 0, g) }
            part("armR", 6, -0.4, 0) { box(1.6, 2.4, 1.6, 0, 0, 0, g) }
        }) { p, s ->
            for (i in 0 until 5) p["tail$i"]!!.y = 4.6f + abs(sin(s.t * 4f + i)) * 0.9f
            val a = sin(s.t * 3f) * 0.3f
            p["armL"]!!.zRot = a; p["armR"]!!.zRot = -a
            p["root"]!!.zRot += sin(s.t * 1.2f) * 0.06f
        }

        Look.LOFI_CHILL -> model(look, {
            base("#7FA88F", "#3E6B52", "#233A2E", face("#233A2E", "#CFEFD9", style = "sleepy"), hand = "#E07A5F")
            box(10.6, 3, 4.6, 0, 6.6, 0, "#E07A5F"); box(10.9, 1.1, 4.9, 0, 5.4, 0, "#B85A42"); box(2, 2, 2, 0, 9.1, 0, WHITE)
            box(13.2, 0.8, 1, 0, 8.4, 0, "#333333")
            for (s in listOf(-1, 1)) {
                box(0.8, 6.8, 1, 6.2 * s, 5, 0, "#333333"); box(1.8, 3.4, 3.4, 6.2 * s, 1.2, 0, "#333333"); box(0.4, 2.2, 2.2, 7.2 * s, 1.2, 0, "#E07A5F")
            }
            for ((i, x) in listOf(8.5, -8.5).withIndex()) part("note$i", x, 6, 1) {
                box(0.4, 2.2, 0.4, 0.5, 1, 0, "#FFE27A"); box(1.2, 0.9, 0.6, 0, 0, 0, "#FFE27A"); box(1, 0.4, 0.4, 1, 2, 0, "#FFE27A")
            }
        }) { p, s ->
            for (i in 0..1) p["note$i"]!!.apply { y = -(6f + sin(s.t * 2f + i * 2f) * 1.2f); zRot = sin(s.t * 3f + i) * 0.3f }
        }

        else -> error("not a costume: $look")
    }
}
