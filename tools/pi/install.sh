#!/bin/bash
# Installs the Dashwheel second screen on a Raspberry Pi (3B / 3B+ or newer)
# running Raspberry Pi OS Lite, already set up to join the phone's hotspot.
#
#   On a computer:   ./gradlew :display:installDist
#                    scp -r display/build/install/dashwheel-display tools/pi pi@dashwheel-display.local:
#   On the Pi:       sudo ./pi/install.sh ./dashwheel-display [--composite] [--acc-sense] [--overlay]
#
#   --composite   drive an RCA (composite) monitor from the 3.5 mm jack instead of HDMI
#   --acc-sense   shut down cleanly when the ignition goes off (GPIO3 through an optocoupler, see README.md)
#   --overlay     make the SD card read-only (overlay file system). Pair the head
#                 unit FIRST: the pairing is kept on the boot partition.
#
# Run it again to update; settings and the pairing are kept.
set -euo pipefail

usage() { sed -n '2,15p' "$0"; exit 2; }

[ "$(id -u)" -eq 0 ] || { echo "run as root (sudo)"; exit 1; }
BUNDLE="${1:-}"; shift || true
[ -n "$BUNDLE" ] && [ -x "$BUNDLE/bin/dashwheel-display" ] || usage
COMPOSITE=0; ACC=0; OVERLAY=0
for arg in "$@"; do
  case "$arg" in
    --composite) COMPOSITE=1 ;;
    --acc-sense) ACC=1 ;;
    --overlay) OVERLAY=1 ;;
    *) usage ;;
  esac
done
HERE="$(cd "$(dirname "$0")" && pwd)"

# Bookworm and later mount the boot partition at /boot/firmware.
BOOT=/boot/firmware
[ -f "$BOOT/config.txt" ] || BOOT=/boot
CONFIG_DIR="$BOOT/dashwheel"

echo "== packages"
apt-get update
apt-get install -y --no-install-recommends \
  default-jre-headless fonts-dejavu-core avahi-daemon \
  gstreamer1.0-tools gstreamer1.0-plugins-base gstreamer1.0-plugins-good gstreamer1.0-plugins-bad

echo "== program"
systemctl stop dashwheel-display 2>/dev/null || true
rm -rf /opt/dashwheel-display
cp -r "$BUNDLE" /opt/dashwheel-display
chmod +x /opt/dashwheel-display/bin/dashwheel-display

echo "== settings in $CONFIG_DIR"
mkdir -p "$CONFIG_DIR"
[ -f "$CONFIG_DIR/display.conf" ] || cp "$HERE/display.conf.example" "$CONFIG_DIR/display.conf"
if [ "$COMPOSITE" -eq 1 ] && ! grep -q '^overscan=' "$CONFIG_DIR/display.conf"; then
  echo "overscan=5" >> "$CONFIG_DIR/display.conf"
fi
# Made now, while the card is still writable.
echo "pairing: $(/opt/dashwheel-display/bin/dashwheel-display --config "$CONFIG_DIR" --print-pairing)"

echo "== services"
sed "s|@CONFIG_DIR@|$CONFIG_DIR|" "$HERE/dashwheel-display.service" > /etc/systemd/system/dashwheel-display.service
install -m 644 "$HERE/dashwheel-display.avahi.service" /etc/avahi/services/dashwheel-display.service
systemctl daemon-reload
systemctl enable avahi-daemon dashwheel-display
systemctl disable getty@tty1 2>/dev/null || true

echo "== boot settings"
CFG="$BOOT/config.txt"
# Our block is rewritten each time, between these markers.
sed -i '/^# >>> dashwheel/,/^# <<< dashwheel/d' "$CFG"
{
  echo "# >>> dashwheel (tools/pi/install.sh)"
  echo "[all]"
  echo "disable_splash=1"
  if [ "$COMPOSITE" -eq 1 ]; then
    echo "enable_tvout=1"
  else
    # Keep HDMI on even when the monitor powers up after the Pi (ACC).
    echo "hdmi_force_hotplug=1"
  fi
  [ "$ACC" -eq 1 ] && echo "dtoverlay=gpio-shutdown,gpio_pin=3,active_low=0,gpio_pull=up"
  echo "# <<< dashwheel"
} >> "$CFG"
if [ "$COMPOSITE" -eq 1 ]; then
  sed -i 's/^dtoverlay=vc4-kms-v3d$/dtoverlay=vc4-kms-v3d,composite/' "$CFG"
else
  sed -i 's/^dtoverlay=vc4-kms-v3d,composite$/dtoverlay=vc4-kms-v3d/' "$CFG"
fi
CMD="$BOOT/cmdline.txt"
# No console blanking and no boot text on the car's screen.
grep -q 'consoleblank=0' "$CMD" || sed -i '1 s/$/ consoleblank=0/' "$CMD"
grep -q 'logo.nologo' "$CMD" || sed -i '1 s/$/ logo.nologo/' "$CMD"

if [ "$OVERLAY" -eq 1 ]; then
  echo "== read-only SD card"
  raspi-config nonint do_overlayfs 0
fi

echo
echo "Done. Reboot, then scan the code on the screen with the Dashwheel phone app."
echo "The head unit and this Pi must both be on the phone's hotspot."
