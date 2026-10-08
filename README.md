# Dashwheel

**The free, open-source car launcher for Android head units (FYT / UIS7862 and similar).** Map, music and live car data on one calm screen: up to seven dashboards you arrange yourself, 30+ widgets, whole-design skins, an AI mechanic that explains warning lights, and your phone's calls and messages on the big screen. No ads, no trial, no account.

![Dashwheel: map, speed, weather and music on one screen](docs/screenshots/01_hero.jpg)

**[Download the latest release](https://github.com/deviloufr-ai/Dashwheel/releases/latest)** · Android 10+ · made for 1280×720 head units (ROCO K706 / FYT / QF), also works on upright screens and phones.

📖 **[Read the wiki](https://github.com/deviloufr-ai/Dashwheel/wiki)** for every feature, setup guides and what works with or without root.

**Will it work on my unit?** See [Tested hardware](https://github.com/deviloufr-ai/Dashwheel/wiki/Tested-Hardware). Root is not needed.

[![Support me on Ko-fi](https://ko-fi.com/img/githubbutton_sm.svg)](https://ko-fi.com/deviloufr)

Dashwheel is free, with no ads. If it makes your drives nicer, a coffee on Ko-fi keeps it going.

## Screenshots

| | |
|---|---|
| ![Live car data](docs/screenshots/02_live_car_data.jpg) | ![AI mechanic](docs/screenshots/03_ai_mechanic.jpg) |
| **Live car data** from a Bluetooth OBD adapter and the head unit's CAN box | **AI mechanic**: fault codes explained in plain words |
| ![Road trip](docs/screenshots/04_road_trip.jpg) | ![Widget designs](docs/screenshots/05_widget_designs.jpg) |
| **Road trip**: next turn, break reminder, eco score, fuel prices nearby | **60+ widget designs**, one per tile |
| ![Skins](docs/screenshots/06_skins.jpg) | ![Arranged your way](docs/screenshots/07_customise.jpg) |
| **Whole-design skins**: Orbit, Cockpit, Horizon, Tape Deck | **Your dashboards**, live widget previews, templates |
| ![Calls](docs/screenshots/13_calls.jpg) | ![Upright screens](docs/screenshots/14_upright.jpg) |
| **Calls** from the paired phone, answered from the screen | **Upright screens** get their own layout |

## Highlights

- **Dashboards your way**: free grid, drag to move and resize, templates, Canvas theme with the map behind the tiles
- **Live car data**: ELM327 Bluetooth OBD and the head unit's own car data (doors, fuel, tyres, reversing)
- **AI mechanic** (free Google Gemini key): fault codes explained and spoken, start-up briefing, car care and servicing reminders
- **Navigation**: free built-in 3D map with turn-by-turn guidance, or Google Maps / Waze next turn on any tile
- **Phone link** (Dashwheel Companion app): notifications, messages, calls, where's my car, drive log
- **Your own alerts and reverse view** instead of the head unit's pop-ups
- **Hands-free**: spoken alerts, steering-wheel buttons, volume that follows speed
- **Second screen** (experimental): a Raspberry Pi drives a second monitor in the car
- **8 languages**, day and night themes, updates from GitHub Releases
- **Root is optional**: extra features appear only when the unit allows them ([what needs what](https://github.com/deviloufr-ai/Dashwheel/wiki/Root-and-PMPatch3))

## Two editions

- **GitHub edition** (`Dashwheel-<version>.apk` from [Releases](https://github.com/deviloufr-ai/Dashwheel/releases/latest)): the full app. Root, internal ADB and PMPatch3 features appear when the unit allows them, and it updates itself from GitHub Releases.
- **Google Play edition**: the same app without the features that need root, the unit's internal ADB or PMPatch3. It never installs anything itself: updates come from Google Play, and the Companion comes from Play too. Store texts and the privacy policy are in `fastlane/metadata-play/` and `docs/play/`.

| | GitHub | Google Play |
|---|---|---|
| Dashboards, widgets, skins, OBD, map, AI mechanic, phone link | yes | yes |
| Self-update from GitHub, Ko-fi link | yes | no (Play updates it) |
| System-app install, boot logo, Google Maps inside a tile (PMPatch3), reverse camera takeover, firmware pop-up and volume bar replacement, CAN car data from the unit's MCU, Signal Finder | when the unit allows it | no |
| Accessibility service (split screen, typing from the phone, wheel buttons) | turned on by the app where there is a root shell | turned on by you in Android's settings, after an in-app explanation |

## Get started

1. Install `Dashwheel-<version>.apk` on the head unit and set it as the Home app.
2. Optional: `dashwheel-companion.apk` on your phone, an ELM327 Bluetooth adapter, a free Gemini key.
3. Follow the first-run setup and the tour.

Details: [Installation and updates](https://github.com/deviloufr-ai/Dashwheel/wiki/Installation-and-Updates) · [Troubleshooting](https://github.com/deviloufr-ai/Dashwheel/wiki/Troubleshooting)

## Feedback and support

- Something broken, or a unit that isn't listed? [Open an issue](https://github.com/deviloufr-ai/Dashwheel/issues) and say which head unit you have.
- If Dashwheel is useful to you, a ⭐ on this repo helps other drivers find it.
- [Ko-fi](https://ko-fi.com/deviloufr) pays for test hardware.

## Branded builds and commercial licences

Installers and resellers: custom or branded builds, and licences for closed distribution, are available. [Get in touch](https://deviloufr-ai.github.io/alexandreleblanc.github.io/).

## Build from source

Android Studio with a Gradle JDK of 17–21, then `./gradlew assembleDebug`. Toolchain, modules, CI and release signing: [Building from source](https://github.com/deviloufr-ai/Dashwheel/wiki/Building-from-Source).

## License

Copyright (C) 2026 Alexandre Leblanc (deviloufr-ai)

Dashwheel (the launcher and the Companion app) is free software: you can redistribute it and/or modify it under the terms of the GNU General Public License as published by the Free Software Foundation, either version 3 of the License, or (at your option) any later version. It is distributed WITHOUT ANY WARRANTY; see the [LICENSE](LICENSE) file for the full text.

The Dashwheel name and logo are not covered by the GPL: forks must use a different name and logo.
