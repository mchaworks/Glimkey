package dev.pillboard.keyboard

import android.animation.Animator
import android.animation.AnimatorListenerAdapter
import android.animation.ValueAnimator
import android.annotation.SuppressLint
import android.app.Activity
import android.graphics.Bitmap
import android.graphics.Canvas
import android.os.SystemClock
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.view.animation.LinearInterpolator
import android.widget.ImageView
import androidx.core.view.doOnPreDraw

/**
 * The settings app's feel, mirrored from the Liquid Glass keyboard ([KbTokens] with [KbTheme.GLASS]: key spring 0.62 / 22,
 * pop spring 0.70 / 30, flow spring 0.78 / 28, press 65ms), so a control in the settings moves like a key does:
 * pressed = quick shrink, released = spring back with a small overshoot, a state change = spring, never a linear tween.
 * Full-width things (a page opening out of its row) use the shared text timeline with no overshoot, like the translate dock.
 */
internal object SettingsFeel {
    const val PRESS_MS = 65L
    val spring = SpringInterpolator(0.62f, 22f)       // release of a pressed control (KbTokens.spring)
    val pop = SpringInterpolator(0.70f, 30f)          // a control changing state, a card growing (KbTokens.popSpring)
    val flow = SpringInterpolator(0.78f, 28f)         // things that slide to a new place (KbTokens.flowSpring)
    val page = TimelineEase(BlurTextTokens.TOTAL_MS, BlurTextTokens.inEase)   // page <-> row morph: no overshoot at the screen edge

    /** Press feedback like a keycap: shrinks to [scale] in [PRESS_MS], springs back on release. Never consumes the touch. */
    @SuppressLint("ClickableViewAccessibility")
    fun press(v: View, scale: Float) {
        v.setOnTouchListener { view, e ->
            when (e.actionMasked) {
                MotionEvent.ACTION_DOWN -> pressDown(view, scale)
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> pressUp(view)
            }
            false
        }
    }

    private fun pressDown(v: View, scale: Float) {
        v.animate().cancel()
        if (!animationsOn(v.context)) { v.scaleX = scale; v.scaleY = scale; return }
        v.animate().scaleX(scale).scaleY(scale).setDuration(PRESS_MS).setInterpolator(BlurTextTokens.outEase).start()
    }

    private fun pressUp(v: View) {
        v.animate().cancel()
        if (!animationsOn(v.context)) { v.scaleX = 1f; v.scaleY = 1f; return }
        v.animate().scaleX(1f).scaleY(1f).setDuration(spring.settleMs).setInterpolator(spring).start()
    }
}

/**
 * The keyboard's two motions, lent to the settings screen: Blur Replace (the old content blurs, fades, shrinks and
 * drifts away along a [BlurDir] while the new one arrives from blur, on [BlurTextTokens]' timings) and the container
 * morph ([morphContainer]: a rounded-rect outline that flows between two rects, on the shared pop spring).
 *
 * One transition at a time: starting another cancels the running one, which cleans up after itself.
 */
internal class SettingsMotion(private val a: Activity) {
    private var anim: ValueAnimator? = null
    private var morph: ValueAnimator? = null
    private var flowAnim: ValueAnimator? = null
    private var flowToken: Any? = null
    private val flowMorphs = ArrayList<ValueAnimator>()
    private var lastAt = 0L

    private companion object {
        /** Blur on whole blocks (a page, a card list) is a full-screen RenderEffect rebuilt every frame: too heavy for most phones. Text labels keep their blur. */
        const val BLOCK_BLUR = false
        const val GHOST_SCALE = 0.5f      // the leaving bitmap is blurred and fading anyway: a quarter of the pixels
    }

    private fun View.fx(s: Float, dir: BlurDir, sign: Int) {
        if (BLOCK_BLUR) { blurFx(s, dir, sign); return }
        val c = s.coerceIn(0f, 1f); val k = 1f - c
        alpha = k * k
        val sc = 1f - (1f - BlurTextTokens.SCALE_MIN) * 0.5f * c
        scaleX = sc; scaleY = sc
        val d = -sign * BlurTextTokens.SHIFT_DP * 1.5f * resources.displayMetrics.density * c
        translationX = dir.mx * d; translationY = dir.my * d
    }

