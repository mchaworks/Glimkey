package dev.pillboard.keyboard

import android.app.Activity
import android.content.res.ColorStateList
import android.content.res.Configuration
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import androidx.core.content.res.ResourcesCompat
import androidx.core.graphics.ColorUtils
import com.google.android.material.button.MaterialButton
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.android.material.color.MaterialColors
import com.google.android.material.slider.LabelFormatter
import com.google.android.material.slider.Slider
import com.google.android.material.R as MR

/** One place for every size in the settings app, so no page picks its own number. */
internal object Metrics {
    const val BOX_RADIUS = 26        // inset-grouped card (iOS 26 capsule-like corner)
    const val FIELD_RADIUS = 18      // theme cards and other fixed-radius things sitting inside a card
    const val TILE = 32              // leading icon tile
    const val TILE_RADIUS = 11
    const val HERO = 48
    const val ROW_PAD_H = 16
    const val ROW_PAD_V = 12
    const val ROW_MIN = 56
    const val BUTTON_H = 50
    const val SEARCH_H = 54
    const val NAV_H = 56
    const val BACK = 44              // iOS 26 round back button
    const val PAGE_PAD = 16
    const val SWITCH_W = 56
    const val SWITCH_H = 32
    const val SEGMENT_H = 40
}

/**
 * Shared design tokens + builders for every settings page, so pages can't drift apart.
 *
 * Language: iOS 26 on the structure (large title, inset-grouped cards with hairline dividers, capsule controls, glass only
 * on the floating layer: the back button and the search capsule, never on the lists themselves) and Material 3 Expressive
 * on the character (dynamic / three-tone colour roles for tiles and controls, big soft shapes, a switch with a check in its
 * thumb, tonal pills, shape-flowing motion).
 *
 * Every row is one of five kinds, so the same affordance always means the same thing:
 *  - [link]   opens a page            (icon tile, chevron)
 *  - [value]  opens a picker / editor (current value on the right, chevron)
 *  - [toggle] switches something      (switch)
 *  - [action] does something now      (accent coloured title, [destructive] in red)
 *  - [info]   only shows a value
 * plus the stacked controls [slider], [segmentRow], [accent] and [themePicker], which share the same title line.
 */
class SettingsUi(private val a: Activity) {
    // ---- tokens ----
    val night = a.resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK == Configuration.UI_MODE_NIGHT_YES
    val boxRadius = Metrics.BOX_RADIUS.dp.toFloat()
    val rowPad = Metrics.ROW_PAD_H.dp
    val tileSize = Metrics.TILE.dp
    /** Divider start for rows that lead with an icon tile (the divider begins under the text, like iOS Settings). */
    val tileInset get() = rowPad + tileSize + 14.dp
    private fun color(attr: Int, fb: Int) = MaterialColors.getColor(a, attr, fb)

    /** A radius for a shape that sits [pad] inside a card, so the corners stay concentric with the card's. */
    fun concentric(pad: Int) = (boxRadius - pad).coerceAtLeast(8.dp.toFloat())

    /**
     * Tonal triad that colours the whole settings app: the wallpaper's Material You palette, or the accent chosen in
     * Appearance, so the app, the keyboard and its preview always wear the same three tones.
     */
    private val tri: Triad by lazy {
        if (Prefs.dynamic(a)) Triad.fromTheme(a) else Triad.fromSeed(Prefs.seedColor(a), night, Prefs.accentStyle(a))
    }
    val secondaryContainer get() = tri.sc
    val onSecondaryContainer get() = tri.onSc
    val tertiary get() = tri.t

