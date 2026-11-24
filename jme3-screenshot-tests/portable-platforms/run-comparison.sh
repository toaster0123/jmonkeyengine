#!/usr/bin/env bash
set -euo pipefail

platform="${1:?usage: run-comparison.sh desktop|ios reference-checkout}"
reference="$(cd "${2:?reference checkout required}" && pwd)"
target="$(pwd)"
adapter=jme3-screenshot-tests/portable-platforms
report="$target/portable-screenshot-report"
if [[ "$reference" == "$target" || -e "$reference/$adapter" ]]; then
  # A later reference branch may already contain the adapter. Copy only its
  # test infrastructure, never its scenario sources or engine code.
  if [[ "$reference" == "$target" ]]; then
    echo "Reference checkout must be distinct from target" >&2
    exit 1
  fi
  mv "$reference/$adapter" "$reference/$adapter-original"
fi
cp -R "$target/$adapter" "$reference/$adapter"
output="$(mktemp -d)"
mkdir -p "$report"

if [[ "$platform" == ios ]]; then
  xcrun simctl list devices available -j > "$report/devices.json"
  read -r runtime device_type < <(python3 - "$report/devices.json" <<'PY'
import json
import sys

with open(sys.argv[1], encoding="utf-8") as source:
    devices = json.load(source)["devices"]
for runtime in sorted((r for r in devices if ".iOS-" in r),
                      key=lambda r: tuple(map(int, r.split(".iOS-")[1].split("-"))), reverse=True):
    for device in devices[runtime]:
        device_type = device.get("deviceTypeIdentifier", "")
        if device.get("isAvailable", False) and ".iPhone-" in device_type:
            print(runtime, device_type)
            sys.exit(0)
sys.exit("No installed iPhone simulator was found")
PY
  )
  udid="$(xcrun simctl create "jME Screenshot Test $$" "$device_type" "$runtime")"
  trap 'xcrun simctl shutdown "$udid" >/dev/null 2>&1 || true; xcrun simctl delete "$udid" >/dev/null 2>&1 || true; rm -rf "$output"' EXIT
  xcrun simctl boot "$udid"
  xcrun simctl bootstatus "$udid" -b
elif [[ "$platform" == desktop ]]; then
  trap 'rm -rf "$output"' EXIT
else
  echo "Unknown platform: $platform" >&2
  exit 1
fi

capture() (
  cd "$1"
  pass="$2"
  if [[ "$platform" == ios ]]; then
    ./gradlew --no-daemon -I "$adapter/include.gradle" -Pscreenshot.ios=true \
      :screenshot-platform-ios:buildIosSimulatorApp
    app="$(find "$adapter/ios/build" -type d -name JmeScreenshotTests.app -print -quit)"
    [[ -n "$app" ]] || { echo "Simulator app was not built" >&2; exit 1; }
    bundle=org.jmonkeyengine.screenshottests.ios
    xcrun simctl install "$udid" "$app"
  fi
  for scene in bitmap-text simple-bumps; do
    dest="$output/$pass/$scene"
    if [[ "$platform" == ios ]]; then
      data="$(xcrun simctl get_app_container "$udid" "$bundle" data)"
      capture_dir="$data/Documents/$pass-$scene"
      SIMCTL_CHILD_JME_SCREENSHOT_OUTPUT="$capture_dir" SIMCTL_CHILD_JME_SCREENSHOT_SCENARIO="$scene" \
        xcrun simctl launch --terminate-running-process --stdout="$report/$pass-$scene.stdout.log" \
        --stderr="$report/$pass-$scene.stderr.log" "$udid" "$bundle"
      for ((attempt = 0; attempt < 90; attempt++)); do
        if [[ -f "$capture_dir/complete" ]]; then
          break
        fi
        sleep 1
      done
      [[ -f "$capture_dir/complete" ]] || { echo "$pass/$scene timed out" >&2; exit 1; }
      mkdir -p "$(dirname "$dest")"
      cp -R "$capture_dir" "$dest"
      xcrun simctl terminate "$udid" "$bundle"
    else
      # Convert MSYS paths explicitly for the Windows JVM.
      if command -v cygpath >/dev/null 2>&1; then
        dest="$(cygpath -am "$dest")"
      fi
      ./gradlew --no-daemon -I "$adapter/include.gradle" :screenshot-platform-desktop:capture \
        "-Pscreenshot.scenario=$scene" "-Pscreenshot.output=$dest"
    fi
    [[ -f "$dest/complete" ]] || { echo "$pass/$scene did not finish" >&2; exit 1; }
  done
)

capture "$reference" reference
capture "$target" target

if command -v cygpath >/dev/null 2>&1; then
  output="$(cygpath -am "$output")"
  report="$(cygpath -am "$report")"
fi
./gradlew --no-daemon -I "$adapter/include.gradle" :screenshot-platform-desktop:compare \
  "-Pscreenshot.referenceDir=$output/reference" "-Pscreenshot.targetDir=$output/target" \
  "-Pscreenshot.reportDir=$report" | tee "$report/comparison.txt"
