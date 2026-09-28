# Feature and function review, 2026-09-28

A review of what Dashwheel does, what it does badly or for nothing, and what was
missing. Four areas were read end to end: the widgets and their catalogue, the
settings and menus, everything that happens by itself while driving, and apps,
media, navigation and phone. The first batch of fixes and features is built and
checked on the emulator; the rest is listed for a decision.

Screenshots: `docs/feature-review-2026-09-28/`.

## 1. What was built

### Things that did not work as a driver expects

| Problem | What it does now |
|---|---|
| The voice could talk into a phone call: only Android's own call mode was checked, which the unit's Bluetooth calls never set. | The voice waits while a call is on (companion or head unit Bluetooth) and says what it held afterwards. A call still ringing is announced as before. |
| Every sentence queued behind the one before: "the engine is overheating" waited behind a briefing. | Overheating, battery not charging and a door open on the move go first, and are said during a call too. |
| Wheel buttons "Go to Home", "Open the app drawer", "Next / Previous dashboard" did nothing visible with another app in front. | The dashboard comes in front first. The dashboard buttons show it on the page it was left on; the next press steps. |
| Wheel buttons "Answer / Decline / Hang up" only reached the companion app. | They act on the call wherever it is, the head unit's own Bluetooth included. |
| "Clear" sat beside "Scan" on the fault codes tile and erased the car's stored codes on one tap, moving or not. | Parked only, and asked first. |
| "No connection" was reported as "Address not found" on the map. | It says "No connection". |
| Opening the navigation app beside the dashboard docked the dashboard to half the screen even when that app was gone. | The app is checked first. |
| The map's Start button and the fuel tile each had their own hand-off to Google Maps, with no message when the unit had no navigation app. | One hand-off for all: the app the NAVI key opens, else Google Maps, else Waze, else any map app, and a message when there is none. |
| The voice switches were in Car, AI mechanic, where someone without a Gemini key never looks. | Settings, Alerts, Voice: warnings and tips, start-up briefing, messages. |

### New

| Feature | How it works |
|---|---|
| **My places** | Settings, Driving, My places: Home and Work, from an address or from where the car is. They show as buttons under the map tile's search bar with the last six destinations, usable while moving, where typing has to wait for a stop. |
| **Low fuel on the move** | Said once when the range falls to 80 km, with the cheapest station nearby when prices are known there. Again only after a fill-up. Works without the OBD adapter. |
| **Messages announced** | "Message from Alex" when a message arrives on the linked phone: who, never what. The same conversation is not announced again for three minutes. Off in Settings, Alerts. |
| **App drawer** | A search field, and the apps opened most in a row of their own at the top. |
| **Eleven wheel button actions** | Say how the car is, Quiet on / off, Navigate home, Navigate to work, Go to the cheapest fuel, Dashboard above, Dashboard below, Read the last message, plus the four fixed above. Each answers out loud, so a button never seems dead. |

"Quiet" silences what the car says by itself until the next start. Urgent
warnings are still said.

### Removed

The unused "favourites" code in the app launcher (never called), replaced by the
launch counter behind the drawer's "Most used" row.

## 2. How it was checked

| Check | Result |
|---|---|
| Unit tests | 438 pass, 25 of them new (`HandsFreeTest`) |
| Lint | passes |
| Translations, 8 languages | 1709 strings complete, placeholders match |
| Emulator (1280x720, Android 10) | see below |

Seen working on the emulator: the drawer's search and "Most used" row, the Voice
switches saving, My places set from the car's position and from a typed address,
the new actions in the wheel button picker, each new button pressed with a real
key event, "Go to Home" and "Dashboard below" pressed over another app, the
cheapest station found in Paris and guidance started, the confirmation before
fault codes are cleared.

**Not checked on a screen:** the place buttons on the map tile (the map cannot
run on the emulator). **Not checked at all outside unit tests:** the voice
waiting during a call, a message being announced, low fuel on the move. All of
it still has to be tried in the car.

## 3. Findings left for a decision

These change what the driver sees or take something away, so nothing was done
without asking.

### Widgets

1. **Designs that take a tile's buttons away.** Volume knob and Fader draw a
   control that cannot be touched. Rotary phone cannot call. At the default size
   several designs drop the caption that says what is wrong. Proposed: hide a
   design where it cannot show the tile's buttons, or drop the three.
