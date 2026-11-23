package dev.pillboard.keyboard

import android.content.Context
import android.graphics.Canvas
import android.graphics.LinearGradient
import android.graphics.Outline
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.graphics.Shader
import android.os.Build
import android.view.Gravity
import android.view.View
import android.view.ViewOutlineProvider
import android.widget.FrameLayout
import com.example.liquidglass.LiquidGlassView
import android.widget.PopupWindow
import androidx.core.graphics.ColorUtils

/**
 * Glimkey's own cursor loupe, shown while sliding on the space bar. It replaces the system (Android) loupe, which
 * only the app's text field can draw and a keyboard can never reach. The keyboard cannot see the app's pixels, so
 * the loupe is built from what the InputConnection gives us: the text just before and after the cursor, enlarged,
 * with a caret between. On API 33+ it is wrapped in a Liquid-Glass-Android glass, like the key-preview lens.
 */
class CursorLoupe(private val ctx: Context, private val t: KbTokens) {

    private val view = LoupeView(ctx, t)
    private val motionOn = animationsOn(ctx)
    private var popup: PopupWindow? = null
    private var fading = false
    private var screenX = 0f
    /** Caret in screen px (x, top, bottom) from the app's CursorAnchorInfo; null = unknown, loupe sits above the keyboard. */
    private var caret: FloatArray? = null

    private val w = minOf(ctx.resources.displayMetrics.widthPixels - 32.dp, 260.dp)
    private val h = 56.dp

    val showing get() = popup?.isShowing == true && !fading

    /** Show the loupe above the keyboard window, centered on [rawX] (screen x of the finger). */
    fun show(anchor: View, rawX: Float, before: String, after: String) {
        view.set(before, after)
        screenX = rawX
        val loc = IntArray(2); anchor.getLocationOnScreen(loc)
        val win = IntArray(2); anchor.getLocationInWindow(win)
        val ox = loc[0] - win[0]; val oy = loc[1] - win[1]            // IME window origin on screen
        val (x, y) = place(ox, oy)
        val p = popup ?: PopupWindow(view, w, h, false).also { it.isTouchable = false; popup = it }
        view.animate().cancel()
        fading = false
        if (p.isShowing) {
            p.update(x, y, w, h)
            view.alpha = 1f; view.scaleX = 1f; view.scaleY = 1f
        } else {
            p.width = w; p.height = h
            p.showAtLocation(anchor, Gravity.NO_GRAVITY, x, y)
            if (motionOn) {
                view.pivotX = w / 2f; view.pivotY = h.toFloat()
                view.alpha = 0f; view.scaleX = 0.88f; view.scaleY = 0.88f
                view.animate().alpha(1f).scaleX(1f).scaleY(1f).setDuration(t.popMs).setInterpolator(t.popSpring).start()
            } else { view.alpha = 1f; view.scaleX = 1f; view.scaleY = 1f }
        }
        origin = ox to oy
    }

    private var origin = 0 to 0

    /** New text around the cursor (the finger moved the cursor). */
    fun update(before: String, after: String) { view.set(before, after) }

    /** Where the app says its caret is (screen px). The loupe then floats right above the text, centered on it. */
    fun setCaret(x: Float, top: Float, bottom: Float) {
        caret = floatArrayOf(x, top, bottom)
        view.setCaretHeight(bottom - top)
        val p = popup ?: return
        if (!p.isShowing || fading) return
        val (px, py) = place(origin.first, origin.second)
        p.update(px, py, w, h)
    }

    /** Keep the loupe under the finger while it slides. */
    fun follow(rawX: Float) {
        val p = popup ?: return
        if (!p.isShowing || fading) return
        if (caret != null) return                 // the loupe follows the caret, not the finger
        if (Math.abs(rawX - screenX) < 1f) return
        screenX = rawX
        val (x, y) = place(origin.first, origin.second)
        p.update(x, y, w, h)
    }

    fun dismiss(immediate: Boolean = false) {
        caret = null
        view.animate().cancel()
        val p = popup ?: return
        if (immediate || !motionOn || !p.isShowing) { fading = false; p.dismiss(); return }
        fading = true
        view.animate().setUpdateListener(null).alpha(0f).setDuration(t.fadeOutMs).withEndAction {
            if (fading) { fading = false; p.dismiss() }
        }.start()
    }

