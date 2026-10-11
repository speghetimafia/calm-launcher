package com.calm.launcher

import android.app.Application
import java.util.concurrent.FutureTask

/** One Car bridge per process: the launcher process stays alive, so state is tracked even off-screen. */
class App : Application() {
    lateinit var car: Car; private set
    /** Layout, fonts and the home screen's images, loaded off the main thread while the activity starts. */
    lateinit var ui: FutureTask<Ui>; private set
    override fun onCreate() {
        super.onCreate()
        car = Car(this).also { it.start() }
        val theme = car.themeName(); val accent = car.str("accent")
        ui = FutureTask { Ui(this, theme).also { it.preloadHome(accent) } }.also { Thread(it, "ui-load").start() }
    }
}
