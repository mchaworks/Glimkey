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
    /** 0..PRESETS.lastIndex = preset, PRESETS.size = custom (hue slider). */
    fun preset(c: Context) = int(c, "preset", 0).coerceIn(0, PRESETS.size)
    fun hue(c: Context) = int(c, "hue", 260).coerceIn(0, 359)
    fun customColor(c: Context): Int = Color.HSVToColor(floatArrayOf(hue(c).toFloat(), 0.55f, 0.85f))
    fun seedColor(c: Context): Int = preset(c).let { if (it >= PRESETS.size) customColor(c) else PRESETS[it].second }

    /** name to seed color; used when Material You colors is off */
    val PRESETS = listOf(
        "Lavender" to 0xFF6750A4.toInt(),
        "Mint" to 0xFF2E7D6B.toInt(),
        "Sunset" to 0xFFE8743B.toInt(),
        "Midnight" to 0xFF5C6BC0.toInt(),
        "Rose" to 0xFFD81B60.toInt(),
        "Ocean" to 0xFF0288D1.toInt(),
    )
    fun clear(c: Context) = sp(c).edit().clear().apply()
}

private var cachedVibrator: android.os.Vibrator? = null

/**
 * One place for key haptics: honours the in-app toggle AND still vibrates when the system-wide "touch feedback" is off.
 * FLAG_IGNORE_GLOBAL_SETTING is deprecated since API 33, so there we drive the Vibrator directly (needs VIBRATE).
 */
@Suppress("DEPRECATION")
fun android.view.View.keyHaptic(kind: Int = android.view.HapticFeedbackConstants.KEYBOARD_TAP) {
    if (!Prefs.bool(context, "haptic", true)) return
    if (android.os.Build.VERSION.SDK_INT >= 33) {
        val v = cachedVibrator ?: (context.getSystemService(android.content.Context.VIBRATOR_MANAGER_SERVICE)
            as android.os.VibratorManager).defaultVibrator.also { cachedVibrator = it }
        if (v.hasVibrator()) {
            val effect = when (kind) {
                android.view.HapticFeedbackConstants.LONG_PRESS -> android.os.VibrationEffect.EFFECT_HEAVY_CLICK
                android.view.HapticFeedbackConstants.CLOCK_TICK -> android.os.VibrationEffect.EFFECT_TICK
                else -> android.os.VibrationEffect.EFFECT_CLICK
            }
            v.vibrate(android.os.VibrationEffect.createPredefined(effect))
            return
        }
    }
    performHapticFeedback(kind, android.view.HapticFeedbackConstants.FLAG_IGNORE_GLOBAL_SETTING)
}
