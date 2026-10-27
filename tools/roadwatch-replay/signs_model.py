#!/usr/bin/env python3
"""The speed-limit sign classifier the companion ships (companion/src/main/assets/sign_faces.tflite): a small
network that says what a round red sign's face shows, from the 32 x 32 picture of the face SignReader cuts out.
It reads a face half the size ML Kit's text recognizer needs, in under a millisecond, and it knows the end-of-limit
signs and the signs that carry no number.

Trained on GTSRB (the German traffic sign benchmark, 43 classes; the Hugging Face copy tanganke/gtsrb as parquet),
plus every limit from 20 to 130 (France has 40, 90, 110 and 130, which Germany doesn't) written over real GTSRB limit
signs (the number wiped, a new one drawn: the ring, light and blur stay real) and drawn from scratch, plus crops of
road pictures that are no sign. The written-over signs are made for every number alike, so the look of a drawn number
tells the network nothing about which number it is. Tested on GTSRB's test split and on the faces the phone kept
(GET /signs), named by what ML Kit read.

Usage: python signs_model.py <train.parquet> <test.parquet> <out.tflite> [--faces dir] [--frames glob ...] [--epochs 20]
Needs: pip install tensorflow==2.18.* pandas pyarrow pillow numpy
"""
import argparse, glob, io, os, random, sys
import numpy as np
from PIL import Image, ImageDraw, ImageFilter, ImageFont, ImageEnhance

SIDE = 32
CLASSES = ["20", "30", "40", "50", "60", "70", "80", "90", "100", "110", "120", "130", "end", "other", "nothing"]
# GTSRB's 43 classes to ours.
GTSRB = {0: "20", 1: "30", 2: "50", 3: "60", 4: "70", 5: "80", 7: "100", 8: "120", 6: "end", 32: "end", 41: "end", 42: "end"}
DRAWN = ["20", "30", "40", "50", "60", "70", "80", "90", "100", "110", "120", "130"]
# Round red signs that carry a number but no speed: weight, height and width limits (a 3,5t at a village's entrance
# read as 110 on the phone, 2026-10-27). Written over real signs like the limits, into "other".
NOT_LIMITS = ["3,5t", "7,5t", "12t", "19t", "26t", "3,5 t", "2,5t", "3m", "4m", "2,6m", "3,2m", "2,2m", "5,5t", "10t"]
FONTS = [f for f in ["C:/Windows/Fonts/arialbd.ttf", "C:/Windows/Fonts/verdanab.ttf", "C:/Windows/Fonts/calibrib.ttf",
                     "/usr/share/fonts/truetype/dejavu/DejaVuSans-Bold.ttf"] if os.path.exists(f)]
# The phone cuts the face as SignReader.FACE of the ring's side: the number with a little red at the edges.
FACE = 0.74


def face_of(img, jitter=True):
    """The face of a sign picture that shows the whole sign with a margin, as the phone would cut it."""
    w, h = img.size
    side = min(w, h)
    share = random.uniform(0.55, 0.8) if jitter else 0.66
    s = side * share
    cx, cy = w / 2, h / 2
    if jitter:
        cx += random.uniform(-0.08, 0.08) * side
        cy += random.uniform(-0.08, 0.08) * side
    box = (cx - s / 2, cy - s / 2, cx + s / 2, cy + s / 2)
    return img.crop(tuple(int(round(v)) for v in box)).resize((SIDE, SIDE), Image.BILINEAR)


def rough(img):
    """What a camera does to a far sign: small, blurred, over- or under-exposed, tinted, noisy."""
    small = random.randint(14, 64)
    img = img.resize((small, small), Image.BILINEAR).resize((SIDE, SIDE), Image.BILINEAR)
    if random.random() < 0.5:
        img = img.filter(ImageFilter.GaussianBlur(random.uniform(0.3, 1.2)))
    img = ImageEnhance.Brightness(img).enhance(random.uniform(0.5, 1.5))
    img = ImageEnhance.Contrast(img).enhance(random.uniform(0.6, 1.4))
    a = np.asarray(img).astype(np.float32)
    tint = np.array([random.uniform(0.85, 1.15), random.uniform(0.85, 1.15), random.uniform(0.85, 1.15)])
    a = a * tint + np.random.normal(0, random.uniform(0, 12), a.shape)
    return Image.fromarray(np.clip(a, 0, 255).astype(np.uint8))