    fun cancel() {
        anim?.cancel(); anim = null
        morph?.cancel(); morph = null
        flowToken = null
        flowAnim?.cancel(); flowAnim = null
        flowMorphs.toList().forEach { it.cancel() }; flowMorphs.clear()
    }

    /** The rect of [v] in [host]'s coordinates, as the start or end of a container morph. */
    fun rectOf(host: View, v: View, radius: Float): RoundRectState {
        val h = IntArray(2); val p = IntArray(2)
        host.getLocationOnScreen(h); v.getLocationOnScreen(p)
        val l = (p[0] - h[0]).toFloat(); val t = (p[1] - h[1]).toFloat()
        return RoundRectState(l, t, l + v.width, t + v.height, radius)
    }

    /**
     * Blur Replace of everything inside [host]: a bitmap of what is shown leaves while [change] rebuilds the content and
     * [kids] (the views that now make it up) arrive from blur. Same out/in timing as the keyboard's panel swap.
     *
     * Container morph, both optional: [growFrom] is evaluated before [change] (the tapped row still exists) and the new
     * content [growView] opens out of that rect; [shrinkTo] is evaluated once the new content is laid out and the old
     * content, still on screen as the bitmap, folds into that rect.
     *
     * Changes closer than [minGapMs] to the previous one (typing in the search field) and animations switched off in
     * system settings just apply [change].
     */
    fun swap(
        host: ViewGroup, dir: BlurDir, kids: () -> List<View>, minGapMs: Long = 0L,
        growFrom: (() -> RoundRectState?)? = null, growView: View? = null,
        shrinkTo: (() -> RoundRectState?)? = null, change: () -> Unit,
    ) {
        val now = SystemClock.uptimeMillis()
        val rapid = minGapMs > 0L && now - lastAt < minGapMs
        lastAt = now
        cancel()
        if (rapid || host.width <= 0 || host.height <= 0 || !animationsOn(a)) { change(); return }

        val grow = growFrom?.invoke()
        val w = host.width; val h = host.height
        val bmp = Bitmap.createBitmap((w * GHOST_SCALE).toInt().coerceAtLeast(1), (h * GHOST_SCALE).toInt().coerceAtLeast(1), Bitmap.Config.ARGB_8888)
        host.draw(Canvas(bmp).apply { scale(GHOST_SCALE, GHOST_SCALE) })
        val ghost = ImageView(a).apply {
            setImageBitmap(bmp); scaleType = ImageView.ScaleType.FIT_XY
            layout(0, 0, w, h)
        }
        change()
        val views = kids()
        host.overlay.add(ghost)
        views.forEach { it.setLayerType(View.LAYER_TYPE_HARDWARE, null); it.fx(1f, dir, 1) }
        ghost.setLayerType(View.LAYER_TYPE_HARDWARE, null)

        val clock = ValueAnimator.ofFloat(0f, 1f).apply {
            duration = BlurTextTokens.TOTAL_MS
            interpolator = LinearInterpolator()
            addUpdateListener {
                val t = it.animatedFraction * BlurTextTokens.TOTAL_MS
                ghost.fx(BlurTextTokens.outEase.getInterpolation((t / BlurTextTokens.OUT_MS).coerceIn(0f, 1f)), dir, -1)
                val x = ((t - BlurTextTokens.IN_DELAY_MS) / BlurTextTokens.IN_MS).coerceIn(0f, 1f)
                val s = 1f - BlurTextTokens.inEase.getInterpolation(x)
                views.forEach { v -> v.fx(s, dir, 1) }
            }
            addListener(object : AnimatorListenerAdapter() {         // also runs when cancelled
                override fun onAnimationEnd(animation: Animator) {
                    host.overlay.remove(ghost); bmp.recycle()
                    views.forEach { v -> v.fx(0f, dir, 1); v.setLayerType(View.LAYER_TYPE_NONE, null) }
                    if (anim === animation) anim = null
                }
            })
        }
        anim = clock
        clock.start()

        val preset = MorphPreset(SettingsFeel.page.settleMs, SettingsFeel.page)
        if (grow != null && growView != null) {
            val to = RoundRectState(0f, 0f, growView.width.toFloat(), growView.height.toFloat(), 0f)
            morph = morphContainer(growView, grow, to, preset)
        }
        if (shrinkTo != null) host.doOnPreDraw {
            if (anim !== clock) return@doOnPreDraw                      // superseded meanwhile
            val to = shrinkTo() ?: return@doOnPreDraw
            morph = morphContainer(ghost, RoundRectState(0f, 0f, w.toFloat(), h.toFloat(), 0f), to, preset)
        }
    }

