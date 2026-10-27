# Road watch: ways to see better, ranked (2026-10-26)

A review of the detection pipeline on main (4db5cf27) and the ideas that would improve it, ranked by the gain each should bring. The percentages are estimates against the numbers already measured in `tools/roadwatch-replay/eval` and on the S25; each says which number it moves. None is measured yet: score each in the replay before it ships.

## Where it stands

| What | Now | Weak spot |
|---|---|---|
| Vehicle detector | EfficientDet-Lite2, 448², GPU 48 ms cool / 106 ms warm, CPU trial | 8 to 18 fps; the model is the bottleneck of the whole frame |
| Range | Car ahead found 99 / 93 / 80 % at 15–30 / 30–50 / 50–80 m, 15 % past 80 m (zoom 1.3) | Past 80 m is where a motorway warning needs it |
| Invented boxes | 3.7 per 100 frames | Fixed score 0.4; 0.3 found more but invented 3× |
| Following a vehicle | Nearest-in-lane box, IoU 0.3 to the last one, history dropped otherwise, let go after 500 ms | No identity, no prediction; a near car's box moves fast between two frames at 8 fps and loses its history |
| Distance | Box width over an assumed width per kind (car 1.8 m) | A van read as a car is 10–20 % off; a motorcycle at an angle worse |
| Time to impact | Straight line through 1 s of distances (5 samples min) | At 8 fps, 5 samples is 0.6 s; box jitter sets the floor |
| False collision alarms | 13 on the 13 kept clips (all false) | Parked cars, junctions, and the phone being moved |
| Lane | Brightness Hough, learned horizon, bonnet from placement; 66–91 % both lines | Night, rain, worn paint, unmarked country roads; the model is replay only |
| Signs | Red rings every 3rd frame, ML Kit OCR on the face, 2 reads needed | One 90 at 80 km/h missed; reads only when the face is ~32 px; "06" upside-down reads |
| Night | Nothing specific | Brightness lane finder and a COCO model both drop at night (unmeasured) |
| Ego motion | Gyro heading recorded, not used | A bump moves every box; a phone moved in its holder warned on the rural clip |

## Ideas, ranked

