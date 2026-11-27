package dev.pillboard.keyboard

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Path
import android.view.View
import androidx.core.graphics.ColorUtils
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.pow
import kotlin.math.sin

/**
 * Tap effects: a little burst where a key is pressed. This is a setting of its own (Settings > Appearance > Effects > Tap effect) and has
 * nothing to do with the keyboard [KbTheme]: any effect works on any theme, like the key effects of Samsung Keys Cafe.
 */
private val TAP_EFFECTS = TapEffect.values()
private val BACK_EFFECTS = BackEffect.values()

enum class TapEffect(val id: String, val label: String) {
    OFF("off", "Off"),
    RIPPLE("ripple", "Ripple"),
    SPARKLE("sparkle", "Sparkle"),
    HEARTS("hearts", "Hearts"),
    BUBBLES("bubbles", "Bubbles"),
    CONFETTI("confetti", "Confetti"),
    PETALS("petals", "Petals");

    companion object {
        const val PREF = "tap_fx"
        const val COLOR_PREF = "tap_fx_color"
        fun of(id: String?): TapEffect = values().firstOrNull { it.id == id } ?: OFF
        fun current(c: Context): TapEffect = of(Prefs.str(c, PREF, OFF.id))
        val STYLES = values().map { it.id to it.label }
        /** auto = each effect's own colours; accent = the theme's accent; rainbow = random bright colours. */
        val COLORS = listOf("auto" to "Auto (effect colours)", "accent" to "Theme accent", "rainbow" to "Rainbow")
    }
}

/**
 * Overlay above the keyboard that draws the tap effects. It never takes touches. One pool of particles, one Paint, no
 * allocation per frame; it only keeps animating while something is alive. Keys call [burst] on press, after the input
 * and the haptic / sound, so an effect can never delay typing.
 */
class TapFxView(ctx: Context, private val t: KbTokens) : View(ctx) {
    private val n = 72
    private val alive = BooleanArray(n)
    private val kind = IntArray(n)
    private val x = FloatArray(n); private val y = FloatArray(n)
    private val vx = FloatArray(n); private val vy = FloatArray(n); private val gy = FloatArray(n)
    private val age = FloatArray(n); private val life = FloatArray(n)
    private val size = FloatArray(n); private val rot = FloatArray(n); private val vr = FloatArray(n)
    private val sway = FloatArray(n); private val phase = FloatArray(n)
    private val col = IntArray(n)

    private val d = ctx.resources.displayMetrics.density
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val rnd = java.util.Random()
    private val a = IntArray(2); private val b = IntArray(2)
    private var running = false
    private var last = 0L

    // unit shapes (about 1 unit wide), scaled per particle
    private val heart = Path().apply { moveTo(0f, 0.9f); cubicTo(-1.4f, -0.1f, -0.6f, -1.1f, 0f, -0.35f); cubicTo(0.6f, -1.1f, 1.4f, -0.1f, 0f, 0.9f); close() }
    private val spark = Path().apply {
        moveTo(0f, -1f); lineTo(0.22f, -0.22f); lineTo(1f, 0f); lineTo(0.22f, 0.22f)
        lineTo(0f, 1f); lineTo(-0.22f, 0.22f); lineTo(-1f, 0f); lineTo(-0.22f, -0.22f); close()
    }
    private val petal = Path().apply { moveTo(0f, -1f); cubicTo(0.9f, -0.6f, 0.7f, 0.6f, 0f, 1f); cubicTo(-0.7f, 0.6f, -0.9f, -0.6f, 0f, -1f); close() }

    init { isClickable = false; isFocusable = false; importantForAccessibility = IMPORTANT_FOR_ACCESSIBILITY_NO }
    override fun onMeasure(w: Int, h: Int) = setMeasuredDimension(resolveSize(0, w), resolveSize(0, h))

    private fun r(lo: Float, hi: Float) = lo + rnd.nextFloat() * (hi - lo)
    private fun pick(vararg c: Long) = c[rnd.nextInt(c.size)].toInt()

    private val rainbow = longArrayOf(0xFFFF4D6D, 0xFFFF9F1C, 0xFFFFD60A, 0xFF2EC4B6, 0xFF3A86FF, 0xFF8338EC, 0xFFFF70A6)

