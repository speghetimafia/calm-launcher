#!/bin/sh
# Build, install and make Calm the default Home on the K2501 (car on, Wi-Fi ADB).
# The stock launcher stays installed and enabled; ./scripts/rollback.sh switches back.
set -e
cd "$(dirname "$0")/.."
UNIT=${UNIT:?set UNIT to the head unit, e.g. UNIT=192.168.1.50:5555}
export ANDROID_SERIAL=$UNIT
[ -d /opt/homebrew/opt/openjdk@17 ] && export JAVA_HOME=${JAVA_HOME:-/opt/homebrew/opt/openjdk@17}   # Homebrew JDK 17; otherwise use yours

[ "$1" = "--no-build" ] || ./gradlew assembleRelease -q
adb connect "$UNIT" >/dev/null
adb install -r app/build/outputs/apk/release/app-release.apk
# compile ahead of time now, instead of running slow interpreted code until Android gets round to it
adb shell cmd package compile -m speed -f com.calm.launcher >/dev/null
# lets "open Android Auto automatically" start zlink while another app is in front
adb shell appops set com.calm.launcher SYSTEM_ALERT_WINDOW allow
# song length: read from the BT daemon's log (the stock BT app drops it); restart so the reader picks it up
adb shell pm grant com.calm.launcher android.permission.READ_LOGS
adb shell am force-stop com.calm.launcher
# NWD firmware forces Home to whatever this property names (PackageManagerService.chooseBestActivity);
# the normal preferred-activity / HOME role is ignored while it is set. Set all three for good measure.
adb shell setprop persist.nwd.launcher.default com.calm.launcher
adb shell cmd package set-home-activity com.calm.launcher/.HomeActivity >/dev/null
adb shell cmd role add-role-holder android.app.role.HOME com.calm.launcher >/dev/null 2>&1 || true
adb shell am start -a android.intent.action.MAIN -c android.intent.category.HOME >/dev/null
echo "Calm is now Home: $(adb shell cmd package resolve-activity --brief -c android.intent.category.HOME -a android.intent.action.MAIN | tail -1)"
