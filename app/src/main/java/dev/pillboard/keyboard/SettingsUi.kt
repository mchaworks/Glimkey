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
import com.google.android.material.color.MaterialColors
import com.google.android.material.slider.LabelFormatter
import com.google.android.material.slider.Slider
import com.google.android.material.R as MR

/**
 * Shared design tokens + builders for every settings page, so pages can't drift apart.
 * Look: iOS inset-grouped lists (grey page, white/dark cards, colored icon tiles, large titles, uppercase section
 * labels) with Material 3 Expressive touches (dynamic color, big shapes, a switch whose thumb stretches, a gliding
 * segmented pill). Motion uses the keyboard's smooth ease: no springs.
 */
class SettingsUi(private val a: Activity) {
    // ---- tokens ----
    val night = a.resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK == Configuration.UI_MODE_NIGHT_YES
    val boxRadius = 28.dp.toFloat()
    val rowPad = 16.dp
    val tileSize = 30.dp
    /** Divider start for rows that lead with an icon tile (the divider begins under the text, like iOS Settings). */
    val tileInset get() = rowPad + tileSize + 14.dp
    private fun color(attr: Int, fb: Int) = MaterialColors.getColor(a, attr, fb)

    val pageBg get() = if (night) ColorUtils.blendARGB(color(MR.attr.colorSurface, 0xFF141218.toInt()), Color.BLACK, 0.45f)
        else color(MR.attr.colorSurfaceContainer, 0xFFF2F2F7.toInt())
    val surface get() = if (night) color(MR.attr.colorSurfaceContainerHigh, 0xFF2B2B2B.toInt())
        else color(MR.attr.colorSurfaceContainerLowest, Color.WHITE)
    val inputBg get() = pageBg
    val onSurface get() = color(MR.attr.colorOnSurface, Color.WHITE)
    val onSurfaceVar get() = color(MR.attr.colorOnSurfaceVariant, Color.LTGRAY)
    val primary get() = color(MR.attr.colorPrimary, Color.WHITE)
    val onPrimary get() = color(MR.attr.colorOnPrimary, Color.BLACK)
    val hairline get() = (onSurface and 0x00FFFFFF) or (0x1A shl 24)
    val error get() = color(MR.attr.colorError, Color.RED)
    private val switchOff get() = ColorUtils.blendARGB(surface, onSurface, if (night) 0.24f else 0.14f)
    private val segTrack get() = ColorUtils.blendARGB(surface, onSurface, if (night) 0.12f else 0.07f)
    private val segPill get() = if (night) ColorUtils.blendARGB(surface, onSurface, 0.30f) else Color.WHITE
    private val medium: Typeface get() = ResourcesCompat.getFont(a, R.font.gsr_medium) ?: Typeface.create("sans-serif-medium", Typeface.NORMAL)
    private val bold: Typeface get() = ResourcesCompat.getFont(a, R.font.gsr_bold) ?: Typeface.create("sans-serif", Typeface.BOLD)

    fun lp(bottom: Int = 0) = LinearLayout.LayoutParams(-1, -2).apply { bottomMargin = bottom }

    // ---- structure ----
    /** iOS 26 section label: sentence case, small, grey (iOS 18 shouted in capitals). */
    fun section(title: String) = TextView(a).apply {
        text = title; textSize = 13.5f; setTextColor(onSurfaceVar); typeface = medium
        setPadding(20.dp, 24.dp, 0, 8.dp)
    }

    /** Tint of the real glass (Liquid-Glass-Android) floating controls: the card colour at ~55-60% strength. */
    val glassVeil get() = ColorUtils.setAlphaComponent(surface, if (night) 0x8C else 0x99)

    /** Floating "Liquid Glass" surface: the card colour, mostly opaque, with a hairline edge. Give the view an elevation for the lift. */
    fun glassBg(radius: Float) = GradientDrawable().apply {
        setColor(ColorUtils.setAlphaComponent(surface, if (night) 0xE6 else 0xEC)); cornerRadius = radius; setStroke(1.dp, hairline)
    }

    /** One rounded card, rows inside, hairline dividers that start at [inset]. */
    fun box(rows: List<View>, inset: Int = rowPad): View = LinearLayout(a).apply {
        orientation = LinearLayout.VERTICAL
        background = GradientDrawable().apply { setColor(surface); cornerRadius = boxRadius }
        clipToOutline = true
        val divider = (onSurface and 0x00FFFFFF) or (0x16 shl 24)
        rows.forEachIndexed { i, v ->
            if (i > 0) addView(View(a).apply { setBackgroundColor(divider) },
                LinearLayout.LayoutParams(-1, 1.dp).apply { marginStart = inset })
            addView(v, LinearLayout.LayoutParams(-1, -2))
        }
    }