    private fun colorFor(e: TapEffect, mode: String): Int = when (mode) {
        "accent" -> t.accent
        "rainbow" -> pick(*rainbow)
        else -> when (e) {
            TapEffect.RIPPLE -> t.accent
            TapEffect.SPARKLE -> pick(0xFFFFE08A, 0xFFFFC857, 0xFFFFFFFF)
            TapEffect.HEARTS -> pick(0xFFFF4D6D, 0xFFFF758F, 0xFFFF8FA3)
            TapEffect.BUBBLES -> pick(0xFF7DD3FC, 0xFFA5F3FC, 0xFFBAE6FD)
            TapEffect.CONFETTI -> pick(*rainbow)
            TapEffect.PETALS -> pick(0xFFFFB7C5, 0xFFFFC8DD, 0xFFFFAFCC)
            TapEffect.OFF -> t.accent
        }
    }

    private fun spawn(e: TapEffect, px: Float, py: Float, mode: String): Int {
        for (i in 0 until n) if (!alive[i]) {
            alive[i] = true; kind[i] = e.ordinal; x[i] = px; y[i] = py
            vx[i] = 0f; vy[i] = 0f; gy[i] = 0f; age[i] = 0f; rot[i] = 0f; vr[i] = 0f; sway[i] = 0f; phase[i] = r(0f, 6.28f)
            col[i] = colorFor(e, mode)
            return i
        }
        return -1
    }

    /** A burst for a press at ([lx], [ly]) inside [src] (the key's own coordinates). */
    fun burst(src: View, lx: Float, ly: Float) {
        val e = TapEffect.current(context)
        if (e == TapEffect.OFF || width == 0) return
        src.getLocationInWindow(a); getLocationInWindow(b)
        val px = a[0] - b[0] + lx; val py = a[1] - b[1] + ly
        val mode = Prefs.str(context, TapEffect.COLOR_PREF, "auto")
        when (e) {
            TapEffect.RIPPLE -> spawn(e, px, py, mode).let { if (it >= 0) { life[it] = 0.5f; size[it] = 34f * d } }
            TapEffect.SPARKLE -> repeat(6) {
                val i = spawn(e, px, py, mode); if (i < 0) return@repeat
                val ang = r(0f, 6.28f); val sp = r(60f, 150f) * d
                vx[i] = cos(ang) * sp; vy[i] = sin(ang) * sp
                life[i] = r(0.45f, 0.7f); size[i] = r(4f, 8f) * d; vr[i] = r(-200f, 200f)
            }
            TapEffect.HEARTS -> repeat(3) {
                val i = spawn(e, px, py, mode); if (i < 0) return@repeat
                vx[i] = r(-22f, 22f) * d; vy[i] = -r(70f, 120f) * d
                life[i] = r(0.8f, 1.1f); size[i] = r(6f, 10f) * d; sway[i] = r(8f, 22f) * d; vr[i] = r(-40f, 40f)
            }
            TapEffect.BUBBLES -> repeat(4) {
                val i = spawn(e, px + r(-8f, 8f) * d, py, mode); if (i < 0) return@repeat
                vx[i] = r(-18f, 18f) * d; vy[i] = -r(50f, 95f) * d
                life[i] = r(0.8f, 1.2f); size[i] = r(4f, 9f) * d; sway[i] = r(6f, 16f) * d
            }
            TapEffect.CONFETTI -> repeat(10) {
                val i = spawn(e, px, py, mode); if (i < 0) return@repeat
                val ang = Math.toRadians(r(-150f, -30f).toDouble()).toFloat(); val sp = r(120f, 260f) * d
                vx[i] = cos(ang) * sp; vy[i] = sin(ang) * sp; gy[i] = 520f * d
                life[i] = r(0.7f, 1f); size[i] = r(3.5f, 6.5f) * d; vr[i] = r(-520f, 520f); rot[i] = r(0f, 360f)
            }
            TapEffect.PETALS -> repeat(4) {
                val i = spawn(e, px, py, mode); if (i < 0) return@repeat
                vx[i] = r(-34f, 34f) * d; vy[i] = -r(20f, 55f) * d; gy[i] = 70f * d
                life[i] = r(1f, 1.4f); size[i] = r(5f, 8.5f) * d; sway[i] = r(14f, 30f) * d; vr[i] = r(-120f, 120f); rot[i] = r(0f, 360f)
            }
            TapEffect.OFF -> Unit
        }
        if (!running) { running = true; last = System.nanoTime(); postInvalidateOnAnimation() }
    }

