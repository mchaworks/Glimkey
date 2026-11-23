package dev.pillboard.keyboard

import android.animation.Animator
import android.animation.AnimatorListenerAdapter
import android.animation.ValueAnimator
import android.app.Activity
import android.graphics.Bitmap
import android.graphics.Canvas
import android.os.SystemClock
import android.view.View
import android.view.ViewGroup
import android.view.animation.LinearInterpolator
import android.widget.ImageView
import androidx.core.view.doOnPreDraw

/**
 * The keyboard's two motions, lent to the settings screen: Blur Replace (the old content blurs, fades, shrinks and
 * drifts away along a [BlurDir] while the new one arrives from blur, on [BlurTextTokens]' timings) and the container
 * morph ([morphContainer]: a rounded-rect outline that flows between two rects, on the shared pop spring).
 *
 * One transition at a time: starting another cancels the running one, which cleans up after itself.
 */
internal class SettingsMotion(private val a: Activity) {
    private val tokens by lazy { KbTokens(a, KbTheme.GLASS) }
    private var anim: ValueAnimator? = null
    private var morph: ValueAnimator? = null
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

        val preset = MorphPresets.container(tokens)
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
}
