package dev.pillboard.keyboard

import android.animation.ArgbEvaluator
import android.animation.ValueAnimator
import android.content.Context
import android.graphics.Canvas
import android.graphics.Outline
import android.graphics.Paint
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.view.ViewOutlineProvider
import android.view.animation.DecelerateInterpolator
import android.widget.FrameLayout
import android.widget.PopupWindow

/**
 * Gboard-style long-press hint for a letter key.
 *
 * Pressing a key with a hint shows a round bubble above it: the letter with three dots ("hold for more").
 * When the hold fires, the bubble turns into an accent circle with the hint character; letting go types it.
 *
 * Like [KeyPreview] it is ONE reusable window per keyboard, parked invisible between presses: adding and removing
 * a window on every tap is what makes typing feel heavy.
 */
class HintBubble(ctx: Context, private val t: KbTokens) {
    private val motionOn = animationsOn(ctx)
    private val d = 56.dp
    private val pad = KbTokens.POPUP_SHADOW_PAD
    private val face = Face(ctx, t)
    private val holder = shadowHolder(ctx, face)
    private var popup: PopupWindow? = null
    private var owner: View? = null
    private var parked = false
    private var parkX = 0
    private var parkY = 0
    private var mixAnim: ValueAnimator? = null
    private val handler = android.os.Handler(android.os.Looper.getMainLooper())
    private val reap = Runnable { if (parked) { parked = false; popup?.dismiss() } }

    /** Plain state: [letter] with three dots, above [key]. */
    fun show(key: View, letter: String, hint: String) {
        handler.removeCallbacks(reap)
        parked = false
        mixAnim?.cancel()
        face.mix = 0f
        face.letter = letter
        face.hint = hint
        face.visibility = View.VISIBLE
        val ww = d + pad * 2
        val loc = IntArray(2); key.getLocationOnScreen(loc)
        val win = IntArray(2); key.getLocationInWindow(win)
        val ox = loc[0] - win[0]; val oy = loc[1] - win[1]     // IME window origin on screen
        val screenW = key.resources.displayMetrics.widthPixels
        val bx = (loc[0] + key.width / 2 - d / 2).coerceIn(2.dp, (screenW - d - 2.dp).coerceAtLeast(2.dp))
        val x = (bx - pad).coerceAtLeast(0)
        val y = (loc[1] + 6.dp - d - pad).coerceAtLeast(0)
        parkX = x - ox; parkY = (loc[1] - oy).coerceAtLeast(0)
        owner = key
        val p = popup ?: PopupWindow(holder, ww, ww, false).also { it.isTouchable = false; popup = it }
        if (p.isShowing) {
            p.update(x - ox, y - oy, ww, ww)
        } else {
            p.width = ww; p.height = ww
            p.showAtLocation(key, Gravity.NO_GRAVITY, x - ox, y - oy)
        }
        popIn(face, d, d, t.popupInMs, motionOn)
    }

    /** The hold fired: the dark bubble becomes the accent circle that shows the hint. */
    fun fire() {
        if (owner == null) return
        mixAnim?.cancel()
        if (!motionOn) { face.mix = 1f; return }
        mixAnim = ValueAnimator.ofFloat(face.mix, 1f).apply {
            duration = 140L
            interpolator = DecelerateInterpolator()
            addUpdateListener { face.mix = it.animatedValue as Float }
            start()
        }
        face.animate().cancel()
        face.scaleX = 0.9f; face.scaleY = 0.9f
        face.animate().scaleX(1f).scaleY(1f).setDuration(t.popMs).setInterpolator(t.popSpring).start()
    }

    /** Hide only if [key] is the one currently shown (fast typing can overlap presses). */
    fun hide(key: View) { if (owner === key) dismiss() }

