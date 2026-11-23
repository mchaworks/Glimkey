package dev.pillboard.keyboard

import android.content.Context
import android.view.View
import com.example.liquidglass.LiquidGlassView

/**
 * The settings search capsule as real liquid glass, now the Liquid-Glass-Android [LiquidGlassView].
 * The settings page owns what floats under this capsule, so [src] (the list, a sibling that fills the same parent,
 * never an ancestor or child of this view) is its backdrop: rows that slide under it bend and fringe at the rim.
 * The icon and the field are ordinary children drawn over the glass. The library picks its own pipeline per API
 * level (AGSL lens on 33+, blur fallbacks below), so no separate fallback drawable is needed.
 *
 * The glass only re-captures while [pulse]d (typing, page change; scrolling re-captures on its own through the
 * library's scroll listener), so it costs nothing at rest.
 */
class GlassBar(
    ctx: Context,
    private val src: () -> View?,
    tint: Int,
) : LiquidGlassView(ctx) {

    private val idle = Runnable { enableDynamicBackground = false; invalidate() }

    init {
        cornerRadius = 999f                       // full capsule (the library clamps to half the height)
        bevelWidth = 20.dp.toFloat()
        refractionHeight = 16.dp.toFloat()
        dispersionStrength = 0.08f
        blurAmount = 0.12f
        glassTint = tint                          // straight ARGB: the alpha is the strength, keeps the field text readable
        enablePressEffect = false                 // a text field lives inside; no press squash while typing
    }

    override fun onAttachedToWindow() {
        if (backdropSource == null) backdropSource = src()
        super.onAttachedToWindow()
    }

    override fun onDetachedFromWindow() {
        removeCallbacks(idle)
        enableDynamicBackground = false
        super.onDetachedFromWindow()
    }

    /** Keep re-capturing the glass for [ms] (the content under it is moving or changing). */
    fun pulse(ms: Long = 400) {
        enableDynamicBackground = true
        removeCallbacks(idle)
        postDelayed(idle, ms)
    }
}
