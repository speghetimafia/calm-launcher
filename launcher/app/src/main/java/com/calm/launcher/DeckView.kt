package com.calm.launcher

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.BlurMaskFilter
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import android.view.View
import android.view.animation.PathInterpolator
import kotlin.math.roundToInt

/**
 * The open-reel deck inside the display. Static body is baked; the spools, level bars and head
 * glow move only while something plays (the mockup's rule: nothing animates at rest).
 */
@SuppressLint("ViewConstructor")
class DeckView(ctx: Context, private val ui: Ui, private val acc: () -> Int) : View(ctx) {
    var playing = false
        set(v) { if (field != v) { field = v; last = 0L; invalidate() } }

    private val bmp = Paint(Paint.FILTER_BITMAP_FLAG or Paint.ANTI_ALIAS_FLAG)
    private val p = Paint(Paint.ANTI_ALIAS_FLAG)
    private val ease = PathInterpolator(.42f, 0f, .58f, 1f)      // CSS ease-in-out
    private val blurs = Array(6) { BlurMaskFilter(3f + it, BlurMaskFilter.Blur.NORMAL) }   // 3…8 px, no per-frame allocation

    private val deck = ui.rect("deck")
    private val reels = ui.meta.getJSONArray("reels").let { a -> (0 until a.length()).map { a.getJSONObject(it) } }
    private val bars = ui.meta.getJSONArray("bars").let { a -> (0 until a.length()).map { a.getJSONObject(it) } }
    private val glow = ui.meta.getJSONObject("headGlow")

    // spool speeds from the mockup: 3.2 s and 2.1 s per turn, clockwise
    private val period = floatArrayOf(3200f, 2100f)
    private val spin = floatArrayOf(0f, 0f)
    private var last = 0L
    private var t = 0L
    private val d = RectF()

    // .b1….b8 → (keyframes 0/50/100 %, duration, delay) exactly as in the CSS
    private val k1 = floatArrayOf(.2f, .9f, .4f); private val k2 = floatArrayOf(.35f, .7f, .25f)
    private val k3 = floatArrayOf(.15f, 1f, .5f); private val k4 = floatArrayOf(.4f, .85f, .2f)
    private val anim = listOf(Triple(k1, 420, 0), Triple(k2, 360, 80), Triple(k3, 540, 40), Triple(k4, 380, 140),
        Triple(k3, 460, 60), Triple(k1, 320, 120), Triple(k4, 500, 20), Triple(k2, 400, 160))

    override fun onDraw(c: Canvas) {
        val now = System.nanoTime() / 1_000_000
        if (playing) {
            if (last != 0L) { val dt = now - last; t += dt; for (i in 0..1) spin[i] = (spin[i] + dt / period[i] * 360f) % 360f }
            last = now
        }
        c.translate(-left.toFloat(), -top.toFloat())       // draw in screen coordinates
        ui.image("deck")?.let { c.drawBitmap(it.bmp, null, it.dst, bmp) }
        val spool = ui.image("spool")
        reels.forEachIndexed { i, r ->
            val cx = r.f("cx"); val cy = r.f("cy")
            spool?.let {                                    // baked once at reel 0; offset to this reel
                d.set(it.dst); d.offset(cx - reels[0].f("cx"), cy - reels[0].f("cy"))
                c.save(); c.rotate(spin[i], cx, cy)
                c.drawBitmap(it.bmp, null, d, bmp)
                c.restore()
            }
        }
        val a = acc()
        p.color = a
        p.alpha = if (playing) 255 else (255 * .7f).toInt()
        bars.forEachIndexed { i, b ->
            val s = if (playing) level(anim[i]) else .18f
            val x = deck.left + b.f("x"); val bottom = deck.top + b.f("y") + b.f("h")
            c.drawRoundRect(x, bottom - b.f("h") * s, x + b.f("w"), bottom, b.f("rx"), b.f("rx"), p)
        }
        // head glow: .4 at rest, pulsing .7↔1 with a 3↔8 px glow over 1.6 s while playing
        val gx = deck.left + glow.f("cx"); val gy = deck.top + glow.f("cy"); val gr = glow.f("r")
        val phase = if (playing) ease.getInterpolation(pingPong(t, 1600, 0)) else 0f
        val op = if (playing) .7f + .3f * phase else .4f
        if (playing) {
            p.color = a; p.alpha = (255 * op).toInt()
            p.maskFilter = blurs[(5 * phase).roundToInt()]
            c.drawCircle(gx, gy, gr + 2, p); p.maskFilter = null
        }
        p.color = a; p.alpha = (255 * op).toInt()
        c.drawCircle(gx, gy, gr, p)
        p.color = Color.WHITE; p.alpha = (255 * .8f).toInt()
        c.drawCircle(gx, gy, 1f, p)
        if (playing) postInvalidateDelayed(FRAME_MS)
    }

    companion object {
        /** ~30 fps: reels and meters look the same, and the Mali-G31 + CPU do half the work of vsync-rate redraws. */
        private const val FRAME_MS = 33L
    }

    /** 0→1→0 alternate progress for a CSS `alternate infinite` animation. */
    private fun pingPong(time: Long, dur: Int, delay: Int): Float {
        val x = (time - delay).coerceAtLeast(0) / dur.toFloat()
        val cycle = x.toInt(); val f = x - cycle
        return if (cycle % 2 == 0) f else 1 - f
    }

    private fun level(a: Triple<FloatArray, Int, Int>): Float {
        val (k, dur, delay) = a
        if (t < delay) return .18f
        val f = pingPong(t, dur, delay)
        return if (f < .5f) k[0] + (k[1] - k[0]) * ease.getInterpolation(f * 2)
        else k[1] + (k[2] - k[1]) * ease.getInterpolation((f - .5f) * 2)
    }
}
