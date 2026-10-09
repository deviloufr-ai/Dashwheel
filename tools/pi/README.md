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

- **Network.** Every device joins the **phone's hotspot**; the head unit already does this for the phone link. The head unit finds the Pi by itself: its beacon first, then the last address that worked, DNS-SD, then a scan of the network. Better still, a **cable**: see *Wired to the head unit* below.
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
- **Read-only card.** The card turns read-only by itself once a head unit has paired (step 5), so a sudden power cut can't damage it.

### Wired to the head unit (recommended)

Two Wi-Fi hops through a phone are the usual cause of a late or stuttering picture, and at home the head unit and the Pi easily end up on different networks. A cable avoids all of it:

- a **USB Ethernet adapter** on one of the head unit's USB ports (most head units take the common AX88179 / RTL8153 ones), and
- an **Ethernet cable** from it to the Pi's own port.

Nothing to set up: the Pi hands the head unit an address on that cable (10.47.0.x), without a gateway, so the head unit keeps using the phone's Wi-Fi for the internet, and finds the Pi over the cable within a second. Wi-Fi stays as the fallback. Do not plug the Pi's Ethernet port into a home network: it hands out addresses there too.

## 2. Video output

- **HDMI:** plug in the monitor. The installer keeps HDMI on even if the monitor powers up after the Pi.
- **RCA (composite) monitor:** use the Pi's 3.5 mm jack with a TRRS-to-RCA cable, and install with `--composite`. The Pi puts video on the sleeve and ground on the second ring; many camcorder cables swap these, so if the picture doesn't show, try the red or white plug. `overscan=5` is then set in `display.conf`; adjust it to what the monitor crops off.

## 3. The card, ready-made

The easiest way: a card with everything already installed.

1. Download **dashwheel-second-screen.img.xz** from the [second screen card release](https://github.com/deviloufr-ai/Dashwheel/releases/tag/second-screen-image).
2. In **Raspberry Pi Imager**, pick the Raspberry Pi model, then **Choose OS → Use custom** and the downloaded file, then the SD card, and write it. If Imager offers OS customisation, say **No**: this card doesn't use it.
3. Take the card out and put it back into the computer. Open the drive called **bootfs**, then the **dashwheel** folder, and open **wifi.txt** (Notepad works). After `wifi_name=` and `wifi_password=`, write the **phone's hotspot** name and password, and your country after `country=`. Save it.
4. **Pi 3B only:** its Wi-Fi is 2.4 GHz only, so set the phone's hotspot to 2.4 GHz or dual band. A 3B+ also does 5 GHz, which leaves more room for the video.
5. Put the card in the Pi, start the phone's hotspot, and power the Pi. The first start takes about a minute longer: the card grows to its full size. The monitor then shows a QR code: go to step 5.

The Pi saves the Wi-Fi at its start and clears the password from `wifi.txt`. To add a network later, fill in the lines again. Once the card is read-only (after pairing), the password stays in the file and is read at every start.

- **RCA (composite) monitor or ignition sense** (`--composite`, `--acc-sense`): install by hand (step 4) instead.
- **Updating:** write the new image to the card. To keep the pairing, first copy the `dashwheel` folder from the card's **bootfs** drive to the computer, then copy it back onto the new card before its first start.
- **SSH** (optional; the card has no login): put an empty file named `ssh` and a `userconf.txt` on **bootfs**. `userconf.txt` holds one line, `name:` followed by the output of `openssl passwd -6`.

## 4. Or install by hand

On a card of your own, for an RCA monitor, ignition sense, or to work on the Pi.

### The Pi's system

1. Write **Raspberry Pi OS Lite** (Bookworm or later) with Raspberry Pi Imager. In its settings:
   - Wi-Fi: the **phone's hotspot** name and password.
   - Hostname: `dashwheel-display`.
   - Enable SSH.
2. **Pi 3B only:** set the phone's hotspot to 2.4 GHz or dual band (see above).
3. Start the phone's hotspot and boot the Pi.

### Install

On a computer with this repository:

```sh
./gradlew :display:installDist
scp -r display/build/install/dashwheel-display tools/pi pi@dashwheel-display.local:
ssh pi@dashwheel-display.local 'sudo ./pi/install.sh ./dashwheel-display'   # add --composite / --acc-sense
ssh pi@dashwheel-display.local 'sudo reboot'
```

Run the same commands again to update. Settings and the pairing are kept.

**Another Wi-Fi network** (usually the phone's hotspot, if you set up the Pi on your home Wi-Fi): `ssh -t pi@dashwheel-display.local sudo ./pi/add-wifi.sh` asks for its name and password. The Pi then prefers it to the networks it already knows, and moves to it when it comes in range (never while a head unit is linked). At home the head unit itself usually stays on the house Wi-Fi: to test the hotspot there, pick it in the head unit's Wi-Fi settings too. The installer moves the Pi's Wi-Fi from NetworkManager to wpa_supplicant, which joins in seconds, so add networks with this script (or `wifi.txt`, see step 3) rather than `nmcli`.

**Building the card image yourself:** on an arm64 Linux, `sudo tools/pi/build-image.sh display/build/install/dashwheel-display out.img.xz`. GitHub Actions does it for each change to the second screen (`.github/workflows/pi-image.yml`).

## 5. Pair (once)

1. After the reboot, the monitor shows a **QR code**.
2. In the Dashwheel **phone app**, tap **Pair a car** and scan the code on the monitor (the phone's camera app works too). Confirm **Send to the car**. The phone passes the code to the head unit over the phone link, so the head unit must be linked to the phone at that moment.
3. The head unit connects to the Pi within a few seconds. The QR code disappears from the idle screen once a head unit has used it.
4. The Pi then saves the pairing and turns its card **read-only** from its next start: from then on, cutting the power (the ignition) can't damage it. Nothing to do. To keep the card writable, put `read_only=manual` in `display.conf` before pairing.

**Pairing without the phone app:** the code is also in `dashwheel/pairing.txt` on the SD card's boot partition. Copy it to a USB stick and use **Import a pairing file** in the head unit's second-screen settings.

**New pairing:** put the SD card in a computer, delete `dashwheel/pairing.txt` and `dashwheel/paired` on its boot partition, put it back and start the Pi: it shows a new code, even on a read-only card.

**Updating the Pi** on a read-only card: turn that off first (`sudo raspi-config nonint do_overlayfs 1`, then restart), run the installer: the Pi turns read-only again by itself the next time a head unit connects.

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
- **Stuttering video:** first look at **Settings → Display → Second screen** on the head unit: it says when the Pi is under-powered or hot (the Pi reports its own throttle flags), which no Wi-Fi setting can fix: a 5 V 2.5 A supply over a short, thick cable, and air or a heatsink. Then the Wi-Fi: both hops (head unit → phone → Pi) share the air; use the cable above, 5 GHz on a 3B+, or a lower picture height on the head unit.
- **Wrong time on the screen:** the head unit sends its clock and time zone at each link (older head units send no zone: set it on the Pi with `sudo timedatectl set-timezone Europe/Paris`).
- **"Program older than this app" in the head unit's settings:** the card's program and the app ship separately; write the newest card image (step 3) or run the installer again (step 4).

## Trying it on a computer

With GStreamer installed (`gst-libav` for the decoder):

```sh
mkdir -p /tmp/dw && printf 'sink=auto\nwidth=1024\nheight=600\n' > /tmp/dw/display.conf
display/build/install/dashwheel-display/bin/dashwheel-display --config /tmp/dw
```
