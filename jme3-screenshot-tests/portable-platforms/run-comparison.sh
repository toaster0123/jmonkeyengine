#!/usr/bin/env bash
set -euo pipefail

platform="${1:?usage: run-comparison.sh desktop|ios reference-checkout}"
reference="$(cd "${2:?reference checkout required}" && pwd)"
target="$(pwd)"
adapter=jme3-screenshot-tests/portable-platforms
shared=jme3-screenshot-tests/jme3-screenshot-tests-shared/src/main/java/org/jmonkeyengine/screenshottests
report="$target/portable-screenshot-report"
if [[ "$reference" == "$target" || ! -d "$reference/$shared" ]]; then
  echo "Reference must be a distinct checkout supporting the shared screenshot suite" >&2
  exit 1
fi
if [[ "$platform" != desktop && "$platform" != ios ]]; then
  echo "Unknown platform: $platform" >&2
  exit 1
fi
output="$(mktemp -d)"
udid=
restore_adapter=false
installed_adapter=false
additions=()
cleanup() {
  if [[ -n "$udid" ]]; then
    xcrun simctl shutdown "$udid" >/dev/null 2>&1 || true
    xcrun simctl delete "$udid" >/dev/null 2>&1 || true
  fi
  if [[ "$installed_adapter" == true ]]; then
    rm -rf "$reference/$adapter"
  fi
  if [[ "$restore_adapter" == true ]]; then
    mv "$output/reference-original" "$reference/$adapter"
  fi
  for file in ${additions[@]+"${additions[@]}"}; do rm -f "$reference/$file"; done
  rm -rf "$output"
}
trap cleanup EXIT

if [[ -e "$reference/$adapter" ]]; then
  mv "$reference/$adapter" "$output/reference-original"
  restore_adapter=true
fi
mkdir -p "$reference/$adapter"
installed_adapter=true
cp "$target/$adapter/include.gradle" "$reference/$adapter/"
for module in common desktop ios; do
  mkdir "$reference/$adapter/$module"
  cp -R "$target/$adapter/$module/src" "$reference/$adapter/$module/"
  if [[ -f "$target/$adapter/$module/build.gradle" ]]; then
    cp "$target/$adapter/$module/build.gradle" "$reference/$adapter/$module/"
  fi
done
# Transition from a base predating the registry. Only add files that did not
# exist on the reference; never overwrite a scene or script from that revision.
if [[ ! -f "$reference/$shared/testframework/ScreenshotSuite.java" ]]; then
  for path in testframework/ScreenshotSuite.java scenarios/texture/ScenarioSingleChannelTexture.java; do
    if [[ ! -e "$reference/$shared/$path" ]]; then
      mkdir -p "$(dirname "$reference/$shared/$path")"
      cp "$target/$shared/$path" "$reference/$shared/$path"
      additions+=("$shared/$path")
    fi
  done
fi
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
        kind = device.get("deviceTypeIdentifier", "")
        if device.get("isAvailable", False) and ".iPhone-" in kind:
            print(runtime, kind)
            sys.exit(0)
sys.exit("No installed iPhone simulator was found")
PY
  )
  udid="$(xcrun simctl create "jME Screenshot Test $$" "$device_type" "$runtime")"
  xcrun simctl boot "$udid"
  xcrun simctl bootstatus "$udid" -b
fi

java_path() {
  if command -v cygpath >/dev/null 2>&1; then cygpath -am "$1"; else printf '%s\n' "$1"; fi
}

