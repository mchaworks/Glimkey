package dev.pillboard.keyboard

import android.graphics.Canvas
import android.graphics.Color
import android.graphics.ColorFilter
import android.graphics.LinearGradient
import android.graphics.Outline
import android.graphics.Paint
import android.graphics.Path
import android.graphics.PixelFormat
import android.graphics.RectF
import android.graphics.Shader
import android.graphics.drawable.Drawable

/**
 * The keycap body. Same surface as the GradientDrawable it replaces (colours, four corner radii) so existing press /
 * Shift / release code is unchanged, plus an organic mode: [setMorph] changes the face size around its centre (a key
 * whose width or height really differs on the new page) and adds [bulge] (0..1), a soft Bezier deformation: all four
 * edges bow outward a little, like a soft pillow / flexible material. At rest it is a plain
 * rounded rect. One Path and one Paint, reused every frame; no bitmaps.
 */
internal class KeyBodyDrawable : Drawable() {
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val path = Path()
    private val rect = RectF()
    private val radii = FloatArray(8)
    private var c0 = Color.TRANSPARENT
    private var c1 = Color.TRANSPARENT
    private var alphaMul = 255
    private var shaderFor = 0L

    private companion object {
        const val ORGANIC_EDGES = MorphTokens.KEY_BULGE_TOP + MorphTokens.KEY_BULGE_SIDE + MorphTokens.KEY_BULGE_BOTTOM + MorphTokens.KEY_BULGE_LEAN
    }