    /** A free-form card (status, test field): padded content in the same rounded surface. */
    fun card(content: View, h: Int = 16.dp, v: Int = 14.dp): View = box(listOf(content.apply { setPadding(h, v, h, v) }))

    /** Filled = accent pill; otherwise a tinted pill (accent text on a faint accent wash), like iOS. */
    fun button(label: String, filled: Boolean, compact: Boolean = false, onClick: () -> Unit) = MaterialButton(a).apply {
        text = label; setTextSize(TypedValue.COMPLEX_UNIT_SP, if (compact) 14f else 15f); isAllCaps = false; typeface = medium
        cornerRadius = 100.dp; insetTop = 0; insetBottom = 0; minHeight = 0; minimumHeight = 0; minWidth = 0; minimumWidth = 0
        stateListAnimator = null; elevation = 0f
        setPadding(if (compact) 16.dp else 18.dp, 0, if (compact) 16.dp else 18.dp, 0)
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

    // ---- rows ----
    private fun texts(title: String, sub: String?) = LinearLayout(a).apply {
        orientation = LinearLayout.VERTICAL
        addView(TextView(a).apply { text = title; textSize = 17f; setTextColor(onSurface) })
        if (!sub.isNullOrEmpty()) addView(TextView(a).apply { text = sub; textSize = 13f; setTextColor(onSurfaceVar) },
            LinearLayout.LayoutParams(-2, -2).apply { topMargin = 1.dp })
    }

    private fun ripple(v: View) {
        val t = a.obtainStyledAttributes(intArrayOf(android.R.attr.selectableItemBackground))
        v.foreground = t.getDrawable(0); t.recycle()
    }

    private fun row() = LinearLayout(a).apply {
        orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL
        minimumHeight = 56.dp; setPadding(rowPad, 11.dp, rowPad, 11.dp)
    }

    /** iOS 26 settings icon: one neutral grey rounded square with a white glyph (the per-page colour [bg] is no longer used). */
    private val tileGrey get() = if (night) 0xFF6B6B70.toInt() else 0xFF8E8E93.toInt()
    @Suppress("UNUSED_PARAMETER")
    private fun iconTile(icon: Int, bg: Int): View = FrameLayout(a).apply {
        background = tileBg(tileGrey, 9.dp)
        addView(ImageView(a).apply { setImageResource(icon); imageTintList = ColorStateList.valueOf(Color.WHITE) },
            FrameLayout.LayoutParams(19.dp, 19.dp, Gravity.CENTER))
    }

    /** iOS tile body: the colour with a soft light-to-base vertical gradient. */
    private fun tileBg(c: Int, r: Int) = GradientDrawable(GradientDrawable.Orientation.TOP_BOTTOM,
        intArrayOf(ColorUtils.blendARGB(c, Color.WHITE, 0.2f), c)).apply { cornerRadius = r.toFloat() }

    /** Big accent tile for the home status card (Material 3 Expressive: large soft shape, dynamic colour). */
    fun heroTile(icon: Int): View = FrameLayout(a).apply {
        background = tileBg(primary, 16.dp)
        addView(ImageView(a).apply { setImageResource(icon); imageTintList = ColorStateList.valueOf(onPrimary) },
            FrameLayout.LayoutParams(26.dp, 26.dp, Gravity.CENTER))
    }

    fun toggle(title: String, sub: String, key: String, def: Boolean, enabled: Boolean = true, onChange: ((Boolean) -> Unit)? = null): View {
        val r = row()
        r.addView(texts(title, sub), LinearLayout.LayoutParams(0, -2, 1f).apply { marginEnd = 14.dp })
        val sw = PillSwitch(a, primary, switchOff, Color.WHITE, onPrimary).apply {
            setChecked(enabled && Prefs.bool(a, key, def), false); isEnabled = enabled
            contentDescription = title
            onToggle = { v -> Prefs.setBool(a, key, v); onChange?.invoke(v) }
        }
        r.addView(sw, LinearLayout.LayoutParams(52.dp, 32.dp))
        if (enabled) { r.setOnClickListener { sw.toggle() }; ripple(r) } else r.alpha = 0.5f
        return r
    }

    /** Navigation / action row. [chevron] false for rows that open a dialog instead of a page. [icon]/[tile] add the colored tile. */
    /** A row that steps through [options] (value to label) on every tap and shows the current label under the title. */
    fun cycle(title: String, key: String, def: String, options: List<Pair<String, String>>, onChange: () -> Unit): View = row().apply {
        val t = texts(title, null)
        // the current value is a Blur Replace label; full width so the old text keeps its room while it leaves
        val sub = BlurTextView(a).apply { textSize = 13f; setTextColor(onSurfaceVar) }
        t.addView(sub, LinearLayout.LayoutParams(-1, -2).apply { topMargin = 1.dp })
        fun show(animate: Boolean) {
            val label = options.firstOrNull { it.first == Prefs.str(a, key, def) }?.second ?: options.first().second
            if (animate) sub.setTextBlur(label) else sub.text = label
        }
        show(false)
        addView(t, LinearLayout.LayoutParams(0, -2, 1f))
        addView(ImageView(a).apply {
            setImageResource(R.drawable.ic_swap); imageTintList = ColorStateList.valueOf(onSurfaceVar); alpha = 0.55f
        }, LinearLayout.LayoutParams(20.dp, 20.dp))
        setOnClickListener {
            val i = options.indexOfFirst { it.first == Prefs.str(a, key, def) }
            Prefs.setStr(a, key, options[(i + 1) % options.size].first)
            show(true); onChange()
        }
        ripple(this)
    }

    fun link(title: String, sub: String?, chevron: Boolean = true, icon: Int = 0, tile: Int = 0, onClick: () -> Unit): View = row().apply {
        if (icon != 0) addView(iconTile(icon, tile), LinearLayout.LayoutParams(tileSize, tileSize).apply { marginEnd = 14.dp })
        addView(texts(title, sub), LinearLayout.LayoutParams(0, -2, 1f))
        if (chevron) addView(ImageView(a).apply {
            setImageResource(R.drawable.ic_chevron_right); imageTintList = ColorStateList.valueOf(onSurfaceVar); alpha = 0.55f
        }, LinearLayout.LayoutParams(20.dp, 20.dp))
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
        LinearLayout(a).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(rowPad, 14.dp, rowPad, 14.dp)
            // the readout Blur-Replaces as the value changes; a drag faster than RAPID_GAP_MS just snaps (same rule as typing)
            val readout = BlurTextView(a).apply {
                textSize = 15f; setTextColor(primary); typeface = medium
                gravity = Gravity.END or Gravity.CENTER_VERTICAL; minWidth = 64.dp
            }
            val s = Slider(a).apply {
                valueFrom = from.toFloat(); valueTo = to.toFloat(); stepSize = 1f
                isTickVisible = false; labelBehavior = LabelFormatter.LABEL_GONE
                trackHeight = 10.dp; thumbRadius = 13.dp; haloRadius = 22.dp
                value = Prefs.int(a, key, def).toFloat().coerceIn(valueFrom, valueTo)
            }
            val seg = Segmented(a, presets.map { it.first }, segTrack, segPill, onSurface, onSurfaceVar, medium).apply {
                onSelect = { i -> s.value = presets[i].second.toFloat() }
            }
            val reset = TextView(a).apply {
                text = "Reset"; textSize = 14f; setTextColor(primary); setPadding(14.dp, 4.dp, 0, 4.dp)
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
            addView(s, LinearLayout.LayoutParams(-1, -2).apply { topMargin = 4.dp; bottomMargin = 6.dp })
            addView(seg, LinearLayout.LayoutParams(-1, -2))
        }

    fun accent(onChange: () -> Unit): View = LinearLayout(a).apply {
        orientation = LinearLayout.VERTICAL
        setPadding(rowPad, 14.dp, rowPad, 16.dp)
        addView(TextView(a).apply { text = "Accent color"; textSize = 17f; setTextColor(onSurface) })
        val swatches = LinearLayout(a).apply { setPadding(0, 14.dp, 0, 0) }
        val custom = Prefs.PRESETS.size
        val hue = Slider(a).apply {
            valueFrom = 0f; valueTo = 359f; stepSize = 1f; isTickVisible = false
            labelBehavior = LabelFormatter.LABEL_GONE; trackHeight = 10.dp; thumbRadius = 13.dp; haloRadius = 22.dp
            value = Prefs.hue(a).toFloat()
        }
        fun refresh() {
            for (i in 0..custom) swatches.getChildAt(i).background = GradientDrawable().apply {
                shape = GradientDrawable.OVAL
                setColor(if (i < custom) Prefs.PRESETS[i].second else Prefs.customColor(a))
                if (i == Prefs.preset(a)) setStroke(3.dp, onSurface)
            }
            hue.visibility = if (Prefs.preset(a) == custom) View.VISIBLE else View.GONE
        }
        for (i in 0..custom) swatches.addView(View(a).apply {
            contentDescription = if (i < custom) Prefs.PRESETS[i].first else "Custom"
            setOnClickListener { Prefs.setInt(a, "preset", i); refresh(); onChange() }
        }, LinearLayout.LayoutParams(36.dp, 36.dp).apply { marginEnd = 10.dp })
        hue.addOnChangeListener { _, v, _ -> Prefs.setInt(a, "hue", v.toInt()); refresh(); onChange() }
        refresh(); addView(swatches); addView(hue, lp())
    }

    /** Theme picker: a 2x2 grid of live miniatures (real shell + keycap skin of each theme); tapping one applies it. */
    fun themePicker(onChange: () -> Unit): View = LinearLayout(a).apply {
        orientation = LinearLayout.VERTICAL
        setPadding(rowPad, 14.dp, rowPad, 16.dp)
        addView(TextView(a).apply { text = "Theme"; textSize = 17f; setTextColor(onSurface) })
        val cards = ArrayList<Pair<KbTheme, View>>()
        fun refresh() {
            val cur = KbTheme.current(a)
            cards.forEach { (th, v) ->
                v.background = GradientDrawable().apply {
                    cornerRadius = 18.dp.toFloat()
                    setColor(segTrack)
                    if (th == cur) setStroke(3.dp, primary)
                }
            }
        }
        val grid = LinearLayout(a).apply { orientation = LinearLayout.VERTICAL; setPadding(0, 12.dp, 0, 0) }
        KbTheme.values().toList().chunked(2).forEach { pair ->
            val line = LinearLayout(a)
            pair.forEachIndexed { i, th ->
                val card = LinearLayout(a).apply {
                    orientation = LinearLayout.VERTICAL
                    setPadding(8.dp, 8.dp, 8.dp, 10.dp)
                    contentDescription = th.label
                    addView(ThemeSwatch(a, th), LinearLayout.LayoutParams(-1, 76.dp))
                    addView(TextView(a).apply { text = th.label; textSize = 14.5f; setTextColor(onSurface); typeface = medium },
                        LinearLayout.LayoutParams(-2, -2).apply { topMargin = 8.dp })
                    addView(TextView(a).apply { text = th.blurb; textSize = 11.5f; setTextColor(onSurfaceVar) })
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
            gravity = Gravity.CENTER_VERTICAL; setPadding(18.dp, 0, 18.dp, 0)
            addView(ImageView(a).apply { setImageResource(R.drawable.ic_search); imageTintList = ColorStateList.valueOf(onSurfaceVar) },
                LinearLayout.LayoutParams(20.dp, 20.dp).apply { marginEnd = 10.dp })
            addView(input, LinearLayout.LayoutParams(0, -1, 1f))
        }
        val bar = GlassBar(a, src, glassVeil).apply { addView(row, FrameLayout.LayoutParams(-1, -1)) }
        return bar to input
    }

    /** iOS search field: a grey rounded bar with a magnifier. */
    fun searchField(hint: String): EditText = EditText(a).apply {
        this.hint = hint; textSize = 17f; maxLines = 1; isSingleLine = true
        setTextColor(onSurface); setHintTextColor(onSurfaceVar)
        setPadding(18.dp, 12.dp, 18.dp, 12.dp)
        background = GradientDrawable().apply { cornerRadius = 100.dp.toFloat(); setColor(surface) }
        compoundDrawablePadding = 10.dp
        androidx.core.content.ContextCompat.getDrawable(a, R.drawable.ic_search)?.mutate()?.let { d ->
            d.setBounds(0, 0, 20.dp, 20.dp); d.setTint(onSurfaceVar)
            setCompoundDrawablesRelative(d, null, null, null)
        }
    }

    fun testField(): Pair<View, EditText> {
        val input = EditText(a).apply {
            hint = "Type here to test…"; textSize = 17f
            setTextColor(onSurface); setHintTextColor(onSurfaceVar)
            minLines = 2; gravity = Gravity.TOP or Gravity.START
            setPadding(16.dp, 12.dp, 16.dp, 12.dp)
            background = GradientDrawable().apply { cornerRadius = 18.dp.toFloat(); setColor(inputBg) }
        }
        val wrap = LinearLayout(a).apply { setPadding(14.dp, 14.dp, 14.dp, 14.dp); addView(input, LinearLayout.LayoutParams(-1, -2)) }
        return wrap to input
    }
}