def drawn(number):
    """A French-style limit sign: a red ring, a light face, the number in a bold sans; then cut like the phone."""
    size = 160
    img = Image.new("RGB", (size, size), tuple(random.randint(40, 200) for _ in range(3)))
    d = ImageDraw.Draw(img)
    r = size * random.uniform(0.42, 0.48)
    cx = cy = size / 2
    red = (random.randint(150, 230), random.randint(0, 60), random.randint(0, 60))
    face = tuple(random.randint(190, 255) for _ in range(3))
    d.ellipse((cx - r, cy - r, cx + r, cy + r), fill=red)
    ring = r * random.uniform(0.14, 0.24)
    d.ellipse((cx - r + ring, cy - r + ring, cx + r - ring, cy + r - ring), fill=face)
    font = ImageFont.truetype(random.choice(FONTS), int(r * (1.05 if len(number) < 3 else 0.82)))
    bbox = d.textbbox((0, 0), number, font=font)
    tw, th = bbox[2] - bbox[0], bbox[3] - bbox[1]
    d.text((cx - tw / 2 - bbox[0], cy - th / 2 - bbox[1]), number, font=font, fill=(random.randint(0, 40),) * 3)
    img = img.rotate(random.uniform(-8, 8), resample=Image.BILINEAR)
    s = 2 * r * FACE * random.uniform(0.9, 1.15)
    jx, jy = random.uniform(-0.06, 0.06) * r, random.uniform(-0.06, 0.06) * r
    img = img.crop(tuple(int(round(v)) for v in (cx - s / 2 + jx, cy - s / 2 + jy, cx + s / 2 + jx, cy + s / 2 + jy)))
    return rough(img.resize((SIDE, SIDE), Image.BILINEAR))


def transplant(canvas, number):
    """[number] written over a real limit sign [canvas] (a GTSRB picture: the sign with a small margin): the number's
    patch filled with the face's own colour, the new number drawn in, then cut and roughened like the rest."""
    size = 96
    img = canvas.resize((size, size), Image.BILINEAR)
    a = np.asarray(img).astype(np.float32)
    cx = cy = size / 2
    yy, xx = np.mgrid[0:size, 0:size]
    rr = np.sqrt((xx - cx) ** 2 + (yy - cy) ** 2) / size
    # The face just outside the number: its colour, to wipe the number with.
    ring = (rr > 0.27) & (rr < 0.33)
    fill = np.median(a[ring], axis=0) if ring.any() else np.array([220, 220, 220])
    patch = rr < 0.29
    a[patch] = fill + np.random.normal(0, 4, a[patch].shape)
    img = Image.fromarray(np.clip(a, 0, 255).astype(np.uint8))
    d = ImageDraw.Draw(img)
    font = ImageFont.truetype(random.choice(FONTS), int(size * (0.5 if len(number) < 3 else 0.38) * random.uniform(0.9, 1.1)))
    bbox = d.textbbox((0, 0), number, font=font)
    tw, th = bbox[2] - bbox[0], bbox[3] - bbox[1]
    dark = int(max(0, fill.mean() - random.uniform(150, 210)))
    d.text((cx - tw / 2 - bbox[0] + random.uniform(-2, 2), cy - th / 2 - bbox[1] + random.uniform(-2, 2)), number, font=font, fill=(dark, dark, dark))
    img = img.rotate(random.uniform(-6, 6), resample=Image.BILINEAR)
    return rough(face_of(img))


def load_parquet(path):
    import pandas as pd
    df = pd.read_parquet(path)
    for _, row in df.iterrows():
        yield Image.open(io.BytesIO(row["image"]["bytes"])).convert("RGB"), int(row["label"])


