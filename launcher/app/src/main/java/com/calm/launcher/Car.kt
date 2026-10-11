package com.calm.launcher

import android.content.BroadcastReceiver
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.ServiceConnection
import android.database.ContentObserver
import android.os.Binder
import android.os.Handler
import android.os.HandlerThread
import android.os.IBinder
import android.os.Looper
import android.os.Parcel
import android.os.SystemClock
import android.provider.Settings
import android.util.Log
import java.util.Locale

enum class Src { BT, RADIO, USB }
enum class Aa { NONE, CONNECTED }

/**
 * Live car state from the NWD K2501 stereo, and the ways we act on it.
 * Every hook was taken from the stock launcher's own BT / radio / music widgets (see README → "Integration").
 * Lives for the whole process, so auto-play and auto-open work even when Home isn't showing.
 */
class Car(private val app: Context) {
    // ── state ──
    var night = false; private set
    var btName: String? = null; private set
    var btPlaying = false; private set
    var title = ""; private set
    var artist = ""; private set
    var mcuSource = 0; private set
    var lastAndroid = Src.BT; private set
    var freq = ""; private set
    var band = "FM"; private set
    var station = ""; private set
    var muted = false; private set
    var aa = Aa.NONE; private set
    var vol = 0; private set
    var maxVol = 40; private set
    var reverse = false; private set
    var inCall = false; private set
    var usbPlaying = false; private set
    var usbTitle = ""; private set
    var usbArtist = ""; private set
    var counter = 0            // track position in s, only meaningful while [posKnown]
    var total = 0; private set // track length in s from the phone, 0 while unknown
    /**
     * The BT stack can't be asked for the play position, so it is only known when we saw the track start
     * (title changed from one song to the next) and counted from there. Joining mid-song leaves it unknown.
     */
    var posKnown = false; private set
    private val lengths = object : LinkedHashMap<String, Int>() {          // title → length in s, as the phone reported it
        override fun removeEldestEntry(e: MutableMap.MutableEntry<String, Int>) = size > 30
    }

    val source: Src get() = if (mcuSource == SOURCE_RADIO) Src.RADIO else lastAndroid
    val playing: Boolean get() = when (source) {
        Src.RADIO -> !muted
        Src.USB -> usbPlaying
        Src.BT -> btName != null && btPlaying
    }
    val hasTrack: Boolean get() = btName != null && title.isNotEmpty()

    val prefs = app.getSharedPreferences("calm", Context.MODE_PRIVATE)
    val listeners = ArrayList<() -> Unit>()
    /** Volume changes from the stereo (wheel, rotary knob, our knob). */
    var onVolume: ((Int) -> Unit)? = null

    private val main = Handler(Looper.getMainLooper())
    private val io = Handler(HandlerThread("car-io").apply { start() }.looper)
    private val cr = app.contentResolver

    private val notify = Runnable { listeners.forEach { it() } }
    /** Coalesced: a burst of broadcasts / settings changes becomes one redraw. Safe from any thread. */
    private fun changed() { main.removeCallbacks(notify); main.post(notify) }

    /** Prefs never crash Home: a missing or wrongly-typed value falls back to the default. */
    fun flag(k: String) = try { prefs.getBoolean(k, DEFAULTS[k] as Boolean) } catch (e: ClassCastException) { DEFAULTS[k] as Boolean }
    fun str(k: String) = try { prefs.getString(k, null) } catch (e: ClassCastException) { null } ?: DEFAULTS[k] as String

    fun themeName() = when {
        night -> "night"
        str("theme") == "auto" -> "light"
        else -> "dark"
    }

    fun start() {
        lastAndroid = runCatching { Src.valueOf(str("lastAndroid")) }.getOrDefault(Src.BT)
        readSettings()
        io.post {                                   // system calls the first frame doesn't need: off the main thread
            for (k in listOf(K_VOL, K_SOURCE, K_LIGHT, K_BACKCAR, K_MUTE, K_MAXVOL)) {
                cr.registerContentObserver(Settings.System.getUriFor(k), false, object : ContentObserver(main) {
                    override fun onChange(self: Boolean) = readSettings()
                })
            }
            app.registerReceiver(receiver, IntentFilter().apply { ACTIONS.forEach { addAction(it) } }, null, main)
            bt.bind()
            radio.bind()
            send(Intent(RADIO_REQUEST_FREQ))
            main.post { readSettings() }            // anything that changed while we were registering
        }
        watchTrackLength()
        applyPanel()
        poll.run()
        if (SystemClock.elapsedRealtime() < 180_000) armStartBehaviour()   // unit just booted (not a reinstall/restart)
    }

