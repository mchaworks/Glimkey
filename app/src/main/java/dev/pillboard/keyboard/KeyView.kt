package dev.pillboard.keyboard

import android.animation.ArgbEvaluator
import android.animation.ValueAnimator
import android.annotation.SuppressLint
import android.content.Context
import android.content.res.Resources
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.drawable.Drawable
import android.graphics.drawable.GradientDrawable
import android.provider.Settings
import android.view.Choreographer
import android.view.Gravity
import android.view.HapticFeedbackConstants
import android.view.MotionEvent
import android.view.View
import android.view.animation.DecelerateInterpolator
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.PopupWindow
import android.widget.TextView
import androidx.core.content.ContextCompat
import androidx.core.graphics.ColorUtils

val Int.dp: Int get() = (this * Resources.getSystem().displayMetrics.density).toInt()

enum class KeyStyle { LETTER, SPECIAL, ACCENT, ACTIVE }

internal fun animationsOn(c: Context) =
    Settings.Global.getFloat(c.contentResolver, Settings.Global.ANIMATOR_DURATION_SCALE, 1f) != 0f

/** Wraps [bubble] in a transparent, padded holder so its elevation shadow isn't clipped by the popup window. */
internal fun shadowHolder(ctx: Context, bubble: View): FrameLayout = FrameLayout(ctx).apply {
    clipChildren = false
    clipToPadding = false
    val p = KbTokens.POPUP_SHADOW_PAD
    setPadding(p, p, p, p)
    addView(bubble, FrameLayout.LayoutParams(-1, -1))
}

/** Scale/fade a popup bubble in from its bottom edge. */
internal fun popIn(bubble: View, w: Int, h: Int, ms: Long, on: Boolean) {
    bubble.animate().cancel()
    if (!on) { bubble.alpha = 1f; bubble.scaleX = 1f; bubble.scaleY = 1f; return }
    bubble.pivotX = w / 2f; bubble.pivotY = h.toFloat()
    bubble.alpha = 0f; bubble.scaleX = 0.86f; bubble.scaleY = 0.86f
    bubble.animate().alpha(1f).scaleX(1f).scaleY(1f).setDuration(ms).setInterpolator(DecelerateInterpolator()).start()
}

/**
 * Stage 5: the preview grows out of the pressed key. It starts at the key's own size, pinned to its bottom edge,
 * and springs up to the bubble size (a small overshoot is the liquid pop). [LensView] eases its glyph with the same
 * progress.
 */
private fun morphFromKey(face: View, lens: LensView?, bw: Int, bh: Int, kw: Int, kh: Int, t: KbTokens, on: Boolean) {
    face.animate().cancel()
    if (!on) { face.alpha = 1f; face.scaleX = 1f; face.scaleY = 1f; lens?.morph = 1f; return }
    face.pivotX = bw / 2f; face.pivotY = bh.toFloat()
    face.alpha = 0f
    face.scaleX = (kw.toFloat() / bw).coerceIn(0.5f, 1f)
    face.scaleY = (kh.toFloat() / bh).coerceIn(0.5f, 1f)
    face.animate().alpha(1f).scaleX(1f).scaleY(1f).setDuration(t.popMs).setInterpolator(t.popSpring)
        .start()
}

/**
 * One reusable floating character preview per keyboard (never one window per key).
 * Shown after the key has already been committed, so it can never delay typing.
 */
class KeyPreview(ctx: Context, private val t: KbTokens) {
    private val motionOn = animationsOn(ctx)
    private val bubble = TextView(ctx).apply {
        gravity = Gravity.CENTER
        textSize = 30f
        includeFontPadding = false
        setTextColor(t.keyText)
        t.typeface?.let { typeface = it }
        background = t.popupBackground()
        elevation = t.popupElevation
    }
    /** API 33+: a liquid-glass bubble (Liquid-Glass-Android) that bends the keys around it; otherwise the plain bubble above. */
    private val lens: LensView? = if (t.isGlass && LensView.available() && Prefs.bool(ctx, "lens", true)) LensView(ctx, t) else null
    private val face: View = lens ?: bubble
    private val holder = shadowHolder(ctx, face)
    private var popup: PopupWindow? = null
    private var owner: View? = null
    private var fading = false

    /**
     * Adding and removing a window is a round trip to the window manager (and, for the glass lens, a fresh render
     * surface). Doing it on every tap is what made typing feel heavy. After the fade the window stays up, invisible and
     * untouchable, and is only removed once typing has paused; the next tap just moves it.
     */
    private var parked = false
    private val handler = android.os.Handler(android.os.Looper.getMainLooper())
    private val reap = Runnable { if (parked) { parked = false; popup?.dismiss() } }

