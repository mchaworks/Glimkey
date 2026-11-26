package dev.pillboard.keyboard

import android.content.Context
import android.os.Build
import android.os.VibrationEffect
import android.os.VibrationEffect.Composition
import android.os.Vibrator
import android.os.VibratorManager
import android.view.HapticFeedbackConstants
import android.view.View

/** A kind beyond the system's own constants: the finger lifting off a key. */
const val HAPTIC_RELEASE = 9001

/** The feel of a key press (Settings > Feedback > Haptic style). */
enum class HapticStyle(val id: String, val label: String) {
    /** Short, sharp tick. */
    CRISP("crisp", "Crisp"),
    /** Faint, rounded, like a soft pad. */
    SOFT("soft", "Soft"),
    /** Full click with weight. */
    HEAVY("heavy", "Heavy"),
    /** Click on the way down, a tick on the way up: a switch with a bottom-out. */
    MECH("mech", "Mechanical");

    companion object { fun of(id: String?): HapticStyle = values().firstOrNull { it.id == id } ?: CRISP }
}

/**
 * Key haptics, one place. On phones with haptic primitives (Android 11+ and a capable motor) a press is a composed
 * effect scaled by the Strength setting; on the rest it is a waveform (amplitude-scaled where the motor allows,
 * duration-scaled where it does not). Always drives the Vibrator directly, so it works with the system's "touch
 * feedback" switched off. Effects are cached per (style, strength): a tap costs one vibrate() call.
 */
internal object Haptics {
    private const val TAP = 0
    private const val LONG = 1
    private const val TICK = 2
    private const val RELEASE = 3

    private var vib: Vibrator? = null
    private var cacheKey = -1
    private val cache = arrayOfNulls<VibrationEffect>(4)

    @Suppress("DEPRECATION")
    private fun vibrator(c: Context): Vibrator? {
        vib?.let { return it }
        val v = if (Build.VERSION.SDK_INT >= 31)
            (c.getSystemService(Context.VIBRATOR_MANAGER_SERVICE) as? VibratorManager)?.defaultVibrator
        else c.getSystemService(Context.VIBRATOR_SERVICE) as? Vibrator
        vib = v
        return v
    }

    private fun slot(kind: Int) = when (kind) {
        HapticFeedbackConstants.LONG_PRESS -> LONG
        HapticFeedbackConstants.CLOCK_TICK -> TICK
        HAPTIC_RELEASE -> RELEASE
        else -> TAP
    }

    /** Plays [kind]; [v] only matters for the last-resort system fallback. */
    @Suppress("DEPRECATION")
    fun play(c: Context, kind: Int = HapticFeedbackConstants.KEYBOARD_TAP, v: View? = null) {
        if (!Prefs.bool(c, "haptic", true)) return
        if (kind == HAPTIC_RELEASE && !Prefs.bool(c, "haptic_release", true)) return
        val strength = Prefs.int(c, "haptic_strength", 70).coerceIn(5, 100)
        val style = HapticStyle.of(Prefs.str(c, "haptic_style", "crisp"))
        val vb = vibrator(c)
        if (vb != null && vb.hasVibrator()) {
            val key = strength * 8 + style.ordinal
            if (key != cacheKey) { cacheKey = key; cache.fill(null) }
            val i = slot(kind)
            val effect = cache[i] ?: build(vb, style, strength, i).also { cache[i] = it }
            vb.vibrate(effect)
            return
        }
        if (kind != HAPTIC_RELEASE) v?.performHapticFeedback(kind, HapticFeedbackConstants.FLAG_IGNORE_GLOBAL_SETTING)
    }

    private fun build(vb: Vibrator, s: HapticStyle, strength: Int, slot: Int): VibrationEffect {
        if (Build.VERSION.SDK_INT >= 30) {
            if (vb.areAllPrimitivesSupported(
                    Composition.PRIMITIVE_CLICK, Composition.PRIMITIVE_TICK,
                    Composition.PRIMITIVE_LOW_TICK, Composition.PRIMITIVE_THUD)) {
                return composed(s, strength / 100f, slot)
            }
        }
        return wave(vb, s, strength, slot)
    }

