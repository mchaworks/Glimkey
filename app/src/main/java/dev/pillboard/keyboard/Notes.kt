package dev.pillboard.keyboard

import android.animation.ValueAnimator
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.os.SystemClock
import android.text.TextUtils
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.View
import android.view.inputmethod.BaseInputConnection
import android.view.inputmethod.ExtractedText
import android.view.inputmethod.ExtractedTextRequest
import android.widget.ScrollView
import android.widget.TextView
import androidx.core.graphics.ColorUtils
import org.json.JSONArray
import org.json.JSONObject
import kotlin.math.PI
import kotlin.math.cos

/** One saved note. The title is its first line; there is no separate title field to fill in. */
internal data class Note(val id: Long, val body: String, val updated: Long) {
    private val lines get() = body.lineSequence().map { it.trim() }.filter { it.isNotEmpty() }
    val title: String get() = lines.firstOrNull()?.take(40) ?: "Untitled"
    val preview: String get() = lines.drop(1).joinToString(" ").take(160)
    val words: Int get() = body.trim().split(Regex("\\s+")).count { it.isNotEmpty() }
}

/** Notes live on the device only (the app's private preferences), newest first. A note that becomes empty is deleted. */
internal object NoteStore {
    private const val KEY = "notes_v1"

    fun all(c: Context): List<Note> = runCatching {
        val a = JSONArray(Prefs.str(c, KEY, "[]"))
        (0 until a.length()).map { val o = a.getJSONObject(it); Note(o.getLong("id"), o.getString("body"), o.getLong("t")) }
            .sortedByDescending { it.updated }
    }.getOrDefault(emptyList())

    private fun write(c: Context, list: List<Note>) {
        val a = JSONArray()
        list.forEach { a.put(JSONObject().put("id", it.id).put("body", it.body).put("t", it.updated)) }
        Prefs.setStr(c, KEY, a.toString())
    }

    fun newId() = System.currentTimeMillis()

    /** Saves [body] under [id] (an empty body removes the note). An unchanged body keeps its date. */
    fun save(c: Context, id: Long, body: String) {
        val list = all(c).toMutableList()
        val i = list.indexOfFirst { it.id == id }
        if (body.isBlank()) { if (i >= 0) { list.removeAt(i); write(c, list) }; return }
        if (i >= 0 && list[i].body == body) return
        val n = Note(id, body, System.currentTimeMillis())
        if (i >= 0) list[i] = n else list.add(n)
        write(c, list)
    }

    fun delete(c: Context, id: Long) = write(c, all(c).filter { it.id != id })
}

/**
 * The text of the note being edited, with a selection (anchor + cursor) and an undo history. The keyboard types into it
 * through [NoteConnection], so every key, suggestion and editing tool works on a note exactly as on an app's field.
 */
internal class NoteBuffer(initial: String) {
    val text = StringBuilder(initial)
    var anchor = initial.length; private set
    var cursor = initial.length; private set
    val selStart get() = minOf(anchor, cursor)
    val selEnd get() = maxOf(anchor, cursor)
    /** Called after every change; the flag says whether the text (not just the selection) changed. */
    val listeners = ArrayList<(Boolean) -> Unit>()
    /** Vertical caret movement needs the view's line layout: (offset, up) -> new offset, or -1. */
    var verticalMove: ((Int, Boolean) -> Int)? = null

    private class Snap(val text: String, val anchor: Int, val cursor: Int)
    private val undo = ArrayList<Snap>(); private val redo = ArrayList<Snap>()
    private var lastPush = 0L

    private fun fire(changed: Boolean) { listeners.toList().forEach { it(changed) } }
    private fun snap() = Snap(text.toString(), anchor, cursor)

    private fun remember(coalesce: Boolean) {
        val now = SystemClock.uptimeMillis()
        if (!(coalesce && now - lastPush < 800L && undo.isNotEmpty())) {
            undo.add(snap()); if (undo.size > 100) undo.removeAt(0)
        }
        lastPush = now; redo.clear()
    }

    fun setSel(a: Int, c: Int) {
        val n = text.length
        val na = a.coerceIn(0, n); val nc = c.coerceIn(0, n)
        if (na == anchor && nc == cursor) return
        anchor = na; cursor = nc; fire(false)
    }

