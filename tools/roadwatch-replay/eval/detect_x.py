"""Vehicles as the phone would see them, for comparing models and crops.

The phone analyses a 4:3 picture: its square is as wide as 0.75 of the camera's width and as tall as the
whole 4:3 frame, which on a 16:9 recording reaches above and below it (filled grey). With --tile, the
middle of the square around the horizon is also run at twice the detail. Every box down to --min-score
is kept, with "t": 1 for the tile's, so thresholds and modes are chosen afterwards.

Usage: python detect_x.py <model.tflite|model.onnx> <video> <out.jsonl> [--horizon 0.5] [--every 2] [--tile]
"""
import argparse, json, subprocess, sys
import numpy as np

VEHICLES_COCO = {1, 2, 3, 5, 7}  # bicycle, car, motorcycle, bus, truck (0 = person)


def probe(video):
    out = subprocess.check_output(["ffprobe", "-v", "error", "-select_streams", "v:0", "-show_entries",
                                   "stream=width,height", "-of", "csv=p=0", video], text=True)
    w, h = out.strip().split(",")[:2]
    return int(w), int(h)


class Lite:
    """EfficientDet-Lite (TF task library export): uint8 square in, boxes/classes/scores out."""
    def __init__(self, path):
        from ai_edge_litert.interpreter import Interpreter
        self.it = Interpreter(model_path=path, num_threads=4)
        self.it.allocate_tensors()
        d = self.it.get_input_details()[0]
        self.inp, self.size = d["index"], d["shape"][1]
        o = {x["name"]: x["index"] for x in self.it.get_output_details()}
        self.o = [o["StatefulPartitionedCall:3"], o["StatefulPartitionedCall:2"], o["StatefulPartitionedCall:1"], o["StatefulPartitionedCall:0"]]

    def __call__(self, sq, min_score):
        """sq: SxSx3 RGB uint8 at self.size. Boxes (l, t, r, b) in fractions of it."""
        self.it.set_tensor(self.inp, sq[None])
        self.it.invoke()
        boxes, classes, scores, count = (self.it.get_tensor(i)[0] for i in self.o)
        out = []
        for k in range(min(int(count), len(scores))):
            if scores[k] >= min_score and int(classes[k]) in VEHICLES_COCO:
                y0, x0, y1, x1 = (float(v) for v in boxes[k])
                out.append((x0, y0, x1, y1, int(classes[k]), float(scores[k])))
        return out


class Yolox:
    """YOLOX ONNX (Megvii release, or one fine-tuned on dashcam frames with a single vehicle class, which comes
    out as COCO's car): BGR float 0-255 in, raw grid out, decoded and NMSed here."""
    def __init__(self, path):
        import onnxruntime as ort
        self.s = ort.InferenceSession(path, providers=["CPUExecutionProvider"])
        self.name = self.s.get_inputs()[0].name
        self.size = self.s.get_inputs()[0].shape[2]
        grids, strides = [], []
        for st in (8, 16, 32):
            n = self.size // st
            yv, xv = np.meshgrid(np.arange(n), np.arange(n), indexing="ij")
            grids.append(np.stack((xv, yv), 2).reshape(-1, 2))
            strides.append(np.full((n * n, 1), st))
        self.grid = np.concatenate(grids).astype(np.float32)
        self.stride = np.concatenate(strides).astype(np.float32)

    def __call__(self, sq, min_score):
        x = sq[:, :, ::-1].transpose(2, 0, 1)[None].astype(np.float32)
        p = self.s.run(None, {self.name: x})[0][0]
        xy = (p[:, :2] + self.grid) * self.stride
        wh = np.exp(p[:, 2:4]) * self.stride
        cls = p[:, 5:].argmax(1)
        score = p[:, 4] * p[np.arange(len(p)), 5 + cls]
        if p.shape[1] == 6:
            cls = np.full(len(p), 2)
        keep = (score >= min_score) & np.isin(cls, list(VEHICLES_COCO))
        xy, wh, cls, score = xy[keep], wh[keep], cls[keep], score[keep]
        b = np.concatenate([xy - wh / 2, xy + wh / 2], 1) / self.size
        out = []
        for i in nms(b, score, 0.45):
            out.append((*[float(v) for v in b[i]], int(cls[i]), float(score[i])))
        return out