    // with a fixed accent the page and cards are tinted by it (Pixel style) instead of the baseline purple
    val pageBg get() = when {
        Prefs.dynamic(a) -> if (night) ColorUtils.blendARGB(color(MR.attr.colorSurface, 0xFF141218.toInt()), Color.BLACK, 0.45f)
            else color(MR.attr.colorSurfaceContainer, 0xFFF2F2F7.toInt())
        night -> ColorUtils.blendARGB(0xFF0F0F12.toInt(), tri.pc, 0.16f)
        else -> ColorUtils.blendARGB(0xFFF5F5F8.toInt(), tri.pc, 0.32f)
    }
    val surface get() = when {
        Prefs.dynamic(a) -> if (night) color(MR.attr.colorSurfaceContainerHigh, 0xFF2B2B2B.toInt())
            else color(MR.attr.colorSurfaceContainerLowest, Color.WHITE)
        night -> ColorUtils.blendARGB(0xFF1C1C21.toInt(), tri.pc, 0.22f)
        else -> ColorUtils.blendARGB(Color.WHITE, tri.pc, 0.06f)
    }
    val inputBg get() = pageBg
    val onSurface get() = color(MR.attr.colorOnSurface, Color.WHITE)
    val onSurfaceVar get() = color(MR.attr.colorOnSurfaceVariant, Color.LTGRAY)
    val primary get() = tri.p
    val onPrimary get() = tri.onP
    val hairline get() = (onSurface and 0x00FFFFFF) or (0x1A shl 24)
    val error get() = color(MR.attr.colorError, Color.RED)
    private val divider get() = (onSurface and 0x00FFFFFF) or (0x16 shl 24)
    private val switchOff get() = ColorUtils.blendARGB(surface, onSurface, if (night) 0.24f else 0.14f)
    private val segTrack get() = ColorUtils.blendARGB(surface, onSurface, if (night) 0.12f else 0.07f)
    private val segPill get() = if (night) ColorUtils.blendARGB(surface, onSurface, 0.30f) else Color.WHITE
    private val medium: Typeface get() = ResourcesCompat.getFont(a, R.font.gsr_medium) ?: Typeface.create("sans-serif-medium", Typeface.NORMAL)
    private val bold: Typeface get() = ResourcesCompat.getFont(a, R.font.gsr_bold) ?: Typeface.create("sans-serif", Typeface.BOLD)

    /** Tint of the real glass (Liquid-Glass-Android) floating controls: the card colour at ~55-60% strength. */
    val glassVeil get() = ColorUtils.setAlphaComponent(surface, if (night) 0x8C else 0xE8)

    fun lp(bottom: Int = 0) = LinearLayout.LayoutParams(-1, -2).apply { bottomMargin = bottom }

    // ---- structure ----
    /** iOS 26 section label: sentence case, small, grey. */
    fun section(title: String) = TextView(a).apply {
        text = title; textSize = 13.5f; setTextColor(onSurfaceVar); typeface = medium
        setPadding(20.dp, 24.dp, 20.dp, 8.dp)
    }

    /** The small grey explanation under a card. */
    fun note(text: String, top: Int = 8.dp) = TextView(a).apply {
        this.text = text; textSize = 13f; setTextColor(onSurfaceVar); setLineSpacing(0f, 1.1f)
        setPadding(20.dp, top, 20.dp, 0)
    }

    /** Nothing to show (no search hits). */
    fun empty(text: String) = TextView(a).apply {
        this.text = text; textSize = 15f; setTextColor(onSurfaceVar); gravity = Gravity.CENTER
        setPadding(24.dp, 56.dp, 24.dp, 24.dp)
    }

    /** One rounded card, rows inside, hairline dividers that start at [inset]. */
    fun box(rows: List<View>, inset: Int = rowPad): View = LinearLayout(a).apply {
        orientation = LinearLayout.VERTICAL
        background = GradientDrawable().apply { setColor(surface); cornerRadius = boxRadius }
        clipToOutline = true
        rows.forEachIndexed { i, v ->
            if (i > 0) addView(View(a).apply { setBackgroundColor(divider) },
                LinearLayout.LayoutParams(-1, 1.dp).apply { marginStart = inset })
            addView(v, LinearLayout.LayoutParams(-1, -2))
        }
    }

    /** A free-form card (status, test field): padded content in the same rounded surface. */
    fun card(content: View, h: Int = 16.dp, v: Int = 16.dp): View = box(listOf(content.apply { setPadding(h, v, h, v) }))

    /** Filled = accent capsule; otherwise a tinted capsule (accent text on a faint accent wash). */
    fun button(label: String, filled: Boolean, compact: Boolean = false, onClick: () -> Unit) = MaterialButton(a).apply {
        text = label; setTextSize(TypedValue.COMPLEX_UNIT_SP, if (compact) 14f else 16f); isAllCaps = false; typeface = medium
        cornerRadius = 100.dp; insetTop = 0; insetBottom = 0; minHeight = 0; minimumHeight = 0; minWidth = 0; minimumWidth = 0
        stateListAnimator = null; elevation = 0f
        setPadding(if (compact) 16.dp else 20.dp, 0, if (compact) 16.dp else 20.dp, 0)
        backgroundTintList = ColorStateList.valueOf(if (filled) primary else ColorUtils.setAlphaComponent(primary, if (night) 0x38 else 0x24))
        setTextColor(if (filled) onPrimary else primary)
        setOnClickListener { onClick() }
    }

