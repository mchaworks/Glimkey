package dev.pillboard.keyboard

import android.app.Activity
import android.app.AlertDialog
import android.content.Context
import android.content.Intent
import android.content.res.ColorStateList
import android.content.res.Configuration
import androidx.core.view.doOnLayout
import android.graphics.drawable.ColorDrawable
import android.widget.FrameLayout
import com.example.liquidglass.LiquidGlassView
import android.widget.ImageView
import android.widget.TextView
import androidx.core.content.res.ResourcesCompat
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.text.Editable
import android.text.TextWatcher
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.view.inputmethod.InputMethodManager
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import androidx.core.view.ViewCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat

/** Home + subpages (Typing, Appearance, Feedback, Advanced, About) with search. One activity, in-place navigation. */
class SettingsActivity : Activity() {

    private enum class Page(val label: String, val sub: String, val icon: Int = 0, val tile: Int = 0) {
        HOME("Glimkey", ""),
        TYPING("Typing", "Suggestions, learning, auto-correction, capitalization", R.drawable.ic_set_typing, 0xFF0A84FF.toInt()),
        TRANSLATE("Translation", "Online translate, target language", R.drawable.ic_translate, 0xFF32ADE6.toInt()),
        APPEARANCE("Appearance", "Colors, shape, spacing, height", R.drawable.ic_set_look, 0xFFBF5AF2.toInt()),
        LAYOUT("Layout", "Layout style, number row, emoji key, strip button", R.drawable.ic_set_layout, 0xFFFF9F0A.toInt()),
        FEEDBACK("Feedback", "Haptics", R.drawable.ic_set_feedback, 0xFF30B650.toInt()),
        ADVANCED("Advanced", "Learned words, reset, emoji history", R.drawable.ic_set_more, 0xFF8E8E93.toInt()),
        ABOUT("About", "Version, license, credits", R.drawable.ic_set_info, 0xFF5AB8E8.toInt()),
    }

    /** Single source of truth: pages and search both render from this list. */
    private class Item(val page: Page, val title: String, val keys: String, val visible: () -> Boolean = { true }, val make: () -> View)

    private companion object { const val REPO = "https://github.com/mchaworks/Glimkey" }

    private lateinit var ui: SettingsUi
    private lateinit var scroll: ScrollView
    private lateinit var col: LinearLayout
    private lateinit var top: LinearLayout
    private lateinit var body: LinearLayout
    private var status: android.widget.TextView? = null
    private var previewHolder: LinearLayout? = null
    // fixed iOS-style nav bar: back button, small title that fades in once the large title scrolls away, hairline
    private lateinit var nav: FrameLayout
    private lateinit var navBack: View
    private lateinit var navTitle: BlurTextView
    private lateinit var searchBar: View          // iOS 26: a glass capsule floating at the bottom of Home
    private lateinit var searchInput: EditText
    private var searchSilent = false               // true while the code (not the user) edits the field
    private var insetBottom = 0
    private lateinit var pinned: FrameLayout       // live keyboard preview, pinned under the nav bar (Appearance only)
    private lateinit var content: FrameLayout     // the page area under the nav bar: what Blur Replace and the container morph work on
    private lateinit var pageBox: LinearLayout     // pinned preview + scroll; the new page opens out of the tapped row as this
    private lateinit var motion: SettingsMotion
    private var pageDir = BlurDir.LEFT             // deeper pages arrive from the right, back arrives from the left
    private val rowFor = HashMap<Page, View>()     // the home rows, so Back can fold the page into the row it came from
    private var blurStatusValue: BlurTextView? = null
    private var lastPreviewAt = 0L
    private var testInput: EditText? = null
    private var tryHeader: View? = null
    private var page = Page.HOME
    private var query = ""

    private val blurOk get() = Build.VERSION.SDK_INT >= 31 &&
        (getSystemService(WINDOW_SERVICE) as WindowManager).isCrossWindowBlurEnabled

    /** What the system actually allows right now, so a blur that "does nothing" has a visible reason. */
    private fun blurStatus(): String = when {
        Build.VERSION.SDK_INT < 31 -> "Needs Android 12+"
        !blurOk -> when {
            getSystemService(android.os.PowerManager::class.java)?.isPowerSaveMode == true -> "Blocked: battery saver is on"
            runCatching { android.provider.Settings.Global.getInt(contentResolver, "disable_window_blurs", 0) }.getOrDefault(0) == 1 ->
                "Blocked: Developer options"
            else -> "Blocked by system"
        }
        Prefs.bool(this, "blur", false) -> "Active"
        else -> "Available, off"
    }