| # | Idea | Moves | Estimated gain | Cost |
|---|---|---|---|---|
| 1 | **Run the detector on the NPU** through LiteRT's Qualcomm accelerator (the S25 is a Snapdragon 8 Elite), with an int8 model. Keep the GPU/CPU trial as the fallback for other phones. | fps | 8–18 → 25–30 fps (+150–250 %). Everything below the detector gets more frames: more TTC samples, more sign reads, the horizon look every frame. | Medium: int8 conversion of the model, LiteRT 2.x accelerator dependency, a per-phone trial like the GPU one. Qualcomm phones only. |
| 2 | **A dashcam-trained detector** instead of a COCO one: YOLOX-tiny already scored 86 % at 50–80 m against Lite2's 60 %; fine-tuned on BDD100K (100k dashcam frames, night and rain included) it sees further and invents less, since it only knows road things. Apache 2.0 models only: YOLOX, PP-YOLOE, DAMO-YOLO. YOLO26/YOLO11 are AGPL, which the Dashwheel licence can't take. | Range, invented boxes, night | 50–80 m: 80 → 90 %; 80 m+: 15 → 40 %; invented boxes −30 to −50 % (vehicle-only head); night recall +30–50 % relative (unmeasured today). | High: training run on a PC GPU (a few hours), onnx2tf, hosting, a new SHA. Needs #1 or it halves the fps. |
| 3 | **A real tracker** (ByteTrack-style: Kalman per vehicle, an ID, two-pass matching). Boxes scoring 0.2–0.4 are kept only when they match a track already seen: the recall of threshold 0.3 without its invented boxes. A track survives 2–3 missed frames, and the filtered box height feeds the TTC instead of the raw one. | Invented boxes, false alarms, TTC jitter, range | Invented boxes −50 to −70 %; false collision alarms −20 to −30 %; 50–80 m +10 %; closing speed noise about halved. | Low: ~150 lines of plain Kotlin in `link`, JVM-tested, scored in the replay. Best gain per hour. |
| 4 | **Distance from the box's bottom on the ground plane** (camera height, learned horizon, focal), fused with the width. Class-free: a van, a motorcycle, a trailer all touch the road at their real distance. Width stays for a box whose bottom is hidden. | Distance (TOO_CLOSE, MOVED_OFF, the edge test) | Distance error −30 to −40 % (width needs the size right; the ground plane needs the pitch right, which #5 gives). TTC unchanged (it's scale-based). | Low: one formula in `LeadTracker`, camera height asked once in the placement guide (1.2 m default). |
| 5 | **Pitch and yaw from the gyro, per frame**: shift the horizon and the road band by the pitch (a bump moves every box, which reads as growth), and hold warnings while the phone itself turns in its holder. Heading is already recorded; add pitch. | False alarms (collision and lane) | False collision −15 to −25 %; false lane −30 % on rough roads. The heading-only trial gave nothing; pitch is the axis a bump moves. | Low: gyro integration in `RoadWatch`, one offset in `RoadBand`. Replayable once pitch is in the clip notes. |
| 6 | **Track-guided far look**: once the lead is beyond 40 m, or none is seen, run the horizon crop on every frame instead of every other, and crop around the tracked lead (a cheap SAHI). With #1 both looks fit in one frame. | Range | 80 m+: 15 → 35 %; 50–80 m +5 %. | Low with #1, else −40 % fps. |
| 7 | **A sign classifier instead of OCR**: a GTSRB-trained CNN on the ring's 32×32 grey face (97–98 % on GTSRB, under 1 ms). Reads a face half the size OCR needs (so twice as far, 3–4× more tries per sign), never reads "06", and knows end-of-limit, village entry/exit and no-overtaking signs that OCR can't. Keep OCR as a second opinion for 1 s after. | Sign read rate | Signs read: the missed 90 at 80 km/h gets ~6 tries instead of 1–2; expect ≥ 90 % of limits read (today unmeasured, one miss on two signs). | Medium: train on GTSRB + a few hundred French crops from `/signs`, 100 kB model shipped in the app. |
| 8 | **Lane model on the phone** (TwinLiteNet at 640×360 every 3rd frame on the NPU, 8 GFLOPs; or UFLDv2-lite). The replay gave 84 % vs 80 % both lines and 1 vs 4 false warnings on Jakarta, but the brightness finder has no answer at night, in rain, on worn paint, and the model's road mask gives the edge of an unmarked country road. | Lane both-lines, lane false alarms, night | Both lines +10–20 points on marked roads; night/rain from near 0 to usable (unmeasured); unmarked roads: the road edge as a line. | High: needs #1 for the budget; the 320×176 model failed on the rural clip, so 640×360 it is. |
| 9 | **Night camera settings**: exposure compensation −0.5 EV and a shorter max exposure at night (CameraX `setExposureCompensationIndex`, Camera2 interop for the range), so oncoming headlights don't blow out the lead's tail lights; tail-light pairing as a fallback lead cue when the detector sees nothing in the dark. | Night recall | Night recall +20–40 % relative (unmeasured; needs a night clip in the eval set). | Low for exposure, medium for tail-light pairing. |
| 10 | **People and cyclists as leads**: the model already outputs COCO class 0 (person); `VehicleKind.ofCoco` drops it. Add PERSON (0.5 m wide, 1.7 m tall) for the town-speed collision rule. | Coverage | A whole class of collision warned on in town; no change to vehicle numbers. | Trivial: one enum entry, one case. |
| 11 | **TTC from log-scale, filtered**: fit the line on log(height) over time (constant closing speed is linear in log scale, as width-based distance already is), through the Kalman-filtered height from #3, with the window growing to 1.5 s at low closing rates. | TTC noise | Closing-speed jitter −30 %; fewer 2-frame false hits. | Low; mostly comes with #3. |
| 12 | **Metric monocular depth** (Depth Anything V2 Small, metric outdoor): one distance per pixel, for everything including things the detector has no class for. 25 M parameters, 34 ms on an iPhone's neural engine; on an Android NPU probably 40–80 ms. | Distance, unclassified obstacles | Distance error −50 %; obstacles of any kind. | High and only after #1; today it would halve the fps. Later. |