    /** [minW]: a floor for the width, so a label that changes (Active / Not enabled) Blur-Replaces inside a steady pill. */
    fun badge(minW: Int = 0): TextView = BlurTextView(a).apply {
        textSize = 14f; typeface = medium
        setTextColor(primary); gravity = Gravity.CENTER
        if (minW > 0) minWidth = minW
        setPadding(14.dp, 8.dp, 14.dp, 8.dp)
        background = GradientDrawable().apply { cornerRadius = 100.dp.toFloat(); setColor(ColorUtils.setAlphaComponent(primary, if (night) 0x38 else 0x24)) }
    }

    /** Large title (iOS): bold, big. */
    fun title(text: String, size: Float = 34f) = TextView(a).apply { this.text = text; textSize = size; setTextColor(onSurface); typeface = bold }
    fun caption(text: String) = TextView(a).apply { this.text = text; textSize = 15f; setTextColor(onSurfaceVar) }

    // ---- dialogs: same card colour and corner as the rest of the app ----
    fun dialog(title: String): MaterialAlertDialogBuilder = MaterialAlertDialogBuilder(a).setTitle(title)
        .setBackground(GradientDrawable().apply {
            setColor(ColorUtils.blendARGB(surface, onSurface, if (night) 0.05f else 0f)); cornerRadius = 28.dp.toFloat()
        })

    /** Call after show(): buttons wear the accent, the confirming one red when [destructive]. */
    fun tint(d: androidx.appcompat.app.AlertDialog, destructive: Boolean = false): androidx.appcompat.app.AlertDialog {
        d.getButton(androidx.appcompat.app.AlertDialog.BUTTON_POSITIVE)?.setTextColor(if (destructive) error else primary)
        d.getButton(androidx.appcompat.app.AlertDialog.BUTTON_NEGATIVE)?.setTextColor(primary)
        d.getButton(androidx.appcompat.app.AlertDialog.BUTTON_NEUTRAL)?.setTextColor(primary)
        return d
    }

    // ---- rows ----
    private fun texts(title: String, sub: String?, titleColor: Int = onSurface, titleFont: Typeface? = null) = LinearLayout(a).apply {
        orientation = LinearLayout.VERTICAL
        addView(TextView(a).apply { text = title; textSize = 17f; setTextColor(titleColor); titleFont?.let { typeface = it } })
        if (!sub.isNullOrEmpty()) addView(TextView(a).apply { text = sub; textSize = 13f; setTextColor(onSurfaceVar) },
            LinearLayout.LayoutParams(-2, -2).apply { topMargin = 2.dp })
    }

    private fun ripple(v: View) {
        val t = a.obtainStyledAttributes(intArrayOf(android.R.attr.selectableItemBackground))
        v.foreground = t.getDrawable(0); t.recycle()
    }

    private fun row() = LinearLayout(a).apply {
        orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL
        minimumHeight = Metrics.ROW_MIN.dp; setPadding(rowPad, Metrics.ROW_PAD_V.dp, rowPad, Metrics.ROW_PAD_V.dp)
    }

    /** Stacked controls (slider, segmented, pickers) share this frame so their title line sits where a row's does. */
    private fun stack() = LinearLayout(a).apply {
        orientation = LinearLayout.VERTICAL
        setPadding(rowPad, 14.dp, rowPad, 16.dp)
    }

    private fun chevron(res: Int = R.drawable.ic_chevron_right) = ImageView(a).apply {
        setImageResource(res); imageTintList = ColorStateList.valueOf(onSurfaceVar); alpha = 0.55f
    }

