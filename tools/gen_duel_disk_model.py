# -*- coding: utf-8 -*-
"""Generate classic anime Duel Disk Minecraft item texture + JSON model.
UVs use vanilla 0-16 space (full texture = 16 units) so a 64x64 atlas works without texture_size.
"""
from PIL import Image
import json, os

BASE = r"E:\miemod\ygo\Yu-Gi-Oh in Minecraft\common\src\main\resources\assets\ygomc"
TEX_PATH = os.path.join(BASE, "textures", "item", "duel_disk.png")
MODEL_PATH = os.path.join(BASE, "models", "item", "duel_disk.json")
NOTE_PATH = r"E:\miemod\ygo\Yu-Gi-Oh in Minecraft\.agent\duel_disk_model.md"

C_WHITE = (245, 245, 250, 255)
C_SILVER = (168, 175, 185, 255)
C_SILVER_DK = (110, 118, 128, 255)
C_SILVER_LT = (210, 215, 222, 255)
C_RED = (200, 35, 40, 255)
C_RED_DK = (140, 20, 25, 255)
C_BLUE = (40, 90, 210, 255)
C_BLUE_DK = (25, 55, 150, 255)
C_BROWN = (120, 75, 40, 255)
C_BROWN_DK = (80, 50, 25, 255)
C_CUFF = (70, 85, 105, 255)
C_CUFF_LT = (95, 110, 130, 255)
C_BLACK = (20, 22, 28, 255)
C_TRANS = (0, 0, 0, 0)

W = 64
img = Image.new("RGBA", (W, W), C_TRANS)
px = img.load()

def fill(x0, y0, x1, y1, c):
    for y in range(y0, y1):
        for x in range(x0, x1):
            px[x, y] = c

def rect_outline(x0, y0, x1, y1, c):
    for x in range(x0, x1):
        px[x, y0] = c
        px[x, y1 - 1] = c
    for y in range(y0, y1):
        px[x0, y] = c
        px[x1 - 1, y] = c

# Swatches 8x8
fill(0, 0, 8, 8, C_SILVER)
fill(0, 0, 8, 1, C_SILVER_LT)
fill(0, 7, 8, 8, C_SILVER_DK)
fill(8, 0, 16, 8, C_WHITE)
fill(8, 0, 16, 1, C_SILVER_LT)
fill(16, 0, 24, 8, C_BLUE)
fill(16, 0, 24, 1, C_BLUE_DK)
fill(16, 7, 24, 8, C_BLUE_DK)
fill(24, 0, 32, 8, C_RED)
fill(24, 0, 32, 1, C_RED_DK)
fill(32, 0, 40, 8, C_BROWN)
fill(32, 7, 40, 8, C_BROWN_DK)
fill(40, 0, 48, 8, C_CUFF)
fill(40, 0, 48, 1, C_CUFF_LT)
fill(48, 0, 56, 8, C_SILVER_DK)
fill(56, 0, 64, 8, C_SILVER_LT)

# Hub face 16x16 at (0,16)
fill(0, 16, 16, 32, C_SILVER)
rect_outline(0, 16, 16, 32, C_SILVER_DK)
rect_outline(1, 17, 15, 31, C_SILVER_LT)
fill(4, 19, 12, 29, C_RED)
fill(5, 20, 11, 28, C_RED)
fill(6, 21, 10, 24, C_RED_DK)
fill(7, 22, 9, 23, C_BLACK)

# Zone face 8x8 at (16,16)
fill(16, 16, 24, 24, C_BLUE)
for dy, xs in [(0, range(19, 20)), (1, range(18, 21)), (2, range(17, 22))]:
    for x in xs:
        px[x, 17 + dy] = C_RED

# Trim strip
fill(24, 16, 40, 20, C_SILVER_LT)
fill(24, 19, 40, 20, C_SILVER_DK)

# Deck slot face
fill(40, 16, 48, 28, C_BROWN)
fill(41, 17, 47, 27, C_BROWN_DK)
fill(42, 18, 46, 20, C_BLACK)

img.save(TEX_PATH, "PNG")
print("Wrote texture", TEX_PATH)

def uvp(x0, y0, x1, y1):
    """Pixel rect on 64x64 -> vanilla UV 0-16 (full tex = 16)."""
    s = 16.0 / 64.0
    return [round(x0 * s, 4), round(y0 * s, 4), round(x1 * s, 4), round(y1 * s, 4)]

