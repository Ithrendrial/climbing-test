#!/bin/sh
# Build, install and launch on the connected phone.
# Xiaomi blocks `gradlew installDebug` (INSTALL_FAILED_USER_RESTRICTED) unless
# "Install via USB" is enabled in Developer options, which wants a Mi account.
# Pushing the APK and running `pm install` from the shell sidesteps that.
set -e
ADB="$HOME/Library/Android/sdk/platform-tools/adb"
APK=app/build/outputs/apk/debug/app-debug.apk

./gradlew assembleDebug
"$ADB" push "$APK" /data/local/tmp/climbspike.apk
"$ADB" shell pm install -r -t /data/local/tmp/climbspike.apk
"$ADB" shell am start -n com.rachel.climbspike/.MainActivity