    // ── Settings.System (MCU mirrors) ──
    private fun readSettings() {
        val oldVol = vol
        vol = sys(K_VOL, vol)
        maxVol = sys(K_MAXVOL, 40).coerceAtLeast(1)
        val oldSource = mcuSource
        mcuSource = sys(K_SOURCE, 0)
        val wasNight = night
        night = sys(K_LIGHT, 0) != 0
        if (night != wasNight) applyPanel()
        muted = sys(K_MUTE, 0) != 0
        val wasReverse = reverse
        reverse = sys(K_BACKCAR, 0) != 0
        if (wasReverse && !reverse) maybeOpenAa()
        if (mcuSource == SOURCE_RADIO && oldSource != SOURCE_RADIO) refreshRadio()
        if (vol != oldVol) main.post { onVolume?.invoke(vol) }
        changed()
    }

    /**
     * Daytime: the LCD's own contrast and saturation up (50 = factory) so the screen stays punchy in direct sun;
     * factory values at night (headlights on) to avoid glare. The driver resets them on reboot, so this runs
     * on start, at ignition-on and when the headlights switch.
     */
    private fun applyPanel() = io.post {
        val v = if (night) 50 else PANEL_DAY
        for (f in listOf("lcd_contrast", "lcd_satuation")) {
            try { java.io.File("/sys/devices/virtual/disp/disp/attr/$f").writeText("$v") } catch (e: Exception) { Log.w(TAG, "panel $f", e) }
        }
    }

    private fun sys(k: String, def: Int) = try { Settings.System.getInt(cr, k, def) } catch (e: Exception) { def }

    // ── broadcasts ──
    private val receiver = object : BroadcastReceiver() {
        override fun onReceive(c: Context, i: Intent) {
            i.setExtrasClassLoader(app.classLoader)     // vendor broadcasts carry their own Parcelables
            try { handle(i) } catch (e: Exception) { Log.w(TAG, "bad broadcast ${i.action}", e) }   // never crash Home
            changed()
        }

        private fun handle(i: Intent) {
            when (i.action) {
                BT_CONNECTION -> {
                    val connected = i.getIntExtra("extra_bt_connection_event", 0) == 1
                    if (!connected) { btName = null; title = ""; artist = ""; btPlaying = false; total = 0; posKnown = false; inCall = false }
                    else { btName = i.getStringExtra("extra_bt_device_name") ?: btName ?: "Phone"; armStartBehaviour() }
                    refreshBt()
                }
                A2DP_ON -> { refreshBt(); if (inWindow()) main.postDelayed(enforce, 600) }   // phone's audio link is up: start now
                A2DP_OFF -> refreshBt()
                AVRCP_ID3 -> {
                    val t = i.getStringExtra("extra_avrcp_id3_title").orEmpty()
                    val a = i.getStringExtra("extra_avrcp_id3_artist").orEmpty()
                    if (t != title) { posKnown = title.isNotEmpty() && t.isNotEmpty(); counter = 0; total = lengths[t] ?: 0 }
                    title = t; artist = a
                }
                AVRCP_RELEASE -> { title = ""; artist = ""; btPlaying = false; total = 0; posKnown = false }
                BT_PLAY -> { btPlaying = true; if (mcuSource != SOURCE_RADIO) setAndroidSource(Src.BT); onBtPlay() }
                BT_PAUSE -> btPlaying = false
                CALL_IN, CALL_OUT, CALL_ONLINE -> inCall = true
                CALL_END -> { inCall = false; maybeOpenAa() }
                RADIO_FREQ -> setFreq(i.getIntExtra("extra_frequence", 0), i.getByteExtra("extra_band", 0).toInt(), null)
                MUSIC_META, MUSIC_STATE, MUSIC_REFRESH -> {
                    usbPlaying = i.getBooleanExtra("playing", usbPlaying)
                    i.getParcelableExtra<com.acloud.newinterface.MusicBean>("id")?.let { mb ->
                        if (mb.name != usbTitle) { counter = 0; total = 0 }
                        usbTitle = mb.name; usbArtist = mb.artist?.takeUnless { it == "<unknown>" }.orEmpty()
                    }
                    if (usbPlaying && mcuSource != SOURCE_RADIO) setAndroidSource(Src.USB)
                }
                MCU_STATE -> if (i.getByteExtra("extra_mcu_state", 0).toInt() == 1) { armStartBehaviour(); refreshBt(); applyPanel() }
                KEY_ACTION -> when (i.getByteExtra("extra_key_value", 0).toInt()) {
                    KEY_PLAY, KEY_NEXT, KEY_PREV -> userActed = true
                    KEY_SHOW_LAUNCHER -> launch("com.calm.launcher/.HomeActivity")   // was handled by the stock Home screen
                }
                ZLINK -> poll.run()
            }
        }
    }

