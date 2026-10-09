# Second screen (Pi ↔ head unit) review, 2026-10-09

Read-only review of the link between the head unit and the Raspberry Pi display, and of the
design on both sides. Files: `link/.../DisplayMessages.kt`, `SecureChannel.kt`,
`app/.../DisplayLink.kt`, `SecondScreenController.kt`, `ClusterFeed.kt`, `StreamDisplay.kt`,
`display/.../DisplayServer.kt`, `Screen.kt`, `VideoSink.kt`, `Painter.kt`, `tools/pi/*`.
Nothing was built or run on a device.

**Status 2026-10-09 evening:** P0 and every P1 item below are built (display protocol 2), with unit tests
and lint passing; not yet committed, and not yet tried on the Pi or the head unit. P2 and P3 are open.

## What is good

- One encrypted, authenticated channel for the phone and the display (ECDH + pairing HMAC,
  AES-GCM with counter nonces). Replays and reordering fail closed.
- Discovery is layered (beacon → last address → DNS-SD → subnet scan) and the beacon makes the
  common case (both on the hotspot) link within a second.
- Video back-pressure is bounded at every stage: 5-frame outbox, 128 KB socket buffer, per-frame
  acks with a 300 ms window, 5-frame decoder queue, AUD after each frame. The 0.2 s lag measured
  on 2026-10-04 confirms the design.
- The Pi paints the newest state on its own thread (cf7f705), the card goes read-only by itself,
  and the installer and card image are reproducible.

## P0: wrong on screen for other users

1. **The Pi's clock is in the Pi's time zone, which the card image never sets.** `ClusterState.clock`
   is epoch milliseconds; `Painter`/`ClusterText.clock` formats it with `ZoneId.systemDefault()`.
   Raspberry Pi OS defaults to Europe/London, so every card written from the ready-made image shows
   the hour wrong in France, Germany, Spain, Italy, Poland, the Netherlands… (the bench Pi was set up
   through Imager with a time zone, which hid it). Before any link, the waiting screen's clock is
   the Pi's own: no RTC, so it is the fake-hwclock stamp from the last run until NTP answers.
   Proposal: the head unit sends its wall clock **and zone offset** (or local-time fields) at link-up
   and in each ping (`Hello`/`Ping` or a small `display_time` message), the Pi formats with that
   offset and, as root, also sets its system time (`date -s`) so journal times are right.

## P1: logic that misleads or costs the most days

2. **Bitrate adaptation treats a slow decoder as a slow network.** `DisplayStats` carries one
   `framesDropped` count; frames dropped because the decoder queue is full (throttled, hot Pi, as on
   2026-10-07: 90 °C, 300 MHz) and frames dropped because the link lost one look the same, and the
   unit answers both by cutting bitrate, which does nothing for a throttled Pi. Proposal: split the
   count (`droppedLate` vs `droppedMissing`), add `throttled` (bits of `vcgencmd get_throttled`) and
   `tempC`; on the unit, lower **fps or picture height** when the Pi is the bottleneck and bitrate
   only for network loss; show "display under-powered / hot" in Settings and the unit log; the Pi
   logs the throttled flags at start and on change.

3. **The network topology is the recurring failure, not the code.** Two Wi-Fi hops through a phone,
   2.4 GHz only on a 3B, hotspot client isolation, the head unit staying on the house Wi-Fi while the
   Pi moves to the hotspot. Proposal: a **wired point-to-point link as the preferred transport**:
   USB-Ethernet adapter on the K706 to the Pi's eth0; the Pi runs a DHCP server on eth0
   (`systemd-networkd`: `DHCPServer=yes`, e.g. 10.47.0.1/24) so the unit gets an address with no
   router; the beacon already reaches it (bound to every interface), and `DisplayLink.probe()` should
   also consider the Ethernet transport (today it only looks at Wi-Fi, then a hosted hotspot). Wi-Fi
   stays as fallback. Latency and jitter drop, and the hotspot stops being a requirement.
   Lighter, Wi-Fi-only steps if the cable is not wanted: the Pi hops through its known networks after
   60 s unlinked; Settings shows the unit's own SSID next to "Searching" so a mismatch is visible.

4. **Dead links are noticed late and stall everything.** Both sides ping every 15 s and time out at
   45 s; the Pi declares "No signal" after 20 s (video) or 12 s (data). While the socket is stalled the
   single outbox sender blocks, so `DisplayMode`, `DisplayWords`, `DisplayBrightness` wait behind it
   and `DisplayLink.send` then drops silently when the 48-slot queue fills. Proposal: ping 5 s,
   timeout 15 s on both sides; the Pi sends its stats line in every mode, not only video (it doubles
   as a heartbeat); a send that takes longer than ~3 s closes the link rather than waiting 45 s; two
   queues (control first, video second) or at least conflate `ClusterState` (latest wins).

