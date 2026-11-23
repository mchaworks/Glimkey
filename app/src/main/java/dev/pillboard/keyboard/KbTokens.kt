package dev.pillboard.keyboard

import android.content.Context
import android.content.res.Configuration
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.ColorDrawable
import android.graphics.drawable.Drawable
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.StateListDrawable
import androidx.core.graphics.ColorUtils
import com.google.android.material.color.MaterialColors
import com.google.android.material.R as MR

/**
 * Liquid Expressive design tokens: every color, radius, elevation and timing the keyboard uses.
 * Resolved from the (dynamic or static) Material theme of [ctx]. Components must read from here
 * instead of hard-coding values, so the keyboard stays one coherent product.
 */
class KbTokens(private val ctx: Context, val theme: KbTheme = KbTheme.current(ctx)) {
    companion object {
        const val KEY_R_DEFAULT = 10
        /** Shadow room around floating popups (a popup window clips anything outside its bounds). */
        val POPUP_SHADOW_PAD get() = 10.dp
    }

    private fun c(attr: Int, fb: Int): Int = MaterialColors.getColor(ctx, attr, fb)
    val night = ctx.resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK == Configuration.UI_MODE_NIGHT_YES

    // ---- theme: Liquid Glass resolves colours from the Material theme (below), every other theme from its fixed palette ----
    val isGlass = theme.isGlass
    val skin = theme.skin
    private val p: Palette? = if (isGlass) null else theme.palette(night)
    /** Face of the theme: null keeps the keyboard's own font (Google Sans Rounded). */
    val typeface: Typeface? = when (theme) {
        KbTheme.GLASS -> null
        KbTheme.PAPER -> Typeface.create("serif", Typeface.NORMAL)
        KbTheme.TERMINAL -> Typeface.create("monospace", Typeface.BOLD)
        KbTheme.MECHANICAL -> Typeface.create("sans-serif-medium", Typeface.NORMAL)
        KbTheme.CLAY -> null
        KbTheme.PIXEL -> pixelFace()
        KbTheme.LCD -> Typeface.create("sans-serif-condensed", Typeface.BOLD)
    }

    /** Pixel font: drop any pixel TTF (e.g. Press Start 2P / Silkscreen, OFL) at assets/fonts/pixel.ttf; bold monospace otherwise. */
    private fun pixelFace(): Typeface = try { Typeface.createFromAsset(ctx.assets, "fonts/pixel.ttf") }
        catch (_: Exception) { Typeface.create("monospace", Typeface.BOLD) }

    // ---- geometry ----
    val containerRadius = theme.shellRadiusDp.dp.toFloat()
    val stripRadius = theme.stripRadiusDp.dp.toFloat()      // glass: near-pill, the strip reads as a capsule inside the shell
    val popupRadius = theme.popupRadiusDp.dp.toFloat()
    val specialRadiusBoost = 0.dp      // iOS 26: every keycap shares one corner radius
    val actionRadiusBoost = if (isGlass) 2.dp else 0.dp       // Enter / active Shift: a touch rounder (glass only)
    val pressRadiusBoost = if (isGlass) 2.dp else 0.dp        // corner morph while pressed (glass only)

    // ---- elevation ----
    // the other skins draw their own depth (hard shadow, skirt), so no system shadow under their keys
    val keyElevation = (if (isGlass) 1f else 0f) * ctx.resources.displayMetrics.density
    val popupElevation = when (skin) {
        KeySkin.FLAT -> 6.dp.toFloat()
        KeySkin.PAPER -> 2.dp.toFloat()
        KeySkin.TERMINAL -> 0f
        KeySkin.MECHANICAL -> 4.dp.toFloat()
        KeySkin.CLAY -> 6.dp.toFloat()
        KeySkin.PIXEL -> 0f
        KeySkin.LCD -> 0f
    }

