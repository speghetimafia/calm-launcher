# How Calm talks to the NWD K2501

Everything below was found by reading what the unit's own apps send and receive (the stock launcher's Bluetooth, radio and music widgets, and the Bluetooth app) and by watching `logcat` on a real unit. No vendor code is included in this repo. Names are listed for interoperability.

Source ids: `mcu_current_source` 4 = radio, 0 = Android audio (Bluetooth music or USB music, whichever was used last).

| What | How |
|---|---|
| Default Home | NWD's `PackageManagerService.chooseBestActivity()` returns the package in the system property `persist.nwd.launcher.default` and ignores the usual preferred-Home and HOME-role settings. `scripts/install.sh` sets it, `rollback.sh` restores `com.android.launcher`. |
| BT phone name, A2DP, playing | Bind `com.bt.bc03/.BTService` (AIDL `com.bt.BTFeature`, raw binder transactions 36/24/59/60/38) plus the broadcasts `com.bt.ACTION_BT_CONNECTION_CHANGE` (event 1 = connected) and `ACTION_BT_MUSIC_PLAY/PAUSE`. |
| Song / artist | `com.bt.ACTION_AVRCP_MUSIC_ID3` (`extra_avrcp_id3_title`, `extra_avrcp_id3_artist`), cleared by `ACTION_AVRCP_RELEASE`. |
| BT play / next / prev | If needed `ACTION_REQUEST_CHANGE_SOURCE(0)`, then `ACTION_MEDIA_PLAY(app 15)` and `BTFeature.setBtMusicMute(false)`, then `playControl`. This is what the stock Bluetooth widget does. |
| BT / RADIO / USB keys | Switch source and play without opening an app. Bluetooth as above; radio `ACTION_REQUEST_CHANGE_SOURCE(4)`; USB source 0 + `ACTION_APP_IN_OUT(app 2)` + play. |
| Radio | Service `com.nwd.radio.service/.RadioService` (`RadioFeature.seek`, `getCurrentFrequency` for the frequency and RDS name); frequency broadcasts `com.nwd.action.ACTION_SEND_RADIO_FREQUENCE_NEW` (`extra_frequence`, `extra_band`); pause = the MCU mute key. |
| USB transport / song | `com.nwd.action.music.on_widget_button_click` (2 = play/pause), `com.nwd.ACTION_PLAY_COMMAND` (2 prev, 3 next); the song comes from `com.android.music.playstatechanged`, which carries a `MusicBean` parcelable (reimplemented in `com.acloud.newinterface`; without it the broadcast cannot be read). |
| Volume | Reads `mcu_system_volume` / `mcu_max_volume`; steps with the steering-wheel key broadcast `com.nwd.action.ACTION_KEY_VALUE` (14 / 15), one per knob notch. |
| Night | `mcu_light_state` (headlights). |
| Reverse / calls | `mcu_backcar_state`; `com.bt.ACTION_BT_INCOMING/OUTGOING_CALL`, `BEGIN_CALL_ONLINE`, `END_CALL`. Android Auto auto-open waits for both. |
| Android Auto / CarPlay | System property `rw.zlink.connected`, polled every 2 s off the main thread; opens `com.zjinnova.zlink/.main.view.SplashActivity`. |
| Wheel "show launcher" key (110) | Brings Calm to the front (the stock Home screen used to handle it). |
| LCD contrast | Writes the display's sysfs attributes (65 by day, factory 50 at night). |

## Behaviour notes
- **No sounds.** NWD's `NwdAudioService` treats any audio from a non-NWD app as an interruption and mutes and pauses Bluetooth music (`com.nwd.android.ACTION_STREAM_MUSIC` → `ACTION_A2DP_MUTE`). So the launcher plays no key clicks.
- **Play when the phone connects** runs on Bluetooth connect, ignition-on and boot: switch to Bluetooth, unmute, play. With the setting off it pauses instead. `playControl(0)` is a *toggle*, so it uses explicit play (4) and pause (1), and the Bluetooth module can announce a connection two or three times, so there is one schedule and a 4 s guard.
- **After ignition-off** the stock BT app clears bit 4 of `nwd_arm_volume_type` and drops the audio while the phone keeps "playing". The launcher detects that and sends `ACTION_MEDIA_PLAY` to restore it.
- **Song length** comes from the phone but is dropped by the stock Bluetooth app, so the launcher reads it from the Bluetooth daemon's log (`logcat -s nwdbt`, needs `READ_LOGS`). The stack cannot report the play *position*, so the elapsed time is counted from an observed track change.
- **Performance:** the home screen and knob are separate GPU layers (the tape deck animation does not redraw the screen), bitmaps are preloaded off the main thread, and the app is AOT-compiled at install.
- **Power:** the unit sleeps about 60 s after ACC goes off, on the MCU's timer; Calm holds no wake-locks.

## `eq/`
An optional tune for the unit's AK7602 DSP (`calm.xml`, `calm-allseats.xml`; undo with `original.xml`). The stock DSP service re-sends its saved settings to the chip on start and at every ignition-on, so `apply.sh` writes the file and locks the settings folder read-only. Band layout: 14 bands (32 Hz … 16 kHz), 15 = 0 dB, two steps per dB, maximum +6 dB.