    private fun collect(v: View, sw: MutableList<PillSwitch>, sg: MutableList<Segmented>) {
        when (v) {
            is PillSwitch -> sw.add(v)
            is Segmented -> sg.add(v)
            is ViewGroup -> for (i in 0 until v.childCount) collect(v.getChildAt(i), sw, sg)
            else -> {}
        }
    }

    private fun crop(src: Bitmap, l: Int, t: Int, w: Int, h: Int): Bitmap? {
        val x = (l * GHOST_SCALE).toInt().coerceIn(0, src.width - 1)
        val y = (t * GHOST_SCALE).toInt().coerceIn(0, src.height - 1)
        val cw = (w * GHOST_SCALE).toInt().coerceIn(1, src.width - x)
        val ch = (h * GHOST_SCALE).toInt().coerceIn(1, src.height - y)
        return runCatching { Bitmap.createBitmap(src, x, y, cw, ch) }.getOrNull()
    }

    /**
     * A change that keeps the page (a switch that flips, a value that changes, rows that appear or go): no page-wide blur.
     * What was there stays put and flows to its new place on the flow spring (the cards below slide up or down), a card that
     * grows opens out of its old rect (container morph), one that shrinks folds into its new rect as a fading picture, new
     * cards arrive from blur, and the switches / segmented controls that changed play their own spring from the old state.
     * Cards are matched by position; changes with the animations off just apply [change].
     */
    fun flow(host: ViewGroup, change: () -> Unit) {
        cancel()
        val n = host.childCount
        if (n == 0 || host.width <= 0 || host.height <= 0 || !animationsOn(a)) { change(); return }

        val oldL = IntArray(n) { host.getChildAt(it).left }
        val oldT = IntArray(n) { host.getChildAt(it).top }
        val oldW = IntArray(n) { host.getChildAt(it).width }
        val oldH = IntArray(n) { host.getChildAt(it).height }
        val swOld = HashMap<String, Boolean>(); val sgOld = HashMap<String, Int>()
        run {
            val sw = ArrayList<PillSwitch>(); val sg = ArrayList<Segmented>()
            for (i in 0 until n) collect(host.getChildAt(i), sw, sg)
            sw.forEach { s -> s.contentDescription?.toString()?.let { swOld[it] = s.checked } }
            sg.forEach { s -> s.contentDescription?.toString()?.let { sgOld[it] = s.selected } }
        }
        val whole = Bitmap.createBitmap((host.width * GHOST_SCALE).toInt().coerceAtLeast(1), (host.height * GHOST_SCALE).toInt().coerceAtLeast(1), Bitmap.Config.ARGB_8888)
        host.draw(Canvas(whole).apply { scale(GHOST_SCALE, GHOST_SCALE) })

        change()
        val token = Any()
        flowToken = token
        host.doOnPreDraw {
            if (flowToken !== token) { whole.recycle(); return@doOnPreDraw }          // superseded meanwhile
            val kids = (0 until host.childCount).map { host.getChildAt(it) }
            val radius = Metrics.BOX_RADIUS.dp.toFloat()
            val preset = MorphPreset(SettingsFeel.pop.settleMs, SettingsFeel.pop)
            val dy = FloatArray(kids.size)
            val ghosts = ArrayList<ImageView>()
            val ghostDy = ArrayList<Float>()
            val bitmaps = ArrayList<Bitmap>()

            fun ghostOf(i: Int, top: Int, startDy: Float, foldToH: Int) {
                val bmp = crop(whole, oldL[i], oldT[i], oldW[i], oldH[i]) ?: return
                bitmaps.add(bmp)
                val g = ImageView(a).apply {
                    setImageBitmap(bmp); scaleType = ImageView.ScaleType.FIT_XY
                    layout(oldL[i], top, oldL[i] + oldW[i], top + oldH[i]); translationY = startDy
                }
                host.overlay.add(g); ghosts.add(g); ghostDy.add(startDy)
                if (foldToH in 1 until oldH[i]) flowMorphs.add(morphContainer(g, RoundRectState(0f, 0f, oldW[i].toFloat(), oldH[i].toFloat(), radius),
                    RoundRectState(0f, 0f, oldW[i].toFloat(), foldToH.toFloat(), radius), preset))
            }

            for (i in kids.indices) {
                val k = kids[i]
                if (i < n) {
                    dy[i] = (oldT[i] - k.top).toFloat(); k.translationY = dy[i]
                    if (k.height > oldH[i] + 1) flowMorphs.add(morphContainer(k, RoundRectState(0f, 0f, k.width.toFloat(), oldH[i].toFloat(), radius),
                        RoundRectState(0f, 0f, k.width.toFloat(), k.height.toFloat(), radius), preset))
                    else if (k.height < oldH[i] - 1) ghostOf(i, k.top, dy[i], k.height)
                } else k.fx(1f, BlurDir.UP, 1)                                   // a new card arrives from blur
            }
            for (i in kids.size until n) ghostOf(i, oldT[i], 0f, 0)                // a card that went fades out where it was

            val sw = ArrayList<PillSwitch>(); val sg = ArrayList<Segmented>()
            kids.forEach { collect(it, sw, sg) }
            sw.forEach { s ->
                val was = s.contentDescription?.toString()?.let { swOld[it] }
                if (was != null && was != s.checked) { val now = s.checked; s.setChecked(was, false); s.setChecked(now, true) }
            }
            sg.forEach { s ->
                val was = s.contentDescription?.toString()?.let { sgOld[it] }
                if (was != null && was != s.selected) { val now = s.selected; s.select(was, false); s.select(now, true) }
            }

            val clock = ValueAnimator.ofFloat(0f, 1f).apply {
                duration = SettingsFeel.flow.settleMs
                interpolator = SettingsFeel.flow
                addUpdateListener {
                    val f = it.animatedFraction
                    val t = it.currentPlayTime.toFloat()
                    for (i in kids.indices) if (i < n) kids[i].translationY = dy[i] * (1f - f)
                    val x = ((t - BlurTextTokens.IN_DELAY_MS) / BlurTextTokens.IN_MS).coerceIn(0f, 1f)
                    val s = 1f - BlurTextTokens.inEase.getInterpolation(x)
                    for (i in n until kids.size) kids[i].fx(s, BlurDir.UP, 1)
                    val c = BlurTextTokens.outEase.getInterpolation((t / BlurTextTokens.OUT_MS).coerceIn(0f, 1f))
                    ghosts.forEachIndexed { gi, g -> g.alpha = (1f - c) * (1f - c); g.translationY = ghostDy[gi] * (1f - f) }
                }
                addListener(object : AnimatorListenerAdapter() {            // also runs when cancelled
                    override fun onAnimationEnd(animation: Animator) {
                        kids.forEach { k -> k.fx(0f, BlurDir.UP, 1) }
                        ghosts.forEach { g -> host.overlay.remove(g) }
                        bitmaps.forEach { b -> b.recycle() }
                        whole.recycle()
                        if (flowAnim === animation) flowAnim = null
                    }
                })
            }
            flowAnim = clock
            clock.start()
        }
    }
}