    // ---- motion (kept lightweight; input never waits on these) ----
    val pressMs = if (isGlass) 65L else 40L
    val pressScale = 0.94f
    /** How far (dp per axis) a pressed key shrinks. Only glass squeezes; paper barely, terminal and mechanical not at all (they move another way). */
    val pressShrinkDp = when (skin) { KeySkin.FLAT -> 4; KeySkin.PAPER -> 2; KeySkin.CLAY -> 2; else -> 0 }
    // glass: a small controlled overshoot; paper: soft; terminal: instant (critically damped); mechanical: a quick tiny bounce
    val springDamping = when (skin) { KeySkin.FLAT -> 0.62f; KeySkin.PAPER -> 0.80f; KeySkin.TERMINAL -> 0.98f; KeySkin.MECHANICAL -> 0.55f; KeySkin.CLAY -> 0.85f; KeySkin.PIXEL -> 0.98f; KeySkin.LCD -> 0.98f }
    val springOmega = when (skin) { KeySkin.FLAT -> 22f; KeySkin.PAPER -> 30f; KeySkin.TERMINAL -> 80f; KeySkin.MECHANICAL -> 40f; KeySkin.CLAY -> 26f; KeySkin.PIXEL -> 90f; KeySkin.LCD -> 90f }
    val spring = SpringInterpolator(springDamping, springOmega)
    val releaseMs = spring.settleMs    // ~340ms
    val morphMs = 180L                 // shape/tone change (Shift states)
    val radiusReleaseMs = 140L         // corner morph back after a press
    /** Preview bubble: a quicker, slightly firmer spring so it springs out of the key rather than fading in. */
    val popSpring = if (isGlass) SpringInterpolator(0.70f, 30f) else SpringInterpolator(springDamping.coerceAtLeast(0.75f), springOmega * 1.2f)
    val popMs = popSpring.settleMs
    /** Suggestion words changing: gentle spring settle. */
    val flowSpring = SpringInterpolator(0.78f, 28f)
    val flowMs = flowSpring.settleMs
    val popupInMs = 100L
    val fadeOutMs = 70L
    val rowInMs = 140L                 // ?123 <-> ABC page change
    /** Android's window-blur guidance: ~80px reads well, above ~150px costs real performance. */
    val blurRadius = minOf(28.dp, 110)

    // ---- color: glass belongs to the shell, keycaps stay solid tonal surfaces ----
    // Light theme: shell sits a notch darker so white keycaps separate from it (validated contrast ~1.24:1).
    val background = p?.bg ?: c(MR.attr.colorSurfaceContainerLow, if (night) 0xFF1C1B1F.toInt() else 0xFFF3EDF7.toInt())
        .let { if (night) tone(it, Color.BLACK, 0.30f) else tone(it, Color.BLACK, 0.05f) }
    // non-glass shells are opaque: they never blur and are drawn by GlassSurfaceDrawable's themed path
    val glassFill = p?.bg ?: ColorUtils.setAlphaComponent(background, if (night) 0xD8 else 0xE8)
    /** Real backdrop blur keeps text legible, so the shell can be more translucent. */
    val glassFillBlur = p?.bg ?: ColorUtils.setAlphaComponent(background, if (night) 0xB4 else 0xC8)
    /** Blur requested but unavailable: no blur behind, so stay nearly opaque for readability. */
    val glassFillNoBlur = p?.bg ?: ColorUtils.setAlphaComponent(background, 0xF2)
    val glassStroke = p?.shellLine ?: ColorUtils.setAlphaComponent(c(MR.attr.colorOutlineVariant, if (night) Color.WHITE else Color.BLACK), if (night) 0x48 else 0x30)
    val glassHighlight = p?.let { ColorUtils.setAlphaComponent(it.keyText, 0x40) } ?: ColorUtils.setAlphaComponent(Color.WHITE, if (night) 0x24 else 0x66)
    val key = p?.key ?: if (night) tone(c(MR.attr.colorSurfaceContainerHighest, 0xFF36343B.toInt()), Color.WHITE, 0.10f)
              else c(MR.attr.colorSurfaceContainerLowest, Color.WHITE)
    val keyText = p?.keyText ?: c(MR.attr.colorOnSurface, if (night) Color.WHITE else Color.BLACK)
    // the user's accent / Material You only drive Liquid Glass; every other theme has its own accent
    private val seed: Int? = if (!isGlass || Prefs.dynamic(ctx)) null else Prefs.seedColor(ctx)
    /** Paper: ruled lines. Terminal: phosphor glow. */
    val ruleColor = p?.rule ?: Color.TRANSPARENT

    // iOS 26 direction: neutral gray keycaps (special keys one step darker), accent only for the primary action
    private val hint = seed ?: c(MR.attr.colorPrimary, 0xFF6750A4.toInt())
    val special = p?.special ?: ColorUtils.blendARGB(tone(key, Color.BLACK, if (night) 0.30f else 0.20f), hint, 0.06f)
    val specialText = p?.specialText ?: keyText
    val pressed = p?.pressed ?: if (night) tone(key, Color.WHITE, 0.16f) else tone(key, Color.BLACK, 0.12f)
    val pressedText = p?.pressedText ?: keyText
    /** What a special key turns into while pressed: iOS flips it to the letter-key tone. */
    val pressedSpecial = p?.pressedSpecial ?: key
    val accent = p?.accent ?: seed ?: c(MR.attr.colorPrimary, Color.BLUE)
    val onAccent = p?.onAccent ?: seed?.let { if (ColorUtils.calculateLuminance(it) > 0.5) Color.BLACK else Color.WHITE }
        ?: c(MR.attr.colorOnPrimary, Color.WHITE)
    val keyStroke = ColorUtils.setAlphaComponent(keyText, if (night) 0x22 else 0x18)
    val accentPressed = tone(accent, Color.BLACK, 0.18f)

