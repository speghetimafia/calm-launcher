package com.calm.launcher

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.BlurMaskFilter
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.Typeface
import android.os.Handler
import android.os.Looper
import android.util.Log
import org.json.JSONObject
import java.io.IOException

/**
 * Everything baked from the mockup by tools/bake.mjs: where each image goes, element rects,
 * and the text styles Chrome measured. Nothing here is hand-tuned; re-bake to change the look.
 */
class Ui(private val ctx: Context, initialTheme: String) {
    private val json = JSONObject(ctx.assets.open("ui/layout.json").bufferedReader().use { it.readText() })
    private val img = json.getJSONObject("img")
    private val rects = json.getJSONObject("rect")
    val meta: JSONObject = json.getJSONObject("meta")
    val heros: Typeface = Typeface.createFromAsset(ctx.assets, "fonts/texgyreheros-bold.otf")
    val mono: Typeface = Typeface.createFromAsset(ctx.assets, "fonts/JetBrainsMono-Bold.ttf")

    var theme = initialTheme; private set
    private var pending: String? = null
    private var first = true
    private val main = Handler(Looper.getMainLooper())
    private val files = ctx.assets.list("ui").orEmpty().toHashSet()
    private val cache = HashMap<String, Bitmap>()
    private val placed = HashMap<String, Placed>()
    private val rectCache = HashMap<String, RectF>()
    private val specs = HashMap<String, TextSpec>()

    /**
     * light | dark | night. The first call applies at once; later ones (headlights on/off) decode the new set
     * off the UI thread so Home never freezes, then swap, drop the old set and call [ready] on the main thread.
     */
    fun setTheme(t: String, ready: () -> Unit) {
        if (first) { first = false; theme = t; return }
        if (t == theme) { pending = null; return }
        if (t == pending) return
        pending = t
        Thread {
            val fresh = decodeAll { _, at -> at == t }
            main.post {
                if (pending != t) return@post
                pending = null; theme = t
                cache.keys.removeAll { '@' in it }; cache.putAll(fresh)
                placed.clear(); specs.clear()
                ready()
            }
        }.start()
    }

    /**
     * Decodes the images [want] picks (base name, theme or "") on all cores. Hardware bitmaps: decoded straight
     * into GPU memory, so nothing is uploaded on the first frame and nothing sits on the Java heap.
     * Off the main thread only; a file that fails just loads lazily later.
     */
    private fun decodeAll(want: (String, String) -> Boolean): Map<String, Bitmap> {
        val out = java.util.concurrent.ConcurrentHashMap<String, Bitmap>()
        try {
            files.filter { it.endsWith(".png") }.map { it.removeSuffix(".png") }
                .filter { want(it.substringBefore('@'), it.substringAfter('@', "")) }
                .parallelStream().forEach { n -> decode(n)?.let { out[n] = it } }
        } catch (e: Throwable) { Log.w(Car.TAG, "preload", e) }
        return out
    }

    private fun isSheet(base: String) = SHEET.any { base.startsWith(it) }

    /** Before the first frame: every image the home screen can show in this theme, all key states, this accent only. */
    fun preloadHome(accent: String) {
        val acc = accent.drop(1).uppercase()
        cache.putAll(decodeAll { base, at ->
            (at.isEmpty() || at == theme) && !isSheet(base) && (!base.startsWith("pad_play_") || base.endsWith("_$acc"))
        })
    }

    /** After the first frame: the More / Settings sheets, so opening them never waits on decoding. */
    fun preloadSheets() {
        val t = theme
        Thread {
            val m = decodeAll { base, at -> (at.isEmpty() || at == t) && isSheet(base) }
            main.post { if (theme == t) m.forEach { (k, v) -> cache.putIfAbsent(k, v) } }
        }.apply { priority = Thread.MIN_PRIORITY }.start()
    }

    fun rect(name: String): RectF = rectCache.getOrPut(name) { rects.getJSONObject(name).toRect() }
    fun metaRect(name: String): RectF = rectCache.getOrPut("meta/$name") { meta.getJSONObject(name).toRect() }

    class Placed(val bmp: Bitmap, val dst: RectF)

    /** Image slot [slot] drawn from file [file] (themed file `file@theme.png` wins over `file.png`). Shared: don't mutate. */
    fun image(slot: String, file: String = slot, themeAs: String = theme): Placed? {
        val key = "$slot|$file@$themeAs"
        placed[key]?.let { return it }
        val p = img.optJSONObject(slot) ?: return null
        val bmp = load("$file@$themeAs") ?: load(file) ?: return null
        return Placed(bmp, p.toRect()).also { placed[key] = it }
    }

    private fun load(f: String): Bitmap? = cache[f] ?: decode(f)?.also { cache[f] = it }

    private fun decode(f: String): Bitmap? {
        if ("$f.png" !in files) return null
        return try {
            ctx.assets.open("ui/$f.png").use { BitmapFactory.decodeStream(it, null, OPTS) }
        } catch (e: IOException) { null } catch (e: OutOfMemoryError) { null }
    }

    /** [scale] < 1 is a smaller copy of the measured style (long song titles shrink to fit). */
    fun text(key: String, scale: Float = 1f): TextSpec = specs.getOrPut(if (scale == 1f) key else "$key@$scale") {
        TextSpec(json.getJSONObject("text").getJSONObject(theme).getJSONObject(key), this, scale)
    }

    fun themeVar(name: String): Int = parseCss(json.getJSONObject("vars").getJSONObject(theme).getString(name))

    companion object {
        private val OPTS = BitmapFactory.Options().apply { inScaled = false; inPreferredConfig = Bitmap.Config.HARDWARE }
        private val SHEET = listOf("more_bg", "set_bg", "mi_", "opt_", "done_", "led_opt")
    }
}

