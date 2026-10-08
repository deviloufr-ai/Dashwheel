"""Builds the Dashwheel marketing slides as HTML pages from the captures in raw/ (see README.md)."""
import pathlib

ROOT = pathlib.Path(__file__).parent
SHOTS = (ROOT / "raw").as_uri()

LOGO = """<svg viewBox="0 0 108 108" width="44" height="44"><rect width="108" height="108" rx="26" fill="#16181e"/>
<path d="M30,68 A30,30 0 1 1 78,68" stroke="#81D4FA" stroke-width="6" fill="none" stroke-linecap="round"/>
<circle cx="54" cy="54" r="7" fill="#6750A4"/><path d="M54,34 L65,70 L54,62 L43,70 Z" fill="#fff"/></svg>"""

CSS = """
*{box-sizing:border-box;margin:0;padding:0}
html,body{width:1920px;height:1080px;overflow:hidden}
body{font-family:'Manrope',sans-serif;color:#f3f5fa;background:#0b0d12;position:relative}
.bg{position:absolute;inset:0;background:
  radial-gradient(900px 520px at 18% 8%, rgba(129,212,250,.18), transparent 70%),
  radial-gradient(900px 600px at 88% 20%, rgba(197,138,249,.20), transparent 70%),
  radial-gradient(1200px 600px at 50% 110%, rgba(103,80,164,.35), transparent 70%),
  linear-gradient(180deg,#0d1017 0%,#090a0f 100%)}
.brand{position:absolute;left:64px;top:52px;display:flex;align-items:center;gap:14px;font-weight:700;font-size:26px;letter-spacing:.02em}
.brand svg{border-radius:12px;box-shadow:0 0 0 1px rgba(255,255,255,.08)}
.copy{position:absolute;left:0;right:0;top:58px;text-align:center}
.eyebrow{display:inline-block;font-size:18px;font-weight:700;letter-spacing:.22em;text-transform:uppercase;
  background:linear-gradient(90deg,#81D4FA,#C58AF9);-webkit-background-clip:text;color:transparent}
h1{font-size:68px;line-height:1.05;font-weight:800;letter-spacing:-.02em;margin-top:14px}
p.sub{font-size:26px;line-height:1.4;color:#aeb5c4;margin:16px auto 0;max-width:1180px;font-weight:500}
.device{position:absolute;padding:16px;border-radius:32px;
  background:linear-gradient(160deg,#30333c 0%,#17181d 45%,#0c0d10 100%);
  box-shadow:0 50px 100px rgba(0,0,0,.6),0 0 0 1px rgba(255,255,255,.06),inset 0 1px 0 rgba(255,255,255,.12)}
.device img{display:block;border-radius:12px}
.device::after{content:"";position:absolute;inset:16px;border-radius:12px;pointer-events:none;
  background:linear-gradient(125deg,rgba(255,255,255,.07) 0%,rgba(255,255,255,0) 32%)}
.label{position:absolute;font-size:22px;font-weight:700;letter-spacing:.14em;text-transform:uppercase;color:#d8dcea;text-align:center}
.chips{position:absolute;left:0;right:0;display:flex;justify-content:center;gap:14px}
.chip{padding:10px 20px;border-radius:999px;background:rgba(255,255,255,.06);border:1px solid rgba(255,255,255,.10);font-size:20px;font-weight:600;color:#d8dcea}
"""


def page(body: str) -> str:
    return f"""<!doctype html><html><head><meta charset="utf-8">
<link href="https://fonts.googleapis.com/css2?family=Manrope:wght@500;600;700;800&display=swap" rel="stylesheet">
<style>{CSS}</style></head><body><div class="bg"></div><div class="brand">{LOGO}Dashwheel</div>{body}
<script>document.fonts.ready.then(()=>Promise.all([...document.images].map(i=>i.decode().catch(()=>{{}})))).then(()=>document.title='READY')</script>
</body></html>"""


def copy(eyebrow, h1, sub):
    return f'<div class="copy"><div class="eyebrow">{eyebrow}</div><h1>{h1}</h1><p class="sub">{sub}</p></div>'


def device(shot, left, top, scale=1.0, extra=""):
    w, h = round(1280 * scale), round(720 * scale)
    return f'<div class="device" style="left:{left}px;top:{top}px;{extra}"><img src="{SHOTS}/{shot}.png" width="{w}" height="{h}"></div>'


