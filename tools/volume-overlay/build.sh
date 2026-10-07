#!/bin/sh
# Builds the overlay that hides the firmware volume pop-up and puts it where the
# app ships it (app/src/main/assets/volume_overlay.apk). Run again only when this
# folder changes; the APK is committed so the app build needs no extra step.
#
# The ids are pinned to the firmware's own (stable-ids.txt, read from
# QF_Framework.apk with `aapt2 dump resources`): the firmware finds the views
# by those numbers.
set -e
HERE=$(cd "$(dirname "$0")" && pwd)
SDK=${ANDROID_HOME:-${ANDROID_SDK_ROOT:-$LOCALAPPDATA/Android/Sdk}}
BT=$(ls -d "$SDK"/build-tools/* | sort -V | tail -1)
EXE=; [ -f "$BT/aapt2.exe" ] && EXE=.exe
AAPT2="$BT/aapt2$EXE"
APKSIGNER="$BT/apksigner"; [ -f "$APKSIGNER.bat" ] && APKSIGNER="$APKSIGNER.bat"
ANDROID_JAR="$SDK/platforms/android-29/android.jar"
OUT="$HERE/build"
rm -rf "$OUT" && mkdir -p "$OUT"
"$AAPT2" compile --dir "$HERE/res" -o "$OUT/res.zip"
"$AAPT2" link -o "$OUT/unsigned.apk" -I "$ANDROID_JAR" --manifest "$HERE/AndroidManifest.xml" \
  --stable-ids "$HERE/stable-ids.txt" --min-sdk-version 29 --target-sdk-version 29 "$OUT/res.zip"
"$BT/zipalign$EXE" -f 4 "$OUT/unsigned.apk" "$OUT/aligned.apk"
# Any key will do: Android trusts an overlay for being on /system, not for its signature.
KS=${KEYSTORE:-$HOME/.android/debug.keystore}
"$APKSIGNER" sign --ks "$KS" --ks-pass pass:android --key-pass pass:android --ks-key-alias androiddebugkey \
  --v4-signing-enabled false --out "$HERE/../../app/src/main/assets/volume_overlay.apk" "$OUT/aligned.apk"
rm -rf "$OUT"
echo "app/src/main/assets/volume_overlay.apk"