    private fun setFreq(v: Int, b: Int, ps: String?) {
        val old = freq
        band = if (b in 3..5) "AM" else "FM"
        freq = when {
            v <= 0 -> freq
            band == "AM" -> v.toString()
            v >= 6000 -> String.format(Locale.US, "%.1f", v / 100f)       // 10 kHz units: 9830 → 98.3
            v >= 600 -> String.format(Locale.US, "%.1f", v / 10f)          // 100 kHz units: 983 → 98.3
            else -> v.toString()
        }
        if (ps != null) station = ps.trim()
        else if (freq != old) {                     // new station: drop the old RDS name, ask for the new one once it's decoded
            station = ""
            main.removeCallbacks(radioRefresh); main.postDelayed(radioRefresh, 1500)
        }
    }
    private val radioRefresh = Runnable { refreshRadio() }

    // ── raw binder helper for the vendor AIDL services ──
    private open inner class Remote(val action: String, val pkg: String, val cls: String, val descriptor: String) : ServiceConnection {
        @Volatile var binder: IBinder? = null
        fun bind() {
            try { app.bindService(Intent(action).setComponent(ComponentName(pkg, cls)), this, Context.BIND_AUTO_CREATE) }
            catch (e: Exception) { Log.w(TAG, "bind $cls failed", e) }
        }
        override fun onServiceConnected(n: ComponentName, b: IBinder) { binder = b; connected() }
        override fun onServiceDisconnected(n: ComponentName) { binder = null }
        open fun connected() {}
        fun <T> call(code: Int, write: (Parcel) -> Unit = {}, read: (Parcel) -> T): T? {
            val b = binder ?: return null
            val data = Parcel.obtain(); val reply = Parcel.obtain()
            return try {
                data.writeInterfaceToken(descriptor); write(data)
                b.transact(code, data, reply, 0); reply.readException(); read(reply)
            } catch (e: Exception) { Log.w(TAG, "$cls call $code", e); null } finally { data.recycle(); reply.recycle() }
        }
    }

    // BT: com.bt.bc03/.BTService, AIDL com.bt.BTFeature
    private val bt = object : Remote("com.bt.ACTION_START_SERVICE", "com.bt.bc03", "com.bt.bc03.BTService", "com.bt.BTFeature") {
        override fun connected() {
            io.post { call(33, { it.writeStrongBinder(progress) }) { } }    // registPIMCallback; the service keeps a list
            refreshBt()
        }
        fun isA2dp() = call(36) { it.readInt() != 0 } ?: false
        fun isPlaying() = call(24) { it.readInt() != 0 } ?: false
        fun isMute() = call(59) { it.readInt() != 0 } ?: false
        fun setMute(m: Boolean) = call(39, { it.writeInt(if (m) 1 else 0) }) { }
        fun queryId3() = call(60) { }
        fun playControl(cmd: Int) = call(23, { it.writeInt(cmd) }) { it.readInt() != 0 }
        fun deviceName(): String? = call(38) { if (it.readInt() != 0) { it.readString(); it.readString() } else null }
    }

