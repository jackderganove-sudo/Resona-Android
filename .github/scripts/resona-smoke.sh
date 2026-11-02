#!/usr/bin/env bash
# Start a freshly installed Resona APK and make sure it is not locked by
# ArchiveTune's "Update required" screen, did not crash and is in front.
set -u
APK=out/app-gms-mobile-universal-release.apk
PKG=dev.jackderganove.resona

adb install -r "$APK" || exit 1
adb logcat -c
adb shell monkey -p "$PKG" -c android.intent.category.LAUNCHER 1
sleep 45
adb exec-out screencap -p > start.png
adb shell uiautomator dump /sdcard/ui.xml >/dev/null && adb pull /sdcard/ui.xml ui.xml >/dev/null
adb logcat -d > logcat.txt
adb shell dumpsys activity activities | grep -E "topResumedActivity|mResumedActivity" | tee activity.txt

echo "Visible texts:"
grep -o 'text="[^"]*"' ui.xml | sort -u | head -40

status=0
if grep -qiE 'Update required|two stable releases|to continue\.' ui.xml; then
  echo "::error::App started locked on the update screen"; status=1
fi
if grep -q 'FATAL EXCEPTION' logcat.txt; then
  grep -A20 'FATAL EXCEPTION' logcat.txt | head -40
  echo "::error::App crashed on start"; status=1
fi
if ! grep -q "$PKG" activity.txt; then
  echo "::error::Resona is not in the foreground"; status=1
fi
echo "Release lookups in the log:"
grep -oE 'api\.github\.com/repos/[A-Za-z0-9_.-]+/[A-Za-z0-9_.-]+' logcat.txt | sort -u || true
[ "$status" = 0 ] && echo "Start OK: Resona is not locked"
exit $status
