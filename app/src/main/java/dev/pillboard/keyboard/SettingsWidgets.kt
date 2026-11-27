package dev.pillboard.keyboard

import android.animation.ValueAnimator
import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.Typeface
import android.view.MotionEvent
import android.view.View
import android.view.accessibility.AccessibilityNodeInfo
import androidx.core.graphics.ColorUtils
import kotlin.math.PI
import kotlin.math.sin

/**
 * iOS 26 switch (a wide capsule thumb on a capsule track) with Material 3 Expressive touches: the thumb stretches while
 * pressed and while it travels, a check mark grows inside it when on, and the thumb springs to the new state on the
 * keyboard's pop spring ([SettingsFeel]); the track tone follows, clamped (a spring overshoots, a colour must not).
 */
@SuppressLint("ViewConstructor")
internal class PillSwitch(
    ctx: Context, private val onColor: Int, private val offColor: Int, private val thumbColor: Int,
    private val thumbOnColor: Int = thumbColor,     // thumb when on: must contrast with [onColor] (a light accent in dark mode)
) : View(ctx) {
    var onToggle: ((Boolean) -> Unit)? = null
    var checked = false
        private set

    private var progress = 0f      // 0 = off, 1 = on
    private var stretch = 0f       // 0 = resting thumb, 1 = pressed thumb
    private var moveAnim: ValueAnimator? = null
    private var pressAnim: ValueAnimator? = null
    private val track = Paint(Paint.ANTI_ALIAS_FLAG)
    private val thumb = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = thumbColor }
    private val ring = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; strokeWidth = 1.dp.toFloat(); color = 0x26000000 }
    private val mark = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE; strokeCap = Paint.Cap.ROUND; strokeJoin = Paint.Join.ROUND; color = onColor
    }
    private val markPath = android.graphics.Path()
    private val r = RectF()
    private val inset = 2.dp.toFloat()

    init { isClickable = true; contentDescription = null }

    fun setChecked(v: Boolean, animate: Boolean) {
        checked = v
        moveAnim?.cancel()
        val to = if (v) 1f else 0f
        if (!animate || !animationsOn(context) || !isAttachedToWindow) { progress = to; invalidate(); return }
        moveAnim = ValueAnimator.ofFloat(progress, to).apply {
            duration = SettingsFeel.pop.settleMs
            interpolator = SettingsFeel.pop
            addUpdateListener { progress = it.animatedValue as Float; invalidate() }
            start()
        }
    }

    fun toggle() { setChecked(!checked, true); onToggle?.invoke(checked) }

    private fun pressTo(v: Float) {
        pressAnim?.cancel()
        if (!animationsOn(context)) { stretch = v; invalidate(); return }
        pressAnim = ValueAnimator.ofFloat(stretch, v).apply {
            // like a key: quick on the way down, spring on the way back
            duration = if (v > 0f) SettingsFeel.PRESS_MS else SettingsFeel.spring.settleMs
            interpolator = if (v > 0f) BlurTextTokens.outEase else SettingsFeel.spring
            addUpdateListener { stretch = it.animatedValue as Float; invalidate() }
            start()
        }
    }

    override fun onMeasure(w: Int, h: Int) = setMeasuredDimension(Metrics.SWITCH_W.dp, Metrics.SWITCH_H.dp)

    override fun onDraw(c: Canvas) {
        val w = width.toFloat(); val h = height.toFloat()
        val p = progress.coerceIn(0f, 1f)               // the thumb may overshoot on the spring; tone and mark may not
        track.color = ColorUtils.blendARGB(offColor, onColor, p)
        thumb.color = ColorUtils.blendARGB(thumbColor, thumbOnColor, p)
        r.set(0f, 0f, w, h)
        c.drawRoundRect(r, h / 2f, h / 2f, track)
        val d = h - inset * 2f
        val travel = 4f * p * (1f - p)                   // 0 at rest, 1 half way: the thumb stretches while it travels
        val tw = (d * 1.3f + stretch * 6.dp + travel * 5.dp).coerceAtLeast(d)
        val left = inset + (w - inset * 2f - tw) * progress
        r.set(left, inset, left + tw, inset + d)
        c.drawRoundRect(r, d / 2f, d / 2f, thumb)
        c.drawRoundRect(r, d / 2f, d / 2f, ring)
        if (p > 0.02f) {
            // check mark grows in with the track tone
            val cx = r.centerX(); val cy = r.centerY(); val k = d / 28f * p
            markPath.reset()
            markPath.moveTo(cx - 4.5f * k, cy + 0.4f * k); markPath.lineTo(cx - 1.4f * k, cy + 3.4f * k); markPath.lineTo(cx + 4.6f * k, cy - 3.2f * k)
            mark.strokeWidth = 2.2f * k.coerceAtLeast(0.01f)
            mark.alpha = (255 * p).toInt()
            c.drawPath(markPath, mark)
        }
    }

    @SuppressLint("ClickableViewAccessibility")
    override fun onTouchEvent(e: MotionEvent): Boolean {
        if (!isEnabled) return false
        when (e.actionMasked) {
            MotionEvent.ACTION_DOWN -> { parent?.requestDisallowInterceptTouchEvent(true); pressTo(1f) }
            MotionEvent.ACTION_UP -> {
                pressTo(0f)
                if (e.x in 0f..width.toFloat() && e.y in 0f..height.toFloat()) performClick()
            }
            MotionEvent.ACTION_CANCEL -> pressTo(0f)
        }
        return true
    }

    override fun performClick(): Boolean { toggle(); return super.performClick() }

    override fun getAccessibilityClassName(): CharSequence = "android.widget.Switch"
    override fun onInitializeAccessibilityNodeInfo(info: AccessibilityNodeInfo) {
        super.onInitializeAccessibilityNodeInfo(info)
        info.isCheckable = true
        info.isChecked = checked
    }

    override fun onDetachedFromWindow() { moveAnim?.cancel(); pressAnim?.cancel(); super.onDetachedFromWindow() }
}