    // Radio: com.nwd.radio.service/.RadioService, AIDL com.nwd.radio.service.RadioFeature
    private val radio = object : Remote("com.nwd.radio.service.ACTION_RADIO_SERVICE", "com.nwd.radio.service",
        "com.nwd.radio.service.RadioService", "com.nwd.radio.service.RadioFeature") {
        override fun connected() = refreshRadio()
        fun seek(up: Boolean) = call(3, { it.writeInt(if (up) 1 else 0) }) { }
        /** Frequency parcel: band (byte), RDS name, frequency. */
        fun current(): Triple<Int, Int, String?>? = call(2) {
            if (it.readInt() != 0) { val b = it.readByte().toInt(); val ps = it.readString(); Triple(it.readInt(), b, ps) } else null
        }
    }

    /** BTPIMCallback stub: only onGetA2dpProgress(total s, position s) matters; contacts / call log / SMS are acked and ignored. */
    private val progress = object : Binder() {
        init { attachInterface(null, PIM) }
        override fun onTransact(code: Int, data: Parcel, reply: Parcel?, flags: Int): Boolean {
            if (code !in 1..4) return super.onTransact(code, data, reply, flags)
            if (code == 4) {
                data.enforceInterface(PIM)
                val t = data.readInt(); val p = data.readInt()
                main.post { if (source == Src.BT && t > 0 && p in 0..t) { total = t; counter = p; posKnown = true; changed() } }
            }
            reply?.writeNoException()
            return true
        }
    }

    /**
     * The phone sends each track's length (AVRCP "playing time") but the stock BT app drops it before broadcasting;
     * the gocsdk daemon logs it, so read it from there. Needs READ_LOGS (scripts/install.sh grants it).
     */
    private fun watchTrackLength() = Thread {
        while (true) {
            try {
                var name: String? = null
                val p = ProcessBuilder("logcat", "-v", "raw", "-T", "1", "-s", "nwdbt:D").redirectErrorStream(true).start()
                p.inputStream.bufferedReader().forEachLine { l ->
                    when {
                        l.startsWith("name ") -> name = l.substringAfter(':').trim()
                        l.startsWith("playing times") -> {
                            val s = l.substringAfter(':').trim().removeSuffix("ms").trim().toIntOrNull()?.div(1000)
                            val n = name
                            if (s != null && s > 0 && n != null) main.post {
                                lengths[n] = s
                                if (n == title.trim() && total != s) { total = s; changed() }
                            }
                        }
                    }
                }
                p.waitFor()
            } catch (e: Exception) { Log.w(TAG, "track length reader", e) }
            SystemClock.sleep(5000)                                   // logcat ended (shouldn't): start again
        }
    }.apply { isDaemon = true; name = "track-length" }.start()

    private fun refreshRadio() { io.post { radio.current()?.let { (f, b, ps) -> main.post { setFreq(f, b, ps); changed() } } } }

    private fun refreshBt() { io.post {
        val name = bt.deviceName()
        val a2dp = bt.isA2dp()
        val playing = bt.isPlaying() && !bt.isMute()
        if (a2dp) bt.queryId3()
        main.post {
            if (bt.binder != null) {
                btName = name?.takeIf { it.isNotBlank() } ?: if (a2dp) btName ?: "Phone" else null
                btPlaying = playing
                if (btName == null) { title = ""; artist = "" }
                changed()
            }
        }
    } }

    // ── zlink (Android Auto / CarPlay over USB), polled cheaply off the main thread ──
    private val poll = object : Runnable {
        override fun run() {
            io.removeCallbacks(this)
            io.post {
                val connected = getprop("rw.zlink.connected") == "1"
                main.post {
                    val was = aa
                    aa = if (connected) Aa.CONNECTED else Aa.NONE
                    if (was != Aa.CONNECTED && aa == Aa.CONNECTED) { aaPending = true; maybeOpenAa() }
                    if (aa != Aa.CONNECTED) aaPending = false
                    if (was != aa) changed()
                }
            }
            io.postDelayed(this, 2000)
        }
    }

