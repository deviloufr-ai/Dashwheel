#!/bin/bash
# Joins the Wi-Fi networks written in dashwheel/wifi.txt on the SD card's boot
# partition, which any computer can edit (see wifi.txt.example). Runs at every
# start, before the Wi-Fi comes up (dashwheel-wifi.service).
#
# Once saved, the passwords are cleared from the file. On a read-only card the
# file is left as it is: what this start saves is gone at the next one, so it
# is read again each time.
set -uo pipefail

BOOT=/boot/firmware
[ -f "$BOOT/config.txt" ] || BOOT=/boot
FILE="$BOOT/dashwheel/wifi.txt"
WPA=/etc/wpa_supplicant/wpa_supplicant-wlan0.conf

# A fresh Raspberry Pi OS keeps the Wi-Fi radio off until it is told a country.
rfkill unblock wifi 2>/dev/null || true

[ -f "$FILE" ] && [ -f "$WPA" ] || exit 0

# Spaces around a value, a Windows line end, or quotes around it are not part of it.
value() {
  local v="${1#*=}"
  v="${v%$'\r'}"
  v="${v#"${v%%[![:space:]]*}"}"
  v="${v%"${v##*[![:space:]]}"}"
  case "$v" in \"*\") v="${v:1:${#v}-2}" ;; esac
  printf '%s' "$v"
}

saved=0
name=""
while IFS= read -r line || [ -n "$line" ]; do
  line="${line#"${line%%[![:space:]]*}"}"
  case "$line" in
    wifi_name=*) name=$(value "$line") ;;
    wifi_password=*)
      pass=$(value "$line")
      if [ -n "$name" ] && [ -n "$pass" ]; then
        # The first one (the phone's hotspot) ahead of the others.
        if printf '%s\n' "$pass" | PRIORITY=$((saved ? 10 : 20)) /usr/local/sbin/dashwheel-add-wifi "$name"; then
          saved=1
        fi
      fi
      name="" ;;
    country=*)
      country=$(value "$line" | tr '[:lower:]' '[:upper:]')
      if printf '%s' "$country" | grep -qE '^[A-Z]{2}$'; then
        if grep -q '^country=' "$WPA"; then
          sed -i "s/^country=.*/country=$country/" "$WPA"
        else
          sed -i "/^update_config=/a country=$country" "$WPA"
        fi
      fi ;;
  esac
done < "$FILE"

# Kept on the card only while it is read-only (see above).
if [ "$saved" -eq 1 ] && ! grep -q 'overlayroot=tmpfs' /proc/cmdline; then
  sed -i 's/^\(\s*wifi_password\s*=\).*/\1/' "$FILE" && sync
fi
exit 0
