package dev.pillboard.keyboard

import android.animation.Animator
import android.animation.AnimatorListenerAdapter
import android.animation.TimeInterpolator
import android.animation.ValueAnimator
import android.content.Context
import android.view.View
import android.widget.FrameLayout
import android.widget.TextView
import kotlin.math.max

/**
 * Blur Replace for a label or a block of text that is made of real [TextView]s (two stacked ones made by [make]): the
 * old text blurs, fades, shrinks and drifts away along [BlurDir] while the new one arrives from blur. [BlurTextView]
 * paints one ellipsised line by hand; this one is for what that cannot do: wrapping text, spans, two-line labels.
 *
 * Same scalar, curves and timings as every other Blur Replace ([BlurTextTokens], [blurFx]). Interruption safe: a label
 * that is still arriving when the text changes again leaves from exactly where it is, so rapid updates never pop.
 * [dim] fades the whole text (not the view's background) without fighting the transition, which owns the labels' alpha.
 */
internal class BlurSwapText(ctx: Context, fill: Boolean, make: () -> TextView) : FrameLayout(ctx) {
    private class Layer(val v: TextView) {
        var s = 0f
        var anim: ValueAnimator? = null
    }

    private val holder = FrameLayout(ctx)
    private var front = Layer(make())
    private var back = Layer(make())

    val text: CharSequence get() = front.v.text

    init {
        val h = if (fill) -1 else -2
        addView(holder, LayoutParams(-1, h))
        holder.addView(front.v, LayoutParams(-1, h))
        holder.addView(back.v, LayoutParams(-1, h))
        back.v.visibility = View.INVISIBLE
    }

    private fun stop(l: Layer) {
        l.anim?.let { it.removeAllListeners(); it.cancel() }
        l.anim = null
    }

    private fun settle() {
        stop(front); stop(back)
        back.v.visibility = View.INVISIBLE; back.s = 0f; back.v.blurFx(0f, BlurDir.UP, 1)
        front.v.visibility = View.VISIBLE; front.s = 0f; front.v.blurFx(0f, BlurDir.UP, 1)
    }

    /** Fade all the text to [a] (the card behind it stays as it is). */
    fun dim(a: Float) = holder.fadeTo(a)

    /** Show [next]. Unchanged text does nothing; before the first layout, or with animations off, it just swaps. */
    fun set(next: CharSequence, dir: BlurDir = BlurDir.UP, animate: Boolean = true) {
        val cur = front.v.text
        if (cur.toString() == next.toString()) return
        if (!animate || !isLaidOut || cur.isEmpty() || !animationsOn(context)) {
            settle()
            front.v.text = next
            return
        }
        // the label that was already leaving is nearly gone: dropped. The one still arriving leaves from where it is.
        stop(back); back.v.visibility = View.INVISIBLE
        val old = front
        val from = old.s
        stop(old)
        val nw = back
        nw.v.text = next; nw.v.visibility = View.VISIBLE
        front = nw; back = old
        run(old, from, 1f, max(90L, (BlurTextTokens.OUT_MS * (1f - from)).toLong()), 0L, BlurTextTokens.outEase, dir, -1) {
            old.v.visibility = View.INVISIBLE
        }
        run(nw, 1f, 0f, BlurTextTokens.IN_MS, BlurTextTokens.IN_DELAY_MS, BlurTextTokens.inEase, dir, 1)
    }

    private fun run(l: Layer, from: Float, to: Float, dur: Long, delay: Long, ease: TimeInterpolator, dir: BlurDir, sign: Int, end: (() -> Unit)? = null) {
        l.s = from
        l.v.blurFx(from, dir, sign)
        val a = ValueAnimator.ofFloat(from, to).apply {
            duration = dur; startDelay = delay; interpolator = ease
            addUpdateListener { l.s = it.animatedValue as Float; l.v.blurFx(l.s, dir, sign) }
            addListener(object : AnimatorListenerAdapter() {
                override fun onAnimationEnd(animation: Animator) {
                    if (l.anim === animation) l.anim = null
                    end?.invoke()
                }
            })
        }
        l.anim = a
        a.start()
    }

    override fun onDetachedFromWindow() {
        settle()
        super.onDetachedFromWindow()
    }
}

/** Fade to [a]; straight to it before the first layout or with animations off. */
internal fun View.fadeTo(a: Float, ms: Long = 140L) {
    if (!isLaidOut || !animationsOn(context)) { animate().cancel(); alpha = a; return }
    animate().alpha(a).setDuration(ms).start()
}