    /** android.os.SystemProperties via reflection (allowed at targetSdk 29). */
    private val sysprop = try { Class.forName("android.os.SystemProperties").getMethod("get", String::class.java) } catch (e: Exception) { null }
    private fun getprop(k: String): String = try { sysprop?.invoke(null, k) as String? ?: "" } catch (e: Exception) { "" }

    private var aaPending = false
    /** Opens Android Auto once after it connects, but never over reverse camera or a call. */
    private fun maybeOpenAa() {
        if (!aaPending || aa != Aa.CONNECTED || reverse || inCall) return
        aaPending = false
        if (flag("autoAA")) launch(ZLINK_UI)
    }

    // ── phone connects / ignition on: play by default, or stay silent if the user turned it off ──
    private var windowUntil = 0L
    private var userActed = false
    private var tries = 0
    private fun inWindow() = SystemClock.elapsedRealtime() < windowUntil && !userActed
    private fun autoPlay() = flag("resumeOnStart")

    fun armStartBehaviour() {
        windowUntil = SystemClock.elapsedRealtime() + 15_000
        userActed = false
        tries = 0
        // The BT module reports connect several times (no-address, address, MCU state): one schedule, not one per report
        main.removeCallbacks(enforce)
        // A2DP + AVRCP come up a few seconds after the phone connects; try a few times
        for (d in longArrayOf(2500, 5000, 8000, 11000)) main.postDelayed(enforce, d)
    }

    private val enforce = Runnable { enforceStart() }

    private fun onBtPlay() { if (inWindow() && !autoPlay()) main.postDelayed(enforce, 400) }

    private fun enforceStart() {
        if (!inWindow() || tries >= 3 || inCall) return
        io.post {
            if (bt.binder == null || !bt.isA2dp()) return@post
            // only explicit play / pause here (never the toggle), so the repeated attempts can't undo each other
            val playing = bt.isPlaying()
            if (autoPlay()) {
                if (mcuSource == SOURCE_RADIO && lastAndroid != Src.BT) return@post       // don't hijack the radio you chose
                if (!playing || btSilenced()) {
                    tries++; setAndroidSource(Src.BT); btPrepare()
                    if (!playing) bt.playControl(BT_FORCE_PLAY)                            // explicit play: repeats can't pause it
                    Log.i(TAG, "phone connected: playing BT (was ${if (playing) "silenced" else "paused"})")
                }
            } else if (playing && mcuSource != SOURCE_RADIO) {
                tries++; bt.playControl(BT_PAUSE_CMD); Log.i(TAG, "silent start: paused BT")
            }
        }
    }

    // ── actions ──
    private fun send(i: Intent) = app.sendBroadcast(i.addFlags(Intent.FLAG_RECEIVER_FOREGROUND))
    private fun changeSource(id: Int) = send(Intent("com.nwd.action.ACTION_REQUEST_CHANGE_SOURCE").putExtra("extra_source_id", id.toByte()))
    private fun appStart(appId: Int) = send(Intent("com.nwd.action.ACTION_APP_IN_OUT")
        .putExtra("extra_app_id", appId).putExtra("extra_app_operation", 1).putExtra("extra_app_event", 0))

    /**
     * The phone can be "playing" while the stereo drops its audio: ignition-off clears the "BT music active" bit
     * (4) in nwd_arm_volume_type and the BT app then discards the stream until a MEDIA_PLAY ([btPrepare]) sets it again.
     */
    private fun btSilenced() = bt.isMute() || (sys(K_ARM_VOLUME_TYPE, 4) and 4) == 0

    /** Stock BtMusicWidget.switchToBtmusicSource(): Android source, tell the kernel BT music is active, unmute. */
    private fun btPrepare() {
        leaveRadio()
        send(Intent("com.nwd.ACTION_MEDIA_PLAY").putExtra("extra_app_id", APP_BT_MUSIC))
        bt.setMute(false)
    }