    // where a parked window waits: the top of the last pressed key, so it sits over the keyboard and never over the app
    // (the preview itself floats above the key, and an invisible window over the app could swallow the person's next tap)
    private var parkX = 0
    private var parkY = 0

    private fun park(p: PopupWindow) {
        if (!p.isShowing) return
        try { p.update(parkX, parkY, -1, -1) } catch (_: Exception) { }
        face.visibility = View.INVISIBLE
        parked = true
        handler.removeCallbacks(reap)
        handler.postDelayed(reap, PARK_MS)
    }

    private var warming = false

    /**
     * Pay the first-tap cost up front: create the window and let the lens draw once (at 1 % alpha, so nothing is seen),
     * which compiles its shader. Then it parks like after any tap. Does nothing if the window is already up.
     */
    fun warm(anchor: View) {
        if (popup?.isShowing == true || !anchor.isAttachedToWindow || anchor.windowToken == null) return
        try {
            val pad = KbTokens.POPUP_SHADOW_PAD
            val ww = 52.dp + pad * 2
            val wh = 60.dp + pad * 2
            val p = popup ?: PopupWindow(holder, ww, wh, false).also { it.isTouchable = false; popup = it }
            p.width = ww; p.height = wh
            lens?.aim(anchor, "a")
            face.visibility = View.VISIBLE
            face.alpha = 0.01f
            warming = true
            bornAt = android.os.SystemClock.uptimeMillis()
            p.showAtLocation(anchor, Gravity.NO_GRAVITY, 0, 0)
            handler.postDelayed({
                if (warming && owner == null && p.isShowing) { warming = false; face.alpha = 0f; park(p) }
            }, 300)
        } catch (_: Exception) { warming = false }
    }

    fun show(key: View, glyph: String) {
        handler.removeCallbacks(reap)
        val wasParked = parked || warming
        parked = false; warming = false
        face.visibility = View.VISIBLE
        val pad = KbTokens.POPUP_SHADOW_PAD
        val bw = (key.width + 14.dp).coerceAtLeast(52.dp)
        val bh = (key.height + 14.dp).coerceAtLeast(60.dp)
        val ww = bw + pad * 2
        val wh = bh + pad * 2
        val loc = IntArray(2); key.getLocationOnScreen(loc)
        val win = IntArray(2); key.getLocationInWindow(win)
        val ox = loc[0] - win[0]; val oy = loc[1] - win[1]     // IME window origin on screen
        val screenW = key.resources.displayMetrics.widthPixels
        val bx = (loc[0] + key.width / 2 - bw / 2).coerceIn(2.dp, (screenW - bw - 2.dp).coerceAtLeast(2.dp))
        val x = (bx - pad).coerceAtLeast(0)
        val y = (loc[1] + 2.dp - bh - pad).coerceAtLeast(0)
        parkX = x - ox; parkY = (loc[1] - oy).coerceAtLeast(0)
        bubble.text = glyph
        lens?.aim(key, glyph)
        val sameKey = owner === key     // overlapping presses move the bubble to another key: never glide it there
        owner = key
        val p = popup ?: PopupWindow(holder, ww, wh, false).also { it.isTouchable = false; popup = it }
        val wasSolid = p.isShowing && !fading && !wasParked && sameKey
        fading = false
        face.animate().cancel()
        val seq = ++moveSeq
        val now = android.os.SystemClock.uptimeMillis()
        if (p.isShowing) {
            // Only a window that has just been created, or one woken from its parking spot, sits far from this key and
            // may not have drawn yet: there the bubble waits one frame for the move to land (otherwise it is drawn at the
            // old spot and runs to the key). Everywhere else the window is already near the keys: show at once, as before.
            val lateMove = !wasSolid && (wasParked || now - bornAt < 400L)
            if (wasSolid) {
                p.update(x - ox, y - oy, ww, wh)
                face.alpha = 1f; face.scaleX = 1f; face.scaleY = 1f; lens?.morph = 1f
            } else if (lateMove) {
                face.alpha = 0f
                p.update(x - ox, y - oy, ww, wh)
                afterFrames(1) {
                    if (seq == moveSeq && owner === key) {
                        lens?.aim(key, glyph)
                        morphFromKey(face, lens, bw, bh, key.width, key.height, t, motionOn)
                    }
                }
            } else {
                p.update(x - ox, y - oy, ww, wh)
                morphFromKey(face, lens, bw, bh, key.width, key.height, t, motionOn)
            }
        } else {
            p.width = ww; p.height = wh
            bornAt = now
            p.showAtLocation(key, Gravity.NO_GRAVITY, x - ox, y - oy)
            morphFromKey(face, lens, bw, bh, key.width, key.height, t, motionOn)
        }
    }

    /** When the popup window was last added (or warmed up): a window this young may not have settled at its spot yet. */
    private var bornAt = 0L

