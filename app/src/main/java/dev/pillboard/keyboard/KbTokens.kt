package dev.pillboard.keyboard

import android.content.Context
import android.content.res.Configuration
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.ColorDrawable
import android.graphics.drawable.Drawable
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.StateListDrawable
import android.view.ContextThemeWrapper
import androidx.core.graphics.ColorUtils
import com.google.android.material.color.MaterialColors
import com.google.android.material.R as MR

/**
 * Liquid Expressive design tokens: every color, radius, elevation and timing the keyboard uses.
 * Resolved from the (dynamic or static) Material theme of [ctx]. Components must read from here
 * instead of hard-coding values, so the keyboard stays one coherent product.
 */
class KbTokens(ctx0: Context, val theme: KbTheme = KbTheme.current(ctx0)) {
    /** The context whose Material theme matches the keyboard's chosen mode (Settings > Appearance > Mode). */
    private val ctx: Context = KbMode.matching(ctx0)
    companion object {
        const val KEY_R_DEFAULT = 10
        /** Shadow room around floating popups (a popup window clips anything outside its bounds). */
        val POPUP_SHADOW_PAD get() = 10.dp
    }

    private fun c(attr: Int, fb: Int): Int = MaterialColors.getColor(ctx, attr, fb)
    val night = KbMode.night(ctx)

    // ---- theme: Liquid Glass resolves colours from the Material theme (below), every other theme from its fixed palette ----
    val isGlass = theme.isGlass
    val skin = theme.skin
    private val p: Palette? = if (isGlass) null else theme.palette(night)
    /** The chosen accent's tones (fixed accent only): the shell and keys are tinted by it, so a blue accent no longer sits on a lavender shell. */
    private val seedTri: Triad? = if (!isGlass || Prefs.dynamic(ctx)) null else Triad.fromSeed(Prefs.seedColor(ctx), night, Prefs.accentStyle(ctx))
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
    // Liquid Glass keys used to cast a 1dp system shadow too, but the shadow follows the view's outline (the whole cell, gaps
    // included) while the keycap is drawn smaller and morphs, so on light keys it showed as grey ghost rectangles behind
    // every key. Depth now comes from the key/shell tone contrast only.
    val keyElevation = 0f
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
    val background = p?.bg ?: seedTri?.let {
        if (night) ColorUtils.blendARGB(0xFF18181C.toInt(), it.pc, 0.16f) else ColorUtils.blendARGB(0xFFDDDFE5.toInt(), it.pc, 0.22f)
    } ?: c(MR.attr.colorSurfaceContainerLow, if (night) 0xFF1C1B1F.toInt() else 0xFFF3EDF7.toInt())
        .let { if (night) tone(it, Color.BLACK, 0.30f) else tone(it, Color.BLACK, 0.05f) }
    // non-glass shells are opaque: they never blur and are drawn by GlassSurfaceDrawable's themed path
    val glassFill = p?.bg ?: ColorUtils.setAlphaComponent(background, if (night) 0xD8 else 0xE8)
    /** Real backdrop blur keeps text legible, so the shell can be more translucent. */
    val glassFillBlur = p?.bg ?: ColorUtils.setAlphaComponent(background, (Prefs.int(ctx, "blur_tint", 62).coerceIn(10, 100) * 255 / 100))
    /** Blur requested but unavailable: no blur behind, so stay nearly opaque for readability. */
    val glassFillNoBlur = p?.bg ?: ColorUtils.setAlphaComponent(background, 0xF2)
    val glassStroke = p?.shellLine ?: ColorUtils.setAlphaComponent(c(MR.attr.colorOutlineVariant, if (night) Color.WHITE else Color.BLACK), if (night) 0x48 else 0x30)
    val glassHighlight = p?.let { ColorUtils.setAlphaComponent(it.keyText, 0x40) } ?: ColorUtils.setAlphaComponent(Color.WHITE, if (night) 0x24 else 0x66)
    /** Liquid Glass keycap opacity (Settings > Appearance > Glass & blur > Key opacity): below 100% the keys are glass too and the blur shows through them. */
    private val keyAlpha = if (isGlass) Prefs.int(ctx, "key_alpha", 100).coerceIn(30, 100) * 255 / 100 else 255
    private fun ka(c: Int) = if (keyAlpha >= 255) c else ColorUtils.setAlphaComponent(c, keyAlpha)
    private val keyBase = p?.key ?: seedTri?.takeIf { night }?.let { ColorUtils.blendARGB(0xFF3C3C42.toInt(), it.pc, 0.20f) } ?: if (night) tone(c(MR.attr.colorSurfaceContainerHighest, 0xFF36343B.toInt()), Color.WHITE, 0.10f)
              else c(MR.attr.colorSurfaceContainerLowest, Color.WHITE)
    val key = ka(keyBase)
    val keyText = p?.keyText ?: c(MR.attr.colorOnSurface, if (night) Color.WHITE else Color.BLACK)
    // the user's accent / Material You only drive Liquid Glass; every other theme has its own accent
    private val seed: Int? = if (!isGlass || Prefs.dynamic(ctx)) null else Prefs.seedColor(ctx)
    /**
     * Pixel-style three-tone accent: primary (Enter), secondary (special keys) and tertiary (active Shift), each with its
     * container and "on" colour. Liquid Glass only. From the system's Material You palette, or built from the chosen seed.
     */
    private val triad: Triad? = if (!isGlass || !Prefs.bool(ctx, "tritone", true)) null
        else seed?.let { Triad.fromSeed(it, night, Prefs.accentStyle(ctx)) } ?: Triad.fromTheme(ctx)
    /** Paper: ruled lines. Terminal: phosphor glow. */
    val ruleColor = p?.rule ?: Color.TRANSPARENT

