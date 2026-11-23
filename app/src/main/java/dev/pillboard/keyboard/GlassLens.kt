package dev.pillboard.keyboard

import android.content.Context
import android.os.Build
import android.view.Gravity
import android.view.View
import android.widget.FrameLayout
import android.widget.TextView
import androidx.core.graphics.ColorUtils
import com.example.liquidglass.LiquidGlassView

/**
 * The glass bubble shown above a pressed key, now a Liquid-Glass-Android [LiquidGlassView]. It sits in the key
 * preview's own popup window; its backdrop is the keyboard window ([View.getRootView] of the pressed key), which the
 * library samples by screen position, so the keys under the bubble bend and fringe at its rim. The glyph is an
 * ordinary child drawn over the glass.
 *
 * Unlike the old hand-written lens this does not magnify: the library refracts what is behind the glass, and the
 * pressed key itself is below the bubble, not behind it. The big glyph carries the "what did I press" job instead.
 * API 33+ only (the key lens stays off below it and [KeyPreview] uses the plain bubble).
 */
class LensView(ctx: Context, private val t: KbTokens) : LiquidGlassView(ctx) {

    private val glyph = TextView(ctx).apply {
        gravity = Gravity.CENTER
        textSize = 30f
        includeFontPadding = false
        setTextColor(t.keyText)
    }

    init {
        cornerRadius = t.popupRadius
        bevelWidth = 12.dp.toFloat()
        refractionHeight = 9.dp.toFloat()
        dispersionStrength = 0.12f
        blurAmount = 0.10f
        glassTint = ColorUtils.setAlphaComponent(t.key, 0x59)
        enablePressEffect = false                 // the popup never takes touches; the preview animates the view itself
        addView(glyph, FrameLayout.LayoutParams(-1, -1, Gravity.CENTER))
    }

    /** 0 = just left the key, 1 = fully formed. Only the glyph eases; the preview springs the whole bubble. */
    var morph = 1f
        set(v) {
            if (field == v) return
            field = v
            val s = 0.82f + 0.18f * v.coerceIn(0f, 1.25f)
            glyph.scaleX = s; glyph.scaleY = s
        }

    /** Point the bubble at [key]: the keyboard window is the backdrop, [g] the glyph. */
    private val recapture = Runnable { invalidate() }

    fun aim(key: View, g: String) {
        glyph.text = g
        backdropSource = key.rootView
        invalidate()
        // the key's own press scale (65ms) is still running: capture once more when it has settled.
        // One pending runnable, so rolling typing does not stack a capture per tap.
        removeCallbacks(recapture)
        postDelayed(recapture, 80)
    }

    override fun onDetachedFromWindow() {
        removeCallbacks(recapture)
        super.onDetachedFromWindow()
    }

    companion object {
        fun available() = Build.VERSION.SDK_INT >= 33
    }
}
