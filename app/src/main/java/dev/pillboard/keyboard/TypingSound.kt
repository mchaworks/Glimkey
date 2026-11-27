package dev.pillboard.keyboard

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioManager
import android.media.MediaCodec
import android.media.MediaExtractor
import android.media.MediaFormat
import android.media.SoundPool
import java.io.ByteArrayOutputStream
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.abs
import kotlin.math.tanh

/**
 * Key-press sounds. Looks for Keypress{Standard,Spacebar,Delete,Return}.{ogg,wav} in assets/sounds/<style>/ (style chosen in Settings; plain assets/sounds/ also works)
 * and plays them through a SoundPool (low latency, overlapping). If none are bundled, falls back to the device's own
 * system key sounds via AudioManager.playSoundEffect (which also follows the system "Touch sounds" switch).
 * Volume goes up to 300 %: above 100 % the bundled samples are amplified (cached copies in cacheDir/boost) because SoundPool itself caps at 1.0.
 */
object TypingSound {
    enum class Kind(val asset: String, val fx: Int) {
        STANDARD("KeypressStandard", AudioManager.FX_KEYPRESS_STANDARD),
        SPACE("KeypressSpacebar", AudioManager.FX_KEYPRESS_SPACEBAR),
        DELETE("KeypressDelete", AudioManager.FX_KEYPRESS_DELETE),
        RETURN("KeypressReturn", AudioManager.FX_KEYPRESS_RETURN),
    }

    const val SYSTEM = "system"
    /** Default: each theme brings its own sound (see [KbTheme.soundStyle]); themes without one keep the iOS pack. */
    const val FOLLOW_THEME = "theme"

    private var pool: SoundPool? = null
    private val ids = HashMap<Kind, IntArray>()
    private val ready = HashSet<Int>()
    private var am: AudioManager? = null
    private var loadedBoost = 100                     // digital gain (%) baked into the sounds currently in the pool
    private var loadedStyle = ""                      // folder the pool was loaded from (changes with the theme when following it)
    private val pcmCache = HashMap<String, Pcm>()     // decoded assets, kept so changing volume never re-decodes
    private val rng = java.util.Random()

    /** Sound styles shown in Settings: folder name -> label. "system" has no folder: it uses the device's own key sounds. */
    val STYLES = listOf(
        FOLLOW_THEME to "Follow theme",
        "system" to "System default", "ios" to "iOS",
        "thock1" to "Thock A · foam", "thock2" to "Thock B · tofu65", "thock3" to "Thock C · deep",
        "glass" to "Glass · soft tink", "paper" to "Paper · pencil tap", "terminal" to "Terminal · teletype", "mechanical" to "Mechanical · clicky",
        "neu" to "Clay · soft pillow", "pixel" to "Pixel · 8-bit blip", "lcd" to "LCD · keypad tone",
    )

    /** The style as chosen in Settings (may be [FOLLOW_THEME]). */
    fun styleOf(c: Context): String {
        val s = Prefs.str(c, "sound_style", "")
        return if (STYLES.any { it.first == s }) s else FOLLOW_THEME     // unknown/removed styles (older builds) follow the theme
    }

    /** The sound folder actually used: [FOLLOW_THEME] resolves to the current theme's own pack. */
    fun folderOf(c: Context): String { val s = styleOf(c); return if (s == FOLLOW_THEME) KbTheme.current(c).soundStyle else s }

    fun isLoaded() = pool != null

    /** Load the pool ahead of the first key press (called off the main thread when the keyboard opens). */
    fun warm(c: Context) {
        if (!Prefs.bool(c, "sound", false)) return
        val volPct = Prefs.int(c, "sound_vol", 60).coerceIn(0, 300)
        ensure(c, if (styleOf(c) == SYSTEM) 100 else boostOf(volPct))
    }