/** iOS segmented control: the selected pill glides between segments; with no selection (custom value) the pill fades out. */
@SuppressLint("ViewConstructor")
internal class Segmented(
    ctx: Context, private val labels: List<String>,
    private val trackColor: Int, private val pillColor: Int, private val textOn: Int, private val textOff: Int, font: Typeface?,
) : View(ctx) {
    var onSelect: ((Int) -> Unit)? = null
    var selected = -1
        private set

    private var pos = 0f           // animated segment position (in segments)
    private var pillAlpha = 0f
    private var glide = 0f         // 0..1..0 while the pill travels: it stretches along its way
    private var anim: ValueAnimator? = null
    private val bg = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = trackColor }
    private val pill = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = pillColor }
    private val ring = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; strokeWidth = 1.dp.toFloat(); color = 0x14000000 }
    private val text = Paint(Paint.ANTI_ALIAS_FLAG).apply { textAlign = Paint.Align.CENTER; textSize = 14f * ctx.resources.displayMetrics.scaledDensity; typeface = font }
    private val fm = Paint.FontMetrics()
    private val r = RectF()
    private val pad = 3.dp.toFloat()

    init { isClickable = true }

    fun select(i: Int, animate: Boolean) {
        if (i == selected) return
        val from = pos; val fromA = pillAlpha
        val toPos = if (i >= 0) i.toFloat() else pos
        val toA = if (i >= 0) 1f else 0f
        selected = i
        anim?.cancel()
        if (!animate || !animationsOn(context) || !isAttachedToWindow || (fromA == 0f && i >= 0)) {
            pos = toPos; pillAlpha = toA; invalidate(); return
        }
        anim = ValueAnimator.ofFloat(0f, 1f).apply {
            duration = SettingsFeel.flow.settleMs
            interpolator = SettingsFeel.flow
            addUpdateListener {
                val f = it.animatedValue as Float
                pos = from + (toPos - from) * f
                pillAlpha = (fromA + (toA - fromA) * f).coerceIn(0f, 1f)
                glide = sin(PI * it.animatedFraction.coerceIn(0f, 1f)).toFloat().coerceAtLeast(0f) * if (toPos != from) 1f else 0f
                invalidate()
            }
            addListener(object : android.animation.AnimatorListenerAdapter() {
                override fun onAnimationEnd(animation: android.animation.Animator) { glide = 0f; invalidate() }
            })
            start()
        }
    }

    override fun onMeasure(w: Int, h: Int) = setMeasuredDimension(MeasureSpec.getSize(w), Metrics.SEGMENT_H.dp)

    override fun onDraw(c: Canvas) {
        val w = width.toFloat(); val h = height.toFloat()
        r.set(0f, 0f, w, h)
        c.drawRoundRect(r, h / 2f, h / 2f, bg)
        val n = labels.size.coerceAtLeast(1)
        val sw = (w - pad * 2f) / n
        if (pillAlpha > 0.01f) {
            pill.alpha = (Color.alpha(pillColor) * pillAlpha).toInt()
            ring.alpha = (0x14 * pillAlpha).toInt()
            val grow = glide * sw * 0.12f                  // the pill stretches a little along its way, like the switch thumb
            r.set((pad + sw * pos - grow).coerceAtLeast(0f), pad, (pad + sw * (pos + 1f) + grow).coerceAtMost(w), h - pad)
            c.drawRoundRect(r, (h - pad * 2f) / 2f, (h - pad * 2f) / 2f, pill)
            c.drawRoundRect(r, (h - pad * 2f) / 2f, (h - pad * 2f) / 2f, ring)
        }
        text.getFontMetrics(fm)
        val y = h / 2f - (fm.ascent + fm.descent) / 2f
        labels.forEachIndexed { i, l ->
            text.color = if (i == selected) textOn else textOff
            c.drawText(l, pad + sw * (i + 0.5f), y, text)
        }
    }

    @SuppressLint("ClickableViewAccessibility")
    override fun onTouchEvent(e: MotionEvent): Boolean {
        when (e.actionMasked) {
            MotionEvent.ACTION_DOWN -> { parent?.requestDisallowInterceptTouchEvent(true); return true }
            MotionEvent.ACTION_UP -> {
                val n = labels.size.coerceAtLeast(1)
                val i = (((e.x - pad) / ((width - pad * 2f) / n)).toInt()).coerceIn(0, n - 1)
                if (e.y in 0f..height.toFloat()) { select(i, true); onSelect?.invoke(i); performClick() }
                return true
            }
        }
        return true
    }

    override fun performClick(): Boolean = super.performClick()

    override fun onDetachedFromWindow() { anim?.cancel(); super.onDetachedFromWindow() }
}
