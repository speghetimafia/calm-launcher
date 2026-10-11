package com.calm.launcher

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.graphics.Shader
import android.os.SystemClock
import android.view.MotionEvent
import android.view.View
import java.util.Calendar
import java.util.Locale

/**
 * The home screen, drawn in the mockup's z-order: baked background → tabs → clock → display text
 * → keys → knob. The tape deck animates in its own [DeckView] on top.
 */
@SuppressLint("ViewConstructor")
class HomeView(ctx: Context, private val ui: Ui, private val car: Car,
               private val onMore: () -> Unit) : View(ctx) {

    private val bmp = Paint(Paint.FILTER_BITMAP_FLAG or Paint.ANTI_ALIAS_FLAG)
    private val fill = Paint(Paint.ANTI_ALIAS_FLAG)

    // ── theme / accent ──
    fun themeName() = car.themeName()
    /** Accent, resolved once per render (not per draw call / animation frame). */
    var accentHex = ""; private set
    var acc = 0; private set
    fun restyle() {
        accentHex = car.str("accent")
        acc = parseCss(accentHex).let { if (ui.theme == "night") mix(it, Color.BLACK, .8f) else it }
    }

    // ── press / hit targets ──
    private enum class T { TAB_DEV, TAB_USB, PREV, PLAY, NEXT, BT, RADIO, USB, PHONE, SWITCH, AUTO, MORE, LCD }
    private var pressed: T? = null
    private val tabDevRect = RectF(); private val tabUsbRect = RectF()
    private val padOf = mapOf(T.PREV to "prev", T.PLAY to "play", T.NEXT to "next", T.BT to "bt", T.RADIO to "radio",
        T.USB to "usb", T.PHONE to "phone", T.SWITCH to "switch", T.AUTO to "auto", T.MORE to "more")

    // ── volume readout flash (the knob itself is KnobView) ──
    private var volFlashUntil = 0L
    fun onVolume() {
        volFlashUntil = SystemClock.uptimeMillis() + 1500
        postInvalidateDelayed(1550)
        invalidate()
    }

    // ── one-second tick between real position reports (never runs on an invented clock) ──
    private val ticker = object : Runnable {
        override fun run() {
            if (car.playing && car.source == Src.BT && car.posKnown && (car.total == 0 || car.counter < car.total)) { car.counter++; invalidate() }
            postDelayed(this, 1000)
        }
    }
    override fun onAttachedToWindow() { super.onAttachedToWindow(); postDelayed(ticker, 1000) }
    override fun onDetachedFromWindow() { removeCallbacks(ticker); super.onDetachedFromWindow() }

    // ═════════════ drawing ═════════════
    private fun img(c: Canvas, file: String, slot: String = file, themeAs: String = ui.theme, dy: Float = 0f, alpha: Int = 255) {
        val p = ui.image(slot, file, themeAs) ?: return
        bmp.alpha = alpha
        if (dy != 0f) { c.save(); c.translate(0f, dy) }
        c.drawBitmap(p.bmp, null, p.dst, bmp)
        if (dy != 0f) c.restore()
        bmp.alpha = 255
    }

    override fun onDraw(c: Canvas) {
        img(c, "bg")
        drawTabs(c)
        drawPlate(c)
        drawDisplay(c)
        drawPads(c)
    }

    // ── port tabs ──
    private class TabStyle(val stops: IntArray, val pos: FloatArray, val border: Int)
    private fun tabStyle(kind: String) = when (kind) {
        "o" -> TabStyle(intArrayOf(parseCss("#ff773d"), acc, mix(acc, Color.BLACK, .78f)), floatArrayOf(0f, .5f, 1f), parseCss("rgba(0,0,0,.3)"))
        "k" -> TabStyle(intArrayOf(parseCss("#31343a"), parseCss("#1c1e22"), parseCss("#121316")), floatArrayOf(0f, .5f, 1f), parseCss("#0b0c0e"))
        "g" -> TabStyle(intArrayOf(parseCss("#cbced4"), parseCss("#b5b9c2"), parseCss("#a3a8b2")), floatArrayOf(0f, .5f, 1f), parseCss("rgba(0,0,0,.22)"))
        else -> TabStyle(intArrayOf(parseCss("#8e929b"), parseCss("#7a7e87")), floatArrayOf(0f, 1f), parseCss("#5a5e66"))
    }

    private val path = Path(); private val edge = Path()
    private val radii = floatArrayOf(4f, 4f, 4f, 4f, 0f, 0f, 0f, 0f)

    private fun drawTab(c: Canvas, r: RectF, kind: String, spec: TextSpec, text: String, down: Boolean) {
        val s = tabStyle(kind)
        c.save()
        if (down) c.translate(0f, 2f)
        path.reset(); path.addRoundRect(r, radii, Path.Direction.CW)
        fill.color = Color.BLACK                      // shader paints still use the paint's alpha
        fill.shader = LinearGradient(0f, r.top, 0f, r.bottom, s.stops, s.pos, Shader.TileMode.CLAMP)
        c.drawPath(path, fill); fill.shader = null
        fill.color = parseCss("rgba(255,255,255,.7)"); c.drawRect(r.left + 3, r.top + 1, r.right - 3, r.top + 2, fill)
        fill.style = Paint.Style.STROKE; fill.strokeWidth = 1f; fill.color = s.border
        edge.reset()
        edge.moveTo(r.left + .5f, r.bottom); edge.lineTo(r.left + .5f, r.top + 4); edge.quadTo(r.left + .5f, r.top + .5f, r.left + 4, r.top + .5f)
        edge.lineTo(r.right - 4, r.top + .5f); edge.quadTo(r.right - .5f, r.top + .5f, r.right - .5f, r.top + 4); edge.lineTo(r.right - .5f, r.bottom)
        c.drawPath(edge, fill)
        fill.style = Paint.Style.FILL
        spec.draw(c, text, r.left + 15, r.top + spec.firstBaseline)      // 1 px border + 14 px padding
        if (down) { fill.color = parseCss("rgba(0,0,0,.08)"); c.drawPath(path, fill) }
        c.restore()
    }

    private fun drawTabs(c: Canvas) {
        val oSpec = ui.text(if (car.btName != null) "tabO" else "tabOff")
        val top = oSpec.y; val h = oSpec.h
        val left = ui.rect("tabs").left
        val devText = car.btName ?: "No phone"
        tabDevRect.set(left, top, left + 30 + oSpec.width(devText), top + h)
        drawTab(c, tabDevRect, if (car.btName != null) "o" else "off", oSpec, devText, pressed == T.TAB_DEV)

        val kSpec = ui.text("tabK")
        val usbText = "USB · " + if (car.aa == Aa.CONNECTED) "AA on" else "—"
        tabUsbRect.set(tabDevRect.right + 6, top, tabDevRect.right + 6 + 30 + kSpec.width(usbText), top + h)
        drawTab(c, tabUsbRect, "k", kSpec, usbText, pressed == T.TAB_USB)
        // indicator nub on the USB tab
        val nub = RectF(tabUsbRect.right - 26, tabUsbRect.top - 6, tabUsbRect.right - 12, tabUsbRect.top)
        val lit = car.aa == Aa.CONNECTED
        fill.color = if (lit) parseCss("#ff3b1f") else parseCss("#5a5e66")
        if (lit) fill.setShadowLayer(10f, 0f, 0f, parseCss("#ff3b1f"))
        c.drawRoundRect(nub, 3f, 3f, fill)
        fill.clearShadowLayer()

        val gSpec = ui.text("tabG")
        val volText = "Vol ${car.vol}"
        val right = ui.rect("tabs").right
        drawTab(c, RectF(right - 30 - gSpec.width(volText), top, right, top + h), "g", gSpec, volText, false)
    }

    // ── clock plate: time | divider | weekday over date ──
    private fun drawPlate(c: Canvas) {
        val now = Calendar.getInstance()
        val h = now.get(Calendar.HOUR).let { if (it == 0) 12 else it }
        val time = String.format(Locale.US, "%d:%02d", h, now.get(Calendar.MINUTE))
        val ampm = if (now.get(Calendar.AM_PM) == Calendar.AM) "AM" else "PM"
        val gaps = ui.meta.getJSONObject("plateGaps")
        val tS = ui.text("time"); val aS = ui.text("ampm"); val wS = ui.text("wday"); val dS = ui.text("date")
        tS.draw(c, time, tS.x, tS.y + tS.firstBaseline)
        val ax = tS.x + tS.width(time) + gaps.f("timeToAmpm")
        aS.draw(c, ampm, ax, aS.y + aS.firstBaseline)
        val div = ui.rect("plateDiv")
        val dx = ax + aS.width(ampm) + gaps.f("ampmToDiv")
        fill.color = withAlpha(ui.themeVar("--ink3"), .45f)
        c.drawRoundRect(dx, div.top, dx + div.width(), div.bottom, 1f, 1f, fill)
        val tx = dx + div.width() + gaps.f("divToText")
        wS.draw(c, now.getDisplayName(Calendar.DAY_OF_WEEK, Calendar.LONG, Locale.UK)!!, tx, wS.y + wS.firstBaseline, acc)
        dS.draw(c, "${now.get(Calendar.DAY_OF_MONTH)} ${now.getDisplayName(Calendar.MONTH, Calendar.LONG, Locale.UK)}", tx, dS.y + dS.firstBaseline)
    }

    // ── the black display: source grid, what's playing, big readout ──
    enum class View3 { TAPE, RADIO, EMPTY }
    fun view3(): View3 = when {
        car.source == Src.RADIO -> View3.RADIO
        car.source == Src.BT && !car.hasTrack && !car.playing -> View3.EMPTY
        else -> View3.TAPE
    }

    private fun drawDisplay(c: Canvas) {
        val v = view3()
        val playing = car.playing

        // text column
        val tag: String; val dev: String; val title: String; val artist: String
        when {
            v == View3.RADIO -> { tag = car.band; dev = "Radio · stereo"; title = car.station.ifEmpty { "${car.band} Radio" }
                artist = if (car.freq.isEmpty()) "Tuning…" else "${car.band} ${car.freq} ${if (car.band == "FM") "MHz" else "kHz"}" }
            car.source == Src.USB -> { tag = "USB"; dev = "Music · USB"; title = car.usbTitle.ifEmpty { "USB music" }; artist = car.usbArtist.ifEmpty { "USB music" } }
            v == View3.EMPTY -> { tag = "BT"; dev = car.btName ?: "Not connected"; title = "No signal"
                artist = car.btName?.let { "Press play on $it" } ?: "Press SWITCH to connect a phone" }
            else -> { tag = "BT"; dev = car.btName ?: ""; title = car.title.ifEmpty { "Bluetooth audio" }; artist = car.artist }
        }
        val meta = ui.metaRect(when (v) { View3.TAPE -> "metaTape"; View3.RADIO -> "metaRadio"; View3.EMPTY -> "metaEmpty" })
        val tagrow = ui.metaRect("tagrow")
        val gap = ui.meta.f("gap")
        val tagS = ui.text("srcTag"); val devS = ui.text("srcDev")
        val aS = ui.text(if (v == View3.EMPTY) "artistEmpty" else "artist")
        // song titles show in full: 2 lines at full size, else 3 lines a step or two smaller
        val tKey = if (v == View3.EMPTY) "titleEmpty" else "title"
        var tS = ui.text(tKey)
        var tLines = tS.wrap(title.ifEmpty { " " }, meta.width(), 2)
        if (v == View3.TAPE) for (sc in floatArrayOf(.82f, .7f)) {
            if (tS.wrap(title, meta.width(), 99).size <= tLines.size) break
            tS = ui.text(tKey, sc); tLines = tS.wrap(title, meta.width(), 3)
        }
        val aLines = if (v == View3.EMPTY) aS.wrap(artist, meta.width(), 2) else listOf(aS.ellipsize(artist, meta.width()))
        val fmScale = v == View3.RADIO && car.band == "FM"
        val scaleH = if (fmScale) gap + 4 + 26 else 0f
        val metaH = tagrow.height() + gap + tLines.size * tS.lineHeight + gap + aLines.size * aS.lineHeight + scaleH
        val lcd = ui.rect("lcd")
        var y = lcd.top + (lcd.height() - metaH) / 2
        val x = meta.left
        // tag box + device name
        val boxW = tagS.width(tag) + tagS.padL * 2
        val boxTop = y + (tagS.y - tagrow.top)
        fill.color = tagS.bg
        c.drawRect(x, boxTop, x + boxW, boxTop + tagS.h, fill)
        tagS.draw(c, tag, x + tagS.padL, boxTop + tagS.firstBaseline)
        devS.draw(c, devS.ellipsize(dev, meta.width() - boxW - 10), x + boxW + 10, y + (devS.y - tagrow.top) + devS.firstBaseline)
        y += tagrow.height() + gap
        tLines.forEachIndexed { i, s -> tS.draw(c, s, x, y + tS.firstBaseline + i * tS.lineHeight) }
        y += tLines.size * tS.lineHeight + gap
        val artColor = if (v == View3.EMPTY) aS.color else acc
        aLines.forEachIndexed { i, s -> aS.draw(c, s, x, y + aS.firstBaseline + i * aS.lineHeight, artColor) }
        y += aLines.size * aS.lineHeight
        if (fmScale) drawScale(c, RectF(x, y + gap + 4, meta.right, y + gap + 4 + 26))

        // readout
        val flash = SystemClock.uptimeMillis() < volFlashUntil
        val r = when {
            flash -> arrayOf("Vol", "88", String.format(Locale.US, "%02d", car.vol), "of ${car.maxVol}")
            v == View3.RADIO -> arrayOf(if (car.band == "FM") "MHz" else "kHz", car.freq.replace(digit, "8").ifEmpty { "88.8" },
                car.freq.ifEmpty { "--.-" }, if (car.muted) "Muted" else "On air")
            v == View3.EMPTY -> arrayOf("Time", "88:88", "--:--", "Waiting")
            car.source == Src.BT && car.posKnown -> arrayOf("Time", "88:88", String.format(Locale.US, "%02d:%02d", car.counter / 60, car.counter % 60),
                if (car.total > 0) String.format(Locale.US, "of %d:%02d", car.total / 60, car.total % 60) else "Since track start")
            car.source == Src.BT && car.total > 0 -> arrayOf("Length", "88:88",            // joined mid-song: the real length, no guessed position
                String.format(Locale.US, "%02d:%02d", car.total / 60, car.total % 60), "Track length")
            else -> arrayOf("Time", "88:88", "--:--", "Not reported")
        }
        val rt = ui.text("roTag")
        val rtW = rt.width(r[0]) + rt.padL * 2
        fill.color = rt.bg
        c.drawRect(rt.x, rt.y, rt.x + rtW, rt.y + rt.h, fill)
        rt.draw(c, r[0], rt.x + rt.padL, rt.y + rt.firstBaseline)
        val show = v != View3.EMPTY
        img(c, "stPlay_${if (show && playing) "on" else "off"}")
        img(c, "stPause_${if (show && !playing) "on" else "off"}")
        img(c, "stRec_${if (show && playing) "on" else "off"}")
        val ro = ui.rect("ro")
        val gS = ui.text("roGhost"); val vS = ui.text("roVal"); val bS = ui.text("roBot")
        gS.draw(c, r[1], ro.right - gS.width(r[1]), gS.y + gS.firstBaseline)
        vS.draw(c, r[2], ro.right - vS.width(r[2]), vS.y + vS.firstBaseline, if (flash) acc else vS.color)
        bS.draw(c, r[3], ro.right - bS.width(r[3]), bS.y + bS.firstBaseline)
    }

    private val label = Paint(Paint.ANTI_ALIAS_FLAG).apply { typeface = ui.mono; textSize = 8f; textAlign = Paint.Align.CENTER; color = Color.argb(153, 255, 255, 255) }
    private val digit = Regex("\\d")

    /** The mockup's drawScale(): 87.5–108 MHz ticks with the accent needle, stretched to the column. */
    private fun drawScale(c: Canvas, r: RectF) {
        val sx = r.width() / 360f
        fill.style = Paint.Style.STROKE; fill.strokeWidth = 1.5f
        for (m in 88..108) {
            val x = r.left + ((m - 87.5f) / 20.5f * 350 + 5) * sx
            val big = m % 4 == 0
            fill.color = Color.argb(if (big) 179 else 89, 255, 255, 255)
            c.drawLine(x, r.top + if (big) 2f else 8f, x, r.top + 14, fill)
            if (big) { c.save(); c.scale(sx, 1f, x, 0f); c.drawText("$m", x, r.top + 25, label); c.restore() }
        }
        val v = if (car.band == "FM") car.freq.toFloatOrNull() ?: 98f else 98f
        val nx = r.left + ((v - 87.5f) / 20.5f * 350 + 5) * sx
        fill.color = acc; fill.strokeWidth = 3f
        c.drawLine(nx, r.top, nx, r.top + 16, fill)
        fill.style = Paint.Style.FILL
    }

    // ── keys ──
    private fun down(t: T, latched: Boolean = false) = pressed == t || latched
    private fun st(t: T, latched: Boolean = false) = if (down(t, latched)) "down" else "up"

    private fun drawPads(c: Canvas) {
        val radio = car.source == Src.RADIO
        val dim = if (view3() == View3.EMPTY) (255 * .45f).toInt() else 255
        img(c, "pad_prev_${if (radio) "seek" else "prev"}_${st(T.PREV)}", alpha = dim)
        val playFile = "pad_play_${if (car.playing) "pause" else "play"}_${st(T.PLAY)}_${accentHex.drop(1).uppercase()}"
        img(c, playFile, themeAs = if (ui.theme == "night") "night" else "light", alpha = dim)
        img(c, "pad_next_${if (radio) "seek" else "next"}_${st(T.NEXT)}", alpha = dim)

        for ((t, key, src) in listOf(Triple(T.BT, "bt", Src.BT), Triple(T.RADIO, "radio", Src.RADIO), Triple(T.USB, "usb", Src.USB))) {
            val on = car.source == src
            img(c, "pad_${key}_${st(t, on)}")
            led(c, key, "led_g", "bt", on, down(t, on))
        }

        img(c, "pad_phone_${st(T.PHONE)}")
        led(c, "phone", "led_k", "phone", car.btName != null, down(T.PHONE))
        val ph = ui.text("phSub")
        ph.draw(c, ph.ellipsize(car.btName ?: "No phone", 80f), ph.x, ph.y + ph.firstBaseline + if (down(T.PHONE)) 4 else 0)

        img(c, "pad_switch_${st(T.SWITCH)}")

        val aaOn = car.aa == Aa.CONNECTED
        img(c, "pad_auto_${st(T.AUTO, aaOn)}")
        led(c, "auto", "led_g", "bt", aaOn, down(T.AUTO, aaOn))
        val aa = ui.text("aaSub")
        aa.draw(c, if (aaOn) "Connected" else "AA/CarPlay",
            aa.x, aa.y + aa.firstBaseline + if (down(T.AUTO, aaOn)) 4 else 0)

        img(c, "pad_more_${st(T.MORE)}")
    }

    /** The LED image was baked at the [ref] key's LED; shift it to [key]'s. */
    private fun led(c: Canvas, key: String, file: String, ref: String, on: Boolean, down: Boolean) {
        val p = ui.image("${file}_${if (on) "on" else "off"}") ?: return
        val dst = ui.rect("led_$key"); val src = ui.rect("led_$ref")
        c.save(); c.translate(dst.left - src.left, dst.top - src.top + if (down) 4f else 0f)
        c.drawBitmap(p.bmp, null, p.dst, bmp)
        c.restore()
    }

    // ═════════════ touch ═════════════
    private fun hit(x: Float, y: Float): T? {
        if (tabDevRect.contains(x, y)) return T.TAB_DEV
        if (tabUsbRect.contains(x, y)) return T.TAB_USB
        for ((t, key) in padOf) if (ui.rect("pad_$key").contains(x, y)) return t
        if (ui.rect("lcd").contains(x, y)) return T.LCD
        return null
    }

    @SuppressLint("ClickableViewAccessibility")
    override fun onTouchEvent(e: MotionEvent): Boolean {
        when (e.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                pressed = hit(e.x, e.y) ?: return false
                invalidate()
            }
            MotionEvent.ACTION_UP -> {
                val t = pressed; pressed = null
                if (t != null && hit(e.x, e.y) == t) act(t)
                invalidate()
            }
            MotionEvent.ACTION_CANCEL -> { pressed = null; invalidate() }
        }
        return true
    }

    private fun act(t: T) = when (t) {
        T.TAB_DEV, T.SWITCH, T.PHONE -> car.launch(Car.PHONE_UI)
        T.TAB_USB, T.AUTO -> car.launch(Car.ZLINK_UI)
        T.PREV -> car.transport('b')
        T.PLAY -> car.transport('p')
        T.NEXT -> car.transport('n')
        T.BT -> car.selectSource(Src.BT)          // switch + play, stay on Home
        T.RADIO -> car.selectSource(Src.RADIO)
        T.USB -> car.selectSource(Src.USB)
        T.MORE -> onMore()
        T.LCD -> car.launch(when (car.source) { Src.RADIO -> Car.RADIO_UI; Src.USB -> Car.USB_MUSIC_UI; Src.BT -> Car.BT_MUSIC_UI })
    }
}
