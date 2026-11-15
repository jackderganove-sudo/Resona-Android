#!/usr/bin/env bash
# Round 12 device tests on the Pixel (adb must already be connected; this script
# checks once and never waits for or reconnects the device).
#
#   scripts/device-tests-runde12.sh [VIDEO_ID]
#
# VIDEO_ID: test song (with canvas and word-synced lyrics). Without it, the song
# that is playing right now is used.
#
# Steps (each can be skipped with SKIP_<NAME>=1):
#   INSTALL  newest green wip build (measure build: RESONA_MEASURE) from GitHub
#   DEPTH    lyrics depth: gfxinfo per variant (portrait/landscape, canvas on/off,
#            standard vs. blur of the library with content below hidden, word sync
#            and translation off), song playing quietly
#   AISYNC   AI sync runtime (CTC) through the bench file, numbers only
#   PAUSE    playback after a long pause (PAUSE_SECONDS, default 900) + error log export
#
# Rules: music volume is set to VOLUME (default 2, never full) and restored at the
# end. No lyrics text is written anywhere (logs keep numbers only). Screenshots
# with lyrics on screen stay in build/device-tests-r12/private/ and never go to
# docs/; docs/screens-r12/ only gets player/settings screens. Results: table in
# docs/progress-android.md between the device-tests-r12 markers.
set -u

PKG=dev.jackderganove.resona
REPO=jackderganove-sudo/Resona-Android
ROOT=$(cd "$(dirname "$0")/.." && pwd)
OUT=$ROOT/build/device-tests-r12
PRIV=$OUT/private
SHOTS=$ROOT/docs/screens-r12
DOC=$ROOT/docs/progress-android.md
DURATION=${DURATION:-30}
PAUSE_SECONDS=${PAUSE_SECONDS:-900}
VOLUME=${VOLUME:-2}
SONG=${1:-}
FILES=/sdcard/Android/data/$PKG/files
mkdir -p "$OUT" "$PRIV" "$SHOTS"
: > "$OUT/results.md"

log() { echo "[$(date +%H:%M:%S)] $*" | tee -a "$OUT/run.log"; }
row() { echo "$*" >> "$OUT/results.md"; }

if [ "$(adb get-state 2>/dev/null)" != "device" ]; then
    echo "No device on adb. Connect the Pixel first (this script does not wait)."; exit 1
fi
MODEL=$(adb shell getprop ro.product.model | tr -d '\r')
ANDROID=$(adb shell getprop ro.build.version.release | tr -d '\r')
log "Device: $MODEL, Android $ANDROID"

# ---------------------------------------------------------------- restore on exit
# `cmd media_session volume --set` has no effect on Android 17 (Bluetooth absolute volume): use the audio service.
VOL_BEFORE=${VOL_RESTORE:-$(adb shell cmd audio get-stream-volume 3 2>/dev/null | grep -o "[0-9]*$")}
ROT_AUTO=$(adb shell settings get system accelerometer_rotation | tr -d '\r')
ROT_USER=$(adb shell settings get system user_rotation | tr -d '\r')
cleanup() {
    adb shell rm -f "$FILES/lyrics-depth.txt" "$FILES/aisync-bench.txt" >/dev/null 2>&1
    [ -n "${VOL_BEFORE:-}" ] && adb shell cmd audio set-volume 3 "$VOL_BEFORE" >/dev/null 2>&1
    adb shell settings put system user_rotation "${ROT_USER:-0}" >/dev/null 2>&1
    adb shell settings put system accelerometer_rotation "${ROT_AUTO:-1}" >/dev/null 2>&1
    log "Restored volume ($VOL_BEFORE), rotation, removed bench files"
}
trap cleanup EXIT

