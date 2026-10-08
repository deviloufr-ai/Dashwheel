# Dashwheel privacy policy

Last updated: 2026-10-08

This policy covers the Google Play editions of **Dashwheel** (the car launcher, package `com.dashwheel.app`) and **Dashwheel Companion** (the phone app, package `com.dashwheel.companion`). The GitHub editions behave the same, with one difference noted at the end.

## The short version

- The developer collects nothing. There is no account, no analytics, no advertising, no crash reporting, and no server of ours.
- Everything Dashwheel knows about you and your car stays on the head unit and the phone, under your control.
- Some features fetch data from public internet services. Those requests carry only what the service needs to answer, usually your position. They are listed below, with what leaves the device and to whom.
- The phone link between Dashwheel and the Companion runs on the phone's own hotspot, encrypted, and never through the internet.

## What the apps store on your devices

Dashwheel keeps its settings, your dashboards, the car profile you fill in, readings from your OBD adapter, drive and fuel logs, and the optional API key you paste in. The Companion keeps the pairing secret, the list of paired cars, and its own settings. All of it lives in the apps' private storage. Uninstalling an app removes it. Nothing is backed up to a cloud by the apps themselves: the Companion opts out of Android backup; Dashwheel's data stays on the head unit.

## Data that leaves the device, and to whom

Each item below is sent only when you use the feature, and only to the service named. We verified each endpoint in the source code.

| Feature | What is sent | To whom |
|---|---|---|
| Map and routes | The area you look at (tile requests), and the start and end of a route you ask for | CARTO basemaps (`basemaps.cartocdn.com`) for the map style and tiles; Valhalla at `valhalla1.openstreetmap.de` for routing |
| Place search and address lookup | The text you type, and your position for reverse geocoding | Nominatim (`nominatim.openstreetmap.org`) |
| Weather | Your position | Open-Meteo (`api.open-meteo.com`) |
| Weather alerts | Your country, found from your position through Nominatim | MeteoAlarm (`feeds.meteoalarm.org`) |
| Fuel prices (France) | The area around you | data.economie.gouv.fr (French open data) and Overpass (`overpass-api.de`) for station details |
| Speed limits and speed cameras | The area around you | Overpass (`overpass-api.de`), OpenStreetMap data |
| Car brand logo | The brand name of your car | GitHub raw content (`raw.githubusercontent.com`, the open car-logos dataset) |
| AI mechanic, car questions, Gemini Live and the "my car" photo features | Only when you add your own Google Gemini API key: fault codes, car readings, the text of your question, pictures of your car you choose to send, and, in Gemini Live, your voice while the panel is open | Google Gemini API (`generativelanguage.googleapis.com`). Google's terms and privacy policy apply to this data. A connectivity probe to `www.gstatic.com` precedes these calls; it carries nothing |
| Companion: "Open in Maps" | A destination, handed to the Google Maps app on your phone | Your phone's map app |

Dashwheel does not send your OBD readings, your drive log, your fuel log, your notifications or your contacts to any of these services, except where the table says so for the Gemini features you turn on yourself.

Voice input uses Android's speech recognition and text-to-speech services on the head unit. Those belong to the device and to their provider (usually Google), and their privacy terms apply.

## The phone link

When you pair the Companion with the car, the two apps talk over the phone's hotspot on a local TCP connection, encrypted with a secret created at pairing and shown as a QR code. Over that link the phone can share, each only after you turn it on in the Companion's setup checklist:

- notifications, so messages show on the car's screen and you can reply from there
- calls: who is calling, answering and hanging up from the car, and your favourites and recent calls for the Quick dial
- your next calendar events for the Agenda tile
- your phone's GPS position, for a head unit with a poor GPS
- an "On my way" text message, sent from your phone when you ask for it on the car's screen
- your phone's battery level, and parking reminders

None of this reaches the internet. It goes from your phone to your head unit and is shown there.

## Permissions and why

### Dashwheel (head unit)

- Location: the map, weather, fuel prices, speed limits, the drive log and parking spot. Used on the device and in the requests listed above.
- Bluetooth: your ELM327 OBD adapter, and the phone's connection state.
- Microphone: voice notes, dictation and Gemini Live, only while you use them.
- Camera: the "my car" photo builder, only when you take a picture.
- Calendar and contacts: the Agenda tile and Quick dial when the head unit itself holds them.
- Notification access: reads notifications on the head unit to show what is playing, the next turn from your navigation app, and messages. Nothing is stored beyond the current session or sent anywhere.
- Accessibility service: puts two apps side by side, types the text you dictate on the phone into the field selected on the car's screen, and reads the steering-wheel buttons you assign. It does not read your screen for anything else. You turn it on yourself in Android's accessibility settings; the app never turns it on for you.
- Draw over other apps: the app's own pop-ups for calls and car events.
- Notifications: its own alerts.

### Dashwheel Companion (phone)

- Notification access: forwards notifications to the car. Only when you turn it on.
- Phone, call log and answer calls: who is calling, recent calls, answering and ending calls from the car. Only when you turn calls on.
- Call phone: dials a favourite you tap on the car's screen.
- Contacts: names and photos for calls and the Quick dial.
- Calendar: the Agenda tile.
- SMS: the "On my way" text, written on the car and sent from your phone, only when you turn texts on.
- Location, including "all the time": shares the phone's GPS with the car during a drive; the sharing starts in the background when the car's Bluetooth connects. Only when you turn it on.
- Bluetooth: knows when the car connects, to start and stop sharing.
- Foreground service (connected device): keeps the link to the car alive while the screen is off.
- Battery optimisation exemption: so the link survives a long drive with the phone in a pocket.

Each of these is asked for when you turn the matching feature on, and the feature stays off without it.

## Children

Dashwheel is not directed at children and collects no data from anyone.

## Changes

The current policy is always at `docs/play/PRIVACY_POLICY.md` in the project's repository. The date at the top says when it last changed.

## The GitHub editions

The editions downloaded from GitHub Releases also check `api.github.com` for new versions and download them from GitHub. That request carries the app's name as its user agent and nothing about you. The Google Play editions do not do this: Play updates them.

## Contact

Alexandre Leblanc, devilangellus@gmail.com