def single(eyebrow, h1, sub, shot, chips=None):
    # 1280x720 screen at native pixels, centred, bleeding slightly off the bottom edge.
    top = 318
    body = copy(eyebrow, h1, sub) + device(shot, (1920 - 1312) // 2, top)
    if chips:
        body = body.replace('<p class="sub">', '<p class="sub">', 1)
    return page(body)


SLIDES = {}

SLIDES["01_hero"] = single(
    "The car launcher for Android head units",
    "Your car. Your dashboard.",
    "Map, music and live car data on one calm screen, the way your head unit should have shipped.",
    "hero_AUTO_DARK")

SLIDES["02_live_car_data"] = single(
    "Live car data",
    "See what your car is doing.",
    "Speed, revs, fuel, battery, doors and particle filter, live from a Bluetooth OBD adapter.",
    "car_AUTO_DARK")

SLIDES["03_ai_mechanic"] = single(
    "AI mechanic",
    "A mechanic in the passenger seat.",
    "Warning light on? Dashwheel reads the fault code and says, in plain words, what to check.",
    "ai_AUTO_DARK")

SLIDES["04_road_trip"] = single(
    "Made for the long drive",
    "Every stop, planned.",
    "Next turn from Google Maps or Waze, break reminders, eco-driving score and fuel prices nearby.",
    "trip_AUTO_DARK")

SLIDES["05_widget_designs"] = single(
    "60+ widget designs",
    "Give every tile its own face.",
    "Flip clocks, twin dials, shift lights, neon gauges. Pick a design for each widget.",
    "designs_AUTO_DARK")

# Four skins, 2x2 at 0.56 scale.
s = 0.45
w, h = round(1280 * s) + 32, round(720 * s) + 32
gap_x, gap_y = 56, 56
x0 = (1920 - (2 * w + gap_x)) // 2
y0 = 318
skins = [("skin_ORBIT_DARK", "Orbit"), ("skin_COCKPIT_DARK", "Cockpit"), ("skin_HORIZON_DARK", "Horizon"), ("skin_TAPE_DECK_DARK", "Tape Deck")]
body = copy("Four complete looks", "Not just a colour. A whole new dashboard.",
            "Orbit, Cockpit, Horizon and Tape Deck restyle every gauge, bar and button.")
y0 = 268
for i, (shot, name) in enumerate(skins):
    x = x0 + (i % 2) * (w + gap_x)
    y = y0 + (i // 2) * (h + gap_y)
    body += device(shot, x, y, s)
    body += f'<div class="label" style="left:{x}px;width:{w}px;top:{y + h + 10}px;font-size:17px">{name}</div>'
SLIDES["06_skins"] = page(body)

# Customise: edit mode large, templates dialog overlapping on the right.
body = copy("Seven dashboards", "Arranged your way.",
            "Seven swipeable dashboards. Drag, resize, add widgets or start from a template.")
sc = 0.62
cw = round(1280 * sc) + 32
cx = (1920 - (2 * cw + 60)) // 2
body += device("ui_add", cx, 330, sc)
body += device("ui_templates", cx + cw + 60, 330, sc)
for i, name in enumerate(["See each widget before adding it", "Start from a template"]):
    body += f'<div class="label" style="left:{cx + i * (cw + 60)}px;width:{cw}px;top:{330 + round(720 * sc) + 32 + 22}px;font-size:17px">{name}</div>'
body += '<div class="chips" style="top:{}px">'.format(330 + round(720 * sc) + 32 + 110) + "".join(f'<div class="chip">{c}</div>' for c in ["7 dashboards", "30+ widgets", "60+ designs", "14 themes", "8 languages"]) + "</div>"
SLIDES["07_customise"] = page(body)

# Day and night: one screen, split diagonally.
body = copy("Day and night", "Easy on the eyes, day and night.",
            "Every theme comes in light and dark, and follows your car's day and night mode.")
body += f"""<div class="device" style="left:{(1920-1312)//2}px;top:318px">
<div style="position:relative;width:1280px;height:720px;border-radius:12px;overflow:hidden">
<img src="{SHOTS}/hero_AUTO_LIGHT.png" width="1280" height="720" style="position:absolute;inset:0;border-radius:0">
<img src="{SHOTS}/hero_AUTO_DARK.png" width="1280" height="720" style="position:absolute;inset:0;border-radius:0;clip-path:polygon(0 0,58% 0,42% 100%,0 100%)">
<div style="position:absolute;inset:0;background:linear-gradient(to right,transparent calc(50% - 1px),rgba(255,255,255,.9) 50%,transparent calc(50% + 1px));clip-path:polygon(57.8% 0,58.2% 0,42.2% 100%,41.8% 100%);background:#fff"></div>
</div></div>"""
SLIDES["08_day_night"] = page(body)


for key, shot, name, h1, sub in [
    ("09_skin_orbit", "skin_ORBIT_DARK", "Orbit skin", "Built around the driver.", "Round gauges, a spinning record and big controls within reach."),
    ("10_skin_cockpit", "skin_COCKPIT_DARK", "Cockpit skin", "Chrome dials, amber glow.", "The warmth of a classic instrument panel, on your centre screen."),
    ("11_skin_horizon", "skin_HORIZON_DARK", "Horizon skin", "A sky that keeps time.", "Dawn, noon, dusk and night: the backdrop follows the clock as you drive."),
    ("12_skin_tape_deck", "skin_TAPE_DECK_DARK", "Tape Deck skin", "Press play on the eighties.", "A cassette, VU meters and LCD digits for every road trip."),
]:
    SLIDES[key] = single(name, h1, sub, shot)

SLIDES["13_calls"] = single(
    "Calls", "Calls on the big screen.",
    "Phone and WhatsApp calls from your paired phone: answer or decline in one tap.",
    "call_overlay")

body = copy("Wide or upright", "Made for your screen's shape.",
            "Upright, Tesla-style screens get their own layout, in every theme and skin.")
vs = 0.68
vw, vh = round(768 * vs) + 32, round(1024 * vs) + 32
vx = (1920 - (2 * vw + 80)) // 2
for i, shot in enumerate(["vert_default", "vert_orbit"]):
    body += f'<div class="device" style="left:{vx + i * (vw + 80)}px;top:300px"><img src="{SHOTS}/{shot}.png" width="{vw - 32}" height="{vh - 32}"></div>'
SLIDES["14_upright"] = page(body)


SLIDES["15_canvas"] = single(
    "Canvas look", "The map is the dashboard.",
    "A full-screen 3D map with your next turn, arrival time, speed and music floating on top.",
    "canvas_DARK")

body = copy("Canvas, day and night", "Clear at noon. Calm at midnight.",
            "The map and every card switch with the light, so the screen never dazzles you after dark.")
body += f"""<div class="device" style="left:{(1920-1312)//2}px;top:318px">
<div style="position:relative;width:1280px;height:720px;border-radius:12px;overflow:hidden">
<img src="{SHOTS}/canvas_LIGHT.png" width="1280" height="720" style="position:absolute;inset:0;border-radius:0">
<img src="{SHOTS}/canvas_DARK.png" width="1280" height="720" style="position:absolute;inset:0;border-radius:0;clip-path:polygon(0 0,58% 0,42% 100%,0 100%)">
<div style="position:absolute;inset:0;clip-path:polygon(57.8% 0,58.2% 0,42.2% 100%,41.8% 100%);background:#fff"></div>
</div></div>"""
SLIDES["16_canvas_day_night"] = page(body)

SLIDES["17_upkeep"] = single(
    "Servicing and AI mechanic", "Knows your car. Remembers the service.",
    "Your car's specs, fault codes in plain words, and a heads-up before each service.",
    "upkeep_AUTO_DARK")


def grid(eyebrow, h1, sub, items, cols, sc, gap=40, top=272):
    """Screens in a grid with a label under each: items = [(shot, label)]."""
    w, h = round(1280 * sc) + 32, round(720 * sc) + 32
    x0 = (1920 - (cols * w + (cols - 1) * gap)) // 2
    body = copy(eyebrow, h1, sub)
    for i, (shot, name) in enumerate(items):
        x = x0 + (i % cols) * (w + gap)
        y = top + (i // cols) * (h + 62)
        body += device(shot, x, y, sc)
        body += f'<div class="label" style="left:{x}px;width:{w}px;top:{y + h + 12}px;font-size:17px">{name}</div>'
    return page(body)


SLIDES["18_skins_six"] = grid(
    "Whole-dashboard skins", "One app. Six personalities.",
    "Each skin redraws every gauge, card and button, not just the colours.",
    [("sk_CANVAS", "Canvas"), ("sk_ORBIT", "Orbit"), ("sk_COCKPIT", "Cockpit"),
     ("sk_HORIZON", "Horizon"), ("sk_TAPE_DECK", "Tape Deck"), ("sk_CYBER_SPORT", "Cyber Sport")], 3, 0.42)

SLIDES["19_colour_themes"] = grid(
    "Colour themes", "Match it to your interior.",
    "Calm blues, sporty reds, warm gold or soft green. Switch in one tap, day or night.",
    [("sk_AURORA", "Aurora"), ("sk_SPORTY", "Sporty"), ("sk_LUXURY", "Luxury"),
     ("sk_ECO_LEAF", "Eco Leaf"), ("sk_NEON_DARK", "Neon"), ("sk_NORDIC", "Nordic")], 3, 0.42)

SLIDES["20_arrangements"] = grid(
    "Your widgets, your way", "Build the dashboard you need.",
    "Big map, car data, road trip or daily drive: drag, resize and mix 50+ widgets on seven dashboards.",
    [("lay_bigmap", "Map first"), ("lay_car", "Car data"), ("lay_trip", "Road trip"), ("lay_glance", "Daily drive")], 2, 0.42, gap=56, top=268)

out = ROOT / "out" / "html"
out.mkdir(parents=True, exist_ok=True)
import sys
only = sys.argv[1:]
for name, html in SLIDES.items():
    if only and not any(name.startswith(o) for o in only):
        continue
    (out / f"{name}.html").write_text(html, encoding="utf-8")
    print(name)
