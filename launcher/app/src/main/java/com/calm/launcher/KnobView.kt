package com.calm.launcher

import android.animation.ValueAnimator
import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.Shader
import android.view.MotionEvent
import android.view.View
import android.view.animation.PathInterpolator
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.roundToInt
import kotlin.math.sin

/**
 * The volume knob in its own small view, so turning it redraws only the knob (not the whole screen).
 * While your finger is on it the cap follows the finger directly; the stereo catches up behind.
 */
@SuppressLint("ViewConstructor")
class KnobView(ctx: Context, private val ui: Ui, private val car: Car,
               private val acc: () -> Int, private val onStep: () -> Unit) : View(ctx) {

    /** Screen-space area this view covers: ticks, knob shadows and the value label. */
    val area: RectF = RectF(ui.image("knob_back")!!.dst).apply { union(ui.rect("kticks")); union(ui.rect("kval")); inset(-2f, -2f) }

    private val bmp = Paint(Paint.FILTER_BITMAP_FLAG or Paint.ANTI_ALIAS_FLAG)
    private val line = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; strokeCap = Paint.Cap.ROUND }
    private val ind = Paint(Paint.ANTI_ALIAS_FLAG)
    private val ease = PathInterpolator(.2f, .8f, .2f, 1f)
    private val ch = ui.rect("chassis")
    private val well = ui.rect("knobwell")

    /** Volume the knob shows: the finger's while dragging, then held until the stereo reports it. */
    private var shown = car.vol
    private var holdUntil = 0L
    private var angle = angleFor(car.vol)
    private var anim: ValueAnimator? = null
    private fun angleFor(v: Int) = -135f + v.toFloat() / car.maxVol * 270f

    /** Stereo volume changed (wheel, rotary knob, or our own steps arriving). */
    fun onVolume() {
        if (dragging || android.os.SystemClock.uptimeMillis() < holdUntil && car.vol != shown) return
        shown = car.vol
        anim?.cancel()
        anim = ValueAnimator.ofFloat(angle, angleFor(shown)).apply {
            duration = 150; interpolator = ease
            addUpdateListener { angle = it.animatedValue as Float; invalidate() }
            start()
        }
    }

    override fun onDraw(c: Canvas) {
        c.translate(-left.toFloat(), -top.toFloat())          // draw in screen coordinates
        val ticks = ui.rect("kticks")
        val s = ticks.width() / 168f
        val lit = (shown.toFloat() / car.maxVol * 20).roundToInt()
        val ink = ui.themeVar("--ink")
        val a = acc()
        for (i in 0..20) {
            val r = Math.toRadians(-135.0 + i * 13.5 - 90)
            val r1 = if (i % 5 != 0) 76 else 72
            line.strokeWidth = (if (i % 5 != 0) 1.5f else 2.5f) * s
            line.color = if (i <= lit) a else withAlpha(ink, .28f)
            c.drawLine(ticks.left + (84 + r1 * cos(r)).toFloat() * s, ticks.top + (84 + r1 * sin(r)).toFloat() * s,
                ticks.left + (84 + 81 * cos(r)).toFloat() * s, ticks.top + (84 + 81 * sin(r)).toFloat() * s, line)
        }
        c.save()
        if (pressed && !dragging) c.scale(.98f, .98f, ch.centerX(), ch.centerY())
        img(c, "knob_back")
        c.save(); c.rotate(angle, ch.centerX(), ch.centerY())
        img(c, "knob_rot")
        indicator(c, a)
        c.restore()
        img(c, "knob_front")
        c.restore()

        val nS = ui.text("kvNum"); val kS = ui.text("kval")
        val num = "$shown"; val rest = "Tap for EQ"
        val kv = ui.rect("kval")
        val x0 = kv.centerX() - (nS.width(num) + 6 + kS.width(rest)) / 2
        nS.draw(c, num, x0, nS.y + nS.firstBaseline)
        kS.draw(c, rest, x0 + nS.width(num) + 6, kS.y + kS.firstBaseline)
    }

    private fun img(c: Canvas, name: String) { ui.image(name)?.let { c.drawBitmap(it.bmp, null, it.dst, bmp) } }

    private fun indicator(c: Canvas, a: Int) {
        val i = ui.rect("knobI")
        ind.shader = LinearGradient(0f, i.top, 0f, i.bottom, intArrayOf(Color.WHITE, a, mix(a, Color.BLACK, .8f)), floatArrayOf(0f, .65f, 1f), Shader.TileMode.CLAMP)
        ind.setShadowLayer(8f, 0f, 0f, a)
        c.drawRoundRect(i, 2f, 2f, ind)
        ind.shader = null; ind.color = Color.WHITE
        ind.setShadowLayer(10f, 0f, 0f, a)
        c.drawCircle(i.centerX(), i.top - 1.5f, 3.5f, ind)
        ind.clearShadowLayer()
    }

    // ── touch: drag up/down one step per 6 px (like the mockup), tap opens EQ ──
    private var pressed = false
    private var dragging = false
    private var downY = 0f
    private var startVol = 0

    @SuppressLint("ClickableViewAccessibility")
    override fun onTouchEvent(e: MotionEvent): Boolean {
        val x = e.x + left; val y = e.y + top
        when (e.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                if (!well.contains(x, y)) return false
                pressed = true; dragging = false; downY = y; startVol = shown
                anim?.cancel(); invalidate()
            }
            MotionEvent.ACTION_MOVE -> {
                val dy = downY - y
                if (!dragging && abs(dy) > 4) dragging = true
                if (dragging) {
                    val target = (startVol + (dy / 6).roundToInt()).coerceIn(0, car.maxVol)
                    while (shown != target) {                     // one wheel-key step per notch
                        val up = target > shown
                        car.volumeStep(up)
                        shown += if (up) 1 else -1
                        onStep()
                    }
                    angle = angleFor(shown)                        // follow the finger, no easing lag
                    invalidate()
                }
            }
            MotionEvent.ACTION_UP -> {
                if (!dragging && well.contains(x, y)) car.launch(Car.EQ_UI)
                pressed = false; dragging = false
                holdUntil = android.os.SystemClock.uptimeMillis() + 1500   // keep our value while the MCU catches up
                postDelayed({ if (!dragging) onVolume() }, 1600)            // then show what the stereo really has
                invalidate()
            }
            MotionEvent.ACTION_CANCEL -> { pressed = false; dragging = false; invalidate() }
        }
        return true
    }
}
