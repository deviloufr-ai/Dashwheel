#!/bin/sh
# Moves the Pi to its preferred Wi-Fi (the highest priority: the phone's hotspot,
# see add-wifi.sh) when it is connected to another one and the preferred one is
# in range. wpa_supplicant keeps the network it joined at start-up: at home the
# Pi came up on the house Wi-Fi before the hotspot showed, and stayed there.
# Does nothing on the preferred network or with none: the car is left alone.
# Nor while a head unit is linked: the scan alone takes the radio off the
# channel for seconds and cuts the picture, and that link is what the hotspot
# was for (at home the head unit may well sit on the house Wi-Fi instead).
IF=wlan0
PORT=47811
w() { wpa_cli -i "$IF" "$@" 2>/dev/null; }

[ -z "$(ss -Htn state established "( sport = :$PORT )" 2>/dev/null)" ] || exit 0
cur=$(w status | sed -n 's/^id=//p')
[ -n "$cur" ] || exit 0
best=$(w list_networks | tail -n +2 | cut -f1 | while read -r id; do
  p=$(w get_network "$id" priority)
  case "$p" in ''|*[!0-9]*) p=0 ;; esac
  echo "$p $id"
done | sort -rn | head -1)
[ -n "$best" ] || exit 0
best_id=${best#* }
best_priority=${best% *}
cur_priority=$(w get_network "$cur" priority)
case "$cur_priority" in ''|*[!0-9]*) cur_priority=0 ;; esac
[ "$cur_priority" -lt "$best_priority" ] || exit 0

# Only names written as text ("Galaxy S25") can be looked for in the scan.
ssid=$(w get_network "$best_id" ssid)
case "$ssid" in \"*\") ssid=${ssid#\"}; ssid=${ssid%\"} ;; *) exit 0 ;; esac
w scan >/dev/null
sleep 6
w scan_results | tail -n +2 | cut -f5 | grep -qxF "$ssid" || exit 0
logger -t dashwheel-wifi "\"$ssid\" in range: moving to it"
w reassociate >/dev/null