    @Synchronized
    private fun ensure(c: Context, boost: Int) {
        if (pool != null) return
        loadedBoost = boost
        val app = c.applicationContext
        am = app.getSystemService(Context.AUDIO_SERVICE) as AudioManager
        val sp = SoundPool.Builder()
            .setMaxStreams(6)
            .setAudioAttributes(
                AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_ASSISTANCE_SONIFICATION)
                    .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION).build())
            .build()
        sp.setOnLoadCompleteListener { _, id, status -> if (status == 0) ready.add(id) }
        pool = sp
        val style = folderOf(app)
        loadedStyle = style
        if (style == SYSTEM) return    // built-in option: nothing to load, play() falls back to AudioManager.playSoundEffect
        fun load(name: String): Int {
            for (dir in arrayOf("sounds/$style", "sounds")) for (ext in arrayOf("ogg", "wav")) {
                val path = "$dir/$name.$ext"
                if (!exists(app, path)) continue
                if (boost > 100) {      // SoundPool can't go above 1.0, so the gain is baked into a cached copy of the sound
                    val f = boosted(app, path, style, name, boost)
                    val id = if (f != null) sp.load(f.path, 1) else 0
                    if (id != 0) return id
                }
                val id = try { app.assets.openFd(path).use { sp.load(it, 1) } } catch (_: Exception) { 0 }
                if (id != 0) return id
            }
            return 0
        }
        for (k in Kind.values()) {
            // normal keys may ship variants (KeypressStandard, ...2, ...3): rotating them avoids the machine-gun effect
            val names = if (k == Kind.STANDARD) listOf(k.asset, k.asset + "2", k.asset + "3") else listOf(k.asset)
            val got = names.map { load(it) }.filter { it != 0 }
            if (got.isNotEmpty()) ids[k] = got.toIntArray()
        }
        // a pack with only one file still works: every key uses it
        val any = ids[Kind.STANDARD] ?: ids.values.firstOrNull()
        if (any != null) for (k in Kind.values()) if (k !in ids) ids[k] = any
    }

    /** Volume is 10..300 %. Up to 100 it is plain SoundPool volume; above that the samples themselves are amplified (steps of 25 %). */
    private fun boostOf(volPct: Int) = if (volPct <= 100) 100 else ((volPct + 12) / 25 * 25).coerceIn(125, 300)

    fun play(c: Context, kind: Kind) {
        if (!Prefs.bool(c, "sound", false)) return
        val volPct = Prefs.int(c, "sound_vol", 60).coerceIn(0, 300)
        val boost = if (styleOf(c) == SYSTEM) 100 else boostOf(volPct)
        if (pool != null && (boost != loadedBoost || folderOf(c) != loadedStyle)) release()    // gain bucket or theme sound changed: reload
        ensure(c, boost)
        val set = ids[kind]
        if (set == null) { am?.playSoundEffect(kind.fx, (volPct / 100f).coerceAtMost(1f)); return }    // system key sounds can't be amplified
        val vol = (volPct.toFloat() / boost).coerceAtMost(1f)
        val id = set[rng.nextInt(set.size)]
        if (id in ready) pool?.play(id, vol, vol, 1, 0, 0.98f + rng.nextFloat() * 0.04f)
    }

    // ---- amplification (volume above 100 %) ----

    private class Pcm(val data: ShortArray, val rate: Int, val ch: Int)

    private fun exists(app: Context, path: String) = try { app.assets.open(path).close(); true } catch (_: Exception) { false }

    /** Cached WAV copy of [path] with [boost] % gain (soft-limited so it clips gently instead of crackling). */
    private fun boosted(app: Context, path: String, style: String, name: String, boost: Int): File? {
        return try {
            val f = File(app.cacheDir, "boost/${style}_${name}_$boost.wav")
            if (f.exists() && f.length() > 44) return f
            val pcm = pcmCache[path] ?: decode(app, path)?.also { pcmCache[path] = it } ?: return null
            f.parentFile?.mkdirs()
            writeWav(f, pcm, boost / 100f)
            f
        } catch (_: Exception) { null }
    }

    private fun decode(app: Context, path: String): Pcm? =
        try { if (path.endsWith(".wav")) readWav(app, path) else readCodec(app, path) } catch (_: Exception) { null }

    /** 16-bit PCM WAV only (what the bundled packs use); anything else returns null and plays unamplified. */
    private fun readWav(app: Context, path: String): Pcm? {
        val b = app.assets.open(path).use { it.readBytes() }
        val bb = ByteBuffer.wrap(b).order(ByteOrder.LITTLE_ENDIAN)
        var pos = 12; var fmt = 0; var ch = 0; var rate = 0; var bits = 0
        while (pos + 8 <= b.size) {
            val id = String(b, pos, 4, Charsets.US_ASCII)
            var size = bb.getInt(pos + 4)
            val body = pos + 8
            if (size < 0 || body + size > b.size) size = b.size - body
            if (id == "fmt ") {
                fmt = bb.getShort(body).toInt(); ch = bb.getShort(body + 2).toInt()
                rate = bb.getInt(body + 4); bits = bb.getShort(body + 14).toInt()
            } else if (id == "data") {
                if (fmt != 1 || bits != 16 || ch < 1) return null
                val s = ShortArray(size / 2)
                bb.position(body); bb.asShortBuffer().get(s)
                return Pcm(s, rate, ch)
            }
            pos = body + size + (size and 1)
        }
        return null
    }

    /** Decodes ogg (and anything else MediaCodec knows) to 16-bit PCM. */
    private fun readCodec(app: Context, path: String): Pcm? {
        val ex = MediaExtractor()
        var codec: MediaCodec? = null
        try {
            app.assets.openFd(path).use { ex.setDataSource(it.fileDescriptor, it.startOffset, it.length) }
            val fmt = ex.getTrackFormat(0)
            ex.selectTrack(0)
            codec = MediaCodec.createDecoderByType(fmt.getString(MediaFormat.KEY_MIME) ?: return null)
            codec.configure(fmt, null, null, 0); codec.start()
            var rate = fmt.getInteger(MediaFormat.KEY_SAMPLE_RATE)
            var ch = fmt.getInteger(MediaFormat.KEY_CHANNEL_COUNT)
            val out = ByteArrayOutputStream(); val info = MediaCodec.BufferInfo()
            var inDone = false; var outDone = false; var guard = 0
            while (!outDone && guard++ < 5000) {
                if (!inDone) {
                    val i = codec.dequeueInputBuffer(10_000)
                    if (i >= 0) {
                        val n = ex.readSampleData(codec.getInputBuffer(i)!!, 0)
                        if (n < 0) { codec.queueInputBuffer(i, 0, 0, 0, MediaCodec.BUFFER_FLAG_END_OF_STREAM); inDone = true }
                        else { codec.queueInputBuffer(i, 0, n, ex.sampleTime, 0); ex.advance() }
                    }
                }
                val o = codec.dequeueOutputBuffer(info, 10_000)
                if (o >= 0) {
                    val buf = codec.getOutputBuffer(o)!!
                    val chunk = ByteArray(info.size); buf.position(info.offset); buf.get(chunk); out.write(chunk)
                    codec.releaseOutputBuffer(o, false)
                    if (info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0) outDone = true
                } else if (o == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) {
                    val f = codec.outputFormat
                    rate = f.getInteger(MediaFormat.KEY_SAMPLE_RATE); ch = f.getInteger(MediaFormat.KEY_CHANNEL_COUNT)
                }
            }
            val bytes = out.toByteArray()
            val s = ShortArray(bytes.size / 2)
            ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN).asShortBuffer().get(s)
            return if (s.isEmpty()) null else Pcm(s, rate, ch)
        } finally {
            try { codec?.stop() } catch (_: Exception) {}
            try { codec?.release() } catch (_: Exception) {}
            ex.release()
        }
    }

    private fun writeWav(f: File, pcm: Pcm, gain: Float) {
        val n = pcm.data.size
        val bb = ByteBuffer.allocate(44 + n * 2).order(ByteOrder.LITTLE_ENDIAN)
        bb.put("RIFF".toByteArray()).putInt(36 + n * 2).put("WAVEfmt ".toByteArray()).putInt(16)
        bb.putShort(1).putShort(pcm.ch.toShort()).putInt(pcm.rate).putInt(pcm.rate * pcm.ch * 2)
        bb.putShort((pcm.ch * 2).toShort()).putShort(16).put("data".toByteArray()).putInt(n * 2)
        for (s in pcm.data) {
            val x = s * gain / 32768f
            val a = abs(x)
            // linear up to 0.8 full scale, then a smooth knee towards 1.0 (hard clipping at 300 % sounds like static)
            val y = if (a <= 0.8f) a else 0.8f + 0.2f * tanh((a - 0.8f) / 0.2f)
            bb.putShort(((if (x < 0) -y else y) * 32767f).toInt().toShort())
        }
        f.writeBytes(bb.array())
    }

    @Synchronized
    fun release() { pool?.release(); pool = null; ids.clear(); ready.clear(); am = null; loadedBoost = 100; loadedStyle = "" }
}

/** Plays the key sound that matches the key (space, backspace, return or a normal key). */
fun android.view.View.keySound(label: String) {
    val kind = when {
        label == "space" -> TypingSound.Kind.SPACE
        label == "Backspace" -> TypingSound.Kind.DELETE
        label in RETURN_LABELS -> TypingSound.Kind.RETURN
        else -> TypingSound.Kind.STANDARD
    }
    TypingSound.play(context, kind)
}

private val RETURN_LABELS = setOf("Go", "Search", "Send", "Done", "Next", "return")