fun JSONObject.f(k: String) = getDouble(k).toFloat()
fun JSONObject.toRect() = RectF(f("x"), f("y"), f("x") + f("w"), f("y") + f("h"))

/** One text style measured in Chrome (size, letter-spacing, colour, shadows, real baseline). */
class TextSpec(j: JSONObject, ui: Ui, scale: Float = 1f) {
    val x = j.f("x"); val y = j.f("y"); val h = j.f("h") * scale
    val color = parseCss(j.getString("color"))
    val bg = parseCss(j.getString("bg"))
    val padL = j.f("padL")
    private val upper = j.getBoolean("upper")
    val lineHeight: Float = if (j.isNull("lineHeight")) h else j.f("lineHeight") * scale
    /** Offset from the element top to the first line's baseline (measured in Chrome). */
    val firstBaseline: Float = (j.f("baseline") - y) * scale
    private val shadows = parseShadows(j.getString("shadow"))
    val paint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.SUBPIXEL_TEXT_FLAG).apply {
        typeface = if (j.getBoolean("mono")) ui.mono else ui.heros
        textSize = j.f("size") * scale
        letterSpacing = j.f("ls")
        color = this@TextSpec.color
    }
    private val shadowPaint = Paint(paint)

    fun str(s: String) = if (upper) s.uppercase() else s
    fun width(s: String) = paint.measureText(str(s))

    /** Draws [s] with its CSS text-shadows, baseline at [base]. */
    fun draw(c: Canvas, s: String, x: Float, base: Float, color: Int = this.color) {
        val t = str(s)
        for (sh in shadows.asReversed()) {        // CSS paints the first shadow on top
            shadowPaint.set(paint)
            shadowPaint.color = sh.color
            shadowPaint.maskFilter = sh.filter
            c.drawText(t, x + sh.dx, base + sh.dy, shadowPaint)
        }
        paint.color = color
        c.drawText(t, x, base, paint)
    }

    /** Fits [s] into [maxW] with a trailing ellipsis, like CSS text-overflow. */
    fun ellipsize(s: String, maxW: Float): String {
        val t = str(s)
        if (paint.measureText(t) <= maxW) return t
        var n = paint.breakText(t, true, maxW - paint.measureText("…"), null)
        while (n > 0 && t[n - 1] == ' ') n--
        return t.substring(0, n) + "…"
    }

    /** Word-wraps into at most [maxLines] lines, ellipsizing the last. */
    fun wrap(s: String, maxW: Float, maxLines: Int): List<String> {
        val words = str(s).split(' ').filter { it.isNotEmpty() }
        val out = ArrayList<String>()
        var line = ""
        var i = 0
        while (i < words.size) {
            val next = if (line.isEmpty()) words[i] else "$line ${words[i]}"
            if (paint.measureText(next) <= maxW || line.isEmpty()) { line = next; i++ } else {
                out += line; line = ""
                if (out.size == maxLines - 1) { line = words.drop(i).joinToString(" "); i = words.size }
            }
        }
        if (line.isNotEmpty()) out += line
        if (out.size > maxLines) out.subList(maxLines, out.size).clear()
        if (out.isNotEmpty()) out[out.size - 1] = ellipsize(out.last(), maxW)
        return out
    }

    private class Shadow(val color: Int, val dx: Float, val dy: Float, blur: Float) {
        val filter = if (blur > 0) BlurMaskFilter(blur / 2, BlurMaskFilter.Blur.NORMAL) else null   // made once, not per draw
    }

    private fun parseShadows(s: String): List<Shadow> {
        if (s == "none") return emptyList()
        return SHADOW.findAll(s).map { m ->
            Shadow(parseCss(m.groupValues[1]), m.groupValues[2].toFloat(), m.groupValues[3].toFloat(), m.groupValues[4].toFloat())
        }.toList()
    }

    companion object {
        private val SHADOW = Regex("""((?:rgba?|color)\([^)]*\)|#[0-9a-fA-F]+)\s+(-?[\d.]+)px\s+(-?[\d.]+)px\s+(-?[\d.]+)px""")
    }
}

private val NUM = Regex("""-?[\d.]+""")
private val cssCache = HashMap<String, Int>()   // main thread only

/** CSS colour → ARGB: #rgb, #rrggbb, rgb(), rgba(), color(srgb r g b / a). */
fun parseCss(s: String): Int = cssCache.getOrPut(s) {
    val t = s.trim()
    if (t.startsWith("#")) {
        val h = if (t.length == 4) t.drop(1).map { "$it$it" }.joinToString("") else t.drop(1)
        return@getOrPut Color.parseColor("#$h")
    }
    val nums = NUM.findAll(t.substringAfter('(')).map { it.value.toFloat() }.toList()
    val a = if (nums.size > 3) nums[3] else 1f
    if (t.startsWith("color(")) Color.argb((a * 255).toInt(), (nums[0] * 255).toInt(), (nums[1] * 255).toInt(), (nums[2] * 255).toInt())
    else Color.argb((a * 255 + .5f).toInt(), nums[0].toInt(), nums[1].toInt(), nums[2].toInt())
}

/** CSS color-mix(in srgb, c p%, other). */
fun mix(c: Int, other: Int, p: Float): Int = Color.argb(
    (Color.alpha(c) * p + Color.alpha(other) * (1 - p)).toInt(),
    (Color.red(c) * p + Color.red(other) * (1 - p)).toInt(),
    (Color.green(c) * p + Color.green(other) * (1 - p)).toInt(),
    (Color.blue(c) * p + Color.blue(other) * (1 - p)).toInt(),
)

fun withAlpha(c: Int, a: Float) = Color.argb((a * 255).toInt(), Color.red(c), Color.green(c), Color.blue(c))
