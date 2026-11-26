package dev.pillboard.keyboard

import android.app.Activity
import android.app.NotificationManager
import android.content.Context
import android.content.pm.PackageManager
import android.media.AudioAttributes
import android.media.AudioManager
import android.os.Build
import android.os.PowerManager
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager
import android.provider.Settings
import android.view.HapticFeedbackConstants
import android.view.View
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import com.google.android.material.dialog.MaterialAlertDialogBuilder

/** Settings > Feedback > Haptic test: fires one vibration method per button, so we can see which one this phone actually plays. */
@Suppress("DEPRECATION")
internal object HapticLab {
    private fun vib(c: Context): Vibrator? =
        if (Build.VERSION.SDK_INT >= 31) (c.getSystemService(Context.VIBRATOR_MANAGER_SERVICE) as? VibratorManager)?.defaultVibrator
        else c.getSystemService(Context.VIBRATOR_SERVICE) as? Vibrator

    private fun info(c: Context): String {
        val v = vib(c)
        val sb = StringBuilder()
        fun l(k: String, x: Any?) { sb.append(k).append(": ").append(x).append('\n') }
        val ver = try { c.packageManager.getPackageInfo(c.packageName, 0).versionName } catch (_: Exception) { "?" }
        l("Build", ver)
        l("Phone", "${Build.MANUFACTURER} ${Build.MODEL}")
        l("Android SDK", Build.VERSION.SDK_INT)
        l("VIBRATE permission", c.checkSelfPermission("android.permission.VIBRATE") == PackageManager.PERMISSION_GRANTED)
        l("hasVibrator", v?.hasVibrator())
        l("amplitude control", v?.hasAmplitudeControl())
        if (Build.VERSION.SDK_INT >= 30 && v != null) {
            val ids = intArrayOf(
                VibrationEffect.Composition.PRIMITIVE_CLICK, VibrationEffect.Composition.PRIMITIVE_TICK,
                VibrationEffect.Composition.PRIMITIVE_LOW_TICK, VibrationEffect.Composition.PRIMITIVE_THUD)
            l("primitives click/tick/low/thud", v.arePrimitivesSupported(*ids).joinToString("/"))
        }
        l("system haptic setting", try { Settings.System.getInt(c.contentResolver, Settings.System.HAPTIC_FEEDBACK_ENABLED) } catch (_: Exception) { "?" })
        l("ringer mode (0 silent,1 vibrate,2 normal)", (c.getSystemService(Context.AUDIO_SERVICE) as AudioManager).ringerMode)
        l("battery saver", (c.getSystemService(Context.POWER_SERVICE) as PowerManager).isPowerSaveMode)
        l("DND filter (1 all)", (c.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager).currentInterruptionFilter)
        l("haptic pref / engine", "${Prefs.bool(c, "haptic", true)} / ${Prefs.str(c, "haptic_engine", "system")}")
        return sb.toString()
    }

    private fun attrs(usage: Int) = AudioAttributes.Builder().setUsage(usage).build()

    fun show(a: Activity) {
        val v = vib(a)
        val col = LinearLayout(a).apply { orientation = LinearLayout.VERTICAL; setPadding(48, 24, 48, 8) }
        col.addView(TextView(a).apply { text = info(a); textSize = 12f })
        fun probe(label: String, f: (View) -> Unit) = col.addView(Button(a).apply {
            text = label; isAllCaps = false
            setOnClickListener { try { f(this) } catch (t: Throwable) { text = "$label  -> ERROR ${t.javaClass.simpleName}" } }
        })
        if (Build.VERSION.SDK_INT >= 29 && v != null) {
            probe("1. Predefined CLICK") { v.vibrate(VibrationEffect.createPredefined(VibrationEffect.EFFECT_CLICK)) }
            probe("2. Predefined HEAVY_CLICK") { v.vibrate(VibrationEffect.createPredefined(VibrationEffect.EFFECT_HEAVY_CLICK)) }
        }
        if (v != null) {
            probe("3. One-shot 40 ms, full") { v.vibrate(VibrationEffect.createOneShot(40, VibrationEffect.DEFAULT_AMPLITUDE)) }
            probe("4. One-shot 8 ms, full (old waveform length)") { v.vibrate(VibrationEffect.createOneShot(8, 255)) }
            probe("5. Plain vibrate 100 ms (legacy)") { v.vibrate(100) }
        }
        if (Build.VERSION.SDK_INT >= 30 && v != null) {
            probe("6. Primitive CLICK 1.0") { v.vibrate(VibrationEffect.startComposition().addPrimitive(VibrationEffect.Composition.PRIMITIVE_CLICK, 1f).compose()) }
        }
        if (v != null) {
            probe("7. CLICK as ALARM usage") { v.vibrate(VibrationEffect.createOneShot(40, VibrationEffect.DEFAULT_AMPLITUDE), attrs(AudioAttributes.USAGE_ALARM)) }
            probe("8. CLICK as ACCESSIBILITY usage") { v.vibrate(VibrationEffect.createOneShot(40, VibrationEffect.DEFAULT_AMPLITUDE), attrs(AudioAttributes.USAGE_ASSISTANCE_ACCESSIBILITY)) }
        }
        probe("9. View haptic KEYBOARD_TAP") { it.performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP) }
        probe("10. View haptic + IGNORE_GLOBAL_SETTING") { it.performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP, HapticFeedbackConstants.FLAG_IGNORE_GLOBAL_SETTING) }
        probe("11. The keyboard's own engine (Haptics.play)") { Haptics.play(a, HapticFeedbackConstants.KEYBOARD_TAP, it) }
        MaterialAlertDialogBuilder(a).setTitle("Haptic test").setView(ScrollView(a).apply { addView(col) })
            .setPositiveButton("Close", null).show()
    }
}