    fun dismiss(immediate: Boolean = false) {
        owner = null
        mixAnim?.cancel()
        face.animate().cancel()
        val p = popup ?: return
        if (immediate || !p.isShowing) {
            parked = false
            handler.removeCallbacks(reap)
            p.dismiss(); return
        }
        if (!motionOn) { park(p); return }
        face.animate().setUpdateListener(null).alpha(0f).setDuration(t.fadeOutMs).withEndAction {
            if (owner == null) park(p)
        }.start()
    }

    private fun park(p: PopupWindow) {
        if (!p.isShowing) return
        try { p.update(parkX, parkY, -1, -1) } catch (_: Exception) { }
        face.visibility = View.INVISIBLE
        parked = true
        handler.removeCallbacks(reap)
        handler.postDelayed(reap, PARK_MS)
    }

    private companion object { const val PARK_MS = 2500L }

    /** The round bubble. [mix] 0 = letter + dots on the popup tone, 1 = hint on the accent colour. */
    private class Face(ctx: Context, private val t: KbTokens) : View(ctx) {
        var letter = ""
        var hint = ""
        var mix = 0f
            set(v) { field = v; invalidate() }

        private val argb = ArgbEvaluator()
        private val bg = Paint(Paint.ANTI_ALIAS_FLAG)
        private val txt = Paint(Paint.ANTI_ALIAS_FLAG).apply { textAlign = Paint.Align.CENTER }
        private val dot = Paint(Paint.ANTI_ALIAS_FLAG)

        init {
            elevation = t.popupElevation
            outlineProvider = object : ViewOutlineProvider() {
                override fun getOutline(v: View, o: Outline) { o.setOval(0, 0, v.width, v.height) }
            }
        }

        private fun sp(v: Float) = TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_SP, v, resources.displayMetrics)

        override fun onDraw(c: Canvas) {
            val cx = width / 2f
            val cy = height / 2f
            bg.color = argb.evaluate(mix, t.popupFill, t.accent) as Int
            c.drawCircle(cx, cy, minOf(width, height) / 2f, bg)

            val plain = ((1f - mix) * 255).toInt().coerceIn(0, 255)
            val chosen = (mix * 255).toInt().coerceIn(0, 255)
            if (plain > 0) {
                txt.textSize = sp(26f)
                txt.color = androidx.core.graphics.ColorUtils.setAlphaComponent(t.keyText, plain)
                val fm = txt.fontMetrics
                c.drawText(letter, cx, cy - (fm.ascent + fm.descent) / 2f - 4.dp, txt)
                dot.color = androidx.core.graphics.ColorUtils.setAlphaComponent(t.keyText, (plain * 0.6f).toInt())
                val r = 1.4f * resources.displayMetrics.density
                val gap = 5.dp.toFloat()
                for (i in -1..1) c.drawCircle(cx + i * gap, cy + 15.dp, r, dot)
            }
            if (chosen > 0) {
                txt.textSize = sp(28f)
                txt.color = androidx.core.graphics.ColorUtils.setAlphaComponent(t.onAccent, chosen)
                val fm = txt.fontMetrics
                c.drawText(hint, cx, cy - (fm.ascent + fm.descent) / 2f, txt)
            }
        }
    }
}

/** Gboard's long-press hints on the letter page. */
private val KEY_HINTS = mapOf(
    'q' to "1", 'w' to "2", 'e' to "3", 'r' to "4", 't' to "5", 'y' to "6", 'u' to "7", 'i' to "8", 'o' to "9", 'p' to "0",
    'a' to "@", 's' to "#", 'd' to "$", 'f' to "_", 'g' to "&", 'h' to "-", 'j' to "+", 'k' to "(", 'l' to ")",
    'z' to "*", 'x' to "\"", 'c' to "'", 'v' to ":", 'b' to ";", 'n' to "!", 'm' to "?",
)

/** The hint printed on [c]'s key, or null. The digits are left out when the number row already shows 1–0. */
internal fun keyHint(c: Char, numRowOn: Boolean): String? {
    val h = KEY_HINTS[c.lowercaseChar()] ?: return null
    return if (numRowOn && h[0].isDigit()) null else h
}
