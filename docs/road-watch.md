# Road watch

Experimental. The phone, in a holder behind the windscreen, watches the road with its camera; the head unit warns the driver.

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

## What to try on a real drive

- The horizon line set right (drag it on the phone's screen): the lane is searched below it.
- Lanes at night, in rain, on worn markings; false lane warnings on a slip road or at roadworks.
- Collision warnings in stop-and-go traffic (false alarms) and approaching a queue at speed (late or missing).
- The phone's temperature after an hour in the sun.