    // iOS 26 direction: neutral gray keycaps (special keys one step darker), accent only for the primary action
    private val hint = seed ?: c(MR.attr.colorPrimary, 0xFF6750A4.toInt())
    // three-tone: special keys wear the secondary container (a notch darker in light mode so they stay apart from the shell)
    // special keys are the SHELL a step deeper (plus a little of the secondary tone), so they share its hue instead of reading as a different grey
    private val specialBase = tone(background, Color.BLACK, if (night) 0.22f else 0.09f)
    val special = ka(p?.special ?: triad?.let { ColorUtils.blendARGB(specialBase, it.sc, 0.25f) }
        ?: ColorUtils.blendARGB(specialBase, hint, 0.06f))
    val specialText = p?.specialText ?: triad?.onSc ?: keyText
    /** Ink of a special key while pressed. */
    val pressedSpecialText = p?.pressedText ?: triad?.onSc ?: keyText
    val pressed = p?.pressed ?: if (night) tone(key, Color.WHITE, 0.16f) else tone(key, Color.BLACK, 0.12f)
    val pressedText = p?.pressedText ?: keyText
    /** What a special key turns into while pressed: iOS flips it to the letter-key tone. */
    val pressedSpecial = p?.pressedSpecial ?: triad?.let { tone(special, if (night) Color.WHITE else Color.BLACK, 0.12f) } ?: key
    val accent = p?.accent ?: triad?.p ?: seed ?: c(MR.attr.colorPrimary, Color.BLUE)
    val onAccent = p?.onAccent ?: triad?.onP ?: seed?.let { if (ColorUtils.calculateContrast(Color.WHITE, it) >= 3.0) Color.WHITE else 0xFF10181D.toInt() }
        ?: c(MR.attr.colorOnPrimary, Color.WHITE)
    val keyStroke = ColorUtils.setAlphaComponent(keyText, if (night) 0x22 else 0x18)
    val accentPressed = tone(accent, Color.BLACK, 0.18f)
    /** An active Shift / Caps / Select key: tertiary in the three-tone accent, the inverted letter tone otherwise. */
    val activeFill = triad?.t ?: keyText
    val activeInk = triad?.onT ?: (keyBase or 0xFF000000.toInt())

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

/**
 * Light / dark choice of the keyboard (Settings > Appearance > Mode): "system" (default), "light" or "dark". The Material
 * theme the colours come from is wrapped with an overridden night flag, so every theme attribute follows the choice.
 */
object KbMode {
    const val PREF = "mode"
    fun pref(c: Context) = Prefs.str(c, PREF, "system")
    fun systemNight(c: Context) = c.resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK == Configuration.UI_MODE_NIGHT_YES
    /** Set by the service while typing into an app known to be dark whatever the system says (terminals); null = follow the system. */
    @Volatile var appNight: Boolean? = null
    private val TERMINALS = setOf("com.termux", "org.connectbot", "jackpal.androidterm", "com.server.auditor.ssh.client", "com.sonelli.juicessh")
    /** In "System" mode a terminal gets the dark keyboard even when the phone is in light mode. */
    fun forApp(pkg: String?): Boolean? = if (pkg != null && pkg in TERMINALS) true else null
    fun night(c: Context): Boolean = when (pref(c)) { "dark" -> true; "light" -> false; else -> appNight ?: systemNight(c) }

    /** The keyboard's Material theme (Material You or the fixed palette) with the chosen mode applied. */
    fun themed(base: Context, dynamic: Boolean): ContextThemeWrapper {
        val tw = ContextThemeWrapper(base, if (dynamic) R.style.Theme_Pillboard else R.style.Theme_Pillboard_Static)
        val want = night(base)
        if (want != systemNight(base)) {
            // must happen before the wrapper's resources or theme are touched
            val cfg = Configuration(base.resources.configuration)
            cfg.uiMode = (cfg.uiMode and Configuration.UI_MODE_NIGHT_MASK.inv()) or
                (if (want) Configuration.UI_MODE_NIGHT_YES else Configuration.UI_MODE_NIGHT_NO)
            tw.applyOverrideConfiguration(cfg)
        }
        return tw
    }

