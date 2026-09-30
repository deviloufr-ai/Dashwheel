<#
Fetches the unit's log over adb, for when the phone route (Settings > Driving >
Unit log, then the companion's notification) is not at hand.

  .\tools\dash_log.ps1 [-Unit 192.168.1.2:9876]   # writes logs\unit-<date>\
#>
param([string]$Unit = "192.168.1.2:9876")
$ErrorActionPreference = "Stop"
$pkg = "com.openauto.dash"
adb connect $Unit | Out-Null
$adb = @("-s", $Unit)
$dir = Join-Path "logs" ("unit-" + (Get-Date -Format "yyyyMMdd-HHmmss"))
New-Item -ItemType Directory -Force $dir | Out-Null
adb @adb logcat -d -b all -v threadtime | Set-Content "$dir\logcat.txt"
adb @adb shell "su -c 'cat /data/data/$pkg/files/debug.log'" | Set-Content "$dir\events.txt"
adb @adb shell "su -c 'cat /data/data/$pkg/files/log_snapshots/*'" | Set-Content "$dir\snapshots.txt"
adb @adb shell "su -c 'cat /data/data/$pkg/shared_prefs/car_power.xml'" | Set-Content "$dir\car_power.xml"
adb @adb shell "getprop" | Set-Content "$dir\getprop.txt"
adb @adb shell "su -c 'ls -l /sys/fs/pstore; cat /sys/fs/pstore/console-ramoops* 2>/dev/null'" | Set-Content "$dir\pstore.txt"
Write-Host "Saved to $dir"
