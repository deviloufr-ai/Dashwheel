# Dashwheel

**The car launcher for Android head units.** Map, music and live car data on one calm screen: up to seven dashboards you arrange yourself, 30+ widgets, whole-design skins, an AI mechanic that explains warning lights, and your phone's calls and messages on the big screen. Free and open source (GPL v3).

![Dashwheel: map, speed, weather and music on one screen](docs/screenshots/01_hero.jpg)

**[Download the latest release](https://github.com/deviloufr-ai/Dashwheel/releases/latest)** · Android 10+ · made for 1280×720 head units (ROCO K706 / FYT / QF), also works on upright screens and phones.

Two builds of the same app: the **GitHub release** above (everything open, updates itself from GitHub Releases) and the **Google Play** build (updated by Play, with four skins and 13 extra widgets in a one-time *Dashwheel Pro* purchase; everything else is the same). The source is the same for both, under the GPL.

📖 **[Read the wiki](https://github.com/deviloufr-ai/Dashwheel/wiki)** for every feature, setup guides, what works with or without root, and tested hardware.

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
- **8 languages**, day and night themes, updates from GitHub Releases (or from Google Play)
- **Root is optional**: extra features appear only when the unit allows them ([what needs what](https://github.com/deviloufr-ai/Dashwheel/wiki/Root-and-PMPatch3))

## Get started

1. Install `Dashwheel-<version>.apk` on the head unit and set it as the Home app.
2. Optional: `dashwheel-companion.apk` on your phone, an ELM327 Bluetooth adapter, a free Gemini key.
3. Follow the first-run setup and the tour.

Details: [Installation and updates](https://github.com/deviloufr-ai/Dashwheel/wiki/Installation-and-Updates) · [Troubleshooting](https://github.com/deviloufr-ai/Dashwheel/wiki/Troubleshooting)

## Build from source

Android Studio with a Gradle JDK of 17–21, then `./gradlew assembleGithubDebug`. Toolchain, modules, CI and release signing: [Building from source](https://github.com/deviloufr-ai/Dashwheel/wiki/Building-from-Source).

The launcher has two product flavours, `github` (the default) and `play`; they differ only in `app/src/github` and `app/src/play` (`Edition.kt`, the Play billing client, the Play manifest) and in what `Premium.kt` keeps for the Pro purchase. The Play bundle is built by the *Google Play bundle* workflow (`.github/workflows/play.yml`, run by hand) and uploaded in the Play Console; the GitHub release by *Build Android APK*.

## License

Copyright (C) 2026 deviloufr-ai

Dashwheel (the launcher and the Companion app) is free software: you can redistribute it and/or modify it under the terms of the GNU General Public License as published by the Free Software Foundation, either version 3 of the License, or (at your option) any later version. It is distributed WITHOUT ANY WARRANTY; see the [LICENSE](LICENSE) file for the full text.
