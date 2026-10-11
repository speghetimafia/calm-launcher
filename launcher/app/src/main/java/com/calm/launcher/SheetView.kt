package com.calm.launcher

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.view.MotionEvent
import android.view.View
import android.view.animation.PathInterpolator

/** The More grid and Home settings overlays (baked sheets + keys, driven by the mockup's MORE / SETTINGS lists). */
@SuppressLint("ViewConstructor")
class SheetView(ctx: Context, private val ui: Ui, private val car: Car, private val onPrefs: () -> Unit) : View(ctx) {
    enum class Kind { MORE, SETTINGS }
    var kind: Kind? = null; private set

    private val bmp = Paint(Paint.FILTER_BITMAP_FLAG or Paint.ANTI_ALIAS_FLAG)
    private val ease = PathInterpolator(.2f, .8f, .2f, 1f)
    private val more = ui.meta.getJSONArray("more").let { a -> (0 until a.length()).map { a.getJSONObject(it) } }
    private val settings = ui.meta.getJSONArray("settings").let { a -> (0 until a.length()).map { a.getJSONObject(it) } }
    private var pressed: String? = null

    init { visibility = GONE }

    fun open(k: Kind) {
        val wasHidden = kind == null
        kind = k
        visibility = VISIBLE
        if (wasHidden) { alpha = 0f; translationY = 24f; animate().alpha(1f).translationY(0f).setDuration(220).setInterpolator(ease).start() }
        invalidate()
    }

    /** Back / Done: settings → more → closed, like the mockup. */
    fun back(): Boolean {
        when (kind) {
            Kind.SETTINGS -> open(Kind.MORE)
            Kind.MORE -> close()
            null -> return false
        }
        return true
    }

    fun close(animated: Boolean = true) {
        kind = null
        if (!animated) { visibility = GONE; return }
        animate().alpha(0f).translationY(24f).setDuration(200).setInterpolator(ease).withEndAction { if (kind == null) visibility = GONE }.start()
    }

    private fun img(c: Canvas, file: String, dx: Float = 0f, dy: Float = 0f) {
        val p = ui.image(file) ?: return
        c.save(); c.translate(dx, dy); c.drawBitmap(p.bmp, null, p.dst, bmp); c.restore()
    }

    private fun st(id: String, latched: Boolean = false) = if (pressed == id || latched) "down" else "up"

    override fun onDraw(c: Canvas) {
        when (kind) {
            Kind.MORE -> {
                img(c, "more_bg")
                for (i in more.indices) img(c, "mi_${i}_${st("mi_$i")}")
                img(c, "done_more_${st("done_more")}")
            }
            Kind.SETTINGS -> {
                img(c, "set_bg")
                val all = car.prefs.all
                settings.forEachIndexed { r, row ->
                    val opts = row.getJSONArray("options")
                    for (o in 0 until opts.length()) {
                        val key = row.getString("key")
                        val on = (all[key] ?: Car.DEFAULTS[key]) == opts.get(o)   // no typed getters: can't throw
                        val id = "opt_${r}_$o"
                        img(c, "${id}_${st(id, on)}")
                        val dst = ui.rect("optled_${r}_$o"); val ref = ui.rect("optled_0_0")
                        img(c, "led_opt_${if (on) "on" else "off"}", dst.left - ref.left, dst.top - ref.top + if (pressed == id || on) 4f else 0f)
                    }
                }
                img(c, "done_settings_${st("done_settings")}")
            }
            null -> Unit
        }
    }

    private fun hit(x: Float, y: Float): String? {
        val done = if (kind == Kind.MORE) "done_more" else "done_settings"
        if (ui.rect(done).contains(x, y)) return done
        if (kind == Kind.MORE) for (i in more.indices) if (ui.rect("mi_$i").contains(x, y)) return "mi_$i"
        if (kind == Kind.SETTINGS) settings.forEachIndexed { r, row ->
            for (o in 0 until row.getJSONArray("options").length()) if (ui.rect("opt_${r}_$o").contains(x, y)) return "opt_${r}_$o"
        }
        return null
    }

    @SuppressLint("ClickableViewAccessibility")
    override fun onTouchEvent(e: MotionEvent): Boolean {
        if (kind == null) return false
        when (e.actionMasked) {
            MotionEvent.ACTION_DOWN -> { pressed = hit(e.x, e.y); invalidate() }
            MotionEvent.ACTION_UP -> {
                val id = pressed; pressed = null
                if (id != null && hit(e.x, e.y) == id) act(id)
                invalidate()
            }
            MotionEvent.ACTION_CANCEL -> { pressed = null; invalidate() }
        }
        return true                        // the sheet swallows touches meant for Home underneath
    }

    private fun act(id: String) {
        when {
            id.startsWith("done") -> back()
            id.startsWith("mi_") -> {
                val m = more[id.removePrefix("mi_").toInt()]
                when (m.optString("special")) {
                    "settings" -> open(Kind.SETTINGS)
                    "screenoff" -> { close(false); car.launch(Car.SCREEN_OFF_UI) }
                    else -> { close(false); car.launch(m.getString("component")) }
                }
            }
            id.startsWith("opt_") -> {
                val (r, o) = id.removePrefix("opt_").split('_').map { it.toInt() }
                val row = settings[r]
                val v = row.getJSONArray("options").get(o)
                car.prefs.edit().apply { if (v is Boolean) putBoolean(row.getString("key"), v) else putString(row.getString("key"), v.toString()) }.apply()
                onPrefs()
            }
        }
    }
}