run_pass() (
  cd "$1"
  pass="$2"
  ./gradlew --no-daemon -I "$adapter/include.gradle" :screenshot-platform-desktop:prepareLauncher
  export MSYS2_ARG_CONV_EXCL='*'
  classpath="$(cat "$adapter/desktop/build/launcher-classpath.txt")"
  main=org.jmonkeyengine.screenshottests.testframework
  manifest="$output/$pass.tsv"
  java -cp "$classpath" "$main.ComparePlatformCaptures" list "$(java_path "$manifest")"
  if [[ "$platform" == ios ]]; then
    ./gradlew --no-daemon -I "$adapter/include.gradle" -Pscreenshot.ios=true \
      :screenshot-platform-ios:buildIosSimulatorApp
    app="$(find "$adapter/ios/build" -type d -name JmeScreenshotTests.app -print -quit)"
    [[ -n "$app" ]] || { echo "Simulator app was not built" >&2; exit 1; }
    bundle=org.jmonkeyengine.screenshottests.ios
    xcrun simctl install "$udid" "$app"
    data="$(xcrun simctl get_app_container "$udid" "$bundle" data)"
  fi
  java_flags=()
  comparison_java_flag=
  renderer=ANGLE_GLES3
  if [[ "$(uname -s)" == Darwin && "$platform" == desktop ]]; then
    # SDL owns the first thread in the capture process; keep AWT from starting AppKit.
    java_flags=(-XstartOnFirstThread -Djava.awt.headless=true)
    if [[ "$(uname -m)" == x86_64 && -n "${SCREENSHOT_CAPTURE_TIMEOUT_SECONDS:-}" ]]; then
      java_flags+=("-Djme.screenshot.captureTimeoutSeconds=$SCREENSHOT_CAPTURE_TIMEOUT_SECONDS")
    fi
    comparison_java_flag=-Djava.awt.headless=true
    renderer=LWJGL-OpenGL41
  fi
  failures=0
  while IFS=$'\t' read -r test scenario; do
    scenario="${scenario%$'\r'}"
    dest="$output/$pass/$test/$scenario"
    log="$report/$pass-$test-$scenario"
    if [[ "$platform" == ios ]]; then
      capture_dir="$data/Documents/$pass/$test/$scenario"
      if SIMCTL_CHILD_JME_SCREENSHOT_OUTPUT="$capture_dir" SIMCTL_CHILD_JME_SCREENSHOT_TEST="$test" \
        SIMCTL_CHILD_JME_SCREENSHOT_SCENARIO="$scenario" xcrun simctl launch --terminate-running-process \
        --stdout="$log.stdout.log" --stderr="$log.stderr.log" "$udid" "$bundle"; then
        for ((attempt = 0; attempt < 45; attempt++)); do
          [[ ! -f "$capture_dir/complete" ]] || break
          sleep 1
        done
        if [[ -f "$capture_dir/complete" ]]; then
          mkdir -p "$(dirname "$dest")"
          cp -R "$capture_dir" "$dest"
        fi
        xcrun simctl terminate "$udid" "$bundle" || true
      fi
    else
      if ! java ${java_flags[@]+"${java_flags[@]}"} -cp "$classpath" "$main.DesktopPlatformCapture" \
           "$test" "$scenario" "$(java_path "$dest")" "$renderer" >"$log.log" 2>&1; then
        failures=$((failures + 1))
        echo "::error::$pass/$test/$scenario process failed (see capture log)" >&2
        continue
      fi
    fi
    if [[ ! -f "$dest/complete" ]]; then
      failures=$((failures + 1))
      echo "::error::$pass/$test/$scenario did not finish (see capture logs)" >&2
    fi
  done < "$manifest"
  if [[ "$failures" != 0 ]]; then
    echo "$pass capture failed for $failures scenario(s); no completed reference comparison published" >&2
    exit 1
  fi
  mode="$([[ "$pass" == reference ]] && echo generate || echo compare)"
  java ${comparison_java_flag:+"$comparison_java_flag"} -cp "$classpath" "$main.ComparePlatformCaptures" "$mode" "$(java_path "$output/$pass")" \
    "$(java_path "$output/baseline")" "$(java_path "$report/$pass")" | tee "$report/$pass.txt"
)

run_pass "$reference" reference
run_pass "$target" target