5. **Two clusters that diverge.** The board (layout, widget, design per slot) applies to the
   streamed cluster only; the Simple display draws fixed pages, guesses the turn arrow from the
   instruction's words in 9 languages (`Maneuver.of`), and gets its labels resent five times a
   second. Proposal: `ClusterState` carries the page's arrangement and slot kinds so the Pi draws what
   the board says (with its own designs as the per-slot look), the unit sends a maneuver kind from
   `NavDirections` instead of words, and the labels move into `DisplayWords` (sent once per language,
   already persisted on the Pi).

6. **Protocol and program versions are declared but never checked.** `Hello.protocol`,
   `DisplayHello.protocol` and `DisplayHello.appVersion` (always "1.0", `display/build.gradle.kts`)
   are read by nobody. The Pi is updated by card or scp, the unit by OTA; the two halves have already
   drifted several times ("unit still sends no design", "head unit half needs the next release").
   Proposal: stamp the display build with the git describe, compare on both sides, and show in
   Settings "Display program 1.0.612, older than this app: rewrite the card" (or the reverse).

## P2: design improvements

7. **Time to picture at each ignition** (~25–30 s on a hotspot, ~16 s to the logo screen) is the
   metric the driver feels every day, since the Pi is on ACC. Options, in rising effort: keep the Pi
   powered through short stops (delay-off relay, already in the README); strip the initramfs and
   Plymouth from the boot path (draw the logo from fb0 earlier); pre-start the video pipeline at
   boot; long-term, a native display program (Kotlin/Native, Rust or Go) would listen in ~5 s with a
   fraction of the RAM and no JVM warm-up.

8. **Mode switches cost ~1 s of black** (two GStreamer processes, one DRM master; `fb0` only
   papers over the gap). Investigate painting IDLE/DATA straight to fb0 (`ConsoleFrameBuffer`
   exists) with no `frames` pipeline at all, leaving the video pipeline the only DRM client; or a
   single pipeline with `intervideosrc`/`compositor`. Caveat to test: whether fb0 writes show while
   kmssink holds the primary plane.

9. **Beacon and DNS-SD reception on the head unit** has no `MulticastLock`; some Android Wi-Fi
   drivers filter broadcast in power save, and NSD needs the lock on several ROMs. Acquire one while
   searching (the `WifiLock` is only held once connected).

10. **Pi power saving**: the installer's `ExecStartPre=iw dev wlan0 set power_save off` is a no-op
    because `iw` is not in the apt list (seen on the Pi 2026-10-07). Add `iw`.

11. **Stale announced addresses never expire** in `DisplayLink.announced`; each costs a 2 s connect
    timeout per dial round after a DHCP change. Drop an address after three misses.

12. **Settings copy and placement**: the status line shows raw exception names
    (`lastAttempt`), the entry sits three levels deep (Settings → Display → Second screen), and
    pairing from the phone needs the phone linked at that moment with no entry named for it in the
    companion (open since the 2026-10-03 review). Proposal: one card with three states and one action
    each: *Not paired → Pair*, *Searching on <SSID> → Pairing file / Help*, *Connected: 1024×600,
    1.8 Mbit/s, Pi 62 °C → Board*.

13. **The ack says "received", not "decoded"** (`VideoAck` goes out before `screen.feed`). The window
    bounds network buffers only; the decoder queue is bounded separately (5 frames), so this is fine
    today, but acking after a successful `offer` would make one window cover both.

## P3: tidy-ups

14. `Painter.kt` (1130 lines, four designs in one class): split per design; the tests already render
    each design.
15. `pairing.txt` holds the secret in clear on the FAT boot partition: acceptable for a display, but
    say so in the README (anyone with the card can connect to that pairing).
16. The pairing QR comes back after 3 min without a head unit: fine on ACC power; on a bench Pi left
    on the house Wi-Fi it is offered to anyone on that LAN. Consider requiring a button press or a
    file on the card to re-offer.
17. No test covers a second connection replacing the first in `DisplayServer`, nor the reconnect
    path in `SecondScreenController.apply` (grace period then `startVideo()`); both are where the
    field bugs were.

## Suggested order

1 (clock) and 10 (`iw`) are an afternoon each. Then 2 (stats split) and 6 (versions), which make
every later field test diagnosable. 3 (wired link) is the structural fix for the stutter and the
"waiting on the hotspot" cases and needs one adapter to try. 4 and 5 after that.
