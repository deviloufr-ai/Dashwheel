<#
Fetch the unit's log from the PC, or give the unit a GitHub token so its
Settings > Driving > "Send the log" button can post an issue.

  .\tools\dash_log.ps1 fetch [-Unit 192.168.1.2:9876]   # writes logs\unit-<date>\
  .\tools\dash_log.ps1 token <file-with-token> [-Unit ...]

The token: a fine-grained personal access token limited to the Dashwheel
repository with "Issues: read and write". It is pushed to the unit only, never
into the app or the repository.
#>
param(
    [Parameter(Mandatory = $true, Position = 0)][ValidateSet("fetch", "token")][string]$Action,
    [Parameter(Position = 1)][string]$TokenFile,
    [string]$Unit = "192.168.1.2:9876"
)
$ErrorActionPreference = "Stop"
$pkg = "com.openauto.dash"
adb connect $Unit | Out-Null
$adb = @("-s", $Unit)

if ($Action -eq "token") {
    if (-not $TokenFile) { throw "give the file holding the token" }
    adb @adb push $TokenFile /data/local/tmp/dashwheel_issue_token | Out-Null
    adb @adb shell chmod 600 /data/local/tmp/dashwheel_issue_token
    Write-Host "Token is on the unit."
    return
}

$dir = Join-Path "logs" ("unit-" + (Get-Date -Format "yyyyMMdd-HHmmss"))
New-Item -ItemType Directory -Force $dir | Out-Null
adb @adb logcat -d -b all -v threadtime | Set-Content "$dir\logcat.txt"
adb @adb shell "su -c 'cat /data/data/$pkg/files/debug.log'" | Set-Content "$dir\events.txt"
adb @adb shell "su -c 'cat /data/data/$pkg/shared_prefs/car_power.xml'" | Set-Content "$dir\car_power.xml"
adb @adb shell "getprop" | Set-Content "$dir\getprop.txt"
adb @adb shell "su -c 'ls -l /sys/fs/pstore; cat /sys/fs/pstore/console-ramoops* 2>/dev/null'" | Set-Content "$dir\pstore.txt"
Write-Host "Saved to $dir"