## What to do first

1. #3 (tracker) and #5 (gyro pitch) now: cheap, plain Kotlin, scored on the 13 clips and Jakarta in the replay, no new model.
2. #1 (NPU) next: it is what makes #2, #6 and #8 affordable.
3. #2 (dashcam-trained detector) once #1 runs: the biggest range gain, and it folds in night.
4. #7 (sign classifier) and #10 (people) whenever: independent of the rest.

Add a night clip and a rain clip to the eval set before #2, #8 or #9, or their gains stay guesses.

## What came of it (2026-10-27)

| # | Outcome |
|---|---|
| 1 NPU | Not built. LiteRT 2.3 has `litert-npu-runtime-qualcomm` on Maven (0.5 MB), but the Hexagon libraries must be bundled from Qualcomm's SDK under its licence, the detector would move to the CompiledModel API, and nothing can be tested here. |
| 2 Dashcam-trained detector | YOLOX-tiny fine-tuned on YOLOPv2 pseudo-labels of the 33 phone clips and the Jakarta hour (17,479 frames, 101,475 boxes) on the 3080 Ti. On three held-out clips it finds the car ahead past 80 m 61 % of the time (Lite2 3 %, stock YOLOX-tiny 47 %) but is worse up to 50 m and invents more; details in docs/road-watch.md. Not on the phone: it needs the ONNX-to-TFLite conversion, a YOLOX decoder in `RoadDetector`, hosting, and the NPU for its cost. |
| 3 Tracker | Built: `VehicleTracker`. False collision alarms 21 → 14 on 15 clips, car ahead seen 50.6 → 55.1 %, time to impact on 31 % more frames. |
| 4 Ground-plane distance | Built, tested, dropped: it fell 0.05 of the true distance against 0.86 for the width. The horizon isn't known to the fraction of a percent it needs. |
| 5 Gyro pitch | `pitchDeg` and `tiltDeg` in the clip notes; warnings held while the phone has turned in its holder. The horizon shift waits for drives with pitch in the notes. |
| 6 Far look | Built, tested, dropped: car ahead seen 57.1 → 57.5 %, three more false alarms. |
| 7 Sign classifier | Built: `SignClassifier`, 99.0 % on GTSRB, every 90 and 40 on the phone's faces plus six ML Kit missed, no false limit. |
| 8 Lane model on the phone | Not built: the replay gain was marginal (84 vs 80 % both lines) and the phone-sized model fails on the user's roads; it needs the NPU budget first. |
| 9 Night exposure | Built: half a stop less when the road is dark; untested. |
| 10 People as leads | Built, tested, dropped: 6 of 19 alarms were people beside the road. |
| 11 Log-scale TTC | Dropped: a constant closing speed makes the distance linear in time, which is what the regression already fits; the tracker's smoothing is the gain. |
| 12 Monocular depth | Not built (later, after the NPU). |

## Sources

- YOLO26 vs EfficientDet (Ultralytics): https://docs.ultralytics.com/compare/yolo26-vs-efficientdet/
- YOLO26n on a Snapdragon 8 Elite Gen 5 phone, CPU 53 / GPU 17 / NPU 11 ms: https://docs.ultralytics.com/integrations/qnn
- LiteRT Qualcomm NPU accelerator (Google, Nov 2025): https://developers.googleblog.com/en/unlocking-peak-performance-on-qualcomm-npu-with-litert/ and https://ai.google.dev/edge/litert/android/npu
- ByteTrack: https://arxiv.org/abs/2110.06864
- Ground-plane ranging and its pitch sensitivity: https://arxiv.org/pdf/2603.22781 and https://www.ncbi.nlm.nih.gov/pmc/articles/PMC3914606/
- Collision risk from monocular video (bottom edge + width hybrid): https://arxiv.org/pdf/1902.01293
- Lightweight GTSRB classifiers (97.5 %): https://www.frontiersin.org/articles/10.3389/fnins.2024.1431033/full
- Depth Anything V2: https://github.com/DepthAnything/Depth-Anything-V2
- Lightweight lane detectors (CULane/TuSimple): https://www.mdpi.com/2079-9292/13/13/2486/xml