    /**
     * Settings icon: a rounded square in one of the accent's three tonal containers (primary / secondary / tertiary,
     * rotated by [tone]) with its matching "on" glyph, so neighbouring rows read apart and still belong to the hero tile.
     */
    private fun iconTile(icon: Int, tone: Int): View = FrameLayout(a).apply {
        val (bg, fg) = when (((tone % 3) + 3) % 3) { 0 -> tri.pc to tri.onPc; 1 -> tri.sc to tri.onSc; else -> tri.tc to tri.onTc }
        background = GradientDrawable().apply { setColor(bg); cornerRadius = Metrics.TILE_RADIUS.dp.toFloat() }
        addView(ImageView(a).apply { setImageResource(icon); imageTintList = ColorStateList.valueOf(fg) },
            FrameLayout.LayoutParams(20.dp, 20.dp, Gravity.CENTER))
    }

    /** Slider parts in the accent: a white capsule-ish thumb with a hairline and a lift, like the iOS 26 slider. */
    private fun tintSlider(s: Slider) {
        s.setThumbTintList(ColorStateList.valueOf(Color.WHITE))
        s.setThumbStrokeColor(ColorStateList.valueOf(ColorUtils.setAlphaComponent(Color.BLACK, 0x26)))
        s.setThumbStrokeWidth(1.dp.toFloat())
        s.setThumbElevation(3.dp.toFloat())
        s.setTrackActiveTintList(ColorStateList.valueOf(primary))
        s.setTrackInactiveTintList(ColorStateList.valueOf(ColorUtils.blendARGB(surface, primary, if (night) 0.30f else 0.22f)))
        s.setHaloTintList(ColorStateList.valueOf(ColorUtils.setAlphaComponent(primary, 0x30)))
    }

    /** Title over a gliding segmented control: for a few options where seeing all of them beats tapping through. */
    fun segmentRow(title: String, key: String, def: String, options: List<Pair<String, String>>, onChange: () -> Unit): View =
        stack().apply {
            addView(TextView(a).apply { text = title; textSize = 17f; setTextColor(onSurface) })
            addView(Segmented(a, options.map { it.second }, segTrack, segPill, onSurface, onSurfaceVar, medium).apply {
                select(options.indexOfFirst { it.first == Prefs.str(a, key, def) }, false)
                onSelect = { i -> Prefs.setStr(a, key, options[i].first); onChange() }
            }, LinearLayout.LayoutParams(-1, -2).apply { topMargin = 12.dp })
        }

    /** Big accent tile for the home status card (Material 3 Expressive: large soft shape, dynamic colour). */
    fun heroTile(icon: Int): View = FrameLayout(a).apply {
        background = GradientDrawable(GradientDrawable.Orientation.TOP_BOTTOM,
            intArrayOf(ColorUtils.blendARGB(primary, Color.WHITE, 0.18f), primary)).apply { cornerRadius = 16.dp.toFloat() }
        addView(ImageView(a).apply { setImageResource(icon); imageTintList = ColorStateList.valueOf(onPrimary) },
            FrameLayout.LayoutParams(26.dp, 26.dp, Gravity.CENTER))
    }

    fun toggle(title: String, sub: String, key: String, def: Boolean, enabled: Boolean = true, onChange: ((Boolean) -> Unit)? = null): View {
        val r = row()
        r.addView(texts(title, sub), LinearLayout.LayoutParams(0, -2, 1f).apply { marginEnd = 16.dp })
        val sw = PillSwitch(a, primary, switchOff, Color.WHITE, onPrimary).apply {
            setChecked(enabled && Prefs.bool(a, key, def), false); isEnabled = enabled
            contentDescription = title
            onToggle = { v -> Prefs.setBool(a, key, v); onChange?.invoke(v) }
        }
        r.addView(sw, LinearLayout.LayoutParams(Metrics.SWITCH_W.dp, Metrics.SWITCH_H.dp))
        if (enabled) { r.setOnClickListener { sw.toggle() }; ripple(r) } else r.alpha = 0.5f
        return r
    }

    /**
     * A choice between [options] (value to label). A few short options show as a segmented control, so every option is in
     * sight and one tap picks it; more than that opens a list, instead of stepping through the options on every tap.
     */
    fun cycle(title: String, key: String, def: String, options: List<Pair<String, String>>, onChange: () -> Unit): View =
        if (options.size <= 3 && options.all { it.second.length <= 14 }) segmentRow(title, key, def, options, onChange)
        else choice(title, key, def, options, onChange)