    /** Bumped by every show() and dismiss(): a reveal that was waiting for a window move drops itself when it is stale. */
    private var moveSeq = 0

    private fun afterFrames(n: Int, block: () -> Unit) {
        val c = Choreographer.getInstance()
        var left = n
        c.postFrameCallback(object : Choreographer.FrameCallback {
            override fun doFrame(frameTimeNanos: Long) {
                if (--left > 0) c.postFrameCallback(this) else block()
            }
        })
    }

    /** Hide only if [key] is the one currently shown (fast typing can overlap presses). */
    fun hide(key: View) { if (owner === key) dismiss() }

    /** Quick fade-out unless [immediate]; a new [show] cancels the fade, so rolling typing never flickers. */
    fun dismiss(immediate: Boolean = false) {
        owner = null
        moveSeq++
        face.animate().cancel()
        val p = popup ?: return
        if (immediate || !p.isShowing) {
            fading = false; parked = false
            handler.removeCallbacks(reap)
            p.dismiss(); return
        }
        if (!motionOn) { fading = false; face.alpha = 0f; park(p); return }
        fading = true
        face.animate().setUpdateListener(null).alpha(0f).setDuration(t.fadeOutMs).withEndAction {
            if (fading) { fading = false; park(p) }
        }.start()
    }

    private companion object { const val PARK_MS = 2500L }
}

/**
 * Rounded-rect key: instant tonal change + slight scale-down on press, spring release.
 * Plain keys commit on touch DOWN (no dropped letters when typing fast / rolling fingers).
 * Keys with long-press alternates ([altsFor] returns a non-empty list) commit on UP instead.
 * Icon keys ([iconRes] != 0) draw a Material-style vector centered on the key.
 */
