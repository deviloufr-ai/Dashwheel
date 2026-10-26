# Road watch

Experimental, and marked so in both apps. The phone, in a holder behind the windscreen, watches the road with its camera; the head unit warns the driver. The road recorder (below, also experimental) keeps clips of the road on the phone.

## Who does what

| Part | Where | What it does |
|---|---|---|
| `LaneFinder`, `LaneTracker` | `link/.../roadwatch/` | The car's lane in each frame: bright stripes on the road, voted into straight lines (Hough), followed from frame to frame. Learns where the car usually drives in its lane, so a phone off the middle of the windscreen still reads 0. |
| `SeenRoad`, `SeenLanes`, `LaneCount` | `link/.../roadwatch/` | The lane model's two masks (lane lines, drivable road) laid over the lane picture; the car's lane lines followed on them; how many lanes there are and which the car is in. Replay only for now. Below. |
| `LeadTracker` | `link/.../roadwatch/` | The vehicle ahead in the lane, from the detector's boxes. Distance from its apparent width (rough); time to reach it from how fast it grows (does not depend on the guessed width). |
| `RoadRules` | `link/.../roadwatch/` | When to warn: collision, lane, following too close, traffic moving off. |
| `RoadScene` | `link/.../link/` | Phone to head unit, about ten times a second while Road watch runs. |
| `RoadWatch`, `RoadDetector`, `RoadWatchActivity` | `companion/` | The camera (CameraX), the vehicle detector (LiteRT), the screen over the camera's picture. |
| `RoadWatch`, `RoadAlertOverlay` | `app/` | Applies `RoadRules` with the car's own speed and indicators, shows `AlertKind.ROAD`, plays the tones. |

The shared part is plain Kotlin, tested on the JVM with drawn roads (`link/src/test/.../roadwatch/`), and replayed over recorded drives with `tools/roadwatch-replay` (its README): every change to the lane or vehicle logic is judged there, on real clips, by numbers.

## The vehicle detector

EfficientDet-Lite2 (COCO, Apache 2.0) from TensorFlow's model storage, 7.6 MB, with the detection post-processing built in: input 448 x 448 RGB uint8; outputs boxes `[1,25,4]` (ymin, xmin, ymax, xmax), classes `[1,25]` (0-based COCO: 2 car, 3 motorcycle, 5 bus, 7 truck, 1 bicycle), scores `[1,25]`, count `[1]`. Fetched once on the driver's request and checked against its SHA-256 (`RoadModel`); a phone that had Lite0 fetches it by itself. It runs on the graphics chip where LiteRT's GPU delegate takes it, on four processor threads otherwise.

It looks at the road in turns. On one frame it takes a square as wide as the 4:3 picture is tall, so the picture isn't squashed. On the next frame it takes the middle half of that square around the horizon, at twice the detail, for far vehicles. Boxes cut off by that look's edge are dropped: those are near vehicles, which the square sees whole. Each frame's vehicles are its own plus those from the frame before (`withBefore`).

### How it was chosen (2026-10-26)

Measured against a big model run on the PC (YOLOPv2, trained on BDD100K dashcam drives; `tools/roadwatch-replay/eval/`), on the Jakarta drive and three of the user's village drives. The score is the share of the reference's vehicles found, for the nearest one straight ahead:

| Detector | 15-30 m | 30-50 m | 50-80 m | Boxes invented / 100 frames |
|---|---|---|---|---|
| Lite0, the square only (before) | 78 % | 22 % | 0 % | 3.9 |
| Lite0 + the horizon look | 96 % | 84 % | 21 % | 6.6 |
| **Lite2 + the horizon look** | **99 %** | **93 %** | **60 %** | **8.2** |
| YOLOX-tiny + the horizon look | 98 % | 94 % | 86 % | 16.9 |

On the village drives alone, Lite2 with the horizon look found the car ahead 100 / 96 / 52 % of the time at those distances, against 51 / 9 / 0 % before. YOLOX-tiny (Apache 2.0) sees furthest. It also invents twice as many boxes, is about four times heavier, and would need converting and hosting. A threshold of 0.3 instead of 0.4 found more and invented about three times as much. Requiring a box on 3 frames running halved what was invented but lost range, and the collision warning already waits for several frames. The ultra-wide lens (zoom 0.6) was tried too, and it made vehicles too small for these models (draft PR #82).

The replay's `detect.py` crops as the phone does: the square is 0.75 of a sideways 16:9 recording's width and reaches above and below it. Before 2026-10-26 it took a square as tall as the recording, which made vehicles look a third bigger than the phone sees them.

## A car beside the path, not in it

A car parked at the edge of a street without lines, or coming the other way in its own half, sits inside the guessed lane. As the car closes in, `LeadTracker` measures how far its sides slide across the picture for each bit it grows taller. That gives each side's distance aside in metres, whatever the camera's aim. The height is used, not the width: a car beside the path shows its flank, which widens its box towards the path. The inner side of the box is the far front corner, so the side read is the outer one (the back corner), and the inner side is one car's width in from it. A car wholly more than 1.1 m from the middle of the path (half the car's width and 20 cm) is driven past: it gets no time to impact.

