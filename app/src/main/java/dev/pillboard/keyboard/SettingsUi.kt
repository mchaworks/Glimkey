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
    val glassVeil get() = ColorUtils.setAlphaComponent(surface, if (night) 0x8C else 0xE8)

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

    /** Settings icon: a rounded square in the accent's secondary container with its "on" colour glyph, so the tiles belong to the hero tile. */
    @Suppress("UNUSED_PARAMETER")
    private fun iconTile(icon: Int, bg: Int): View = FrameLayout(a).apply {
        background = GradientDrawable().apply { setColor(secondaryContainer); cornerRadius = 9.dp.toFloat() }
        addView(ImageView(a).apply { setImageResource(icon); imageTintList = ColorStateList.valueOf(onSecondaryContainer) },
            FrameLayout.LayoutParams(19.dp, 19.dp, Gravity.CENTER))
    }

    /** Slider parts in the accent instead of the theme's baseline purple. */
    private fun tintSlider(s: Slider) {
        s.setThumbTintList(ColorStateList.valueOf(primary))
        s.setTrackActiveTintList(ColorStateList.valueOf(primary))
        s.setTrackInactiveTintList(ColorStateList.valueOf(ColorUtils.blendARGB(surface, primary, if (night) 0.30f else 0.22f)))
        s.setHaloTintList(ColorStateList.valueOf(ColorUtils.setAlphaComponent(primary, 0x30)))
    }

    /** Title over a gliding segmented control: for a few options where seeing all of them beats tapping through. */
    fun segmentRow(title: String, key: String, def: String, options: List<Pair<String, String>>, onChange: () -> Unit): View =
        LinearLayout(a).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(rowPad, 14.dp, rowPad, 14.dp)
            addView(TextView(a).apply { text = title; textSize = 17f; setTextColor(onSurface) })
            addView(Segmented(a, options.map { it.second }, segTrack, segPill, onSurface, onSurfaceVar, medium).apply {
                select(options.indexOfFirst { it.first == Prefs.str(a, key, def) }, false)
                onSelect = { i -> Prefs.setStr(a, key, options[i].first); onChange() }
            }, LinearLayout.LayoutParams(-1, -2).apply { topMargin = 10.dp })
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
                tintSlider(this)
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

    /**
     * Accent picker: a grid of three-tone chips (primary / secondary / tertiary, like Pixel's picker), the wallpaper's own
     * Material You palette first, then the presets and a custom hue. A palette style decides how far the other two tones
     * drift from the accent. The settings app wears the choice too, so a change recreates the page ([onChange] only
     * refreshes the preview while a hue is being dragged).
     */
    fun accent(onChange: () -> Unit): View {
        val root = LinearLayout(a).apply { orientation = LinearLayout.VERTICAL; setPadding(rowPad, 14.dp, rowPad, 16.dp) }
        root.addView(TextView(a).apply { text = "Accent color"; textSize = 17f; setTextColor(onSurface) })
        val caption = TextView(a).apply { textSize = 13f; setTextColor(onSurfaceVar) }
        root.addView(caption, LinearLayout.LayoutParams(-2, -2).apply { topMargin = 1.dp })
        val grid = LinearLayout(a).apply { orientation = LinearLayout.VERTICAL; setPadding(0, 12.dp, 0, 0) }
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
                LinearLayout.LayoutParams(-2, -2).apply { topMargin = 14.dp; bottomMargin = 8.dp })
            val styles = AccentStyle.values().toList()
            root.addView(Segmented(a, styles.map { it.label }, segTrack, segPill, onSurface, onSurfaceVar, medium).apply {
                select(styles.indexOf(style), false)
                onSelect = { i -> Prefs.setStr(a, "accent_style", styles[i].id); a.recreate() }
            }, LinearLayout.LayoutParams(-1, -2))
            if (Prefs.preset(a) == Prefs.CUSTOM) {
                root.addView(TextView(a).apply { text = "Hue"; textSize = 13f; setTextColor(onSurfaceVar) },
                    LinearLayout.LayoutParams(-2, -2).apply { topMargin = 14.dp })
                val hue = Slider(a).apply {
                    valueFrom = 0f; valueTo = 359f; stepSize = 1f; isTickVisible = false
                    labelBehavior = LabelFormatter.LABEL_GONE; trackHeight = 10.dp; thumbRadius = 13.dp; haloRadius = 22.dp
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
