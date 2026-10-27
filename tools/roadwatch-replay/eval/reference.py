"""Reference vehicles for a drive: YOLOPv2 (BDD100K dashcam training) over every nth frame,
on the whole picture and on its middle at twice the detail, merged.

Usage: python reference.py <yolopv2.pt> <video.mp4> <out.jsonl> [--every 6] [--device cuda]
Writes {"frame": n, "boxes": [[l, t, r, b, score], ...]} in fractions of the frame.
On a CUDA card (--device cuda, the default when one is there) it runs 50 to 100 times faster than on the CPU.
"""
import argparse, json, subprocess
import numpy as np
import torch, torchvision

SIZE = 640


def probe(video):
    out = subprocess.check_output(["ffprobe", "-v", "error", "-select_streams", "v:0", "-show_entries",
                                   "stream=width,height", "-of", "csv=p=0", video], text=True)
    w, h = out.strip().split(",")[:2]
    return int(w), int(h)


def decode(pred, anchor_grid):
    # split_for_trace_model from YOLOPv2, rewritten: the three heads to (x, y, w, h, obj, cls) in input pixels.
    z = []
    st = [8, 16, 32]
    for i in range(3):
        bs, _, ny, nx = pred[i].shape
        p = pred[i].view(bs, 3, 85, ny, nx).permute(0, 1, 3, 4, 2).contiguous()
        y = p.sigmoid()
        yv, xv = torch.meshgrid([torch.arange(ny, device=y.device), torch.arange(nx, device=y.device)], indexing="ij")
        grid = torch.stack((xv, yv), 2).view(1, 1, ny, nx, 2).float()
        y[..., 0:2] = (y[..., 0:2] * 2. - 0.5 + grid) * st[i]
        y[..., 2:4] = (y[..., 2:4] * 2) ** 2 * anchor_grid[i]
        z.append(y.view(bs, -1, 85))
    return torch.cat(z, 1)[0]


def run(model, img, conf=0.3, device="cpu"):
    """img: HxWx3 uint8 RGB, W == SIZE. Boxes in its pixels."""
    h, w = img.shape[:2]
    ph = (32 - h % 32) % 32
    x = np.pad(img, ((0, ph), (0, 0), (0, 0)), constant_values=114)
    t = torch.from_numpy(x).permute(2, 0, 1).float().unsqueeze(0).to(device) / 255.0
    with torch.no_grad():
        [pred, anchor_grid], _, _ = model(t)
    p = decode(pred, anchor_grid)
    score = p[:, 4] * p[:, 5:].max(1).values
    keep = score > conf
    p, score = p[keep], score[keep]
    b = torch.stack([p[:, 0] - p[:, 2] / 2, p[:, 1] - p[:, 3] / 2, p[:, 0] + p[:, 2] / 2, p[:, 1] + p[:, 3] / 2], 1)
    k = torchvision.ops.nms(b, score, 0.45)
    return b[k].cpu().numpy(), score[k].cpu().numpy()


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("weights"); ap.add_argument("video"); ap.add_argument("out")
    ap.add_argument("--every", type=int, default=6)
    ap.add_argument("--max", type=int, default=0)
    ap.add_argument("--device", default="cuda" if torch.cuda.is_available() else "cpu")
    a = ap.parse_args()
    torch.set_num_threads(8)
    model = torch.jit.load(a.weights, map_location=a.device).eval()
    w, h = probe(a.video)
    ow, oh = SIZE, round(h * SIZE / w / 2) * 2
    # The middle half of the picture at twice the detail: far vehicles.
    cw, chh = w // 2, h // 2
    cx0, cy0 = w // 4, h // 4
    proc = subprocess.Popen(["ffmpeg", "-v", "error", "-i", a.video, "-f", "rawvideo", "-pix_fmt", "rgb24", "-"],
                            stdout=subprocess.PIPE)
    n = 0
    with open(a.out, "w") as out:
        while True:
            raw = proc.stdout.read(w * h * 3)
            if len(raw) < w * h * 3 or (a.max and n >= a.max):
                break
            if n % a.every == 0:
                frame = np.frombuffer(raw, np.uint8).reshape(h, w, 3)
                whole = torch.nn.functional.interpolate(torch.from_numpy(frame).permute(2, 0, 1)[None].float(), size=(oh, ow), mode="area")[0].permute(1, 2, 0).byte().numpy()
                mid = frame[cy0:cy0 + chh, cx0:cx0 + cw]
                midr = torch.nn.functional.interpolate(torch.from_numpy(np.ascontiguousarray(mid)).permute(2, 0, 1)[None].float(), size=(round(chh * SIZE / cw / 2) * 2, SIZE), mode="area")[0].permute(1, 2, 0).byte().numpy()
                b1, s1 = run(model, whole, device=a.device)
                b1 = b1 / np.array([ow, oh, ow, oh])
                b2, s2 = run(model, midr, device=a.device)
                b2 = b2 / np.array([SIZE, midr.shape[0], SIZE, midr.shape[0]])
                b2 = b2 * np.array([cw / w, chh / h, cw / w, chh / h]) + np.array([cx0 / w, cy0 / h, cx0 / w, cy0 / h])
                boxes = np.concatenate([b1, b2]) if len(b2) else b1
                scores = np.concatenate([s1, s2]) if len(s2) else s1
                if len(boxes):
                    k = torchvision.ops.nms(torch.from_numpy(boxes).float(), torch.from_numpy(scores).float(), 0.5).numpy()
                    boxes, scores = boxes[k], scores[k]
                found = [[round(float(x), 4) for x in bx] + [round(float(s), 3)] for bx, s in zip(boxes, scores)]
                out.write(json.dumps({"frame": n, "boxes": found}) + "\n")
                if n % 300 == 0:
                    print("frame", n, flush=True)
            n += 1
    proc.kill()


if __name__ == "__main__":
    main()
