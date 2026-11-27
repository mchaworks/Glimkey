package dev.pillboard.keyboard

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RectF
import android.view.View

/** A miniature keyboard drawn with the real tokens, shell and keycap skin of [theme]: what you see is what you get. */
internal class ThemeSwatch(ctx: Context, private val theme: KbTheme) : View(ctx) {
    private val t = KbTokens(ctx, theme)
    private val shell = GlassSurfaceDrawable(t, 10.dp.toFloat().coerceAtMost(t.containerRadius), t.background, GlassSurfaceDrawable.Style.SHELL, false)
    private val body = KeyBodyDrawable()
    private val txt = Paint(Paint.ANTI_ALIAS_FLAG).apply { textAlign = Paint.Align.CENTER; typeface = t.typeface }
    private val rows = listOf("qwerty", "asdfgh", "zxcvb")

    override fun onDraw(canvas: Canvas) {
        shell.setBounds(0, 0, width, height)
        shell.draw(canvas)
        val pad = 7.dp.toFloat()
        val gap = 3.dp.toFloat()
        val cols = 7
        val kw = (width - pad * 2 - gap * (cols - 1)) / cols
        val kh = (height - pad * 2 - gap * (rows.size - 1)) / rows.size
        val r = (Prefs.keyRadius(context, theme).dp.toFloat() * 0.6f).coerceAtMost(kh / 2.2f)
        val radii = FloatArray(8) { r }
        txt.textSize = kh * 0.46f
        rows.forEachIndexed { ri, row ->
            val n = row.length + if (ri == rows.lastIndex) 1 else 0
            val x0 = pad + (cols - n) * (kw + gap) / 2f
            for (i in 0 until n) {
                val accent = ri == rows.lastIndex && i == n - 1
                val fill = if (accent) t.accent else t.key
                val l = x0 + i * (kw + gap)
                val tp = pad + ri * (kh + gap)
                val rect = RectF(l, tp, l + kw, tp + kh)
                body.skin = t.skin
                body.edge = t.edgeOf(fill)
                body.line = t.lineOf(if (accent) KeyStyle.ACCENT else KeyStyle.LETTER)
                body.down = false
                body.setColors(t.sheen(fill))
                body.setCornerRadii(radii)
                body.setBounds(rect.left.toInt(), rect.top.toInt(), rect.right.toInt(), rect.bottom.toInt())
                body.draw(canvas)
                txt.color = if (accent) t.onAccent else t.keyText
                val label = if (accent) "\u21B5" else row[i].toString()
                canvas.drawText(label, rect.centerX(), rect.centerY() - (txt.ascent() + txt.descent()) / 2f + body.contentDy * 0.5f, txt)
            }
        }
    }
}
