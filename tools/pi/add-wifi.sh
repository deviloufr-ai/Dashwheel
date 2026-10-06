#!/bin/bash
# Adds a Wi-Fi network to the Dashwheel display, ahead of the ones it knows:
# usually the phone's hotspot, which the head unit uses too.
#
#   On the Pi:   sudo ./pi/add-wifi.sh [name]
#
# It asks for the network's name and password; the password isn't shown (or
# reads it from a pipe: wifi-from-card.sh does). The Pi joins it at its next
# start, or right away when the network it is on goes.
set -euo pipefail
# The file holds the networks' keys: what is written here is root's alone.
umask 077

[ "$(id -u)" -eq 0 ] || { echo "run as root (sudo)"; exit 1; }
WPA=/etc/wpa_supplicant/wpa_supplicant-wlan0.conf
[ -f "$WPA" ] || { echo "$WPA is missing: run install.sh first"; exit 1; }

ssid="${1:-}"
[ -n "$ssid" ] || read -r -p "Hotspot name: " ssid
[ -n "$ssid" ] || exit 1
if [ -t 0 ]; then
  read -r -s -p "Password: " pass
  echo
else
  IFS= read -r pass || [ -n "$pass" ]
fi
[ ${#pass} -ge 8 ] || { echo "A Wi-Fi password has at least 8 characters"; exit 1; }
# Made first, and aside: wpa_passphrase prints its complaint (a password or a
# name too long) where the entry would be, and that text in the file left the
# Pi without Wi-Fi at its next start.
entry=$(printf '%s\n' "$pass" | wpa_passphrase "$ssid") || { echo "That name or password can't be used: $entry"; exit 1; }

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
# Ahead of the networks known before: 10, or PRIORITY (wifi-from-card.sh puts wifi.txt's first one first).
priority="${PRIORITY:-10}"
case "$priority" in ''|*[!0-9]*) priority=10 ;; esac
printf '%s\n' "$entry" | sed "/^\s*#/d; s/^}\$/\tpriority=$priority\n}/" >> "$WPA"
chmod 600 "$WPA"
echo "Added \"$ssid\". The Pi prefers it from its next start."