    /** Title, the current choice on the right, and a list dialog. The label Blur-Replaces when it changes. */
    fun choice(title: String, key: String, def: String, options: List<Pair<String, String>>, onChange: () -> Unit): View = row().apply {
        fun current() = options.firstOrNull { it.first == Prefs.str(a, key, def) } ?: options.first()
        val valueView = BlurTextView(a).apply {
            textSize = 16f; setTextColor(onSurfaceVar); gravity = Gravity.END or Gravity.CENTER_VERTICAL
            text = current().second
        }
        addView(TextView(a).apply { text = title; textSize = 17f; setTextColor(onSurface) }, LinearLayout.LayoutParams(-2, -2))
        addView(valueView, LinearLayout.LayoutParams(0, -2, 1f).apply { marginStart = 12.dp; marginEnd = 6.dp })
        addView(chevron(R.drawable.ic_unfold), LinearLayout.LayoutParams(20.dp, 20.dp))
        setOnClickListener {
            val idx = options.indexOfFirst { it.first == current().first }
            tint(dialog(title).setSingleChoiceItems(options.map { it.second }.toTypedArray(), idx) { d, i ->
                Prefs.setStr(a, key, options[i].first); d.dismiss(); valueView.setTextBlur(options[i].second); onChange()
            }.setNegativeButton("Cancel", null).show())
        }
        ripple(this)
    }

    /** Opens a page: leading tile (optional), title, sub, chevron. */
    fun link(title: String, sub: String?, icon: Int = 0, tone: Int = 0, onClick: () -> Unit): View = row().apply {
        if (icon != 0) addView(iconTile(icon, tone), LinearLayout.LayoutParams(tileSize, tileSize).apply { marginEnd = 14.dp })
        addView(texts(title, sub), LinearLayout.LayoutParams(0, -2, 1f).apply { marginEnd = 8.dp })
        addView(chevron(), LinearLayout.LayoutParams(20.dp, 20.dp))
        setOnClickListener { onClick() }; ripple(this)
    }

    /** Opens a picker or editor: title, the current value on the right (grey), chevron. */
    fun value(title: String, value: String, sub: String? = null, onClick: () -> Unit): View = row().apply {
        addView(texts(title, sub), LinearLayout.LayoutParams(-2, -2))
        addView(TextView(a).apply {
            text = value; textSize = 16f; setTextColor(onSurfaceVar); gravity = Gravity.END or Gravity.CENTER_VERTICAL
            maxLines = 1; ellipsize = android.text.TextUtils.TruncateAt.END
        }, LinearLayout.LayoutParams(0, -2, 1f).apply { marginStart = 12.dp; marginEnd = 6.dp })
        addView(chevron(), LinearLayout.LayoutParams(20.dp, 20.dp))
        setOnClickListener { onClick() }; ripple(this)
    }

    /** Does something right now: the title wears the accent (red when [destructive]); no chevron, no value. */
    fun action(title: String, sub: String? = null, destructive: Boolean = false, onClick: () -> Unit): View = row().apply {
        addView(texts(title, sub, if (destructive) error else primary, medium), LinearLayout.LayoutParams(0, -2, 1f))
        setOnClickListener { onClick() }; ripple(this)
    }

    /** Title and value. The value (tagged "value") is a Blur Replace label, so a status that changes can flow to its new text. */
    fun info(title: String, value: String): View = row().apply {
        addView(TextView(a).apply { text = title; textSize = 17f; setTextColor(onSurface) }, LinearLayout.LayoutParams(-2, -2))
        addView(BlurTextView(a).apply {
            text = value; textSize = 16f; setTextColor(onSurfaceVar)
            gravity = Gravity.END or Gravity.CENTER_VERTICAL; tag = "value"
        }, LinearLayout.LayoutParams(0, -2, 1f).apply { marginStart = 12.dp })
    }