2. **The default layout a new driver gets** gives a whole page to the G-force
   meter and another to the raw OBD list, and never suggests the car-care tiles
   that carry the spoken alerts. Range, Doors and CAN monitor are held back when
   no OBD adapter is paired although they come from the CAN box.
3. **Duplicates.** "Engine gauges" and "All OBD data" are the same tile in two
   orders. "Maps window" and "Google Maps inside" are the same as two entries of
   the Windows tab. Fuel is spread over three tiles, the odometer over two.
4. **Parking spot** measures the distance from the car to the car: it reads
   "0 m" at every start. The spot is already saved at switch-off and sent to the
   phone. Proposed: take it off the catalogue.
5. **Quick dial and Agenda** read contacts and calendars stored on the head
   unit, which has none. Proposed: feed them from the phone over the link.
6. **Road sign** design draws the current speed inside a red ring, which reads
   as a speed limit.
7. **Rev scales** stop at 7000 on a diesel that redlines near 5000; the car
   profile already knows the redline.

### Alerts

8. **Two lists for the same six alerts.** Choosing a design and "Speak it" for
   the doors does nothing until the separate switch above is on, and that
   switch is off by default. Proposed: one row per alert with its own switch.
9. **Battery alerts** speak on a single voltage sample from the adapter, where
   the bar's chip requires a 30 second median from the engine computer. A weak
   battery can be announced at every ignition-on with the glow plugs running.
10. **Tyre alerts** have no margin: 180 / 181 kPa raises and clears the alert
    over and over. The low limit ignores the pressures in the car profile.
11. **Red and amber bar alerts** are only worked out while the bar is drawn, so
    not behind Maps full screen.
12. **Break reminder and eco figures** stop when the OBD link drops.

### Settings

13. **Volume control** offers three choices on the K706 where two are the same
    and the third makes every volume control do nothing.
14. **System app** promises Google Maps in a tile, which it does not deliver;
    "System permissions" below it does.
15. **Units and clock** are fixed: km/h, °C, L/100 km, 24 hours.
16. **No way to save and restore** the layout and settings.
17. **The OBD adapter** cannot be changed from Settings once one is saved.
18. **"Changes only while parked"** says the app grid waits for a stop; it opens
    while moving, as a list.

### Apps, media, navigation, phone

19. **A docked app that crashes** is treated as closed by the driver and is not
    reopened, so guidance is lost until a tap.
20. **Sharing a place from the phone** pastes text into whatever field has the
    focus; it should start guidance. Needs a new message in the companion.
21. **Music does not resume** when the car starts. Left out on purpose: what the
    unit does by itself at power-up has to be seen in the car first.
22. **Updates** are checked once per start, often before the hotspot is up, and
    a failed download only shows in Settings.
23. **Turn-by-turn in the map tile** is described in the code and its library is
    shipped, but Start hands over to the navigation app. Either wire it or take
    the library out.
24. **Past drives** are logged and sent to the phone but cannot be seen on the
    head unit.

## 3b. Second batch: the high-priority findings (same day, after v1.0.464)

| # | Finding | What it does now |
|---|---|---|
| 1 | Designs that hid a tile's buttons | The widget-specific designs show their keys from a two-row tile up (volume knob, fader, parking pointer); Neon puts the caption and keys under a square gauge; Island shows three keys; Contour shows every key where it fits. The rotary phone design is retired (its tiles wear Faces, whose contacts can be tapped) and the road sign no longer draws the speed. A design a widget no longer offers falls back to its own look. |
| 2 | Default layout and templates | The three templates are built around the map, music and the car-care tiles (fault codes, battery, servicing, warm-up, particle filter, eco, break, tyres, fuel). G-force, raw OBD values, the CAN monitor, quick dial and the parking spot are left to the add sheet. Without an adapter only the OBD tiles go; range and fuel stay when the CAN box gives them; tyres only with the TPMS app. |
| 8 | Two alert lists | One row per alert with its own switch, and the same switch at the top of its design dialog. The section is called "Alerts Dashwheel shows". |
| 9 | Weak battery said at every start | It needs a full minute below 12 V (glow plugs and blower at ignition-on are not a weak battery), is said once a day at most (kept across restarts), and a voltage read at the adapter's own pin is allowed its diode drop (0.4 V). |
| 10 | Tyre alerts flickering at the limit | A problem ends only 15 kPa (or 5 °C) back on the right side; a sensor not heard for a minute holds no warning; the low limit is 80 % of the car profile's pressure for that axle. The alert, its voice and every tyre tile share one judgement. |
| 19 | Crashed Maps window left closed | When a docked app's window goes and its process is gone too, the window is opened again (twice in ten minutes at most). A window the driver closed, whose app still runs, stays closed as before. |

