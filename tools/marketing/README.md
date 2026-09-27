# Marketing screenshots

Regenerates the slides in [`docs/screenshots/`](../../docs/screenshots/) from the running app in demo mode.

## 1. Capture build

Apply `capture-build.patch` in a throwaway worktree (never on `main`) and copy `mkt_map_dark.jpg` / `mkt_map_light.jpg` into
`app/src/main/res/drawable-nodpi/`. The patch:

- draws a still of the map (rendered by `map.html` with MapLibre GL JS and the same CARTO styles as the app) where the emulator
  would show "Map needs a real GPU", with the app's own next-turn banner on top;
- hides the "Demo" badge;
- starts demo mode on launch when the intent carries `--ez mkt_demo true`.

## 2. Emulator

An API 29 x86_64 AVD at 1280x720, 160 dpi (the head unit). Before capturing:

```bash
adb -s emulator-5580 shell settings put secure immersive_mode_confirmations confirmed
adb -s emulator-5580 root && adb -s emulator-5580 shell "date 092719052026.00"
adb -s emulator-5580 shell appops set com.openauto.dash SYSTEM_ALERT_WINDOW allow
```

## 3. Captures

```bash
./capture.sh hero_AUTO_DARK AUTO DARK layouts/hero.json
./capture.sh hero_AUTO_LIGHT AUTO LIGHT layouts/hero.json
./capture.sh car_AUTO_DARK AUTO DARK layouts/car.json
./capture.sh ai_AUTO_DARK AUTO DARK layouts/ai.json
./capture.sh trip_AUTO_DARK AUTO DARK layouts/trip.json
./capture.sh designs_AUTO_DARK AUTO DARK layouts/designs.json
./capture.sh skin_ORBIT_DARK ORBIT DARK layouts/skin.json      # likewise COCKPIT, HORIZON, TAPE_DECK
```

Taken by hand in `raw/`: `ui_add` (⋮ → Edit dashboards → Add), `ui_templates` (→ Templates), `ui_alert_try`
(Settings → Alerts → Calls → Side panel → Try it; `call_overlay.png` = `hero_AUTO_DARK` with its right 460 px),
`vert_default` / `vert_orbit` (`wm size 768x1024`, then `capture.sh … -`).

## 4. Slides

```bash
python build.py
for f in out/html/*.html; do node shot.mjs "file:///$PWD/$f" "out/$(basename "$f" .html).png" 1920 1080; done
```

`shot.mjs` drives the installed Chrome over the DevTools protocol (Node 22+). Convert the PNGs to JPEG (quality 90) into
`docs/screenshots/`. `raw/` and `out/` are not committed.