UV_SILVER = uvp(0, 0, 8, 8)
UV_WHITE = uvp(8, 0, 16, 8)
UV_BLUE = uvp(16, 0, 24, 8)
UV_RED = uvp(24, 0, 32, 8)
UV_BROWN = uvp(32, 0, 40, 8)
UV_CUFF = uvp(40, 0, 48, 8)
UV_SILVER_DK = uvp(48, 0, 56, 8)
UV_SILVER_LT = uvp(56, 0, 64, 8)
UV_HUB = uvp(0, 16, 16, 32)
UV_ZONE = uvp(16, 16, 24, 24)
UV_TRIM = uvp(24, 16, 40, 20)
UV_DECK = uvp(40, 16, 48, 28)

def faces_solid(u):
    return {s: {"uv": u, "texture": "#0"} for s in ("north", "east", "south", "west", "up", "down")}

def faces_hub_top():
    return {
        "north": {"uv": UV_SILVER, "texture": "#0"},
        "south": {"uv": UV_SILVER, "texture": "#0"},
        "east": {"uv": UV_SILVER, "texture": "#0"},
        "west": {"uv": UV_SILVER, "texture": "#0"},
        "up": {"uv": UV_HUB, "texture": "#0"},
        "down": {"uv": UV_SILVER_DK, "texture": "#0"},
    }

def faces_zone():
    return {
        "north": {"uv": UV_BLUE, "texture": "#0"},
        "south": {"uv": UV_BLUE, "texture": "#0"},
        "east": {"uv": UV_BLUE, "texture": "#0"},
        "west": {"uv": UV_BLUE, "texture": "#0"},
        "up": {"uv": UV_ZONE, "texture": "#0"},
        "down": {"uv": UV_BLUE, "texture": "#0"},
    }

def faces_deck():
    return {
        "north": {"uv": UV_DECK, "texture": "#0"},
        "south": {"uv": UV_DECK, "texture": "#0"},
        "east": {"uv": UV_BROWN, "texture": "#0"},
        "west": {"uv": UV_BROWN, "texture": "#0"},
        "up": {"uv": UV_BROWN, "texture": "#0"},
        "down": {"uv": UV_BROWN, "texture": "#0"},
    }

def faces_blade():
    return {
        "north": {"uv": UV_TRIM, "texture": "#0"},
        "south": {"uv": UV_TRIM, "texture": "#0"},
        "east": {"uv": UV_WHITE, "texture": "#0"},
        "west": {"uv": UV_WHITE, "texture": "#0"},
        "up": {"uv": UV_WHITE, "texture": "#0"},
        "down": {"uv": UV_SILVER_LT, "texture": "#0"},
    }

elements = []

elements.append({"name": "cuff", "from": [2.5, 5.5, 5.0], "to": [6.5, 7.0, 11.0], "faces": faces_solid(UV_CUFF)})
elements.append({"name": "hub_core", "from": [1.5, 7.0, 4.5], "to": [7.5, 9.0, 11.5], "faces": faces_hub_top()})
elements.append({"name": "hub_n", "from": [2.5, 7.15, 3.5], "to": [6.5, 8.85, 4.5], "faces": faces_solid(UV_SILVER)})
elements.append({"name": "hub_s", "from": [2.5, 7.15, 11.5], "to": [6.5, 8.85, 12.5], "faces": faces_solid(UV_SILVER)})
elements.append({"name": "hub_w", "from": [0.5, 7.15, 5.5], "to": [1.5, 8.85, 10.5], "faces": faces_solid(UV_SILVER)})
elements.append({"name": "hub_e_ring", "from": [7.5, 7.15, 5.5], "to": [8.0, 8.85, 10.5], "faces": faces_solid(UV_SILVER_LT)})
elements.append({"name": "hub_red_plate", "from": [3.0, 9.0, 6.0], "to": [6.0, 9.35, 10.0], "faces": faces_solid(UV_RED)})
elements.append({"name": "hub_sensor", "from": [4.0, 9.35, 7.5], "to": [5.0, 9.5, 8.5], "faces": faces_solid(UV_SILVER_DK)})
elements.append({"name": "deck_slot", "from": [2.5, 7.3, 12.0], "to": [6.5, 9.2, 14.0], "faces": faces_deck()})
elements.append({"name": "blade_main", "from": [7.5, 7.4, 3.0], "to": [20.5, 8.9, 13.0], "faces": faces_blade()})
elements.append({"name": "blade_under", "from": [8.0, 7.0, 4.0], "to": [19.5, 7.4, 12.0], "faces": faces_solid(UV_SILVER)})
elements.append({"name": "blade_trim_n", "from": [7.5, 8.7, 2.7], "to": [20.5, 9.05, 3.0], "faces": faces_solid(UV_SILVER_LT)})
elements.append({"name": "blade_trim_s", "from": [7.5, 8.7, 13.0], "to": [20.5, 9.05, 13.3], "faces": faces_solid(UV_SILVER_LT)})
elements.append({"name": "tip_n", "from": [20.5, 7.5, 3.2], "to": [22.5, 8.85, 7.0], "faces": faces_blade()})
elements.append({"name": "tip_s", "from": [20.5, 7.5, 9.0], "to": [22.5, 8.85, 12.8], "faces": faces_blade()})
elements.append({"name": "tip_n_point", "from": [22.5, 7.6, 3.8], "to": [23.5, 8.7, 6.5], "faces": faces_solid(UV_WHITE)})
elements.append({"name": "tip_s_point", "from": [22.5, 7.6, 9.5], "to": [23.5, 8.7, 12.2], "faces": faces_solid(UV_WHITE)})

