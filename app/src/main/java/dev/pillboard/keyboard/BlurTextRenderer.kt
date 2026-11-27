package dev.pillboard.keyboard

import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RenderEffect
import android.graphics.RenderNode
import android.graphics.Shader
import android.os.Build
import android.text.TextPaint
import android.text.TextUtils
import android.view.Gravity
import android.widget.TextView
import androidx.annotation.RequiresApi
import kotlin.math.round

/**
 * One piece of text taking part in a transition. A single scalar [s] drives everything: 0 = sharp, opaque, in place;
 * 1 = fully blurred, transparent, shrunk and drifted. Outgoing layers run s -> 1, incoming layers 1 -> 0, so a layer
 * interrupted half way simply reverses direction from where it is (no jump).
 */
internal class BlurTextLayer(var text: CharSequence, val sign: Int) {
    var s = 0f
    var from = 0f
    var to = 0f
    var startAt = -1L       // uptime ms; -1 = not started yet (view not laid out)
    var delay = 0L
    var dur = 1L
    var outgoing = false
    var mx = 0f             // travel direction (unit vector, screen coords), see [BlurDir]
    var my = -1f
    var node: Any? = null   // API 31+ RenderNode holder (see [NodeHolder])
    // placement cache (see BlurTextRenderer.layout): measured once per host size/font, not every frame
    var cValid = false
    var cSig = 0
    var cTxt: CharSequence? = null
    var cX = 0f; var cY = 0f; var cPx = 0f; var cPy = 0f
    var cKey = 0

    fun copy() = BlurTextLayer(text, sign).also {
        it.s = s; it.from = from; it.to = to; it.startAt = startAt; it.delay = delay; it.dur = dur; it.outgoing = outgoing; it.mx = mx; it.my = my
    }
}

@RequiresApi(31)
private class NodeHolder {
    val node = RenderNode("blurText")
    var key = 0
    var radius = -1f
}

/**
 * Draws a [BlurTextLayer] over its host [TextView] with the host's own paint (font, size, colour, gravity).
 * API 31+ on a hardware canvas: text is recorded once into a reused RenderNode, blur is a RenderEffect on that node
 * (rebuilt only when the quantised radius changes), alpha/scale/translation are node properties: no per-frame
 * bitmaps and no re-recording. Anything else (older API, software canvas, any failure): same alpha + scale + drift
 * without the blur, drawn directly.
 */
internal class BlurTextRenderer(private val density: Float, private val lite: Boolean = false) {
    private val paint = TextPaint(Paint.ANTI_ALIAS_FLAG)
    private val fm = Paint.FontMetrics()

    private companion object {
        @Volatile var nodeFailed = false
    }

    fun release(l: BlurTextLayer) {
        if (Build.VERSION.SDK_INT >= 31) (l.node as? NodeHolder)?.node?.discardDisplayList()
        l.node = null
    }

    fun draw(canvas: Canvas, host: TextView, l: BlurTextLayer) {
        val s = l.s.coerceIn(0f, 1f)
        val k = 1f - s
        val alpha = k * k
        if (alpha < 0.004f || l.text.isEmpty()) return
        val w = host.width
        val h = host.height
        if (w <= 0 || h <= 0 || w - host.compoundPaddingLeft - host.compoundPaddingRight <= 0) return
        layout(host, l, w, h)

        val scale = 1f - (1f - BlurTextTokens.SCALE_MIN) * s
        // outgoing (sign -1) travels along the direction, incoming (sign +1) starts behind it and travels along it too
        val dist = -l.sign * BlurTextTokens.SHIFT_DP * density * s
        val tx = l.mx * dist
        val ty = l.my * dist
        val step = BlurTextTokens.BLUR_STEP_PX
        val radius = round(BlurTextTokens.MAX_BLUR_DP * density * s / step) * step

        if (!lite && radius > 0f && Build.VERSION.SDK_INT >= 31 && !nodeFailed && canvas.isHardwareAccelerated) {
            try {
                drawNode(canvas, host, l, w, h, alpha, scale, tx, ty, radius)
                return
            } catch (e: Throwable) {
                nodeFailed = true       // fall through to the plain path from now on
            }
        }
        paint.set(host.paint)
        paint.color = host.currentTextColor
        paint.alpha = (paint.alpha * alpha).toInt().coerceIn(0, 255)
        val save = canvas.save()
        canvas.translate(tx, ty)
        canvas.scale(scale, scale, l.cPx, l.cPy)
        val txt = l.cTxt ?: l.text
        canvas.drawText(txt, 0, txt.length, l.cX, l.cY, paint)
        canvas.restoreToCount(save)
    }