    fun moveTo(off: Int, extend: Boolean) = setSel(if (extend) anchor else off.coerceIn(0, text.length), off)
    fun selectAll() = setSel(0, text.length)

    /** Replaces [from, to) with [s] and puts the cursor after it (or before it when [cursorAfter] is false). */
    fun replace(from: Int, to: Int, s: String, cursorAfter: Boolean = true, coalesce: Boolean = true) {
        val a = from.coerceIn(0, text.length); val b = to.coerceIn(a, text.length)
        if (a == b && s.isEmpty()) return
        remember(coalesce)
        text.replace(a, b, s)
        val c = if (cursorAfter) a + s.length else a
        anchor = c; cursor = c
        fire(true)
    }

    fun insert(s: String, cursorAfter: Boolean = true) = replace(selStart, selEnd, s, cursorAfter)

    fun backspace() {
        if (selStart != selEnd) { replace(selStart, selEnd, "", coalesce = false); return }
        if (cursor == 0) return
        var from = cursor - 1
        if (from > 0 && Character.isLowSurrogate(text[from]) && Character.isHighSurrogate(text[from - 1])) from--
        replace(from, cursor, "")
    }

    fun forwardDelete() {
        if (selStart != selEnd) { replace(selStart, selEnd, "", coalesce = false); return }
        if (cursor >= text.length) return
        val to = if (Character.isHighSurrogate(text[cursor]) && cursor + 1 < text.length) cursor + 2 else cursor + 1
        replace(cursor, to, "")
    }

    fun deleteSurrounding(before: Int, after: Int) {
        val s = selStart; val e = selEnd
        val a = (s - before).coerceAtLeast(0); val b = (e + after).coerceAtMost(text.length)
        if (a == s && b == e) return
        remember(true)
        text.delete(e, b)          // the part after first, so the indices before it stay valid
        text.delete(a, s)
        anchor = a; cursor = a + (e - s)
        fire(true)
    }

    fun lineStart(off: Int): Int {
        if (off <= 0) return 0
        val i = text.lastIndexOf('\n', off - 1)
        return if (i < 0) 0 else i + 1
    }

    fun lineEnd(off: Int): Int {
        val i = text.indexOf('\n', off.coerceAtMost(text.length))
        return if (i < 0) text.length else i
    }

    fun undo(): Boolean {
        val s = undo.removeLastOrNull() ?: return false
        redo.add(snap()); restore(s); return true
    }

    fun redo(): Boolean {
        val s = redo.removeLastOrNull() ?: return false
        undo.add(snap()); restore(s); return true
    }

    private fun restore(s: Snap) {
        text.setLength(0); text.append(s.text)
        anchor = s.anchor.coerceAtMost(text.length); cursor = s.cursor.coerceAtMost(text.length)
        lastPush = 0L; fire(true)
    }
}

/** The keyboard's [android.view.inputmethod.InputConnection] while a note is open: everything it types lands in [buf]. */
internal class NoteConnection(view: View, private val buf: NoteBuffer) : BaseInputConnection(view, false) {
    private val ctx = view.context

    override fun commitText(text: CharSequence?, newCursorPosition: Int): Boolean {
        buf.insert(text?.toString().orEmpty(), cursorAfter = newCursorPosition > 0); return true
    }
    override fun setComposingText(text: CharSequence?, newCursorPosition: Int): Boolean = commitText(text, newCursorPosition)
    override fun finishComposingText(): Boolean = true
    override fun deleteSurroundingText(beforeLength: Int, afterLength: Int): Boolean { buf.deleteSurrounding(beforeLength, afterLength); return true }
    override fun getTextBeforeCursor(n: Int, flags: Int): CharSequence = buf.text.substring((buf.selStart - n).coerceAtLeast(0), buf.selStart)
    override fun getTextAfterCursor(n: Int, flags: Int): CharSequence = buf.text.substring(buf.selEnd, (buf.selEnd + n).coerceAtMost(buf.text.length))
    override fun getSelectedText(flags: Int): CharSequence? = if (buf.selStart == buf.selEnd) null else buf.text.substring(buf.selStart, buf.selEnd)
    override fun setSelection(start: Int, end: Int): Boolean { buf.setSel(start, end); return true }
    override fun getExtractedText(request: ExtractedTextRequest?, flags: Int): ExtractedText? = null
    override fun getCursorCapsMode(reqModes: Int): Int = TextUtils.getCapsMode(buf.text, buf.selStart, reqModes)
    override fun performEditorAction(actionCode: Int): Boolean { buf.insert("\n"); return true }

