# -*- coding: utf-8 -*-
"""Generate KaibaCorp Battle City Duel Disk texture + item model.

Geometry/display based on user-provided Gemini JSON:
  C:\\Users\\Administrator\\Downloads\\gemini-code-1792809622875.json
("KaibaCorp Battle City Duel Disk - Custom Minecraft Model").

Textures namespaced to ygomc:item/duel_disk. texture_size [64,64] keeps
Gemini UV coordinates (0-64 pixel space). DuelDiskItem logic unchanged.
"""
from PIL import Image, ImageDraw
import json
import os
import shutil

ROOT = r"E:\miemod\ygo\Yu-Gi-Oh in Minecraft"
BASE = os.path.join(ROOT, r"common\src\main\resources\assets\ygomc")
TEX_PATH = os.path.join(BASE, "textures", "item", "duel_disk.png")
MODEL_PATH = os.path.join(BASE, "models", "item", "duel_disk.json")
NOTE_PATH = os.path.join(ROOT, r".agent\duel_disk_model.md")
GEMINI_SRC = r"C:\Users\Administrator\Downloads\gemini-code-1792809622875.json"
GEMINI_COPY = os.path.join(ROOT, r".agent\previews\gemini-duel-disk-source.json")

# Palette (anime Battle City / Standard Duel Disk)
C_SILVER = (175, 182, 192, 255)
C_SILVER_LT = (220, 225, 232, 255)
C_SILVER_DK = (115, 122, 132, 255)
C_WHITE = (245, 247, 250, 255)
C_BLUE = (45, 95, 215, 255)
C_BLUE_DK = (28, 60, 155, 255)
C_RED = (205, 38, 42, 255)
C_RED_DK = (145, 22, 28, 255)
C_BROWN = (125, 78, 42, 255)
C_BROWN_DK = (85, 52, 28, 255)
C_CUFF = (55, 60, 70, 255)
C_CUFF_LT = (85, 92, 105, 255)
C_BLACK = (18, 20, 26, 255)
C_TRANS = (0, 0, 0, 0)

W = 64
img = Image.new("RGBA", (W, W), C_TRANS)
draw = ImageDraw.Draw(img)


def fill(x0, y0, x1, y1, c):
    x0, x1 = int(round(x0)), int(round(x1))
    y0, y1 = int(round(y0)), int(round(y1))
    if x1 <= x0 or y1 <= y0:
        return
    draw.rectangle([x0, y0, x1 - 1, y1 - 1], fill=c)


def outline(x0, y0, x1, y1, c):
    x0, x1 = int(round(x0)), int(round(x1))
    y0, y1 = int(round(y0)), int(round(y1))
    if x1 <= x0 or y1 <= y0:
        return
    draw.rectangle([x0, y0, x1 - 1, y1 - 1], outline=c)


# --- Atlas regions matching Gemini UVs (texture_size 64) ---
# core_base sides [0,0,6,2.5], up [0,0,6,6], down [0,6,6,12]
fill(0, 0, 6, 6, C_SILVER)
outline(0, 0, 6, 6, C_SILVER_DK)
fill(1, 1, 5, 5, C_SILVER_LT)
fill(0, 6, 6, 12, C_SILVER_DK)
outline(0, 6, 6, 12, C_BLACK)

# lp_counter: north [6,0,11,2.5] red, east/west [6,2.5,10,5], south [11,0,16,2.5], up [6,5,11,9]
fill(6, 0, 11, 3, C_RED)
fill(6, 1, 11, 2, C_RED_DK)
fill(7, 1, 10, 2, C_BLACK)  # LP display hint
fill(11, 0, 16, 3, C_RED_DK)
fill(6, 3, 10, 5, C_RED)
fill(6, 5, 11, 9, C_RED)
outline(6, 5, 11, 9, C_RED_DK)
fill(7, 6, 10, 8, C_BLACK)

# deck_holder brown [12,6,16,13]
fill(12, 6, 16, 9, C_BROWN)
fill(12, 9, 16, 13, C_BROWN_DK)
outline(12, 6, 16, 13, C_BLACK)
fill(13, 10, 15, 12, C_BLACK)

# graveyard [0,12,6,14]
fill(0, 12, 3, 14, C_SILVER_DK)
fill(3, 12, 6, 14, C_SILVER)
outline(0, 12, 6, 14, C_BLACK)

# blade strip / faces: edges [0,16,16,17] [0,17,4,18], up [0,18,16,22], down [0,22,16,26]
fill(0, 16, 16, 17, C_SILVER_LT)
fill(0, 17, 4, 18, C_SILVER)
fill(0, 18, 16, 22, C_WHITE)
outline(0, 18, 16, 22, C_SILVER)
# subtle zone guide lines on blade top
for gx in (3, 6, 9, 12):
    fill(gx, 18, gx + 1, 22, C_SILVER_LT)
