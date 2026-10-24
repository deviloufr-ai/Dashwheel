#!/usr/bin/env python3
"""What the lane model sees in every other frame of a recorded drive, for
roadreplay to read (--lanes). It looks at a 16:9 band as wide as the frame,
set on the road.

Usage: python -I lanes.py <video.mp4> <out.lanes> [--horizon 0.5] [--bonnet 0.92] [--every 2]

Needs ffmpeg on the PATH and: pip install ai-edge-litert numpy
The model: road_lanes.tflite beside this script (built by lanes_model.py).

The file: b"DWLANES1"; the band the model looked at, its top and height as
fractions of the frame (2 float32 LE); then per frame looked at: the frame's
number and the byte count (2 int32 LE), and zlib of the output as 0..255
bytes ([2, 176, 320]: the road's plane, then the lanes').
"""
import argparse
import os
import struct
import subprocess
import sys
import zlib

import numpy as np

W, H = 320, 176  # the model's input
MODEL = os.path.join(os.path.dirname(os.path.abspath(__file__)), "road_lanes.tflite")


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
    ap.add_argument("--every", type=int, default=2, help="look at every nth frame; the replay keeps the last between")
    a = ap.parse_args()

    from ai_edge_litert.interpreter import Interpreter
    it = Interpreter(model_path=MODEL, num_threads=4)
    it.allocate_tensors()
    inp = it.get_input_details()[0]["index"]
    outp = it.get_output_details()[0]["index"]

    w, h = probe(a.video)
    # A 16:9 band as wide as the frame, set on the road.
    band_h = min(h, w * H // W)
    top = min(max(int(round((a.horizon + a.bonnet) / 2 * h)) - band_h // 2, 0), h - band_h)
    ys = top + np.arange(H) * band_h // H
    xs = np.arange(W) * w // W
    dec = subprocess.Popen(["ffmpeg", "-v", "error", "-i", a.video, "-f", "rawvideo", "-pix_fmt", "rgb24", "-"],
                           stdout=subprocess.PIPE, bufsize=1 << 20)
    n = 0
    with open(a.out, "wb") as out:
        out.write(b"DWLANES1" + struct.pack("<ff", top / h, band_h / h))
        while True:
            raw = dec.stdout.read(w * h * 3)
            if len(raw) < w * h * 3:
                break
            if n % a.every == 0:
                frame = np.frombuffer(raw, dtype=np.uint8).reshape(h, w, 3)
                it.set_tensor(inp, (frame[ys][:, xs].astype(np.float32) / 255)[None])
                it.invoke()
                chances = np.clip(np.rint(it.get_tensor(outp)[0] * 255), 0, 255).astype(np.uint8)
                packed = zlib.compress(chances.tobytes(), 6)
                out.write(struct.pack("<ii", n, len(packed)) + packed)
                if n % 300 == 0:
                    print(f"frame {n}", file=sys.stderr)
            n += 1
    dec.wait()
    print(f"{n} frames", file=sys.stderr)


if __name__ == "__main__":
    main()
