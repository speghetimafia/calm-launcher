# Manual test plan (on the head unit)

There are no automated tests: the interesting behaviour only exists on the real unit. After `install.sh`, tick these off. The app was developed against one NWD K2501 running Android 10.

- [ ] Installs and opens; no crash in `adb logcat -b crash`
- [ ] Pressing Home opens Calm; `rollback.sh` brings the stock launcher back
- [ ] Looks like the mockup in silver, graphite and night
- [ ] Phone connects: name in the orange tab, Phone key light, song and artist, reels turn only while playing
- [ ] Prev / Play / Next control the phone; BT key switches to Bluetooth and plays
- [ ] RADIO key switches to radio on Home (no app opens); Seek −/+ move the needle; Pause mutes
- [ ] USB key plays the USB music and shows the title; Prev / Play / Next work
- [ ] Ignition off then on with music playing: music comes back (or stays paused with *Play when phone connects* off)
- [ ] Android Auto: plugging the phone in opens it; unplugging returns to Home
- [ ] Android Auto does not open over the reverse camera or during a call
- [ ] Reverse camera still appears on top of Calm
- [ ] Steering-wheel keys: volume ±, next, prev, phone, source, show-launcher (110)
- [ ] Headlights switch to the night theme
- [ ] Dragging the knob changes the volume one step per notch; tapping it opens the EQ
- [ ] Calm appears straight after ignition-on, with no flash of the stock launcher