    /** Slider with a live readout, a gliding segmented control for the presets and a small reset shown only when changed. */
    fun slider(title: String, from: Int, to: Int, key: String, def: Int, presets: List<Pair<String, Int>>, unit: String = " dp", onChange: () -> Unit): View =
        stack().apply {
            // the readout Blur-Replaces as the value changes; a drag faster than RAPID_GAP_MS just snaps (same rule as typing)
            val readout = BlurTextView(a).apply {
                textSize = 16f; setTextColor(primary); typeface = medium
                gravity = Gravity.END or Gravity.CENTER_VERTICAL; minWidth = 64.dp
            }
            val s = Slider(a).apply {
                valueFrom = from.toFloat(); valueTo = to.toFloat(); stepSize = 1f
                isTickVisible = false; labelBehavior = LabelFormatter.LABEL_GONE
                trackHeight = 12.dp; thumbRadius = 14.dp; haloRadius = 22.dp
                value = Prefs.int(a, key, def).toFloat().coerceIn(valueFrom, valueTo)
                tintSlider(this)
            }
            val seg = Segmented(a, presets.map { it.first }, segTrack, segPill, onSurface, onSurfaceVar, medium).apply {
                onSelect = { i -> s.value = presets[i].second.toFloat() }
            }
            val reset = TextView(a).apply {
                text = "Reset"; textSize = 14f; setTextColor(primary); typeface = medium
                setPadding(12.dp, 6.dp, 0, 6.dp)
                setOnClickListener { s.value = def.toFloat() }
            }
            fun show(v: Int, animate: Boolean) {
                val txt = "$v$unit"
                if (animate) readout.setTextBlur(txt, minGapMs = BlurTextTokens.RAPID_GAP_MS) else readout.text = txt
                reset.visibility = if (v == def) View.GONE else View.VISIBLE
                seg.select(presets.indexOfFirst { it.second == v }, animate)
            }
            show(s.value.toInt(), false)
            s.addOnChangeListener { _, v, _ -> Prefs.setInt(a, key, v.toInt()); show(v.toInt(), true); onChange() }
            addView(LinearLayout(a).apply {
                gravity = Gravity.CENTER_VERTICAL
                addView(TextView(a).apply { text = title; textSize = 17f; setTextColor(onSurface) }, LinearLayout.LayoutParams(0, -2, 1f))
                addView(readout); addView(reset)
            })
            addView(s, LinearLayout.LayoutParams(-1, -2).apply { topMargin = 6.dp; bottomMargin = 8.dp })
            addView(seg, LinearLayout.LayoutParams(-1, -2))
        }

