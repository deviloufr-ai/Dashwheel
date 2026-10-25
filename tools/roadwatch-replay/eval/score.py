"""How much of what the reference sees a detector finds, by distance, and what it invents.

Usage: python score.py <eval dir> [--drives a,b,...] [--models lite0,lite2,...]
Reads <dir>/ref/<drive>.jsonl and <dir>/det/<drive>.<model>.jsonl (detect_x.py), prints a table.

Modes: base = the phone's square every frame; alt = square and horizon tile on alternate frames;
both = square and tile every frame (twice the work). A frame counts what the detector reported on it and
on the processed frame before it (the phone's tracker holds a vehicle for 500 ms).
Confirm: a box counts once a matching box was seen on 2 of the 3 runs of the same kind before it.
"""
import argparse, json, math, os
from collections import defaultdict

HFOV = {"jakarta": 66.0}
DEFAULT_HFOV = 74.0  # the S25's main camera across its long side
HORIZON = {"jakarta": 0.5, "20261024-144909-collision": 0.47, "20261024-145228-collision": 0.5, "20261024-145247-collision": 0.5}
BUCKETS = [(0, 15), (15, 30), (30, 50), (50, 80), (80, 999)]


def load(path):
    out = {}
    for line in open(path):
        j = json.loads(line)
        out[j["frame"]] = j["boxes"]
    return out


def iou(a, b):
    w = min(a[2], b[2]) - max(a[0], b[0]); h = min(a[3], b[3]) - max(a[1], b[1])
    if w <= 0 or h <= 0: return 0.0
    i = w * h
    return i / ((a[2] - a[0]) * (a[3] - a[1]) + (b[2] - b[0]) * (b[3] - b[1]) - i)


def same(a, b):
    if iou(a, b) >= 0.3: return True
    acx, acy = (a[0] + a[2]) / 2, (a[1] + a[3]) / 2
    bcx, bcy = (b[0] + b[2]) / 2, (b[1] + b[3]) / 2
    return a[0] <= bcx <= a[2] and a[1] <= bcy <= a[3] and b[0] <= acx <= b[2] and b[1] <= acy <= b[3]


def schedule(mode, k):
    """Which kinds (0 square, 1 tile) run on the k-th processed frame."""
    if mode == "base": return {0}
    if mode == "both": return {0, 1}
    return {0} if k % 2 == 0 else {1}


def score(ref, det, drive, mode, thr, confirm):
    hfov = HFOV.get(drive, DEFAULT_HFOV)
    half = math.tan(math.radians(hfov / 2))
    horizon = HORIZON.get(drive, 0.6)
    frames = sorted(det)
    step = frames[1] - frames[0]
    index = {f: k for k, f in enumerate(frames)}
    # What each processed frame reports in this mode, before confirmation.
    raw = {}
    for f in frames:
        kinds = schedule(mode, index[f])
        raw[f] = [b for b in det[f] if b[6] in kinds and b[5] >= thr]
    if confirm:
        kept = {}
        for f in frames:
            k = index[f]
            out = []
            for b in raw[f]:
                prev = [frames[k - j] for j in range(1, 7) if k - j >= 0 and b[6] in schedule(mode, k - j)][:3]
                hits = sum(any(same(b, p) for p in raw[g]) for g in prev)
                if hits >= 2: out.append(b)
            kept[f] = out
        raw = kept
    found = defaultdict(lambda: [0, 0]); ahead = defaultdict(lambda: [0, 0]); invented = 0; n = 0
    for f, rboxes in ref.items():
        if f not in index: continue
        k = index[f]
        seen = raw[f] + (raw[frames[k - 1]] if k > 0 else [])
        n += 1
        road = [r for r in rboxes if r[4] >= 0.5 and 0.125 <= (r[0] + r[2]) / 2 <= 0.875
                and r[3] > horizon - 0.02 and r[3] - r[1] < 0.6]
        near_ahead = None
        for r in road:
            d = 1.8 / (2 * half * max(r[2] - r[0], 1e-3))
            bucket = next(i for i, (lo, hi) in enumerate(BUCKETS) if lo <= d < hi)
            hit = any(same(r, b) for b in seen)
            found[bucket][0] += hit; found[bucket][1] += 1
            if 0.4 <= (r[0] + r[2]) / 2 <= 0.6 and (near_ahead is None or d < near_ahead[0]):
                near_ahead = (d, bucket, hit)
        if near_ahead:
            ahead[near_ahead[1]][0] += near_ahead[2]; ahead[near_ahead[1]][1] += 1
        invented += sum(1 for b in raw[f] if not any(same(b, r) for r in rboxes if r[4] >= 0.25))
    return found, ahead, invented, n


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("dir")
    ap.add_argument("--drives", default="")
    ap.add_argument("--models", default="lite0,lite2,nano,tiny")
    ap.add_argument("--thr", default="0.4,0.3")
    a = ap.parse_args()
    drives = a.drives.split(",") if a.drives else sorted(f[:-6] for f in os.listdir(os.path.join(a.dir, "ref")) if f.endswith(".jsonl"))
    refs = {d: load(os.path.join(a.dir, "ref", d + ".jsonl")) for d in drives}
    head = " ".join(f"{lo}-{hi if hi < 999 else '+'}m".rjust(8) for lo, hi in BUCKETS)
    print(f"{'model':6} {'mode':5} {'thr':4} {'conf':4} | all vehicles found: {head} | car ahead: {head} | invented/100")
    for m in a.models.split(","):
        for mode in ("base", "alt", "both"):
            for thr in (float(t) for t in a.thr.split(",")):
                for confirm in (False, True):
                    F = defaultdict(lambda: [0, 0]); A = defaultdict(lambda: [0, 0]); inv = 0; n = 0
                    for d in drives:
                        p = os.path.join(a.dir, "det", f"{d}.{m}.jsonl")
                        if not os.path.exists(p): continue
                        f, ah, i, k = score(refs[d], load(p), d, mode, thr, confirm)
                        for b in f: F[b][0] += f[b][0]; F[b][1] += f[b][1]
                        for b in ah: A[b][0] += ah[b][0]; A[b][1] += ah[b][1]
                        inv += i; n += k
                    if not n: continue
                    cell = lambda D, b: (f"{100 * D[b][0] / D[b][1]:.0f}%/{D[b][1]}" if D[b][1] else "-").rjust(8)
                    print(f"{m:6} {mode:5} {thr:<4} {'yes' if confirm else 'no':4} | {'':19}"
                          + " ".join(cell(F, b) for b in range(len(BUCKETS))) + " | "
                          + " ".join(cell(A, b) for b in range(len(BUCKETS))) + f" | {100 * inv / n:6.1f}")


if __name__ == "__main__":
    main()
