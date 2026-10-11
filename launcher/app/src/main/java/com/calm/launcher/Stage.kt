package com.calm.launcher

import android.annotation.SuppressLint
import android.content.Context
import android.view.View
import android.widget.FrameLayout

/** The 1024×600 home: HomeView + animated DeckView + More/Settings sheet. Used by HomeActivity and the snapshot tests. */
@SuppressLint("ViewConstructor")
class Stage(ctx: Context, val ui: Ui, val car: Car) : FrameLayout(ctx) {
    val home = HomeView(ctx, ui, car) { sheet.open(SheetView.Kind.MORE) }
    val deck = DeckView(ctx, ui) { home.acc }
    val knob = KnobView(ctx, ui, car, { home.acc }) { home.onVolume() }
    val sheet = SheetView(ctx, ui, car) { render() }

    init {
        // Home and the knob change at most once a second; as GPU layers the 30 fps tape deck on top
        // composites one cached texture instead of re-rendering the whole screen every frame.
        home.setLayerType(LAYER_TYPE_HARDWARE, null)
        knob.setLayerType(LAYER_TYPE_HARDWARE, null)
        addView(home, LayoutParams(W, H))
        val d = ui.image("deck")!!.dst
        addView(deck, LayoutParams(d.width().toInt() + 1, d.height().toInt() + 1).apply { leftMargin = d.left.toInt(); topMargin = d.top.toInt() })
        val k = knob.area
        addView(knob, LayoutParams(k.width().toInt() + 1, k.height().toInt() + 1).apply { leftMargin = k.left.toInt(); topMargin = k.top.toInt() })
        addView(sheet, LayoutParams(W, H))
        render()
    }

    fun render() {
        ui.setTheme(home.themeName()) { render() }
        home.restyle()
        deck.visibility = if (home.view3() == HomeView.View3.TAPE) View.VISIBLE else View.INVISIBLE
        deck.playing = car.playing
        home.invalidate(); deck.invalidate(); knob.invalidate(); sheet.invalidate()
    }

    companion object { const val W = 1024; const val H = 600 }
}