    /**
     * Accent picker: a grid of three-tone chips (primary / secondary / tertiary, like Pixel's picker), the wallpaper's own
     * Material You palette first, then the presets and a custom hue. A palette style decides how far the other two tones
     * drift from the accent. The settings app wears the choice too, so a change recreates the page ([onChange] only
     * refreshes the preview while a hue is being dragged).
     */
    fun accent(onChange: () -> Unit): View {
        val root = stack()
        root.addView(TextView(a).apply { text = "Accent color"; textSize = 17f; setTextColor(onSurface) })
        val caption = TextView(a).apply { textSize = 13f; setTextColor(onSurfaceVar) }
        root.addView(caption, LinearLayout.LayoutParams(-2, -2).apply { topMargin = 2.dp })
        val grid = LinearLayout(a).apply { orientation = LinearLayout.VERTICAL; setPadding(0, 14.dp, 0, 0) }
        root.addView(grid)

        val dark = KbMode.night(a)
        val isDyn = Prefs.dynamic(a)
        val threeTone = Prefs.bool(a, "tritone", true)
        val style = Prefs.accentStyle(a)

        class E(val label: String, val triad: Triad, val selected: Boolean, val pick: () -> Unit)
        fun choose(i: Int) { Prefs.setPreset(a, i); Prefs.setBool(a, "dynamic", false); a.recreate() }
        fun entries(): List<E> {
            val list = ArrayList<E>()
            if (android.os.Build.VERSION.SDK_INT >= 31) {
                val sys = Triad.fromTheme(android.view.ContextThemeWrapper(a, R.style.Theme_Pillboard))
                list.add(E("Wallpaper", sys, isDyn) { Prefs.setBool(a, "dynamic", true); a.recreate() })
            }
            Prefs.PRESETS.forEachIndexed { i, p ->
                list.add(E(p.first, Triad.fromSeed(p.second, dark, style), !isDyn && Prefs.preset(a) == i) { choose(i) })
            }
            list.add(E("Custom", Triad.fromSeed(Prefs.customColor(a), dark, style), !isDyn && Prefs.preset(a) == Prefs.CUSTOM) { choose(Prefs.CUSTOM) })
            return list
        }
        fun chip(e: E): View = FrameLayout(a).apply {
            contentDescription = e.label
            addView(View(a).apply {
                background = if (threeTone) TriToneDrawable(e.triad.p, e.triad.s, e.triad.t, if (e.selected) onSurface else 0, 3.dp.toFloat())
                else GradientDrawable().apply {
                    shape = GradientDrawable.OVAL; setColor(e.triad.p)
                    if (e.selected) setStroke(3.dp, onSurface)
                }
            }, FrameLayout.LayoutParams(44.dp, 44.dp, Gravity.CENTER))
            setOnClickListener { e.pick() }
        }
        fun refresh() {
            val list = entries()
            grid.removeAllViews()
            list.chunked(5).forEach { rowItems ->
                val line = LinearLayout(a)
                rowItems.forEach { line.addView(chip(it), LinearLayout.LayoutParams(0, 52.dp, 1f)) }
                repeat(5 - rowItems.size) { line.addView(View(a), LinearLayout.LayoutParams(0, 1, 1f)) }
                grid.addView(line, LinearLayout.LayoutParams(-1, -2))
            }
            val sel = list.firstOrNull { it.selected }
            caption.text = when {
                sel == null -> ""
                sel.label == "Wallpaper" -> "Wallpaper · Material You"
                else -> "${sel.label} · ${style.label}"
            }
        }
        refresh()

        if (!isDyn) {
            root.addView(TextView(a).apply { text = "Palette style"; textSize = 13f; setTextColor(onSurfaceVar) },
                LinearLayout.LayoutParams(-2, -2).apply { topMargin = 16.dp; bottomMargin = 8.dp })
            val styles = AccentStyle.values().toList()
            root.addView(Segmented(a, styles.map { it.label }, segTrack, segPill, onSurface, onSurfaceVar, medium).apply {
                select(styles.indexOf(style), false)
                onSelect = { i -> Prefs.setStr(a, "accent_style", styles[i].id); a.recreate() }
            }, LinearLayout.LayoutParams(-1, -2))
            if (Prefs.preset(a) == Prefs.CUSTOM) {
                root.addView(TextView(a).apply { text = "Hue"; textSize = 13f; setTextColor(onSurfaceVar) },
                    LinearLayout.LayoutParams(-2, -2).apply { topMargin = 16.dp })
                val hue = Slider(a).apply {
                    valueFrom = 0f; valueTo = 359f; stepSize = 1f; isTickVisible = false
                    labelBehavior = LabelFormatter.LABEL_GONE; trackHeight = 12.dp; thumbRadius = 14.dp; haloRadius = 22.dp
                    value = Prefs.hue(a).toFloat()
                    tintSlider(this)
                    addOnChangeListener { _, v, _ -> Prefs.setInt(a, "hue", v.toInt()); refresh(); onChange() }
                    addOnSliderTouchListener(object : Slider.OnSliderTouchListener {
                        override fun onStartTrackingTouch(slider: Slider) {}
                        override fun onStopTrackingTouch(slider: Slider) { a.recreate() }
                    })
                }
                root.addView(hue, LinearLayout.LayoutParams(-1, -2))
            }
        }
        return root
    }

    /** Theme picker: a 2x2 grid of live miniatures (real shell + keycap skin of each theme); tapping one applies it. */
    fun themePicker(onChange: () -> Unit): View = stack().apply {
        addView(TextView(a).apply { text = "Theme"; textSize = 17f; setTextColor(onSurface) })
        val cards = ArrayList<Pair<KbTheme, View>>()
        fun refresh() {
            val cur = KbTheme.current(a)
            cards.forEach { (th, v) ->
                v.background = GradientDrawable().apply {
                    cornerRadius = Metrics.FIELD_RADIUS.dp.toFloat()
                    setColor(segTrack)
                    if (th == cur) setStroke(3.dp, primary)
                }
            }
        }
        val grid = LinearLayout(a).apply { orientation = LinearLayout.VERTICAL; setPadding(0, 14.dp, 0, 0) }
        KbTheme.values().toList().chunked(2).forEach { pair ->
            val line = LinearLayout(a)
            pair.forEachIndexed { i, th ->
                val card = LinearLayout(a).apply {
                    orientation = LinearLayout.VERTICAL
                    setPadding(8.dp, 8.dp, 8.dp, 12.dp)
                    contentDescription = th.label
                    addView(ThemeSwatch(a, th), LinearLayout.LayoutParams(-1, 76.dp))
                    addView(TextView(a).apply { text = th.label; textSize = 15f; setTextColor(onSurface); typeface = medium },
                        LinearLayout.LayoutParams(-2, -2).apply { topMargin = 8.dp; marginStart = 4.dp })
                    addView(TextView(a).apply { text = th.blurb; textSize = 12f; setTextColor(onSurfaceVar) },
                        LinearLayout.LayoutParams(-2, -2).apply { marginStart = 4.dp })
                    setOnClickListener {
                        if (KbTheme.current(a) != th) { Prefs.setStr(a, KbTheme.PREF, th.id); refresh(); onChange() }
                    }
                }
                cards.add(th to card)
                line.addView(card, LinearLayout.LayoutParams(0, -2, 1f).apply { if (i == 0) marginEnd = 10.dp })
            }
            grid.addView(line, LinearLayout.LayoutParams(-1, -2).apply { bottomMargin = 10.dp })
        }
        refresh(); addView(grid)
    }

