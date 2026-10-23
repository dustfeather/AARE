#!/bin/sh
# Builds a Magisk module that installs AARE as a privileged system app, so it can capture the
# phone's audio the way Android Auto does (navigation voice included, phone speaker muted).
#
#   tools/magisk/build-module.sh [path/to/aare.apk]     -> build/aare-magisk.zip
#
# Install the zip from the Magisk app (Modules > Install from storage) and reboot. Later AARE
# updates can be installed normally (adb install -r): Android keeps them privileged as long as
# they are signed with the same key as the APK in the module.
set -e
ROOT=$(cd "$(dirname "$0")/../.." && pwd)
APK=${1:-$ROOT/app/build/outputs/apk/debug/app-debug.apk}
[ -f "$APK" ] || { echo "No APK at $APK: build it first (./build.sh)" >&2; exit 1; }
VERSION=$(sed -n 's/.*versionName = "\(.*\)".*/\1/p' "$ROOT/app/build.gradle.kts" | head -1)

WORK=$(mktemp -d)
trap 'rm -rf "$WORK"' EXIT
mkdir -p "$WORK/system/priv-app/AARE" "$WORK/system/etc/permissions" "$WORK/META-INF/com/google/android"
cp "$APK" "$WORK/system/priv-app/AARE/AARE.apk"
cp "$ROOT/tools/magisk/privapp-permissions-aare.xml" "$WORK/system/etc/permissions/"
cat > "$WORK/module.prop" <<PROP
id=aare
name=AARE (privileged)
version=$VERSION
versionCode=1
author=AARE contributors
description=Installs AARE as a privileged app: car audio like Android Auto (music and navigation voice, phone speaker muted).
PROP
# Magisk's standard installer stubs.
cat > "$WORK/META-INF/com/google/android/update-binary" <<'BIN'
#!/sbin/sh
umask 022
ui_print() { echo "$1"; }
OUTFD=$2
ZIPFILE=$3
. /data/adb/magisk/util_functions.sh
install_module
exit 0
BIN
echo "#MAGISK" > "$WORK/META-INF/com/google/android/updater-script"

mkdir -p "$ROOT/build"
OUT="$ROOT/build/aare-magisk.zip"
rm -f "$OUT"
(cd "$WORK" && zip -qr "$OUT" .)
echo "$OUT"
