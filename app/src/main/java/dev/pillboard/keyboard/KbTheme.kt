package dev.pillboard.keyboard

import android.content.Context

/** How a keycap body is drawn: this is what makes a theme change the UI instead of just recolouring it. */
enum class KeySkin {
    /** Liquid Glass: flat tonal keycap, spring corner morph. */
    FLAT,
    /** Paper: index card with a hard offset shadow and an ink border; presses flat onto the page. */
    PAPER,
    /** Terminal: hairline-outlined block; the pressed key inverts to a solid phosphor block. */
    TERMINAL,
    /** Mechanical: dished cap on a darker skirt; the cap sinks into the skirt when pressed. */
    MECHANICAL,
    /** Clay (neumorphism): keys extruded from the shell with a soft light/dark shadow pair; pressed, they sink inward. */
    CLAY,
    /** Pixel: notched 8-bit blocks with a hard chunky shadow; pressed, the block drops onto its shadow. */
    PIXEL,
    /** Nokia LCD: ink-on-green keys with a ghost shadow like a slow LCD; pressed, the key fills solid ink. */
    LCD,
}

/**
 * A theme is geometry + palette + material ([skin]) + motion feel + typeface. Everything a component needs comes from
 * [KbTokens], which resolves it from the theme in use, so no component hard-codes a look.
 *
 * Per-theme key size and spacing are stored under their own preference keys ([keyRKey], [keyGapKey]); Liquid Glass keeps
 * the original keys so existing settings survive.
 */
enum class KbTheme(
    val id: String,
    val label: String,
    val blurb: String,
    val skin: KeySkin,
    val keyRadiusDp: Int,
    val keyGapDp: Int,
    val shellRadiusDp: Int,
    val stripRadiusDp: Int,
    val popupRadiusDp: Int,
) {
    GLASS("glass", "Liquid Glass", "Frosted shell, soft springy keys", KeySkin.FLAT, 10, 3, 30, 20, 20),
    PAPER("paper", "Paper", "Lined paper, index-card keys, ink", KeySkin.PAPER, 7, 4, 14, 6, 8),
    TERMINAL("terminal", "Terminal", "Phosphor on black, monospace, inverted keys", KeySkin.TERMINAL, 2, 3, 4, 2, 2),
    MECHANICAL("mechanical", "Mechanical", "Dished keycaps in a metal case that sink when pressed", KeySkin.MECHANICAL, 6, 4, 16, 8, 8),
    CLAY("clay", "Clay", "Soft neumorphic keys that press inward", KeySkin.CLAY, 14, 2, 24, 16, 16),
    PIXEL("pixel", "Pixel", "8-bit blocks, notched corners, chunky shadow", KeySkin.PIXEL, 4, 4, 0, 0, 0),
    LCD("lcd", "Nokia LCD", "Green LCD, ghosted keys, keypad beeps", KeySkin.LCD, 3, 3, 8, 3, 3);

    val isGlass get() = this == GLASS
    val keyRKey get() = if (this == GLASS) "key_r" else "key_r_$id"
    val keyGapKey get() = if (this == GLASS) "key_gap" else "key_gap_$id"

    /** Font overlay applied on top of the keyboard's Material theme (0 = keep Google Sans Rounded). */
    val fontOverlay: Int get() = when (this) {
        GLASS -> 0
        PAPER -> R.style.Overlay_Font_Serif
        TERMINAL -> R.style.Overlay_Font_Mono
        MECHANICAL -> R.style.Overlay_Font_Medium
        CLAY -> 0
        PIXEL -> R.style.Overlay_Font_Mono
        LCD -> R.style.Overlay_Font_Condensed
    }

    /** Typing-sound folder used when Settings > Sound style is "Follow theme". */
    val soundStyle: String get() = when (this) {
        GLASS -> "glass"
        PAPER -> "paper"
        TERMINAL -> "terminal"
        MECHANICAL -> "mechanical"
        CLAY -> "neu"
        PIXEL -> "pixel"
        LCD -> "lcd"
        else -> "ios"
    }

    internal fun palette(night: Boolean): Palette = when (this) {
        GLASS -> error("Liquid Glass resolves its colours from the Material theme")
        PAPER -> if (night) Palette.PAPER_NIGHT else Palette.PAPER_DAY
        TERMINAL -> Palette.TERMINAL
        MECHANICAL -> if (night) Palette.MECHANICAL_NIGHT else Palette.MECHANICAL_DAY
        CLAY -> if (night) Palette.CLAY_NIGHT else Palette.CLAY_DAY
        PIXEL -> Palette.PIXEL
        LCD -> if (night) Palette.LCD_NIGHT else Palette.LCD_DAY
    }

    companion object {
        const val PREF = "theme"
        fun of(id: String?): KbTheme = values().firstOrNull { it.id == id } ?: GLASS
        fun current(c: Context): KbTheme = of(Prefs.str(c, PREF, GLASS.id))
    }
}

