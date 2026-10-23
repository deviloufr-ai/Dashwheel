#!/usr/bin/env python3
"""The vehicles in every frame of a recorded drive, with the phone's own model
and the phone's own crop, for roadreplay to read (--detections).

Usage: python -I detect.py <video.mp4> <out.jsonl> [--horizon 0.5] [--bonnet 0.92] [--every 1]

Needs ffmpeg on the PATH and: pip install ai-edge-litert numpy
The model (EfficientDet-Lite0, 4.6 MB) is fetched once next to this script
and checked against the same SHA-256 the phone checks.
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
             "object_detection/android/lite-model_efficientdet_lite0_detection_metadata_1.tflite")
MODEL_SHA256 = "2e04c53bfeac0ac2a30c057c7e2a777594ce39baaac35a92f74fb1e8c4fc4e0b"
SIZE = 320
MIN_SCORE = 0.4  # as RoadDetector.MIN_SCORE on the phone


def model_path():
    path = os.path.join(os.path.dirname(os.path.abspath(__file__)), "efficientdet_lite0.tflite")
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
    # The phone's crop (RoadDetector.detect): a square as wide as the shorter side, set on the road.
    side = min(w, h)
    left = (w - side) // 2
    top = min(max(int(round((a.horizon + a.bonnet) / 2 * h)) - side // 2, 0), h - side)
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
                crop = frame[top:top + side, left:left + side]
                # Nearest-neighbour to 320, as the phone samples it.
                ys = (np.arange(SIZE) * side // SIZE)
                xs = (np.arange(SIZE) * side // SIZE)
                small = crop[ys][:, xs]
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
                    found.append([round((left + xmin * side) / w, 4), round((top + ymin * side) / h, 4),
                                  round((left + xmax * side) / w, 4), round((top + ymax * side) / h, 4),
                                  int(classes[k]), round(float(scores[k]), 3)])
                out.write(json.dumps({"frame": n, "boxes": found}) + "\n")
                if n % 300 == 0:
                    print(f"frame {n}", file=sys.stderr)
            n += 1
    dec.wait()
    print(f"{n} frames", file=sys.stderr)


if __name__ == "__main__":
    main()
