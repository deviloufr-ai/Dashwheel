# Road watch replay

Runs the code the phone runs (`LaneFinder`, `LeadTracker`, `RoadRules` from `link`) over a recorded drive on a PC, draws what it saw over every frame, and counts what it got right. Every change to the lane or vehicle logic should be judged here, on real clips, before it goes on the road.

Needs a JDK (17 to 21), `ffmpeg` and `ffprobe` on the PATH, and for the vehicles Python with `pip install ai-edge-litert numpy`.

## Get a clip

The road recorder in Dashwheel Companion keeps clips (`clip.mp4` with `clip.json` beside it, the speeds and indicators twice a second). Share one to the PC. Any dashcam video works too, with `--speed` instead of the notes.

## Run it

```sh
# 1. The vehicles, with the phone's own model and crop (once per clip; slow-ish). Boxes down to score 0.2, as the
#    phone keeps them: the tracker sorts them out. --looks both writes both looks' boxes on every frame, tagged, and
#    the replay takes the look the phone would (for trying another schedule of the looks).
python -I tools/roadwatch-replay/detect.py clip.mp4 clip.detections.jsonl --horizon 0.5

# 2. The lane model, with the model the companion ships (once per clip; every other frame, as the phone).
python -I tools/roadwatch-replay/lanes.py clip.mp4 clip.lanes --horizon 0.5

# 3. The replay: the annotated video, the report and the timeline.
./gradlew :roadreplay:run --args="clip.mp4 --detections clip.detections.jsonl --lanes clip.lanes --clip clip.json --horizon 0.5 --out clip.seen.mp4 --report clip.report.json"
```

Leave out `--lanes` to see what a phone without the lane model does (lines found by brightness); the video then shows no green road and red lines.

`--hfov` is the camera's own view; a clip filmed zoomed in (`zoom` in its notes, 1.3 since 2026-10-26) narrows it by itself. `--horizon` is where the horizon starts in the picture (the dotted line on the phone); the replay then learns it from where the lane's lines meet, as the phone does, and the summary says where it ended (`--learn-horizon no` keeps it put). It matters: start near it, from a frame. `--hfov` is the camera's view across the picture (66° sideways on most phones, about 51° upright); `--car-x` where the car's middle is across it.

The summary prints at the end:

```
frames: 1800
lane: both lines 71 %, one 19 %, none 10 %
car ahead seen: 64 %
warnings: 2
  TOO_CLOSE at 41.2 s (frame 1236)
  LANE_RIGHT at 52.9 s (frame 1587)
```

`clip.report.csv` has every frame: speed, lines, offset, where each line is, the car ahead's distance, closing speed and time to collision, the warning, and with `--lanes` the lanes and which the car is in; then the horizon and the car's place across the picture, as learned. Plot it, or diff two runs.

## Look at the signs it finds

`--signs dir` runs `SignFinder` as the phone does: every third frame, shrunk to 1280 across. It writes each round sign found (a red ring, `limit`; a light disc struck through, `end`), four times bigger, into `dir`, with `signs.csv` (frame, kind, box, size in pixels). The number on a limit sign is read by ML Kit on the phone only.

## Measure the vehicle finder against a big model

`eval/` scores detectors by distance, with no hand labels. `reference.py` runs YOLOPv2 (its `yolopv2.pt` from the YOLOPv2 release; needs `torch` and `torchvision`; on a CUDA card it is 50 to 100 times faster than on the CPU) over every 6th frame, on the whole picture and on its middle at twice the detail. `detect_x.py` runs a candidate as the phone would: an EfficientDet `.tflite` or a YOLOX `.onnx`, `--tile` for the horizon look. `score.py` prints, by distance, how much of the reference each candidate and mode found (the car ahead apart), and how many boxes it invented:

```sh
python eval/reference.py yolopv2.pt clip.mp4 eval-dir/ref/clip.jsonl
python -I eval/detect_x.py efficientdet_lite2.tflite clip.mp4 eval-dir/det/clip.lite2.jsonl --horizon 0.6 --tile
python -I eval/score.py eval-dir --models lite2
```

## The sign classifier

`signs_model.py` trains the companion's `sign_faces.tflite` (what a round red sign's face shows: a limit, the end of one, another sign, no sign) on GTSRB and tests it on the faces the phone kept (`GET /signs`); its docstring says what it needs. Run it again after changing the classes or the drawn signs, and copy the model into `companion/src/main/assets/`.

## Label frames to measure errors

```sh
./gradlew :roadreplay:run --args="clip.mp4 --dump labels/ --every 60"
```

writes every 60th frame as a PNG with a ruler along the bonnet's row. Write what you see into `labels.csv`:

```
frame,left,right,lead_m
0,0.18,0.84,
60,0.17,0.85,32
120,,,18
```

`left` and `right` are where the lane's lines cross the bonnet's row, as tenths of the width off the ruler; `lead_m` your best estimate of the distance to the car ahead (a pace is a metre; a car's length is 4.5). Empty cells are "not labelled". Then `--labels labels.csv` adds the recall and the mean errors to the report.

A dozen clips (day, night, rain, a bend, a slip road, a queue) with fifty labelled frames each is enough to tell a good change from a bad one.