def build(train_parquet, frames, per_drawn, nothing_n):
    X, Y = [], []
    canvases = []
    for img, label in load_parquet(train_parquet):
        cls = GTSRB.get(label, "other")
        X.append(np.asarray(rough(face_of(img))))
        Y.append(CLASSES.index(cls))
        if cls.isdigit() and min(img.size) >= 40:
            canvases.append(img)
    for number in DRAWN:
        for _ in range(per_drawn):
            X.append(np.asarray(transplant(random.choice(canvases), number)))
            Y.append(CLASSES.index(number))
        for _ in range(per_drawn // 3):
            X.append(np.asarray(drawn(number)))
            Y.append(CLASSES.index(number))
    for text in NOT_LIMITS:
        for _ in range(per_drawn // 5):
            X.append(np.asarray(transplant(random.choice(canvases), text)))
            Y.append(CLASSES.index("other"))
        for _ in range(per_drawn // 12):
            X.append(np.asarray(drawn(text)))
            Y.append(CLASSES.index("other"))
    files = [f for g in frames for f in glob.glob(g)]
    random.shuffle(files)
    for f in files[:400]:
        img = Image.open(f).convert("RGB")
        w, h = img.size
        for _ in range(max(1, nothing_n // 400)):
            s = random.randint(14, 90)
            x, y = random.randint(0, w - s), random.randint(0, h - s)
            X.append(np.asarray(img.crop((x, y, x + s, y + s)).resize((SIDE, SIDE), Image.BILINEAR)))
            Y.append(CLASSES.index("nothing"))
    return np.stack(X).astype(np.float32) / 255, np.array(Y)


def phone_faces(dir):
    """The faces the phone kept, by what ML Kit read: 90 (and its upside-down reads), 40, 3.5t (other), and the
    ones it read nothing on (give-way triangles, lights: no limit; labelled -1)."""
    X, Y = [], []
    for f in glob.glob(os.path.join(dir, "*.png")):
        read = os.path.basename(f)[:-4].split("-")[-1]
        cls = {"90": "90", "06": "90", "O6": "90", "9C": "90", "40": "40", "404": "40", "35t": "other", "351": "other", "35": "other", "nothing": None}.get(read, "")
        if cls == "":
            continue
        X.append(np.asarray(Image.open(f).convert("RGB").resize((SIDE, SIDE), Image.BILINEAR)))
        Y.append(CLASSES.index(cls) if cls else -1)
    return (np.stack(X).astype(np.float32) / 255, np.array(Y)) if X else (None, None)


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("train"); ap.add_argument("test"); ap.add_argument("out")
    ap.add_argument("--faces"); ap.add_argument("--frames", nargs="*", default=[])
    ap.add_argument("--epochs", type=int, default=20)
    ap.add_argument("--per-drawn", type=int, default=2500)
    a = ap.parse_args()
    random.seed(1); np.random.seed(1)
    print("building the training set", flush=True)
    X, Y = build(a.train, a.frames, a.per_drawn, 4000)
    print("train", X.shape, np.bincount(Y, minlength=len(CLASSES)), flush=True)
    Xt, Yt = [], []
    for img, label in load_parquet(a.test):
        Xt.append(np.asarray(face_of(img, jitter=False)))
        Yt.append(CLASSES.index(GTSRB.get(label, "other")))
    Xt, Yt = np.stack(Xt).astype(np.float32) / 255, np.array(Yt)

    import tensorflow as tf
    tf.random.set_seed(1)
    L = tf.keras.layers
    model = tf.keras.Sequential([
        L.Input((SIDE, SIDE, 3)),
        L.Conv2D(32, 3, padding="same"), L.BatchNormalization(), L.ReLU(), L.MaxPool2D(),
        L.Conv2D(64, 3, padding="same"), L.BatchNormalization(), L.ReLU(), L.MaxPool2D(),
        L.Conv2D(128, 3, padding="same"), L.BatchNormalization(), L.ReLU(), L.MaxPool2D(),
        L.Conv2D(128, 3, padding="same"), L.BatchNormalization(), L.ReLU(),
        L.GlobalAveragePooling2D(), L.Dropout(0.3), L.Dense(len(CLASSES), activation="softmax"),
    ])
    steps = (len(X) + 255) // 256 * a.epochs
    model.compile(optimizer=tf.keras.optimizers.Adam(tf.keras.optimizers.schedules.CosineDecay(1e-3, steps, alpha=0.01)),
                  loss="sparse_categorical_crossentropy", metrics=["accuracy"])
    model.fit(X, Y, batch_size=256, epochs=a.epochs, validation_data=(Xt, Yt), verbose=2)
    loss, acc = model.evaluate(Xt, Yt, verbose=0)
    print(f"GTSRB test: {acc * 100:.2f} %")
    # The limits alone: what the car acts on.
    limits = np.array([i for i in range(len(CLASSES)) if CLASSES[i].isdigit()])
    sel = np.isin(Yt, limits)
    pred = model.predict(Xt[sel], verbose=0).argmax(1)
    print(f"GTSRB limits ({sel.sum()} signs): {(pred == Yt[sel]).mean() * 100:.2f} % right, {(np.isin(pred, limits) & (pred != Yt[sel])).mean() * 100:.2f} % read as another limit")
    if a.faces:
        Xf, Yf = phone_faces(a.faces)
        if Xf is not None:
            p = model.predict(Xf, verbose=0)
            pred = p.argmax(1)
            sure = p.max(1) >= 0.8
            known = Yf >= 0
            print(f"phone faces ({known.sum()} known): {(pred[known] == Yf[known]).mean() * 100:.1f} % right; sure (>=0.8) on {sure[known].mean() * 100:.0f} %, of which {(pred[known & sure] == Yf[known & sure]).mean() * 100:.1f} % right")
            for c in sorted(set(Yf)):
                m = Yf == c
                name = CLASSES[c] if c >= 0 else "no limit"
                print(f"  {name:>8}: {m.sum():3d} faces, read as " + ", ".join(f"{CLASSES[k]} x{(pred[m] == k).sum()}" for k in sorted(set(pred[m]))))
            nol = Yf < 0
            if nol.any():
                false_limit = np.isin(pred[nol], limits) & sure[nol]
                print(f"  no-limit faces read as a sure limit: {false_limit.sum()} of {nol.sum()}")
    converter = tf.lite.TFLiteConverter.from_keras_model(model)
    tflite = converter.convert()
    open(a.out, "wb").write(tflite)
    print(f"wrote {a.out}: {len(tflite)} bytes; classes: {CLASSES}")


if __name__ == "__main__":
    main()