    override fun onDraw(c: Canvas) {
        val now = System.nanoTime()
        val dt = ((now - last) / 1e9f).coerceIn(0f, 0.05f)
        last = now
        var any = false
        for (i in 0 until n) {
            if (!alive[i]) continue
            age[i] += dt
            if (age[i] >= life[i]) { alive[i] = false; continue }
            any = true
            val p = age[i] / life[i]
            val e = TAP_EFFECTS[kind[i]]
            val drag = when (e) { TapEffect.SPARKLE -> 3f; TapEffect.CONFETTI -> 1.2f; else -> 0f }
            if (drag > 0f) { val k = (1f - drag * dt).coerceAtLeast(0f); vx[i] *= k; vy[i] *= k }
            vy[i] += gy[i] * dt
            x[i] += (vx[i] + sway[i] * sin(age[i] * 7f + phase[i])) * dt
            y[i] += vy[i] * dt
            rot[i] += vr[i] * dt
            val fade = if (p < 0.65f) 1f else ((1f - p) / 0.35f)
            val color = ColorUtils.setAlphaComponent(col[i], (alphaOf(col[i]) * fade).toInt())
            when (e) {
                TapEffect.RIPPLE -> {
                    val rad = size[i] * (1f - (1f - p).pow(3))
                    paint.style = Paint.Style.FILL; paint.color = ColorUtils.setAlphaComponent(col[i], (46 * fade * (1f - p)).toInt())
                    c.drawCircle(x[i], y[i], rad, paint)
                    paint.style = Paint.Style.STROKE; paint.strokeWidth = (2.2f * (1f - p) + 0.6f) * d
                    paint.color = ColorUtils.setAlphaComponent(col[i], (200 * (1f - p)).toInt())
                    c.drawCircle(x[i], y[i], rad, paint)
                }
                TapEffect.SPARKLE -> shape(c, spark, i, size[i] * (1f - 0.6f * p), color)
                TapEffect.HEARTS -> shape(c, heart, i, size[i] * minOf(1f, p * 7f), color)
                TapEffect.PETALS -> shape(c, petal, i, size[i], color)
                TapEffect.BUBBLES -> {
                    val rad = size[i] * (0.6f + 0.4f * minOf(1f, p * 4f))
                    paint.style = Paint.Style.FILL; paint.color = ColorUtils.setAlphaComponent(col[i], (45 * fade).toInt())
                    c.drawCircle(x[i], y[i], rad, paint)
                    paint.style = Paint.Style.STROKE; paint.strokeWidth = 1.3f * d; paint.color = color
                    c.drawCircle(x[i], y[i], rad, paint)
                    paint.style = Paint.Style.FILL; paint.color = ColorUtils.setAlphaComponent(0xFFFFFFFF.toInt(), (200 * fade).toInt())
                    c.drawCircle(x[i] - rad * 0.35f, y[i] - rad * 0.35f, rad * 0.18f, paint)
                }
                TapEffect.CONFETTI -> {
                    paint.style = Paint.Style.FILL; paint.color = color
                    c.save(); c.translate(x[i], y[i]); c.rotate(rot[i])
                    c.drawRect(-size[i], -size[i] * 0.5f, size[i], size[i] * 0.5f, paint)
                    c.restore()
                }
                TapEffect.OFF -> Unit
            }
        }
        if (any) postInvalidateOnAnimation() else running = false
    }

    private fun alphaOf(argb: Int) = (argb ushr 24).toFloat()

    private fun shape(c: Canvas, path: Path, i: Int, s: Float, color: Int) {
        paint.style = Paint.Style.FILL; paint.color = color
        c.save(); c.translate(x[i], y[i]); c.rotate(rot[i]); c.scale(s, s)
        c.drawPath(path, paint)
        c.restore()
    }
}

/**
 * Back effects: drawn BEHIND the keys, on the shell itself, so the colour shows through the gaps and around the keycaps
 * (like a paint splat on the board). Separate from both the theme and the front [TapEffect]; the two can be combined.
 */
enum class BackEffect(val id: String, val label: String) {
    OFF("off", "Off"),
    /** The built-in glass glow (AGSL light that leans toward the pressed key). Liquid Glass only, Android 13+; the default there. */
    PRESS_GLOW("pressglow", "Press glow (built-in)"),
    SPLAT("splat", "Paint splat"),
    GLOW("glow", "Glow bloom"),
    INK("ink", "Ink spread");

