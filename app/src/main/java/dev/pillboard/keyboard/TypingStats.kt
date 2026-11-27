package dev.pillboard.keyboard

import android.content.Context
import android.os.SystemClock
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale

/**
 * Typing statistics (Settings > Typing > Typing stats): counts only, never the text itself. Stays on this device, and
 * the service feeds it only where learning is allowed (so never in password fields). Written to Prefs in small batches.
 */
internal object TypingStats {
    const val PREF = "stats"
    private const val IDLE_MS = 2500L       // a longer pause ends a typing burst
    private const val MIN_BURST = 15        // shorter bursts say nothing about speed

    private var pendingKeys = 0
    private val pendingLetters = IntArray(26)
    private var burstStart = 0L
    private var burstLast = 0L
    private var burstChars = 0
    private var pendingMs = 0L
    private var pendingBest = 0
    private var pendingDels = 0
    private var pendingWords = 0

    fun enabled(c: Context) = Prefs.bool(c, PREF, true)

    private fun dayKey(offset: Int = 0): String {
        val cal = Calendar.getInstance(); cal.add(Calendar.DAY_OF_YEAR, -offset)
        return "st_d_" + SimpleDateFormat("yyyyMMdd", Locale.US).format(Date(cal.timeInMillis))
    }

    private fun endBurst() {
        if (burstChars >= 2) {
            val dur = (burstLast - burstStart).coerceAtLeast(0L)
            pendingMs += dur
            if (burstChars >= MIN_BURST && dur > 0) {
                val wpm = ((burstChars / 5f) / (dur / 60000f)).toInt()
                if (wpm > pendingBest) pendingBest = wpm.coerceAtMost(400)
            }
        }
        burstChars = 0
    }

    fun key(c: Context, s: String) {
        if (!enabled(c)) return
        val now = SystemClock.uptimeMillis()
        if (burstChars == 0 || now - burstLast > IDLE_MS) { endBurst(); burstStart = now }
        burstLast = now; burstChars++
        pendingKeys++
        if (s.length == 1) { val l = s[0].lowercaseChar(); if (l in 'a'..'z') pendingLetters[l - 'a']++ }
        if (pendingKeys >= 40) flush(c)
    }

    fun delete(c: Context) { if (enabled(c)) { pendingDels++; burstLast = SystemClock.uptimeMillis() } }
    fun word(c: Context) { if (enabled(c)) pendingWords++ }

    fun flush(c: Context) {
        endBurst()
        if (pendingKeys == 0 && pendingDels == 0 && pendingWords == 0 && pendingMs == 0L) return
        Prefs.setInt(c, "st_keys", Prefs.int(c, "st_keys", 0) + pendingKeys)
        Prefs.setInt(c, "st_del", Prefs.int(c, "st_del", 0) + pendingDels)
        Prefs.setInt(c, "st_words", Prefs.int(c, "st_words", 0) + pendingWords)
        Prefs.setInt(c, "st_sec", Prefs.int(c, "st_sec", 0) + (pendingMs / 1000L).toInt())
        if (pendingBest > Prefs.int(c, "st_best", 0)) Prefs.setInt(c, "st_best", pendingBest)
        val dk = dayKey(); Prefs.setInt(c, dk, Prefs.int(c, dk, 0) + pendingKeys)
        val l = letters(c); for (i in 0 until 26) l[i] += pendingLetters[i]
        Prefs.setStr(c, "st_letters", l.joinToString(","))
        pendingKeys = 0; pendingDels = 0; pendingWords = 0; pendingMs = 0L; pendingBest = 0
        pendingLetters.fill(0)
    }

    private fun letters(c: Context): IntArray {
        val a = IntArray(26)
        Prefs.str(c, "st_letters", "").split(",").forEachIndexed { i, s -> if (i < 26) a[i] = s.toIntOrNull() ?: 0 }
        return a
    }

    fun reset(c: Context) {
        pendingKeys = 0; pendingDels = 0; pendingWords = 0; pendingMs = 0L; pendingBest = 0; pendingLetters.fill(0); burstChars = 0
        for (k in listOf("st_keys", "st_del", "st_words", "st_sec", "st_best")) Prefs.setInt(c, k, 0)
        Prefs.setStr(c, "st_letters", "")
        for (i in 0 until 14) Prefs.setInt(c, dayKey(i), 0)
    }

    /** Plain-text report for the Settings dialog. */
    fun report(c: Context): String {
        flush(c)
        val keys = Prefs.int(c, "st_keys", 0)
        if (keys == 0) return "Nothing yet. Type for a bit and come back.\n\nOnly counts are kept, never what you type, and nothing is counted in password fields."
        val dels = Prefs.int(c, "st_del", 0)
        val words = Prefs.int(c, "st_words", 0)
        val sec = Prefs.int(c, "st_sec", 0)
        val avg = if (sec >= 20) ((keys / 5f) / (sec / 60f)).toInt() else 0
        val acc = (100f * (1f - dels.toFloat() / keys.coerceAtLeast(1))).coerceIn(0f, 100f)
        val today = Prefs.int(c, dayKey(0), 0)
        var week = 0; for (i in 0 until 7) week += Prefs.int(c, dayKey(i), 0)
        val top = letters(c).withIndex().filter { it.value > 0 }.sortedByDescending { it.value }.take(5)
            .joinToString("  ") { "${'a' + it.index} ${it.value}" }
        val sb = StringBuilder()
        sb.append("Today: ").append(today).append(" keys\n")
        sb.append("Last 7 days: ").append(week).append(" keys\n")
        sb.append("All time: ").append(keys).append(" keys, ").append(words).append(" words\n\n")
        if (avg > 0) sb.append("Average speed: ").append(avg).append(" WPM\n")
        val best = Prefs.int(c, "st_best", 0)
        if (best > 0) sb.append("Best burst: ").append(best).append(" WPM\n")
        sb.append("Backspaces: ").append(dels).append("  (").append(String.format(Locale.US, "%.1f", acc)).append("% kept)\n")
        if (top.isNotEmpty()) sb.append("\nTop letters: ").append(top)
        return sb.toString()
    }
}