zone_w, zone_gap = 1.9, 0.35
zone_z0, zone_z1 = 4.2, 11.8
start_x = 8.6
for i in range(5):
    x0 = start_x + i * (zone_w + zone_gap)
    x1 = x0 + zone_w
    elements.append({
        "name": f"zone_{i+1}",
        "from": [round(x0, 2), 8.55, zone_z0],
        "to": [round(x1, 2), 8.95, zone_z1],
        "faces": faces_zone(),
    })
    elements.append({
        "name": f"zone_{i+1}_tri",
        "from": [round(x0 + 0.15, 2), 8.95, 7.5],
        "to": [round(x0 + 0.55, 2), 9.1, 8.5],
        "faces": faces_solid(UV_RED),
    })

elements.append({"name": "arm_mount", "from": [3.0, 4.8, 6.5], "to": [6.0, 5.5, 9.5], "faces": faces_solid(UV_CUFF)})

model = {
    "credit": "ygomc — classic Standard Duel Disk (hub + 5-zone white blade)",
    "textures": {
        "0": "ygomc:item/duel_disk",
        "particle": "ygomc:item/duel_disk",
    },
    "elements": elements,
    "display": {
        "gui": {
            "rotation": [60, 45, 0],
            "translation": [0.5, -1.0, 0],
            "scale": [0.55, 0.55, 0.55],
        },
        "ground": {
            "rotation": [0, 0, 0],
            "translation": [0, 3, 0],
            "scale": [0.35, 0.35, 0.35],
        },
        "fixed": {
            "rotation": [0, 90, 0],
            "translation": [0, 0, -1],
            "scale": [0.5, 0.5, 0.5],
        },
        "thirdperson_righthand": {
            "rotation": [90, 0, 0],
            "translation": [0, -2.0, 2.5],
            "scale": [0.55, 0.55, 0.55],
        },
        "thirdperson_lefthand": {
            "rotation": [90, 0, 0],
            "translation": [0, -2.0, 2.5],
            "scale": [0.55, 0.55, 0.55],
        },
        "firstperson_righthand": {
            "rotation": [75, 5, -10],
            "translation": [1.5, 2.5, 0.5],
            "scale": [0.5, 0.5, 0.5],
        },
        "firstperson_lefthand": {
            "rotation": [75, 5, 10],
            "translation": [1.5, 2.5, 0.5],
            "scale": [0.5, 0.5, 0.5],
        },
        "head": {
            "rotation": [0, 90, 0],
            "translation": [0, 8, 0],
            "scale": [0.4, 0.4, 0.4],
        },
    },
}

with open(MODEL_PATH, "w", encoding="utf-8") as f:
    json.dump(model, f, indent=2, ensure_ascii=False)
    f.write("\n")
print("Wrote model elements=", len(elements))

# Validate element bounds / from < to
for e in elements:
    fr, to = e["from"], e["to"]
    assert all(fr[i] < to[i] for i in range(3)), e["name"]
print("Bounds OK")

note = """# Duel Disk item model

Classic anime **Standard Duel Disk** (Yugi): silver forearm hub + white blade with five blue monster zones.

- Model: `assets/ygomc/models/item/duel_disk.json` — vanilla JSON `elements` (no GeckoLib).
- Texture: `assets/ygomc/textures/item/duel_disk.png` — 64×64 atlas; UVs in vanilla 0–16 space.
- Silhouette: octagonal hub (red center plate, brown deck slot) → long white blade → V-notch tip; five recessed blue zones with tiny red triangle hints.
- Behavior of `DuelDiskItem` unchanged — visuals only.

## Verify in-game
1. `/give @s ygomc:duel_disk` (or creative inventory)
2. Check inventory icon (gui display) shows hub + blade
3. Hold in hand — blade should extend along the arm / outward
"""
with open(NOTE_PATH, "w", encoding="utf-8") as f:
    f.write(note)
print("DONE")
