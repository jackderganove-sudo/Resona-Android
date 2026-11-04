#!/usr/bin/env bash
# Instrumentation test "onboarding without login" on the emulator: debug build
# with cleared data, nobody signed in. Logcat and a screenshot stay for failures.
set -u
PKG=dev.jackderganove.resona.debug
TEST=moe.rukamori.archivetune.resona.OnboardingWithoutLoginTest

adb install -r instrumented/app-debug.apk || exit 1
adb install -r instrumented/app-debug-androidTest.apk || exit 1
adb shell pm clear "$PKG"
adb logcat -c
adb shell am instrument -w -r -e class "$TEST" "$PKG.test/androidx.test.runner.AndroidJUnitRunner" | tee instrument.txt
adb exec-out screencap -p > onboarding-end.png
adb logcat -d > onboarding-logcat.txt

status=0
grep -q "OK (1 test)" instrument.txt || { echo "::error::Onboarding without login failed"; status=1; }
if grep -q "FATAL EXCEPTION" onboarding-logcat.txt; then
  grep -A20 "FATAL EXCEPTION" onboarding-logcat.txt | head -40
  echo "::error::App crashed during the onboarding test"; status=1
fi
exit $status
