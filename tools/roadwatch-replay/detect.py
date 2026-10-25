#!/usr/bin/env python3
"""The vehicles in every frame of a recorded drive, with the phone's own model
and the phone's own crop, for roadreplay to read (--detections).

Usage: python -I detect.py <video.mp4> <out.jsonl> [--horizon 0.5] [--bonnet 0.92] [--every 1]

Needs ffmpeg on the PATH and: pip install ai-edge-litert numpy
The model (EfficientDet-Lite2, 7.6 MB) is fetched once next to this script
and checked against the same SHA-256 the phone checks. As on the phone, every
other frame looks at the middle of the square around the horizon at twice the
detail (far vehicles); roadreplay merges each frame with the one before.
"""
import argparse
import hashlib
import json
import os
import subprocess
import sys
import urllib.request

import numpy as np

MODEL_URL = ("https://storage.googleapis.com/download.tensorflow.org/models/tflite/task_library/"
             "object_detection/android/lite-model_efficientdet_lite2_detection_metadata_1.tflite")
MODEL_SHA256 = "6fd32c84ab1eb0f7e7f3a7a20a20d7df1530daa8378728f7c79571096286bd52"
SIZE = 448
MIN_SCORE = 0.4  # as RoadDetector.MIN_SCORE on the phone
EDGE = 0.01  # a horizon look's box cut off by its edge is a near vehicle the square has whole


def model_path():
    path = os.path.join(os.path.dirname(os.path.abspath(__file__)), "efficientdet_lite2.tflite")
    if not os.path.exists(path):
        print("fetching the model", file=sys.stderr)
        urllib.request.urlretrieve(MODEL_URL, path)
    with open(path, "rb") as f:
        if hashlib.sha256(f.read()).hexdigest() != MODEL_SHA256:
            os.remove(path)
            sys.exit("the model's fingerprint is wrong; removed, run again")
    return path


def probe(video):
    out = subprocess.check_output(["ffprobe", "-v", "error", "-select_streams", "v:0", "-show_entries",
                                   "stream=width,height", "-of", "csv=p=0", video], text=True)
    w, h = out.strip().split(",")[:2]
    return int(w), int(h)


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("video")
    ap.add_argument("out")
    ap.add_argument("--horizon", type=float, default=0.5)
    ap.add_argument("--bonnet", type=float, default=0.92)
    ap.add_argument("--every", type=int, default=1, help="run the model on every nth frame")
    a = ap.parse_args()

    from ai_edge_litert.interpreter import Interpreter
    it = Interpreter(model_path=model_path(), num_threads=4)
    it.allocate_tensors()
    inp = it.get_input_details()[0]["index"]
    outs = {d["name"]: d["index"] for d in it.get_output_details()}
    boxes_i = outs["StatefulPartitionedCall:3"]
    classes_i = outs["StatefulPartitionedCall:2"]
    scores_i = outs["StatefulPartitionedCall:1"]
    count_i = outs["StatefulPartitionedCall:0"]

    w, h = probe(a.video)
    # The phone's crop (RoadDetector.detect): a square as wide as the shorter side of the 4:3 picture
    # Road watch analyses, set on the road. A sideways 16:9 recording is that picture with its top and
    # bottom cut: the square is 0.75 of its width and reaches above and below it (grey), so vehicles
    # come out as small as the phone sees them. Upright, the 4:3 picture is as wide as the recording.
    if w > h:
        side = round(0.75 * w)
        left = (w - side) // 2
        top = (h - side) // 2
        upright_h = side  # the 4:3 picture's height
    else:
        side = w
        left = 0
        top = min(max(int(round((a.horizon + a.bonnet) / 2 * h)) - side // 2, 0), h - side)
        upright_h = h
    # The horizon look (RoadDetector.detect with horizonY): half the square, a third of it above the horizon.
    tside = side // 2
    tleft = (w - tside) // 2
    ttop = int(round(a.horizon * h - tside / 3))
    ttop = min(max(ttop, top), top + upright_h - tside) if w > h else min(max(ttop, 0), h - tside)
    dec = subprocess.Popen(["ffmpeg", "-v", "error", "-i", a.video, "-f", "rawvideo", "-pix_fmt", "rgb24", "-"],
                           stdout=subprocess.PIPE, bufsize=1 << 20)
    n = 0
    with open(a.out, "w") as out:
        while True:
            raw = dec.stdout.read(w * h * 3)
            if len(raw) < w * h * 3:
                break
            if n % a.every == 0:
                frame = np.frombuffer(raw, dtype=np.uint8).reshape(h, w, 3)
                horizon_look = (n // a.every) % 2 == 1
                sx, sy, ss = (tleft, ttop, tside) if horizon_look else (left, top, side)
                # Nearest-neighbour to the model's size, as the phone samples it; outside the recording, grey.
                ys = sy + np.arange(SIZE) * ss // SIZE
                xs = sx + np.arange(SIZE) * ss // SIZE
                small = np.full((SIZE, SIZE, 3), 114, np.uint8)
                vy = (ys >= 0) & (ys < h)
                vx = (xs >= 0) & (xs < w)
                small[np.ix_(vy, vx)] = frame[np.ix_(ys[vy], xs[vx])]
                it.set_tensor(inp, small[None])
                it.invoke()
                boxes = it.get_tensor(boxes_i)[0]
                classes = it.get_tensor(classes_i)[0]
                scores = it.get_tensor(scores_i)[0]
                count = int(it.get_tensor(count_i)[0])
                found = []
                for k in range(min(count, len(scores))):
                    if scores[k] < MIN_SCORE:
                        continue
                    ymin, xmin, ymax, xmax = [float(v) for v in boxes[k]]
                    if horizon_look and (xmin < EDGE or ymin < EDGE or xmax > 1 - EDGE or ymax > 1 - EDGE):
                        continue
                    found.append([round((sx + xmin * ss) / w, 4), round((sy + ymin * ss) / h, 4),
                                  round((sx + xmax * ss) / w, 4), round((sy + ymax * ss) / h, 4),
                                  int(classes[k]), round(float(scores[k]), 3)])
                out.write(json.dumps({"frame": n, "boxes": found}) + "\n")
                if n % 300 == 0:
                    print(f"frame {n}", file=sys.stderr)
            n += 1
    dec.wait()
    print(f"{n} frames", file=sys.stderr)


if __name__ == "__main__":
    main()