# ---------------------------------------------------------------- helpers
playback() {  # "<state> <position ms>" of Resona's media session
    adb shell dumpsys media_session | python3 -c '
import re, sys
text = sys.stdin.read()
part = text.split("'"$PKG"'", 1)[-1]
m = re.search(r"state=PlaybackState \{state=(\w+)\((\d+)\), position=(\d+)", part) or re.search(r"state=(\d+), position=(\d+)", part)
if not m: print("? 0")
elif len(m.groups()) == 3: print(m.group(2), m.group(3))
else: print(m.group(1), m.group(2))
'
}
title() {
    adb shell dumpsys media_session | python3 -c '
import re, sys
part = sys.stdin.read().split("'"$PKG"'", 1)[-1]
m = re.search(r"metadata: size=\d+, description=([^,\n]+)", part)
print(m.group(1).strip() if m else "")
'
}
# UI dumps go straight from the device into python (never into a file: on the
# lyrics screen a dump holds lyrics text). Only coordinates come out.
# uiautomator needs an idle screen: lyrics and canvas animate, so dumps happen paused.
# find_node NAMES…: centre "x y" of the first node whose text or content-desc equals
# (else contains) a name; LOWEST=1 picks the lowest match.
find_node() {
    adb exec-out uiautomator dump /dev/tty 2>/dev/null | python3 -c '
import re, sys
import xml.etree.ElementTree as ET
lowest, names = sys.argv[1] == "1", [n.lower() for n in sys.argv[2:]]
raw = sys.stdin.read()
raw = raw[: raw.rfind(">") + 1] if ">" in raw else ""
try:
    nodes = list(ET.fromstring(raw).iter("node"))
except Exception:
    sys.exit(1)
def centre(n):
    x1, y1, x2, y2 = map(int, re.findall(r"\d+", n.get("bounds", "[0,0][0,0]")))
    return (x1 + x2) // 2, (y1 + y2) // 2
for exact in (True, False):
    hits = []
    for n in nodes:
        labels = [(n.get("text") or "").lower(), (n.get("content-desc") or "").lower()]
        if any((l == name) if exact else (name in l and l) for l in labels for name in names):
            hits.append(centre(n))
    if hits:
        x, y = max(hits, key=lambda p: p[1]) if lowest else hits[0]
        print(x, y); sys.exit(0)
sys.exit(1)
' "${LOWEST:-0}" "$@"
}
# Full player, paused: lyrics pill (text), play/pause = largest unlabelled clickable in the
# control row above the pill, previous = the clickable left of it. Prints "pill play prev" (6 numbers).
scan_player() {
    adb exec-out uiautomator dump /dev/tty 2>/dev/null | python3 -c '
import re, sys
import xml.etree.ElementTree as ET
raw = sys.stdin.read(); raw = raw[: raw.rfind(">") + 1] if ">" in raw else ""
try:
    nodes = list(ET.fromstring(raw).iter("node"))
except Exception:
    sys.exit(1)
def box(n): return list(map(int, re.findall(r"\d+", n.get("bounds", "[0,0][0,0]"))))
pill = next((box(n) for n in nodes if (n.get("text") or "") in ("Songtext", "Lyrics")), None)
if not pill: sys.exit(1)
clicks = [box(n) for n in nodes if n.get("clickable") == "true" and not (n.get("text") or "").strip()]
row = [b for b in clicks if b[3] <= pill[1] and pill[1] - b[3] < 600 and (b[2] - b[0]) < 500]
if not row: sys.exit(1)
play = max(row, key=lambda b: (b[2] - b[0]) * (b[3] - b[1]))
cy = (play[1] + play[3]) // 2
left = [b for b in row if b[2] <= play[0] and b[1] <= cy <= b[3]]
prev = max(left, key=lambda b: b[2]) if left else None
c = lambda b: f"{(b[0] + b[2]) // 2} {(b[1] + b[3]) // 2}"
print(c(pill), c(play), c(prev) if prev else "0 0")
'
}
state() { playback | cut -d' ' -f1; }
press_play() { [ -n "${PLAY_XY:-}" ] && adb shell input tap $PLAY_XY; sleep 2; }
# Media keys may not reach the app (Android 17 beta: telecom holds the media button session): tap as fallback.
ensure_paused() {
    [ "$(state)" = 3 ] || return 0
    adb shell cmd media_session dispatch pause >/dev/null 2>&1; sleep 1
    [ "$(state)" = 3 ] && press_play
    [ "$(state)" != 3 ]
}
ensure_playing() {
    [ "$(state)" = 3 ] && return 0
    adb shell cmd media_session dispatch play >/dev/null 2>&1; sleep 2
    [ "$(state)" = 3 ] || press_play
    [ "$(state)" = 3 ]
}
locate() { ensure_paused; find_node "$@"; }   # leaves playback paused
player_controls() {  # paused, full player open → PILL / PLAY_XY / PREV_XY for this orientation
    ensure_paused
    local r; r=$(scan_player) || return 1
    read -r a b c d e f <<< "$r"
    PILL="$a $b"; PLAY_XY="$c $d"; PREV_XY="$e $f"
    [ "$PREV_XY" = "0 0" ] && PREV_XY=""
    log "Controls: lyrics pill $PILL, play $PLAY_XY, previous ${PREV_XY:-?}"
}
restart_song() {  # previous restarts the song when it has played more than 3 s
    if [ -n "${PREV_XY:-}" ]; then adb shell input tap $PREV_XY; else adb shell cmd media_session dispatch previous >/dev/null 2>&1; fi
    sleep 2
}
tap_xy() { adb shell input tap $1 $2; }
shot() { adb exec-out screencap -p > "$1"; }
gfx() {  # "<janky %> <janky> <total> <p90> <p99>"
    adb shell dumpsys gfxinfo "$PKG" | python3 -c '
import re, sys
t = sys.stdin.read()
def num(p, d="0"):
    m = re.search(p, t); return m.group(1) if m else d
total = num(r"Total frames rendered: (\d+)")
janky = num(r"Janky frames: (\d+)")
pct = num(r"Janky frames: \d+ \(([\d.]+)%\)")
print(pct, janky, total, num(r"90th percentile: (\d+)ms"), num(r"99th percentile: (\d+)ms"))
'
}
rotate() {  # 0 portrait, 1 landscape
    adb shell settings put system accelerometer_rotation 0
    adb shell settings put system user_rotation "$1"
    sleep 3
}

