#!/usr/bin/env bash
#
# Archive HobPad and upload it to App Store Connect / TestFlight, non-interactively.
# RiderNav's ios/deploy.sh, adapted: the App Store Connect API key config is SHARED with
# RiderNav (same team, same key) — ios/.appstoreconnect.env here wins if present, else
# RiderNav's is used, so there is one key config on this machine, not two drifting copies.
#
# To ship a build:   ios/deploy.sh
# Then wait for processing:   ios/asc-api.sh wraps RiderNav's — use:
#   /Volumes/DATA/Projects/RiderNav/ridernav/ios/asc-api.sh get '/v1/builds?filter[app]=6817252074&limit=1'
set -euo pipefail

cd "$(dirname "$0")"                 # ios/
export PATH="/opt/homebrew/bin:$PATH"   # xcodegen
# Gradle (the Xcode pre-build step) needs Android Studio's JBR.
if [ -z "${JAVA_HOME:-}" ] || [ ! -x "${JAVA_HOME:-}/bin/java" ]; then
  export JAVA_HOME="/Applications/Android Studio.app/Contents/jbr/Contents/Home"
fi

# --- Load the API key config (secrets, gitignored; RiderNav's is the fallback) ---
CONFIG=".appstoreconnect.env"
[ -f "$CONFIG" ] || CONFIG="/Volumes/DATA/Projects/RiderNav/ridernav/ios/.appstoreconnect.env"
[ -f "$CONFIG" ] || { echo "No .appstoreconnect.env found (here or in RiderNav)" >&2; exit 1; }
# shellcheck disable=SC1090
source "$CONFIG"
: "${ASC_KEY_ID:?set ASC_KEY_ID in $CONFIG}"
: "${ASC_ISSUER_ID:?set ASC_ISSUER_ID in $CONFIG}"
: "${ASC_KEY_PATH:?set ASC_KEY_PATH in $CONFIG}"
[ -f "$ASC_KEY_PATH" ] || { echo "API key file not found: $ASC_KEY_PATH" >&2; exit 1; }

AUTH=(-allowProvisioningUpdates
      -authenticationKeyPath "$ASC_KEY_PATH"
      -authenticationKeyID "$ASC_KEY_ID"
      -authenticationKeyIssuerID "$ASC_ISSUER_ID")

# --- Bump the build number (source of truth is project.yml), then regenerate ---
CUR=$(grep -E 'CURRENT_PROJECT_VERSION:' project.yml | head -1 | sed -E 's/.*"([0-9]+)".*/\1/')
NEXT=$((CUR + 1))
sed -i '' -E "s/CURRENT_PROJECT_VERSION: \"$CUR\"/CURRENT_PROJECT_VERSION: \"$NEXT\"/" project.yml
echo "==> Build number $CUR -> $NEXT"
xcodegen generate >/dev/null

# --- Pre-stage the Release XCFramework BEFORE xcodebuild (mid-build restage invalidates
#     Xcode's precompiled modules — "header modified since module built"). ---
( cd .. \
  && ./gradlew :core:assembleHobPadCoreReleaseXCFramework -q \
  && SRC="core/build/XCFrameworks/release/HobPadCore.xcframework" \
  && STAGE="core/build/XCFrameworks/current/HobPadCore.xcframework" \
  && if ! diff -qr "$SRC" "$STAGE" >/dev/null 2>&1; then rm -rf "$STAGE"; mkdir -p "$(dirname "$STAGE")"; cp -R "$SRC" "$STAGE"; fi )

# --- Archive (Release, signed) ---
ARCHIVE="build/HobPad.xcarchive"
rm -rf build
echo "==> Archiving..."
xcodebuild archive \
  -project HobPad.xcodeproj \
  -scheme HobPad \
  -configuration Release \
  -destination 'generic/platform=iOS' \
  -archivePath "$ARCHIVE" \
  "${AUTH[@]}"

# --- Export + upload to App Store Connect (TestFlight) ---
echo "==> Uploading to App Store Connect..."
xcodebuild -exportArchive \
  -archivePath "$ARCHIVE" \
  -exportPath build/export \
  -exportOptionsPlist ExportOptions.plist \
  "${AUTH[@]}"

echo "==> Done. Build $NEXT uploaded; it appears in TestFlight after Apple processes it."