    /**
     * Where the label sits (same placement the TextView uses: horizontal by gravity, vertically on its own baseline, so
     * the last frame is pixel-identical to the native text). It only depends on the host's size, font and padding, so it
     * is measured once per layer and reused for every frame of the transition.
     */
    private fun layout(host: TextView, l: BlurTextLayer, w: Int, h: Int) {
        var sig = w
        sig = 31 * sig + h
        sig = 31 * sig + host.textSize.toBits()
        sig = 31 * sig + host.currentTextColor
        sig = 31 * sig + System.identityHashCode(host.typeface)
        sig = 31 * sig + host.compoundPaddingLeft
        sig = 31 * sig + host.compoundPaddingRight
        sig = 31 * sig + host.compoundPaddingTop
        sig = 31 * sig + host.compoundPaddingBottom
        sig = 31 * sig + host.gravity
        sig = 31 * sig + host.layoutDirection
        sig = 31 * sig + host.baseline
        if (l.cValid && l.cSig == sig) return

        val avail = (w - host.compoundPaddingLeft - host.compoundPaddingRight).toFloat()
        paint.set(host.paint)
        paint.color = host.currentTextColor
        var txt = l.text
        if (paint.measureText(txt, 0, txt.length) > avail) txt = TextUtils.ellipsize(txt, paint, avail, TextUtils.TruncateAt.END)
        val tw = paint.measureText(txt, 0, txt.length)
        paint.getFontMetrics(fm)
        val left = host.compoundPaddingLeft.toFloat()
        val x = when (Gravity.getAbsoluteGravity(host.gravity, host.layoutDirection) and Gravity.HORIZONTAL_GRAVITY_MASK) {
            Gravity.LEFT -> left
            Gravity.RIGHT -> left + avail - tw
            else -> left + (avail - tw) / 2f
        }
        val bl = host.baseline
        val y = if (bl >= 0) bl.toFloat() else {
            val top = host.compoundPaddingTop.toFloat()
            val bottom = (h - host.compoundPaddingBottom).toFloat()
            if (host.includeFontPadding) (top + bottom) / 2f - (fm.top + fm.bottom) / 2f
            else (top + bottom) / 2f - (fm.ascent + fm.descent) / 2f
        }
        l.cTxt = txt
        l.cX = x; l.cY = y
        l.cPx = x + tw / 2f
        l.cPy = y + (fm.ascent + fm.descent) / 2f
        var key = txt.toString().hashCode()
        key = 31 * key + paint.textSize.toBits()
        key = 31 * key + paint.color
        key = 31 * key + (paint.typeface?.hashCode() ?: 0)
        key = 31 * key + x.toBits()
        key = 31 * key + y.toBits()
        key = 31 * key + w
        key = 31 * key + h
        l.cKey = key
        l.cSig = sig
        l.cValid = true
    }

    @RequiresApi(31)
    private fun drawNode(
        canvas: Canvas, host: TextView, l: BlurTextLayer, w: Int, h: Int,
        alpha: Float, scale: Float, tx: Float, ty: Float, radius: Float,
    ) {
        val hold = (l.node as? NodeHolder) ?: NodeHolder().also { l.node = it }
        val n = hold.node
        if (l.cKey != hold.key || !n.hasDisplayList()) {
            hold.key = l.cKey
            paint.set(host.paint)
            paint.color = host.currentTextColor
            val txt = l.cTxt ?: l.text
            n.setPosition(0, 0, w, h)
            val rc = n.beginRecording(w, h)
            try { rc.drawText(txt, 0, txt.length, l.cX, l.cY, paint) } finally { n.endRecording() }
        }
        if (radius != hold.radius) {
            hold.radius = radius
            n.setRenderEffect(BlurEffects.get(radius))
        }
        n.setPivotX(l.cPx); n.setPivotY(l.cPy)
        n.setScaleX(scale); n.setScaleY(scale)
        n.setTranslationX(tx)
        n.setTranslationY(ty)
        n.setAlpha(alpha)
        canvas.drawRenderNode(n)
    }
}

/** Blur effects are immutable and shareable between nodes: one per radius step for the whole process, so a transition never allocates one per frame. */
@RequiresApi(31)
private object BlurEffects {
    private val cache = HashMap<Int, RenderEffect>()
    fun get(radius: Float): RenderEffect =
        cache.getOrPut(Math.round(radius * 4f)) { RenderEffect.createBlurEffect(radius, radius, Shader.TileMode.DECAL) }
}