# ---------------------------------------------------------------- 1. install
if [ "${SKIP_INSTALL:-0}" != 1 ]; then
    RUN=$(gh run list -R "$REPO" --branch wip --status success --limit 1 --json databaseId,headSha -q '.[0] | "\(.databaseId) \(.headSha[0:9])"')
    log "Newest green wip build: $RUN"
    rm -rf "$OUT/apk"; mkdir -p "$OUT/apk"
    gh run download "${RUN%% *}" -R "$REPO" -n apks -D "$OUT/apk" >/dev/null 2>&1 || { log "APK download failed"; exit 1; }
    APK=$(ls "$OUT"/apk/*arm64*.apk 2>/dev/null | head -1)
    [ -n "$APK" ] || APK=$(ls "$OUT"/apk/*.apk | head -1)
    if ! adb install -r "$APK" 2>&1 | tee -a "$OUT/run.log" | grep -q Success; then
        log "Install failed. Not uninstalling (would delete downloads and settings)."; exit 1
    fi
    log "Installed $(basename "$APK") (wip ${RUN#* })"
    row "Build: wip ${RUN#* } ($(basename "$APK")), $MODEL, Android $ANDROID, Lautstärke $VOLUME"
fi

# ---------------------------------------------------------------- song + player
adb shell cmd audio set-volume 3 "$VOLUME" >/dev/null
log "Music volume $(adb shell cmd audio get-stream-volume 3 | grep -o '[0-9]*$') (was $VOL_BEFORE)"
if [ -n "$SONG" ]; then
    adb shell am start -a android.intent.action.VIEW -d "https://music.youtube.com/watch?v=$SONG" "$PKG" >/dev/null
    sleep 15
else
    adb shell monkey -p "$PKG" -c android.intent.category.LAUNCHER 1 >/dev/null 2>&1
    sleep 6
fi
if ! ensure_paused; then
    log "Playback could not be paused (media keys do not reach the app): pause it in Resona and start again"; exit 1
fi
TITLE=$(title)
log "Song: ${TITLE:-?} (state $(playback))"
rotate 0
# Full player: open it from the mini player when needed (tap on the title).
if ! player_controls; then
    XY=$(LOWEST=1 find_node "$TITLE") && tap_xy $XY && sleep 3
    player_controls || { log "Full player not found"; exit 1; }
fi
shot "$SHOTS/player-portrait.png"   # paused player, no lyrics on screen
# Back in the player after the lyrics (BACK) or wherever the steps left the app.
back_to_player() {
    for _ in 1 2 3; do
        ensure_paused
        player_controls && return 0
        adb shell input keyevent KEYCODE_BACK; sleep 2
        XY=$(LOWEST=1 find_node "$TITLE") && { tap_xy $XY; sleep 3; }
    done
    return 1
}

# ---------------------------------------------------------------- 2. lyrics depth
if [ "${SKIP_DEPTH:-0}" != 1 ]; then
    row ""
    row "| Lage | Canvas | Variante | Wort-Sync | Übersetzung | Janky | Frames | 90./99. Perzentil |"
    row "|---|---|---|---|---|---|---|---|"
    VARIANTS=(
        "0 1 standard 1 1" "0 0 standard 1 1" "0 1 blur 1 1" "0 0 blur 1 1"
        "0 1 blur 0 1" "0 1 blur 1 0"
        "1 1 standard 1 1" "1 0 standard 1 1" "1 1 blur 1 1" "1 0 blur 1 1"
    )
    BLUR_WORST=0
    CURRENT_ROT=0
    for v in "${VARIANTS[@]}"; do
        read -r rot canvas mode words trans <<< "$v"
        if [ "$rot" != "$CURRENT_ROT" ]; then
            ensure_paused; rotate "$rot"; CURRENT_ROT=$rot; sleep 2
            PILL=""; player_controls || back_to_player
            [ "$rot" = 1 ] && [ -n "$PILL" ] && shot "$SHOTS/player-landscape.png"
        fi
        if [ -z "$PILL" ]; then log "Lyrics pill not found (rotation $rot) – variant skipped"; continue; fi
        config="canvas=$canvas words=$words trans=$trans"
        [ "$mode" = blur ] && config="lib=1 hide=1 $config"
        echo "$config" > "$OUT/lyrics-depth.txt"
        adb push "$OUT/lyrics-depth.txt" "$FILES/lyrics-depth.txt" >/dev/null
        ensure_playing || log "could not start playback"
        restart_song   # same song from the start
        adb logcat -c
        tap_xy $PILL; sleep 5
        applied=$(adb logcat -d -s ResonaLyricsDepth:I | grep -o 'levels=.*' | tail -1)
        adb shell dumpsys gfxinfo "$PKG" reset >/dev/null
        sleep "$DURATION"
        read -r pct janky total p90 p99 <<< "$(gfx)"
        state=$(playback)
        shot "$PRIV/lyrics-r$rot-c$canvas-$mode-w$words-t$trans.png"   # lyrics on screen: private only
        adb shell input keyevent KEYCODE_BACK; sleep 2
        lage=$([ "$rot" = 0 ] && echo hoch || echo quer)
        row "| $lage | $([ "$canvas" = 1 ] && echo an || echo aus) | $mode | $([ "$words" = 1 ] && echo an || echo aus) | $([ "$trans" = 1 ] && echo an || echo aus) | ${pct} % | ${janky}/${total} | ${p90}/${p99} ms |"
        log "depth r$rot c$canvas $mode w$words t$trans: $pct % janky ($janky/$total), p90/p99 $p90/$p99 ms, player $state, applied: ${applied:-?}"
        if [ "$mode" = blur ] && python3 -c "import sys; sys.exit(0 if float('$pct') > float('$BLUR_WORST') else 1)"; then BLUR_WORST=$pct; fi
    done
    ensure_paused; rotate 0; CURRENT_ROT=0; sleep 2
    back_to_player
    if python3 -c "import sys; sys.exit(0 if float('$BLUR_WORST') == 0 else 1)"; then
        VERDICT="Blur in allen Varianten 0 % Janky → kann Standard werden"
    else
        VERDICT="Blur schlechtester Wert $BLUR_WORST % Janky → bleibt aus (Bedingung 0 %)"
    fi
    row ""
    row "Entscheidung Lyrics-Blur: $VERDICT"
    log "$VERDICT"
fi

# ---------------------------------------------------------------- 3. AI sync (CTC)
if [ "${SKIP_AISYNC:-0}" != 1 ]; then
    printf '%s\n' "ctc ctc=model_int8.onnx threads=4" > "$OUT/aisync-bench.txt"
    adb push "$OUT/aisync-bench.txt" "$FILES/aisync-bench.txt" >/dev/null
    adb logcat -c
    back_to_player
    if [ -n "$PILL" ]; then tap_xy $PILL; sleep 4; fi
    MORE=$(locate "Weitere Optionen" "More options")
    if [ -n "$MORE" ]; then
        tap_xy $MORE; sleep 2
        CAL=$(locate "Mit KI kalibrieren" "Calibrate with AI")
        if [ -n "$CAL" ]; then
            tap_xy $CAL
            log "AI sync bench started (CTC); waiting up to 240 s for BENCH done"
            for _ in $(seq 1 48); do
                adb logcat -d -s ResonaAISync:I | grep -q "BENCH done" && break
                adb logcat -d -s ResonaAISync:I | grep -q "not cached\|NOT_CACHED" && break
                sleep 5
            done
            adb logcat -d -s ResonaAISync:I ResonaAISync:W > "$OUT/aisync.log"
            # Numbers only (the bench never logs text).
            line=$(grep -o 'BENCH ctc.*' "$OUT/aisync.log" | tail -1)
            row ""
            row "KI-Sync CTC (Kalibrieren mit Bench-Datei): ${line:-kein Ergebnis – Song ganz im Cache? siehe build/device-tests-r12/aisync.log}"
            log "AI sync: ${line:-no result}"
            # Close whatever is open; never tap "Speichern" (the bench saves nothing).
            adb shell input keyevent KEYCODE_BACK; sleep 1
        else
            log "Menu entry 'Mit KI kalibrieren' not found"; adb shell input keyevent KEYCODE_BACK
        fi
    else
        log "Lyrics menu not found – AI sync step skipped"
    fi
    adb shell rm -f "$FILES/aisync-bench.txt"
    back_to_player
fi

# ---------------------------------------------------------------- 4. long pause + error log
if [ "${SKIP_PAUSE:-0}" != 1 ]; then
    START_ISO=$(date -Iseconds)
    back_to_player
    ensure_playing; sleep 5
    ensure_paused
    log "Paused for $PAUSE_SECONDS s (long pause)"
    sleep "$PAUSE_SECONDS"
    adb logcat -c
    ensure_playing
    read -r s1 p1 <<< "$(playback)"; sleep 20; read -r s2 p2 <<< "$(playback)"
    ensure_paused
    adb logcat -d > "$OUT/pause-logcat.txt"
    errors=$(grep -c "PlaybackException\|HTTP 403\|Response code: 403" "$OUT/pause-logcat.txt")
    advanced=$(( (p2 - p1) / 1000 ))
    if [ "$s2" = "3" ] && [ "$advanced" -ge 15 ]; then RESULT="läuft weiter (+$advanced s in 20 s)"; else RESULT="Problem: Zustand $s2, +$advanced s in 20 s"; fi
    log "After pause: $RESULT, $errors error lines in logcat"
    row ""
    row "Wiedergabe nach $((PAUSE_SECONDS / 60)) min Pause: $RESULT; Fehlerzeilen im Logcat: $errors"

    # Error log export: Settings → About → Share error log (wip builds keep a copy for adb).
    adb shell rm -f "$FILES/Resona-Fehlerprotokoll.txt"
    for _ in 1 2 3; do adb shell input keyevent KEYCODE_BACK; sleep 1; done
    adb shell monkey -p "$PKG" -c android.intent.category.LAUNCHER 1 >/dev/null 2>&1; sleep 3
    XY=$(locate "Einstellungen" "Settings") && tap_xy $XY && sleep 2
    found=""
    for _ in 1 2 3 4 5 6; do
        XY=$(locate "Über" "About") && { tap_xy $XY; sleep 2; found=1; break; }
        adb shell input swipe 540 1800 540 600 300; sleep 1
    done
    exported=""
    if [ -n "$found" ]; then
        for _ in 1 2 3 4; do
            XY=$(locate "Fehlerprotokoll teilen" "Share error log") && { tap_xy $XY; sleep 3; exported=1; break; }
            adb shell input swipe 540 1800 540 600 300; sleep 1
        done
    fi
    if [ -n "$exported" ]; then
        shot "$SHOTS/error-log-share.png"
        adb shell input keyevent KEYCODE_BACK; sleep 1
        if adb pull "$FILES/Resona-Fehlerprotokoll.txt" "$OUT/Resona-Fehlerprotokoll.txt" >/dev/null 2>&1; then
            entries=$(grep -cE '^[0-9]{4}-' "$OUT/Resona-Fehlerprotokoll.txt")
            recent=$(python3 - "$OUT/Resona-Fehlerprotokoll.txt" "$START_ISO" <<'PY'
import sys
from datetime import datetime
start = datetime.fromisoformat(sys.argv[2])
fails = ok = 0
for line in open(sys.argv[1], encoding="utf-8"):
    parts = line.split()
    if len(parts) < 5 or not parts[0][:4].isdigit():
        continue
    try:
        when = datetime.fromisoformat(parts[0])
    except ValueError:
        continue
    if when.timestamp() < start.timestamp():
        continue
    if parts[3] == "success": ok += 1
    else: fails += 1
print(f"{ok} erfolgreich, {fails} Fehlversuche seit Teststart")
PY
)
            row "Fehlerprotokoll exportiert: $entries Einträge; $recent"
            log "Error log: $entries entries; $recent"
        else
            row "Fehlerprotokoll: Teilen-Dialog geöffnet, Kopie nicht gefunden"
        fi
    else
        row "Fehlerprotokoll: Einstellungen → Über → „Fehlerprotokoll teilen“ nicht gefunden (von Hand prüfen)"
        log "Error log export: entry not found"
    fi
fi

# ---------------------------------------------------------------- results into the docs
python3 - "$DOC" "$OUT/results.md" "$(date '+%d.%m.%Y %H:%M')" <<'PY'
import sys
doc, results, when = sys.argv[1], open(sys.argv[2], encoding="utf-8").read().strip(), sys.argv[3]
start, end = "<!-- device-tests-r12:start -->", "<!-- device-tests-r12:end -->"
block = f"{start}\n### Gerätetests Runde 12 ({when}, `scripts/device-tests-runde12.sh`)\n\n{results}\n\nSongtext-Screenshots nur lokal (`build/device-tests-r12/private/`), nicht in docs/.\n{end}"
text = open(doc, encoding="utf-8").read()
if start in text and end in text:
    text = text[: text.index(start)] + block + text[text.index(end) + len(end):]
else:
    text = text.rstrip() + "\n\n" + block + "\n"
open(doc, "w", encoding="utf-8").write(text)
PY
log "Results written to docs/progress-android.md"
cat "$OUT/results.md"
