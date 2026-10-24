#!/usr/bin/env python3
"""Builds the lane and road model the replay runs (road_lanes.tflite beside
this script) from TwinLiteNet's published weights. Not on the phone yet: it
must first beat the brightness finder on recorded drives.

TwinLiteNet (MIT, Che et al. 2023) does what YOLOPv2's two segmentation heads
do, lane lines and the drivable road, trained on the same BDD100K drives, at
a 90th of YOLOPv2's cost: 0.44 M parameters, 1.9 GFLOPs at 320 x 176 against
YOLOPv2's 18 there (72 at its own 640 x 384). See docs/road-watch.md.

Usage:
  git clone https://github.com/chequanghuy/TwinLiteNet <dir>   # at TWIN_COMMIT
  python -I lanes_model.py <dir> road_lanes.tflite [--check road.jpg]

Needs: pip install torch onnx==1.17.* onnx2tf onnx_graphsurgeon sng4onnx onnxsim tensorflow==2.18.* tf_keras ai-edge-litert

The model: input [1, 176, 320, 3] float RGB 0..1 (the road's 16:9 band of the
frame, squeezed to 320 x 176); output [1, 2, 176, 320] float, the chance each
pixel is the drivable road (plane 0) and a lane line (plane 1).
"""
import argparse
import hashlib
import os
import shutil
import subprocess
import sys
import tempfile

import numpy as np

TWIN_COMMIT = "524141821c373c886443cf4254e3e87787c2c55e"
WEIGHTS_SHA256 = "98ffc6c8abc98fda72052117249ce4e2dbc85e58cfcfc6ee2d42f5398c772463"
W, H = 320, 176  # both divisible by 8, as the encoder needs; 16:8.8, near enough the 16:9 it was trained on


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("repo")
    ap.add_argument("out")
    ap.add_argument("--check", help="a road picture: the TFLite model must agree with PyTorch on it")
    a = ap.parse_args()

    import torch
    weights = os.path.join(a.repo, "pretrained", "best.pth")
    with open(weights, "rb") as f:
        if hashlib.sha256(f.read()).hexdigest() != WEIGHTS_SHA256:
            sys.exit("not TwinLiteNet's published weights")
    sys.path.append(a.repo)  # model/TwinLite.py imports torch alone
    from model.TwinLite import TwinLiteNet

    net = TwinLiteNet()
    state = torch.load(weights, map_location="cpu", weights_only=True)
    net.load_state_dict({k.removeprefix("module."): v for k, v in state.items()})
    net.eval()

    class Chances(torch.nn.Module):
        """Both heads' softmax, the 'yes' channel of each, side by side."""
        def __init__(self, inner):
            super().__init__()
            self.inner = inner

        def forward(self, x):
            road, lane = self.inner(x)
            return torch.stack([road.softmax(1)[:, 1], lane.softmax(1)[:, 1]], 1)

    model = Chances(net).eval()
    work = tempfile.mkdtemp()
    try:
        onnx_path = os.path.join(work, "lanes.onnx")
        torch.onnx.export(model, torch.zeros(1, 3, H, W), onnx_path, opset_version=17,
                          input_names=["image"], output_names=["chances"])
        # onnx2tf's self-check otherwise fetches its sample pictures from the internet, pickled: give it plain ones.
        np.save(os.path.join(work, "calibration_image_sample_data_20x128x128x3_float32.npy"),
                np.random.default_rng(0).random((20, 128, 128, 3), dtype=np.float32))
        subprocess.run([sys.executable, "-m", "onnx2tf", "-i", onnx_path, "-o", os.path.join(work, "tf"), "-n"], check=True, cwd=work)
        shutil.copy(os.path.join(work, "tf", "lanes_float16.tflite"), a.out)
    finally:
        shutil.rmtree(work, ignore_errors=True)

    from ai_edge_litert.interpreter import Interpreter
    lite = Interpreter(model_path=a.out)
    lite.allocate_tensors()
    i, o = lite.get_input_details()[0], lite.get_output_details()[0]
    print("input", i["shape"], i["dtype"], "output", o["shape"], o["dtype"], os.path.getsize(a.out), "bytes")
    assert list(i["shape"]) == [1, H, W, 3] and list(o["shape"]) == [1, 2, H, W], "the phone reads these shapes"

    if a.check:
        import cv2
        img = cv2.resize(cv2.imread(a.check), (W, H))[:, :, ::-1].astype(np.float32) / 255
        with torch.no_grad():
            want = model(torch.from_numpy(img.transpose(2, 0, 1).copy()).unsqueeze(0))[0].numpy()
        lite.set_tensor(i["index"], img[None])
        lite.invoke()
        got = lite.get_tensor(o["index"])[0]
        agree = ((got > 0.5) == (want > 0.5)).mean(axis=(1, 2))
        print("pixels agreeing with PyTorch: road %.4f, lane %.4f" % tuple(agree))
        assert agree.min() > 0.995, "the conversion changed what the model sees"


if __name__ == "__main__":
    main()
