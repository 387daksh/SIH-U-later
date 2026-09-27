#!/usr/bin/env bash
# Tier-1 task 2 — pull the four PS-26173 latency numbers, plus RTF and peak RAM, off a real phone.
#
# Everything this reads is produced by the app itself: SutraStore's metrics table (exported to
# external files as CSV) and Android's own `dumpsys meminfo`. Nothing here estimates or projects.
#
# Usage:
#   scripts/measure_on_device.sh [serial] [out_dir]
#
# Before running: install the debug build, use the app for a few utterances (ideally two phones
# paired, so the cross-device rows exist), then trigger "Export metrics" in the app so the CSV is
# written. Run this for each phone, with its own serial.

set -uo pipefail

ADB="${ADB:-E:/SIH/.android-sdk/platform-tools/adb.exe}"
SERIAL="${1:-}"
OUT="${2:-evaluation/device_latency/$(date +%Y%m%d-%H%M%S)}"
PKG="${PKG:-org.sih.offlinespeech}"

adb_cmd() { if [ -n "$SERIAL" ]; then "$ADB" -s "$SERIAL" "$@"; else "$ADB" "$@"; fi; }

command -v "$ADB" >/dev/null 2>&1 || [ -x "$ADB" ] || { echo "adb not found at $ADB (set ADB=...)"; exit 1; }

mapfile -t DEVICES < <("$ADB" devices | awk 'NR>1 && $2=="device" {print $1}')
if [ "${#DEVICES[@]}" -eq 0 ]; then
    echo "No device connected. Enable USB debugging and re-run."
    exit 1
fi
if [ -z "$SERIAL" ] && [ "${#DEVICES[@]}" -gt 1 ]; then
    echo "Multiple devices attached; pass one explicitly:"
    printf '  %s\n' "${DEVICES[@]}"
    exit 1
fi
[ -n "$SERIAL" ] || SERIAL="${DEVICES[0]}"

mkdir -p "$OUT"
echo "device  : $SERIAL"
echo "output  : $OUT"

# --- device identity, so a number is never reported without the hardware it came from ----------
{
    echo "serial=$SERIAL"
    for prop in ro.product.model ro.product.manufacturer ro.build.version.release \
                ro.build.version.sdk ro.board.platform ro.soc.model; do
        echo "$prop=$(adb_cmd shell getprop "$prop" 2>/dev/null | tr -d '\r')"
    done
    echo "total_ram_kb=$(adb_cmd shell cat /proc/meminfo 2>/dev/null | awk '/MemTotal/{print $2}' | tr -d '\r')"
    echo "cpu_cores=$(adb_cmd shell cat /proc/cpuinfo 2>/dev/null | grep -c ^processor | tr -d '\r')"
    echo "low_ram_device=$(adb_cmd shell getprop ro.config.low_ram 2>/dev/null | tr -d '\r')"
} > "$OUT/device.txt"
sed 's/^/  /' "$OUT/device.txt"

# --- peak RAM: dumpsys meminfo is the number the OS itself reports --------------------------------
echo "collecting meminfo..."
adb_cmd shell dumpsys meminfo "$PKG" > "$OUT/meminfo.txt" 2>/dev/null
PSS=$(awk '/TOTAL PSS:/ {print $3; exit}' "$OUT/meminfo.txt" 2>/dev/null)
[ -n "${PSS:-}" ] || PSS=$(awk '/^ *TOTAL/ {print $2; exit}' "$OUT/meminfo.txt" 2>/dev/null)
echo "  TOTAL PSS = ${PSS:-unavailable} kB"

# --- the app's own metrics CSV --------------------------------------------------------------------
REMOTE="/sdcard/Android/data/$PKG/files/metrics/sutra_metrics.csv"
echo "pulling metrics csv..."
if adb_cmd shell "test -f $REMOTE" 2>/dev/null; then
    adb_cmd pull "$REMOTE" "$OUT/sutra_metrics.csv" >/dev/null 2>&1 && echo "  pulled $(wc -l < "$OUT/sutra_metrics.csv") rows"
else
    echo "  NOT FOUND at $REMOTE"
    echo "  Use the app's \"Export metrics\" action first — the CSV is only written on export."
fi

# --- logcat, as the cross-check on the CSV --------------------------------------------------------
adb_cmd logcat -d -s SIH_METRIC:I > "$OUT/logcat_sih_metric.txt" 2>/dev/null
echo "  logcat SIH_METRIC lines: $(wc -l < "$OUT/logcat_sih_metric.txt" 2>/dev/null || echo 0)"

# --- summarise, reporting only what is present ----------------------------------------------------
if [ -f "$OUT/sutra_metrics.csv" ]; then
    echo
    echo "=== measured, from this device's own metrics ==="
    PSS="$PSS" python - "$OUT/sutra_metrics.csv" <<'PY'
import csv, os, re, statistics, sys

rows = list(csv.DictReader(open(sys.argv[1], encoding="utf-8", errors="replace")))
def field(event, key):
    out = []
    for r in rows:
        if r.get("event") != event:
            continue
        m = re.search(rf"{key}=(-?[\d.]+)", r.get("detail", "") or "")
        if m:
            out.append(float(m.group(1)))
    return out

def show(label, values, unit, metric=None):
    tag = f"metric {metric}: " if metric else ""
    if not values:
        print(f"  {tag}{label}: NOT MEASURED (no rows)")
        return
    med = statistics.median(values)
    print(f"  {tag}{label}: median {med:.1f}{unit}  (n={len(values)}, min {min(values):.1f}, max {max(values):.1f})")

show("speech end -> STT complete", field("asr_final", "stt_latency_ms"), " ms", 1)
show("text received -> audio playing", field("rx_to_first_audio", "ms"), " ms", 2)
rtf = field("asr_final", "rtf")
if rtf:
    print(f"  metric 3: RTF: median {statistics.median(rtf):.3f}  (n={len(rtf)}, min {min(rtf):.3f}, max {max(rtf):.3f})")
else:
    print("  metric 3: RTF: NOT MEASURED (no rows)")
show("speech end -> peer playback (cross-device)", field("peer_playback_latency", "ms"), " ms", 4)

print()
show("model load (cold)", [v for v in field("asr_final", "model_load_ms") if v > 0], " ms")
show("STT warm-up at startup", field("stt_warm", "ms"), " ms")
show("TTS warm-up at startup", field("tts_warm", "ms"), " ms")
show("peer clock offset", field("peer_clock_sync", "offset_ms"), " ms")
show("peer round-trip", field("peer_clock_sync", "rtt_ms"), " ms")

unsynced = sum(1 for r in rows if r.get("event") == "peer_playback_unsynced")
if unsynced:
    print(f"  NOTE: {unsynced} playback events had no clock exchange yet; metric 4 withheld for those.")

pss = os.environ.get("PSS")
if pss and pss.isdigit():
    print(f"\n  peak RAM (TOTAL PSS): {int(pss)/1024:.1f} MB")
PY
fi

echo
echo "Artifacts in $OUT — commit these alongside any number quoted from them."