Checked: 452 unit tests, lint, translations, and on the emulator the templates dialog and pages, and a page of designed tiles showing their keys. Not seen on a screen: the alert rows with switches (the emulator has none of the head unit's alerts, only calls), the tyre and battery rules and the crash reopen, which need the car.

## 3d. Third batch: the medium findings

| # | Finding | What it does now |
|---|---|---|
| 3 | Duplicate widgets | The add sheet no longer offers All OBD data (Engine gauges in another order), Maps window and Google Maps inside (the Windows tab's own two ways to add Maps) or My car (a Settings page). Tiles already placed keep working. Fuel & range, Fuel to destination and Fuel nearby sit together under Vehicle. |
| 4 | Parking spot tile | No longer offered, nor in any template. The spot is still kept at switch-off and sent to the phone. |
| 11 | Bar alerts behind a full-screen app | Judged for the whole app at every reading, not by the bar: an alert raised behind Maps is on the bar, and in the log, when the driver comes back. The coolant limits follow the engine (105 and 110 °C for one that runs at 90), red exactly where the voice says it is overheating. New: a spoken warning when the battery is over-charged (over 15.5 V for 30 s, engine computer's reading only), and a "Dismiss the alert" wheel button. |
| 12 | Break reminder stopped without the adapter | Driving time is counted every second from the car box's speed, else the GPS's (held only then), whenever the adapter is not connected. |
| 13 | Volume control setting | Hidden on the QF firmware, where Automatic is the only way that works (a saved "Android" is put back to Automatic); on other units it moved to Settings, Advanced. |
| 14 | System app row | Says what it really does (Android widgets without a prompt each time). The PMPatch row is now called "Apps inside tiles". |
| 15 | Units and clock | In progress, in a later update. |
| 16 | Save and restore | Settings, Advanced, Your setup: every Dashwheel preference to a JSON file through Android's file picker (USB stick or the unit), restored from one with a confirmation and a restart. The AI key, phone pairings and this unit's own state stay out. |
| 17 | OBD adapter row | Settings, Car: the saved adapter's name, a tap to pick another, Forget. |
| 20 | Destination from the phone | In progress, in a later update. |
| 21 | Music at start | Settings, Driving, Resume music at start (off by default): the app that was playing within 30 s of the switch-off is told to play again 6 s after the start, unless something already plays. |
| 5 | Quick dial and Agenda | In progress, in a later update. |

Checked on the emulator: the OBD adapter row with Forget, the file picker saving the setup and the restore bringing the old theme back after a restart, the Driving page with the music option.

## 3c. Car test checklist (item A)

With the car parked and the unit updated:

1. Settings, Alerts: every alert has a switch in its row; turn Doors and Tyres on.
2. Open Google Maps in a docked window, then `am crash com.google.android.apps.maps` from adb: the window should come back within a few seconds.
3. Close the docked Maps window with its own close button: it should stay closed.
4. Assign "Go to Home" and "Dashboard below" to wheel buttons; press them over Maps full screen.
5. Map tile: the Home, Work and recent-destination buttons under the search bar.
6. Ignition on with the engine off for a minute: no "weak battery" unless it really is under 12 V.

Driving: a phone call (the voice waits), a message arriving (announced by sender), low fuel near 80 km, tyre pressure near the limit (one alert, not a flicker).

## 4. Suggested order

1. Try this batch in the car: the voice during a call, a message, the wheel
   buttons over Maps, the place buttons on the map tile.
2. Alerts: items 8 to 10, since a safety alert that stays silent or cries wolf
   is worse than none.
3. The default layout and the catalogue: items 1 to 4.
4. Save and restore, units and clock: items 15 and 16.