    override fun sendKeyEvent(event: KeyEvent): Boolean {
        if (event.action != KeyEvent.ACTION_DOWN) return true
        val shift = event.isShiftPressed
        when (event.keyCode) {
            KeyEvent.KEYCODE_DEL -> buf.backspace()
            KeyEvent.KEYCODE_FORWARD_DEL -> buf.forwardDelete()
            KeyEvent.KEYCODE_ENTER, KeyEvent.KEYCODE_NUMPAD_ENTER -> buf.insert("\n")
            KeyEvent.KEYCODE_DPAD_LEFT ->
                if (!shift && buf.selStart != buf.selEnd) buf.moveTo(buf.selStart, false)
                else buf.moveTo(prevOffset(buf.cursor), shift)
            KeyEvent.KEYCODE_DPAD_RIGHT ->
                if (!shift && buf.selStart != buf.selEnd) buf.moveTo(buf.selEnd, false)
                else buf.moveTo(nextOffset(buf.cursor), shift)
            KeyEvent.KEYCODE_DPAD_UP, KeyEvent.KEYCODE_DPAD_DOWN -> {
                val to = buf.verticalMove?.invoke(buf.cursor, event.keyCode == KeyEvent.KEYCODE_DPAD_UP) ?: -1
                if (to >= 0) buf.moveTo(to, shift)
            }
            KeyEvent.KEYCODE_MOVE_HOME -> buf.moveTo(buf.lineStart(buf.cursor), shift)
            KeyEvent.KEYCODE_MOVE_END -> buf.moveTo(buf.lineEnd(buf.cursor), shift)
        }
        return true
    }

    private fun prevOffset(o: Int): Int {
        if (o <= 0) return 0
        return if (o >= 2 && Character.isLowSurrogate(buf.text[o - 1]) && Character.isHighSurrogate(buf.text[o - 2])) o - 2 else o - 1
    }
    private fun nextOffset(o: Int): Int {
        if (o >= buf.text.length) return buf.text.length
        return if (Character.isHighSurrogate(buf.text[o]) && o + 1 < buf.text.length) o + 2 else o + 1
    }

    override fun performContextMenuAction(id: Int): Boolean {
        val cm = ctx.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        when (id) {
            android.R.id.selectAll -> buf.selectAll()
            android.R.id.undo -> buf.undo()
            android.R.id.redo -> buf.redo()
            android.R.id.copy, android.R.id.cut -> {
                if (buf.selStart == buf.selEnd) return true
                cm.setPrimaryClip(ClipData.newPlainText("note", buf.text.substring(buf.selStart, buf.selEnd)))
                if (id == android.R.id.cut) buf.replace(buf.selStart, buf.selEnd, "", coalesce = false)
            }
            android.R.id.paste -> {
                val t = cm.primaryClip?.takeIf { it.itemCount > 0 }?.getItemAt(0)?.coerceToText(ctx)?.toString() ?: return true
                buf.replace(buf.selStart, buf.selEnd, t, coalesce = false)
            }
            else -> return false
        }
        return true
    }
}

/**
 * The note's text on the dock's card: draws the selection and a soft blinking caret itself (the keyboard window never has
 * focus, so a real EditText would show neither). A tap places the caret, a long press selects the word, and the card
 * scrolls to keep the caret in sight.
 */
