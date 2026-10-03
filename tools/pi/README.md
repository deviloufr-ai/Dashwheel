# Dashwheel second screen on a Raspberry Pi

A Raspberry Pi 3 (3B or 3B+) wired to a monitor becomes a second, non-touch
screen for the Dashwheel launcher: an instrument cluster, or an app such as
Google Maps, streamed from the head unit. Use it on head units with no video
output of their own, such as the K706 (QF001, UIS7862).

```
 Phone (hotspot + internet)
   ▲ Wi-Fi              ▲ Wi-Fi
 Head unit (Dashwheel) ──► Raspberry Pi 3 ──HDMI / RCA──► monitor
   encrypted link, port 47811: H.264 video, or cluster data
```

- **Network.** Every device joins the **phone's hotspot**; the head unit already does this for the phone link. The head unit finds the Pi by itself: DNS-SD first, then the last address that worked, then a scan of the hotspot.
- **Video.** The head unit draws the picture and encodes it to H.264. The Pi's VideoCore decodes it in hardware straight to the screen, with no desktop and no browser.
- **Fallback.** When the head unit can't encode (another app holds the encoder), it sends only data, and the Pi draws a simple cluster itself.
- **Security.** Every connection is encrypted and authenticated, like the phone link. Only a head unit that was given the Pi's pairing code can connect.

## 1. Power: from the head unit's wiring, not its USB

A head unit's USB port gives 0.5–1 A. A Pi 3 needs up to 2.5 A, and the monitor needs 12 V anyway. Tap the head unit's ISO/Quadlock harness on the car side, and give each branch its own inline fuse:

| Harness wire | Goes to | Fuse |
|---|---|---|
| Yellow, constant 12 V | A 12 V → 5 V **3 A synchronous buck converter** (8–36 V input, e.g. Pololu D24V30F5 / D36V28F5), set to 5.1–5.2 V, then the Pi's micro-USB over a short, thick cable | 3 A |
| Red, ACC 12 V | The monitor's 12 V input (headrest and 7" monitors draw 0.5–1 A) | 2 A |
| Black, ground | Shared ground for the converter and the monitor | — |

- **Converters to avoid.** Bare LM2596 boards sag under load, and a Pi on low voltage crashes and corrupts its SD card.
- **Clean shutdown.** Cutting a Pi's power while it writes can corrupt its SD card. Either:
  - **Relay and signal:** power the converter through a **12 V delay-off relay module**. It is fed from yellow and triggered by red (ACC), and keeps power on for about 60 s after the ignition goes off. Also feed ACC through a **PC817 optocoupler** module to GPIO3 (pin 5) and ground (pin 6), wired so the opto pulls GPIO3 low while ACC is on. Install with `--acc-sense`: when ACC goes off, GPIO3 rises and the Pi shuts down before the relay cuts power.
  - **Ready-made board:** use an ignition-sense power HAT (e.g. Mausberry car switch), which does the same in one module.
  - **Signal alternative:** the blue/white **REMOTE** (amplifier turn-on) wire follows the head unit's own power, sleep included. It can be the sense signal instead of ACC, but it carries only 100–300 mA: never power anything from it.
- **Read-only card.** With `--overlay` too, a sudden power cut can't damage the card.

## 2. Video output

- **HDMI:** plug in the monitor. The installer keeps HDMI on even if the monitor powers up after the Pi.
- **RCA (composite) monitor:** use the Pi's 3.5 mm jack with a TRRS-to-RCA cable, and install with `--composite`. The Pi puts video on the sleeve and ground on the second ring; many camcorder cables swap these, so if the picture doesn't show, try the red or white plug. `overscan=5` is then set in `display.conf`; adjust it to what the monitor crops off.

## 3. The Pi's system

1. Write **Raspberry Pi OS Lite** (Bookworm or later) with Raspberry Pi Imager. In its settings:
   - Wi-Fi: the **phone's hotspot** name and password.
   - Hostname: `dashwheel-display`.
   - Enable SSH.
2. **Pi 3B only:** its Wi-Fi is 2.4 GHz only, so set the phone's hotspot to 2.4 GHz or dual band. A 3B+ also does 5 GHz, which leaves more room for the video.
3. Start the phone's hotspot and boot the Pi.

## 4. Install

On a computer with this repository:

```sh
./gradlew :display:installDist
scp -r display/build/install/dashwheel-display tools/pi pi@dashwheel-display.local:
ssh pi@dashwheel-display.local 'sudo ./pi/install.sh ./dashwheel-display'   # add --composite / --acc-sense
ssh pi@dashwheel-display.local 'sudo reboot'
```

Run the same commands again to update. Settings and the pairing are kept.

## 5. Pair (once)

1. After the reboot, the monitor shows a **QR code**.
2. In the Dashwheel **phone app**, tap **Pair a car** and scan the code on the monitor (the phone's camera app works too). Confirm **Send to the car**. The phone passes the code to the head unit over the phone link, so the head unit must be linked to the phone at that moment.
3. The head unit connects to the Pi within a few seconds. The QR code disappears from the idle screen once a head unit has used it.
4. Only now, if you want a read-only card: `sudo ./pi/install.sh ./dashwheel-display --overlay` (plus the flags you used before).

**Pairing without the phone app:** the code is also in `dashwheel/pairing.txt` on the SD card's boot partition. Copy it to a USB stick and use **Import a pairing file** in the head unit's second-screen settings.

**New pairing:** delete `dashwheel/pairing.txt` (and `dashwheel/paired`), reboot, and pair again.

## 6. Use

On the head unit: **Settings → Display → Second screen**:
- the mode: off, cluster, or an app
- the cluster's pages
- which steering-wheel keys change the page
- the picture height (480p, 720p or 1080p; lower is lighter on the Wi-Fi)
- *Rear-seat screen*: video apps may play while driving only on a screen the driver can't see

For keys to work while another app is in front, Dashwheel's accessibility service must be on. If it was already on before this update, turn it off and on again: Android only grants key filtering when the service is switched on.

## Settings file

`dashwheel/display.conf` on the boot partition: see `display.conf.example`. It sets the screen's name, overscan, a forced size for monitors that report none, and a custom GStreamer chain.

**Boot picture:** the screen shows the Dashwheel logo from power-up until its first picture. To show your own instead, such as your car maker's logo, put a PNG named `splash.png` in `dashwheel/` on the boot partition and run the installer again. A transparent background works best, about 300 to 600 pixels wide.

## Troubleshooting

- **Logs:** `journalctl -u dashwheel-display -f`
- **Screen size:** `cat /sys/class/drm/card*-HDMI-A-1/modes` shows what the monitor reports; the first line is used.
- **Decoder check:** `gst-launch-1.0 videotestsrc num-buffers=100 ! x264enc ! h264parse ! v4l2h264dec ! kmssink` needs `gstreamer1.0-plugins-ugly` for x264enc. Stop the service first.
- **Head unit can't find the Pi:** make sure both are on the hotspot and the phone doesn't isolate hotspot clients from each other (some phones call it "client isolation" or "AP isolation").
- **Stuttering video:** check Wi-Fi. Both hops (head unit → phone → Pi) share the air. Use 5 GHz on a 3B+, or lower the resolution or bit rate on the head unit.

## Trying it on a computer

With GStreamer installed (`gst-libav` for the decoder):

```sh
mkdir -p /tmp/dw && printf 'sink=auto\nwidth=1024\nheight=600\n' > /tmp/dw/display.conf
display/build/install/dashwheel-display/bin/dashwheel-display --config /tmp/dw
```