    /** [c] itself when it already shows the chosen mode, otherwise a themed wrapper that does. */
    fun matching(c: Context): Context = if (night(c) == systemNight(c)) c else themed(c, Prefs.dynamic(c))
}

/** How the secondary and tertiary tones are derived from the accent (Settings > Appearance > Palette style). */
enum class AccentStyle(
    val id: String, val label: String,
    /** Saturation of secondary / tertiary relative to the accent's. */
    val secondary: Float, val tertiary: Float,
    /** Hue offset (degrees) of secondary / tertiary. */
    val secondaryShift: Float, val tertiaryShift: Float,
) {
    TONAL("tonal", "Tonal", 0.40f, 0.70f, 0f, 60f),
    VIVID("vivid", "Vivid", 0.80f, 1.00f, 0f, 60f),
    MUTED("muted", "Muted", 0.12f, 0.25f, 0f, 60f),
    BOLD("bold", "Bold", 0.60f, 0.90f, 40f, 150f);

    companion object { fun of(id: String?): AccentStyle = values().firstOrNull { it.id == id } ?: TONAL }
}

/** Tonal triad of one accent, Pixel / Material You style: primary, secondary and tertiary, each with container and "on" colours. */
internal class Triad(
    val p: Int, val onP: Int, val pc: Int, val onPc: Int,
    val s: Int, val onS: Int, val sc: Int, val onSc: Int,
    val t: Int, val onT: Int, val tc: Int, val onTc: Int,
) {
    companion object {
        /** The three tones the system (Material You / wallpaper) theme of [ctx] resolves to. */
        fun fromTheme(ctx: Context): Triad {
            fun c(attr: Int, fb: Int) = MaterialColors.getColor(ctx, attr, fb)
            return Triad(
                c(MR.attr.colorPrimary, 0xFF6750A4.toInt()), c(MR.attr.colorOnPrimary, Color.WHITE),
                c(MR.attr.colorPrimaryContainer, 0xFFEADDFF.toInt()), c(MR.attr.colorOnPrimaryContainer, 0xFF21005D.toInt()),
                c(MR.attr.colorSecondary, 0xFF625B71.toInt()), c(MR.attr.colorOnSecondary, Color.WHITE),
                c(MR.attr.colorSecondaryContainer, 0xFFE8DEF8.toInt()), c(MR.attr.colorOnSecondaryContainer, 0xFF1D192B.toInt()),
                c(MR.attr.colorTertiary, 0xFF7D5260.toInt()), c(MR.attr.colorOnTertiary, Color.WHITE),
                c(MR.attr.colorTertiaryContainer, 0xFFFFD8E4.toInt()), c(MR.attr.colorOnTertiaryContainer, 0xFF31111D.toInt()),
            )
        }

        /** Builds the triad from one seed colour; [style] decides how far secondary and tertiary drift from it. Greys stay grey. */
        fun fromSeed(seed: Int, night: Boolean, style: AccentStyle = AccentStyle.TONAL): Triad {
            val hsl = FloatArray(3); ColorUtils.colorToHSL(seed, hsl)
            val h = hsl[0]
            fun col(hue: Float, sat: Float, l: Float): Int =
                ColorUtils.HSLToColor(floatArrayOf((hue % 360f + 360f) % 360f, sat.coerceIn(0f, 1f), l.coerceIn(0f, 1f)))
            val grey = hsl[1] < 0.06f
            val sp = if (grey) hsl[1] else hsl[1].coerceIn(0.12f, 0.75f)
            val ss = sp * style.secondary
            val st = sp * style.tertiary
            val hs = h + style.secondaryShift
            val ht = h + style.tertiaryShift
            val lift = if (grey) 0f else 0.10f
            return if (!night) Triad(
                col(h, sp, 0.40f), Color.WHITE, col(h, sp, 0.90f), col(h, sp, 0.12f),
                col(hs, ss, 0.40f), Color.WHITE, col(hs, ss + lift, 0.90f), col(hs, ss, 0.12f),
                col(ht, st, 0.40f), Color.WHITE, col(ht, st, 0.90f), col(ht, st, 0.12f),
            ) else Triad(
                col(h, sp, 0.78f), col(h, sp, 0.14f), col(h, sp, 0.30f), col(h, sp, 0.90f),
                col(hs, ss, 0.76f), col(hs, ss, 0.14f), col(hs, ss, 0.28f), col(hs, ss, 0.90f),
                col(ht, st, 0.78f), col(ht, st, 0.14f), col(ht, st, 0.30f), col(ht, st, 0.90f),
            )
        }
    }
}
