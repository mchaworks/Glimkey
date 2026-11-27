package dev.pillboard.keyboard

import android.content.Context
import android.graphics.Color
import android.graphics.PixelFormat
import android.graphics.drawable.ColorDrawable
import android.graphics.drawable.GradientDrawable
import androidx.core.content.res.ResourcesCompat
import android.inputmethodservice.InputMethodService
import android.os.Build
import android.os.SystemClock
import android.text.InputType
import android.view.ContextThemeWrapper
import android.view.Gravity
import android.view.KeyEvent
import android.view.View
import android.view.animation.DecelerateInterpolator
import androidx.core.view.children
import android.view.ViewGroup
import android.view.ViewGroup.LayoutParams.MATCH_PARENT
import android.view.ViewGroup.LayoutParams.WRAP_CONTENT
import android.view.WindowManager
import android.view.inputmethod.CursorAnchorInfo
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputConnection
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.PopupWindow
import android.widget.TextView
import androidx.core.view.ViewCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat

class PillboardService : InputMethodService() {

    private companion object {
        /** Long-press alternates (lowercase keys). */
        val ALTS = mapOf(
            "a" to listOf("à", "á", "â", "ä", "ã", "å"),
            "e" to listOf("è", "é", "ê", "ë"),
            "i" to listOf("î", "ï", "í", "ì"),
            "o" to listOf("ô", "ö", "ò", "ó", "õ"),
            "u" to listOf("û", "ü", "ù", "ú"),
            "c" to listOf("ç"),
            "n" to listOf("ñ"),
            "s" to listOf("ß"),
            "y" to listOf("ÿ"),
        )

        /** Long-press alternates on the symbols page. */
        val PUNCT = mapOf(
            "." to listOf("…"), "?" to listOf("¿"), "!" to listOf("¡"),
            "'" to listOf("‘", "’"), "\"" to listOf("“", "”", "«", "»"),
            "-" to listOf("–", "—", "•"), "$" to listOf("€", "£", "¥", "¢"),
            "&" to listOf("§"), "0" to listOf("°"),
        )

        /** Long-press alternates on the extra symbols page ("#+=" on 123): the rarer neighbours of each key. */
        val EXTRA = mapOf(
            "=" to listOf("≠", "≈", "≤", "≥"), "*" to listOf("†", "‡", "★", "☆"),
            "%" to listOf("‰", "‱"), "#" to listOf("№"),
            "^" to listOf("←", "↑", "→", "↓"), "~" to listOf("≈", "∼"),
            "<" to listOf("≤", "«", "‹"), ">" to listOf("≥", "»", "›"), "|" to listOf("¦"),
            "±" to listOf("∓", "∆", "µ"), "×" to listOf("·", "∙"), "÷" to listOf("√", "∞", "∑", "∫"),
            "•" to listOf("◦", "▪", "‣", "✓", "✗"),
            "°" to listOf("℃", "℉", "′", "″"), "©" to listOf("℗"), "™" to listOf("℠"), "§" to listOf("¶", "†", "‡"),
        )
    }

    private enum class Shift { OFF, ON, LOCK }

    private var shift = Shift.OFF
    private var symbols = false
    private var extra = false          // third page, only while [symbols]: the "#+=" page of everyday symbols
    private var lastShiftTap = 0L
    private var lastFix: Pair<String, String>? = null
    private var root: LinearLayout? = null
    private var strip: LinearLayout? = null
    private val letterKeys = mutableListOf<KeyView>()
    private var shiftKey: KeyView? = null
    private var preview: KeyPreview? = null
    private var tapFx: TapFxView? = null
    private var tapFxBack: TapFxBackView? = null
    private var hintBubble: HintBubble? = null
    private var loupe: CursorLoupe? = null
    private var navInset = 0
    private lateinit var themed: Context
    private lateinit var tokens: KbTokens
    private var builtTheme = KbTheme.GLASS
    private var builtDynamic = true
    private var builtSeed = 0
    private var builtFx = true
    private var builtMode = "system"
    private var builtNight = false
    private var builtTri = true
    private var builtStyle = AccentStyle.TONAL
    private var builtTint = 62
    private var pageKey: KeyView? = null
    // emoji panel: grid mode (tabs + grid) or search mode (letter keys type into [query], results in the top bar)
    private var emojiMode = false
    private var emojiSearch = false
    private var emojiCat = 0
    private val query = StringBuilder()
    private var emojiGrid: EmojiGridView? = null
    private var emojiTabs: List<TextView> = emptyList()
    private var searchLabel: TextView? = null
    private var searchScroll: android.widget.HorizontalScrollView? = null
    private var searchResults: LinearLayout? = null
    // translate dock: above the keyboard, inside the shell; the translation of the whole field follows what is typed; tap it to replace the field's text
    private enum class TrState { IDLE, LOADING, OK, ERROR }
    private var translateMode = false
    private var trState = TrState.IDLE
    private var trText: String? = null        // last good translation
    private var trMsg = ""                    // hint / error line
    private var trSrc: String? = null         // detected source language
    private var trShown: String = "en"        // target actually used for trText
    private var trSeq = 0                     // newest request wins
    private var trSrcChip: BlurSwapText? = null
    private var trTgtChip: BlurSwapText? = null
    private var trSwap: View? = null
    private var trSwapTurns = 0               // half turns the swap icon has made (it keeps turning the same way)
    private var trSrcPick = "auto"            // this session's source language: "auto" or a code
    private var trTgtPick: String? = null     // this session's target (a swap); null = the saved target
    private var trResult: BlurSwapText? = null
    private var trAction: View? = null
    private var trDock: LinearLayout? = null  // the dock's clipping box: its height is what the open / close morph animates
    private var trParts: List<View> = emptyList()   // what arrives one after another when the dock opens
    private var trAnim: android.animation.ValueAnimator? = null
    private var trContent: View? = null       // the dock's content block: slides with the shell's rising top edge
    private var shellTop = 0                  // px the shell's top edge sits below the (already full-size) window top, animated
    private var trInsetHold = 0               // px the app's inset stays below the window top while the shell grows
    private var trPopup: PopupWindow? = null
    private var host: ShellHost? = null          // input view: shell + transparent headroom; its background is the shell's glass
    private var menuLayer: FrameLayout? = null   // the capsule over the strip (hold on the top-right strip button)
    private var menuIcons: List<BlurIconView> = emptyList()
    private var menuClosing = false
    private var menuMorph: android.animation.ValueAnimator? = null
    private var menuFrom: RoundRectState? = null
    private var menuTo: RoundRectState? = null
    private val menuAutoClose = Runnable { dismissMenu(true) }
    private var stripIcon: BlurIconView? = null  // the strip button's icon (active feature)
    private val trHandler = android.os.Handler(android.os.Looper.getMainLooper())
    private val trDebounce = Runnable { requestTranslate() }
    private val trExec = java.util.concurrent.Executors.newSingleThreadExecutor()
    /** One glass drawable per built input view: its AGSL layer and light animation are reused across applyBlur() calls. */
    private var shell: GlassSurfaceDrawable? = null
    /** Stage 4: edge refraction of the shell layer (API 33+, "Liquid refraction" setting). */
    private var refraction: ShellRefraction? = null

    override fun onCreate() {
        super.onCreate()
        // dictionaries load off the main thread; the strip fills in as soon as they are ready
        Suggestions.init(applicationContext) { trHandler.post { updateStrip() } }
        runCatching { getSystemService(android.content.ClipboardManager::class.java).addPrimaryClipChangedListener(clipListener) }
    }

