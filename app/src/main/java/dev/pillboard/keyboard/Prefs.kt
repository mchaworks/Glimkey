package dev.pillboard.keyboard

import android.content.Context
import android.graphics.Color

object Prefs {
    private fun sp(c: Context) = c.getSharedPreferences("pillboard", Context.MODE_PRIVATE)
    fun bool(c: Context, k: String, d: Boolean) = sp(c).getBoolean(k, d)
    fun setBool(c: Context, k: String, v: Boolean) = sp(c).edit().putBoolean(k, v).apply()
    fun str(c: Context, k: String, d: String) = sp(c).getString(k, d) ?: d
    fun setStr(c: Context, k: String, v: String) = sp(c).edit().putString(k, v).apply()
    fun int(c: Context, k: String, d: Int) = sp(c).getInt(k, d)
    fun setInt(c: Context, k: String, v: Int) = sp(c).edit().putInt(k, v).apply()
    /** Settings > Layout > Layout style: "ios" (default) or "gboard". */
    fun gboard(c: Context) = str(c, "layout_style", "ios") == "gboard"
    fun has(c: Context, k: String) = sp(c).contains(k)
    /** Key corner radius / spacing (dp) of [th]; every theme remembers its own, Liquid Glass keeps the original keys. */
    fun keyRadius(c: Context, th: KbTheme = KbTheme.current(c)) = int(c, th.keyRKey, th.keyRadiusDp)
    fun keyGap(c: Context, th: KbTheme = KbTheme.current(c)) = int(c, th.keyGapKey, th.keyGapDp)
    fun dynamic(c: Context) = bool(c, "dynamic", true) && android.os.Build.VERSION.SDK_INT >= 31
    /** Index into [PRESETS], or [CUSTOM] (hue slider). Old builds stored "custom" as 6, before the list grew. */
    fun preset(c: Context): Int {
        val v = int(c, "preset", 0)
        return when {
            v == CUSTOM -> CUSTOM
            v == 6 && !bool(c, "accent_v2", false) -> CUSTOM
            v in PRESETS.indices -> v
            else -> 0
        }
    }
    fun setPreset(c: Context, i: Int) { setBool(c, "accent_v2", true); setInt(c, "preset", i) }
    const val CUSTOM = 100
    /** How the secondary and tertiary tones are derived from the accent: Tonal / Vivid / Muted / Bold. */
    fun accentStyle(c: Context) = AccentStyle.of(str(c, "accent_style", "tonal"))
    fun hue(c: Context) = int(c, "hue", 260).coerceIn(0, 359)
    fun customColor(c: Context): Int = Color.HSVToColor(floatArrayOf(hue(c).toFloat(), 0.55f, 0.85f))
    fun seedColor(c: Context): Int = preset(c).let { if (it == CUSTOM) customColor(c) else PRESETS[it].second }

    /** name to seed color; used when Material You colors is off */
    val PRESETS = listOf(
        "Lavender" to 0xFF6750A4.toInt(),
        "Mint" to 0xFF2E7D6B.toInt(),
        "Sunset" to 0xFFE8743B.toInt(),
        "Midnight" to 0xFF5C6BC0.toInt(),
        "Rose" to 0xFFD81B60.toInt(),
        "Ocean" to 0xFF0288D1.toInt(),
        // appended: the first six keep their index so saved choices survive
        "Sky" to 0xFF29B6F6.toInt(),
        "Teal" to 0xFF00897B.toInt(),
        "Forest" to 0xFF388E3C.toInt(),
        "Lime" to 0xFF7CB342.toInt(),
        "Amber" to 0xFFF9A825.toInt(),
        "Coral" to 0xFFF4511E.toInt(),
        "Raspberry" to 0xFFAD1457.toInt(),
        "Orchid" to 0xFF9C27B0.toInt(),
        "Peach" to 0xFFFF8A65.toInt(),
        "Cocoa" to 0xFF8D6E63.toInt(),
        "Slate" to 0xFF607D8B.toInt(),
        "Graphite" to 0xFF757575.toInt(),
    )
    fun clear(c: Context) = sp(c).edit().clear().apply()
}

/** Key haptics: see [Haptics] (style, strength, release tick); the in-app toggle is honoured there. */
fun android.view.View.keyHaptic(kind: Int = android.view.HapticFeedbackConstants.KEYBOARD_TAP) = Haptics.play(context, kind, this)
