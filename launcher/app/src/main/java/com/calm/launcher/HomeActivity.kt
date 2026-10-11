package com.calm.launcher

import android.app.Activity
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.Bundle
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout

/**
 * Default HOME. Draws the mockup at 1:1 on the 1024×600 panel (scaled if the window differs),
 * fullscreen with our own tabs; swiping down from the top edge brings the Android bar back.
 */
class HomeActivity : Activity() {
    private lateinit var car: Car
    private lateinit var stage: Stage

    private val clock = object : BroadcastReceiver() {
        override fun onReceive(c: Context, i: Intent) = stage.home.invalidate()
    }
    private val refresh: () -> Unit = { stage.render() }

    override fun onCreate(saved: Bundle?) {
        super.onCreate(saved)
        car = (application as App).car
        val ui = (application as App).ui.get()           // usually ready: loaded while Car and the activity started
        stage = Stage(this, ui, car)
        val root = FrameLayout(this)
        root.setBackgroundColor(0xFF000000.toInt())
        root.addView(stage, FrameLayout.LayoutParams(Stage.W, Stage.H))
        root.addOnLayoutChangeListener { _, l, t, r, b, _, _, _, _ ->      // fit other panel sizes, keep aspect
            val s = minOf((r - l) / Stage.W.toFloat(), (b - t) / Stage.H.toFloat())
            stage.pivotX = 0f; stage.pivotY = 0f; stage.scaleX = s; stage.scaleY = s
            stage.translationX = ((r - l) - Stage.W * s) / 2; stage.translationY = ((b - t) - Stage.H * s) / 2
        }
        setContentView(root)
        car.onVolume = { stage.home.onVolume(); stage.knob.onVolume() }
        root.post { ui.preloadSheets() }
    }

    override fun onResume() {
        super.onResume()
        immersive()
        car.listeners += refresh
        registerReceiver(clock, IntentFilter().apply {
            addAction(Intent.ACTION_TIME_TICK); addAction(Intent.ACTION_TIME_CHANGED); addAction(Intent.ACTION_TIMEZONE_CHANGED)
        })
        stage.render()
    }

    override fun onPause() {
        car.listeners -= refresh
        unregisterReceiver(clock)
        stage.deck.playing = false           // stop animating while Home is covered
        super.onPause()
    }

    /** HOME pressed while already home: close any sheet (like the stock launcher). */
    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        if (Intent.ACTION_MAIN == intent.action) stage.sheet.close()
    }

    override fun onBackPressed() { stage.sheet.back() }   // never leave Home with Back

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        if (hasFocus) immersive()
    }

    @Suppress("DEPRECATION")
    private fun immersive() {
        window.decorView.systemUiVisibility = (View.SYSTEM_UI_FLAG_LAYOUT_STABLE or View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION
            or View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN or View.SYSTEM_UI_FLAG_HIDE_NAVIGATION
            or View.SYSTEM_UI_FLAG_FULLSCREEN or View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY)
    }
}