/** Fixed colours of a non-glass theme. Alpha is part of the value where a colour is a line or a wash. */
internal class Palette(
    val bg: Int,              // shell (the case / the page / the screen)
    val key: Int, val keyText: Int,
    val special: Int, val specialText: Int,
    val accent: Int, val onAccent: Int,
    val pressed: Int, val pressedText: Int, val pressedSpecial: Int,
    val line: Int,            // keycap border / rim light
    val specialLine: Int,
    val shellLine: Int,       // outer edge of the shell and of popups
    val rule: Int,            // paper: ruled lines; terminal: phosphor glow
    val stripFill: Int, val stripStroke: Int, val divider: Int, val stripPressed: Int,
    val popupFill: Int,
    val shadow: Int,          // paper: hard card shadow colour
) {
    companion object {
        private fun c(v: Long) = v.toInt()

        val PAPER_DAY = Palette(
            bg = c(0xFFEFE6D2), key = c(0xFFFCF8EC), keyText = c(0xFF2A2622),
            special = c(0xFFE3D8BF), specialText = c(0xFF2A2622),
            accent = c(0xFFB3402D), onAccent = c(0xFFFFF6E8),
            pressed = c(0xFFEFE5CE), pressedText = c(0xFF2A2622), pressedSpecial = c(0xFFFCF8EC),
            line = c(0x442A2622), specialLine = c(0x332A2622), shellLine = c(0x552A2622),
            rule = c(0x2E6C8FB8),
            stripFill = c(0x142A2622), stripStroke = c(0x552A2622), divider = c(0x332A2622), stripPressed = c(0x1F2A2622),
            popupFill = c(0xFFFCF8EC), shadow = c(0xFF5A4630),
        )
        val PAPER_NIGHT = Palette(
            bg = c(0xFF272420), key = c(0xFF383430), keyText = c(0xFFEDE4D0),
            special = c(0xFF2F2C27), specialText = c(0xFFEDE4D0),
            accent = c(0xFFE58B6E), onAccent = c(0xFF2A140D),
            pressed = c(0xFF46413A), pressedText = c(0xFFEDE4D0), pressedSpecial = c(0xFF383430),
            line = c(0x40EDE4D0), specialLine = c(0x30EDE4D0), shellLine = c(0x50EDE4D0),
            rule = c(0x1AA9C4E6),
            stripFill = c(0x14EDE4D0), stripStroke = c(0x50EDE4D0), divider = c(0x33EDE4D0), stripPressed = c(0x26EDE4D0),
            popupFill = c(0xFF383430), shadow = c(0xFF000000),
        )
        /** A terminal is dark whatever the system says. */
        val TERMINAL = Palette(
            bg = c(0xFF040704), key = c(0xFF0B130D), keyText = c(0xFF3DFF7F),
            special = c(0xFF070C08), specialText = c(0xFF2FD068),
            accent = c(0xFF3DFF7F), onAccent = c(0xFF03120A),
            pressed = c(0xFF3DFF7F), pressedText = c(0xFF03120A), pressedSpecial = c(0xFF2FD068),
            line = c(0x803DFF7F), specialLine = c(0x4D3DFF7F), shellLine = c(0xB33DFF7F),
            rule = c(0x143DFF7F),
            stripFill = c(0xFF060B07), stripStroke = c(0x663DFF7F), divider = c(0x663DFF7F), stripPressed = c(0x333DFF7F),
            popupFill = c(0xFF040704), shadow = c(0xFF000000),
        )
        val MECHANICAL_DAY = Palette(
            bg = c(0xFFD3CDBA), key = c(0xFFEEE9D8), keyText = c(0xFF2B2926),
            special = c(0xFFC4BEA9), specialText = c(0xFF2B2926),
            accent = c(0xFFE0662B), onAccent = c(0xFFFFF4EA),
            pressed = c(0xFFE2DCC8), pressedText = c(0xFF2B2926), pressedSpecial = c(0xFFB8B29C),
            line = c(0x80FFFFFF), specialLine = c(0x66FFFFFF), shellLine = c(0x66000000),
            rule = c(0x00000000),
            stripFill = c(0xFFC6CEAC), stripStroke = c(0x66000000), divider = c(0x33000000), stripPressed = c(0x26000000),
            popupFill = c(0xFFEEE9D8), shadow = c(0xFF000000),
        )
        val MECHANICAL_NIGHT = Palette(
            bg = c(0xFF1A1B1E), key = c(0xFF40434B), keyText = c(0xFFE9E7E2),
            special = c(0xFF2D2F35), specialText = c(0xFFE9E7E2),
            accent = c(0xFFFF7A33), onAccent = c(0xFF2A1203),
            pressed = c(0xFF353840), pressedText = c(0xFFE9E7E2), pressedSpecial = c(0xFF262830),
            line = c(0x2EFFFFFF), specialLine = c(0x24FFFFFF), shellLine = c(0x99000000),
            rule = c(0x00000000),
            stripFill = c(0xFF0E1A14), stripStroke = c(0x80000000), divider = c(0x33FFFFFF), stripPressed = c(0x26FFFFFF),
            popupFill = c(0xFF40434B), shadow = c(0xFF000000),
        )

        /** Clay: keys share the shell colour; depth comes from shadow ([shadow] = dark side, [line] = light side). */
        val CLAY_DAY = Palette(
            bg = c(0xFFE2E7F0), key = c(0xFFE2E7F0), keyText = c(0xFF4A5568),
            special = c(0xFFD9DFEB), specialText = c(0xFF4A5568),
            accent = c(0xFF6C7CFF), onAccent = c(0xFFFFFFFF),
            pressed = c(0xFFDCE2EC), pressedText = c(0xFF4A5568), pressedSpecial = c(0xFFD2D9E6),
            line = c(0xFFFFFFFF), specialLine = c(0xFFFFFFFF), shellLine = c(0x33FFFFFF),
            rule = c(0x00000000),
            stripFill = c(0x14000000), stripStroke = c(0x00000000), divider = c(0x22000000), stripPressed = c(0x1A000000),
            popupFill = c(0xFFE9EEF6), shadow = c(0xFFA3B1C6),
        )
        val CLAY_NIGHT = Palette(
            bg = c(0xFF2A2D36), key = c(0xFF2A2D36), keyText = c(0xFFC8CEDC),
            special = c(0xFF262932), specialText = c(0xFFC8CEDC),
            accent = c(0xFF8E9BFF), onAccent = c(0xFF12163A),
            pressed = c(0xFF252830), pressedText = c(0xFFC8CEDC), pressedSpecial = c(0xFF22252D),
            line = c(0x1FFFFFFF), specialLine = c(0x1AFFFFFF), shellLine = c(0x14FFFFFF),
            rule = c(0x00000000),
            stripFill = c(0x22000000), stripStroke = c(0x00000000), divider = c(0x22FFFFFF), stripPressed = c(0x26000000),
            popupFill = c(0xFF30333D), shadow = c(0xFF000000),
        )
        /** A pixel game UI is dark whatever the system says. [rule] is the dither over the shell. */
        val PIXEL = Palette(
            bg = c(0xFF1E1B3A), key = c(0xFF3A3478), keyText = c(0xFFFFF4D6),
            special = c(0xFF2A2655), specialText = c(0xFFB8B0FF),
            accent = c(0xFFFF5D73), onAccent = c(0xFFFFFFFF),
            pressed = c(0xFFFFD23F), pressedText = c(0xFF1E1B3A), pressedSpecial = c(0xFFB8B0FF),
            line = c(0xFFB8B0FF), specialLine = c(0xFF6B63C9), shellLine = c(0xFF6B63C9),
            rule = c(0x14FFFFFF),
            stripFill = c(0xFF161330), stripStroke = c(0xFF6B63C9), divider = c(0x886B63C9), stripPressed = c(0x33FFFFFF),
            popupFill = c(0xFF3A3478), shadow = c(0xFF0E0B22),
        )
        /** Nokia: ink on green LCD. [rule] is the pixel grid, [shadow] the ink used for the ghost. Night = backlit, dimmed. */
        val LCD_DAY = Palette(
            bg = c(0xFFB9C792), key = c(0xFFC9D5A4), keyText = c(0xFF1F2A14),
            special = c(0xFFA9B883), specialText = c(0xFF1F2A14),
            accent = c(0xFF2F3E1D), onAccent = c(0xFFD6E3AE),
            pressed = c(0xFF2F3E1D), pressedText = c(0xFFC9D5A4), pressedSpecial = c(0xFF3C4D26),
            line = c(0xCC1F2A14), specialLine = c(0x991F2A14), shellLine = c(0xCC1F2A14),
            rule = c(0x1E1F2A14),
            stripFill = c(0x1F1F2A14), stripStroke = c(0x661F2A14), divider = c(0x661F2A14), stripPressed = c(0x331F2A14),
            popupFill = c(0xFFC9D5A4), shadow = c(0xFF1F2A14),
        )
        val LCD_NIGHT = Palette(
            bg = c(0xFF34432A), key = c(0xFF3F5133), keyText = c(0xFFDCEFAE),
            special = c(0xFF2C3923), specialText = c(0xFFDCEFAE),
            accent = c(0xFFDCEFAE), onAccent = c(0xFF1D2814),
            pressed = c(0xFFDCEFAE), pressedText = c(0xFF1D2814), pressedSpecial = c(0xFFB9CF8B),
            line = c(0xB3DCEFAE), specialLine = c(0x80DCEFAE), shellLine = c(0x99DCEFAE),
            rule = c(0x18000000),
            stripFill = c(0x22000000), stripStroke = c(0x66DCEFAE), divider = c(0x55DCEFAE), stripPressed = c(0x33DCEFAE),
            popupFill = c(0xFF3F5133), shadow = c(0xFF000000),
        )
    }
}