    private val items: List<Item> by lazy {
        listOf(
            Item(Page.TYPING, "Suggestions", "word bar predictions") { ui.toggle("Suggestions", "Word bar above the keys", "suggest", true) },
            Item(Page.TYPING, "Learn from typing", "learn personalize words adaptive memory privacy") {
                ui.toggle("Learn from typing", "Remembers your words and word pairs on this device only. Never in password, email, web address or incognito fields.", "learn", true)
            },
            Item(Page.TYPING, "Typing stats", "statistics speed wpm words keys accuracy counter") {
                ui.link("Typing stats", "Speed, keys and words. Counts only, never your text", chevron = false) { showTypingStats() }
            },
            Item(Page.TYPING, "Collect typing stats", "statistics speed wpm counter privacy off") {
                ui.toggle("Collect typing stats", "Count keys, words and speed. Never the text, never in password fields", TypingStats.PREF, true)
            },
            Item(Page.TYPING, "Auto-capitalization", "capital sentence shift") { ui.toggle("Auto-capitalization", "Capital letter at the start of sentences", "autocap", true) },
            Item(Page.TYPING, "Double-space period", "dot full stop") { ui.toggle("Double-space period", "Tap space twice to insert a period", "dblspace", true) },
            Item(Page.TYPING, "Auto-correction", "fix typos undo") { ui.toggle("Auto-correction", "Fix common typos on space, backspace undoes", "autocorrect", true) },
            Item(Page.TYPING, "Space bar cursor", "slide swipe cursor move space") { ui.toggle("Space bar cursor", "Slide on the space bar to move the cursor", "spaceslide", true) },
            Item(Page.TYPING, "Cursor loupe", "magnifier loupe cursor space slide glass zoom") { ui.toggle("Cursor loupe", "Shows a magnifier with the text around the cursor while you slide on the space bar. Never in password fields.", "cursorloupe", true) },
            Item(Page.TRANSLATE, "Translation", "translate online language word bar") {
                ui.toggle("Translation", "Adds a translate button to the word bar. While translate mode is open, the text of the field you are typing in is sent to the service chosen below. Never used in password fields.", "translate", false) { renderBody() }
            },
            Item(Page.TRANSLATE, "Service", "translate provider google mymemory libre online") {
                ui.link("Service", Translator.provider(this).label, chevron = false) { pickProvider() }
            },
            Item(Page.TRANSLATE, "Target language", "translate language target default system") {
                ui.link("Target language", Languages.nameOf(this, targetCode()), chevron = false) { pickTarget() }
            },
            Item(Page.TRANSLATE, "Server URL", "translate libre server url host", visible = { Translator.provider(this) == Translator.Provider.LIBRE }) {
                ui.link("Server URL", Prefs.str(this, "tr_url", "").ifEmpty { "Not set" }, chevron = false) { editText("Server URL", "tr_url", false) }
            },
            Item(Page.TRANSLATE, "API key", "translate libre key token", visible = { Translator.provider(this) == Translator.Provider.LIBRE }) {
                ui.link("API key", if (Prefs.str(this, "tr_key", "").isEmpty()) "Optional" else "Set", chevron = false) { editText("API key", "tr_key", true) }
            },
            Item(Page.LAYOUT, "Layout style", "layout gboard ios style symbols bottom row enter emoji comma google apple") {
                ui.cycle("Layout style", "layout_style", "ios", listOf("ios" to "iOS", "gboard" to "Gboard")) { refreshPreview(); renderBody() }
            },
            Item(Page.LAYOUT, "Smart keys", "field numpad number phone email url web at slash dot com adapt") {
                ui.toggle("Smart keys", "Numpad in number fields, @ in e-mail fields, / and .com in web fields", "fieldaware", true)
            },
            Item(Page.LAYOUT, "Number row", "digits numbers top") { ui.toggle("Number row", "Show 1–0 above the letters", "numrow", false) { refreshPreview() } },
            Item(Page.LAYOUT, "Key hints", "hint symbol long press hold gboard number punctuation top right") {
                ui.toggle("Key hints", "A small symbol on each letter key; hold the key to type it. Letters then type on release, and hold no longer shows accents like é", "keyhint", false) { refreshPreview() }
            },
            Item(Page.LAYOUT, "Strip button", "word bar capsule button feature translate clipboard text editing emoji settings swipe hold shortcut") {
                val feats = StripFeature.available(this)
                ui.cycle("Strip button", "strip_feature", StripFeature.current(this).id, feats.map { it.id to it.label }) { refreshPreview() }
            },
            Item(Page.LAYOUT, "Emoji key", "emoji smiley picker button") { ui.toggle("Emoji key", "Show the emoji button", "emoji", true) { refreshPreview(); renderBody() } },
            Item(Page.LAYOUT, "Emoji key position", "emoji smiley button comma capsule widget place", visible = { Prefs.bool(this, "emoji", true) && !Prefs.gboard(this) }) {
                ui.cycle("Emoji key position", "emoji_pos", "capsule", listOf("capsule" to "On the strip button (swipe or hold)", "comma" to "Next to the comma key")) { refreshPreview(); renderBody() }
            },
            Item(Page.ADVANCED, "Clear recent emoji", "emoji recent history forget") { ui.link("Clear recent emoji", "Forget the emoji you used lately", chevron = false) { EmojiData.clearRecents(this) } },
            Item(Page.APPEARANCE, "Theme", "theme style paper terminal mechanical liquid glass skin look") {
                ui.themePicker { refreshPreview(); renderBody() }
            },
            // one mode for the keyboard AND this app: recreate so both wear it
            Item(Page.APPEARANCE, "Mode", "dark light night theme mode system appearance") {
                ui.segmentRow("Mode", "mode", "system", listOf("system" to "System", "light" to "Light", "dark" to "Dark")) { recreate() }
            },
            Item(Page.APPEARANCE, "Accent color", "palette preset color material you dynamic wallpaper pixel style hue custom tonal vivid muted bold", visible = { KbTheme.current(this).isGlass }) {
                ui.accent { refreshPreview() }
            },
            Item(Page.APPEARANCE, "Three-tone accent", "primary secondary tertiary pixel material you colors palette accent", visible = { KbTheme.current(this).isGlass }) {
                ui.toggle("Three-tone accent", "Like Pixel: primary on Enter, secondary on special keys, tertiary on Shift", "tritone", true,
                    onChange = { refreshPreview(); renderBody() })
            },
            Item(Page.APPEARANCE, "Blur background", "frosted glass", visible = { KbTheme.current(this).isGlass }) {
                ui.toggle("Blur background",
                    if (blurOk) "Liquid Glass backdrop blur" else "Glass tint fallback; system blur unavailable",
                    "blur", false, enabled = true, onChange = { blurStatusValue?.setTextBlur(blurStatus()) })
            },
            Item(Page.APPEARANCE, "Blur tint", "blur tint opacity transparency glass shell frosted clear see through", visible = { KbTheme.current(this).isGlass }) {
                ui.slider("Blur tint", 10, 100, "blur_tint", 62, listOf("Clear" to 30, "Standard" to 62, "Frosted" to 85), unit = "%") { refreshPreview() }
            },
            Item(Page.APPEARANCE, "Blur status", "blur glass system available blocked", visible = { KbTheme.current(this).isGlass }) {
                ui.info("Blur status", blurStatus()).also { blurStatusValue = it.findViewWithTag<BlurTextView>("value") }
            },
            Item(Page.APPEARANCE, "Liquid refraction", "glass refraction edge bevel dispersion bend background shell", visible = { KbTheme.current(this).isGlass }) {
                ui.toggle("Liquid refraction",
                    if (Build.VERSION.SDK_INT >= 33) "The glass edge bends the keys near it, with a faint color fringe" else "Needs Android 13+",
                    "refract", false, enabled = Build.VERSION.SDK_INT >= 33, onChange = { refreshPreview() })
            },
            Item(Page.APPEARANCE, "Liquid lens", "refraction lens magnifier key preview glass", visible = { KbTheme.current(this).isGlass }) {
                ui.toggle("Liquid lens",
                    if (Build.VERSION.SDK_INT >= 33) "Key preview refracts the keys around it" else "Needs Android 13+, using the plain bubble",
                    "lens", true, enabled = Build.VERSION.SDK_INT >= 33)
            },
            Item(Page.APPEARANCE, "Key preview", "popup bubble character magnify") {
                ui.toggle("Key preview", "Floating character bubble above the pressed key", "keypreview", true)
            },
            Item(Page.APPEARANCE, "Key height", "size tall short dp") {
                ui.slider("Key height", 42, 62, "key_h", 50, listOf("Short" to 44, "Standard" to 50, "Tall" to 58)) { refreshPreview() }
            },
            Item(Page.APPEARANCE, "Key shape", "radius corner round square") {
                ui.slider("Key shape", 2, 20, KbTheme.current(this).keyRKey, KbTheme.current(this).keyRadiusDp, listOf("Square" to 2, "Standard" to KbTheme.current(this).keyRadiusDp, "Round" to 18)) { refreshPreview() }
            },
            Item(Page.APPEARANCE, "Key spacing", "gap margin tight") {
                ui.slider("Key spacing", 2, 6, KbTheme.current(this).keyGapKey, KbTheme.current(this).keyGapDp, listOf("Tight" to 2, "Standard" to KbTheme.current(this).keyGapDp, "Roomy" to 5)) { refreshPreview() }
            },
            Item(Page.FEEDBACK, "Haptic feedback", "vibration vibrate") { ui.toggle("Haptic feedback", "Vibrate on key press", "haptic", true) { renderBody(animate = true) } },
            Item(Page.FEEDBACK, "Haptic strength", "vibration vibrate strength intensity power haptic light strong", visible = { Prefs.bool(this, "haptic", true) }) {
                ui.slider("Haptic strength", 5, 100, "haptic_strength", 85, listOf("Light" to 40, "Standard" to 85, "Strong" to 100), unit = "%") { Haptics.play(this) }
            },
            Item(Page.FEEDBACK, "Haptic style", "vibration vibrate style feel crisp soft heavy mechanical click haptic", visible = { Prefs.bool(this, "haptic", true) }) {
                ui.cycle("Haptic style", "haptic_style", "crisp", HapticStyle.values().map { it.id to it.label }) { Haptics.play(this) }
            },
            Item(Page.FEEDBACK, "Haptic engine", "vibration haptic engine system advanced primitive no vibration fix", visible = { Prefs.bool(this, "haptic", true) }) {
                ui.cycle("Haptic engine", "haptic_engine", "system", listOf("system" to "Standard", "advanced" to "Advanced (strength)")) { Haptics.play(this) }
            },
            Item(Page.FEEDBACK, "Haptic test", "vibration haptic test diagnose debug no vibration not working") {
                ui.link("Haptic test", "Try each vibration method and see which one your phone feels", chevron = false) { HapticLab.show(this) }
            },
            Item(Page.FEEDBACK, "Haptic per key", "vibration haptic key type return space shift backspace firm light", visible = { Prefs.bool(this, "haptic", true) }) {
                ui.toggle("Haptic per key", "Return feels firmer; shift, 123, space and backspace feel lighter", "haptic_keytype", true) { Haptics.play(this, HAPTIC_ACCENT) }
            },
            Item(Page.FEEDBACK, "Adaptive haptics", "vibration haptic fast typing speed buzz lighter adaptive", visible = { Prefs.bool(this, "haptic", true) }) {
                ui.toggle("Adaptive haptics", "Lighter taps while you type fast, so it never turns into a buzz", "haptic_adaptive", true)
            },
            Item(Page.FEEDBACK, "Release tick", "vibration haptic release lift finger up tick", visible = { Prefs.bool(this, "haptic", true) }) {
                ui.toggle("Release tick", "A faint tick when you lift your finger off a key", "haptic_release", true) { if (it) Haptics.play(this, HAPTIC_RELEASE) }
            },
            Item(Page.FEEDBACK, "Typing sound", "sound click key audio android 17") { ui.toggle("Typing sound", "Play a click on every key press", "sound", false) { if (it) TypingSound.play(this, TypingSound.Kind.STANDARD) } },
            Item(Page.FEEDBACK, "Sound style", "android ios thock boba tofu foam click style") {
                ui.cycle("Sound style", "sound_style", TypingSound.styleOf(this), TypingSound.STYLES) { TypingSound.release(); TypingSound.play(this, TypingSound.Kind.STANDARD) }
            },
            Item(Page.FEEDBACK, "Tap effect", "tap effect animation ripple sparkle hearts bubbles confetti petals keys cafe") {
                ui.cycle("Tap effect", TapEffect.PREF, TapEffect.current(this).id, TapEffect.STYLES) { }
            },
            Item(Page.FEEDBACK, "Back effect", "back effect behind keys press glow paint splat glow ink bloom") {
                ui.cycle("Back effect", BackEffect.PREF, BackEffect.current(this).id, BackEffect.styles(this)) { refreshPreview() }
            },
            Item(Page.FEEDBACK, "Tap effect color", "tap effect color rainbow accent") {
                ui.cycle("Tap effect color", TapEffect.COLOR_PREF, "auto", TapEffect.COLORS) { }
            },
            Item(Page.FEEDBACK, "Sound volume", "volume loud quiet click") {
                ui.slider("Sound volume", 10, 300, "sound_vol", 60, listOf("Quiet" to 30, "Standard" to 60, "Loud" to 100, "Max" to 300), unit = "%") { TypingSound.play(this, TypingSound.Kind.STANDARD) }
            },
            Item(Page.ADVANCED, "Clear learned words", "forget learned dictionary personal suggestions words reset") {
                ui.link("Clear learned words", "Forget the words and word pairs the keyboard learned from you", chevron = false) {
                    Suggestions.clearLearned(this)
                    android.widget.Toast.makeText(this, "Learned words cleared", android.widget.Toast.LENGTH_SHORT).show()
                }
            },
            Item(Page.ADVANCED, "Reset settings", "default restore") { ui.link("Reset settings", "Return all keyboard settings to default", chevron = false) { confirmReset() } },
            Item(Page.ABOUT, "Version", "build about") { ui.info("Version", versionName()) },
            Item(Page.ABOUT, "Source code", "github repository open source code") {
                ui.link("Source code", "github.com/mchaworks/Glimkey", icon = R.drawable.ic_set_more, tile = 0xFF1C1C1E.toInt()) { openUrl(REPO) }
            },
            Item(Page.ABOUT, "Report an issue", "bug feedback github issue problem") {
                ui.link("Report an issue", "Something broken or missing", icon = R.drawable.ic_set_info, tile = 0xFFFF453A.toInt()) { openUrl("$REPO/issues") }
            },
            Item(Page.ABOUT, "License", "mit open source licence") { ui.info("License", "MIT") },
            Item(Page.ABOUT, "Font", "google sans rounded ofl typeface licence") { ui.info("Font", "Google Sans Rounded · OFL 1.1") },
            Item(Page.ABOUT, "Key sounds", "mechvibes ios sound packs credits audio") { ui.info("Key sounds", "Mechvibes packs · iOS-style") },
        )
    }

