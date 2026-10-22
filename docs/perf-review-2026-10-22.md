# Performance review 2026-10-22

Whole-code review for lag, with no feature or visible change allowed. Five
reviewers read the code by area (Compose root and pager, skins, background
pollers, embedded apps and overlays, start-up and main thread); the build
config was read separately. Every figure below is an estimate from the code
path, or from the 2026-10-22 `top -H` on the K706 (Dashwheel 116 % parked:
RenderThread 31 %, MapLibre render 16 % + 4 workers ~8 % each, main 15 %,
PictureRelay 5 %). Nothing was measured after the changes: see "Measure" at
the end.

Potential = how much of the lag or idle CPU the item can remove, on the
hardware and layout where it applies. "Applied" items are in the working tree
of this review (not committed).

## Ranked list

| # | Potential | Item | Where | Status |
|---|-----------|------|-------|--------|
| 1 | ~20-35 % of a core parked | GPS wander kept the map's camera and label placement busy: the map now takes Dashwheel's own fixes and drops one under 3 m while the car stands still; the map's second GPS listener is gone | MapLibrePanel.kt `enableLocation` | Applied. Visible: the puck no longer drifts a metre or two while parked |
| 2 | RenderThread 31 % → ~5-10 % (parked, Canvas look) | Map wallpaper as a SurfaceView instead of a TextureView: each map frame would no longer be a full dashboard window frame | MapLibrePanel.kt `textureMode(true)` | Not applied: needs a flag-gated prototype on the K706 (the earlier SurfaceView tile punched a hole through the page). Biggest remaining win |
| 3 | A few ms of main thread per map/tile frame | The map and each tile's TextureView on a layer of their own, so a frame re-records only the view, not the page around it | MapLibrePanel.kt, EmbeddedApp.kt `AndroidView` | Applied |
| 4 | 5-20 % of a core + a main-thread hitch every 1.5 s, whenever Waze or idle Maps is in a tile | Tile OCR: no readback when the tile drew no new frame; the change test covers only the rows the readers use (Waze top quarter + bottom third, Maps top half), so the moving map in between no longer sends every frame to ML Kit | TileText.kt, EmbeddedApp.kt `tileFrames` | Applied, unit test added |
| 5 | 10 recompositions/s per speed tile → only on a speed change (3 pages, 27 skins) | Speed tiles followed the whole OBD sample (revs move every poll): `rememberSpeedKmh(env)` derives the speed only; Range, Warmup, Speed HUD cards and the bar's speed readout likewise | SkinKit.kt + 26 skin sites, Skins.kt, DashboardGrid.kt, BarItems.kt, DriveTiles.kt, TelemetryTiles.kt, CarCareTiles.kt | Applied |
| 6 | 2 of the 4 off-screen pages stay still during every swipe | `LocalPageActive` woke the pages on the other axis too (their tickers, ambient clocks, map frame caps) | AutomotiveDashboard.kt | Applied |
| 7 | A WindowManager relayout (~2-5 ms main) per bar recomposition, mid-swipe with auto-hide | `enableImmersiveFullscreen` set the FULLSCREEN flag it already had, which relays out the window every time | MainActivity.kt | Applied |
| 8 | 5 % CPU + one GPU blit per Maps frame when no Pi is linked | PictureRelay runs from the start whenever a cluster layout has a Maps copy, Pi linked or not | EmbeddedApp.kt `copyWanted` | Not applied: handing the display's picture back and forth is the known cause of black Maps tiles; needs the `refresh(nudge)` route and a car test |
| 9 | 1-2 % of a core continuously with an adapter | OBD `sendCommand` spins on `available()` + `Thread.sleep(5)` (100-200 wake-ups/s) | ObdBluetoothManager.kt:1114-1148 | Not applied: a reader thread + queue, same timing; the OBD link has bitten before, test in the car first |
| 10 | 0.3-1 ms of main thread 5 times a second while a Pi is linked | Cluster snapshot built on the main thread; the date line formatted 5x/s | ClusterFeed.kt | Applied: `Dispatchers.Default`, date once a minute |
| 11 | ~12 draw ops + 3 full-screen fills per step (20 fps) on the Horizon look | The still sky, sun, stars, moon, hills, horizon line and road redrawn every ambient step: now recorded into three layers in the same order, as the Racing look does | HorizonSkin.kt `sceneModifier` | Applied |
| 12 | Up to 253 SYNs + ~500 binder calls every 15 s with a Pi paired but off | Second-screen subnet sweep asked the networks again for every address | DisplayLink.kt `probe` | Applied (networks once per sweep). The 15 s cadence kept: slowing it would delay finding a silent Pi |
| 13 | 10 recompositions/s on every upkeep tile while driving | `Maintenance.drove` republished the odometer at every reading: now every 100 m (the tiles show whole km) | Maintenance.kt | Applied |
| 14 | 10 main-thread messages/s for identical results | Bar alerts re-evaluated at every OBD sample though only coolant, battery and link matter | VehicleMonitor.kt | Applied (`distinctUntilChanged`) |
| 15 | 1-3 % while charging or dialling; whole bar re-recorded 20x/s | Charging battery mark and the OBD dot pulse drew into the bar's layer; the OBD halo brush built per draw | TopBar.kt | Applied (own layers, cached brush) |
| 16 | Needle swing re-recorded the numerals beside it, 60x/s while moving | Standard analog gauge and the Orbit dial draw on a layer of their own; the gauge's tip glow brush built once | TelemetryTiles.kt, OrbitSkin.kt | Applied |
| 17 | Offscreen buffer per step (20 fps) on the Racing look | Fading layers over a single primitive: `ModulateAlpha` (underglow, banner shadow) | RacingSkin.kt | Applied |
| 18 | Glowing numerals re-rasterised at every OBD sample | Per-poll bars beside blurred text on their own layers (Panorama segments, Futuristic bar); Modern Orb/Liquid faces on their own layers, constant brushes built once; Kinetic sweep paint built once | PanoramaSkinTiles.kt, FuturisticSkinTiles.kt, WidgetFacesModern.kt, KineticSkinCar.kt | Applied |
| 19 | 30-100 ms of cold start (eMMC) | ~35 preferences files were loaded one after the other on the main thread before the first frame: all started at once at process start | UnitReport.kt `DashApp` | Applied |
| 20 | One 100-300 ms frame → two shorter ones at start | The four neighbour pages composed in one frame: row first, column two frames later | AutomotiveDashboard.kt | Applied |
| 21 | 200-800 ms stall per open | App list (labels + icons of every app) loaded on the main thread in two pickers | KeyTargets.kt, SteeringWheelDialog.kt | Applied (IO) |
| 22 | 20-150 ms per open | Camera list (camera service round trips) on the main thread | SignalFinderSheet.kt | Applied (IO) |
| 23 | Up to 25 ms per 3 s tick while the Head unit tile shows | App icons re-rasterised when the top-5 reorder | MonitorTiles.kt | Applied (keyed rows, icon cache) |
| 24 | ~120 shell listings per ignition-on | `watchFront` listed the stacks twice a second for 60 s even with the dashboard focused | EmbeddedApp.kt | Applied (skipped while focused) |
| 25 | 1 snapshot-map write per page per swipe frame | Tour target bounds written to Compose state on every placement: plain map, published only while a tour shows | TourScreen.kt | Applied |
| 26 | ~4 binder/reflection calls per second parked | Speed volume read the unit's audio state every second with nothing to do; `SysProps.get` reflected on every call | SpeedVolume.kt, SysProps.kt | Applied |
| 27 | A Magisk `su` spawn 4x/min while Waze guides with the Pi | Waze file poll through the kept shell session | WazeDestination.kt | Applied |
| 28 | `top` over /proc twice per run, 3-6 % while the tile shows | Thermal zones read through the shell though they read directly; `runBlocking` inside a coroutine | HeadUnitMonitor.kt | Applied |
| 29 | ~3 wake-ups/s idle | Idle tickers: CAN monitor list joined for nobody, MCU tail 2 s loop, phone GPS 1 s loop, My readings 1 s + 2 s loops with nothing to read | McuReader.kt, PhoneGps.kt, MyReadings.kt | Applied |
| 30 | 0.3 % of a core, 2 threads | ShadowRoute matched the car to the whole route on the main thread per fix | ShadowRoute.kt | Applied (`Default`) |
| 31 | 100-500 ms at boot (Reglink/MTK units only) | DuraSpeed settings written with `runBlocking` on the receiver's main thread | BootReceiver.kt | Applied (`goAsync` + IO) |
| 32 | 1 allocation + 1 redraw per accent button recomposition | `DashColors.AccentBrush` made a new brush at every read | DashTheme.kt | Applied (cached per palette) |
| 33 | Root recomposition 1/swipe + 1/min (1-3 ms, mid-fling) | `currentPage` and the clock read in the root body; `DashBarModel` rebuilt each time | AutomotiveDashboard.kt, TopBar.kt, CanvasTabs.kt | Not applied: a model refactor (page and clock as `State` in TopBarModel/DashBarModel) |
| 34 | 0.3-1 % at Full effects | One coroutine + one state per ticker caller (10-20 per skin page): a process-wide ticker per step | SkinKit.kt | Not applied: shared-ticker refactor |
| 35 | 0.3-0.8 % per docked window | PipAnchor polls `am stack list` per window tile every 2.5-5 s forever | PipAnchor.kt | Not applied: shared listing ticker; only window/dock tiles pay |
| 36 | 1 recomposition/s on care tiles parked without OBD | `CarCare.restTick` publishes a state whose only change is `lastAt` | CarCare.kt | Not applied: `lastAt` feeds the saved state in both paths; low value |
| 37 | Up to 10 % of main while reversing with the Pi | Reverse camera mirror: 960x540 `getBitmap` 12.5x/s on the main thread | ReverseCamera.kt | Not applied: a second capture target needs the HAL to accept two 1080p outputs; car test |
| 38 | Binder + parcel per content-change batch, whole trip | Accessibility content watch on while Waze guides in a tile (invisible to a11y) | SplitAccessibilityService.kt | Not applied: file has another session's uncommitted edits. One-line change: `watchContent((wazeRuns && !EmbeddedApp.holds(WazeScreen.PACKAGE)) || mapsInFront)` |
| 39 | Small, bursty | `isInSplitMode()` fetches the a11y window list on every window event | SplitAccessibilityService.kt | Not applied (same file): only when in multi-window or the overlay shows |
| 40 | Size only, not lag | R8 keeps all of MapLibre and Filament by wildcard; optimised resource shrinking off; the baseline profile is still the hand-written wildcard (which makes `speed-profile` compile everything, fine for lag) | proguard-rules.pro, gradle.properties, baseline-prof.txt | Not applied |

## Checked and already fine

Start-up is staged (one feature start per main-thread message after the first
pre-draw); tickers sleep on `withFrameMillis` and hold on hidden pages;
`cachedDraw`/`glowHalo` cache brushes; needles turn layers; grid tiles have
their own layers; overlays are removed after their exit animation; OBD polling
is on `Default` with backoff; CAN frames publish on change only; PictureRelay
stays on the GPU; DriveLog/ParkingMemory/FuelLog/LpgTank save on thresholds;
no `Modifier.blur` anywhere; DockShell keeps one `su` session with a 500 ms
listing cache.

## Measure

On the unit (192.168.1.85:9876), parked, same layout as 2026-10-22:

```
top -b -d 4 -n 2 -H -p $(pidof com.openauto.dash)
dumpsys gfxinfo com.openauto.dash
```

Compare RenderThread, TextureViewRend/Worker, main and PictureRelay with the
figures above. Then watch: the puck while parked (item 1), the Waze tile's
next turn still updating (item 4), the upkeep odometer (item 13).
