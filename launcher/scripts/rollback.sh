#!/bin/sh
# Put the stock NWD launcher back as Home. Calm stays installed (add --uninstall to remove it).
set -e
UNIT=${UNIT:?set UNIT to the head unit, e.g. UNIT=192.168.1.50:5555}
export ANDROID_SERIAL=$UNIT
adb connect "$UNIT" >/dev/null
adb shell setprop persist.nwd.launcher.default com.android.launcher      # the value the unit shipped with
adb shell cmd package set-home-activity com.android.launcher/com.android.launcher2.Launcher >/dev/null
adb shell cmd role add-role-holder android.app.role.HOME com.android.launcher >/dev/null 2>&1 || true
[ "$1" = "--uninstall" ] && adb uninstall com.calm.launcher
adb shell am start -a android.intent.action.MAIN -c android.intent.category.HOME >/dev/null
echo "Home is now: $(adb shell cmd package resolve-activity --brief -c android.intent.category.HOME -a android.intent.action.MAIN | tail -1)"
