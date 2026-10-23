# Road watch

Experimental, and marked so in both apps. The phone, in a holder behind the windscreen, watches the road with its camera; the head unit warns the driver. The road recorder (below, also experimental) keeps clips of the road on the phone.

## Who does what

| Part | Where | What it does |
|---|---|---|
| `LaneFinder`, `LaneTracker` | `link/.../roadwatch/` | The car's lane in each frame: bright stripes on the road, voted into straight lines (Hough), followed from frame to frame. Learns where the car usually drives in its lane, so a phone off the middle of the windscreen still reads 0. |
| `LeadTracker` | `link/.../roadwatch/` | The vehicle ahead in the lane, from the detector's boxes. Distance from its apparent width (rough); time to reach it from how fast it grows (does not depend on the guessed width). |
| `RoadRules` | `link/.../roadwatch/` | When to warn: collision, lane, following too close, traffic moving off. |
| `RoadScene` | `link/.../link/` | Phone to head unit, about ten times a second while Road watch runs. |
| `RoadWatch`, `RoadDetector`, `RoadWatchActivity` | `companion/` | The camera (CameraX), the vehicle detector (LiteRT), the screen over the camera's picture. |
| `RoadWatch`, `RoadAlertOverlay` | `app/` | Applies `RoadRules` with the car's own speed and indicators, shows `AlertKind.ROAD`, plays the tones. |

The shared part is plain Kotlin, tested on the JVM with drawn roads (`link/src/test/.../roadwatch/`).

## The vehicle detector

EfficientDet-Lite0 (COCO, Apache 2.0) from TensorFlow's model storage, 4.6 MB, with the detection post-processing built in: input 320 x 320 RGB uint8; outputs boxes `[1,25,4]` (ymin, xmin, ymax, xmax), classes `[1,25]` (0-based COCO: 2 car, 3 motorcycle, 5 bus, 7 truck, 1 bicycle), scores `[1,25]`, count `[1]`. Fetched once on the driver's request and checked against its SHA-256 (`RoadModel`). It looks at a square from the middle of the frame, so a 4:3 frame isn't squashed.

## Warning limits

| Warning | When |
|---|---|
| Collision | Closing at 2 m/s or more, and 2.4 s or less to impact from 30 km/h (1.5 s from 8 km/h), two frames running. Closing faster than the car drives is ignored as a jumpy box. |
| Lane | From 60 km/h, a wheel on the line (offset 0.22 of the lane) and still drifting out, no indicator in the last 2 s. Once per side until the car is back in the middle. |
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

## What to try on a real drive

- The horizon line set right (drag it on the phone's screen): the lane is searched below it.
- The phone upright as well as sideways: the lane found, the car ahead boxed, and the buttons reachable.
- Lanes at night, in rain, on worn markings; false lane warnings on a slip road or at roadworks.
- Collision warnings in stop-and-go traffic (false alarms) and approaching a queue at speed (late or missing).
- The phone's temperature after an hour in the sun, with and without the recorder.
- The recorder: a clip kept by hand, then one from a firm brake; the joined MP4 plays in the phone's gallery and on a PC; the burned-in time and speed read right, upright, whichever way the phone was held.