    companion object {
        const val PREF = "back_fx"
        fun of(id: String?): BackEffect = values().firstOrNull { it.id == id } ?: OFF
        /** Not chosen yet: Liquid Glass keeps its built-in glow (unless "Press glow" was turned off in an older build). */
        /** The built-in glow is the glass shader: Liquid Glass on Android 13+ only. */
        fun glowAvailable(c: Context) = KbTheme.current(c).isGlass && android.os.Build.VERSION.SDK_INT >= 33
        fun styles(c: Context) = values().filter { it != PRESS_GLOW || glowAvailable(c) }.map { it.id to it.label }
        fun current(c: Context): BackEffect {
            val s = Prefs.str(c, PREF, "")
            if (s.isNotEmpty()) return of(s).let { if (it == PRESS_GLOW && !glowAvailable(c)) OFF else it }
            val glass = KbTheme.current(c).isGlass && android.os.Build.VERSION.SDK_INT >= 33 && Prefs.bool(c, "glass_fx", true)
            return if (glass) PRESS_GLOW else OFF
        }
        /** True when the shell should light up around the pressed key (the glass shader). */
        fun pressGlow(c: Context) = current(c) == PRESS_GLOW
        val STYLES = values().map { it.id to it.label }
    }
}

/** Overlay under the keys (child 0 of the host, below the key rows). Clipped to the shell's rounded shape; never takes touches. */
class TapFxBackView(ctx: Context, private val t: KbTokens) : View(ctx) {
    private val n = 16
    private val pts = 10
    private val drops = 8
    private val alive = BooleanArray(n)
    private val kind = IntArray(n)
    private val cx = FloatArray(n); private val cy = FloatArray(n)
    private val age = FloatArray(n); private val life = FloatArray(n); private val size = FloatArray(n)
    private val col = IntArray(n)
    private val rad = Array(n) { FloatArray(pts) }
    private val dAng = Array(n) { FloatArray(drops) }
    private val dDist = Array(n) { FloatArray(drops) }
    private val dSize = Array(n) { FloatArray(drops) }
    private val px = FloatArray(pts); private val py = FloatArray(pts)