    // ---- skins: what makes a theme's keycap more than a recolour ----
    private val skinPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val strokePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE }
    private val dishPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val face = RectF()
    private val dish = RectF()
    private val dens = android.content.res.Resources.getSystem().displayMetrics.density
    var skin = KeySkin.FLAT
        set(v) { if (field != v) { field = v; invalidateSelf() } }
    /** Mechanical: colour of the skirt around the cap. Paper: colour of the hard shadow under the card. */
    var edge = Color.TRANSPARENT
        set(v) { if (field != v) { field = v; invalidateSelf() } }
    /** Paper / terminal: border. Mechanical: rim light on the cap. */
    var line = Color.TRANSPARENT
        set(v) { if (field != v) { field = v; invalidateSelf() } }
    /** Skins that physically move (paper card, mechanical cap) look different while pressed. */
    var down = false
        set(v) { if (field != v) { field = v; invalidateSelf() } }

    /** How far the key's label should sit from the centre of the cell, so it rides on the face: the cap sinks, the card lies flat. */
    val contentDy: Float get() = when (skin) {
        KeySkin.PAPER -> if (down) dens else 0f
        KeySkin.MECHANICAL -> if (down) 0.75f * dens else -1.75f * dens
        KeySkin.CLAY -> if (down) 0.6f * dens else 0f
        KeySkin.PIXEL -> if (down) 0.9f * dens else -1.5f * dens     // the face rides on the block: lower when it drops onto its shadow
        KeySkin.LCD -> if (down) 0f else -0.5f * dens               // the face sits half a ghost-offset up-left of the cell
        else -> 0f
    }

    var dW = 0f; private set
    var dH = 0f; private set
    var bulge = 0f; private set
    /** How far the face may grow beyond its bounds (the hit inset: the gap between keys). */
    var limitX = 0f
    var limitY = 0f

    fun setColors(cs: IntArray) { c0 = cs[0]; c1 = cs[1]; invalidateSelf() }
    fun setCornerRadii(r: FloatArray) { System.arraycopy(r, 0, radii, 0, 8); invalidateSelf() }
    @Suppress("UNUSED_PARAMETER") fun setStroke(width: Int, color: Int) {}      // flat keycaps: no outline

    fun setMorph(w: Float, h: Float, b: Float) {
        dW = w; dH = h; bulge = b
        invalidateSelf()
    }

    override fun draw(canvas: Canvas) {
        val bd = bounds
        if (bd.isEmpty) return
        val cx = bd.exactCenterX()
        val cy = bd.exactCenterY()
        var w = (bd.width() + dW.coerceIn(-2f * limitX - bd.width(), 2f * limitX)).coerceAtLeast(2f)
        var h = (bd.height() + dH.coerceIn(-2f * limitY - bd.height(), 2f * limitY)).coerceAtLeast(2f)
        val sq = bulge * MorphTokens.KEY_SQUASH            // soft squash: a touch wider and shorter, edges stay straight
        w = minOf(w * (1f + sq), bd.width() + 2f * limitX)
        h *= (1f - sq)
        rect.set(cx - w / 2f, cy - h / 2f, cx + w / 2f, cy + h / 2f)

        if (skin != KeySkin.FLAT) { drawSkin(canvas); return }

        if (c0 == c1) { paint.shader = null; paint.color = c0 }
        else {
            val key = c0.toLong() * 31 + c1 + rect.top.toBits() * 7L + rect.bottom.toBits()
            if (key != shaderFor) { shaderFor = key; paint.shader = LinearGradient(0f, rect.top, 0f, rect.bottom, c0, c1, Shader.TileMode.CLAMP) }
        }
        paint.alpha = Color.alpha(c0) * alphaMul / 255

        val maxR = minOf(w, h) / 2f
        path.reset()
        if (bulge <= 0.002f || ORGANIC_EDGES == 0f) {
            for (i in 0 until 8) radii[i] = radii[i].coerceIn(0f, maxR)
            path.addRoundRect(rect, radii, Path.Direction.CW)
        } else organic(rect, minOf(radii[0], maxR), minOf(radii[2], maxR), minOf(radii[4], maxR), minOf(radii[6], maxR), bulge)
        canvas.drawPath(path, paint)
    }

    private fun roundPath(r: RectF, shrink: Float = 0f) {
        val maxR = minOf(r.width(), r.height()) / 2f
        for (i in 0 until 8) radii2[i] = (radii[i] - shrink).coerceIn(0f, maxR)
        path.reset()
        path.addRoundRect(r, radii2, Path.Direction.CW)
    }
    private val radii2 = FloatArray(8)
    private var faceShaderFor = 0L
    private var dishShaderFor = 0L
    private var dishShader: Shader? = null

    private fun faceFill(r: RectF) {
        if (c0 == c1) { skinPaint.shader = null; skinPaint.color = c0 }
        else {
            val k = c0.toLong() * 31 + c1 + r.top.toBits() * 7L + r.bottom.toBits()
            if (k != faceShaderFor || skinPaint.shader == null) {
                faceShaderFor = k
                skinPaint.shader = LinearGradient(0f, r.top, 0f, r.bottom, c0, c1, Shader.TileMode.CLAMP)
            }
        }
        skinPaint.alpha = Color.alpha(c0) * alphaMul / 255
    }

    private fun drawSkin(canvas: Canvas) {
        val d = dens
        when (skin) {
            KeySkin.PAPER -> {
                // an index card on the page: hard offset shadow, ink border; pressed, it lies flat (shadow all but gone)
                val shadowDy = 1.5f * d
                val faceDy = if (down) d else 0f
                face.set(rect.left, rect.top + shadowDy, rect.right, rect.bottom + shadowDy)
                roundPath(face)
                skinPaint.shader = null; skinPaint.color = edge
                canvas.drawPath(path, skinPaint)
                face.set(rect.left, rect.top + faceDy, rect.right, rect.bottom + faceDy - 0.5f * d)
                roundPath(face)
                faceFill(face)
                canvas.drawPath(path, skinPaint)
                if (Color.alpha(line) > 0) {
                    strokePaint.strokeWidth = d; strokePaint.color = line
                    face.inset(d / 2f, d / 2f)
                    roundPath(face, d / 2f)
                    canvas.drawPath(path, strokePaint)
                }
            }
            KeySkin.TERMINAL -> {
                // a hairline-outlined block; pressed, the colours are inverted by the key (pressed fill is solid phosphor)
                roundPath(rect)
                faceFill(rect)
                canvas.drawPath(path, skinPaint)
                if (Color.alpha(line) > 0) {
                    strokePaint.strokeWidth = d; strokePaint.color = line
                    face.set(rect); face.inset(d / 2f, d / 2f)
                    roundPath(face, d / 2f)
                    canvas.drawPath(path, strokePaint)
                }
            }
            KeySkin.MECHANICAL -> {
                // a cap in its skirt: the skirt is the full key, the cap sits inside it and sinks when pressed
                roundPath(rect)
                skinPaint.shader = null; skinPaint.color = edge
                skinPaint.alpha = Color.alpha(edge) * alphaMul / 255
                canvas.drawPath(path, skinPaint)
                val side = 2.5f * d
                val top = (if (down) 3.5f else 1f) * d
                val bottom = (if (down) 2f else 4.5f) * d
                face.set(rect.left + side, rect.top + top, rect.right - side, rect.bottom - bottom)
                if (face.width() < 4f || face.height() < 4f) return
                roundPath(face, 2f * d)
                faceFill(face)
                canvas.drawPath(path, skinPaint)
                // the dish: a soft darker-to-lighter concave inside the cap
                dish.set(face.left + 3f * d, face.top + 3f * d, face.right - 3f * d, face.bottom - 3f * d)
                if (dish.width() > 4f && dish.height() > 4f) {
                    roundPath(dish, 4f * d)
                    val k = dish.top.toBits() * 7L + dish.bottom.toBits()
                    if (k != dishShaderFor || dishShader == null) {
                        dishShaderFor = k
                        dishShader = LinearGradient(0f, dish.top, 0f, dish.bottom, 0x1F000000, 0x14FFFFFF, Shader.TileMode.CLAMP)
                    }
                    dishPaint.shader = dishShader
                    dishPaint.alpha = alphaMul
                    canvas.drawPath(path, dishPaint)
                }
                if (Color.alpha(line) > 0) {
                    strokePaint.strokeWidth = d; strokePaint.color = line
                    face.inset(d / 2f, d / 2f)
                    roundPath(face, 2f * d + d / 2f)
                    canvas.drawPath(path, strokePaint)
                }
            }
            KeySkin.CLAY -> {
                // extruded from the shell: a light shadow up-left and a dark one down-right, each faked with a few
                // offset copies (no blur / software layer). Pressed, the face goes flat and gets an inner shadow.
                val inset = 2f * d                        // keeps the shadows inside the key's bounds
                face.set(rect.left + inset, rect.top + inset, rect.right - inset, rect.bottom - inset)
                if (face.width() < 6f || face.height() < 6f) return
                roundPath(face)
                if (!down) {
                    val darkA = (Color.alpha(edge) * 0.38f * alphaMul / 255f).toInt()
                    val lightA = (Color.alpha(line) * 0.45f * alphaMul / 255f).toInt()
                    skinPaint.shader = null
                    for (i in 3 downTo 1) {
                        val o = i * 0.7f * d
                        if (darkA > 0) {
                            skinPaint.color = androidx.core.graphics.ColorUtils.setAlphaComponent(edge, darkA)
                            canvas.save(); canvas.translate(o, o); canvas.drawPath(path, skinPaint); canvas.restore()
                        }
                        if (lightA > 0) {
                            skinPaint.color = androidx.core.graphics.ColorUtils.setAlphaComponent(line, lightA)
                            canvas.save(); canvas.translate(-o, -o); canvas.drawPath(path, skinPaint); canvas.restore()
                        }
                    }
                    faceFill(face)
                    canvas.drawPath(path, skinPaint)
                } else {
                    skinPaint.shader = null; skinPaint.color = c1
                    skinPaint.alpha = Color.alpha(c1) * alphaMul / 255
                    canvas.drawPath(path, skinPaint)
                    // inner shadow: stroke the outline shifted inward and clipped to the face
                    val save = canvas.save()
                    canvas.clipPath(path)
                    strokePaint.strokeWidth = 2.5f * d
                    strokePaint.color = androidx.core.graphics.ColorUtils.setAlphaComponent(edge, (Color.alpha(edge) * 0.9f).toInt())
                    canvas.translate(1.3f * d, 1.3f * d); canvas.drawPath(path, strokePaint)
                    strokePaint.color = androidx.core.graphics.ColorUtils.setAlphaComponent(line, (Color.alpha(line) * 0.8f).toInt())
                    canvas.translate(-2.6f * d, -2.6f * d); canvas.drawPath(path, strokePaint)
                    canvas.restoreToCount(save)
                }
            }
            KeySkin.PIXEL -> {
                // a block with notched corners on a hard shadow slab; pressed, the block drops onto the slab
                val s = 3f * d
                val n = (radii[0] * 0.6f).coerceIn(1.5f * d, 4f * d).coerceAtMost(minOf(rect.width(), rect.height()) / 4f)
                val dy = if (down) 0.8f * s else 0f
                stepped(rect, n)
                skinPaint.shader = null; skinPaint.color = edge
                skinPaint.alpha = Color.alpha(edge) * alphaMul / 255
                canvas.drawPath(path, skinPaint)
                face.set(rect.left, rect.top + dy, rect.right, rect.bottom - s + dy)
                if (face.height() < 4f) return
                stepped(face, n)
                faceFill(face)
                canvas.drawPath(path, skinPaint)
                if (Color.alpha(line) > 0) {
                    strokePaint.strokeWidth = 2f * d; strokePaint.color = line
                    strokePaint.strokeJoin = Paint.Join.MITER
                    face.inset(d, d)
                    stepped(face, (n - d).coerceAtLeast(d * 0.5f))
                    canvas.drawPath(path, strokePaint)
                }
            }
            KeySkin.LCD -> {
                // ink on LCD: the face sits up-left of a faint ghost; pressed, the ghost is absorbed and the key fills with ink
                val g = d
                if (!down) {
                    face.set(rect.left, rect.top, rect.right - g, rect.bottom - g)
                    if (Color.alpha(edge) > 0) {
                        dish.set(face); dish.offset(g, g)
                        roundPath(dish)
                        skinPaint.shader = null; skinPaint.color = edge
                        skinPaint.alpha = Color.alpha(edge) * alphaMul / 255
                        canvas.drawPath(path, skinPaint)
                    }
                } else face.set(rect)
                roundPath(face)
                faceFill(face)
                canvas.drawPath(path, skinPaint)
                if (Color.alpha(line) > 0) {
                    strokePaint.strokeWidth = d; strokePaint.color = line
                    face.inset(d / 2f, d / 2f)
                    roundPath(face, d / 2f)
                    canvas.drawPath(path, strokePaint)
                }
            }
            KeySkin.FLAT -> Unit
        }
    }

    /** A rect with one-step notched corners (the 8-bit look). [n] is the notch size in px. */
    private fun stepped(r: RectF, n: Float) {
        val l = r.left; val t = r.top; val rt = r.right; val b = r.bottom
        path.reset()
        path.moveTo(l + n, t); path.lineTo(rt - n, t); path.lineTo(rt - n, t + n); path.lineTo(rt, t + n)
        path.lineTo(rt, b - n); path.lineTo(rt - n, b - n); path.lineTo(rt - n, b); path.lineTo(l + n, b)
        path.lineTo(l + n, b - n); path.lineTo(l, b - n); path.lineTo(l, t + n); path.lineTo(l + n, t + n)
        path.close()
    }

    /** Rounded rect whose four edges are cubic curves pushed by [bu]; corners stay circular arcs (kappa). */
    private fun organic(r: RectF, rTL: Float, rTR: Float, rBR: Float, rBL: Float, bu: Float) {
        val l = r.left; val t = r.top; val rt = r.right; val b = r.bottom
        val w = rt - l; val h = b - t
        val up = bu * MorphTokens.KEY_BULGE_TOP * h
        val side = bu * MorphTokens.KEY_BULGE_SIDE * w
        val down = bu * MorphTokens.KEY_BULGE_BOTTOM * h
        val lean = bu * MorphTokens.KEY_BULGE_LEAN * w
        val k = 0.5523f

        val x0 = l + rTL; val x1 = rt - rTR; val dx = x1 - x0
        path.moveTo(x0, t)
        path.cubicTo(x0 + dx / 3f + lean, t - up, x0 + 2f * dx / 3f + lean, t - up, x1, t)
        path.cubicTo(x1 + k * rTR, t, rt, t + rTR - k * rTR, rt, t + rTR)

        val y0 = t + rTR; val y1 = b - rBR; val dy = y1 - y0
        path.cubicTo(rt + side, y0 + dy / 3f, rt + side, y0 + 2f * dy / 3f, rt, y1)
        path.cubicTo(rt, y1 + k * rBR, rt - rBR + k * rBR, b, rt - rBR, b)

        val x2 = rt - rBR; val x3 = l + rBL; val dx2 = x2 - x3
        path.cubicTo(x2 - dx2 / 3f, b + down, x2 - 2f * dx2 / 3f, b + down, x3, b)
        path.cubicTo(x3 - k * rBL, b, l, b - rBL + k * rBL, l, b - rBL)

        val y2 = b - rBL; val y3 = t + rTL; val dy2 = y2 - y3
        path.cubicTo(l - side, y2 - dy2 / 3f, l - side, y2 - 2f * dy2 / 3f, l, y3)
        path.cubicTo(l, y3 - k * rTL, x0 - k * rTL, t, x0, t)
        path.close()
    }

    override fun getOutline(outline: Outline) {
        val r = (radii[0] + radii[2] + radii[4] + radii[6]) / 4f
        outline.setRoundRect(bounds, r.coerceAtLeast(0f))
        outline.alpha = 1f
    }

    override fun setAlpha(alpha: Int) { alphaMul = alpha; invalidateSelf() }
    override fun setColorFilter(cf: ColorFilter?) { paint.colorFilter = cf; invalidateSelf() }
    @Deprecated("Deprecated in Java")
    override fun getOpacity(): Int = PixelFormat.TRANSLUCENT
}