    // suggestion strip: a faint tonal wash of the text color over the glass, so it belongs to the shell
    val stripFill = p?.stripFill ?: Color.TRANSPARENT          // iOS 26: no capsule, just words and dividers
    val stripStroke = p?.stripStroke ?: Color.TRANSPARENT
    val stripPressed = p?.stripPressed ?: ColorUtils.setAlphaComponent(keyText, 0x24)
    val divider = p?.divider ?: ColorUtils.setAlphaComponent(keyText, 0x26)
    /** Paper tears its strip along a dotted line. */
    private val stripDash = if (skin == KeySkin.PAPER) 4.dp.toFloat() else 0f

    // floating popups sit over arbitrary app content, so they stay (almost) opaque for legibility
    val popupFill = p?.popupFill ?: ColorUtils.setAlphaComponent(key, 0xF2)

    /** Blend [c] toward [target] without touching its alpha. */
    private fun tone(c: Int, target: Int, r: Float) =
        ColorUtils.setAlphaComponent(ColorUtils.blendARGB(c or 0xFF000000.toInt(), target, r), Color.alpha(c))

    /** Keycaps are flat solid surfaces (iOS 26); a mechanical cap has a lit top. Always a two-stop array so morphs stay generic. */
    fun sheen(base: Int): IntArray = when (skin) {
        KeySkin.MECHANICAL -> intArrayOf(lift(base, 0.10f), tone(base, Color.BLACK, 0.04f))
        // clay: a very soft convex pillow, lit from the top-left
        KeySkin.CLAY -> intArrayOf(lift(base, if (night) 0.05f else 0.35f), tone(base, Color.BLACK, if (night) 0.06f else 0.04f))
        else -> intArrayOf(base, base)
    }

    private fun lift(c: Int, r: Float) = ColorUtils.setAlphaComponent(ColorUtils.blendARGB(c or 0xFF000000.toInt(), Color.WHITE, r), Color.alpha(c))

    /** Colour of what sits behind a keycap: the mechanical skirt, or the paper card's hard shadow. */
    fun edgeOf(fill: Int): Int = when (skin) {
        KeySkin.MECHANICAL -> tone(fill, Color.BLACK, if (night) 0.40f else 0.28f)
        KeySkin.PAPER -> ColorUtils.setAlphaComponent(p?.shadow ?: Color.BLACK, if (night) 0x80 else 0x45)
        KeySkin.CLAY -> ColorUtils.setAlphaComponent(p?.shadow ?: Color.BLACK, if (night) 0xA0 else 0xB0)   // the dark side of the extrusion
        KeySkin.PIXEL -> tone(fill, Color.BLACK, 0.45f)                                                      // the chunky block under the key
        KeySkin.LCD -> ColorUtils.setAlphaComponent(p?.shadow ?: Color.BLACK, if (night) 0x50 else 0x38)    // LCD ghosting
        else -> Color.TRANSPARENT
    }

    /** Border (paper, terminal) or rim light (mechanical) of a keycap of [style]. */
    fun lineOf(style: KeyStyle): Int = when (skin) {
        KeySkin.FLAT -> Color.TRANSPARENT
        else -> if (style == KeyStyle.SPECIAL) p?.specialLine ?: Color.TRANSPARENT else p?.line ?: Color.TRANSPARENT
    }

    // ---- shared surfaces ----
    /**
     * Floating glass shell, drawn on Canvas by [GlassSurfaceDrawable]: tonal gradient fill, hairline
     * and soft upper light (AGSL on API 33+ when "Liquid light" is on, gradients otherwise), plus a hairline outline.
     * Also supplies the rounded outline used for clipping and for the window-blur mask.
     */
    fun shell(fill: Int = glassFill): GlassSurfaceDrawable =
        GlassSurfaceDrawable(this, containerRadius, fill, GlassSurfaceDrawable.Style.SHELL, isGlass && BackEffect.pressGlow(ctx))   // real blur or flat tint; optional press glow only

    fun stripBackground(): Drawable = GradientDrawable().apply {
        setColor(stripFill); cornerRadius = stripRadius
        if (stripDash > 0f) setStroke(1.dp, stripStroke, stripDash, 3.dp.toFloat()) else setStroke(1.dp, stripStroke)
    }

    fun stripItemBackground(): Drawable = StateListDrawable().apply {
        addState(intArrayOf(android.R.attr.state_pressed), GradientDrawable().apply {
            setColor(stripPressed); cornerRadius = stripRadius - 4.dp
        })
        addState(intArrayOf(), ColorDrawable(Color.TRANSPARENT))
    }

    /** Floating popups share the glass renderer (hairline) but stay near-opaque over arbitrary app content. */
    fun popupBackground(): Drawable =
        GlassSurfaceDrawable(this, popupRadius, popupFill, GlassSurfaceDrawable.Style.FLOATING, useShader = false)
}
