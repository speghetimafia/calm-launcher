#!/bin/sh
# Apply an AK7602 DSP tune to the K2501: ./eq/apply.sh eq/calm.xml   (undo: ./eq/apply.sh eq/original.xml)
# The stock DSP service re-sends its saved settings to the chip on start and at every ignition-on.
# key_7602_gain: 14 bands 32 64 125 250 380 500 680 750 1k 2k 4k 6k 8k 16k Hz; 15 = 0 dB, 2 steps = 1 dB, max +6 dB (27).
# key_7602_qvalue: Q x10. key_bass_freq: Hz-20, key_bass_gain: dB+7.
# key_speaker_delay_time: ms per speaker FL,FR,RL,RR (calm.xml: aligned to the right-hand driver seat; calm-allseats.xml: none).
# key_speaker_gain: FL,FR,RL,RR,C,Sub (7 = default).
# The settings folder is then locked read-only, so the stock EQ app (sliders, presets, Reset) can't save over it:
# anything changed there only lasts until the next ignition-on, when the DSP service re-applies this file.
set -e
[ -f "$1" ] || { echo "usage: $0 <DspData.xml>"; exit 1; }
export ANDROID_SERIAL=${UNIT:?set UNIT to the head unit, e.g. UNIT=192.168.1.50:5555}
adb connect "$ANDROID_SERIAL" >/dev/null
adb push "$1" /data/local/tmp/DspData.xml >/dev/null
P=/data/data/com.nwd.audioset/shared_prefs/DspData.xml
D=$(dirname $P)
adb shell "am force-stop com.nwd.audioset; chmod 771 $D; rm -f $P.bak; cp /data/local/tmp/DspData.xml $P; chown 10051:10051 $P; chmod 440 $P; chmod 551 $D; am startservice -a com.nwd.ACTION_DSP_SERVICE -n com.nwd.audioset/com.nwd.dsp.utils.DspServices >/dev/null"
echo "applied $1"
