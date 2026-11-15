# Resona-Android – notes for Claude

## Device tests on the Pixel
- Skill `pixel-adb` (`~/.claude/skills/pixel-adb/`): before any device step (adb install,
  screencap, logcat, gfxinfo, `scripts/device-tests-runde12.sh`) run
  `export ANDROID_SERIAL=$(~/.claude/skills/pixel-adb/connect.sh)`. It finds the Pixel
  over Wi-Fi via mDNS by its serial and connects; on failure continue without the device,
  collect the tests, never wait in a loop.
- Music volume never full when sound is needed (the scripts use 2 of 25).