    private val d = ctx.resources.displayMetrics.density
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL }
    private val path = Path()
    private val clip = Path()
    private val rnd = java.util.Random()
    private val a = IntArray(2); private val b = IntArray(2)
    private var running = false
    private var last = 0L
    private val vivid = longArrayOf(0xFFFF4D6D, 0xFFFF9F1C, 0xFFFFD60A, 0xFF2EC4B6, 0xFF3A86FF, 0xFF8338EC, 0xFFFF70A6)

    init { isClickable = false; isFocusable = false; importantForAccessibility = IMPORTANT_FOR_ACCESSIBILITY_NO }
    override fun onMeasure(w: Int, h: Int) = setMeasuredDimension(resolveSize(0, w), resolveSize(0, h))

    override fun onSizeChanged(w: Int, h: Int, ow: Int, oh: Int) {
        clip.reset(); clip.addRoundRect(0f, 0f, w.toFloat(), h.toFloat(), t.containerRadius, t.containerRadius, Path.Direction.CW)
    }

    private fun r(lo: Float, hi: Float) = lo + rnd.nextFloat() * (hi - lo)

    private fun colorFor(e: BackEffect, mode: String): Int = when (mode) {
        "accent" -> t.accent
        "rainbow" -> vivid[rnd.nextInt(vivid.size)].toInt()
        else -> when (e) {
            BackEffect.SPLAT -> vivid[rnd.nextInt(vivid.size)].toInt()
            BackEffect.GLOW -> t.accent
            BackEffect.INK -> t.keyText
            BackEffect.OFF, BackEffect.PRESS_GLOW -> t.accent
        }
    }

    fun burst(src: View, lx: Float, ly: Float) {
        val e = BackEffect.current(context)
        if (e == BackEffect.OFF || e == BackEffect.PRESS_GLOW || width == 0) return     // press glow is drawn by the glass shell itself
        var i = -1
        for (k in 0 until n) if (!alive[k]) { i = k; break }
        if (i < 0) return
        src.getLocationInWindow(a); getLocationInWindow(b)
        alive[i] = true; kind[i] = e.ordinal; age[i] = 0f
        cx[i] = a[0] - b[0] + lx; cy[i] = a[1] - b[1] + ly
        col[i] = colorFor(e, Prefs.str(context, TapEffect.COLOR_PREF, "auto"))
        when (e) {
            BackEffect.SPLAT -> {
                life[i] = r(1.1f, 1.4f); size[i] = r(30f, 44f) * d
                for (j in 0 until pts) rad[i][j] = r(0.65f, 1.15f)
                for (j in 0 until drops) { dAng[i][j] = r(0f, 6.283f); dDist[i][j] = r(1.1f, 1.9f); dSize[i][j] = r(1.5f, 4.5f) * d }
            }
            BackEffect.GLOW -> { life[i] = 0.7f; size[i] = r(60f, 80f) * d }
            BackEffect.INK -> {
                life[i] = r(1.5f, 1.9f); size[i] = r(52f, 70f) * d
                for (j in 0 until pts) rad[i][j] = r(0.8f, 1.1f)
            }
            BackEffect.OFF, BackEffect.PRESS_GLOW -> Unit
        }
        if (!running) { running = true; last = System.nanoTime(); postInvalidateOnAnimation() }
    }

    /** A smooth closed blob around ([x], [y]): quadratic curves through the midpoints of [pts] points at radius [s] * factor. */
    private fun blob(i: Int, s: Float) {
        for (j in 0 until pts) {
            val ang = j * 6.2831855f / pts
            px[j] = cx[i] + cos(ang) * s * rad[i][j]; py[j] = cy[i] + sin(ang) * s * rad[i][j]
        }
        path.reset()
        path.moveTo((px[pts - 1] + px[0]) / 2f, (py[pts - 1] + py[0]) / 2f)
        for (j in 0 until pts) {
            val nx = (j + 1) % pts
            path.quadTo(px[j], py[j], (px[j] + px[nx]) / 2f, (py[j] + py[nx]) / 2f)
        }
        path.close()
    }

    private fun ease(p: Float) = 1f - (1f - p.coerceIn(0f, 1f)).pow(3)

    override fun onDraw(c: Canvas) {
        val now = System.nanoTime()
        val dt = ((now - last) / 1e9f).coerceIn(0f, 0.05f)
        last = now
        var any = false
        c.save(); c.clipPath(clip)
        for (i in 0 until n) {
            if (!alive[i]) continue
            age[i] += dt
            if (age[i] >= life[i]) { alive[i] = false; continue }
            any = true
            val p = age[i] / life[i]
            when (BACK_EFFECTS[kind[i]]) {
                BackEffect.SPLAT -> {
                    val grow = ease(p / 0.16f)
                    val fade = if (p < 0.55f) 1f else (1f - p) / 0.45f
                    paint.color = ColorUtils.setAlphaComponent(col[i], (150 * fade).toInt())
                    blob(i, size[i] * (0.25f + 0.75f * grow))
                    c.drawPath(path, paint)
                    for (j in 0 until drops) {
                        val dist = size[i] * dDist[i][j] * ease(p / 0.3f)
                        c.drawCircle(cx[i] + cos(dAng[i][j]) * dist, cy[i] + sin(dAng[i][j]) * dist, dSize[i][j] * (1f - 0.3f * p), paint)
                    }
                }
                BackEffect.GLOW -> {
                    val rr = size[i] * (0.35f + 0.65f * ease(p))
                    val al = 0.5f * (1f - p).pow(1.3f)
                    for (k in 0 until 5) {
                        paint.color = ColorUtils.setAlphaComponent(col[i], (255 * al * 0.22f).toInt())
                        c.drawCircle(cx[i], cy[i], rr * (1f - k * 0.18f), paint)
                    }
                }
                BackEffect.INK -> {
                    paint.color = ColorUtils.setAlphaComponent(col[i], (255 * 0.3f * (1f - p * p)).toInt())
                    blob(i, size[i] * ease(p))
                    c.drawPath(path, paint)
                    blob(i, size[i] * ease(p) * 0.6f)
                    c.drawPath(path, paint)
                }
                BackEffect.OFF, BackEffect.PRESS_GLOW -> Unit
            }
        }
        c.restore()
        if (any) postInvalidateOnAnimation() else running = false
    }
}