    /** iOS 26 search: a floating glass capsule (magnifier + field) meant to sit at the bottom of the screen. */
    fun searchPill(hint: String, src: () -> View? = { null }): Pair<View, EditText> {
        val input = EditText(a).apply {
            this.hint = hint; textSize = 17f; maxLines = 1; isSingleLine = true
            setTextColor(onSurface); setHintTextColor(onSurfaceVar); background = null; setPadding(0, 0, 0, 0)
            imeOptions = android.view.inputmethod.EditorInfo.IME_ACTION_SEARCH
        }
        val row = LinearLayout(a).apply {
            gravity = Gravity.CENTER_VERTICAL; setPadding(20.dp, 0, 20.dp, 0)
            addView(ImageView(a).apply { setImageResource(R.drawable.ic_search); imageTintList = ColorStateList.valueOf(onSurfaceVar) },
                LinearLayout.LayoutParams(22.dp, 22.dp).apply { marginEnd = 10.dp })
            addView(input, LinearLayout.LayoutParams(0, -1, 1f))
        }
        val bar = GlassBar(a, src, glassVeil).apply { addView(row, FrameLayout.LayoutParams(-1, -1)) }
        return bar to input
    }

    /** The "Try it" field: a recessed well whose corners are concentric with its card. */
    fun testField(): Pair<View, EditText> {
        val pad = 12.dp
        val input = EditText(a).apply {
            hint = "Type here to test…"; textSize = 17f
            setTextColor(onSurface); setHintTextColor(onSurfaceVar)
            minLines = 3; gravity = Gravity.TOP or Gravity.START
            setPadding(16.dp, 14.dp, 16.dp, 14.dp)
            background = GradientDrawable().apply { cornerRadius = concentric(pad); setColor(inputBg) }
        }
        val wrap = LinearLayout(a).apply { setPadding(pad, pad, pad, pad); addView(input, LinearLayout.LayoutParams(-1, -2)) }
        return wrap to input
    }
}

/** A round chip split in three like Pixel's accent picker: [a] the left half, [b] top right, [c] bottom right; [ring] outlines the selected one. */
internal class TriToneDrawable(private val a: Int, private val b: Int, private val c: Int, private val ring: Int, private val ringW: Float) :
    android.graphics.drawable.Drawable() {
    private val paint = android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG)
    private val box = android.graphics.RectF()

    override fun draw(canvas: android.graphics.Canvas) {
        box.set(bounds)
        if (ring != 0) box.inset(ringW + 2f * ringW / 3f, ringW + 2f * ringW / 3f)
        paint.style = android.graphics.Paint.Style.FILL
        paint.color = a; canvas.drawArc(box, 90f, 180f, true, paint)
        paint.color = b; canvas.drawArc(box, 270f, 90f, true, paint)
        paint.color = c; canvas.drawArc(box, 0f, 90f, true, paint)
        if (ring != 0) {
            box.set(bounds); box.inset(ringW / 2f, ringW / 2f)
            paint.style = android.graphics.Paint.Style.STROKE; paint.strokeWidth = ringW; paint.color = ring
            canvas.drawOval(box, paint)
        }
    }
    override fun setAlpha(alpha: Int) { paint.alpha = alpha }
    override fun setColorFilter(cf: android.graphics.ColorFilter?) { paint.colorFilter = cf }
    @Suppress("OVERRIDE_DEPRECATION")
    override fun getOpacity() = android.graphics.PixelFormat.TRANSLUCENT
}