fill(0, 22, 16, 26, C_SILVER)
outline(0, 22, 16, 26, C_SILVER_DK)

# blade tip [0,26,3.5,27] [4,26,7.5,29.5]
fill(0, 26, 4, 27, C_WHITE)
fill(4, 26, 8, 30, C_WHITE)
outline(4, 26, 8, 30, C_SILVER)

# monster zones: edge [16,0,20,0.5], up [16,1,19,5]
fill(16, 0, 20, 1, C_BLUE_DK)
fill(16, 1, 19, 5, C_BLUE)
outline(16, 1, 19, 5, C_BLUE_DK)
# red triangle hint near bottom of zone face
fill(17, 4, 18, 5, C_RED)
fill(16, 3, 19, 4, C_RED)

# arm cuff dark [20,0,24,3.5] [20,4,26,7.5] [20,8,24,14]
fill(20, 0, 24, 4, C_CUFF)
outline(20, 0, 24, 4, C_BLACK)
fill(20, 4, 26, 8, C_CUFF_LT)
outline(20, 4, 26, 8, C_CUFF)
fill(20, 8, 24, 14, C_CUFF)
outline(20, 8, 24, 14, C_BLACK)
# gear-ish stripes
for gy in range(8, 14, 2):
    fill(21, gy, 23, gy + 1, C_BLACK)

os.makedirs(os.path.dirname(TEX_PATH), exist_ok=True)
img.save(TEX_PATH, "PNG")
print("Wrote texture", TEX_PATH, img.size)

# Load Gemini geometry/display, namespace textures
with open(GEMINI_SRC, "r", encoding="utf-8") as f:
    model = json.load(f)

os.makedirs(os.path.dirname(GEMINI_COPY), exist_ok=True)
shutil.copy2(GEMINI_SRC, GEMINI_COPY)

model["credit"] = (
    "KaibaCorp Battle City Duel Disk - Custom Minecraft Model "
    "(from user Gemini JSON; textures ygomc)"
)
model["texture_size"] = [64, 64]
model["textures"] = {
    "0": "ygomc:item/duel_disk",
    "particle": "ygomc:item/duel_disk",
}

# Keep Gemini elements + display (arm-mount thirdperson)
assert len(model["elements"]) == 12, len(model["elements"])
tp = model["display"]["thirdperson_righthand"]
assert tp["rotation"] == [90, 0, -90]
assert tp["translation"] == [-2, -1, 2.5]

os.makedirs(os.path.dirname(MODEL_PATH), exist_ok=True)
with open(MODEL_PATH, "w", encoding="utf-8") as f:
    json.dump(model, f, indent="\t", ensure_ascii=False)
    f.write("\n")
print("Wrote model elements=", len(model["elements"]))

for e in model["elements"]:
    fr, to = e["from"], e["to"]
    assert all(fr[i] < to[i] for i in range(3)), e["name"]
print("Bounds OK")

note = """# Duel Disk item model

**KaibaCorp Battle City / Standard Duel Disk** — geometry from user-provided Gemini JSON
(`Downloads/gemini-code-1792809622875.json`, credit: "KaibaCorp Battle City Duel Disk - Custom Minecraft Model").

- Model: `assets/ygomc/models/item/duel_disk.json` — vanilla JSON `elements` (no GeckoLib).
- Texture: `assets/ygomc/textures/item/duel_disk.png` — 64×64 atlas; `texture_size: [64,64]` (Gemini UV pixel space).
- Textures namespaced: `ygomc:item/duel_disk`.
- 12 elements: `core_base`, `lp_counter_housing` (22.5° x), `deck_holder_right`, `graveyard_slot_front`,
  `blade_main_wing`, `blade_left_tip` (-22.5° y), `monster_zone_1..5`, `arm_cuff_mount`.
- Display: thirdperson_righthand arm-mount `[90,0,-90]` / `[-2,-1,2.5]` / `0.85` (kept from Gemini).
- Behavior of `DuelDiskItem` unchanged — visuals only.
- Regen: `python tools/gen_duel_disk_model.py` (reads Gemini source path above).

## Verify in-game
1. `/give @s ygomc:duel_disk` (or creative inventory)
2. Check inventory icon (gui display) shows hub + blade + cuff
3. Hold in hand — blade should extend along the forearm (arm-mount style)
"""
with open(NOTE_PATH, "w", encoding="utf-8") as f:
    f.write(note)
print("Wrote note", NOTE_PATH)
print("DONE")