def nms(b, s, thr):
    order = s.argsort()[::-1]
    keep = []
    while len(order):
        i = order[0]
        keep.append(i)
        xx0 = np.maximum(b[i, 0], b[order[1:], 0]); yy0 = np.maximum(b[i, 1], b[order[1:], 1])
        xx1 = np.minimum(b[i, 2], b[order[1:], 2]); yy1 = np.minimum(b[i, 3], b[order[1:], 3])
        inter = np.clip(xx1 - xx0, 0, None) * np.clip(yy1 - yy0, 0, None)
        area = (b[:, 2] - b[:, 0]) * (b[:, 3] - b[:, 1])
        iou = inter / (area[i] + area[order[1:]] - inter + 1e-9)
        order = order[1:][iou < thr]
    return keep


def square(frame, x0, y0, side, size):
    """The side x side square at (x0, y0) of frame (pixels, may reach outside: grey), sampled to size."""
    h, w = frame.shape[:2]
    xs = (x0 + np.arange(size) * side / size).astype(int)
    ys = (y0 + np.arange(size) * side / size).astype(int)
    out = np.full((size, size, 3), 114, np.uint8)
    vy = (ys >= 0) & (ys < h)
    vx = (xs >= 0) & (xs < w)
    out[np.ix_(vy, vx)] = frame[np.ix_(ys[vy], xs[vx])]
    return out


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("model"); ap.add_argument("video"); ap.add_argument("out")
    ap.add_argument("--horizon", type=float, default=0.5)
    ap.add_argument("--every", type=int, default=2)
    ap.add_argument("--tile", action="store_true")
    ap.add_argument("--min-score", type=float, default=0.2)
    ap.add_argument("--max", type=int, default=0)
    ap.add_argument("--square", type=float, default=0.75, help="the phone's square, a share of the camera's width")
    a = ap.parse_args()
    model = Lite(a.model) if a.model.endswith(".tflite") else Yolox(a.model)
    w, h = probe(a.video)
    side = a.square * w
    x0 = (w - side) / 2
    y0 = (h - side) / 2  # the 4:3 frame's full height, centred on the 16:9 recording
    tside = side / 2
    tx0 = (w - tside) / 2
    # Far vehicles sit just under the horizon: the tile reaches a third of its height above it.
    ty0 = a.horizon * h - tside / 3
    dec = subprocess.Popen(["ffmpeg", "-v", "error", "-i", a.video, "-f", "rawvideo", "-pix_fmt", "rgb24", "-"],
                           stdout=subprocess.PIPE, bufsize=1 << 20)
    n = 0
    with open(a.out, "w") as out:
        while True:
            raw = dec.stdout.read(w * h * 3)
            if len(raw) < w * h * 3 or (a.max and n >= a.max):
                break
            if n % a.every == 0:
                frame = np.frombuffer(raw, np.uint8).reshape(h, w, 3)
                found = []
                for (sx, sy, ss, t) in [(x0, y0, side, 0)] + ([(tx0, ty0, tside, 1)] if a.tile else []):
                    for (l, tp, r, b, c, s) in model(square(frame, sx, sy, ss, model.size), a.min_score):
                        found.append([round((sx + l * ss) / w, 4), round((sy + tp * ss) / h, 4),
                                      round((sx + r * ss) / w, 4), round((sy + b * ss) / h, 4), c, round(s, 3), t])
                out.write(json.dumps({"frame": n, "boxes": found}) + "\n")
                if n % 600 == 0:
                    print("frame", n, file=sys.stderr, flush=True)
            n += 1
    dec.kill()


if __name__ == "__main__":
    main()