    /** Window coordinates: x follows the finger (kept on screen), y sits just above the keyboard window. */
    private fun place(ox: Int, oy: Int): Pair<Int, Int> {
        val screenW = ctx.resources.displayMetrics.widthPixels
        val c = caret
        val cxs = c?.get(0) ?: screenX
        val sx = (cxs - w / 2f).toInt().coerceIn(8.dp, (screenW - w - 8.dp).coerceAtLeast(8.dp))
        // above the caret line when the app reports it, otherwise just above the keyboard window
        val sy = if (c != null) (c[1] - h - 10.dp).toInt().coerceAtLeast(8.dp) else (oy - h - 8.dp).coerceAtLeast(8.dp)
        return (sx - ox) to (sy - oy)
    }
}

/** The enlarged text around the cursor: before it right-aligned to the caret, after it left-aligned. */
private class SnippetView(ctx: Context, t: KbTokens) : View(ctx) {
    var before = ""
    var after = ""
    private val ink = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = t.keyText
        textSize = 24f * ctx.resources.displayMetrics.scaledDensity
    }

    /** Match the app's text: the caret is about one line tall, the glyphs about 0.75 of that, enlarged 1.25x. */
    fun setCaretHeight(px: Float) {
        if (px <= 0f) return
        ink.textSize = (px * 0.75f * 1.25f).coerceIn(14.dp.toFloat(), 40.dp.toFloat())
        invalidate()
    }
    private val caret = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = t.accent
        strokeWidth = 2.dp.toFloat()
        strokeCap = Paint.Cap.ROUND
    }

    private val body = t.popupFill
    private val flat = !t.isGlass
    private val border = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; strokeWidth = 2.dp.toFloat(); color = t.glassStroke }
    /** Soft light-to-dark sheen over the body: gives the top and bottom rim something to bend. */
    private val sheen = Paint()

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        sheen.shader = LinearGradient(0f, 0f, 0f, h.toFloat(), 0x24FFFFFF, 0x2E000000, Shader.TileMode.CLAMP)
    }

    override fun onDraw(canvas: Canvas) {
        canvas.drawColor(body)
        if (flat) canvas.drawRect(0f, 0f, width.toFloat(), height.toFloat(), border)
        else canvas.drawRect(0f, 0f, width.toFloat(), height.toFloat(), sheen)
        val cx = width / 2f
        val base = height / 2f - (ink.ascent() + ink.descent()) / 2f
        ink.textAlign = Paint.Align.RIGHT
        canvas.drawText(before, cx - 3.dp, base, ink)
        ink.textAlign = Paint.Align.LEFT
        canvas.drawText(after, cx + 3.dp, base, ink)
        canvas.drawLine(cx, base + ink.ascent() - 1.dp, cx, base + ink.descent() + 1.dp, caret)
    }
}

/**
 * The loupe body: the snippet underneath and, on API 33+, a Liquid-Glass-Android [LiquidGlassView] on top of it. The
 * glass's backdrop is its parent (this view), i.e. the snippet, so the enlarged text bends and fringes at the rim.
 * The keyboard cannot read the app's pixels, so the snippet is the only thing there is to refract. Blur is off so
 * the text stays sharp. Older: the snippet alone in a rounded pill.
 */
private class LoupeView(ctx: Context, t: KbTokens) : FrameLayout(ctx) {
    private val snippet = SnippetView(ctx, t)
    private val glass: LiquidGlassView? = if (t.isGlass && Build.VERSION.SDK_INT >= 33) LiquidGlassView(ctx).apply {
        cornerRadius = 999f
        bevelWidth = 18.dp.toFloat()
        refractionHeight = 18.dp.toFloat()
        dispersionStrength = 0.06f
        enableBackdropBlur = false
        saturation = 100f
        glassTint = ColorUtils.setAlphaComponent(t.key, 0x30)
        enablePressEffect = false
    } else null

    init {
        // the snippet is a plain rectangle: the pill outline clips it (and the glass) to the capsule
        outlineProvider = object : ViewOutlineProvider() {
            override fun getOutline(v: View, o: Outline) { o.setRoundRect(0, 0, v.width, v.height, if (t.isGlass) v.height / 2f else t.popupRadius) }
        }
        clipToOutline = true
        addView(snippet, LayoutParams(-1, -1))
        glass?.let { addView(it, LayoutParams(-1, -1)) }
    }

    fun setCaretHeight(px: Float) { snippet.setCaretHeight(px); glass?.invalidate() }

    fun set(before: String, after: String) {
        snippet.before = before
        snippet.after = after
        snippet.invalidate()
        glass?.invalidate()          // the glass only re-captures its backdrop when it is redrawn itself
    }
}