    private fun openUrl(url: String) {
        try { startActivity(Intent(Intent.ACTION_VIEW, android.net.Uri.parse(url))) } catch (_: Exception) {}
    }

    private fun aboutItem(title: String) = items.first { it.page == Page.ABOUT && it.title == title }.make()

    private fun versionName(): String = try { packageManager.getPackageInfo(packageName, 0).versionName ?: "" } catch (e: Exception) { "" }

    /** The in-app Mode (System / Light / Dark) also drives this app, not just the keyboard. */
    override fun attachBaseContext(base: Context) {
        val want = KbMode.night(base)
        if (want == KbMode.systemNight(base)) { super.attachBaseContext(base); return }
        val cfg = Configuration(base.resources.configuration)
        cfg.uiMode = (cfg.uiMode and Configuration.UI_MODE_NIGHT_MASK.inv()) or
            (if (want) Configuration.UI_MODE_NIGHT_YES else Configuration.UI_MODE_NIGHT_NO)
        super.attachBaseContext(base.createConfigurationContext(cfg))
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        setTheme(if (Prefs.dynamic(this)) R.style.Theme_Pillboard_App else R.style.Theme_Pillboard_App_Static)
        super.onCreate(savedInstanceState)
        ui = SettingsUi(this)
        motion = SettingsMotion(this)
        page = savedInstanceState?.getString("page")?.let { runCatching { Page.valueOf(it) }.getOrNull() } ?: Page.HOME

        top = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        body = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        col = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            addView(this@SettingsActivity.top, LinearLayout.LayoutParams(-1, -2))
            addView(this@SettingsActivity.body, LinearLayout.LayoutParams(-1, -2))
            setPadding(16.dp, 4.dp, 16.dp, 32.dp)
        }
        // content fades out softly under the nav bar / pinned preview instead of being cut by a hard edge (top only; the bottom stays crisp)
        scroll = object : ScrollView(this) {
            override fun getBottomFadingEdgeStrength() = 0f
        }.apply {
            addView(col); isVerticalScrollBarEnabled = false; setBackgroundColor(ui.pageBg)
            isVerticalFadingEdgeEnabled = true; setFadingEdgeLength(24.dp)
        }
        scroll.setOnScrollChangeListener { _, _, y, _, _ -> updateNav(y); (searchBar as? GlassBar)?.pulse(250) }
        nav = buildNav()
        pinned = FrameLayout(this).apply { visibility = View.GONE; setBackgroundColor(ui.pageBg); setPadding(16.dp, 2.dp, 16.dp, 10.dp) }
        pageBox = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            addView(pinned, LinearLayout.LayoutParams(-1, -2))
            addView(scroll, LinearLayout.LayoutParams(-1, 0, 1f))
        }
        content = FrameLayout(this).apply { addView(pageBox, FrameLayout.LayoutParams(-1, -1)) }
        val (bar, input) = ui.searchPill("Search") { content }
        searchBar = bar; searchInput = input
        input.addTextChangedListener(object : TextWatcher {
            override fun afterTextChanged(s: Editable?) {
                if (searchSilent) return
                query = s?.toString().orEmpty()
                renderBody(animate = true, dir = if (query.isEmpty()) BlurDir.DOWN else BlurDir.UP)   // results rise in, the list settles back
                (searchBar as? GlassBar)?.pulse(900)
            }
            override fun beforeTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
            override fun onTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
        })
        // the search capsule floats over the page (outside [content], so page transitions don't snapshot it)
        val stack = FrameLayout(this).apply {
            addView(content, FrameLayout.LayoutParams(-1, -1))
            addView(searchBar, FrameLayout.LayoutParams(-1, 52.dp, Gravity.BOTTOM).apply { setMargins(16.dp, 0, 16.dp, 12.dp) })
        }
        val rootCol = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(ui.pageBg)
            addView(nav, LinearLayout.LayoutParams(-1, 52.dp))
            addView(stack, LinearLayout.LayoutParams(-1, 0, 1f))
        }
        window.setBackgroundDrawable(ColorDrawable(ui.pageBg))
        setContentView(rootCol)
        WindowCompat.getInsetsController(window, rootCol).apply { isAppearanceLightStatusBars = !ui.night; isAppearanceLightNavigationBars = !ui.night }
        // Edge-to-edge (targetSdk 35): the nav bar sits under the status bar; the list pads by the nav bar / keyboard (IME)
        WindowCompat.setDecorFitsSystemWindows(window, false)
        ViewCompat.setOnApplyWindowInsetsListener(rootCol) { _, insets ->
            val b = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            val ime = insets.getInsets(WindowInsetsCompat.Type.ime())
            rootCol.setPadding(0, b.top, 0, 0)
            insetBottom = maxOf(b.bottom, ime.bottom)
            (searchBar.layoutParams as FrameLayout.LayoutParams).bottomMargin = insetBottom + 12.dp   // rides above the keyboard
            searchBar.requestLayout()
            applyColPad()
            val field = testInput
            if (ime.bottom > 0 && field != null && field.hasFocus()) {
                scroll.post { field.requestRectangleOnScreen(android.graphics.Rect(0, 0, field.width, field.height + 24.dp), false) }
            }
            insets
        }
        renderPage()
        searchBar.post { (searchBar as? GlassBar)?.pulse(900) }
        if (savedInstanceState != null) {
            // recreated (accent / mode changed): fade in instead of flashing, and stay where the person was scrolled
            // the glass capsule samples the page under it: while the page is still transparent it would capture a dark grey, so it
            // stays hidden through the fade and glasses in once the page is solid
            val home = page == Page.HOME
            rootCol.alpha = 0f; if (home) searchBar.alpha = 0f
            rootCol.animate().alpha(1f).setDuration(180).withEndAction {
                if (home) { searchBar.animate().alpha(1f).setDuration(160).start(); (searchBar as? GlassBar)?.pulse(900) }
            }.start()
            val y = savedInstanceState.getInt("scrollY", 0)
            if (y > 0) scroll.doOnLayout { it.scrollTo(0, y) }
        }
    }

    /** Bottom padding of the list: the system bars / keyboard, plus room for the floating search capsule on Home. */
    private fun applyColPad() = col.setPadding(16.dp, 4.dp, 16.dp, insetBottom + 32.dp + if (page == Page.HOME) 72.dp else 0)

    private fun setSearchText(t: String) { searchSilent = true; searchInput.setText(t); searchSilent = false }

    private fun buildNav(): FrameLayout = FrameLayout(this).apply {
        setBackgroundColor(ui.pageBg); clipChildren = false
        // iOS 26: Back is a round glass button (no text, no bar line); the small title still arrives as the large one scrolls away
        navBack = LiquidGlassView(context).apply {
            visibility = View.GONE; contentDescription = "Back"
            cornerRadius = 20.dp.toFloat(); bevelWidth = 8.dp.toFloat(); refractionHeight = 8.dp.toFloat()
            dispersionStrength = 0.08f; glassTint = ui.glassVeil; pressScale = 0.9f
            isClickable = true; isFocusable = true
            addView(ImageView(context).apply {
                setImageResource(R.drawable.ic_chevron_left); imageTintList = ColorStateList.valueOf(ui.onSurface)
            }, FrameLayout.LayoutParams(24.dp, 24.dp, Gravity.CENTER))
            setOnClickListener { go(Page.HOME) }
        }
        addView(navBack, FrameLayout.LayoutParams(40.dp, 40.dp, Gravity.START or Gravity.CENTER_VERTICAL).apply { marginStart = 16.dp })
        // full width so the old title keeps its room while it Blur-Replaces into the new one (it passes touches to Back)
        navTitle = BlurTextView(context).apply {
            textSize = 17f; setTextColor(ui.onSurface); alpha = 0f; gravity = Gravity.CENTER
            ResourcesCompat.getFont(context, R.font.gsr_bold)?.let { typeface = it }
        }
        addView(navTitle, FrameLayout.LayoutParams(-1, -2, Gravity.CENTER_VERTICAL))
    }

    /** The small title and the hairline arrive as the large title scrolls under the bar. */
    private fun updateNav(y: Int) {
        val p = ((y - 20.dp) / 28.dp.toFloat()).coerceIn(0f, 1f)
        navTitle.alpha = p
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState); outState.putString("page", page.name); outState.putInt("scrollY", scroll.scrollY)
    }

    @Deprecated("Deprecated in Java")
    override fun onBackPressed() {
        when {
            query.isNotEmpty() -> { query = ""; setSearchText(""); renderBody(animate = true, dir = BlurDir.DOWN) }
            page != Page.HOME -> go(Page.HOME)
            else -> super.onBackPressed()
        }
    }

    override fun onResume() { super.onResume(); updateStatus(animate = true) }

    private fun updateStatus(animate: Boolean = false) {
        val imm = getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager
        val enabled = imm.enabledInputMethodList.any { it.packageName == packageName }
        val active = Settings.Secure.getString(contentResolver, Settings.Secure.DEFAULT_INPUT_METHOD)?.startsWith(packageName) == true
        val label = when { active -> "●  Active"; enabled -> "●  Enabled"; else -> "●  Not enabled" }
        val badge = status
        if (animate && badge is BlurTextView) badge.setTextBlur(label) else badge?.text = label
    }

    /**
     * Change page. The page Blur-Replaces (the old one leaves along [pageDir], the new one arrives from blur). Going deeper
     * also opens the new page out of the row that was tapped ([from]); going back folds the old page into that row.
     */
    private fun go(p: Page, from: View? = null) {
        val leaving = page
        val forward = p != Page.HOME
        pageDir = if (forward) BlurDir.LEFT else BlurDir.RIGHT
        motion.swap(
            content, pageDir, kids = { listOf(pinned, scroll) },
            growFrom = { if (forward && from != null) motion.rectOf(content, from, 16.dp.toFloat()) else null }, growView = pageBox,
            shrinkTo = { if (forward) null else rowFor[leaving]?.let { motion.rectOf(content, it, 16.dp.toFloat()) } },
        ) {
            page = p; query = ""; setSearchText(""); renderPage(); scroll.scrollTo(0, 0)
        }
    }

    /** A home row that opens [p]; remembered so the page can open out of it and fold back into it. */
    private fun pageLink(p: Page): View {
        val holder = arrayOfNulls<View>(1)
        val row = ui.link(p.label, p.sub, icon = p.icon, tile = p.tile) { go(p, holder[0]) }
        holder[0] = row
        rowFor[p] = row
        return row
    }

    // ---- rendering ----
    private fun renderPage() {
        top.removeAllViews()
        status = null
        if (page == Page.HOME) {
            top.addView(ui.title("Glimkey", 36f).apply { setPadding(2.dp, 0, 0, 0) })
            top.addView(ui.caption("Your keyboard, your style.").apply { setPadding(2.dp, 2.dp, 0, 16.dp) })
            val st = ui.badge(minW = 150.dp).also { status = it }
            top.addView(ui.card(LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                addView(LinearLayout(context).apply {
                    gravity = Gravity.CENTER_VERTICAL
                    addView(ui.heroTile(R.drawable.ic_set_typing), LinearLayout.LayoutParams(46.dp, 46.dp).apply { marginEnd = 14.dp })
                    addView(LinearLayout(context).apply {
                        orientation = LinearLayout.VERTICAL
                        addView(TextView(context).apply {
                            text = "Glimkey Keyboard"; textSize = 18f; setTextColor(ui.onSurface)
                            ResourcesCompat.getFont(context, R.font.gsr_medium)?.let { typeface = it }
                        })
                        addView(st, LinearLayout.LayoutParams(-2, -2).apply { topMargin = 5.dp })
                    }, LinearLayout.LayoutParams(0, -2, 1f))
                })
                addView(LinearLayout(context).apply {
                    addView(ui.button("Enable", false) { startActivity(Intent(Settings.ACTION_INPUT_METHOD_SETTINGS)) },
                        LinearLayout.LayoutParams(0, 48.dp, 1f).apply { marginEnd = 5.dp })
                    addView(ui.button("Switch", true) {
                        (getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager).showInputMethodPicker()
                    }, LinearLayout.LayoutParams(0, 48.dp, 1f).apply { marginStart = 5.dp })
                }, LinearLayout.LayoutParams(-1, -2).apply { topMargin = 16.dp })
            }, h = 16.dp, v = 16.dp), ui.lp(bottom = 14.dp))
            updateStatus()
        } else {
            top.addView(ui.title(page.label, 32f).apply { setPadding(2.dp, 4.dp, 0, 4.dp) })
            if (page == Page.ABOUT) top.addView(LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL; gravity = Gravity.CENTER_HORIZONTAL; setPadding(0, 18.dp, 0, 6.dp)
                addView(ImageView(context).apply {
                    setImageDrawable(packageManager.getApplicationIcon(packageName))
                    outlineProvider = object : android.view.ViewOutlineProvider() {
                        override fun getOutline(v: View, o: android.graphics.Outline) { o.setRoundRect(0, 0, v.width, v.height, 22.dp.toFloat()) }
                    }
                    clipToOutline = true
                }, LinearLayout.LayoutParams(88.dp, 88.dp))
                addView(TextView(context).apply {
                    text = "Glimkey"; textSize = 24f; setTextColor(ui.onSurface)
                    ResourcesCompat.getFont(context, R.font.gsr_bold)?.let { typeface = it }
                }, LinearLayout.LayoutParams(-2, -2).apply { topMargin = 14.dp })
                addView(ui.badge().apply { text = "Version ${versionName()}" }, LinearLayout.LayoutParams(-2, -2).apply { topMargin = 8.dp })
                addView(ui.caption("An open-source keyboard with Material 3 Expressive shapes and iOS-flavoured looks.").apply {
                    gravity = Gravity.CENTER; textSize = 14f; setPadding(28.dp, 12.dp, 28.dp, 0)
                })
            })
        }
        navBack.visibility = if (page == Page.HOME) View.GONE else View.VISIBLE
        applyColPad()
        searchBar.animate().cancel()
        if (page == Page.HOME) { searchBar.visibility = View.VISIBLE; searchBar.animate().alpha(1f).setDuration(220).start(); (searchBar as? GlassBar)?.pulse(700) }
        else searchBar.animate().alpha(0f).setDuration(140).withEndAction { if (page != Page.HOME) searchBar.visibility = View.GONE }.start()
        navTitle.setTextBlur(page.label, dir = pageDir)
        // the keyboard preview lives on Appearance and Layout (the pages whose options change it), pinned under the nav bar so every change is visible while adjusting it
        pinned.removeAllViews()
        if (page == Page.APPEARANCE || page == Page.LAYOUT) {
            previewHolder = LinearLayout(this).also { pinned.addView(it, FrameLayout.LayoutParams(-1, -2)) }
            pinned.visibility = View.VISIBLE
            buildPreview()
        } else { previewHolder = null; pinned.visibility = View.GONE }
        updateNav(scroll.scrollY)
        renderBody()
    }

    /**
     * Rebuild the body. [animate]: the old cards Blur-Replace into the new ones (a value that changed, rows that came or
     * went, search results); typing faster than RAPID_GAP_MS just swaps. The page change does its own swap.
     */
    private fun renderBody(animate: Boolean = false, dir: BlurDir = BlurDir.UP) {
        if (!animate) { buildBody(); return }
        motion.swap(body, dir, kids = { (0 until body.childCount).map { body.getChildAt(it) } }, minGapMs = BlurTextTokens.RAPID_GAP_MS) { buildBody() }
    }

    private fun buildBody() {
        body.removeAllViews(); tryHeader = null
        when {
            page == Page.HOME && query.isNotBlank() -> searchResults()
            page == Page.HOME -> home()
            page == Page.ABOUT -> {
                body.addView(ui.section("Project"))
                body.addView(ui.box(listOf(aboutItem("Source code"), aboutItem("Report an issue")), inset = ui.tileInset), ui.lp())
                body.addView(ui.section("Legal and credits"))
                body.addView(ui.box(listOf(aboutItem("License"), aboutItem("Font"), aboutItem("Key sounds"))), ui.lp())
                body.addView(ui.caption("Version ${versionName()} · Made with care, on-device only. Nothing you type leaves your phone unless Translation is on.")
                    .apply { textSize = 13f; setPadding(20.dp, 12.dp, 20.dp, 0) })
            }
            else -> {
                body.addView(ui.box(items.filter { it.page == page && it.visible() }.map { it.make() }), ui.lp())
                if (page == Page.TRANSLATE) body.addView(ui.caption(
                    "Off by default. When it is on, text is sent over the internet only while translate mode is open, to the service you pick. " +
                    "Google uses an unofficial public endpoint that can change or stop working without notice; MyMemory has a small free daily limit; " +
                    "LibreTranslate needs your own server.").apply { textSize = 13f; setPadding(20.dp, 4.dp, 20.dp, 0) })
            }
        }
    }

    private fun searchResults() {
        val q = query.trim().lowercase()
        val hits = items.filter { it.visible() && (it.title.lowercase().contains(q) || it.keys.contains(q)) }
        if (hits.isEmpty()) {
            body.addView(ui.caption("No matching settings").apply { setPadding(8.dp, 24.dp, 0, 0) })
        } else {
            body.addView(ui.section("Results"))
            body.addView(ui.box(hits.map { it.make() }), ui.lp())
        }
    }

    private fun home() {
        rowFor.clear()
        body.addView(LinearLayout(this).apply {
            addView(ui.button("Customize", false) { go(Page.APPEARANCE) }, LinearLayout.LayoutParams(0, 48.dp, 1f).apply { marginEnd = 5.dp })
            addView(ui.button("Test typing", true) { scroll.smoothScrollTo(0, (tryHeader?.top ?: 0) + this@SettingsActivity.top.height); focusTest() },
                LinearLayout.LayoutParams(0, 48.dp, 1f).apply { marginStart = 5.dp })
        }, ui.lp())
        body.addView(ui.section("Settings"))
        body.addView(ui.box(listOf(Page.TYPING, Page.TRANSLATE, Page.APPEARANCE, Page.LAYOUT, Page.FEEDBACK)
            .map { p -> pageLink(p) }, inset = ui.tileInset), ui.lp())
        tryHeader = ui.section("Try it").also { body.addView(it) }
        val (wrap, input) = ui.testField(); testInput = input
        body.addView(ui.box(listOf(wrap)), ui.lp())
        body.addView(ui.section("More"))
        body.addView(ui.box(listOf(Page.ADVANCED, Page.ABOUT)
            .map { p -> pageLink(p) }, inset = ui.tileInset), ui.lp())
    }

    private fun showTypingStats() {
        val tv = android.widget.TextView(this).apply { text = TypingStats.report(this@SettingsActivity); textSize = 15f; setPadding(64, 32, 64, 8); setTextColor(ui.onSurface) }
        MaterialAlertDialogBuilder(this).setTitle("Typing stats").setView(tv)
            .setPositiveButton("Close", null)
            .setNegativeButton("Reset") { _, _ -> TypingStats.reset(this) }
            .show().also { tintDialog(it) }
    }

    private fun targetCode() = Prefs.str(this, "tr_target", "").ifEmpty { Languages.defaultTarget(this) }

    private fun pickProvider() {
        val ps = Translator.Provider.values()
        MaterialAlertDialogBuilder(this).setTitle("Translation service")
            .setSingleChoiceItems(ps.map { it.label }.toTypedArray(), ps.indexOf(Translator.provider(this))) { d, i ->
                Prefs.setStr(this, "tr_provider", ps[i].id); d.dismiss(); renderBody(animate = true)
            }.setNegativeButton("Cancel", null).show().also { tintDialog(it) }
    }

    private fun tintDialog(d: androidx.appcompat.app.AlertDialog) {
        d.getButton(androidx.appcompat.app.AlertDialog.BUTTON_POSITIVE)?.setTextColor(ui.primary)
        d.getButton(androidx.appcompat.app.AlertDialog.BUTTON_NEGATIVE)?.setTextColor(ui.primary)
    }

    private fun pickTarget() {
        val ls = Languages.ordered(this)
        MaterialAlertDialogBuilder(this).setTitle("Target language")
            .setSingleChoiceItems(ls.map { it.name }.toTypedArray(), ls.indexOfFirst { it.code == targetCode() }) { d, i ->
                Prefs.setStr(this, "tr_target", ls[i].code); d.dismiss(); renderBody(animate = true)
            }.setNegativeButton("Cancel", null).show().also { tintDialog(it) }
    }

    private fun editText(title: String, key: String, secret: Boolean) {
        val input = EditText(this).apply {
            setText(Prefs.str(this@SettingsActivity, key, "")); setSingleLine()
            inputType = android.text.InputType.TYPE_CLASS_TEXT or
                if (secret) android.text.InputType.TYPE_TEXT_VARIATION_PASSWORD else android.text.InputType.TYPE_TEXT_VARIATION_URI
            hint = if (secret) "Optional" else "https://your-server.example"
        }
        val wrap = android.widget.FrameLayout(this).apply { setPadding(24.dp, 8.dp, 24.dp, 0); addView(input) }
        MaterialAlertDialogBuilder(this).setTitle(title).setView(wrap)
            .setPositiveButton("Save") { _, _ -> Prefs.setStr(this, key, input.text.toString().trim()); renderBody(animate = true) }
            .setNegativeButton("Cancel", null).show().also { tintDialog(it) }
    }

    private fun confirmReset() {
        val d = MaterialAlertDialogBuilder(this)
            .setTitle("Reset all keyboard settings?")
            .setMessage("Themes, layout and preferences will return to their defaults.")
            .setPositiveButton("Reset") { _, _ -> Prefs.clear(this); go(Page.HOME); recreate() }
            .setNegativeButton("Cancel", null).create()
        d.setOnShowListener { tintDialog(d); d.getButton(AlertDialog.BUTTON_POSITIVE)?.setTextColor(ui.error) }
        d.show()
    }

    private fun focusTest() {
        testInput?.requestFocus()
        (getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager).showSoftInput(testInput, 0)
    }

    // ---- live preview: same KeyView + KbTokens as the real keyboard, 60% scale, mirrors number row / emoji key / strip button ----
    private fun refreshPreview() { if (previewHolder != null) buildPreview() }

    private fun buildPreview() {
        val holder = previewHolder ?: return
        // keycap shape morph: the keys that are rebuilt flow from the size the old ones had on screen (a running morph carries over)
        val before = ArrayList<KeyView>().also { collectKeys(holder, it) }.map { it.visualW to it.visualH }
        val t = KbTokens(this)
        val scale = 0.6f
        val h = (Prefs.int(this, "key_h", 50).dp * scale).toInt()
        val g = (Prefs.keyGap(this).dp * scale).toInt()
        holder.removeAllViews()
        val card = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(5.dp, 6.dp, 5.dp, 6.dp)
            background = t.shell(if (t.isGlass && Prefs.bool(this@SettingsActivity, "blur", false)) t.glassFillBlur else t.glassFill)
        }
        // same refraction as the real keyboard, so the toggle is visible right here
        if (Build.VERSION.SDK_INT >= 33 && t.isGlass) ShellRefraction(card, t.containerRadius).update(Prefs.bool(this, "refract", false))
        if (Prefs.bool(this, "suggest", true)) {
            card.addView(LinearLayout(this).apply {
                isBaselineAligned = false
                background = t.stripBackground()
                setPadding(2.dp, 2.dp, 2.dp, 2.dp)
                listOf("I", "The", "I'm").forEachIndexed { i, w ->
                    if (i > 0) addView(android.view.View(context).apply { setBackgroundColor(t.divider) },
                        LinearLayout.LayoutParams(1.dp, 12.dp).apply { gravity = Gravity.CENTER_VERTICAL })
                    addView(android.widget.TextView(context).apply {
                        text = w; textSize = 13f; gravity = Gravity.CENTER; setTextColor(t.keyText); t.typeface?.let { typeface = it }
                    }, LinearLayout.LayoutParams(0, -1, 1f))
                }
                // the strip button, showing the feature it currently carries
                addView(ImageView(context).apply {
                    setImageResource(StripFeature.current(this@SettingsActivity).icon)
                    imageTintList = ColorStateList.valueOf(t.keyText); alpha = 0.85f; setPadding(7.dp, 7.dp, 7.dp, 7.dp)
                }, LinearLayout.LayoutParams(32.dp, -1))
            }, LinearLayout.LayoutParams(-1, 30.dp).apply { setMargins(2.dp, 0, 2.dp, 2.dp) })
        }
        /** label, weight, style, icon (0 = text key) */
        class K(val label: String, val w: Float, val style: KeyStyle = KeyStyle.LETTER, val icon: Int = 0)
        val hintsOn = Prefs.bool(this, "keyhint", false)
        val numRowOn = Prefs.bool(this, "numrow", false)
        fun row(keys: List<K>, rh: Int = h) = LinearLayout(this).apply {
            isBaselineAligned = false
            keys.forEach { k ->
                addView(KeyView(context, k.label, k.style, t, false, k.icon) {}.apply {
                    textSize = if (k.label.length == 1 && k.icon == 0) 16f else 13f
                    iconSize = 16.dp
                    if (hintsOn && k.icon == 0 && k.label.length == 1 && k.label[0].isLetter()) {
                        hintSizeSp = 7f
                        hintChar = keyHint(k.label[0], numRowOn)
                    }
                }, LinearLayout.LayoutParams(0, rh, k.w).apply { setMargins(g, g + 2.dp, g, g + 2.dp) })
            }
        }
        fun letters(s: String) = s.map { K("$it", 1f) }
        if (Prefs.bool(this, "numrow", false)) card.addView(row(letters("1234567890"), (h * 0.8f).toInt()))
        card.addView(row(letters("qwertyuiop")))
        card.addView(row(letters("asdfghjkl")))
        card.addView(row(listOf(K("Shift", 1.5f, KeyStyle.SPECIAL, R.drawable.ic_shift)) + letters("zxcvbnm") +
            K("Backspace", 1.5f, KeyStyle.SPECIAL, R.drawable.ic_backspace)))
        val gb = Prefs.gboard(this)
        val emojiBeside = Prefs.bool(this, "emoji", true) && (gb || Prefs.str(this, "emoji_pos", "capsule") == "comma")
        val emojiKey = listOf(K("Emoji", 0.85f, KeyStyle.SPECIAL, R.drawable.ic_emoji))
        card.addView(row(listOf(K(if (gb) "?123" else "123", 1.35f, KeyStyle.SPECIAL)) +
            (if (emojiBeside && !gb) emojiKey else emptyList()) +
            listOf(K(",", 0.85f, KeyStyle.SPECIAL)) +
            (if (emojiBeside && gb) emojiKey else emptyList()) +
            listOf(K("space", if (emojiBeside) 3.6f else 4.45f, KeyStyle.SPECIAL),
                K(".", 0.85f, KeyStyle.SPECIAL),
                if (gb) K("return", 1.5f, KeyStyle.ACCENT, R.drawable.ic_enter) else K("return", 1.5f, KeyStyle.ACCENT))))
        holder.addView(card, LinearLayout.LayoutParams(-1, -2))
        val after = ArrayList<KeyView>().also { collectKeys(card, it) }
        // dragging a slider rebuilds the preview every step: only a change after a pause morphs (same rule as the text), a drag just snaps
        val nowMs = android.os.SystemClock.uptimeMillis()
        val calm = nowMs - lastPreviewAt >= BlurTextTokens.RAPID_GAP_MS
        lastPreviewAt = nowMs
        if (calm && after.size == before.size) after.forEachIndexed { i, k -> if (before[i].first > 0) k.startShapeMorph(before[i].first, before[i].second) }
    }

    private fun collectKeys(v: View, out: MutableList<KeyView>) {
        if (v is KeyView) out.add(v)
        else if (v is ViewGroup) for (i in 0 until v.childCount) collectKeys(v.getChildAt(i), out)
    }
}