On the 13 collision clips of 2026-10-24/26 (all false alarms), this cut the alarms from 23 to 15. Measured by the width, the remaining parked cars had read 1 to 1.5 m aside. On Jakarta, a closing car ahead kept its time to impact 96 % of the time (94 % before). Taking the car's turn out with the gyroscope (draft PR #85) made no difference on these clips.

## Speed-limit signs

Every third frame, and on every frame for 1.5 s once one is seen, `SignFinder` (link, plain image maths) looks over the picture above the bonnet for round red signs. It samples every other pixel and marks the strongly red, well coloured ones. It groups those that touch, and keeps the groups that are round, ring-shaped (red over 20 to 65 % of their square) and light in the middle. That last test is what rules out brick walls, red cars and tail lights. The biggest sign at least 16 pixels across goes to `SignReader`. It copies the sign's face inside the ring, enlarges it to 160 pixels, and reads it with ML Kit's text recognizer on the phone, with no network. Only a speed limit counts (10 to 130 km/h), and `SignConfirm` believes it once it has been read twice within 3 s.

The limit goes to the car in `RoadScene.signKmh` for 2 s after it is read. The car's `SpeedLimit` holds it over OpenStreetMap's limit for the next 2 km, or until the next sign. The phone shows it in a corner of Road watch, and the recorder notes it (`signKmh`).

Road watch analyses the picture at 1280 x 960 since 2026-10-26, against 640 x 480 before. At 640, a 30 or a 90 was only readable in the last moments before the car passed it.

On the user's drives (`roadreplay --signs dir` dumps what the finder finds), it found the 30 and 90 signs, the round signs without a number, the give-way triangles and a village name sign, with no brick walls. The reading itself only runs on the phone. Each face it tried to read is kept with what it read (the last 120, `GET /signs`), and `GET /roadwatch` gives the time each part of a frame takes. On the first drive (2026-10-26), a sharp 90 passed at 80 km/h without being read: looking every third frame at 10 fps gave it one or two tries.

## The lane model

The idea is [YOLOPv2](https://github.com/CAIC-AD/YOLOPv2)'s (arXiv 2208.11434, MIT): one network that gives the vehicle boxes, the drivable area and the lane lines of a frame, trained on BDD100K's 100,000 drives. Its released model, taken apart on 2026-10-24: 38.95 M parameters; a YOLOv7 backbone (E-ELAN blocks, SPPCSPC) and FPN/PAN neck feeding three heads; the vehicle head (YOLOv7's `IDetect`, 3 anchors at strides 8/16/32) on the PAN's three outputs, the drivable-area head (upsampling, 2-class softmax) on the FPN at 1/16, the lane head (SE attention, transposed convolutions, one sigmoid channel) on the FPN at 1/8. Input 640 x 384; 72 GFLOPs a frame (18 at 320 x 192), 274 ms on four desktop cores. Too heavy for a phone that is also filming, and the vehicles are already found by EfficientDet-Lite0.

So Road watch takes the same two masks from a network built for them, small enough for a phone: TwinLiteNet (Che et al. 2023, MIT, commit `524141821c37`), trained for YOLOPv2's drivable-area and lane-line tasks on the same BDD100K drives, 0.44 M parameters. At 320 x 176 it costs 1.9 GFLOPs, 14 ms on four desktop cores. It is `tools/roadwatch-replay/road_lanes.tflite` (0.9 MB, its licence beside it), built by `tools/roadwatch-replay/lanes_model.py`, which refuses a conversion that disagrees with PyTorch (onnx2tf once mis-ordered the attention block's tensors). Input `[1,176,320,3]` float RGB 0..1, a 16:9 band of the upright frame as wide as the frame and set on the road; output `[1,2,176,320]`, the chance each pixel is road (plane 0) and lane line (plane 1).

Not on the phone yet: it runs in the replay (`lanes.py`, then `--lanes`), and goes on the phone once it beats the brightness finder there. `SeenRoad.fromModel` lays the two planes over the 160-wide lane picture, each pixel the most likely of the model's under it.

