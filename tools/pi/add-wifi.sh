#!/bin/bash
# Adds a Wi-Fi network to the Dashwheel display, ahead of the ones it knows:
# usually the phone's hotspot, which the head unit uses too.
#
#   On the Pi:   sudo ./pi/add-wifi.sh
#
# It asks for the network's name and password; the password isn't shown. The
# Pi joins it at its next start, or right away when the network it is on goes.
set -euo pipefail

[ "$(id -u)" -eq 0 ] || { echo "run as root (sudo)"; exit 1; }
WPA=/etc/wpa_supplicant/wpa_supplicant-wlan0.conf
[ -f "$WPA" ] || { echo "$WPA is missing: run install.sh first"; exit 1; }

read -r -p "Hotspot name: " ssid
[ -n "$ssid" ] || exit 1
read -r -s -p "Password: " pass
echo
[ ${#pass} -ge 8 ] || { echo "A Wi-Fi password has at least 8 characters"; exit 1; }

# The same name again replaces the older entry.
if grep -qF "ssid=\"$ssid\"" "$WPA"; then
  awk -v s="ssid=\"$ssid\"" '
    /^network=\{/ { block = $0 "\n"; inblock = 1; drop = 0; next }
    inblock { block = block $0 "\n"; if (index($0, s)) drop = 1
              if ($0 ~ /^\}/) { if (!drop) printf "%s", block; inblock = 0 }; next }
    { print }' "$WPA" > "$WPA.new"
  mv "$WPA.new" "$WPA"
fi
# The password is stored hashed, never as typed.
printf '%s\n' "$pass" | wpa_passphrase "$ssid" | sed '/^\s*#/d; s/^}$/\tpriority=10\n}/' >> "$WPA"
chmod 600 "$WPA"
echo "Added \"$ssid\". The Pi prefers it from its next start."
