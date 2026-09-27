#!/usr/bin/env bash
# Seeds a layout + theme, relaunches Dashwheel in demo mode and saves a screenshot to raw/.
#   capture.sh <name> <THEME> <DARK|LIGHT> [layouts/x.json | - (default template) | "" (keep)] [wait seconds]
# Needs the capture build (capture-build.patch) installed on $SERIAL. See README.md.
set -e
HERE="$(cd "$(dirname "$0")" && pwd)"
ADB="${ADB:-adb} -s ${SERIAL:-emulator-5580}"
P=com.openauto.dash
PREFS=/data/data/$P/shared_prefs
put() { $ADB shell "run-as $P sh -c 'cat > $PREFS/$1'"; }

$ADB shell am force-stop $P
$ADB shell "run-as $P mkdir -p $PREFS"
printf '<?xml version="1.0" encoding="utf-8" standalone="yes" ?>\n<map>\n<boolean name="done" value="true" />\n<boolean name="pill_off" value="true" />\n</map>\n' | put setup.xml
printf '<?xml version="1.0" encoding="utf-8" standalone="yes" ?>\n<map>\n<boolean name="enabled" value="false" />\n</map>\n' | put drive_lock.xml
put car_profile.xml < "$HERE/car_profile.xml"
printf '<?xml version="1.0" encoding="utf-8" standalone="yes" ?>\n<map>\n<string name="mode">%s</string>\n<string name="appearance">%s</string>\n<boolean name="bar_auto_hide" value="false" />\n</map>\n' "$2" "$3" | put dashboard_theme.xml
if [ "$4" = "-" ]; then
  $ADB shell "run-as $P rm -f $PREFS/dashboard_layout_prefs.xml"
elif [ -n "$4" ]; then
  python -c "import sys,html,json;d=open(sys.argv[1],encoding='utf-8').read();json.loads(d);print('<?xml version=\"1.0\" encoding=\"utf-8\" standalone=\"yes\" ?>\n<map>\n<string name=\"pages\">'+html.escape(d)+'</string>\n</map>')" "$HERE/$4" | put dashboard_layout_prefs.xml
fi
$ADB shell am start -n $P/.MainActivity --ez mkt_demo true >/dev/null
sleep "${5:-22}"
mkdir -p "$HERE/raw"
$ADB exec-out screencap -p > "$HERE/raw/$1.png"
echo "raw/$1.png"