`SeenLanes` finds the car's lane on them. Each side's line is followed rather than voted for: from the line pixel nearest the car low in the picture, row by row up the road, each row taking the line pixels nearest where the line was heading (a few rows' gap allowed for a dash), then a curve (x = a + b s + c s²) through the followed points in the near part of the road: where it meets the bonnet's row is the line's place, and the lane line runs on from there the way the curve heads. A side with no line follows the road's edge the same way (a country lane has no markings). Vehicle boxes hide what is inside them. LaneFinder's straight lines voted up to the horizon did badly on the model's lines, which bend with the road and meet below the horizon over a crest.

`LaneCount` says how many lanes the road has and which the car is in (1 the leftmost). In a few rows near the car: out from the car to the road's edges (vehicles count as road), the lane lines between, and each gap as many lanes as the car's own lane (`LaneTracker.laneWidth`) fits in it, so a worn or hidden line still counts; the answer most rows agree on, held steady over 1.5 s by `LanePlaceTracker`. The replay shows it as "lane 2 of 3" and counts it in its summary. The car's place in its lane is the lane offset, as before.

On YOLOPv2's own sample frame (a New York expressway, concrete, dashed lines), the brightness finder found the right line only (21 % of the rows); the model, both, backed by 62 % and 55 % of the rows.

### On a recorded drive

5,000 frames, 2 min 52 s of elevated ramps and an urban motorway in Jakarta, `--speed 90`. The horizon matters more than anything else: the drive had been replayed with `--horizon 0.5`, but where the lane's two lines meet, over 2,203 frames, is at 0.59 of the height (quartiles 0.57 and 0.61).

| | Brightness, horizon 0.5 | Brightness, 0.59 set by hand | Brightness, learning from 0.5 | Model, learning from 0.5 |
|---|---|---|---|---|
| Both lines kept | 64 % | 91 % | 80 % | 84 % |
| False lane warnings | 4 (3 in the toll plaza, a curb at 17.9 s) | 1 (a curb at 20.8 s) | 4 (3 in the toll plaza, a curb) | 1 (a curb at 17.3 s) |
| The lane change right at 139.5 s, and back | caught | caught | caught | caught, once each |

What it took for the model: each line followed rather than voted for (above); the follow started from the line point nearest the car anywhere in the lower half of the road, not the first row that has one, or a dash's gap at the bonnet handed the side to the next lane's line and one lane change warned three times; and the road's edge taken for a side only within 1.4 lanes, not past a shoulder. Not yet: a junction's curb taken for the lane's left line (17.3 s, as the brightness finder).

The lane count: "1 of 1" on the single-lane ramps (most of the drive, right), "2 of 3" on the motorway at 170 s (right), "1 of 1" in the middle of three lanes at 150 s (wrong: the model's road mask covers the whole width there, so the road's edges are off the picture); unsure 15 % of the time.

## The horizon, learned

`HorizonLearner` (link): a lane's two lines run to a point on the horizon, so while the car drives on steadily (50 km/h and up), where the best line on each side meet (`LaneSighting.meetY`, taken before the lines are checked against the horizon: one set too high makes the real lines cross under it) goes into a histogram that fades over two minutes, and the horizon is its median. A crest puts the point low and a dip high, sometimes for a minute of ramps, which an average follows and a median over two minutes does not. The driver's drag sets where it starts, worth 3 s of driving; once 20 s of driving went in, the hint to drag the line goes, and the learned horizon is kept for the next drive. The phone's dotted line follows it.

On the drive above, from 0.4, 0.5 and 0.7: the brightness finder ends at 0.60, 0.60 and 0.62, but from too high slowly (2 minutes or more: with the horizon too high it finds fewer pairs); the model, from 0.5, is at 0.59 within 40 s. A wrong start costs one drive; the next starts where the last one ended.

`LaneTracker` learns where the car drives in its lane only while the car is within 0.3 of a lane of the middle: learning while it straddles a line drew its place along with the line, which then never passed under the car (the model run's car drifted from 0.50 to 0.31 of the width after one lane change, and took the left line for the right one).

## Warning limits

| Warning | When |
|---|---|
| Collision | Closing at 2 m/s or more, and 2.4 s or less to impact from 30 km/h (1.5 s from 8 km/h), two frames running. Closing faster than the car drives is ignored as a jumpy box. A box reaching below the bonnet or filling three quarters of the height is not a vehicle ahead (a finger over the lens while the phone is moved set one off). |
| Lane | From 60 km/h, a wheel on the line (offset 0.22 of the lane) and still drifting out, no indicator in the last 2 s. Once per side until the car is back in the middle. A line that would make the lane 1.5 times as wide as the one learned (a junction's far edge) is left out unless the lane stays that wide for 3 s. |
| Too close | From 50 km/h, under 0.8 s behind for 3 s. At most every 30 s. |
| Traffic moving | Stopped 3 s or more behind a vehicle within 12 m, which then moves 4 m away. Once per stop. |

## Phone sideways or upright

Either works, and turning the phone in its holder keeps the camera running. Upright, the frame's width spans the camera's narrower side (about 51° against 66°), so a marking, a lane and the corridor ahead all take a bigger share of the width: the shares in `LaneFinder` and `LeadTracker` are set for 66° and scaled by `widthShare` from the camera's real view, which `RoadWatchActivity` reads off the sensor's size and focal length for both sides. The detector looks at a square set on the road (around the horizon and the bonnet) rather than the middle of a tall frame. The car's learned place in its lane is kept separately for each orientation.

## The road recorder

Off until the driver turns it on (Companion, Car tab, "Record the road"). While Road watch runs, `RoadRecorder` records the road beside it: 1080p at 6 Mbit/s, no sound, in 20-second segments in the app's cache, of which the last two minutes stay (`ClipRing`). A moment worth keeping locks the 30 seconds before and after it as a clip: the segments are joined into one MP4 without decoding (`MediaExtractor` → `MediaMuxer`), with `clip.json` beside it holding the moment, why, and twice a second the time, the GPS position and speed, and what the car said of itself through the new `CarTelemetry` link message (its own speed, rpm, indicators, hazards, lights). The same data is drawn into the picture by a CameraX `OverlayEffect` on the video, so it survives any player and any re-encoding. Clips live in `files/road_clips/<time>-<trigger>/`, never backed up, at most 25, shared from the Clips card.

| Trigger | From |
|---|---|
| `impact` | The phone's linear acceleration over 2.2 g (`ImpactDetector`) |
| `brake` | The GPS speed falling faster than 6 m/s² from above 25 km/h |
| `collision` | Road watch's own collision warning on the phone |
| `hazard` | The head unit, when the CAN box reports the hazard lights going on (`KeepClip`) |
| `button` | "Keep clip" on the Road watch screen |

Two triggers within 10 s are one clip. Recording pauses when Android reports the phone severe-hot (`PowerManager` thermal status) or under 400 MB free, and resumes by itself. On a phone whose camera can't run the preview, Road watch's frames and the video together, the road is watched without being recorded, and the screen says so.

Law: dashcams are allowed in most of Europe, restricted in Austria and Portugal among others; the card says so, and the clips stay on the phone.

## Later: the car's own cameras

Not built; an idea kept here so it isn't lost (also a GitHub issue).

On the QF units Dashwheel already opens the unit's cameras through Camera2 (`ReverseCamera.kt`): camera `1` is the reversing camera, camera `0` the second video input, where a front or kerb camera sits on units that have one. They are analog inputs through the unit's video chip, about 720 x 480, wide fisheye, the rear one usually mirrored; the firmware's camera app holds them, so taking one needs root, as the reverse view does. Two uses, in the order worth building:

1. **The reversing camera as a rear recorder.** Rear-end collisions are the most common claim, and the phone behind the windscreen never sees them. With the reversing camera on constant power (most installs power it from the reverse-light circuit, so this is a wiring question first), the launcher could run the same rolling segments and locked clips as `RoadRecorder`, on the unit's storage, with the car's speed and indicators burned in, locked by the same triggers (the hazards, a hard brake, the phone's impact detector over the link); and a "car behind closing fast" warning, the detector seeing a car from the front as well as from behind.
2. **A front camera on input 0 as Road watch without the phone.** `LaneFinder`, `LeadTracker` and `RoadRules` are shared Kotlin in `link`, so the launcher could run them on the unit's own frames, the vehicle detector on the unit's CPU (a UIS7862 should manage 4 to 6 frames a second). Needs a one-time calibration of the fisheye's view ("the car ahead is about 10 m away"), and gives no impact detection, the unit having no accelerometer.

A third route, the USB "DVR" cameras FYT and QF units support (plain UVC webcams), gives a cleaner picture but needs a UVC library and USB host permission.

First step either way: a probe under Settings that opens camera 0 and 1 outside reversing and reports whether frames arrive, at what size, and whether the rear one stays powered.

## What to try on a real drive

- The horizon line set right (drag it on the phone's screen): the lane is searched below it.
- The phone upright as well as sideways: the lane found, the car ahead boxed, and the buttons reachable.
- Lanes at night, in rain, on worn markings; false lane warnings on a slip road or at roadworks.
- Collision warnings in stop-and-go traffic (false alarms) and approaching a queue at speed (late or missing).
- The phone's temperature after an hour in the sun, with and without the recorder.
- The recorder: a clip kept by hand, then one from a firm brake; the joined MP4 plays in the phone's gallery and on a PC; the burned-in time and speed read right, upright, whichever way the phone was held.
