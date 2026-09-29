#!/usr/bin/env bash
#
# Photograph HobPad's paywall with real pack prices, for the IAPs' App Store review
# screenshots:
#
#   scripts/ios-paywall-screenshot.sh <booted-simulator-udid> <output.png>
#
# How: regenerates the Xcode project, then inserts the StoreKit configuration into the
# scheme's TEST action (XcodeGen only supports it on Run, and xcodebuild test ignores Run's),
# runs HobPadUITests/PaywallScreenshotTest — which navigates to the paywall, proves a price
# rendered, and holds the sheet for ~25 s — and snapshots the simulator from the outside
# during that hold. The LAST snapshot taken before the test passes is the paywall.
set -euo pipefail

UDID="${1:?booted simulator udid}"
OUT="${2:?output png path}"
HERE="$(cd "$(dirname "$0")/.." && pwd)"
LOG="$(mktemp -d)/uitest.log"
SNAPDIR="$(mktemp -d)"

cd "$HERE/ios"
xcodegen generate >/dev/null

python3 - <<'EOF'
import re
path = "HobPad.xcodeproj/xcshareddata/xcschemes/HobPad.xcscheme"
xml = open(path).read()
ref = '      <StoreKitConfigurationFileReference\n         identifier = "../../HobPad/Store/Products.storekit">\n      </StoreKitConfigurationFileReference>\n'
test = re.search(r"<TestAction[\s\S]*?</TestAction>", xml).group(0)
if "StoreKitConfigurationFileReference" not in test:
    xml = xml.replace("</TestAction>", ref + "   </TestAction>")
    open(path, "w").write(xml)
    print("scheme: StoreKit configuration added to TestAction")
EOF

xcodebuild test -project HobPad.xcodeproj -scheme HobPad \
  -destination "platform=iOS Simulator,id=$UDID" >"$LOG" 2>&1 &
TESTPID=$!

# Wait for the test case, then snapshot continuously until it finishes.
for _ in $(seq 1 120); do
  grep -q "testHoldPaywallOpen]' started" "$LOG" 2>/dev/null && break
  sleep 3
done
n=0
while ! grep -qE "testHoldPaywallOpen\]' (passed|failed)" "$LOG"; do
  n=$((n + 1))
  xcrun simctl io "$UDID" screenshot "$SNAPDIR/$n.png" >/dev/null 2>&1 || true
  sleep 3
done
wait "$TESTPID" || true

grep -q "testHoldPaywallOpen]' passed" "$LOG" || {
  echo "UI test failed — no screenshot taken:" >&2
  grep -B2 "error:" "$LOG" | head -20 >&2
  exit 1
}
# The final snapshot can race the sheet closing; take the one before last.
LAST=$((n > 1 ? n - 1 : n))
cp "$SNAPDIR/$LAST.png" "$OUT"
echo "paywall screenshot: $OUT"