    @android.annotation.SuppressLint("NewApi")
    private fun composed(s: HapticStyle, k: Float, slot: Int): VibrationEffect {
        val b = VibrationEffect.startComposition()
        fun add(prim: Int, scale: Float, delayMs: Int = 0) { b.addPrimitive(prim, scale.coerceIn(0.02f, 1f), delayMs) }
        when (slot) {
            TAP -> when (s) {
                HapticStyle.CRISP -> add(Composition.PRIMITIVE_TICK, k)
                HapticStyle.SOFT -> add(Composition.PRIMITIVE_LOW_TICK, k)
                HapticStyle.HEAVY -> add(Composition.PRIMITIVE_CLICK, k)
                HapticStyle.MECH -> add(Composition.PRIMITIVE_CLICK, k * 0.8f)
            }
            LONG -> when (s) {
                HapticStyle.CRISP -> add(Composition.PRIMITIVE_CLICK, k)
                HapticStyle.SOFT -> add(Composition.PRIMITIVE_CLICK, k * 0.7f)
                HapticStyle.HEAVY -> { add(Composition.PRIMITIVE_THUD, k); add(Composition.PRIMITIVE_CLICK, k, 40) }
                HapticStyle.MECH -> { add(Composition.PRIMITIVE_CLICK, k); add(Composition.PRIMITIVE_THUD, k * 0.8f, 30) }
            }
            TICK -> when (s) {
                HapticStyle.CRISP -> add(Composition.PRIMITIVE_TICK, k * 0.8f)
                HapticStyle.SOFT -> add(Composition.PRIMITIVE_LOW_TICK, k * 0.7f)
                HapticStyle.HEAVY -> add(Composition.PRIMITIVE_TICK, k)
                HapticStyle.MECH -> add(Composition.PRIMITIVE_LOW_TICK, k)
            }
            else -> when (s) {
                HapticStyle.CRISP -> add(Composition.PRIMITIVE_LOW_TICK, k * 0.5f)
                HapticStyle.SOFT -> add(Composition.PRIMITIVE_LOW_TICK, k * 0.35f)
                HapticStyle.HEAVY -> add(Composition.PRIMITIVE_TICK, k * 0.6f)
                HapticStyle.MECH -> add(Composition.PRIMITIVE_TICK, k * 0.7f)
            }
        }
        return b.compose()
    }

    /** (silence before, buzz length ms, relative amplitude) */
    private class Pulse(val gap: Long, val ms: Long, val amp: Float)

    private fun pulses(s: HapticStyle, slot: Int): List<Pulse> = when (slot) {
        TAP -> when (s) {
            HapticStyle.CRISP -> listOf(Pulse(0, 8, 1f))
            HapticStyle.SOFT -> listOf(Pulse(0, 12, 0.55f))
            HapticStyle.HEAVY -> listOf(Pulse(0, 20, 1f))
            HapticStyle.MECH -> listOf(Pulse(0, 10, 0.8f))
        }
        LONG -> when (s) {
            HapticStyle.CRISP -> listOf(Pulse(0, 26, 1f))
            HapticStyle.SOFT -> listOf(Pulse(0, 22, 0.65f))
            HapticStyle.HEAVY -> listOf(Pulse(0, 30, 1f), Pulse(30, 18, 0.7f))
            HapticStyle.MECH -> listOf(Pulse(0, 16, 1f), Pulse(24, 22, 0.8f))
        }
        TICK -> when (s) {
            HapticStyle.CRISP -> listOf(Pulse(0, 5, 0.8f))
            HapticStyle.SOFT -> listOf(Pulse(0, 6, 0.4f))
            HapticStyle.HEAVY -> listOf(Pulse(0, 8, 0.9f))
            HapticStyle.MECH -> listOf(Pulse(0, 6, 0.7f))
        }
        else -> when (s) {
            HapticStyle.CRISP -> listOf(Pulse(0, 4, 0.4f))
            HapticStyle.SOFT -> listOf(Pulse(0, 5, 0.25f))
            HapticStyle.HEAVY -> listOf(Pulse(0, 6, 0.5f))
            HapticStyle.MECH -> listOf(Pulse(0, 5, 0.5f))
        }
    }

    private fun wave(vb: Vibrator, s: HapticStyle, strength: Int, slot: Int): VibrationEffect {
        val k = strength / 100f
        val amplitude = vb.hasAmplitudeControl()
        val ps = pulses(s, slot)
        val timings = LongArray(ps.size * 2)
        val amps = IntArray(ps.size * 2)
        ps.forEachIndexed { i, p ->
            timings[i * 2] = p.gap
            // no amplitude control: strength can only change how long the motor runs
            timings[i * 2 + 1] = if (amplitude) p.ms else (p.ms * (0.4f + 0.6f * k)).toLong().coerceAtLeast(2L)
            amps[i * 2 + 1] = if (amplitude) (255 * k * p.amp).toInt().coerceIn(1, 255) else 255
        }
        return if (amplitude) VibrationEffect.createWaveform(timings, amps, -1)
        else VibrationEffect.createWaveform(timings, -1)
    }
}