    override fun onCreateInputView(): View {
        refraction?.release()
        refraction = null
        builtDynamic = Prefs.dynamic(this)
        builtSeed = Prefs.seedColor(this)
        builtFx = BackEffect.pressGlow(this)
        builtMode = KbMode.pref(this)
        builtNight = KbMode.night(this)
        builtTri = Prefs.bool(this, "tritone", true)
        builtStyle = Prefs.accentStyle(this)
        builtTint = Prefs.int(this, "blur_tint", 62)
        shell = null
        lastBlurRadius = -1; maskDrawable = null; maskGlass = null; maskHost = null     // a new input view: everything is applied afresh
        blurAnim?.cancel(); blurK = 1f; fillK = 0f
        builtTheme = KbTheme.current(this)
        val tw = KbMode.themed(this, builtDynamic)      // the Material theme, in the light / dark mode chosen in Settings
        if (builtTheme.fontOverlay != 0) tw.theme.applyStyle(builtTheme.fontOverlay, true)
        themed = tw
        tokens = KbTokens(themed, builtTheme)
        preview = KeyPreview(themed, tokens)
        val fx = TapFxView(themed, tokens).also { tapFx = it }
        val fxBack = TapFxBackView(themed, tokens).also { tapFxBack = it }
        hintBubble?.dismiss(immediate = true)
        hintBubble = HintBubble(themed, tokens)
        loupe?.dismiss(immediate = true)
        loupe = CursorLoupe(themed, tokens)
        val radius = tokens.containerRadius
        val r = LinearLayout(themed).apply {
            orientation = LinearLayout.VERTICAL
            // the glass is drawn by the host (it also covers the capsule); the shell view only clips its keys to the rounded shape
            outlineProvider = object : android.view.ViewOutlineProvider() {
                override fun getOutline(v: View, outline: android.graphics.Outline) {
                    outline.setRoundRect(0, shellTop, v.width, v.height, radius)
                }
            }
            clipToOutline = true
        }
        root = r
        val h = ShellHost(themed).apply {
            capTop = 0          // no headroom: the capsule lives inside the strip, so the window (and its blur) never changes size
            onOutsideTap = { dismissMenu(true) }
            addView(fxBack, FrameLayout.LayoutParams(MATCH_PARENT, MATCH_PARENT))    // back effects: under the key rows, on the shell
            addView(r, FrameLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT, Gravity.BOTTOM).apply { topMargin = capTop })
            addView(fx, FrameLayout.LayoutParams(MATCH_PARENT, MATCH_PARENT))      // tap effects: above the keys, never touchable
        }
        host = h
        refraction = ShellRefraction(r, tokens.containerRadius)
        // Keep keys clear of the system navigation bar (fixes overlap on edge-to-edge Android 15+)
        setupEdgeToEdgeWindow()
        ViewCompat.setOnApplyWindowInsetsListener(r) { _, insets ->
            val bars = insets.getInsets(WindowInsetsCompat.Type.systemBars() or WindowInsetsCompat.Type.displayCutout()).bottom
            val nav = insets.getInsets(WindowInsetsCompat.Type.navigationBars()).bottom
            val n = maxOf(bars, nav)
            if (n != navInset) { navInset = n; applyPadding() }
            insets
        }
        applyPadding()
        applyBlur()
        watchBlur()
        render()
        return h
    }

    /**
     * The headroom above the shell belongs to the window but not to the keyboard: apps lay out above the shell's top
     * edge, and touches in the headroom fall through to the app, except while the capsule is open (then the whole host
     * takes them, so a tap outside the capsule closes it).
     */
    override fun onComputeInsets(outInsets: InputMethodService.Insets) {
        super.onComputeInsets(outInsets)
        val h = host ?: return
        if (h.height <= 0 || h.visibility != View.VISIBLE) return
        val loc = IntArray(2); h.getLocationInWindow(loc)
        val top = loc[1] + h.capTop + trInsetHold
        outInsets.contentTopInsets = top
        outInsets.visibleTopInsets = top
        if (h.menuOpen) {
            outInsets.touchableInsets = InputMethodService.Insets.TOUCHABLE_INSETS_REGION
            outInsets.touchableRegion.set(loc[0], loc[1], loc[0] + h.width, loc[1] + h.height)
        } else if (shellTop > 0) {
            // the shell is growing: only what it covers takes touches, the transparent part above it falls through
            outInsets.touchableInsets = InputMethodService.Insets.TOUCHABLE_INSETS_REGION
            outInsets.touchableRegion.set(loc[0], loc[1] + shellTop, loc[0] + h.width, loc[1] + h.height)
        }
    }

    /** Moves the shell's top edge (glass and the keys' clip) without any layout; the window itself keeps its final size. */
    private fun setShellTop(px: Int) {
        shellTop = px
        shell?.growTop = px
        root?.invalidateOutline()
    }

    /** Where apps stop above the keyboard: held at the old edge while the shell grows, so the app resizes at most once. */
    private fun setInsetHold(px: Int) {
        if (trInsetHold == px) return
        trInsetHold = px
        host?.requestLayout()
    }

    private var lastFieldKey = 0

    override fun onStartInputView(info: EditorInfo?, restarting: Boolean) {
        super.onStartInputView(info, restarting)
        KbMode.appNight = KbMode.forApp(info?.packageName)
        val rebuild = KbMode.night(this) != builtNight || Prefs.dynamic(this) != builtDynamic || Prefs.seedColor(this) != builtSeed || BackEffect.pressGlow(this) != builtFx || KbTheme.current(this) != builtTheme ||
            KbMode.pref(this) != builtMode || Prefs.bool(this, "tritone", true) != builtTri || Prefs.accentStyle(this) != builtStyle || Prefs.int(this, "blur_tint", 62) != builtTint
        // The app restarted input on the SAME field (many apps do when the field is cleared or the send button changes):
        // nothing about the field changed, so keep the keyboard as it is. Rebuilding every key and re-applying the window
        // blur here made the keyboard flash and resize the app (the chat jumping up and down) each time.
        val fieldKey = if (info == null) 0 else (info.inputType * 31 + info.imeOptions) * 31 + (info.packageName?.hashCode() ?: 0)
        val sameField = restarting && !rebuild && fieldKey == lastFieldKey && root != null && host?.isAttachedToWindow == true
        lastFieldKey = fieldKey
        if (sameField) {
            val prevShift = shift
            autoCap()
            if (prevShift != shift) updateShiftUi()
            updateStrip()
            return
        }
        if (rebuild) setInputView(onCreateInputView())
        symbols = false; extra = false
        swapAnim?.cancel(); panel = Panel.NONE; selecting = false
        captureClip()
        emojiMode = false; emojiSearch = false; query.setLength(0)
        stopTranslateState()
        autoCap()
        applyBlur()
        render()
        setupEdgeToEdgeWindow()
        root?.let { ViewCompat.requestApplyInsets(it) }
        // first-tap hitches: load the key sounds and compile the lens shader now, off the typing path
        if (Prefs.bool(this, "sound", false) && !TypingSound.isLoaded()) {
            val app = applicationContext
            Thread { TypingSound.warm(app) }.apply { isDaemon = true }.start()
        }
        if (Prefs.bool(this, "keypreview", true)) root?.post { root?.let { r -> if (r.isAttachedToWindow) preview?.warm(r) } }
    }

    override fun onFinishInputView(finishingInput: Boolean) {
        preview?.dismiss(immediate = true)
        hintBubble?.dismiss(immediate = true)
        loupe?.dismiss(immediate = true)
        emojiGrid?.dismissTones()
        stopTranslateState()
        Suggestions.save()
        super.onFinishInputView(finishingInput)
        TypingStats.flush(this)
        KbMode.appNight = null   // the Settings app shares this process: it must not inherit a terminal's dark
    }

    /**
     * HyperOS (and other OEM skins) lay the IME window out above the navigation bar and paint the bar's area themselves
     * (a light strip under the panel), and then hand the keys no bottom inset. Drawing the window edge to edge makes the
     * glass run under the gesture bar and delivers the real inset, which [applyPadding] keeps the bottom row clear of.
     */
    private fun setupEdgeToEdgeWindow() {
        val w = window.window ?: return
        runCatching {
            WindowCompat.setDecorFitsSystemWindows(w, false)
            @Suppress("DEPRECATION")
            w.navigationBarColor = Color.TRANSPARENT
            if (Build.VERSION.SDK_INT >= 29) w.isNavigationBarContrastEnforced = false
            // gesture pill / nav icons: dark on a light keyboard, light on a dark one
            val light = androidx.core.graphics.ColorUtils.calculateLuminance(tokens.background) > 0.5
            WindowCompat.getInsetsController(w, w.decorView).isAppearanceLightNavigationBars = light
        }
    }

    private fun applyPadding() {
        root?.setPadding(7.dp, 8.dp, 7.dp, 8.dp + navInset)
    }

    private fun blurSupported() = Build.VERSION.SDK_INT >= 31 &&
        (getSystemService(WINDOW_SERVICE) as WindowManager).isCrossWindowBlurEnabled

    private var blurListener: java.util.function.Consumer<Boolean>? = null

    /** Cross-window blur can be toggled at runtime (battery saver, developer options): re-apply when it flips. */
    private fun watchBlur() {
        if (Build.VERSION.SDK_INT < 31 || blurListener != null) return
        val l = java.util.function.Consumer<Boolean> { applyBlur() }
        blurListener = l
        (getSystemService(WINDOW_SERVICE) as WindowManager).addCrossWindowBlurEnabledListener(mainExecutor, l)
    }

    override fun onDestroy() {
        if (Build.VERSION.SDK_INT >= 31) {
            blurListener?.let { (getSystemService(WINDOW_SERVICE) as WindowManager).removeCrossWindowBlurEnabledListener(it) }
        }
        blurListener = null
        trHandler.removeCallbacks(clipExpire); trHandler.removeCallbacks(stripRefresh)
        runCatching { getSystemService(android.content.ClipboardManager::class.java).removePrimaryClipChangedListener(clipListener) }
        refraction?.release(); refraction = null
        hintBubble?.dismiss(immediate = true); hintBubble = null
        trExec.shutdownNow()
        TypingSound.release()
        Suggestions.save()
        super.onDestroy()
    }

    /**
     * Three tiers: real backdrop blur (translucent shell), blur requested but unavailable (near-opaque tint so
     * text stays readable), blur off (solid tonal shell). The window background is a rounded rect so the blur is
     * masked to the shell's corners instead of showing a square blurred slab.
     */
    private var outlineHost: View? = null
    private var maskDrawable: ShapeMaskDrawable? = null
    private var maskGlass: GlassSurfaceDrawable? = null
    private var maskHost: View? = null
    private var lastBlurRadius = -1
    private var blurOn = false
    /**
     * The window blur is a rect the size of the WINDOW (system behaviour), so while the shell grows inside an already
     * full-size window it cannot follow the shell's edge. It is therefore off while the shell grows ([blurK] = 0) and
     * the glass fill firms up ([fillK] = 1) to cover for it; when the shell has arrived (window = glass) both ease back.
     */
    private var blurK = 1f
    private var fillK = 0f
    private var blurAnim: android.animation.ValueAnimator? = null

    private fun blurRadiusNow(): Int = (tokens.blurRadius * blurK).toInt()
    private fun glassFillNow(): Int =
        androidx.core.graphics.ColorUtils.blendARGB(tokens.glassFillBlur, tokens.glassFillNoBlur, fillK)

    private fun applyBlurLevel() {
        if (!blurOn) return
        if (Build.VERSION.SDK_INT >= 31) {
            val rad = blurRadiusNow()
            if (rad != lastBlurRadius) { lastBlurRadius = rad; window.window?.setBackgroundBlurRadius(rad) }
        }
        shell?.setFill(glassFillNow())
    }

    /** Eases blur radius to [blur] and fill firmness to [fill] over [ms] (0 = at once). */
    private fun fadeBlur(blur: Float, fill: Float, ms: Long) {
        blurAnim?.cancel()
        if (!blurOn || !animationsOn(this) || ms <= 0L) { blurK = blur; fillK = fill; applyBlurLevel(); return }
        val b0 = blurK; val f0 = fillK
        blurAnim = android.animation.ValueAnimator.ofFloat(0f, 1f).apply {
            duration = ms
            interpolator = BlurTextTokens.outEase
            addUpdateListener {
                val t = it.animatedFraction
                blurK = b0 + (blur - b0) * t; fillK = f0 + (fill - f0) * t
                applyBlurLevel()
            }
            start()
        }
    }

    private fun applyBlur() {
        // "High contrast text" (accessibility) = Reduce Transparency: solid shell, no blur
        val highContrast = android.provider.Settings.Secure.getInt(contentResolver, "high_text_contrast_enabled", 0) == 1
        val blurRequested = Prefs.bool(this, "blur", false) && !highContrast && tokens.isGlass
        val blur = blurRequested && blurSupported()
        blurOn = blur
        val fill = when {
            blur -> glassFillNow()
            blurRequested -> tokens.glassFillNoBlur
            else -> tokens.background
        }
        // refraction re-samples the shell layer, so it is off for "high contrast text" like the blur
        refraction?.update(Prefs.bool(this, "refract", false) && !highContrast && tokens.isGlass)
        val glass = shell?.also { it.setFill(fill) } ?: tokens.shell(fill).also { shell = it }
        glass.topPad = host?.capTop ?: 0
        if (host?.background !== glass) host?.background = glass
        window.window?.let { w ->
            val hv = host
            if (blur && hv != null) {
                // one mask per glass + host: rebuilding it on every call swapped the window background (and its outline) under
                // a visible window each time the keyboard (re)started, which showed as a flash of the blur
                val mask = maskDrawable?.takeIf { maskGlass === glass && maskHost === hv && w.decorView.background === it }
                    ?: ShapeMaskDrawable(glass, hv).also { m ->
                        maskDrawable = m; maskGlass = glass; maskHost = hv
                        w.setBackgroundDrawable(m)
                        w.decorView.invalidateOutline()
                    }
                glass.onShapeChanged = { mask.invalidateSelf(); w.decorView.invalidateOutline() }
                hv.post { w.decorView.invalidateOutline() }   // host position is known only after layout
                if (outlineHost !== hv) {                     // the outline has bounds: follow the host when its size changes
                    outlineHost = hv
                    hv.addOnLayoutChangeListener { _, l, t, r, b, oldL, oldT, oldR, oldB ->
                        if (r - l != oldR - oldL || b - t != oldB - oldT) w.decorView.invalidateOutline()
                    }
                }
            } else {
                glass.onShapeChanged = null
                if (maskDrawable != null) {
                    maskDrawable = null; maskGlass = null; maskHost = null
                    w.setBackgroundDrawable(ColorDrawable(Color.TRANSPARENT))
                } else if (w.decorView.background !is ColorDrawable) w.setBackgroundDrawable(ColorDrawable(Color.TRANSPARENT))
            }
            // Android's window-blur docs: the blur is drawn under the window surface, so the window must be translucent.
            // Set it once: every setFormat is a window relayout, and the app above resizes with it.
            if (blur && w.attributes.format != PixelFormat.TRANSLUCENT) w.setFormat(PixelFormat.TRANSLUCENT)
            if (Build.VERSION.SDK_INT >= 31) {
                val rad = if (blur) blurRadiusNow() else 0
                if (rad != lastBlurRadius) { lastBlurRadius = rad; w.setBackgroundBlurRadius(rad) }
            }
        }
    }

    // ---- layout ----
    private fun render(animateRows: Boolean = false, dir: BlurDir = BlurDir.UP) {
        val r = root ?: return
        trAnim?.cancel()
        if (menuLayer != null) dismissMenu()
        // labels of the rows above the bottom row (text + any running Blur Replace), kept to animate only what changes
        val oldRows = if (animateRows) (0 until r.childCount).map { r.getChildAt(it) }
            .filter { it !== strip && it !== r.getChildAt(r.childCount - 1) && it is ViewGroup }
            .map { row -> (row as ViewGroup).children.filterIsInstance<KeyView>().filter { it.text.length == 1 }
                .map { OldKey(it.text.toString(), it.blurSnapshot(), it.visualW, it.visualH,
                    row.left + it.left, row.top + it.top, it.width, it.height, it.textSize, it.typeface, it.currentTextColor) }.toList() }
        else null
        r.removeAllViews()
        letterKeys.clear(); shiftKey = null; pageKey = null
        if (panel != Panel.NONE) { strip = null; emojiGrid = null; r.addView(if (panel == Panel.CLIPBOARD) buildClipPanel() else buildEditPanel()); return }
        val keyH = keyHeight()
        val gb = Prefs.gboard(this)
        val numLabel = if (gb) "?123" else "123"

        val field = fieldKind()
        val numpad = field == Field.NUMBER && !emojiMode
        val grid = emojiMode && !emojiSearch
        emojiGrid = null
        strip = when {
            emojiMode && emojiSearch -> buildSearchBar().also { r.addView(it) }
            grid -> buildEmojiTabs().also { r.addView(it) }
            translateMode -> buildTranslateDock().also { r.addView(it) }
            (Prefs.bool(this, "suggest", true) || Prefs.bool(this, "translate", false)) -> buildStrip().also { r.addView(it) }
            else -> null
        }

        if (grid) {
            r.addView(buildEmojiGrid(keyH))
        } else if (numpad) {
            for (digits in listOf("123", "456", "789")) r.addView(rowOf { gap(1f); chars(digits, keyH); gap(1f) })
            r.addView(rowOf {
                gap(1f)
                val side = numpadSide()
                key(side, keyH, 1f, KeyStyle.SPECIAL) { type(side) }
                chars("0", keyH)
                key("Backspace", keyH, 1f, KeyStyle.SPECIAL, repeat = true, icon = R.drawable.ic_backspace) { backspace() }
                gap(1f)
            })
        } else if (!symbols) {
            if (Prefs.bool(this, "numrow", false)) r.addView(rowOf { chars("1234567890", (keyH * 0.8f).toInt()) })
            r.addView(rowOf { chars("qwertyuiop", keyH) })
            r.addView(rowOf { gap(0.5f); chars("asdfghjkl", keyH); gap(0.5f) })
            r.addView(rowOf {
                shiftKey = key(shiftDesc(), keyH, 1.5f, shiftStyle(), icon = shiftIcon()) { onShift() }
                chars("zxcvbnm", keyH)
                key("Backspace", keyH, 1.5f, KeyStyle.SPECIAL, repeat = true, icon = R.drawable.ic_backspace) { backspace() }
            })
        } else if (!extra && gb) {
            // Gboard's ?123 page
            r.addView(rowOf { chars("1234567890", keyH) })
            r.addView(rowOf { chars("@#\$_&-+()/", keyH) })
            r.addView(rowOf {
                key("=\\<", keyH, 1.5f, KeyStyle.SPECIAL) { setExtra(true) }
                chars("*\"':;!?", keyH)
                key("Backspace", keyH, 1.5f, KeyStyle.SPECIAL, repeat = true, icon = R.drawable.ic_backspace) { backspace() }
            })
        } else if (!extra) {
            r.addView(rowOf { chars("1234567890", keyH) })
            r.addView(rowOf { chars("-/:;()$&@\"", keyH) })
            r.addView(rowOf {
                // the slot the Shift key holds on the letter page: the third page of everyday symbols
                key("#+=", keyH, 1.5f, KeyStyle.SPECIAL) { setExtra(true) }
                chars(".,?!'", keyH, 1.4f)
                key("Backspace", keyH, 1.5f, KeyStyle.SPECIAL, repeat = true, icon = R.drawable.ic_backspace) { backspace() }
            })
        } else if (gb) {
            // Gboard's =\< page
            r.addView(rowOf { chars("~`|•√π÷×¶∆", keyH) })
            r.addView(rowOf { chars("£¢€¥^°={}\\", keyH) })
            r.addView(rowOf {
                key(numLabel, keyH, 1.5f, KeyStyle.SPECIAL) { setExtra(false) }
                chars("%©®™✓[]", keyH)
                key("Backspace", keyH, 1.5f, KeyStyle.SPECIAL, repeat = true, icon = R.drawable.ic_backspace) { backspace() }
            })
        } else {
            // third page: brackets, math and the marks documents use; the rarer ones sit under a long-press (EXTRA)
            r.addView(rowOf { chars("[]{}#%^*+=", keyH) })
            r.addView(rowOf { chars("_\\|~<>±×÷•", keyH) })
            r.addView(rowOf {
                key(numLabel, keyH, 1.5f, KeyStyle.SPECIAL) { setExtra(false) }
                chars("°©®™§", keyH, 1.4f)
                key("Backspace", keyH, 1.5f, KeyStyle.SPECIAL, repeat = true, icon = R.drawable.ic_backspace) { backspace() }
            })
        }
        if (grid) r.addView(emojiBottomRow(keyH)) else if (numpad) r.addView(rowOf {
            gap(0.4f)
            key(actionName(), keyH, 3.2f, KeyStyle.ACCENT) { enter() }
            gap(0.4f)
        }) else r.addView(rowOf {
            pageKey = key(if (symbols) "ABC" else numLabel, keyH, 1.35f, KeyStyle.SPECIAL) {
                // snapshot first: render() rebuilds the row, and a transition still running on the old key carries over
                val from = if (symbols) "ABC" else numLabel
                val running = pageKey?.blurSnapshot()
                val oldW = pageKey?.visualW ?: 0
                val oldH = pageKey?.visualH ?: 0
                symbols = !symbols; extra = false     // ABC leaves the third page too; 123 always opens the first symbols page
                val dir = if (symbols) BlurDir.UP else BlurDir.DOWN         // same vertical language as Shift: 123 rises, ABC settles
                render(animateRows = true, dir = dir)    // the new label is already in place; Blur Replace only changes what is painted
                pageKey?.morphLabel(if (symbols) "ABC" else numLabel, from = from, inherited = running, oldW = oldW, oldH = oldH, dir = dir)
            }
            val emojiBeside = emojiBesideComma()
            if (emojiBeside && !gb) key("Emoji", keyH, 0.85f, KeyStyle.SPECIAL, icon = R.drawable.ic_emoji) { setEmoji(true) }
            when (field) {
                Field.EMAIL -> key("@", keyH, 0.85f, KeyStyle.SPECIAL) { type("@") }.also { k ->
                    k.altsFor = { listOf("@gmail.com", "@yahoo.com", "@outlook.com", ",") }; k.onAlt = { type(it) }
                }
                Field.URL -> key("/", keyH, 0.85f, KeyStyle.SPECIAL) { type("/") }.also { k ->
                    k.altsFor = { listOf("https://", "www.", "://", ",") }; k.onAlt = { type(it) }
                }
                else -> key(",", keyH, 0.85f, KeyStyle.SPECIAL) { type(",") }
            }
            if (emojiBeside && gb) key("Emoji", keyH, 0.85f, KeyStyle.SPECIAL, icon = R.drawable.ic_emoji) { setEmoji(true) }
            key("space", keyH, if (emojiBeside) 3.6f else 4.45f, KeyStyle.SPECIAL) { space() }.also { sp ->
                if (Prefs.bool(this@PillboardService, "spaceslide", true)) {
                    sp.onSlide = { n -> moveCursor(n) }
                    sp.onSlideState = { on ->
                        letterKeys.forEach { it.animate().alpha(if (on) 0.35f else 1f).setDuration(120).start() }
                        if (on) showLoupe(sp) else { loupe?.dismiss(); currentInputConnection?.requestCursorUpdates(0) }
                    }
                    sp.onSlideMove = { x -> loupe?.follow(x) }
                }
            }
            key(".", keyH, 0.85f, KeyStyle.SPECIAL) { type(".") }.also { k ->
                if (field == Field.EMAIL || field == Field.URL) {
                    k.altsFor = { listOf(".com", ".id", ".co.id", ".net", ".org") }; k.onAlt = { type(it) }
                }
            }
            val action = currentInputEditorInfo?.imeOptions?.and(EditorInfo.IME_MASK_ACTION) ?: 0
            // iOS: the action key is a word, not an icon ("return" in lowercase, the editor actions capitalised)
            val name = when (action) {
                EditorInfo.IME_ACTION_GO -> "Go"
                EditorInfo.IME_ACTION_SEARCH -> "Search"
                EditorInfo.IME_ACTION_SEND -> "Send"
                EditorInfo.IME_ACTION_DONE -> "Done"
                EditorInfo.IME_ACTION_NEXT -> "Next"
                else -> "return"
            }
            // Gboard: the action key is an icon; the label stays the word (key sound, accessibility)
            val enterIcon = if (!gb) 0 else when (action) {
                EditorInfo.IME_ACTION_GO, EditorInfo.IME_ACTION_NEXT -> R.drawable.ic_arrow_forward
                EditorInfo.IME_ACTION_SEARCH -> R.drawable.ic_search
                EditorInfo.IME_ACTION_SEND -> R.drawable.ic_send
                EditorInfo.IME_ACTION_DONE -> R.drawable.ic_check
                else -> R.drawable.ic_enter
            }
            key(name, keyH, 1.5f, KeyStyle.ACCENT, icon = enterIcon) { enter() }   // always the primary action
        })
        updateStrip()
        if (oldRows != null && animationsOn(this)) {
            // rows stay still; each key whose label differs from the old page blurs from the old to the new character.
            // Rows are matched from the bottom (a number row above QWERTY has no counterpart), keys by position.
            val rows = (0 until r.childCount).map { r.getChildAt(it) }.filter { it !== strip && it !== r.getChildAt(r.childCount - 1) && it is ViewGroup }
            for (ri in rows.indices) {
                val old = oldRows.getOrNull(oldRows.size - rows.size + ri)
                var ki = 0
                for (k in (rows[ri] as ViewGroup).children.filterIsInstance<KeyView>()) {
                    if (k.text.length != 1) continue
                    val o = old?.getOrNull(ki++)
                    val same = o != null && o.snap == null && o.text == k.text.toString()
                    if (same) {
                        // same character: only a genuinely different key size animates (its body), the text stays still
                        if (kotlin.math.abs(o!!.w - k.width) > 1 || kotlin.math.abs(o.h - k.height) > 1) k.startShapeMorph(o.w, o.h)
                        continue
                    }
                    // no counterpart on the old page (e.g. n, m coming back from the symbols page): arrive on the same
                    // delay as its neighbours instead of 60ms ahead of them
                    k.morphLabel(k.text.toString(), from = o?.text ?: "", inherited = o?.snap, oldW = o?.w ?: 0, oldH = o?.h ?: 0,
                        syncDelay = o == null, dir = dir)
                }
                // old keys the new row has no slot for (n, m going to the symbols page): their views are gone, so
                // let the label blur out on its own instead of vanishing in one frame
                old?.drop(ki)?.forEach { ghost(r, it, dir) }
            }
        }
    }



    // ---- panels (clipboard, text editing): the shell's whole content is replaced with Blur Replace ----
    private enum class Panel { NONE, CLIPBOARD, EDIT }
    private var panel = Panel.NONE
    private var panelH = 0
    private var swapAnim: android.animation.ValueAnimator? = null
    private val clips = ArrayList<String>()
    private var clipList: LinearLayout? = null
    private var selecting = false
    private var selectKey: KeyView? = null
    private var copyKey: KeyView? = null
    private val clipListener = android.content.ClipboardManager.OnPrimaryClipChangedListener { captureClip() }

    /** In: the old content blurs out (up), the panel arrives from blur. Out: the same, the other way (down). */
    private fun openPanel(p: Panel) {
        val r = root ?: return
        panelH = (r.height - r.paddingTop - r.paddingBottom).coerceAtLeast(180.dp)   // the shell keeps its height
        selecting = false
        if (p == Panel.CLIPBOARD) captureClip()
        swapShell(BlurDir.UP) { panel = p; render() }
    }

    private fun closePanel() = swapShell(BlurDir.DOWN) { panel = Panel.NONE; selecting = false; render() }

    /**
     * Blur Replace of everything inside the shell: a bitmap of what is shown leaves (blur, fade, drift along [dir]) while
     * the rebuilt content arrives from blur, on the same out/in timing as the text transition.
     */
    private fun swapShell(dir: BlurDir, change: () -> Unit) {
        val r = root
        if (r == null || r.width <= 0 || r.height <= 0 || !animationsOn(this)) { change(); return }
        swapAnim?.cancel()
        val bmp = android.graphics.Bitmap.createBitmap(r.width, r.height, android.graphics.Bitmap.Config.ARGB_8888)
        r.draw(android.graphics.Canvas(bmp))
        val ghost = android.widget.ImageView(themed).apply {
            setImageBitmap(bmp); scaleType = android.widget.ImageView.ScaleType.FIT_XY
            layout(0, 0, r.width, r.height)
        }
        change()
        val kids = (0 until r.childCount).map { r.getChildAt(it) }
        r.overlay.add(ghost)
        kids.forEach { it.blurFx(1f, dir, 1) }
        swapAnim = android.animation.ValueAnimator.ofFloat(0f, 1f).apply {
            duration = BlurTextTokens.TOTAL_MS
            interpolator = android.view.animation.LinearInterpolator()
            addUpdateListener {
                val t = it.animatedFraction * BlurTextTokens.TOTAL_MS
                ghost.blurFx(BlurTextTokens.outEase.getInterpolation((t / BlurTextTokens.OUT_MS).coerceIn(0f, 1f)), dir, -1)
                val x = ((t - BlurTextTokens.IN_DELAY_MS) / BlurTextTokens.IN_MS).coerceIn(0f, 1f)
                val s = 1f - BlurTextTokens.inEase.getInterpolation(x)
                kids.forEach { v -> v.blurFx(s, dir, 1) }
            }
            addListener(object : android.animation.AnimatorListenerAdapter() {     // also runs when cancelled
                override fun onAnimationEnd(a: android.animation.Animator) {
                    r.overlay.remove(ghost); bmp.recycle()
                    kids.forEach { v -> v.blurFx(0f, dir, 1) }
                    if (swapAnim === a) swapAnim = null
                }
            })
            start()
        }
    }

    private fun lp(w: Int, h: Int, weight: Float = 0f) = LinearLayout.LayoutParams(w, h, weight)

    /** Back arrow, title and an optional text action: the panel's own top bar (same footprint as the suggestion strip). */
    private fun panelBar(title: String, action: String? = null, onAction: () -> Unit = {}) = LinearLayout(themed).apply {
        orientation = LinearLayout.HORIZONTAL
        isBaselineAligned = false
        layoutParams = LinearLayout.LayoutParams(MATCH_PARENT, 42.dp).apply { setMargins(3.dp, 0, 3.dp, 3.dp) }
        setPadding(3.dp, 3.dp, 3.dp, 3.dp)
        val medium = ResourcesCompat.getFont(themed, R.font.gsr_medium)
        addView(android.widget.ImageView(themed).apply {
            setImageResource(R.drawable.ic_arrow_back)
            imageTintList = android.content.res.ColorStateList.valueOf(tokens.keyText)
            scaleType = android.widget.ImageView.ScaleType.CENTER_INSIDE
            setPadding(10.dp, 10.dp, 10.dp, 10.dp); imageAlpha = 220
            contentDescription = "Back"
            background = tokens.stripItemBackground()
            setOnClickListener { closePanel() }
        }, lp(46.dp, MATCH_PARENT))
        addView(TextView(themed).apply {
            text = title; textSize = 16f; gravity = Gravity.CENTER_VERTICAL
            setPadding(8.dp, 0, 8.dp, 0); setTextColor(tokens.keyText)
            if (medium != null) typeface = medium
        }, lp(0, MATCH_PARENT, 1f))
        if (action != null) addView(TextView(themed).apply {
            text = action; textSize = 14f; gravity = Gravity.CENTER
            setPadding(14.dp, 0, 14.dp, 0); setTextColor(tokens.keyText)
            background = tokens.stripItemBackground()
            setOnClickListener { onAction() }
        }, lp(WRAP_CONTENT, MATCH_PARENT))
    }

    // clipboard: Android has no history API, so copies are collected while the keyboard process lives (memory only, never written to disk)
    private fun captureClip() {
        runCatching {
            val c = getSystemService(android.content.ClipboardManager::class.java).primaryClip ?: return
            if (c.itemCount == 0) return
            if (Build.VERSION.SDK_INT >= 33 && c.description.extras?.getBoolean("android.content.extra.IS_SENSITIVE") == true) {
                clipTimeMs = 0L          // a sensitive copy (password manager) is never offered on the strip
                return
            }
            val t = c.getItemAt(0).coerceToText(this)?.toString()?.takeIf { it.isNotBlank() } ?: return
            // when this clip was copied: a copy made before the keyboard opened still counts as fresh
            val ts = c.description.timestamp.takeIf { it > 0L } ?: System.currentTimeMillis()
            if (ts != clipTimeMs) { clipTimeMs = ts; clipDismissed = null }    // a new copy (even of the same text) is offered again
            if (clips.firstOrNull() != t) {
                clips.remove(t); clips.add(0, t)
                while (clips.size > 20) clips.removeAt(clips.lastIndex)
                if (panel == Panel.CLIPBOARD) fillClips()
            }
            trHandler.post { updateStrip() }
        }
    }

    // ---- clipboard chip on the suggestion strip (like Gboard): a fresh copy can be pasted with one tap ----
    private val clipFreshMs = 60_000L            // empty field: offered for a minute
    private val clipFreshTypingMs = 20_000L      // field with text: only briefly, the predictions matter more
    private var clipTimeMs = 0L                  // when the current primary clip was copied (0 = nothing to offer)
    private var clipDismissed: String? = null    // pasted already: not offered again until copied anew
    private var clipLayoutOn = false
    private var brandLayoutOn = false              // the name + dot sit at the left of the strip (empty field / no-suggestion fields)
    private var brandChip: LinearLayout? = null
    private var brandInner: LinearLayout? = null   // dot + name: this is what blurs in and out (the chip around it only holds the place)
    private var brandAnim: android.animation.ValueAnimator? = null
    private var brandGhost: View? = null
    private var clipChip: LinearLayout? = null
    private var clipText: TextView? = null
    private var lastStripKey: String? = null
    private val clipExpire = Runnable { updateStrip() }
    private val stripRefresh = Runnable { if (panel == Panel.NONE) refreshStripIfStale() }

    private fun freshClip(maxAgeMs: Long): String? {
        val t = clips.firstOrNull() ?: return null
        if (t == clipDismissed || clipTimeMs <= 0L) return null
        return if (System.currentTimeMillis() - clipTimeMs <= maxAgeMs) t else null
    }

    private fun clipPreview(t: String) = t.replace(Regex("\\s+"), " ").trim().take(80)

    private fun pasteClip(t: String) {
        lastFix = null
        currentInputConnection?.commitText(t, 1)
        clipDismissed = t
        afterEdit()
    }

    /** Never put a copied text on the strip in a password field. */
    private fun isPasswordField(): Boolean {
        val t = currentInputEditorInfo?.inputType ?: return false
        val v = t and InputType.TYPE_MASK_VARIATION
        return when (t and InputType.TYPE_MASK_CLASS) {
            InputType.TYPE_CLASS_TEXT -> v == InputType.TYPE_TEXT_VARIATION_PASSWORD ||
                v == InputType.TYPE_TEXT_VARIATION_VISIBLE_PASSWORD || v == InputType.TYPE_TEXT_VARIATION_WEB_PASSWORD
            InputType.TYPE_CLASS_NUMBER -> v == InputType.TYPE_NUMBER_VARIATION_PASSWORD
            else -> false
        }
    }

    /** While the chip shows, the three suggestion slots collapse and the chip (icon + text, centred) takes the whole strip. */
    private fun setClipChipLayout(s: LinearLayout, on: Boolean, text: String?) {
        val chip = clipChip ?: return
        if (on) clipText?.text = text
        if (clipLayoutOn == on) return
        clipLayoutOn = on
        applySlotWeights(s)
        chip.animate().cancel()
        if (on) {
            chip.visibility = View.VISIBLE
            if (animationsOn(this) && s.isLaidOut) {
                chip.alpha = 0f; chip.scaleX = 0.94f; chip.scaleY = 0.94f
                chip.animate().alpha(1f).scaleX(1f).scaleY(1f).setDuration(BlurTextTokens.TOTAL_MS).setInterpolator(BlurTextTokens.inEase).start()
            } else { chip.alpha = 1f; chip.scaleX = 1f; chip.scaleY = 1f }
        } else {
            chip.visibility = View.GONE
            chip.alpha = 1f; chip.scaleX = 1f; chip.scaleY = 1f
        }
        s.requestLayout()
    }

    /** The three suggestion slots give up their width while the paste chip or the brand (dot + name) owns the strip. */
    private fun applySlotWeights(s: LinearLayout) {
        val w = if (clipLayoutOn || brandLayoutOn) 0f else 1f
        for (i in 0 until 3) (s.getChildAt(i * 2).layoutParams as LinearLayout.LayoutParams).weight = w
    }

    /**
     * Brand on = a small theme-coloured dot and "Glimkey" at the left of the strip (decoration only, not clickable).
     * It comes and goes with Blur Replace like every other word on the strip: arriving, it blurs in a moment after the
     * old words have left; leaving, a picture of it blurs away on the strip's overlay while the chip itself is gone at once.
     */
    private fun setBrandLayout(s: LinearLayout, on: Boolean) {
        val b = brandChip ?: return
        val inner = brandInner ?: return
        if (brandLayoutOn == on) return
        brandLayoutOn = on
        brandAnim?.cancel(); brandAnim = null
        brandGhost?.let { s.overlay.remove(it) }; brandGhost = null
        val animate = animationsOn(this) && s.isLaidOut
        // taken before the slots take their width back: where the name is right now
        val bmp = if (!on && animate && b.visibility == View.VISIBLE && inner.width > 0) snapshot(inner) else null
        val rect = if (bmp != null) android.graphics.Rect(b.left + inner.left, b.top + inner.top, b.left + inner.right, b.top + inner.bottom) else null
        applySlotWeights(s)
        if (on) {
            b.visibility = View.VISIBLE
            if (animate) {
                inner.blurFx(1f, BlurDir.UP, 1)
                brandAnim = android.animation.ValueAnimator.ofFloat(0f, 1f).apply {
                    duration = BlurTextTokens.IN_MS
                    startDelay = BlurTextTokens.IN_DELAY_MS
                    interpolator = BlurTextTokens.inEase
                    addUpdateListener { inner.blurFx(1f - it.animatedValue as Float, BlurDir.UP, 1) }
                    addListener(object : android.animation.AnimatorListenerAdapter() {     // also runs when cancelled
                        override fun onAnimationEnd(a: android.animation.Animator) { inner.blurFx(0f, BlurDir.UP, 1) }
                    })
                    start()
                }
            } else inner.blurFx(0f, BlurDir.UP, 1)
        } else {
            b.visibility = View.GONE
            inner.blurFx(0f, BlurDir.UP, 1)
            if (bmp != null && rect != null) {
                val ghost = ghostOf(bmp, rect)
                brandGhost = ghost
                s.overlay.add(ghost)
                brandAnim = android.animation.ValueAnimator.ofFloat(0f, 1f).apply {
                    duration = BlurTextTokens.OUT_MS
                    interpolator = BlurTextTokens.outEase
                    addUpdateListener { ghost.blurFx(it.animatedValue as Float, BlurDir.UP, -1) }
                    addListener(object : android.animation.AnimatorListenerAdapter() {     // also runs when cancelled
                        override fun onAnimationEnd(a: android.animation.Animator) {
                            s.overlay.remove(ghost); bmp.recycle()
                            if (brandGhost === ghost) brandGhost = null
                        }
                    })
                    start()
                }
            }
        }
        s.requestLayout()
    }

    /**
     * One read of the editor around the caret. Every InputConnection getter is a synchronous binder call into the app
     * being typed in, so each costs UI-thread time (more in heavy apps). The strip used to make about 8 of them per
     * keystroke; it now reads once and derives the key, the context and "is the field empty" from this snapshot.
     */
    private class Around(val before: CharSequence?, val after: CharSequence?, val selected: CharSequence?)

    private fun around(): Around? {
        val ic = currentInputConnection ?: return null
        return Around(ic.getTextBeforeCursor(120, 0), ic.getTextAfterCursor(1, 0), ic.getSelectedText(0))
    }

    private fun stripKey(a: Around?): String =
        if (a == null) "-" else "${a.before}\u0001${a.after}\u0001${a.selected?.length ?: 0}"

    private fun stripKey(): String = stripKey(around())

    /** Same answer as [fieldIsEmpty], from a snapshot: an unknown answer (null) is never "empty", and a select-all is not empty. */
    private fun fieldIsEmpty(a: Around?): Boolean {
        if (a == null) return false
        val b = a.before ?: return false
        val f = a.after ?: return false
        if (b.isNotEmpty() || f.isNotEmpty()) return false
        return a.selected.isNullOrEmpty()
    }

    private fun refreshStripIfStale() { if (stripKey() != lastStripKey) updateStrip() }

    /** The field changed under us (the app cleared it after Send, select-all, a tap that moved the caret): the strip must follow. */
    override fun onUpdateSelection(oldSelStart: Int, oldSelEnd: Int, newSelStart: Int, newSelEnd: Int, candidatesStart: Int, candidatesEnd: Int) {
        super.onUpdateSelection(oldSelStart, oldSelEnd, newSelStart, newSelEnd, candidatesStart, candidatesEnd)
        if (oldSelStart == newSelStart && oldSelEnd == newSelEnd) return
        // our own typing already refreshed the strip in afterEdit(); only outside changes (cleared after Send, a caret tap)
        // need this, so wait a beat and collapse a burst of selection updates into one check
        trHandler.removeCallbacks(stripRefresh)
        trHandler.postDelayed(stripRefresh, 90)
    }

    private fun buildClipPanel() = LinearLayout(themed).apply {
        orientation = LinearLayout.VERTICAL
        layoutParams = LinearLayout.LayoutParams(MATCH_PARENT, panelH)
        addView(panelBar("Clipboard", "Clear") { clips.clear(); fillClips() })
        val list = LinearLayout(themed).apply { orientation = LinearLayout.VERTICAL }
        clipList = list
        addView(android.widget.ScrollView(themed).apply {
            isVerticalScrollBarEnabled = false; overScrollMode = View.OVER_SCROLL_NEVER; addView(list)
        }, lp(MATCH_PARENT, 0, 1f))
        fillClips()
    }

    private fun fillClips() {
        val box = clipList ?: return
        box.removeAllViews()
        if (clips.isEmpty()) {
            box.addView(TextView(themed).apply {
                text = "Copied text shows up here"; textSize = 14f; gravity = Gravity.CENTER
                setTextColor(tokens.keyText); alpha = 0.5f
            }, lp(MATCH_PARENT, 120.dp))
            return
        }
        val rad = Prefs.keyRadius(this).dp.toFloat()
        fun shape(c: Int) = GradientDrawable().apply { setColor(c); cornerRadius = rad }
        for (t in clips.toList()) box.addView(LinearLayout(themed).apply {
            orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL
            background = android.graphics.drawable.StateListDrawable().apply {
                addState(intArrayOf(android.R.attr.state_pressed), shape(tokens.pressed))
                addState(intArrayOf(), shape(tokens.key))
            }
            setOnClickListener {
                lastFix = null
                currentInputConnection?.commitText(t, 1)
                clipDismissed = t
                afterEdit()
                closePanel()
            }
            addView(TextView(themed).apply {
                text = t; textSize = 14.5f; maxLines = 3; ellipsize = android.text.TextUtils.TruncateAt.END
                setTextColor(tokens.keyText); setPadding(14.dp, 10.dp, 6.dp, 10.dp)
            }, lp(0, WRAP_CONTENT, 1f))
            addView(TextView(themed).apply {
                text = "\u2715"; textSize = 14f; gravity = Gravity.CENTER; alpha = 0.55f
                setTextColor(tokens.keyText); contentDescription = "Delete"
                setOnClickListener { clips.remove(t); fillClips() }
            }, lp(44.dp, 44.dp))
        }, LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT).apply { setMargins(3.dp, 0, 3.dp, 6.dp) })
    }

    // text editing: arrows, Select (arrows then extend the selection), select all / copy / paste, line start / end, backspace
    private fun LinearLayout.pk(w: Float, label: String, style: KeyStyle = KeyStyle.SPECIAL, repeat: Boolean = false, icon: Int = 0, onKey: () -> Unit): KeyView {
        val g = Prefs.keyGap(this@PillboardService)
        return KeyView(themed, label, style, tokens, repeat, icon, onKey).also {
            it.setHitInset(g.dp, (g + 2).dp)
            it.glass = shell
            it.tapFx = tapFx
            it.tapFxBack = tapFxBack
            addView(it, if (orientation == LinearLayout.VERTICAL) lp(MATCH_PARENT, 0, w) else lp(0, MATCH_PARENT, w))
        }
    }

    private fun LinearLayout.group(w: Float, vertical: Boolean, build: LinearLayout.() -> Unit) = addView(LinearLayout(themed).apply {
        orientation = if (vertical) LinearLayout.VERTICAL else LinearLayout.HORIZONTAL
        isBaselineAligned = false
        build()
    }, if (this.orientation == LinearLayout.VERTICAL) lp(MATCH_PARENT, 0, w) else lp(0, MATCH_PARENT, w))

    private fun buildEditPanel() = LinearLayout(themed).apply {
        orientation = LinearLayout.VERTICAL
        layoutParams = LinearLayout.LayoutParams(MATCH_PARENT, panelH)
        addView(panelBar("Text editing"))
        val body = LinearLayout(themed).apply { orientation = LinearLayout.VERTICAL }
        addView(body, lp(MATCH_PARENT, 0, 1f))
        body.group(3f, vertical = false) {
            pk(1f, "Left", repeat = true, icon = R.drawable.ic_chevron_left) { moveKey(KeyEvent.KEYCODE_DPAD_LEFT) }
            group(1f, vertical = true) {
                pk(1f, "Up", repeat = true, icon = R.drawable.ic_chevron_up) { moveKey(KeyEvent.KEYCODE_DPAD_UP) }
                selectKey = pk(1f, "Select", if (selecting) KeyStyle.ACTIVE else KeyStyle.SPECIAL) {
                    selecting = !selecting
                    selectKey?.setKeyStyle(if (selecting) KeyStyle.ACTIVE else KeyStyle.SPECIAL)
                }
                pk(1f, "Down", repeat = true, icon = R.drawable.ic_chevron_down) { moveKey(KeyEvent.KEYCODE_DPAD_DOWN) }
            }
            pk(1f, "Right", repeat = true, icon = R.drawable.ic_chevron_right) { moveKey(KeyEvent.KEYCODE_DPAD_RIGHT) }
            group(1f, vertical = true) {
                pk(1f, "Select all") { editAction(android.R.id.selectAll) }
                copyKey = pk(1f, "Copy") { editAction(android.R.id.copy); selecting = false; selectKey?.setKeyStyle(KeyStyle.SPECIAL) }
                pk(1f, "Paste") { lastFix = null; editAction(android.R.id.paste); afterEdit() }
            }
        }
        body.group(1f, vertical = false) {
            pk(1.5f, "Line start", repeat = true, icon = R.drawable.ic_first_page) { moveKey(KeyEvent.KEYCODE_MOVE_HOME) }
            pk(1.5f, "Line end", repeat = true, icon = R.drawable.ic_last_page) { moveKey(KeyEvent.KEYCODE_MOVE_END) }
            pk(1f, "Backspace", repeat = true, icon = R.drawable.ic_backspace) { backspace() }
        }
        refreshEdit()
    }

    /** Arrow / home / end; with Select on, Shift is held so the selection grows instead of the caret moving. */
    private fun moveKey(code: Int) {
        val ic = currentInputConnection ?: return
        val meta = if (selecting) KeyEvent.META_SHIFT_ON or KeyEvent.META_SHIFT_LEFT_ON else 0
        val t = SystemClock.uptimeMillis()
        ic.sendKeyEvent(KeyEvent(t, t, KeyEvent.ACTION_DOWN, code, 0, meta))
        ic.sendKeyEvent(KeyEvent(t, t, KeyEvent.ACTION_UP, code, 0, meta))
        refreshEdit()
    }

    private fun editAction(id: Int) { currentInputConnection?.performContextMenuAction(id); refreshEdit() }

    /** Copy is dimmed while nothing is selected (the selection updates a moment after the key event). */
    private fun refreshEdit() = trHandler.postDelayed({
        if (panel == Panel.EDIT) copyKey?.alpha = if (currentInputConnection?.getSelectedText(0)?.isNotEmpty() == true) 1f else 0.4f
    }, 60)

    // ---- translate mode ----
    private fun trTarget(): String = trTgtPick ?: Prefs.str(this, "tr_target", "").ifEmpty { Languages.defaultTarget(this) }
    private fun sameLang(a: String, b: String) = a.substringBefore('-').equals(b.substringBefore('-'), ignoreCase = true)

    /** Height of the suggestion strip the dock grows out of and shrinks back into. */
    private val trStripH get() = 42.dp
    /** Shell height of the translate dock: roughly two key rows, above the full keyboard. */
    private val trDockH get() = 112.dp
    /** Spring of the language picker morph (the dock itself no longer animates its height: see [playDockEnter]). */
    private val trSpring = SpringInterpolator(0.96f, 19f)
    // stagger of the dock's parts when it opens (ms after the morph starts): the row left to right, then the result card
    private val trPartStart = 40L
    private val trPartStep = 22L
    private val trCardStart = 100L

    /** Translate docks above the keyboard inside the shell (the keys stay usable, the translation follows what is typed). Never in password fields. */
    private fun startTranslate() {
        if (!Prefs.bool(this, "translate", false) || isPasswordField() || translateMode) return
        trAnim?.cancel()
        // only the strip turns into the dock: the keys never blur, so the shell does not flash
        val r = root
        val old = strip
        val canAnimate = r != null && old != null && r.isLaidOut && panel == Panel.NONE && !emojiMode && animationsOn(this)
        val ghost = if (canAnimate && old != null) snapshot(old) else null
        val rect = if (ghost != null && old != null) android.graphics.Rect(old.left, old.top, old.right, old.bottom) else null
        translateMode = true
        trSrcPick = "auto"; trTgtPick = null; trSwapTurns = 0
        trState = TrState.IDLE; trText = null; trSrc = null; trMsg = ""
        val extra = (trDockH - trStripH).coerceAtLeast(0)
        if (ghost != null && rect != null && extra > 0) {
            // the window takes its final size at once; the shell's top edge starts [extra] px down and rises (playDockEnter).
            // The window blur would cover the whole window, so it is switched off first and the fill firms up over it.
            fadeBlur(0f, fillK, 0L)
            fadeBlur(0f, 1f, 100L)
            setInsetHold(extra)
            setShellTop(extra)
        }
        render()
        requestTranslate()
        if (ghost != null && rect != null) playDockEnter(ghost, rect, extra)
    }

    /**
     * Closing: the window shrinks at once (the glass is the window, so blur and glass shrink together and the app's
     * inset changes once). The dock has no exit animation of its own: it is simply gone, and the strip arrives in
     * its place with Blur Replace.
     */
    private fun stopTranslate() {
        if (!translateMode) return
        val r = root
        val dock = trDock
        var bmp: android.graphics.Bitmap? = null
        var rect: android.graphics.Rect? = null
        var extra = 0
        val animate = r != null && dock != null && dock.isLaidOut && dock.height > 0 && animationsOn(this)
        if (animate && dock != null) {
            bmp = snapshot(dock)
            rect = android.graphics.Rect(dock.left, dock.top, dock.right, dock.bottom)
            extra = (dock.height - trStripH).coerceAtLeast(0)
        }
        stopTranslateState()
        val morph = bmp != null && rect != null && extra > 0
        if (morph) {
            // the window keeps its tall size until the shell has sunk back onto the strip (a spacer, see playDockExit); the
            // window blur covers the whole window, so it is off meanwhile. The app is let down to the strip's edge at once.
            fadeBlur(0f, fillK, 0L)
            fadeBlur(0f, 1f, 100L)
            setInsetHold(extra)
        }
        render()
        if (morph && bmp != null && rect != null) playDockExit(bmp, rect, extra)
        else { bmp?.recycle(); if (animate) playStripEnter() }
    }

    /** State only (no rebuild): used when the keyboard is torn down or another panel takes over the strip. */
    private fun stopTranslateState() {
        translateMode = false
        trSeq++
        trAnim?.cancel(); trAnim = null
        setShellTop(0); setInsetHold(0)
        fadeBlur(1f, 0f, 0L)                  // closing: the window shrinks at once, so the blur may be whole again right away
        trHandler.removeCallbacks(trDebounce)
        trPopup?.dismiss(); trPopup = null
        dismissMenu()
    }

    private fun snapshot(v: View): android.graphics.Bitmap? {
        if (v.width <= 0 || v.height <= 0) return null
        return runCatching {
            val bmp = android.graphics.Bitmap.createBitmap(v.width, v.height, android.graphics.Bitmap.Config.ARGB_8888)
            v.draw(android.graphics.Canvas(bmp))
            bmp
        }.getOrNull()
    }

    private fun ghostOf(bmp: android.graphics.Bitmap, rect: android.graphics.Rect) = android.widget.ImageView(themed).apply {
        setImageBitmap(bmp); scaleType = android.widget.ImageView.ScaleType.FIT_XY
        layout(rect.left, rect.top, rect.right, rect.bottom)
    }

    /**
     * Opening: the window and the dock take their full size at once (ONE resize: animating the window's height every
     * frame made the window manager apply sizes a frame late and the keyboard glitch). The growth is drawn instead: the
     * shell's top edge ([setShellTop]: glass and clip) rises from [extra] px down to 0 on [trSpring] while the dock's
     * content slides up with it; the strip's picture blurs away and the dock's parts arrive from blur. Nothing here
     * changes layout. The window blur is off meanwhile (see [fadeBlur]) and eases back once the shell has arrived.
     */
    private fun playDockEnter(bmp: android.graphics.Bitmap, rect: android.graphics.Rect, extra: Int) {
        val r = root
        val dock = trDock
        if (r == null || dock == null) { bmp.recycle(); setShellTop(0); setInsetHold(0); fadeBlur(1f, 0f, 0L); return }
        val parts = trParts
        val content = trContent
        val ghost = ghostOf(bmp, rect)
        val delays = parts.mapIndexed { i, _ -> if (i == parts.lastIndex) trCardStart else trPartStart + i * trPartStep }
        val spring = trSpring
        val total = maxOf(spring.settleMs, BlurTextTokens.OUT_MS, (delays.maxOrNull() ?: 0L) + BlurTextTokens.IN_MS)
        parts.forEach { it.blurFx(1f, BlurDir.UP, 1) }
        ghost.layout(rect.left, rect.top + extra, rect.right, rect.bottom + extra)
        r.overlay.add(ghost)
        trAnim = android.animation.ValueAnimator.ofFloat(0f, 1f).apply {
            duration = total
            interpolator = android.view.animation.LinearInterpolator()
            addUpdateListener {
                val t = it.animatedFraction * total
                val p = spring.getInterpolation((t / spring.settleMs).coerceIn(0f, 1f))
                val g = (extra * (1f - p)).toInt().coerceAtLeast(0)
                if (g != shellTop) {
                    setShellTop(g)
                    content?.translationY = g.toFloat()
                    ghost.layout(rect.left, rect.top + g, rect.right, rect.bottom + g)
                }
                ghost.blurFx(BlurTextTokens.outEase.getInterpolation((t / BlurTextTokens.OUT_MS).coerceIn(0f, 1f)), BlurDir.UP, -1)
                parts.forEachIndexed { i, v ->
                    val x = ((t - delays[i]) / BlurTextTokens.IN_MS).coerceIn(0f, 1f)
                    v.blurFx(1f - BlurTextTokens.inEase.getInterpolation(x), BlurDir.UP, 1)
                }
            }
            addListener(object : android.animation.AnimatorListenerAdapter() {     // also runs when cancelled
                override fun onAnimationEnd(a: android.animation.Animator) {
                    r.overlay.remove(ghost); bmp.recycle()
                    parts.forEach { it.blurFx(0f, BlurDir.UP, 1) }
                    setShellTop(0)
                    content?.translationY = 0f
                    setInsetHold(0)
                    fadeBlur(1f, 0f, 240L)      // window = glass again: the blur may come back, the firm fill gives way to it
                    if (trAnim === a) trAnim = null
                }
            })
            start()
        }
    }

    /**
     * Closing, the opening played backwards: the dock's picture blurs away downward while the strip arrives from blur,
     * and the shell's top edge sinks back onto the strip (drawn, not laid out). A spacer (the dock's extra height) keeps
     * the window at its tall size for the whole animation; it goes at the end, so the window shrinks ONCE, exactly
     * where the edge already is (no pop). The window blur eases back afterwards, like after opening.
     */
    private fun playDockExit(bmp: android.graphics.Bitmap, rect: android.graphics.Rect, extra: Int) {
        val r = root
        if (r == null) { bmp.recycle(); setInsetHold(0); fadeBlur(1f, 0f, 0L); return }
        val s = strip
        val ghost = ghostOf(bmp, rect)
        val spacer = View(themed)
        r.addView(spacer, 0, LinearLayout.LayoutParams(MATCH_PARENT, extra))
        s?.blurFx(1f, BlurDir.DOWN, 1)
        r.overlay.add(ghost)
        val spring = trSpring
        val total = maxOf(spring.settleMs, BlurTextTokens.TOTAL_MS)
        trAnim = android.animation.ValueAnimator.ofFloat(0f, 1f).apply {
            duration = total
            interpolator = android.view.animation.LinearInterpolator()
            addUpdateListener {
                val t = it.animatedFraction * total
                val p = spring.getInterpolation((t / spring.settleMs).coerceIn(0f, 1f))
                val g = (extra * p).toInt().coerceIn(0, extra)
                if (g != shellTop) {
                    setShellTop(g)
                    ghost.layout(rect.left, rect.top + g, rect.right, rect.bottom + g)
                    ghost.clipBounds = android.graphics.Rect(0, 0, rect.width(), (rect.height() - g).coerceAtLeast(0))
                }
                ghost.blurFx(BlurTextTokens.outEase.getInterpolation((t / BlurTextTokens.OUT_MS).coerceIn(0f, 1f)), BlurDir.DOWN, -1)
                val x = ((t - BlurTextTokens.IN_DELAY_MS) / BlurTextTokens.IN_MS).coerceIn(0f, 1f)
                s?.blurFx(1f - BlurTextTokens.inEase.getInterpolation(x), BlurDir.DOWN, 1)
            }
            addListener(object : android.animation.AnimatorListenerAdapter() {     // also runs when cancelled
                override fun onAnimationEnd(a: android.animation.Animator) {
                    r.overlay.remove(ghost); bmp.recycle()
                    (spacer.parent as? ViewGroup)?.removeView(spacer)
                    s?.blurFx(0f, BlurDir.DOWN, 1)
                    setShellTop(0)
                    setInsetHold(0)
                    fadeBlur(1f, 0f, 240L)
                    if (trAnim === a) trAnim = null
                }
            })
            start()
        }
    }

    /** Closing: the strip (already laid out in the shrunken window) arrives from blur, coming down into place. */
    private fun playStripEnter() {
        val s = strip ?: return
        s.blurFx(1f, BlurDir.DOWN, 1)
        trAnim = android.animation.ValueAnimator.ofFloat(0f, 1f).apply {
            duration = BlurTextTokens.TOTAL_MS
            interpolator = android.view.animation.LinearInterpolator()
            addUpdateListener {
                val t = it.animatedFraction * BlurTextTokens.TOTAL_MS
                val x = ((t - BlurTextTokens.IN_DELAY_MS) / BlurTextTokens.IN_MS).coerceIn(0f, 1f)
                s.blurFx(1f - BlurTextTokens.inEase.getInterpolation(x), BlurDir.DOWN, 1)
            }
            addListener(object : android.animation.AnimatorListenerAdapter() {     // also runs when cancelled
                override fun onAnimationEnd(a: android.animation.Animator) {
                    s.blurFx(0f, BlurDir.DOWN, 1)
                    if (trAnim === a) trAnim = null
                }
            })
            start()
        }
    }

    /** The whole text of the field being typed in (extracted text first, text around the cursor as a fallback). */
    private fun fieldText(): String {
        val ic = currentInputConnection ?: return ""
        val ex = ic.getExtractedText(android.view.inputmethod.ExtractedTextRequest(), 0)?.text?.toString()
        if (!ex.isNullOrEmpty()) return ex
        return (ic.getTextBeforeCursor(1500, 0)?.toString().orEmpty()) + (ic.getTextAfterCursor(500, 0)?.toString().orEmpty())
    }

    private fun requestTranslate(swapped: Boolean = false) {
        if (!translateMode) return
        val text = fieldText().trim()
        if (text.isEmpty()) {
            trSeq++
            trState = TrState.IDLE; trText = null; trMsg = "Type something to translate"
            updateTranslateDock(swapped); return
        }
        trState = TrState.LOADING
        updateTranslateDock(swapped)
        val seq = ++trSeq
        val target = trTarget()
        val src = trSrcPick
        trExec.execute {
            val outcome = runCatching {
                var tgt = target
                var r = Translator.translate(this, text, tgt, src)
                // detecting, and already written in the target language: translate to English instead of echoing it back
                val det = r.detected
                if (src == "auto" && det != null && sameLang(det, tgt) && !sameLang("en", tgt)) {
                    tgt = "en"; r = Translator.translate(this, text, tgt, src)
                }
                Triple(r.text, r.detected ?: src.takeIf { it != "auto" }, tgt)
            }
            trHandler.post {
                if (seq != trSeq || !translateMode) return@post
                outcome.onSuccess { (out, det, tgt) ->
                    trText = out; trSrc = det; trShown = tgt; trState = TrState.OK
                }.onFailure { e ->
                    trState = TrState.ERROR
                    trMsg = when (e) {
                        is java.net.UnknownHostException, is java.net.SocketTimeoutException, is java.net.ConnectException -> "No connection"
                        else -> e.message?.takeIf { it.length < 60 } ?: "Couldn't translate"
                    }
                }
                updateTranslateDock()
            }
        }
    }

    /** Replace the field's text with the translation, then go back to the normal word bar. */
    private fun applyTranslation() {
        val out = trText ?: return
        val ic = currentInputConnection ?: return
        val len = fieldText().length
        ic.beginBatchEdit()
        ic.setSelection(0, len)
        ic.commitText(out, 1)
        ic.endBatchEdit()
        lastFix = null
        stopTranslate()
        afterEdit()
    }

    /**
     * The translate dock: ✕, source chip, swap, target chip and "Replace" on top, the translation on a key-coloured card
     * below (tap it to replace). Typing keeps updating it. The dock is a box ([trDock]) that clips a content block of
     * the full height, pinned to the top: while the box is still short the card is simply not revealed yet.
     */
    private fun buildTranslateDock(): LinearLayout {
        val medium = ResourcesCompat.getFont(themed, R.font.gsr_medium)
        val dimText = androidx.core.graphics.ColorUtils.setAlphaComponent(tokens.keyText, 0xB3)
        val close = TextView(themed).apply {
            text = "\u2715"; textSize = 16f; gravity = Gravity.CENTER
            setTextColor(dimText)
            contentDescription = "Close translate"
            background = tokens.stripItemBackground()
            setOnClickListener { stopTranslate() }
        }
        val src = langChip("Source language") { showLangPicker(it, forSource = true) }
        val tgt = langChip("Target language") { showLangPicker(it, forSource = false) }
        trSrcChip = src; trTgtChip = tgt
        val swap = android.widget.ImageView(themed).apply {
            setImageResource(R.drawable.ic_swap)
            imageTintList = android.content.res.ColorStateList.valueOf(tokens.keyText)
            scaleType = android.widget.ImageView.ScaleType.CENTER_INSIDE
            setPadding(9.dp, 9.dp, 9.dp, 9.dp); imageAlpha = 220
            rotation = 180f * trSwapTurns
            contentDescription = "Swap languages"
            background = tokens.stripItemBackground()
            setOnClickListener { swapLangs() }
        }
        trSwap = swap
        val act = TextView(themed).apply {
            text = "Replace"; textSize = 14f; gravity = Gravity.CENTER
            setPadding(14.dp, 0, 14.dp, 0); setTextColor(tokens.keyText)
            if (medium != null) typeface = medium
            background = tokens.stripItemBackground()
            setOnClickListener { applyTranslation() }
        }
        trAction = act
        // each part sits in a holder: the arrival effect owns the holder's alpha, so a dimmed state (swap, Replace) stays on the part itself
        fun holder(v: View) = FrameLayout(themed).apply { addView(v, FrameLayout.LayoutParams(MATCH_PARENT, MATCH_PARENT)) }
        val closeH = holder(close)
        val swapH = holder(swap)
        val actH = holder(act)
        val row = LinearLayout(themed).apply {
            orientation = LinearLayout.HORIZONTAL; isBaselineAligned = false
            addView(closeH, LinearLayout.LayoutParams(40.dp, MATCH_PARENT))
            addView(src, LinearLayout.LayoutParams(0, MATCH_PARENT, 1f))
            addView(swapH, LinearLayout.LayoutParams(38.dp, MATCH_PARENT))
            addView(tgt, LinearLayout.LayoutParams(0, MATCH_PARENT, 1f))
            addView(actH, LinearLayout.LayoutParams(WRAP_CONTENT, MATCH_PARENT))
        }
        // the result: a key-coloured card like the clipboard's items; the corners clip the scrolling text
        val rad = Prefs.keyRadius(this).dp.toFloat()
        val res = BlurSwapText(themed, false) {
            TextView(themed).apply {
                textSize = 15.5f
                setPadding(12.dp, 8.dp, 12.dp, 8.dp)
                setTextColor(tokens.keyText)
            }
        }.apply {
            background = android.graphics.drawable.StateListDrawable().apply {
                addState(intArrayOf(android.R.attr.state_pressed), android.graphics.drawable.ColorDrawable(tokens.pressed))
                addState(intArrayOf(), android.graphics.drawable.ColorDrawable(tokens.key))
            }
            setOnClickListener { applyTranslation() }
        }
        trResult = res
        val card = android.widget.ScrollView(themed).apply {
            isVerticalScrollBarEnabled = false; overScrollMode = View.OVER_SCROLL_NEVER; isFillViewport = true
            outlineProvider = object : android.view.ViewOutlineProvider() {
                override fun getOutline(v: View, outline: android.graphics.Outline) {
                    outline.setRoundRect(0, 0, v.width, v.height, rad)
                }
            }
            clipToOutline = true
            addView(res, FrameLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT))
        }
        val content = LinearLayout(themed).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(3.dp, 3.dp, 3.dp, 3.dp)
            addView(row, LinearLayout.LayoutParams(MATCH_PARENT, 36.dp))
            addView(card, LinearLayout.LayoutParams(MATCH_PARENT, 0, 1f).apply { topMargin = 3.dp })
        }
        trParts = listOf(closeH, src, swapH, tgt, actH, card)
        trContent = content
        content.translationY = shellTop.toFloat()
        val dock = LinearLayout(themed).apply {
            orientation = LinearLayout.VERTICAL
            clipChildren = true
            layoutParams = LinearLayout.LayoutParams(MATCH_PARENT, trDockH).apply { setMargins(3.dp, 0, 3.dp, 3.dp) }
            addView(content, LinearLayout.LayoutParams(MATCH_PARENT, trDockH))
        }
        trDock = dock
        updateTranslateDock()
        return dock
    }

    /** A language button: the language's name, one line (two when the source is auto-detected: a small "Auto" over the name). */
    private fun langChip(desc: String, onClick: (View) -> Unit) = BlurSwapText(themed, true) {
        TextView(themed).apply {
            textSize = 13.5f; gravity = Gravity.CENTER; maxLines = 2
            ellipsize = android.text.TextUtils.TruncateAt.END
            setPadding(8.dp, 0, 8.dp, 0)
            setTextColor(tokens.keyText)
            ResourcesCompat.getFont(themed, R.font.gsr_medium)?.let { typeface = it }
        }
    }.apply {
        contentDescription = desc
        background = tokens.stripItemBackground()
        setOnClickListener { onClick(this) }
    }

    /** The language the text is in now: the one picked, or the one detected (null until known). */
    private fun effSource(): String? = if (trSrcPick != "auto") trSrcPick else trSrc

    /** The language it is being translated to now (a retarget to English shows here). */
    private fun effTarget(): String = if (trState == TrState.OK) trShown else trTarget()

    /** Swap the two languages for this session; needs a known source (picked or detected). The icon turns, the names trade places. */
    private fun swapLangs() {
        val s = effSource() ?: return
        val t = effTarget()
        trSrcPick = t; trTgtPick = s
        trText = null
        trSwapTurns++
        val icon = trSwap
        if (icon != null) {
            if (animationsOn(this)) {
                icon.animate().rotation(180f * trSwapTurns).setDuration(tokens.releaseMs).setInterpolator(tokens.spring).start()
            } else {
                icon.rotation = 180f * trSwapTurns
            }
        }
        requestTranslate(swapped = true)
    }

    /**
     * Every change in the dock is a Blur Replace: the chips' names, the result text (and its dimming while it loads).
     * On a swap the two names travel toward each other's side (the source's comes in from the right, the target's from the left).
     */
    private fun updateTranslateDock(swapped: Boolean = false) {
        val res = trResult ?: return
        val src = effSource()
        trSrcChip?.let { c ->
            val name = src?.let { Languages.nameOf(this, it) }.orEmpty()
            val label: CharSequence = when {
                trSrcPick == "auto" && name.isNotEmpty() -> android.text.SpannableString("Auto\n$name").also {
                    it.setSpan(android.text.style.RelativeSizeSpan(0.72f), 0, 4, android.text.Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
                }
                trSrcPick == "auto" -> "Detect language"
                else -> name
            }
            c.set(label, if (swapped) BlurDir.LEFT else BlurDir.UP)
        }
        trTgtChip?.set(Languages.nameOf(this, effTarget()), if (swapped) BlurDir.RIGHT else BlurDir.UP)
        trSwap?.fadeTo(if (src != null) 1f else 0.4f)
        val shown = trText
        trAction?.fadeTo(if (trState == TrState.OK && shown != null) 1f else 0.4f)
        when {
            trState == TrState.OK && shown != null -> { res.set(shown); res.dim(1f); res.contentDescription = "Replace text with translation" }
            trState == TrState.LOADING -> { res.set(shown ?: "Translating\u2026"); res.dim(0.5f) }
            else -> { res.set(trMsg.ifEmpty { "Type something to translate" }); res.dim(0.55f) }
        }
    }

    /**
     * Language list above the dock; system languages first. The source list also offers "Detect language".
     * It opens as a container morph out of the chip that was tapped (the same morph as the key popups) and leaves with a short fade.
     */
    private fun showLangPicker(anchor: View, forSource: Boolean) {
        trPopup?.dismiss()
        val langs = Languages.ordered(this)
        val cur = if (forSource) trSrcPick else effTarget()
        val col = LinearLayout(themed).apply { orientation = LinearLayout.VERTICAL }
        val sv = android.widget.ScrollView(themed).apply { isVerticalScrollBarEnabled = false; addView(col) }
        val itemH = 44.dp
        val w = 232.dp
        val h = 7 * itemH + 12.dp
        val box = FrameLayout(themed).apply {
            background = tokens.popupBackground(); elevation = tokens.popupElevation
            setPadding(6.dp, 6.dp, 6.dp, 6.dp)
            addView(sv)
        }
        val pop = PopupWindow(box, w, h, false).apply { isOutsideTouchable = true }
        fun add(label: String, code: String) {
            val on = code == cur
            col.addView(TextView(themed).apply {
                text = if (on) "$label   \u2713" else label
                textSize = 16f; gravity = Gravity.CENTER_VERTICAL; maxLines = 1
                setPadding(14.dp, 0, 14.dp, 0)
                setTextColor(tokens.keyText)
                background = tokens.stripItemBackground()
                setOnClickListener { closePicker(pop); pickLang(code, forSource) }
            }, LinearLayout.LayoutParams(MATCH_PARENT, itemH))
        }
        if (forSource) add("Detect language", "auto")
        langs.forEach { add(it.name, it.code) }
        trPopup = pop
        val loc = IntArray(2); anchor.getLocationOnScreen(loc)
        val win = IntArray(2); anchor.getLocationInWindow(win)
        val ox = loc[0] - win[0]; val oy = loc[1] - win[1]
        val screenW = resources.displayMetrics.widthPixels
        val x = loc[0].coerceIn(4.dp, (screenW - w - 4.dp).coerceAtLeast(4.dp))
        val y = (loc[1] - h - 4.dp).coerceAtLeast(0)
        pop.showAtLocation(anchor, Gravity.NO_GRAVITY, x - ox, y - oy)
        if (animationsOn(this)) {
            // from the chip (just below the popup's bottom edge) to the whole list; the list fades in once the box is part open
            val cl = (loc[0] - x).toFloat()
            val from = RoundRectState(cl, (h + 4.dp - anchor.height).toFloat(), cl + anchor.width, (h + 4.dp).toFloat(), tokens.popupRadius)
            val to = RoundRectState(0f, 0f, w.toFloat(), h.toFloat(), tokens.popupRadius)
            sv.alpha = 0f
            morphContainer(box, from, to, MorphPresets.container(tokens), onProgress = { f ->
                sv.alpha = ((f - MorphTokens.CONTAINER_FADE_FROM) / MorphTokens.CONTAINER_FADE_SPAN).coerceIn(0f, 1f)
            })
        }
        trHandler.postDelayed({ if (trPopup === pop) { pop.dismiss(); trPopup = null } }, 8000)
    }

    private fun closePicker(pop: PopupWindow) {
        if (trPopup === pop) trPopup = null
        val v = pop.contentView
        if (v == null || !pop.isShowing || !animationsOn(this)) { pop.dismiss(); return }
        v.pivotX = v.width / 2f; v.pivotY = v.height.toFloat()      // shrinks back toward the dock
        v.animate().alpha(0f).scaleX(0.96f).scaleY(0.96f).setDuration(tokens.fadeOutMs + 50L)
            .withEndAction { pop.dismiss() }.start()
    }

    /** A language was chosen. Picking the other side's language swaps them instead of translating a language into itself. */
    private fun pickLang(code: String, forSource: Boolean) {
        val oldSrc = effSource()
        val oldTgt = effTarget()
        if (forSource) {
            trSrcPick = code
            if (code != "auto" && sameLang(code, oldTgt)) {
                trTgtPick = oldSrc?.takeIf { !sameLang(it, code) } ?: if (sameLang("en", code)) "id" else "en"
            }
        } else {
            Prefs.setStr(this, "tr_target", code); trTgtPick = null
            if (trSrcPick != "auto" && sameLang(trSrcPick, code)) trSrcPick = oldTgt
        }
        trText = null
        requestTranslate()
    }


    // ---- strip button (top-right): a feature slot. tap = run it · swipe up/down = switch feature · hold = all features ----
    private fun View.attachGestures(onLong: (View) -> Unit, onSwipe: (up: Boolean) -> Unit) {
        val slop = android.view.ViewConfiguration.get(context).scaledTouchSlop
        val swipeDist = 22.dp
        var x0 = 0f; var y0 = 0f
        var moved = false; var done = false
        val longRun = Runnable {
            if (done) return@Runnable
            done = true; isPressed = false
            keyHaptic(android.view.HapticFeedbackConstants.LONG_PRESS)
            onLong(this)
        }
        setOnTouchListener { v, e ->
            when (e.actionMasked) {
                android.view.MotionEvent.ACTION_DOWN -> {
                    x0 = e.x; y0 = e.y; moved = false; done = false
                    v.isPressed = true
                    v.postDelayed(longRun, android.view.ViewConfiguration.getLongPressTimeout().toLong())
                }
                android.view.MotionEvent.ACTION_MOVE -> if (!done) {
                    val dx = e.x - x0; val dy = e.y - y0
                    if (Math.abs(dx) > slop || Math.abs(dy) > slop) { moved = true; v.removeCallbacks(longRun) }
                    if (Math.abs(dy) >= swipeDist && Math.abs(dy) > Math.abs(dx)) {   // vertical only
                        done = true; v.isPressed = false
                        v.keyHaptic(android.view.HapticFeedbackConstants.KEYBOARD_TAP)
                        onSwipe(dy < 0)
                    }
                }
                android.view.MotionEvent.ACTION_UP -> {
                    v.removeCallbacks(longRun); v.isPressed = false
                    if (!done && !moved) v.performClick()
                }
                android.view.MotionEvent.ACTION_CANCEL -> { v.removeCallbacks(longRun); v.isPressed = false }
            }
            true
        }
    }

    private fun runFeature(f: StripFeature) = when (f) {
        StripFeature.TRANSLATE -> startTranslate()
        StripFeature.CLIPBOARD -> openPanel(Panel.CLIPBOARD)
        StripFeature.TEXT_EDIT -> openPanel(Panel.EDIT)
        StripFeature.EMOJI -> setEmoji(true)
        StripFeature.SETTINGS -> openApp()
    }

    private fun setFeature(f: StripFeature, dir: BlurDir, animate: Boolean = true) {
        Prefs.setStr(this, "strip_feature", f.id)
        stripIcon?.set(f.icon, dir, animate)
        stripIcon?.contentDescription = f.label
    }

    /** Swipe up = next feature (icon blurs upward), swipe down = previous (icon blurs downward). */
    private fun cycleFeature(up: Boolean) {
        val l = StripFeature.available(this)
        if (l.size < 2) return
        val i = l.indexOf(StripFeature.current(this)).coerceAtLeast(0)
        setFeature(l[(i + if (up) 1 else l.size - 1) % l.size], if (up) BlurDir.UP else BlurDir.DOWN)
    }

    private fun openApp() {
        startActivity(android.content.Intent(this, SettingsActivity::class.java).addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK))
    }

    // ---- the strip button's capsule ----
    // Hold on the button: the suggestion strip itself turns into a row of features. A key-coloured capsule grows out of the
    // button to the left until it fills the strip (container morph on the dock's spring), the icons arrive with Blur Replace,
    // nearest the button first. Nothing leaves the shell: no headroom, no taller window, so the window blur is never touched.
    private var menuF = 0f                       // how far open (0 = the button, 1 = the whole strip): a close can start mid-open
    private var menuPage = 0                     // 0 = features, 1 = quick tools (behind the "+")
    private var menuBusyUntil = 0L               // a page change is still running: ignore taps that would stack a second one
    private var menuAfter: (() -> Unit)? = null  // runs once the capsule is gone (an action that rebuilds the keyboard)
    private var menuActive: BlurIconView? = null // the cell over the strip button

    /** The capsule's key-coloured fill fades in over the first third of the morph (it starts as the button, so it must not pop in). */
    private fun capsuleFill(x: Float) = ((x / 0.35f).coerceIn(0f, 1f) * 255f).toInt()

    private var menuUnder: List<View> = emptyList()   // what the capsule frosts: the suggestion slots (and the paste chip) under it
    private var menuSmearR = -1f

    /**
     * The capsule is frosted glass over the strip: the words under it are blurred (a RenderEffect on the slots, API 31+) until
     * they are only a smear of colour that follows the capsule's translucent fill. [k] 0 = sharp, 1 = fully frosted.
     * Below API 31 the words just fade. The strip's own Blur Replace runs inside the text views, so this does not fight it.
     */
    private fun smear(k: Float) {
        val c = k.coerceIn(0f, 1f)
        brandChip?.alpha = 1f - c      // plain fade, no blur: a solid dot blurred inside its own view bounds is cut into a box
        if (Build.VERSION.SDK_INT >= 31) {
            val r = kotlin.math.round(22.dp * c / 1.5f) * 1.5f
            if (r == menuSmearR) return
            menuSmearR = r
            val fx = if (r > 0f) android.graphics.RenderEffect.createBlurEffect(r, r, android.graphics.Shader.TileMode.CLAMP) else null
            menuUnder.forEach { it.setRenderEffect(fx) }
        } else {
            menuUnder.forEach { it.alpha = 1f - 0.7f * c }
        }
    }

    private fun stopMenuMorph() {
        menuMorph?.let { it.removeAllListeners(); it.cancel() }
        menuMorph = null
    }

    private fun clearMenu() {
        trHandler.removeCallbacks(menuAutoClose)
        stopMenuMorph()
        smear(0f); menuUnder = emptyList(); menuSmearR = -1f
        menuLayer?.let { host?.removeView(it) }
        menuLayer = null; menuIcons = emptyList(); menuActive = null; menuClosing = false; menuFrom = null; menuTo = null
        menuF = 0f; menuPage = 0
        stripIcon?.alpha = 1f
        host?.menuOpen = false
        val a = menuAfter; menuAfter = null
        if (a != null) trHandler.post(a)           // posted: the caller may be in the middle of render()
    }

    /**
     * Close: the same morph run backwards from wherever it is. The icons blur out toward the button, the capsule shrinks
     * back into it and the strip is there again. [after] runs when it is gone.
     */
    private fun dismissMenu(animated: Boolean = false, after: (() -> Unit)? = null) {
        if (after != null) menuAfter = after
        val layer = menuLayer
        val from = menuFrom; val to = menuTo
        if (layer == null) { val a = menuAfter; menuAfter = null; if (a != null) trHandler.post(a); return }
        if (!animated || !animationsOn(this) || from == null || to == null) { clearMenu(); return }
        if (menuClosing) return
        menuClosing = true
        trHandler.removeCallbacks(menuAutoClose)
        stopMenuMorph()
        menuIcons.forEachIndexed { i, v -> v.fadeOut(delay = i * 20L, dir = BlurDir.RIGHT) }
        if (menuPage == 1) menuActive?.set(StripFeature.current(this).icon, BlurDir.RIGHT)   // the button's own icon again
        val start = RoundRectState().also { it.lerp(from, to, menuF.coerceIn(0f, 1f)) }
        val startF = menuF.coerceIn(0f, 1f)
        val bg = layer.background
        menuMorph = morphContainer(layer, start, from, MorphPreset(220L, BlurTextTokens.outEase),
            onProgress = { f ->
                bg?.alpha = capsuleFill(1f - f)
                menuF = startF * (1f - f)
                smear(menuF)
            },
            onEnd = { clearMenu() })
    }

    /** Hold on the strip button. */
    private fun showStripMenu(anchor: View) {
        dismissMenu()
        val h0 = host ?: return
        val s = strip ?: return
        if (!s.isLaidOut || !anchor.isAttachedToWindow) return
        val feats = StripFeature.available(this)
        val cur = StripFeature.current(this)
        val rotated = feats.indexOf(cur).let { i -> feats.drop(i) + feats.take(i) }     // active first
        val pad = 3.dp

        // the strip's rectangle in host coordinates: the capsule's final shape
        val sl = IntArray(2); s.getLocationInWindow(sl)
        val hl = IntArray(2); h0.getLocationInWindow(hl)
        val sx = sl[0] - hl[0]; val sy = sl[1] - hl[1]
        val sw = s.width; val sh = s.height
        val al = IntArray(2); anchor.getLocationInWindow(al)
        val bx = (al[0] - hl[0] - sx).toFloat(); val by = (al[1] - hl[1] - sy).toFloat()

        // frosted glass: lighter toward the left, where the words under it dissolve into colour, firmer by the button
        // Liquid Glass: frosted gradient pill. Every other theme: a flat bar in the theme's key colour with its own outline, shaped like the strip
        val fill = if (tokens.isGlass) android.graphics.drawable.GradientDrawable(
            android.graphics.drawable.GradientDrawable.Orientation.LEFT_RIGHT,
            intArrayOf(androidx.core.graphics.ColorUtils.setAlphaComponent(tokens.key, 0x4D),
                androidx.core.graphics.ColorUtils.setAlphaComponent(tokens.key, 0xB8)))
        else android.graphics.drawable.GradientDrawable().apply {
            setColor(androidx.core.graphics.ColorUtils.setAlphaComponent(tokens.key, 0xF2))
            if (android.graphics.Color.alpha(tokens.stripStroke) > 0) setStroke(2.dp, tokens.stripStroke)
        }
        val layer = FrameLayout(themed).apply {
            background = fill
            isClickable = true                  // a tap between two cells must not reach the suggestions underneath
            setPadding(pad, pad, pad, pad)
        }
        fun cell(res: Int, label: String, action: () -> Unit): BlurIconView = BlurIconView(themed, tokens.keyText, 0.9f).apply {
            background = tokens.stripItemBackground()
            contentDescription = label
            setOnClickListener { if (!menuClosing) action() }
            set(res, animate = false)
        }
        fun group(cells: List<View>) = LinearLayout(themed).apply {
            orientation = LinearLayout.HORIZONTAL
            cells.forEach { addView(it, LinearLayout.LayoutParams(0, MATCH_PARENT, 1f)) }
        }

        // page 1: "+", the other features, the active one over the button. Page 2 (behind the "+"): quick tools.
        val active = cell(cur.icon, cur.label) { if (menuPage == 1) goMenuPage(0, cur) else dismissMenu(true) }
        val plus = cell(R.drawable.ic_add, "More tools") { goMenuPage(1, cur) }
        val restCells = rotated.drop(1).reversed().map { f ->
            cell(f.icon, f.label) { setFeature(f, BlurDir.UP, animate = false); active.set(f.icon, BlurDir.UP); dismissMenu(true) }
        }
        val tools = ArrayList<BlurIconView>()
        tools += cell(R.drawable.ic_select_all, "Select all") { capsuleEdit(android.R.id.selectAll) }
        tools += cell(R.drawable.ic_undo, "Undo") { capsuleEdit(android.R.id.undo) }
        tools += cell(R.drawable.ic_redo, "Redo") { capsuleEdit(android.R.id.redo) }
        tools += cell(R.drawable.ic_keyboard_hide, "Hide keyboard") { dismissMenu(true) { requestHideSelf(0) } }

        val g0 = group(listOf(plus) + restCells)
        val g1 = group(tools).apply { visibility = View.INVISIBLE }
        capGroups = arrayOf(g0, g1)
        capIcons = arrayOf(restCells.reversed() + plus, tools.reversed())      // nearest the button first
        val body = FrameLayout(themed).apply {
            addView(g0, FrameLayout.LayoutParams(MATCH_PARENT, MATCH_PARENT))
            addView(g1, FrameLayout.LayoutParams(MATCH_PARENT, MATCH_PARENT))
        }
        val row = LinearLayout(themed).apply {
            orientation = LinearLayout.HORIZONTAL
            addView(body, LinearLayout.LayoutParams(0, MATCH_PARENT, 1f))
            addView(active, LinearLayout.LayoutParams(anchor.width, MATCH_PARENT))     // exactly over the button
        }
        layer.addView(row, FrameLayout.LayoutParams(MATCH_PARENT, MATCH_PARENT))
        h0.addView(layer, FrameLayout.LayoutParams(sw, sh, Gravity.TOP or Gravity.START).apply { leftMargin = sx; topMargin = sy })

        menuUnder = listOfNotNull(s.getChildAt(0), s.getChildAt(2), s.getChildAt(4), clipChip)
        menuSmearR = -1f
        menuLayer = layer; menuActive = active; menuIcons = capIcons[0]; menuPage = 0; menuF = 0f
        val from = RoundRectState(bx, by, bx + anchor.width, by + anchor.height, minOf(tokens.stripRadius, anchor.height / 2f))
        val to = RoundRectState(0f, 0f, sw.toFloat(), sh.toFloat(), minOf(tokens.stripRadius, sh / 2f))
        menuFrom = from; menuTo = to
        h0.menuRect.set(sx, sy, sx + sw, sy + sh)
        h0.menuOpen = true
        h0.requestLayout()
        stripIcon?.alpha = 0f                    // the capsule's active cell is the button's icon now
        val settled = {
            layer.outlineProvider = object : android.view.ViewOutlineProvider() {
                override fun getOutline(v: View, outline: android.graphics.Outline) { outline.setRoundRect(0, 0, v.width, v.height, to.radius) }
            }
            layer.clipToOutline = true
            fill.alpha = 255
        }
        if (animationsOn(this)) {
            capIcons[0].forEachIndexed { i, v -> v.fadeIn(delay = 50L + i * 40L, dir = BlurDir.LEFT) }
            menuMorph = morphContainer(layer, from, to, MorphPreset(trSpring.settleMs, trSpring),
                onProgress = { f -> fill.alpha = capsuleFill(f); menuF = f; smear(f) },
                onEnd = { menuMorph = null; if (!menuClosing && menuLayer === layer) { menuF = 1f; settled() } })
        } else {
            menuF = 1f; settled(); smear(1f)
        }
        trHandler.postDelayed(menuAutoClose, 8000)
    }

    private var capGroups: Array<LinearLayout> = emptyArray()
    private var capIcons: Array<List<BlurIconView>> = emptyArray()

    /** "+" opens the quick tools in the same bar, the cell over the button becomes "back"; the same Blur Replace, sideways. */
    private fun goMenuPage(p: Int, cur: StripFeature) {
        if (menuClosing || menuPage == p || capGroups.size < 2) return
        val now = android.os.SystemClock.uptimeMillis()
        if (now < menuBusyUntil) return
        menuBusyUntil = now + 260L
        menuPage = p
        val dir = if (p == 1) BlurDir.LEFT else BlurDir.RIGHT
        val gone = 1 - p
        capIcons[gone].forEachIndexed { i, v ->
            v.fadeOut(delay = i * 20L, dir = dir) { if (menuPage == p) capGroups[gone].visibility = View.INVISIBLE }
        }
        capGroups[p].visibility = View.VISIBLE
        capIcons[p].forEachIndexed { i, v -> v.fadeIn(delay = 60L + i * 40L, dir = dir) }
        menuIcons = capIcons[p]
        menuActive?.set(if (p == 1) R.drawable.ic_arrow_back else cur.icon, dir)
        menuActive?.contentDescription = if (p == 1) "Back" else cur.label
        trHandler.removeCallbacks(menuAutoClose)
        trHandler.postDelayed(menuAutoClose, 8000)
    }

    /** Select all / undo / redo on the field being typed in; the strip follows through onUpdateSelection. */
    private fun capsuleEdit(id: Int) {
        currentInputConnection?.performContextMenuAction(id)
        dismissMenu(true)
    }

    // ---- emoji ----
    private fun actionName(): String = when (currentInputEditorInfo?.imeOptions?.and(EditorInfo.IME_MASK_ACTION) ?: 0) {
        EditorInfo.IME_ACTION_GO -> "Go"
        EditorInfo.IME_ACTION_SEARCH -> "Search"
        EditorInfo.IME_ACTION_SEND -> "Send"
        EditorInfo.IME_ACTION_DONE -> "Done"
        EditorInfo.IME_ACTION_NEXT -> "Next"
        else -> "return"
    }

    /** What the focused field wants, so the keys can follow: numbers get a numpad, e-mail and URL fields get their own keys. */
    private enum class Field { TEXT, NUMBER, EMAIL, URL }

    private fun fieldKind(): Field {
        if (!Prefs.bool(this, "fieldaware", true)) return Field.TEXT
        val t = currentInputEditorInfo?.inputType ?: return Field.TEXT
        return when (t and InputType.TYPE_MASK_CLASS) {
            InputType.TYPE_CLASS_NUMBER, InputType.TYPE_CLASS_PHONE, InputType.TYPE_CLASS_DATETIME -> Field.NUMBER
            InputType.TYPE_CLASS_TEXT -> when (t and InputType.TYPE_MASK_VARIATION) {
                InputType.TYPE_TEXT_VARIATION_EMAIL_ADDRESS, InputType.TYPE_TEXT_VARIATION_WEB_EMAIL_ADDRESS -> Field.EMAIL
                InputType.TYPE_TEXT_VARIATION_URI -> Field.URL
                else -> Field.TEXT
            }
            else -> Field.TEXT
        }
    }

    /** The character left of the 0 on a numpad: what the field can use besides digits. */
    private fun numpadSide(): String {
        val t = currentInputEditorInfo?.inputType ?: return ","
        return when (t and InputType.TYPE_MASK_CLASS) {
            InputType.TYPE_CLASS_PHONE -> "+"
            InputType.TYPE_CLASS_DATETIME -> if (t and InputType.TYPE_MASK_VARIATION == InputType.TYPE_DATETIME_VARIATION_TIME) ":" else "/"
            else -> when {
                t and InputType.TYPE_NUMBER_FLAG_DECIMAL != 0 -> "."
                t and InputType.TYPE_NUMBER_FLAG_SIGNED != 0 -> "-"
                else -> ","
            }
        }
    }

    private fun emojiBesideComma() = Prefs.bool(this, "emoji", true) && (Prefs.gboard(this) || Prefs.str(this, "emoji_pos", "capsule") == "comma")

    /** The "#+=" key and its "123" counterpart: flip between the 123 page and the third page of everyday symbols. */
    private fun setExtra(on: Boolean) {
        extra = on
        render(animateRows = true, dir = if (on) BlurDir.UP else BlurDir.DOWN)   // the page key stays ABC; only the rows change
    }

    /** Open/close the emoji panel. It settles in on the shared smooth ease (no spring). */
    private fun setEmoji(on: Boolean) {
        emojiGrid?.dismissTones()
        stopTranslateState()
        emojiMode = on; emojiSearch = false; query.setLength(0)
        if (on) emojiCat = if (EmojiData.recents(this).isEmpty()) 1 else 0
        symbols = false; extra = false
        autoCap()
        render()
        val r = root ?: return
        if (!animationsOn(this)) return
        val fresh = if (on) listOfNotNull(strip, emojiGrid) else (0 until r.childCount).map { r.getChildAt(it) }.filter { it !== strip && it !== r.getChildAt(r.childCount - 1) }
        for (v in fresh) {
            v.alpha = 0f; v.translationY = 8.dp.toFloat()
            v.animate().alpha(1f).translationY(0f).setDuration(BlurTextTokens.TOTAL_MS).setInterpolator(BlurTextTokens.inEase).start()
        }
    }

    private fun startEmojiSearch() {
        emojiGrid?.dismissTones()
        emojiSearch = true; query.setLength(0)
        autoCap()
        render()
    }

    private fun commitEmoji(e: String) {
        lastFix = null
        currentInputConnection?.commitText(e, 1)
        EmojiData.addRecent(this, e)
    }

    private fun emojiBottomRow(keyH: Int) = rowOf {
        pageKey = key("ABC", keyH, 1.35f, KeyStyle.SPECIAL) { setEmoji(false) }
        key("Search emoji", keyH, 1f, KeyStyle.SPECIAL, icon = R.drawable.ic_search) { startEmojiSearch() }
        key("space", keyH, 3.6f, KeyStyle.SPECIAL) { space() }
        key("Backspace", keyH, 1.5f, KeyStyle.SPECIAL, repeat = true, icon = R.drawable.ic_backspace) { backspace() }
        key(actionName(), keyH, 1.5f, KeyStyle.ACCENT) { enter() }
    }

    /** The grid takes exactly the height of the letter rows, so the keyboard never changes size when the panel opens. */
    private fun buildEmojiGrid(keyH: Int): View {
        val g = Prefs.keyGap(this)
        val m = 2 * (g + 2).dp
        val numRow = if (Prefs.bool(this, "numrow", false)) (keyH * 0.8f).toInt() + m else 0
        return EmojiGridView(themed, tokens).also { gv ->
            gv.layoutParams = LinearLayout.LayoutParams(MATCH_PARENT, 3 * (keyH + m) + numRow)
            gv.onPick = { commitEmoji(it) }
            emojiGrid = gv
            showCategory(gv)
        }
    }

    private fun showCategory(gv: EmojiGridView) {
        if (emojiCat == 0) {
            gv.emptyHint = "Recently used emoji show up here"
            gv.items = EmojiData.recents(this)
        } else gv.items = EmojiData.categories().getOrNull(emojiCat - 1)?.items ?: emptyList()
    }

    private fun buildEmojiTabs() = LinearLayout(themed).apply {
        orientation = LinearLayout.HORIZONTAL
        isBaselineAligned = false
        layoutParams = LinearLayout.LayoutParams(MATCH_PARENT, 42.dp).apply { setMargins(3.dp, 0, 3.dp, 3.dp) }
        setPadding(3.dp, 3.dp, 3.dp, 3.dp)
        val glyphs = listOf("\uD83D\uDD58") + EmojiData.categories().map { it.glyph }
        val names = listOf("Recent") + EmojiData.categories().map { it.name }
        emojiTabs = glyphs.mapIndexed { i, gl ->
            TextView(themed).apply {
                text = gl; textSize = 19f; gravity = Gravity.CENTER
                setTextColor(tokens.keyText)          // symbol tabs are plain text; emoji tabs ignore it
                contentDescription = names[i]
                setOnClickListener {
                    emojiCat = i
                    emojiGrid?.dismissTones()
                    emojiGrid?.let { g -> showCategory(g) }
                    styleEmojiTabs()
                }
            }.also { addView(it, LinearLayout.LayoutParams(0, MATCH_PARENT, 1f)) }
        }
        styleEmojiTabs()
    }

    private fun styleEmojiTabs() {
        emojiTabs.forEachIndexed { i, tv ->
            val on = i == emojiCat
            tv.alpha = if (on) 1f else 0.6f
            tv.background = if (on) GradientDrawable().apply { setColor(tokens.stripPressed); cornerRadius = tokens.stripRadius - 4.dp } else null
        }
    }

    /** Search mode top bar: close, the query, then a scrolling row of matches (recents while the query is empty). */
    private fun buildSearchBar() = LinearLayout(themed).apply {
        orientation = LinearLayout.HORIZONTAL
        isBaselineAligned = false
        layoutParams = LinearLayout.LayoutParams(MATCH_PARENT, 42.dp).apply { setMargins(3.dp, 0, 3.dp, 3.dp) }
        background = tokens.stripBackground()
        setPadding(3.dp, 3.dp, 3.dp, 3.dp)
        val medium = ResourcesCompat.getFont(themed, R.font.gsr_medium)
        addView(TextView(themed).apply {
            text = "\u2715"; textSize = 16f; gravity = Gravity.CENTER
            setTextColor(tokens.keyText); alpha = 0.7f
            contentDescription = "Close search"
            background = tokens.stripItemBackground()
            setOnClickListener { setEmoji(true) }
        }, LinearLayout.LayoutParams(40.dp, MATCH_PARENT))
        val labelView = TextView(themed).apply {
            textSize = 15f; maxLines = 1; gravity = Gravity.CENTER_VERTICAL
            ellipsize = android.text.TextUtils.TruncateAt.START
            setPadding(4.dp, 0, 8.dp, 0)
            setTextColor(tokens.keyText)
            if (medium != null) typeface = medium
        }
        searchLabel = labelView
        addView(labelView, LinearLayout.LayoutParams(112.dp, MATCH_PARENT))
        addView(View(themed).apply { setBackgroundColor(tokens.divider) },
            LinearLayout.LayoutParams(1.dp, 14.dp).apply { gravity = Gravity.CENTER_VERTICAL })
        val resultsView = LinearLayout(themed).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
        val scrollView = android.widget.HorizontalScrollView(themed).apply {
            isHorizontalScrollBarEnabled = false
            overScrollMode = View.OVER_SCROLL_NEVER
            addView(resultsView, ViewGroup.LayoutParams(WRAP_CONTENT, MATCH_PARENT))
        }
        searchResults = resultsView
        searchScroll = scrollView
        addView(scrollView, LinearLayout.LayoutParams(0, MATCH_PARENT, 1f))
    }

    private fun updateSearchBar() {
        val label = searchLabel ?: return
        val box = searchResults ?: return
        if (query.isEmpty()) { label.text = "Search emoji"; label.alpha = 0.5f } else { label.text = query.toString(); label.alpha = 1f }
        box.removeAllViews()
        val list = if (query.isBlank()) EmojiData.recents(this) else EmojiData.search(query.toString())
        if (list.isEmpty() && query.isNotBlank()) {
            box.addView(TextView(themed).apply {
                text = "No emoji found"; textSize = 14f; setTextColor(tokens.keyText); alpha = 0.5f
                setPadding(10.dp, 0, 0, 0); gravity = Gravity.CENTER_VERTICAL
            }, LinearLayout.LayoutParams(WRAP_CONTENT, MATCH_PARENT))
        }
        for (e in list) box.addView(TextView(themed).apply {
            text = e; textSize = 24f; gravity = Gravity.CENTER
            background = tokens.stripItemBackground()
            setOnClickListener { commitEmoji(e) }
        }, LinearLayout.LayoutParams(42.dp, MATCH_PARENT))
        searchScroll?.scrollTo(0, 0)
    }

    /** Departing label of a key that no longer exists: a throw-away [BlurTextView] in the shell's overlay at the old spot. */
    private fun ghost(host: LinearLayout, o: OldKey, dir: BlurDir) {
        if (o.kw <= 0 || o.kh <= 0 || o.text.isBlank()) return
        val g = BlurTextView(themed).apply {
            gravity = Gravity.CENTER
            includeFontPadding = true
            setTextSize(android.util.TypedValue.COMPLEX_UNIT_PX, o.textSize)
            o.typeface?.let { typeface = it }
            setTextColor(o.color)
            measure(View.MeasureSpec.makeMeasureSpec(o.kw, View.MeasureSpec.EXACTLY), View.MeasureSpec.makeMeasureSpec(o.kh, View.MeasureSpec.EXACTLY))
            layout(o.x, o.y, o.x + o.kw, o.y + o.kh)
        }
        host.overlay.add(g)
        // text is empty, so setTextBlur departs from [o.text] to nothing
        if (!g.setTextBlur("", from = o.text, dir = dir)) { host.overlay.remove(g); return }
        host.postDelayed({ host.overlay.remove(g) }, BlurTextTokens.TOTAL_MS + 80L)
    }

    /**
     * User key height, clamped so 4–5 rows + suggestions never take more than ~55% of the screen
     * (landscape, split-screen, short displays).
     */
    private fun keyHeight(): Int {
        val pref = Prefs.int(this, "key_h", 50).dp
        val rows = 4f + (if (Prefs.bool(this, "numrow", false) && !symbols) 0.8f else 0f)
        val stripH = if (translateMode) trDockH + 3.dp else if (Prefs.bool(this, "suggest", true) || Prefs.bool(this, "translate", false)) 45.dp else 0
        val budget = resources.displayMetrics.heightPixels * 0.55f - stripH - 16.dp - navInset
        val margins = (2 * Prefs.keyGap(this) + 4).dp
        val fit = (budget / rows).toInt() - margins
        return minOf(pref, fit).coerceAtLeast(34.dp)
    }

    private fun rowOf(build: LinearLayout.() -> Unit) = LinearLayout(themed).apply {
        orientation = LinearLayout.HORIZONTAL
        // Keys mix 22sp letters, 15sp labels and icons: baseline alignment would shift them vertically.
        isBaselineAligned = false
        layoutParams = LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT)
        build()
    }

    private fun LinearLayout.key(
        label: String, h: Int, w: Float = 1f, style: KeyStyle = KeyStyle.LETTER,
        repeat: Boolean = false, icon: Int = 0, onKey: () -> Unit,
    ): KeyView {
        val g = Prefs.keyGap(this@PillboardService)
        // rows breathe more than columns, like iOS 26
        val lp = LinearLayout.LayoutParams(0, h + 2 * (g + 2).dp, w)
        return KeyView(themed, label, style, tokens, repeat, icon, onKey).also {
            it.setHitInset(g.dp, (g + 2).dp)
            if (Prefs.bool(this@PillboardService, "keypreview", true)) it.preview = preview
            it.glass = shell
            it.hintBubble = hintBubble
            it.tapFx = tapFx
            it.tapFxBack = tapFxBack
            addView(it, lp)
        }
    }

    private fun LinearLayout.gap(w: Float) = addView(View(themed), LinearLayout.LayoutParams(0, 1, w))

    private fun LinearLayout.chars(s: String, h: Int, w: Float = 1f) {
        for (c in s) {
            val shown = if (shift != Shift.OFF && !symbols) c.uppercaseChar() else c
            val holder = arrayOfNulls<KeyView>(1)
            holder[0] = key(shown.toString(), h, w) { type(holder[0]!!.text.toString()) }
            val kv = holder[0]!!
            if (!symbols && c.isLetter()) {
                letterKeys.add(kv)
                kv.altsFor = { l ->
                    val base = l.lowercase()
                    ALTS[base]?.map { if (l != base) it.uppercase() else it } ?: emptyList()
                }
                // optional Gboard-style hint: a small symbol on the key, hold to type it (replaces the accent popup on that key)
                if (Prefs.bool(this@PillboardService, "keyhint", false)) {
                    kv.hintChar = keyHint(c, Prefs.bool(this@PillboardService, "numrow", false))
                }
            } else {
                val alts = if (extra) EXTRA else PUNCT
                kv.altsFor = { l -> alts[l] ?: emptyList() }
            }
            kv.onAlt = { type(it) }
        }
    }

    /** iOS: OFF = tonal special key, ON and LOCK = inverted key; LOCK is told apart by the underlined arrow icon. */
    private fun shiftStyle() = when (shift) { Shift.OFF -> KeyStyle.SPECIAL; Shift.ON, Shift.LOCK -> KeyStyle.ACTIVE }

    private fun shiftIcon() = when (shift) {
        Shift.OFF -> R.drawable.ic_shift; Shift.ON -> R.drawable.ic_shift_on; Shift.LOCK -> R.drawable.ic_caps_lock
    }

    private fun shiftDesc() = when (shift) { Shift.OFF -> "Shift"; Shift.ON -> "Shift on"; Shift.LOCK -> "Caps lock" }

    /** Shift changed: relabel keys in place. Never rebuild views while the user is typing. */
    private fun updateShiftUi() {
        if (symbols) return
        letterKeys.forEach {
            val l = it.text.toString()
            // only keys whose label really changes animate (ON <-> LOCK keeps the same capitals); fast typing snaps
            it.morphLabel(if (shift != Shift.OFF) l.uppercase() else l.lowercase(), minGapMs = BlurTextTokens.RAPID_GAP_MS,
                dir = if (shift != Shift.OFF) BlurDir.UP else BlurDir.DOWN)     // capitals rise, lowercase settles down
        }
        shiftKey?.let {
            it.setKeyStyle(shiftStyle())      // tone, corners and glyph ink flow together on the smooth timeline
            it.setIcon(shiftIcon(), shiftDesc())
        }
    }

    // ---- suggestion strip (iOS-style, 3 slots) ----
    private fun suggestionsAllowed(): Boolean {
        val t = currentInputEditorInfo?.inputType ?: return true
        if (t and InputType.TYPE_MASK_CLASS != InputType.TYPE_CLASS_TEXT) return false
        return when (t and InputType.TYPE_MASK_VARIATION) {
            InputType.TYPE_TEXT_VARIATION_PASSWORD,
            InputType.TYPE_TEXT_VARIATION_VISIBLE_PASSWORD,
            InputType.TYPE_TEXT_VARIATION_WEB_PASSWORD -> false
            else -> true
        }
    }

    private fun buildStrip() = LinearLayout(themed).apply {
        orientation = LinearLayout.HORIZONTAL
        isBaselineAligned = false
        layoutParams = LinearLayout.LayoutParams(MATCH_PARENT, 42.dp).apply { setMargins(3.dp, 0, 3.dp, 3.dp) }
        background = tokens.stripBackground()
        setPadding(3.dp, 3.dp, 3.dp, 3.dp)
        val medium = ResourcesCompat.getFont(themed, R.font.gsr_medium)
        divAnim?.cancel(); dividersOn = null        // decided by the first updateStrip()
        clipLayoutOn = false; brandLayoutOn = false; lastStripKey = null
        brandAnim?.cancel(); brandAnim = null; brandGhost = null
        repeat(3) { i ->
            if (i > 0) addView(View(themed).apply { setBackgroundColor(tokens.divider); blurFx(1f, BlurDir.UP, 1) },
                LinearLayout.LayoutParams(1.dp, 14.dp).apply { gravity = Gravity.CENTER_VERTICAL })
            addView(BlurTextView(themed).apply {
                gravity = Gravity.CENTER
                textSize = 16f
                maxLines = 1
                setTextColor(tokens.keyText)
                if (i == 1 && medium != null) typeface = medium   // the lead candidate reads first
                background = tokens.stripItemBackground()
            }, LinearLayout.LayoutParams(0, MATCH_PARENT, 1f))
        }
        run {   // clipboard chip: a pill with the icon and the copied text centred as ONE group; takes the slots' place while a fresh copy is on offer
            val iv = android.widget.ImageView(themed).apply {
                setImageResource(R.drawable.ic_clipboard)
                imageTintList = android.content.res.ColorStateList.valueOf(tokens.keyText)
                imageAlpha = 200
            }
            val tx = TextView(themed).apply {
                textSize = 15f; maxLines = 1; includeFontPadding = false
                ellipsize = android.text.TextUtils.TruncateAt.END
                setTextColor(tokens.keyText)
                if (medium != null) typeface = medium
            }
            val chip = LinearLayout(themed).apply {
                orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER
                visibility = View.GONE
                setPadding(14.dp, 0, 16.dp, 0)
                fun pill(c: Int) = GradientDrawable().apply { setColor(c); cornerRadius = tokens.stripRadius - 4.dp }
                background = android.graphics.drawable.StateListDrawable().apply {
                    addState(intArrayOf(android.R.attr.state_pressed), pill(tokens.stripPressed))
                    addState(intArrayOf(), pill(androidx.core.graphics.ColorUtils.setAlphaComponent(tokens.keyText, 0x14)))
                }
                contentDescription = "Paste copied text"
                addView(iv, LinearLayout.LayoutParams(18.dp, 18.dp).apply { marginEnd = 9.dp })
                addView(tx, LinearLayout.LayoutParams(WRAP_CONTENT, WRAP_CONTENT))
            }
            clipChip = chip; clipText = tx
            addView(chip, LinearLayout.LayoutParams(0, MATCH_PARENT, 1f).apply { setMargins(2.dp, 1.dp, 2.dp, 1.dp) })
        }
        run {   // brand: [dot] Glimkey, left-aligned but inset from the capsule's curve (not at the very edge). The dot follows the chosen theme.
            val dot = View(themed).apply {
                background = GradientDrawable().apply { shape = GradientDrawable.OVAL; setColor(tokens.accent) }
            }
            val name = TextView(themed).apply {
                text = "Glimkey"
                textSize = 16f; maxLines = 1; includeFontPadding = false
                setTextColor(tokens.keyText)
                ResourcesCompat.getFont(themed, R.font.gsr_bold)?.let { typeface = it }
            }
            val inner = LinearLayout(themed).apply {
                orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL
                addView(dot, LinearLayout.LayoutParams(9.dp, 9.dp).apply { marginEnd = 8.dp })
                addView(name, LinearLayout.LayoutParams(WRAP_CONTENT, WRAP_CONTENT))
            }
            val b = LinearLayout(themed).apply {
                orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL or Gravity.START
                visibility = View.GONE
                isClickable = false
                // 2dp margin + 12dp = 14dp from the strip's edge: the same distance the dot widget's icon keeps on the right
                setPadding(12.dp, 0, 8.dp, 0)
                addView(inner, LinearLayout.LayoutParams(WRAP_CONTENT, WRAP_CONTENT))
            }
            brandInner = inner; brandChip = b
            addView(b, LinearLayout.LayoutParams(0, MATCH_PARENT, 1f).apply { setMargins(2.dp, 1.dp, 2.dp, 1.dp) })
        }
        run {
            addView(View(themed).apply { setBackgroundColor(tokens.divider) },
                LinearLayout.LayoutParams(1.dp, 14.dp).apply { gravity = Gravity.CENTER_VERTICAL })
            val f = StripFeature.current(this@PillboardService)
            val icon = BlurIconView(themed, tokens.keyText).apply {
                set(f.icon, animate = false)
                contentDescription = f.label
                background = tokens.stripItemBackground()
                setOnClickListener { runFeature(StripFeature.current(this@PillboardService)) }
                attachGestures(onLong = { showStripMenu(it) }, onSwipe = { up -> cycleFeature(up) })
            }
            stripIcon = icon
            addView(icon, LinearLayout.LayoutParams(46.dp, MATCH_PARENT))
        }
    }

    /** The text around the caret, read by the suggestion engine (language is inferred from it, never from the system). */
    private fun suggestionCtx(): SuggestCore.Ctx =
        Suggestions.context(currentInputConnection?.getTextBeforeCursor(120, 0) ?: "")

    /** Rewriting typed text (auto-correct, ". " on double space) must never touch emails, URLs, filters, passwords or "no suggestions" fields. */
    private fun rewriteAllowed(): Boolean {
        if (!suggestionsAllowed()) return false
        val t = currentInputEditorInfo?.inputType ?: return true
        if (t and InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS != 0) return false
        return when (t and InputType.TYPE_MASK_VARIATION) {
            InputType.TYPE_TEXT_VARIATION_EMAIL_ADDRESS,
            InputType.TYPE_TEXT_VARIATION_WEB_EMAIL_ADDRESS,
            InputType.TYPE_TEXT_VARIATION_URI,
            InputType.TYPE_TEXT_VARIATION_FILTER -> false
            else -> true
        }
    }

    /** Learn only where it is safe: not in passwords, emails, URLs, search filters, "no suggestions" or incognito fields. */
    private fun learningAllowed(): Boolean {
        if (!Prefs.bool(this, "learn", true) || !suggestionsAllowed()) return false
        val ei = currentInputEditorInfo ?: return false
        if (ei.imeOptions and EditorInfo.IME_FLAG_NO_PERSONALIZED_LEARNING != 0) return false
        val t = ei.inputType
        if (t and InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS != 0) return false
        return when (t and InputType.TYPE_MASK_VARIATION) {
            InputType.TYPE_TEXT_VARIATION_EMAIL_ADDRESS,
            InputType.TYPE_TEXT_VARIATION_WEB_EMAIL_ADDRESS,
            InputType.TYPE_TEXT_VARIATION_URI,
            InputType.TYPE_TEXT_VARIATION_FILTER -> false
            else -> true
        }
    }

    /** A word was just finished (space, punctuation, enter): the engine remembers it and which word came before. */
    private fun learnWord(c: SuggestCore.Ctx) {
        if (c.word.isNotEmpty() && learningAllowed()) Suggestions.learn(c, c.word)
    }

    private fun updateStrip() {
        if (emojiMode) { if (emojiSearch) updateSearchBar(); return }
        if (translateMode) return
        val s = strip ?: return
        val around = around()                       // the only editor read of this update
        lastStripKey = stripKey(around)
        val c = Suggestions.context(around?.before ?: "")
        // empty field: the centre slot carries the name with no dividers, whatever the engine would offer (start-of-sentence words)
        val empty = fieldIsEmpty(around)
        val brand = empty || !suggestionsAllowed()      // terminals, passwords, numbers: just the dot + name on the left (and the dot widget on the right)
        // a fresh copy takes the whole strip (like Gboard) until the person starts a word, pastes it, or it gets old
        val maxAge = if (empty) clipFreshMs else clipFreshTypingMs
        val clip = if (c.word.isEmpty() && !isPasswordField()) freshClip(maxAge) else null
        val res = if (clip == null && !brand && Prefs.bool(this, "suggest", true)) Suggestions.suggest(c) else null
        val any = (0 until 3).any { res?.slot(it) != null }
        setClipChipLayout(s, clip != null, clip?.let { clipPreview(it) })
        setBrandLayout(s, brand && clip == null)
        clipChip?.setOnClickListener(if (clip != null) View.OnClickListener { pasteClip(clip) } else null)
        for (i in 0 until 3) {
            val tv = s.getChildAt(i * 2) as BlurTextView
            val cand = res?.slot(i)          // slot 1 (centre) is the lead candidate
            val nextText = when {
                clip != null -> ""
                else -> cand?.label ?: ""
            }
            if (i == 1) {       // the name is bold, the lead candidate medium
                val f = ResourcesCompat.getFont(themed, R.font.gsr_medium)
                if (f != null && tv.typeface !== f) tv.typeface = f
            }
            // tv.tag = the text this slot is heading to. Blur Replace sets the text at once and only animates the paint;
            // changes faster than RAPID_GAP_MS (fast typing) snap, so typing never waits on the effect.
            if (tv.tag != nextText) {
                tv.tag = nextText
                tv.setTextBlur(nextText, minGapMs = BlurTextTokens.RAPID_GAP_MS)
            }
            val action: View.OnClickListener? = when {
                cand != null -> View.OnClickListener { applySuggestion(c, cand) }
                else -> null
            }
            tv.setOnClickListener(action)
            tv.isClickable = action != null      // setOnClickListener(null) would leave the name pressable
        }
        setDividers(s, any)
        trHandler.removeCallbacks(clipExpire)
        if (clip != null) trHandler.postDelayed(clipExpire, (maxAge - (System.currentTimeMillis() - clipTimeMs)).coerceAtLeast(0L) + 50L)
    }

    private var dividersOn: Boolean? = null
    private var divAnim: android.animation.ValueAnimator? = null

    /** Empty = nothing before, after or selected. An unknown answer (null) is never "empty", and a select-all is not empty. */
    private fun fieldIsEmpty(): Boolean {
        val ic = currentInputConnection ?: return false
        val before = ic.getTextBeforeCursor(1, 0) ?: return false
        val after = ic.getTextAfterCursor(1, 0) ?: return false
        if (before.isNotEmpty() || after.isNotEmpty()) return false
        return ic.getSelectedText(0).isNullOrEmpty()
    }

    /** The two dividers between the suggestion slots: Blur Replace in when suggestions appear, out when they go (same timing as the text). */
    private fun setDividers(s: LinearLayout, on: Boolean) {
        val first = dividersOn == null
        if (dividersOn == on) return
        dividersOn = on
        val ds = listOf(s.getChildAt(1), s.getChildAt(3))
        divAnim?.cancel()
        // the slots have no width while the paste chip or the brand owns the strip: fading the dividers would draw them at the left edge
        if (!on && (clipLayoutOn || brandLayoutOn)) { ds.forEach { it.blurFx(1f, BlurDir.UP, 1) }; return }
        val to = if (on) 0f else 1f
        if (first || !animationsOn(this) || !s.isLaidOut) { ds.forEach { it.blurFx(to, BlurDir.UP, 1) }; return }
        val start = 1f - Math.sqrt(ds[0].alpha.coerceIn(0f, 1f).toDouble()).toFloat()     // continue from where an interrupted run is
        val dir = if (on) BlurDir.UP else BlurDir.DOWN
        val sign = if (on) 1 else -1
        divAnim = android.animation.ValueAnimator.ofFloat(0f, 1f).apply {
            duration = if (on) BlurTextTokens.IN_MS else BlurTextTokens.OUT_MS
            startDelay = if (on) BlurTextTokens.IN_DELAY_MS else 0L        // after the words, like the text's own arrival
            interpolator = if (on) BlurTextTokens.inEase else BlurTextTokens.outEase
            addUpdateListener { anim -> val v1 = start + (to - start) * (anim.animatedValue as Float); ds.forEach { d -> d.blurFx(v1, dir, sign) } }
            start()
        }
    }

    /** The caret sits inside a word (a letter or digit follows it): nothing may be learned or corrected from the half before it. */
    private fun midWord(ic: android.view.inputmethod.InputConnection): Boolean {
        val a = ic.getTextAfterCursor(1, 0)
        return !a.isNullOrEmpty() && a[0].isLetterOrDigit()
    }

    private fun applySuggestion(c: SuggestCore.Ctx, cand: SuggestCore.Cand) {
        lastFix = null
        currentInputConnection?.let { ic ->
            // finishing a word from its middle replaces the rest of that word too, and adds a space only where one is missing
            val after = ic.getTextAfterCursor(40, 0)?.toString() ?: ""
            var k = 0
            if (c.word.isNotEmpty()) {
                while (k < after.length && (after[k].isLetter() ||
                        ((after[k] == '\'' || after[k] == '’') && k + 1 < after.length && after[k + 1].isLetter()))) k++
            }
            val next = after.getOrNull(k)
            val needSpace = next == null || !(next.isWhitespace() || next in ".,!?;:)]}")
            ic.deleteSurroundingText(c.word.length, k)
            ic.commitText(if (needSpace) "${cand.text} " else cand.text, 1)
        }
        // tapping the quoted literal means "this is a real word": remember it with extra weight
        if (learningAllowed()) Suggestions.learn(c, cand.text, if (cand.kind == SuggestCore.Kind.LITERAL) 2f else 1f)
        afterEdit()
    }

    // ---- actions ----
    /** Space-bar slide: move the cursor [n] characters (negative = left). */
    private fun moveCursor(n: Int) {
        if (emojiSearch) return
        lastFix = null
        val code = if (n < 0) KeyEvent.KEYCODE_DPAD_LEFT else KeyEvent.KEYCODE_DPAD_RIGHT
        repeat(Math.abs(n)) { sendDownUpKeyEvents(code) }
        updateStrip()
        refreshLoupe()
    }

    /** Text just before / after the cursor, one line, for the cursor loupe. */
    private fun loupeText(): Pair<String, String> {
        val ic = currentInputConnection ?: return "" to ""
        val b = ic.getTextBeforeCursor(40, 0)?.toString().orEmpty().replace('\n', ' ')
        val a = ic.getTextAfterCursor(40, 0)?.toString().orEmpty().replace('\n', ' ')
        return b to a
    }

    private fun showLoupe(anchor: View) {
        if (!Prefs.bool(this, "cursorloupe", true) || emojiSearch || isPasswordField()) return
        val (b, a) = loupeText()
        val loc = IntArray(2); anchor.getLocationOnScreen(loc)
        loupe?.show(anchor, loc[0] + anchor.width / 2f, b, a)
        // ask the app where its caret is; if it never answers the loupe stays above the keyboard
        currentInputConnection?.requestCursorUpdates(InputConnection.CURSOR_UPDATE_MONITOR)
    }

    override fun onUpdateCursorAnchorInfo(info: CursorAnchorInfo) {
        super.onUpdateCursorAnchorInfo(info)
        val l = loupe ?: return
        if (!l.showing) return
        val x = info.insertionMarkerHorizontal
        if (x.isNaN()) return
        val pts = floatArrayOf(x, info.insertionMarkerTop, x, info.insertionMarkerBottom)
        info.matrix.mapPoints(pts)                      // editor coordinates -> screen
        l.setCaret(pts[0], minOf(pts[1], pts[3]), maxOf(pts[1], pts[3]))
    }

    private fun refreshLoupe() {
        val l = loupe ?: return
        if (!l.showing) return
        val (b, a) = loupeText()
        l.update(b, a)
    }

    private fun statsOn() = Prefs.bool(this, TypingStats.PREF, true) && !emojiSearch && learningAllowed()

    private fun type(s: String) {
        if (emojiSearch) { query.append(s.lowercase()); updateSearchBar(); return }
        lastFix = null
        // punctuation ends a word: learn it before the mark goes in
        if (s.length == 1 && !s[0].isLetterOrDigit() && s[0] != '\'' && s[0] != '’' &&
            currentInputConnection?.let { !midWord(it) } != false) learnWord(suggestionCtx())
        if (statsOn()) TypingStats.key(this, s)
        currentInputConnection?.commitText(s, 1)
        afterEdit(letterTyped = s.length == 1 && s[0].isLetterOrDigit())
    }

    private fun backspace() {
        if (emojiSearch) { if (query.isNotEmpty()) query.setLength(query.length - 1); updateSearchBar(); return }
        val fix = lastFix; lastFix = null
        val ic = currentInputConnection
        if (fix != null && ic != null && ic.getTextBeforeCursor(fix.second.length, 0)?.toString() == fix.second) {
            ic.deleteSurroundingText(fix.second.length, 0)   // undo auto-correct: restore what was typed
            ic.commitText(fix.first, 1)
            if (learningAllowed()) Suggestions.undoCorrection(fix.first)    // their spelling wins from now on
            afterEdit(); return
        }
        if (statsOn()) TypingStats.delete(this)
        sendDownUpKeyEvents(KeyEvent.KEYCODE_DEL)
        afterEdit()
    }

    /** iOS-style: double space becomes ". " */
    private fun space() {
        if (emojiSearch) { if (query.isNotEmpty() && query.last() != ' ') query.append(' '); updateSearchBar(); return }
        val ic = currentInputConnection ?: return
        val c = suggestionCtx()
        val mid = midWord(ic)
        if (statsOn()) {
            if (ic.getTextBeforeCursor(1, 0)?.lastOrNull()?.isLetterOrDigit() == true) TypingStats.word(this)
            TypingStats.key(this, " ")
        }
        if (!mid && Prefs.bool(this, "autocorrect", true) && rewriteAllowed() && autoCorrect(ic, c)) { afterEdit(); return }
        if (!mid) learnWord(c)
        lastFix = null
        val before = ic.getTextBeforeCursor(2, 0)?.toString() ?: ""
        if (Prefs.bool(this, "dblspace", true) && rewriteAllowed() && before.length == 2 && before[1] == ' ' && before[0].isLetterOrDigit()) {
            ic.deleteSurroundingText(1, 0)
            ic.commitText(". ", 1)
        } else {
            ic.commitText(" ", 1)
        }
        afterEdit()
    }

    /** The engine decides (typo distance on this keyboard, language mix, what the person already uses); backspace undoes it. */
    private fun autoCorrect(ic: android.view.inputmethod.InputConnection, c: SuggestCore.Ctx): Boolean {
        val out = Suggestions.autoCorrection(c) ?: return false
        ic.deleteSurroundingText(c.word.length, 0)
        ic.commitText("$out ", 1)
        lastFix = c.word to "$out "
        if (learningAllowed()) Suggestions.learn(c, out)
        return true
    }

    private fun enter() {
        if (emojiSearch) { setEmoji(true); return }
        learnWord(suggestionCtx())
        lastFix = null
        if (!sendDefaultEditorAction(true)) sendDownUpKeyEvents(KeyEvent.KEYCODE_ENTER)
        afterEdit()
    }

    private fun onShift() {
        val now = SystemClock.uptimeMillis()
        shift = when {
            shift == Shift.LOCK -> Shift.OFF
            now - lastShiftTap < 300 -> Shift.LOCK
            shift == Shift.ON -> Shift.OFF
            else -> Shift.ON
        }
        lastShiftTap = now
        updateShiftUi()
    }

    /**
     * [letterTyped]: the caret just moved past a letter or digit, so the caps mode can only be "all caps" (when the field
     * asks for it) or none. That is exactly what the editor would answer, minus one synchronous binder call per keystroke.
     */
    private fun autoCap(letterTyped: Boolean = false) {
        if (emojiSearch) { if (shift != Shift.LOCK) shift = Shift.OFF; return }
        if (shift == Shift.LOCK) return
        if (!Prefs.bool(this, "autocap", true)) { shift = Shift.OFF; return }
        val ei = currentInputEditorInfo
        val caps = when {
            ei == null || ei.inputType == 0 -> 0
            letterTyped -> ei.inputType and InputType.TYPE_TEXT_FLAG_CAP_CHARACTERS
            else -> currentInputConnection?.getCursorCapsMode(ei.inputType) ?: 0
        }
        shift = if (caps != 0) Shift.ON else Shift.OFF
    }

    private fun afterEdit(letterTyped: Boolean = false) {
        val prev = shift
        autoCap(letterTyped)
        if (prev != shift) updateShiftUi()
        updateStrip()
        if (translateMode) { trHandler.removeCallbacks(trDebounce); trHandler.postDelayed(trDebounce, 550) }
    }
}

/** What the rebuild needs from a key of the previous page: its character, a running Blur Replace and its visual size. */
private class OldKey(
    val text: String, val snap: List<BlurTextLayer>?, val w: Int, val h: Int,
    val x: Int, val y: Int, val kw: Int, val kh: Int, val textSize: Float, val typeface: android.graphics.Typeface?, val color: Int,
)