    /** Stock MusicWidget: Android source + "music app started" before any USB command. */
    private fun usbPrepare() {
        leaveRadio()
        appStart(APP_USB_MUSIC)
    }
    /** Reads the MCU's real source, not [mcuSource]: selectSource() sets that optimistically before we get here. */
    private fun leaveRadio() {
        if (sys(K_SOURCE, 0) != 0) changeSource(0)
        if (sys(K_MUTE, 0) != 0) key(KEY_MUTE)      // radio "pause" is the MCU mute key; it would silence BT / USB too
    }
    private fun usbPlayPause() = send(Intent("com.nwd.action.music.on_widget_button_click").putExtra("extra_button", 2))
    private fun usbCommand(c: Int) = send(Intent("com.nwd.ACTION_PLAY_COMMAND").putExtra("extra_command", c))

    fun launch(component: String) {
        val cn = ComponentName.unflattenFromString(component) ?: return
        try {
            app.startActivity(Intent(Intent.ACTION_MAIN).setComponent(cn).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        } catch (e: Exception) { Log.w(TAG, "launch $component", e) }
    }

    /** BT / RADIO / USB keys: switch the stereo to that source and start it playing, without opening its app. */
    fun selectSource(s: Src) {
        userActed = true
        if (s != Src.RADIO) setAndroidSource(s)
        mcuSource = if (s == Src.RADIO) SOURCE_RADIO else 0          // optimistic; the MCU confirms via Settings
        changed()
        io.post {
            when (s) {
                Src.BT -> {
                    if (usbPlaying) usbPlayPause()
                    btPrepare()
                    if (!bt.isPlaying()) bt.playControl(BT_PLAYPAUSE)
                }
                Src.USB -> {
                    if (bt.isPlaying()) bt.playControl(BT_PLAYPAUSE)
                    usbPrepare()
                    if (!usbPlaying) main.postDelayed({ usbPlayPause() }, 300)
                }
                Src.RADIO -> {
                    if (bt.isPlaying()) bt.playControl(BT_PLAYPAUSE)
                    if (usbPlaying) usbPlayPause()
                    changeSource(SOURCE_RADIO)
                    if (muted) key(KEY_MUTE)
                    main.postDelayed(::refreshRadio, 600)
                }
            }
        }
    }

    private fun setAndroidSource(s: Src) {
        if (s == Src.RADIO || s == lastAndroid) return
        lastAndroid = s
        prefs.edit().putString("lastAndroid", s.name).apply()
        changed()
    }

    /** 'p' play/pause, 'n' next/seek+, 'b' previous/seek−, each the way the stock widget for that source does it. */
    fun transport(k: Char) {
        userActed = true
        when (source) {
            Src.BT -> {
                if (k == 'p') { btPlaying = !btPlaying; changed() }        // instant feedback; broadcast confirms
                io.post {
                    // playing but silenced by the stereo: PLAY should make it heard, not pause it
                    if (k == 'p' && bt.isPlaying() && btSilenced()) { btPrepare(); main.post { btPlaying = true; changed() }; return@post }
                    btPrepare(); bt.playControl(when (k) { 'p' -> BT_PLAYPAUSE; 'n' -> BT_NEXT; else -> BT_PREV })
                }
            }
            Src.RADIO -> when (k) {
                'p' -> key(KEY_MUTE)
                else -> io.post { if (radio.binder != null) radio.seek(k == 'n') else key(if (k == 'n') KEY_NEXT else KEY_PREV) }
            }
            Src.USB -> {
                if (k == 'p') { usbPlaying = !usbPlaying; changed() }
                io.post { if (mcuSource != 0) usbPrepare(); if (k == 'p') usbPlayPause() else usbCommand(if (k == 'n') 3 else 2) }
            }
        }
    }

    /** One volume step, exactly what the steering wheel sends. */
    fun volumeStep(up: Boolean) = key(if (up) KEY_VOL_UP else KEY_VOL_DOWN)

    private fun key(v: Int) = send(Intent(KEY_ACTION).putExtra("extra_key_value", v.toByte()))


    companion object {
        const val TAG = "CalmCar"
        private const val PANEL_DAY = 65            // LCD contrast + saturation in daylight (50 = factory, 100 = max)
        private const val PIM = "com.bt.BTPIMCallback"
        val DEFAULTS = mapOf("resumeOnStart" to true, "autoAA" to true, "theme" to "auto", "accent" to "#FF5A1F", "lastAndroid" to "BT")
        private const val SOURCE_RADIO = 4
        private const val APP_BT_MUSIC = 15; private const val APP_USB_MUSIC = 2
        private const val K_VOL = "mcu_system_volume"; private const val K_MAXVOL = "mcu_max_volume"
        private const val K_SOURCE = "mcu_current_source"; private const val K_LIGHT = "mcu_light_state"
        private const val K_BACKCAR = "mcu_backcar_state"; private const val K_MUTE = "mcu_mute_state"
        private const val K_ARM_VOLUME_TYPE = "nwd_arm_volume_type"

        private const val BT_CONNECTION = "com.bt.ACTION_BT_CONNECTION_CHANGE"
        private const val A2DP_ON = "com.bt.ACTION_A2DP_ESTABLISHED"; private const val A2DP_OFF = "com.bt.ACTION_A2DP_RELEASE"
        private const val AVRCP_ID3 = "com.bt.ACTION_AVRCP_MUSIC_ID3"; private const val AVRCP_RELEASE = "com.bt.ACTION_AVRCP_RELEASE"
        private const val BT_PLAY = "com.bt.ACTION_BT_MUSIC_PLAY"; private const val BT_PAUSE = "com.bt.ACTION_BT_MUSIC_PAUSE"
        private const val CALL_IN = "com.bt.ACTION_BT_INCOMING_CALL"; private const val CALL_OUT = "com.bt.ACTION_BT_OUTGOING_CALL"
        private const val CALL_ONLINE = "com.bt.ACTION_BT_BEGIN_CALL_ONLINE"; private const val CALL_END = "com.bt.ACTION_BT_END_CALL"
        private const val RADIO_FREQ = "com.nwd.action.ACTION_SEND_RADIO_FREQUENCE_NEW"
        private const val RADIO_REQUEST_FREQ = "com.nwd.action.radiowidget_request_frequence"
        private const val MUSIC_META = "com.android.music.metachanged"; private const val MUSIC_STATE = "com.android.music.playstatechanged"
        private const val MUSIC_REFRESH = "com.nwd.ACTION_REFRESH_MUSICWIDGET"
        private const val MCU_STATE = "com.nwd.action.ACTION_MCU_STATE_CHANGE"
        private const val KEY_ACTION = "com.nwd.action.ACTION_KEY_VALUE"
        private const val ZLINK = "com.zjinnova.zlink"
        private val ACTIONS = listOf(BT_CONNECTION, A2DP_ON, A2DP_OFF, AVRCP_ID3, AVRCP_RELEASE, BT_PLAY, BT_PAUSE,
            CALL_IN, CALL_OUT, CALL_ONLINE, CALL_END, RADIO_FREQ, MUSIC_META, MUSIC_STATE, MUSIC_REFRESH, MCU_STATE, KEY_ACTION, ZLINK)

        private const val KEY_MUTE = 2; private const val KEY_PLAY = 7; private const val KEY_NEXT = 5; private const val KEY_PREV = 6
        private const val KEY_VOL_UP = 14; private const val KEY_VOL_DOWN = 15; private const val KEY_SHOW_LAUNCHER = 110
        private const val BT_PLAYPAUSE = 0; private const val BT_NEXT = 2; private const val BT_PREV = 3; private const val BT_FORCE_PLAY = 4; private const val BT_PAUSE_CMD = 1

        const val ZLINK_UI = "com.zjinnova.zlink/.main.view.SplashActivity"
        const val BT_MUSIC_UI = "com.nwd.bt.music/.home_horizontalActivity"
        const val RADIO_UI = "com.nwd.radio/.home_horizontalActivity"
        const val USB_MUSIC_UI = "com.nwd.android.music.ui/.home_horizontalActivity"
        const val PHONE_UI = "com.nwd.android.phone/.home_horizontalActivity"
        const val EQ_UI = "com.nwd.audioset/.home_horizontalActivity"
        const val SCREEN_OFF_UI = "com.android.launcher/com.nwd.screenoff.ScreenOffActivity"
    }
}