@SuppressLint("ViewConstructor", "ClickableViewAccessibility")
class KeyView(
    context: Context,
    label: String,
    style: KeyStyle,
    private val t: KbTokens,
    private val repeats: Boolean,
    iconRes: Int = 0,
    private val onKey: () -> Unit,
) : BlurTextView(context) {

    private val soundLabel = label

    /** Returns long-press alternates for the key's current label (empty = none). */
    var altsFor: ((String) -> List<String>)? = null
    var onAlt: ((String) -> Unit)? = null
    /** Space-bar cursor slide: called with whole steps (+ right, - left). Non-null makes the key type on release. */
    var onSlide: ((Int) -> Unit)? = null
    var onSlideState: ((Boolean) -> Unit)? = null
    /** Raw screen x of the finger on every move while sliding (lets the cursor loupe follow it). */
    var onSlideMove: ((Float) -> Unit)? = null
    private var downX = 0f
    private var slideX = 0f
    private var sliding = false
    /** Shared character-preview bubble; null disables previews (e.g. in the settings preview). */
    var preview: KeyPreview? = null
    /** Tap-effect overlay (Settings > Feedback > Tap effect); independent of the theme. */
    var tapFx: TapFxView? = null
    /** Effect behind the keys (on the shell); independent of the theme and of [tapFx]. */
    var tapFxBack: TapFxBackView? = null
    var iconSize = 22.dp
    /** The shell's glass surface; its light leans toward the pressed key (AGSL layer only, otherwise a no-op). */
    var glass: GlassSurfaceDrawable? = null

    private val baseRadius = Prefs.keyRadius(context, t.theme).dp.toFloat()
    private var rest = baseRadius
    private var currentStyle = style
    private var fill = 0
    private var pressedFill = 0
    private var ink = 0
    private var pressedInk = 0
    private var icon: Drawable? = null
    private var restColors = IntArray(2)
    private val downColors = IntArray(2)
    private var morph: ValueAnimator? = null
    private val argb = ArgbEvaluator()
    private val shape = KeyBodyDrawable()
    // organic shape: four independent corner radii (TL, TR, BR, BL); the GradientDrawable only ever sees `radii`
    private val corner = FloatArray(4)
    private val cFrom = FloatArray(4)
    private val radii = FloatArray(8)
    private val motionOn = animationsOn(context)

    private var pendingUp = false

    /**
     * Gboard-style hint: the small character printed top-right of the key. Holding the key types it (a round bubble
     * shows it first). Null = no hint on this key (feature off, or not a letter). With a hint, the accent popup is skipped.
     */
    var hintChar: String? = null
        set(v) { field = v; invalidate() }
    var hintBubble: HintBubble? = null
    var hintSizeSp = 10f
        set(v) { field = v; hintPaint.textSize = android.util.TypedValue.applyDimension(android.util.TypedValue.COMPLEX_UNIT_SP, v, Resources.getSystem().displayMetrics); invalidate() }
    private var hintHeld = false          // the hold fired: letting go types the hint instead of the letter
    private var hintShown = false         // the bubble is up and the key's own glyph is dimmed
    private var hitX = 0
    private var hitY = 0
    private val hintPaint = android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG).apply {
        textAlign = android.graphics.Paint.Align.RIGHT
        textSize = android.util.TypedValue.applyDimension(android.util.TypedValue.COMPLEX_UNIT_SP, 10f, Resources.getSystem().displayMetrics)
    }
    private val hintFire = Runnable {
        if (!pendingUp || hintChar == null) return@Runnable
        hintHeld = true
        hintBubble?.fire()
        keyHaptic(HapticFeedbackConstants.LONG_PRESS)
    }

    private fun endHint() {
        removeCallbacks(hintFire)
        if (hintShown) { hintShown = false; hintBubble?.hide(this); invalidate() }
    }

    /** Types this key's pending tap now (it was waiting for release); called when another key goes down so letters keep their order. */
    private fun flushPendingTap() {
        if (!pendingUp || popup != null || sliding || hintHeld) return
        removeCallbacks(longPress)
        pendingUp = false
        if (hintShown) { endHint(); setTextColor(pressedInk) }
        onKey()
    }

    private companion object {
        /** The key still waiting for its finger to lift (alternates and space types on release). */
        var pendingKey: java.lang.ref.WeakReference<KeyView>? = null
    }
    private var alts = emptyList<String>()
    private var altMorph: ValueAnimator? = null
    private var altViews = emptyList<TextView>()
    private var altIdx = 0
    private var popup: PopupWindow? = null
    private var popupLeft = 0
    private val itemW = 44.dp

    private var repeatCount = 0
    private val repeater = object : Runnable {
        override fun run() {
            onKey()
            if (++repeatCount % 4 == 0) keyHaptic(HapticFeedbackConstants.CLOCK_TICK)   // soft tick, not a buzz
            if (repeatCount % 2 == 0) keySound(soundLabel)
            postDelayed(this, 45)
        }
    }
    private val longPress = Runnable { showAlts() }

    init {
        text = label
        t.typeface?.let { typeface = it }
        contentDescription = label
        gravity = Gravity.CENTER
        textSize = when { label.length == 1 && style != KeyStyle.ACCENT -> 22f; style == KeyStyle.ACCENT -> 16f; else -> 15f }
        applyStyle(style)
        background = shape
        elevation = t.keyElevation
        if (iconRes != 0) setIcon(iconRes, label)
    }

    private fun radiusFor(s: KeyStyle) = when (s) {
        KeyStyle.LETTER -> baseRadius
        KeyStyle.SPECIAL -> baseRadius + t.specialRadiusBoost
        KeyStyle.ACCENT, KeyStyle.ACTIVE -> baseRadius + t.actionRadiusBoost
    }

    private fun applyStyle(style: KeyStyle, animate: Boolean = false) {
        val oldColors = restColors
        val oldInk = ink
        System.arraycopy(corner, 0, cFrom, 0, 4)
        currentStyle = style
        when (style) {
            KeyStyle.LETTER -> { fill = t.key; ink = t.keyText }
            KeyStyle.SPECIAL -> { fill = t.special; ink = t.specialText }
            KeyStyle.ACCENT -> { fill = t.accent; ink = t.onAccent }
            KeyStyle.ACTIVE -> { fill = t.keyText; ink = t.key }
        }
        // iOS: special keys flip to the letter-key tone while pressed; the inverted Shift dims a little
        pressedFill = when (style) {
            KeyStyle.ACCENT -> t.accentPressed
            KeyStyle.SPECIAL -> t.pressedSpecial
            KeyStyle.ACTIVE -> ColorUtils.blendARGB(t.keyText, t.key, 0.22f)
            KeyStyle.LETTER -> t.pressed
        }
        pressedInk = when (style) {
            KeyStyle.ACCENT -> t.onAccent
            KeyStyle.ACTIVE -> ink
            else -> t.pressedText
        }
        rest = radiusFor(style)
        restColors = t.sheen(fill)          // cached: no allocation on every release
        shape.skin = t.skin; shape.edge = t.edgeOf(fill); shape.line = t.lineOf(style)
        downColors[0] = pressedFill; downColors[1] = pressedFill
        morph?.cancel()
        paint(false)
        setCorners(rest)
        setTextColor(ink)
        icon?.setTint(ink)
        if (animate && motionOn && isAttachedToWindow && width > 0) morphFrom(oldColors, oldInk)
        invalidate()
    }

    private fun setCorners(a: Float, b: Float, c: Float, d: Float) {
        corner[0] = a; corner[1] = b; corner[2] = c; corner[3] = d
        applyCorners()
    }

    private fun setCorners(r: Float) = setCorners(r, r, r, r)

    private fun applyCorners() {
        for (i in 0..3) { val r = corner[i].coerceAtLeast(0f); radii[i * 2] = r; radii[i * 2 + 1] = r }
        shape.setCornerRadii(radii)
    }

    /**
     * Organic style change (e.g. Shift): tone and every corner flow from where they are to the new state on the
     * spring instead of snapping. Colour is clamped to 0..1 (the spring overshoots; ArgbEvaluator would overflow
     * the channels), corners are allowed to overshoot, which is what makes the shape feel alive.
     */
    private fun morphFrom(oldColors: IntArray, oldInk: Int) {
        val newColors = restColors
        val newInk = ink
        shape.setColors(oldColors)
        setCorners(cFrom[0], cFrom[1], cFrom[2], cFrom[3])
        // the glyph starts in the OLD ink (it sits on the old body colour) and flows to the new one together with the body,
        // so an icon can never be invisible against a body that has not changed colour yet
        icon?.setTint(oldInk); setTextColor(oldInk)
        morph = ValueAnimator.ofFloat(0f, 1f).apply {
            duration = BlurTextTokens.TOTAL_MS        // same smooth timeline as the label: no spring, no overshoot
            interpolator = BlurTextTokens.inEase
            addUpdateListener {
                val f = it.animatedFraction
                val fc = f.coerceIn(0f, 1f)
                shape.setColors(intArrayOf(
                    argb.evaluate(fc, oldColors[0], newColors[0]) as Int,
                    argb.evaluate(fc, oldColors[1], newColors[1]) as Int))
                for (i in 0..3) corner[i] = cFrom[i] + (rest - cFrom[i]) * f
                applyCorners()
                val inkNow = argb.evaluate(fc, oldInk, newInk) as Int
                icon?.setTint(inkNow); setTextColor(inkNow)
            }
            // whichever way it stops (finished or cancelled by a touch / the next style change) the key lands on its final look
            addListener(object : android.animation.AnimatorListenerAdapter() {
                private fun land() {
                    shape.setColors(restColors); setCorners(rest)
                    icon?.setTint(ink); setTextColor(ink)
                }
                override fun onAnimationEnd(a: android.animation.Animator) = land()
                override fun onAnimationCancel(a: android.animation.Animator) = land()
            })
            start()
        }
    }

    /** Spring all four corners back to [rest]: a small undershoot, then settle (the release half of the morph). */
    private fun springCorners() {
        morph?.cancel()
        System.arraycopy(corner, 0, cFrom, 0, 4)
        if (!motionOn) { setCorners(rest); return }
        morph = ValueAnimator.ofFloat(0f, 1f).apply {
            duration = t.releaseMs
            interpolator = t.spring
            addUpdateListener {
                val f = it.animatedFraction
                for (i in 0..3) corner[i] = cFrom[i] + (rest - cFrom[i]) * f
                applyCorners()
            }
            start()
        }
    }

    /**
     * Pressed shape: the corners nearest the finger swell, the far ones tighten, so the key reads as a soft body
     * pressed at that point. ([tx], [ty]) is the touch in view pixels; the average radius stays rest + boost.
     */
    private fun pressedCorners(tx: Float, ty: Float) {
        val nx = if (width > 0) (tx / width * 2f - 1f).coerceIn(-1f, 1f) else 0f
        val ny = if (height > 0) (ty / height * 2f - 1f).coerceIn(-1f, 1f) else 0f
        val boost = t.pressRadiusBoost.toFloat()
        fun at(sx: Float, sy: Float): Float {
            val p = (1f + sx * nx) / 2f * ((1f + sy * ny) / 2f)       // 0 far .. 1 near (0.25 at the centre)
            return rest + boost * (1f + 1.2f * (p - 0.25f) / 0.75f)
        }
        setCorners(at(-1f, -1f), at(1f, -1f), at(1f, 1f), at(-1f, 1f))
    }

    private fun paint(down: Boolean, tx: Float = width / 2f, ty: Float = height / 2f) {
        shape.down = down
        shape.setColors(if (down) downColors else restColors)
        if (down) pressedCorners(tx, ty)     // release springs back via springCorners()
        shape.setStroke(0, Color.TRANSPARENT)   // flat keycaps: no outline
    }

    /**
     * The gap between keys belongs to the key: the view fills its whole cell and only the drawn shape is inset, so a
     * tap that lands between two keys still types one (fast thumbs no longer drop letters).
     */
    fun setHitInset(x: Int, y: Int) {
        shape.limitX = x.toFloat(); shape.limitY = y.toFloat()
        hitX = x; hitY = y
        background = android.graphics.drawable.InsetDrawable(shape, x, y, x, y)
    }

    /** Update in place (no view rebuild), e.g. when Shift changes. */
    fun setKeyStyle(style: KeyStyle) = applyStyle(style, animate = true)
    fun setLabel(l: String) { text = l; contentDescription = l }

    /**
     * Dynamic label (Shift case, layout page): Blur Replace of the character AND an organic morph of the keycap body on
     * one shared timeline ([BlurTextTokens.TOTAL_MS]). [oldW]/[oldH] = the visual size of the key this one replaces (a
     * new page can give the key another width); the body grows/shrinks from it. Nothing happens when the label does
     * not change, or when the change snaps (fast typing, animations off). Touch handling is untouched.
     */
    internal fun morphLabel(l: String, from: CharSequence? = null, minGapMs: Long = 0L,
                            inherited: List<BlurTextLayer>? = null, oldW: Int = 0, oldH: Int = 0, syncDelay: Boolean = false, dir: BlurDir = BlurDir.UP) {
        contentDescription = l
        // the body only morphs when its size really changes (new page); a same-size key just flows its label
        if (setTextBlur(l, from, minGapMs, inherited, syncDelay, dir) && (oldW > 0 || oldH > 0 || shapeClock?.isRunning == true)) startShapeMorph(oldW, oldH)
    }

    // ---- keycap shape morph: one clock, extras of the body (size around its centre) + Bezier bulge ----
    private var shapeClock: ValueAnimator? = null
    private var shapeStart = -1L
    private var shapeDW0 = 0f
    private var shapeDH0 = 0f
    private var oldVisW = 0
    private var oldVisH = 0

    /** Visual size of the cell including a running size morph (carried to the replacement key on rapid changes). */
    internal val visualW get() = width + shape.dW.toInt()
    internal val visualH get() = height + shape.dH.toInt()

    internal fun startShapeMorph(oldW: Int = 0, oldH: Int = 0) {
        if (!motionOn) return
        val running = shapeClock?.isRunning == true && shapeStart >= 0L
        if (running) {                                  // interrupted: continue from the shape on screen
            shapeDW0 = shape.dW; shapeDH0 = shape.dH
            shapeStart = android.os.SystemClock.uptimeMillis()
        } else {
            oldVisW = oldW; oldVisH = oldH
            shapeStart = -1L
        }
        if (shapeClock?.isRunning == true) return
        shapeClock = ValueAnimator.ofFloat(0f, 1f).apply {
            duration = 1000L
            repeatCount = ValueAnimator.INFINITE
            addUpdateListener { shapeTick() }
            start()
        }
    }

    private fun shapeTick() {
        val now = android.os.SystemClock.uptimeMillis()
        if (shapeStart < 0L) {
            if (width <= 0) return                      // not laid out yet: the text waits for the same moment
            shapeStart = now
            shapeDW0 = if (oldVisW > 0) (oldVisW - width).toFloat() else 0f
            shapeDH0 = if (oldVisH > 0) (oldVisH - height).toFloat() else 0f
            if (kotlin.math.abs(shapeDW0) < 1.5f) shapeDW0 = 0f
            if (kotlin.math.abs(shapeDH0) < 1.5f) shapeDH0 = 0f
        }
        val p = ((now - shapeStart).toFloat() / BlurTextTokens.TOTAL_MS).coerceIn(0f, 1f)
        val e = 1f - BlurTextTokens.inEase.getInterpolation(p)                       // size settles early, softly
        shape.setMorph(shapeDW0 * e, shapeDH0 * e, 0f)       // pure size flow; the old squash bulge felt like a bounce
        invalidate()
        if (p >= 1f) stopShapeMorph()
    }

    private fun stopShapeMorph() {
        shapeClock?.cancel(); shapeClock = null
        shapeStart = -1L
        shape.setMorph(0f, 0f, 0f)
        invalidate()
    }

    /** Show a vector icon instead of text; [desc] is the accessibility label. */
    fun setIcon(res: Int, desc: String? = null) {
        icon = if (res == 0) null else ContextCompat.getDrawable(context, res)?.mutate()
        icon?.setTint(ink)
        if (icon != null) text = ""
        if (desc != null) contentDescription = desc
        invalidate()
    }

    override fun onDraw(canvas: Canvas) {
        // skins that move (paper card, mechanical cap) carry the label with the face
        val sv = canvas.save()
        canvas.translate(0f, shape.contentDy)
        super.onDraw(canvas)
        hintChar?.let { h ->
            hintPaint.color = ColorUtils.setAlphaComponent(ink, if (hintShown) 0x30 else 0x99)
            canvas.drawText(h, width - hitX - 5.dp.toFloat(), (hitY + 4.dp) - hintPaint.ascent(), hintPaint)
        }
        icon?.let { d ->
            val s = iconSize
            val l = (width - s) / 2
            val tp = (height - s) / 2
            d.setBounds(l, tp, l + s, tp + s)
            d.draw(canvas)
        }
        canvas.restoreToCount(sv)
    }

    private fun animateTo(down: Boolean, tx: Float = width / 2f, ty: Float = height / 2f) {
        // Color first, instantly: input never waits on animation.
        morph?.cancel()
        paint(down, tx, ty)
        if (!down) springCorners()
        setTextColor(if (down) pressedInk else ink)
        icon?.setTint(if (down) pressedInk else ink)
        invalidate()
        // shrink by a fixed ~4dp per axis (not a percentage), so wide keys such as space don't lose a lot of width
        val shrink = t.pressShrinkDp.dp.toFloat()
        val sx = if (down && shrink > 0f) (1f - shrink / width.coerceAtLeast(1)).coerceIn(0.9f, 0.99f) else 1f
        val sy = if (down && shrink > 0f) (1f - shrink / height.coerceAtLeast(1)).coerceIn(0.9f, 0.99f) else 1f
        animate().cancel()
        if (!motionOn) { scaleX = sx; scaleY = sy; return }
        animate().scaleX(sx).scaleY(sy).setDuration(if (down) t.pressMs else t.releaseMs)
            .setInterpolator(if (down) null else t.spring).start()
    }

    // ---- long-press alternates ----
    private fun showAlts() {
        if (alts.isEmpty()) return
        preview?.hide(this)
        val items = alts.map { a ->
            TextView(context).apply { text = a; textSize = 22f; gravity = Gravity.CENTER; setTextColor(t.keyText) }
        }
        val itemH = 52.dp
        val pad = 6.dp
        val bw = alts.size * itemW + pad * 2
        val bh = itemH + pad * 2
        val box = LinearLayout(context).apply {
            background = t.popupBackground()
            elevation = t.popupElevation
            setPadding(pad, pad, pad, pad)
            items.forEach { addView(it, LinearLayout.LayoutParams(itemW, itemH)) }
        }
        val sp = KbTokens.POPUP_SHADOW_PAD
        val loc = IntArray(2); getLocationOnScreen(loc)
        val win = IntArray(2); getLocationInWindow(win)
        val ox = loc[0] - win[0]; val oy = loc[1] - win[1]
        val screenW = resources.displayMetrics.widthPixels
        val bx = (loc[0] + width / 2 - bw / 2).coerceIn(4.dp, (screenW - bw - 4.dp).coerceAtLeast(4.dp))
        val by = (loc[1] - bh - 8.dp).coerceAtLeast(sp)
        val wx = (bx - sp).coerceAtLeast(0)
        val wy = (by - sp).coerceAtLeast(0)
        popupLeft = wx + sp + pad
        altViews = items
        altIdx = -1      // nothing chosen: letting go without moving types the plain letter
        popup = PopupWindow(shadowHolder(context, box), bw + sp * 2, bh + sp * 2, false).apply {
            isTouchable = false
            showAtLocation(this@KeyView, Gravity.NO_GRAVITY, wx - ox, wy - oy)
        }
        altMorph?.cancel()
        if (motionOn) {
            // container morph: the popup grows out of the key (same rounded-rect outline, radius and position interpolate)
            val fw = minOf(width.toFloat(), bw.toFloat()); val fh = bh * MorphTokens.CONTAINER_START_H
            val from = RoundRectState((bw - fw) / 2f, bh - fh, (bw + fw) / 2f, bh.toFloat(), rest)
            val to = RoundRectState(0f, 0f, bw.toFloat(), bh.toFloat(), t.popupRadius)
            altMorph = morphContainer(box, from, to, MorphPresets.container(t),
                onProgress = { f ->
                    val a = ((f - MorphTokens.CONTAINER_FADE_FROM) / MorphTokens.CONTAINER_FADE_SPAN).coerceIn(0f, 1f)
                    items.forEach { it.alpha = a }
                },
                onEnd = { items.forEach { it.alpha = 1f } })
        } else popIn(box, bw, bh, t.popupInMs, false)
        highlight()
        keyHaptic(HapticFeedbackConstants.LONG_PRESS)
    }

    private fun highlight() {
        altViews.forEachIndexed { i, v ->
            val on = i == altIdx
            v.background = if (on) GradientDrawable().apply {
                setColor(t.accent); cornerRadius = t.popupRadius - 6.dp
                setStroke(1.dp, t.glassHighlight)
            } else null
            v.setTextColor(if (on) t.onAccent else t.keyText)
        }
    }

    private fun dismissAlts() {
        altMorph?.cancel(); altMorph = null
        popup?.dismiss(); popup = null
        altViews = emptyList()
    }

    /** After input and key feedback: lean the shell's light toward this key and light the glass around it (down), or ease back (up). */
    private fun leanGlass(down: Boolean) {
        val g = glass ?: return
        if (!g.shaderActive) return
        if (!down) { g.leanLight(null, null, motionOn); return }
        // the shell is the ancestor that draws this glass; measure the key centre in its coordinates
        var shellView: View? = parent as? View
        while (shellView != null && shellView.background !== g) shellView = shellView.parent as? View
        val sv = shellView ?: return
        val k = IntArray(2); getLocationInWindow(k)
        val o = IntArray(2); sv.getLocationInWindow(o)
        g.leanLight(k[0] - o[0] + width / 2f, k[1] - o[1] + height / 2f, motionOn)
    }

    private fun previewable() = icon == null && !repeats && text.length == 1 && !text[0].isWhitespace()

    override fun onTouchEvent(e: MotionEvent): Boolean {
        when (e.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                parent?.requestDisallowInterceptTouchEvent(true)
                // a key still waiting for its release types first, so two fast thumbs never swap letters
                pendingKey?.get()?.let { if (it !== this) it.flushPendingTap() }
                pendingKey = null
                downX = e.x
                // 1) input first: nothing visual may ever sit in front of the commit
                if (repeats) {
                    repeatCount = 0; onKey(); postDelayed(repeater, 400)
                } else {
                    val hinting = hintChar != null
                    alts = if (hinting) emptyList() else altsFor?.invoke(text.toString()) ?: emptyList()
                    when {
                        onSlide != null -> { pendingUp = true; downX = e.x }   // types on release unless it became a slide
                        hinting -> { pendingUp = true; postDelayed(hintFire, 300) }   // letter on release, hint if held
                        alts.isEmpty() -> onKey()
                        else -> { pendingUp = true; postDelayed(longPress, 350) }
                    }
                }
                if (pendingUp) pendingKey = java.lang.ref.WeakReference(this)
                // 2) then feedback
                animateTo(true, e.x, e.y)
                keyHaptic(HapticFeedbackConstants.KEYBOARD_TAP)
                keySound(soundLabel)
                tapFxBack?.burst(this, e.x, e.y)
                tapFx?.burst(this, e.x, e.y)
                val hint = hintChar
                if (hint != null) {
                    hintShown = true
                    hintBubble?.show(this, text.toString(), hint)
                    setTextColor(ColorUtils.setAlphaComponent(pressedInk, 0x40))     // the glyph steps aside for the bubble
                    invalidate()
                } else if (previewable()) preview?.show(this, text.toString())
                leanGlass(true)
            }
            MotionEvent.ACTION_MOVE -> if (popup != null) {
                if (altIdx < 0 && Math.abs(e.x - downX) < 8.dp) return true     // jitter is not a choice
                val i = ((e.rawX - popupLeft) / itemW).toInt().coerceIn(0, altViews.lastIndex)
                if (i != altIdx) { altIdx = i; highlight() }
            } else onSlide?.let { slide ->
                if (!sliding && Math.abs(e.x - downX) > 10.dp) {
                    sliding = true; pendingUp = false; slideX = e.x
                    onSlideState?.invoke(true)
                }
                if (sliding) {
                    onSlideMove?.invoke(e.rawX)
                    val n = ((e.x - slideX) / 14.dp).toInt()          // one character per 14dp
                    if (n != 0) {
                        slideX += n * 14.dp
                        slide(n)
                        keyHaptic(HapticFeedbackConstants.CLOCK_TICK)
                    }
                }
            }
            MotionEvent.ACTION_UP -> {
                removeCallbacks(repeater); removeCallbacks(longPress)
                if (sliding) { sliding = false; onSlideState?.invoke(false) }
                animateTo(false)
                leanGlass(false)
                preview?.hide(this)
                endHint()
                if (hintHeld) {
                    hintHeld = false
                    hintChar?.let { onAlt?.invoke(it) }
                } else if (popup != null) {
                    val pick = alts.getOrNull(altIdx)
                    dismissAlts()
                    if (pick != null) onAlt?.invoke(pick) else onKey()
                } else if (pendingUp) onKey()
                pendingUp = false
            }
            MotionEvent.ACTION_CANCEL -> {
                removeCallbacks(repeater); removeCallbacks(longPress)
                if (sliding) { sliding = false; onSlideState?.invoke(false) }
                dismissAlts(); pendingUp = false
                hintHeld = false; endHint()
                preview?.hide(this)
                animateTo(false)
                leanGlass(false)
            }
        }
        return true
    }

    override fun onDetachedFromWindow() {
        stopShapeMorph()
        removeCallbacks(repeater); removeCallbacks(longPress)
        dismissAlts()
        hintHeld = false; endHint()
        preview?.hide(this)
        super.onDetachedFromWindow()
    }
}