internal class NoteView(ctx: Context, private val buf: NoteBuffer, private val accent: Int) : TextView(ctx) {
    private val selPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = ColorUtils.setAlphaComponent(accent, 0x55) }
    private val caret = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = accent }
    private val path = Path(); private val r = RectF()
    private var phase = 0f
    private var lastEdit = 0L
    private var blink: ValueAnimator? = null
    private var downX = 0f; private var downY = 0f
    private val listener: (Boolean) -> Unit = { changed ->
        lastEdit = SystemClock.uptimeMillis()
        if (changed && text.toString() != buf.text.toString()) text = buf.text.toString()
        invalidate(); keepCaretVisible(); post { keepCaretVisible() }
    }

    init {
        text = buf.text.toString()
        isClickable = true; isLongClickable = true
        includeFontPadding = false
        setLineSpacing(0f, 1.08f)
        buf.verticalMove = { off, up ->
            val l = layout
            if (l == null) -1 else {
                val line = l.getLineForOffset(off.coerceIn(0, length()))
                val to = if (up) line - 1 else line + 1
                if (to < 0) 0 else if (to >= l.lineCount) length() else l.getOffsetForHorizontal(to, l.getPrimaryHorizontal(off.coerceIn(0, length())))
            }
        }
        setOnLongClickListener {
            val i = getOffsetForPosition(downX, downY).coerceIn(0, buf.text.length)
            var a = i; var b = i
            while (a > 0 && buf.text[a - 1].isLetterOrDigit()) a--
            while (b < buf.text.length && buf.text[b].isLetterOrDigit()) b++
            if (a != b) buf.setSel(a, b)
            true
        }
    }

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        buf.listeners.add(listener)
        if (animationsOn(context)) {
            blink = ValueAnimator.ofFloat(0f, 1f).apply {
                duration = 1100L; repeatCount = ValueAnimator.INFINITE
                interpolator = android.view.animation.LinearInterpolator()
                addUpdateListener { phase = it.animatedFraction; if (buf.selStart == buf.selEnd) invalidate() }
                start()
            }
        }
    }

    override fun onDetachedFromWindow() {
        buf.listeners.remove(listener); blink?.cancel(); blink = null
        super.onDetachedFromWindow()
    }

    override fun onDraw(c: Canvas) {
        val l = layout
        if (l != null && buf.selStart != buf.selEnd) {
            c.save(); c.translate(totalPaddingLeft.toFloat(), totalPaddingTop.toFloat())
            path.reset(); l.getSelectionPath(buf.selStart, buf.selEnd, path); c.drawPath(path, selPaint)
            c.restore()
        }
        super.onDraw(c)
        if (l != null && buf.selStart == buf.selEnd) {
            val off = buf.cursor.coerceIn(0, length())
            val line = l.getLineForOffset(off)
            val x = l.getPrimaryHorizontal(off) + totalPaddingLeft
            val steady = blink == null || SystemClock.uptimeMillis() - lastEdit < 500L
            val a = if (steady) 1f else 0.2f + 0.8f * (0.5f + 0.5f * cos(2.0 * PI * phase).toFloat())
            caret.alpha = (255 * a).toInt()
            val w = 2f * resources.displayMetrics.density
            r.set(x - w / 2f, (l.getLineTop(line) + totalPaddingTop).toFloat(), x + w / 2f, (l.getLineBottom(line) + totalPaddingTop).toFloat())
            c.drawRoundRect(r, w / 2f, w / 2f, caret)
        }
    }

    private fun keepCaretVisible() {
        val l = layout ?: return
        val sv = parent as? ScrollView ?: return
        val line = l.getLineForOffset(buf.cursor.coerceIn(0, length()))
        val top = l.getLineTop(line) + totalPaddingTop - 4
        val bot = l.getLineBottom(line) + totalPaddingTop + 4
        val vh = sv.height - sv.paddingTop - sv.paddingBottom
        if (vh <= 0) return
        if (top < sv.scrollY) sv.smoothScrollTo(0, top.coerceAtLeast(0)) else if (bot > sv.scrollY + vh) sv.smoothScrollTo(0, bot - vh)
    }

    override fun onTouchEvent(e: MotionEvent): Boolean {
        if (e.actionMasked == MotionEvent.ACTION_DOWN) { downX = e.x; downY = e.y }
        val handled = super.onTouchEvent(e)
        if (e.actionMasked == MotionEvent.ACTION_UP && e.eventTime - e.downTime < 350L &&
            Math.abs(e.x - downX) < 12 * resources.displayMetrics.density && Math.abs(e.y - downY) < 12 * resources.displayMetrics.density) {
            buf.moveTo(getOffsetForPosition(e.x, e.y).coerceIn(0, buf.text.length), false)
        }
        return handled
    }
}
