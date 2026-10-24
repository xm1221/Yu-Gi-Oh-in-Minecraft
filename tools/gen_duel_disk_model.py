# -*- coding: utf-8 -*-
"""Generate the anime Battle City Duel Disk item model and texture atlas.

The model is vanilla Minecraft JSON only. Geometry stays in the item-model
coordinate range, while the 128x128 atlas gives the hub, five wing panels,
leather cuff, and metal trim separate readable details.
"""
from PIL import Image, ImageDraw
import json
import math
import os


ROOT = r"E:\miemod\ygo\Yu-Gi-Oh in Minecraft"
ASSETS = os.path.join(ROOT, r"common\src\main\resources\assets\ygomc")
TEX_PATH = os.path.join(ASSETS, "textures", "item", "duel_disk.png")
MODEL_PATH = os.path.join(ASSETS, "models", "item", "duel_disk.json")
NOTE_PATH = os.path.join(ROOT, r".agent\duel_disk_model.md")

TEXTURE_SIZE = 128

TRANSPARENT = (0, 0, 0, 0)
BLACK = (17, 21, 28, 255)
BLACK_SOFT = (32, 38, 48, 255)
METAL = (70, 82, 98, 255)
METAL_LIGHT = (116, 132, 151, 255)
SILVER_DARK = (103, 116, 134, 255)
SILVER = (172, 184, 201, 255)
SILVER_LIGHT = (216, 224, 234, 255)
WHITE = (242, 246, 250, 255)
WHITE_SHADE = (204, 215, 228, 255)
BLUE_DARK = (20, 55, 124, 255)
BLUE = (40, 98, 188, 255)
BLUE_LIGHT = (92, 157, 230, 255)
CYAN = (153, 214, 250, 255)
RED_DARK = (91, 24, 31, 255)
RED = (158, 43, 45, 255)
RED_LIGHT = (211, 80, 70, 255)
BROWN_DARK = (66, 38, 23, 255)
BROWN = (111, 67, 40, 255)
BROWN_LIGHT = (151, 99, 62, 255)
GOLD = (190, 158, 82, 255)

REGIONS = {
    "white": (0, 0, 12, 12),
    "white_shade": (12, 0, 24, 12),
    "silver": (24, 0, 36, 12),
    "silver_dark": (36, 0, 48, 12),
    "metal": (48, 0, 60, 12),
    "metal_light": (60, 0, 72, 12),
    "blue": (72, 0, 84, 12),
    "blue_dark": (84, 0, 96, 12),
    "blue_light": (96, 0, 108, 12),
    "red": (108, 0, 120, 12),
    "red_dark": (120, 0, 128, 12),
    "brown": (0, 12, 16, 28),
    "brown_dark": (16, 12, 32, 28),
    "black": (32, 12, 48, 28),
    "gold": (48, 12, 64, 28),
    "hub_face": (0, 32, 48, 80),
    "blade_top": (48, 32, 80, 48),
    "zone_a": (48, 48, 64, 64),
    "zone_b": (64, 48, 80, 64),
    "tip_top": (80, 32, 96, 48),
    "cuff_top": (96, 32, 128, 48),
    "cuff_side": (96, 48, 128, 64),
    "metal_panel": (0, 80, 32, 96),
    "red_panel": (32, 80, 48, 96),
    "blue_panel": (48, 80, 64, 96),
    "white_panel": (64, 80, 80, 96),
    "hub_core": (80, 80, 104, 104),
    "edge": (80, 64, 96, 80),
    "bolt": (96, 64, 104, 72),
}

img = Image.new("RGBA", (TEXTURE_SIZE, TEXTURE_SIZE), TRANSPARENT)
draw = ImageDraw.Draw(img)


def rect(region, color):
    x0, y0, x1, y1 = REGIONS[region]
    draw.rectangle((x0, y0, x1 - 1, y1 - 1), fill=color)


def outline_rect(region, color, inset=0):
    x0, y0, x1, y1 = REGIONS[region]
    draw.rectangle((x0 + inset, y0 + inset, x1 - 1 - inset, y1 - 1 - inset), outline=color)


def octagon(cx, cy, radius):
    cut = radius * 0.42
    return [
        (cx - radius + cut, cy - radius),
        (cx + radius - cut, cy - radius),
        (cx + radius, cy - radius + cut),
        (cx + radius, cy + radius - cut),
        (cx + radius - cut, cy + radius),
        (cx - radius + cut, cy + radius),
        (cx - radius, cy + radius - cut),
        (cx - radius, cy - radius + cut),
    ]


def local_octagon(region, cx, cy, radius):
    x0, y0, _, _ = REGIONS[region]
    return [(x0 + x, y0 + y) for x, y in octagon(cx, cy, radius)]


# Material swatches. The small edge highlights keep the pixel texture crisp
# when Minecraft samples it at GUI scale.
swatch_colors = {
    "white": WHITE,
    "white_shade": WHITE_SHADE,
    "silver": SILVER,
    "silver_dark": SILVER_DARK,
    "metal": METAL,
    "metal_light": METAL_LIGHT,
    "blue": BLUE,
    "blue_dark": BLUE_DARK,
    "blue_light": BLUE_LIGHT,
    "red": RED,
    "red_dark": RED_DARK,
    "brown": BROWN,
    "brown_dark": BROWN_DARK,
    "black": BLACK,
    "gold": GOLD,
}
for name, color in swatch_colors.items():
    rect(name, color)
    x0, y0, x1, y1 = REGIONS[name]
    draw.line((x0, y0, x1 - 1, y0), fill=tuple(min(255, c + 18) for c in color[:3]) + (255,))
    draw.line((x0, y1 - 1, x1 - 1, y1 - 1), fill=tuple(max(0, c - 20) for c in color[:3]) + (255,))


# Hub face: a layered octagonal ring with blue sectors, silver spokes, and the
# dark red mechanical core visible in the Battle City anime design.
hub_x, hub_y, _, _ = REGIONS["hub_face"]
hub_layers = [
    (23, BLACK),
    (21, SILVER_DARK),
    (19, SILVER_LIGHT),
    (17, WHITE),
    (15, BLUE_DARK),
    (13, BLUE),
    (10, SILVER),
    (8, RED_DARK),
    (6, RED),
    (4, BLACK),
]
for radius, color in hub_layers:
    draw.polygon(local_octagon("hub_face", 24, 24, radius), fill=color)

for angle in (0, 45, 90, 135, 180, 225, 270, 315):
    rad = math.radians(angle)
    inner = (hub_x + 24 + math.cos(rad) * 8, hub_y + 24 + math.sin(rad) * 8)
    outer = (hub_x + 24 + math.cos(rad) * 19, hub_y + 24 + math.sin(rad) * 19)
    draw.line((inner, outer), fill=WHITE, width=2)

for angle in (22.5, 112.5, 202.5, 292.5):
    rad = math.radians(angle)
    bx = round(hub_x + 24 + math.cos(rad) * 15)
    by = round(hub_y + 24 + math.sin(rad) * 15)
    draw.rectangle((bx - 1, by - 1, bx + 1, by + 1), fill=SILVER_LIGHT)

draw.line((hub_x + 14, hub_y + 12, hub_x + 31, hub_y + 12), fill=WHITE, width=1)
draw.line((hub_x + 11, hub_y + 15, hub_x + 11, hub_y + 31), fill=SILVER_LIGHT, width=1)
draw.rectangle((hub_x + 21, hub_y + 20, hub_x + 27, hub_y + 23), fill=RED_LIGHT)
draw.rectangle((hub_x + 22, hub_y + 22, hub_x + 26, hub_y + 27), fill=BLACK_SOFT)

# Raised inner hub. It is a separate octagonal face so the ring remains
# readable after the mechanical ring segments are assembled around it.
core_x, core_y, _, _ = REGIONS["hub_core"]
core_layers = [
    (11, BLACK),
    (9, SILVER_LIGHT),
    (7, BLUE_DARK),
    (5, RED),
    (3, BLACK),
]
for radius, color in core_layers:
    draw.polygon(local_octagon("hub_core", 12, 12, radius), fill=color)
draw.rectangle((core_x + 10, core_y + 10, core_x + 13, core_y + 13), fill=BLUE_LIGHT)
draw.point((core_x + 11, core_y + 11), fill=WHITE)


# Main blade plate: white armor with a brushed silver spine.
rect("blade_top", SILVER_DARK)
x0, y0, x1, y1 = REGIONS["blade_top"]
draw.rectangle((x0 + 1, y0 + 2, x1 - 2, y1 - 3), fill=WHITE)
draw.rectangle((x0 + 2, y0 + 3, x1 - 3, y1 - 4), fill=WHITE_SHADE)
draw.rectangle((x0 + 2, y0 + 5, x1 - 3, y1 - 7), fill=WHITE)
draw.line((x0 + 3, y0 + 4, x1 - 4, y0 + 4), fill=SILVER_LIGHT)
draw.line((x0 + 3, y1 - 5, x1 - 4, y1 - 5), fill=SILVER)
for line_y in range(y0 + 6, y1 - 5, 3):
    draw.line((x0 + 4, line_y, x1 - 5, line_y), fill=(228, 235, 242, 255))


def draw_zone(region, accent):
    rect(region, BLACK)
    x0, y0, x1, y1 = REGIONS[region]
    draw.rectangle((x0 + 1, y0 + 1, x1 - 2, y1 - 2), fill=SILVER_LIGHT)
    draw.rectangle((x0 + 2, y0 + 2, x1 - 3, y1 - 3), fill=BLUE)
    draw.rectangle((x0 + 2, y0 + 2, x1 - 3, y0 + 5), fill=BLUE_LIGHT)
    draw.rectangle((x0 + 2, y1 - 5, x1 - 3, y1 - 3), fill=BLUE_DARK)
    draw.polygon(
        [
            (x0 + 3, y0 + 4),
            (x0 + 9, y0 + 2),
            (x0 + 5, y0 + 10),
            (x0 + 2, y0 + 11),
        ],
        fill=accent,
    )
    draw.line((x0 + 4, y0 + 12, x0 + 11, y0 + 5), fill=CYAN)
    draw.rectangle((x0 + 7, y0 + 7, x0 + 9, y0 + 9), fill=WHITE)
    draw.point((x0 + 8, y0 + 8), fill=BLUE_DARK)


draw_zone("zone_a", (76, 145, 221, 255))
draw_zone("zone_b", (58, 127, 207, 255))

# Outer spearhead plate.
rect("tip_top", SILVER)
x0, y0, x1, y1 = REGIONS["tip_top"]
draw.polygon(
    [(x0 + 1, y0 + 3), (x1 - 4, y0 + 3), (x1 - 1, y0 + 7), (x1 - 1, y1 - 7), (x1 - 4, y1 - 3), (x0 + 1, y1 - 3)],
    fill=WHITE,
)
draw.line((x0 + 2, y0 + 4, x1 - 4, y0 + 4), fill=SILVER_LIGHT)
draw.line((x0 + 2, y1 - 4, x1 - 4, y1 - 4), fill=SILVER_DARK)
draw.rectangle((x0 + 5, y0 + 6, x0 + 8, y0 + 10), fill=BLUE_LIGHT)

# Leather cuff and strap.
rect("cuff_top", BROWN_DARK)
x0, y0, x1, y1 = REGIONS["cuff_top"]
draw.rectangle((x0 + 1, y0 + 1, x1 - 2, y1 - 2), fill=BROWN)
for stitch_y in (y0 + 3, y1 - 4):
    for stitch_x in range(x0 + 3, x1 - 2, 4):
        draw.rectangle((stitch_x, stitch_y, stitch_x + 1, stitch_y + 1), fill=BROWN_LIGHT)
draw.rectangle((x0 + 8, y0 + 4, x1 - 9, y1 - 5), fill=BROWN_DARK)
draw.rectangle((x0 + 9, y0 + 5, x1 - 10, y1 - 6), fill=BROWN_LIGHT)

rect("cuff_side", BROWN_DARK)
x0, y0, x1, y1 = REGIONS["cuff_side"]
draw.rectangle((x0 + 1, y0 + 2, x1 - 2, y1 - 3), fill=BROWN)
draw.line((x0 + 2, y0 + 4, x1 - 3, y0 + 4), fill=BROWN_LIGHT)
draw.line((x0 + 2, y1 - 5, x1 - 3, y1 - 5), fill=BROWN_DARK)

# Reusable mechanical panels.
rect("metal_panel", METAL)
x0, y0, x1, y1 = REGIONS["metal_panel"]
draw.rectangle((x0 + 2, y0 + 2, x1 - 3, y1 - 3), fill=METAL_LIGHT)
draw.rectangle((x0 + 4, y0 + 4, x1 - 5, y1 - 5), fill=METAL)
draw.line((x0 + 4, y0 + 5, x1 - 6, y1 - 5), fill=BLACK_SOFT)
for bx in (x0 + 3, x1 - 4):
    for by in (y0 + 3, y1 - 4):
        draw.rectangle((bx, by, bx + 1, by + 1), fill=SILVER_LIGHT)

rect("red_panel", RED_DARK)
x0, y0, x1, y1 = REGIONS["red_panel"]
draw.rectangle((x0 + 1, y0 + 1, x1 - 2, y1 - 2), fill=RED)
draw.rectangle((x0 + 3, y0 + 3, x1 - 4, y1 - 4), fill=RED_DARK)
draw.line((x0 + 3, y0 + 3, x1 - 5, y0 + 3), fill=RED_LIGHT)

rect("blue_panel", BLUE_DARK)
x0, y0, x1, y1 = REGIONS["blue_panel"]
draw.rectangle((x0 + 1, y0 + 1, x1 - 2, y1 - 2), fill=BLUE)
draw.polygon([(x0 + 2, y0 + 2), (x0 + 9, y0 + 2), (x0 + 5, y0 + 10)], fill=BLUE_LIGHT)
draw.line((x0 + 3, y1 - 4, x1 - 4, y0 + 4), fill=CYAN)

rect("white_panel", SILVER)
x0, y0, x1, y1 = REGIONS["white_panel"]
draw.rectangle((x0 + 1, y0 + 1, x1 - 2, y1 - 2), fill=WHITE)
draw.line((x0 + 2, y0 + 3, x1 - 3, y0 + 3), fill=SILVER_LIGHT)
draw.line((x0 + 2, y1 - 4, x1 - 3, y1 - 4), fill=SILVER_DARK)

rect("edge", SILVER_DARK)
x0, y0, x1, y1 = REGIONS["edge"]
draw.rectangle((x0, y0 + 2, x1 - 1, y1 - 3), fill=SILVER)
draw.line((x0, y0 + 3, x1 - 1, y0 + 3), fill=SILVER_LIGHT)
draw.line((x0, y1 - 4, x1 - 1, y1 - 4), fill=BLACK_SOFT)

rect("bolt", BLACK)
x0, y0, x1, y1 = REGIONS["bolt"]
draw.rectangle((x0 + 1, y0 + 1, x1 - 2, y1 - 2), fill=SILVER_LIGHT)
draw.rectangle((x0 + 2, y0 + 2, x1 - 3, y1 - 3), fill=GOLD)


def uv(region):
    """Full atlas patch (legacy stamp). Prefer aspect_uv for real faces."""
    return list(REGIONS[region])


def aspect_uv(region, face_w, face_h):
    """Map a face into its atlas patch, preserving aspect and staying inside.

    Model units map into the region with a uniform scale so the UV quad
    never leaves the painted patch. Larger faces use more of the detail;
    thin rails stay thin instead of stretching a whole swatch.
    """
    rx0, ry0, rx1, ry1 = REGIONS[region]
    rw = float(rx1 - rx0)
    rh = float(ry1 - ry0)
    fw = max(abs(float(face_w)), 0.05)
    fh = max(abs(float(face_h)), 0.05)
    scale = min(rw / fw, rh / fh)
    uw = fw * scale
    uh = fh * scale
    # Keep a tiny inset so nearest-neighbor sampling stays on painted pixels.
    inset = 0.02 if min(uw, uh) > 0.2 else 0.0
    u0 = rx0 + inset
    v0 = ry0 + inset
    u1 = min(rx0 + uw, rx1) - inset
    v1 = min(ry0 + uh, ry1) - inset
    if u1 <= u0:
        u0, u1 = rx0, min(rx0 + max(uw, 0.25), rx1)
    if v1 <= v0:
        v0, v1 = ry0, min(ry0 + max(uh, 0.25), ry1)
    return [round(u0, 3), round(v0, 3), round(u1, 3), round(v1, 3)]


def faces(frm, to, top, side, bottom=None):
    bottom = bottom or side
    dx = abs(to[0] - frm[0])
    dy = abs(to[1] - frm[1])
    dz = abs(to[2] - frm[2])
    return {
        "north": {"uv": aspect_uv(side, dx, dy), "texture": "#0"},
        "east": {"uv": aspect_uv(side, dz, dy), "texture": "#0"},
        "south": {"uv": aspect_uv(side, dx, dy), "texture": "#0"},
        "west": {"uv": aspect_uv(side, dz, dy), "texture": "#0"},
        "up": {"uv": aspect_uv(top, dx, dz), "texture": "#0"},
        "down": {"uv": aspect_uv(bottom, dx, dz), "texture": "#0"},
    }


elements = []


def box(name, frm, to, top, side, bottom=None, rotation=None):
    element = {
        "name": name,
        "from": [round(v, 3) for v in frm],
        "to": [round(v, 3) for v in to],
        "faces": faces(frm, to, top, side, bottom),
    }
    if rotation:
        angle, axis, origin = rotation
        element["rotation"] = {
            "angle": angle,
            "axis": axis,
            "origin": [round(v, 3) for v in origin],
        }
    elements.append(element)


# Forearm mount and leather cuff sit below the visible disk face.
box("cuff_black_core", (3.9, 4.3, 4.2), (10.5, 7.5, 11.9), "black", "metal")
box("cuff_leather_body", (4.2, 5.0, 3.7), (10.7, 6.5, 12.2), "cuff_top", "cuff_side", "brown_dark")
box("cuff_upper_strap", (4.0, 6.4, 3.6), (10.8, 7.6, 6.1), "cuff_top", "cuff_side")
box("cuff_lower_strap", (4.0, 6.4, 9.9), (10.8, 7.6, 12.4), "cuff_top", "cuff_side")
box("cuff_silver_buckle", (5.1, 6.5, 4.1), (9.7, 7.85, 5.9), "metal_panel", "metal_light", "metal")
box("cuff_buckle_inset", (6.0, 7.85, 4.55), (8.8, 8.0, 5.45), "black", "black")
box("cuff_red_pad", (5.4, 6.5, 8.9), (9.5, 7.7, 11.4), "red_panel", "red_dark", "black")
box("cuff_rear_hinge", (5.1, 5.2, 11.5), (9.9, 7.4, 12.9), "metal_panel", "metal")
box("cuff_front_hinge", (5.1, 5.2, 3.1), (9.9, 7.4, 4.4), "metal_panel", "metal")

# The blade is a stepped white wing: broad at the cuff, then progressively
# narrower toward the spearhead.
blade_sections = (
    ("root", 9.8, 15.2, 2.45, 10.75),
    ("mid", 15.2, 20.8, 2.85, 10.35),
    ("outer", 20.8, 25.4, 3.35, 9.85),
)
for name, x0, x1, z0, z1 in blade_sections:
    box(f"blade_{name}_plate", (x0, 7.55, z0), (x1, 8.95, z1), "blade_top", "silver_dark", "metal")
    box(f"blade_{name}_upper_rail", (x0, 7.7, z0 - 0.55), (x1, 9.16, z0 + 0.15), "white_panel", "edge")
    box(f"blade_{name}_lower_rail", (x0, 7.7, z1 - 0.15), (x1, 9.16, z1 + 0.55), "white_panel", "edge")

box("blade_outer_rib", (25.0, 7.7, 3.15), (26.3, 9.16, 10.05), "white_panel", "edge")

panel_starts = (10.65, 13.45, 16.25, 19.05, 21.85)
# Vanilla/Blockbench only allow ±22.5/±45. Tiny zone angles were invalid; keep flat.
for index, start in enumerate(panel_starts, start=1):
    end = start + 2.55
    zone_region = "zone_a" if index % 2 else "zone_b"
    box(
        f"monster_zone_{index}",
        (start, 8.9, 3.35),
        (end, 9.16, 9.85),
        zone_region,
        "blue_dark",
        "blue_dark",
    )

for index, boundary in enumerate((13.2, 16.0, 18.8, 21.6), start=1):
    box(f"zone_divider_{index}", (boundary, 8.98, 3.0), (boundary + 0.34, 9.28, 10.2), "white_panel", "edge")

box("blade_center_spine", (10.0, 9.05, 6.42), (25.5, 9.32, 6.92), "white_panel", "silver_dark")
box("blade_tip_core", (25.2, 7.65, 4.2), (29.4, 8.95, 8.9), "tip_top", "white_panel", "silver_dark")
# Tip flanks: ±11 was invalid in vanilla; keep silhouette without rotation.
box(
    "blade_tip_upper",
    (24.8, 7.72, 2.8),
    (28.7, 9.05, 4.5),
    "tip_top",
    "white_panel",
    "silver_dark",
)
box(
    "blade_tip_lower",
    (24.8, 7.72, 8.7),
    (28.7, 9.05, 10.4),
    "tip_top",
    "white_panel",
    "silver_dark",
)

# Shorter shoulder wing on the hub side; it gives the disk the asymmetric
# white fan silhouette seen in the reference.
box("left_wing_plate", (1.4, 7.55, 3.3), (5.5, 8.95, 10.1), "white_panel", "silver_dark", "metal")
box("left_wing_tip", (-0.4, 7.65, 4.8), (1.8, 8.95, 8.8), "tip_top", "white_panel", "silver_dark")
box("left_wing_inset", (2.0, 8.9, 4.15), (4.8, 9.14, 9.25), "blue_panel", "blue_dark", "blue_dark")
box("left_wing_upper_rail", (1.3, 7.75, 2.8), (5.5, 9.18, 3.6), "white_panel", "edge")
box("left_wing_lower_rail", (1.3, 7.75, 9.8), (5.5, 9.18, 10.6), "white_panel", "edge")

# Hub mount struts, octagonal ring, and blue sectors.
box("hub_left_strut", (2.2, 7.4, 5.4), (5.2, 8.2, 10.8), "metal_panel", "metal")
box("hub_right_strut", (10.6, 7.4, 4.6), (13.5, 8.2, 11.5), "metal_panel", "metal")
box("hub_inner_core", (5.45, 7.85, 5.45), (10.55, 9.05, 10.55), "hub_core", "red_dark", "black")

def bake_y_aabb(frm, to, angle_deg, origin):
    """AABB after Y rotation — used to bake angles outside {-45,-22.5,0,22.5,45}."""
    import math as _math
    x0, y0, z0 = frm
    x1, y1, z1 = to
    ox, oy, oz = origin
    rad = _math.radians(angle_deg)
    c, s = _math.cos(rad), _math.sin(rad)
    xs, ys, zs = [], [], []
    for x in (x0, x1):
        for y in (y0, y1):
            for z in (z0, z1):
                dx, dz = x - ox, z - oz
                xs.append(ox + dx * c - dz * s)
                ys.append(y)
                zs.append(oz + dx * s + dz * c)
    return (
        (round(min(xs), 3), round(min(ys), 3), round(min(zs), 3)),
        (round(max(xs), 3), round(max(ys), 3), round(max(zs), 3)),
    )


# Octagon ring: vanilla cannot store 90/135/180/... so bake k*90 into from/to
# and keep only a leftover 0 or 45 rotation (both legal).
_ring_origin = (8.0, 8.5, 8.0)
_ring_base_from = (10.8, 7.9, 6.2)
_ring_base_to = (13.2, 9.18, 9.8)
for index, angle in enumerate(range(0, 360, 45), start=1):
    ring_region = "white_panel" if index % 2 else "silver"
    bake = (angle // 90) * 90
    leftover = angle - bake
    if leftover == 315:  # shouldn't happen with this loop
        bake, leftover = 270, 45
    # normalize leftover into {0, 45}
    if leftover not in (0, 45):
        # 135->90+45, 225->180+45, 315->270+45 already covered by angle//90
        raise AssertionError(f"unexpected ring leftover {leftover} for {angle}")
    frm, to = _ring_base_from, _ring_base_to
    if bake:
        frm, to = bake_y_aabb(frm, to, bake, _ring_origin)
    rot = (leftover, "y", _ring_origin) if leftover else None
    box(
        f"hub_outer_ring_{index}",
        frm,
        to,
        ring_region,
        "silver_dark",
        "black",
        rot,
    )

_sec_origin = (8.0, 9.1, 8.0)
_sec_from = (9.55, 9.02, 6.65)
_sec_to = (11.35, 9.32, 9.35)
for index, angle in enumerate((0, 90, 180, 270), start=1):
    frm, to = _sec_from, _sec_to
    rot = None
    if angle:
        frm, to = bake_y_aabb(frm, to, angle, _sec_origin)
    box(
        f"hub_blue_sector_{index}",
        frm,
        to,
        "blue_panel",
        "blue_dark",
        "blue_dark",
        rot,
    )

for index, (bx, bz) in enumerate(((4.35, 4.35), (11.65, 4.35), (4.35, 11.65), (11.65, 11.65)), start=1):
    box(f"hub_bolt_{index}", (bx - 0.35, 9.03, bz - 0.35), (bx + 0.35, 9.28, bz + 0.35), "bolt", "metal")


def build_groups(elements):
    """Blockbench Java item groups starting at root/."""
    by_name = {e["name"]: i for i, e in enumerate(elements)}

    def idxs(*names):
        return [by_name[n] for n in names if n in by_name]

    def grp(name, children, color=0, origin=None):
        return {
            "name": name,
            "origin": origin or [8, 8, 8],
            "color": color,
            "children": children,
        }

    cuff = grp(
        "cuff",
        idxs(
            "cuff_black_core",
            "cuff_leather_body",
            "cuff_upper_strap",
            "cuff_lower_strap",
            "cuff_silver_buckle",
            "cuff_buckle_inset",
            "cuff_red_pad",
            "cuff_rear_hinge",
            "cuff_front_hinge",
        ),
        color=1,
        origin=[7.2, 5.9, 8.0],
    )
    root_seg = grp(
        "root_seg",
        idxs("blade_root_plate", "blade_root_upper_rail", "blade_root_lower_rail"),
        color=2,
        origin=[12.5, 8.3, 6.6],
    )
    mid_seg = grp(
        "mid_seg",
        idxs("blade_mid_plate", "blade_mid_upper_rail", "blade_mid_lower_rail"),
        color=3,
        origin=[18.0, 8.3, 6.6],
    )
    outer_seg = grp(
        "outer_seg",
        idxs(
            "blade_outer_plate",
            "blade_outer_upper_rail",
            "blade_outer_lower_rail",
            "blade_outer_rib",
        ),
        color=4,
        origin=[23.1, 8.3, 6.6],
    )
    zones = grp(
        "zones",
        idxs(
            "monster_zone_1",
            "monster_zone_2",
            "monster_zone_3",
            "monster_zone_4",
            "monster_zone_5",
            "zone_divider_1",
            "zone_divider_2",
            "zone_divider_3",
            "zone_divider_4",
        ),
        color=5,
        origin=[17.5, 9.1, 6.6],
    )
    spine = grp("spine", idxs("blade_center_spine"), color=6, origin=[17.75, 9.2, 6.67])
    tip = grp(
        "tip",
        idxs("blade_tip_core", "blade_tip_upper", "blade_tip_lower"),
        color=7,
        origin=[27.0, 8.3, 6.55],
    )
    blade = grp(
        "blade",
        [root_seg, mid_seg, outer_seg, zones, spine, tip],
        color=8,
        origin=[18.0, 8.4, 6.6],
    )
    left_wing = grp(
        "left_wing",
        idxs(
            "left_wing_plate",
            "left_wing_tip",
            "left_wing_inset",
            "left_wing_upper_rail",
            "left_wing_lower_rail",
        ),
        color=9,
        origin=[2.5, 8.4, 6.7],
    )
    struts = grp("struts", idxs("hub_left_strut", "hub_right_strut"), color=1, origin=[8.0, 7.8, 8.0])
    core = grp("core", idxs("hub_inner_core"), color=2, origin=[8.0, 8.45, 8.0])
    ring = grp(
        "ring",
        idxs(
            "hub_outer_ring_1",
            "hub_outer_ring_2",
            "hub_outer_ring_3",
            "hub_outer_ring_4",
            "hub_outer_ring_5",
            "hub_outer_ring_6",
            "hub_outer_ring_7",
            "hub_outer_ring_8",
        ),
        color=3,
        origin=[8.0, 8.5, 8.0],
    )
    sectors = grp(
        "sectors",
        idxs(
            "hub_blue_sector_1",
            "hub_blue_sector_2",
            "hub_blue_sector_3",
            "hub_blue_sector_4",
        ),
        color=4,
        origin=[8.0, 9.1, 8.0],
    )
    bolts = grp(
        "bolts",
        idxs("hub_bolt_1", "hub_bolt_2", "hub_bolt_3", "hub_bolt_4"),
        color=5,
        origin=[8.0, 9.15, 8.0],
    )
    hub = grp("hub", [struts, core, ring, sectors, bolts], color=6, origin=[8.0, 8.5, 8.0])
    return [grp("root", [cuff, blade, left_wing, hub], color=0, origin=[8.0, 8.0, 8.0])]


model = {
    "credit": "KaibaCorp Battle City Duel Disk - anime-inspired vanilla item model",
    "texture_size": [TEXTURE_SIZE, TEXTURE_SIZE],
    "textures": {
        "0": "ygomc:item/duel_disk",
        "particle": "ygomc:item/duel_disk",
    },
    "elements": elements,
    "groups": build_groups(elements),
    "display": {
        "thirdperson_righthand": {
            "rotation": [0, 0, -90],
            "translation": [-1.5, 1.0, 2.5],
            "scale": [0.7, 0.7, 0.7],
        },
        "thirdperson_lefthand": {
            "rotation": [0, 0, 90],
            "translation": [1.5, 1.0, 2.5],
            "scale": [0.7, 0.7, 0.7],
        },
        "firstperson_righthand": {
            "rotation": [12, -28, -10],
            "translation": [1.0, 2.2, 0.0],
            "scale": [0.62, 0.62, 0.62],
        },
        "firstperson_lefthand": {
            "rotation": [12, 28, 10],
            "translation": [-1.0, 2.2, 0.0],
            "scale": [0.62, 0.62, 0.62],
        },
        "ground": {
            "translation": [0, 2, 0],
            "scale": [0.48, 0.48, 0.48],
        },
        "gui": {
            "rotation": [30, 135, 0],
            "translation": [0, 0.5, 0],
            "scale": [0.58, 0.58, 0.58],
        },
        "fixed": {
            "rotation": [-90, 0, 0],
            "translation": [0, 0, -1],
            "scale": [0.75, 0.75, 0.75],
        },
    },
}


VALID_ROTATION_ANGLES = {-45.0, -22.5, 0.0, 22.5, 45.0}


def validate():
    assert 40 <= len(elements) <= 80, len(elements)
    names = [element["name"] for element in elements]
    assert len(names) == len(set(names)), "duplicate element names"
    for element in elements:
        assert all(element["from"][i] < element["to"][i] for i in range(3)), element["name"]
        assert all(-16 <= value <= 32 for value in element["from"] + element["to"]), element["name"]
        assert set(element["faces"]) == {"north", "east", "south", "west", "up", "down"}
        rot = element.get("rotation")
        if rot:
            assert float(rot["angle"]) in VALID_ROTATION_ANGLES, (
                element["name"],
                rot["angle"],
            )
        for face in element["faces"].values():
            assert face["texture"] == "#0"
            u0, v0, u1, v1 = face["uv"]
            assert 0 <= u0 < u1 <= TEXTURE_SIZE, (element["name"], face["uv"])
            assert 0 <= v0 < v1 <= TEXTURE_SIZE, (element["name"], face["uv"])
            # Each UV rect must sit entirely inside one atlas patch.
            inside = False
            for rx0, ry0, rx1, ry1 in REGIONS.values():
                if u0 >= rx0 - 1e-6 and v0 >= ry0 - 1e-6 and u1 <= rx1 + 1e-6 and v1 <= ry1 + 1e-6:
                    inside = True
                    break
            assert inside, (element["name"], face["uv"])


validate()
os.makedirs(os.path.dirname(TEX_PATH), exist_ok=True)
os.makedirs(os.path.dirname(MODEL_PATH), exist_ok=True)
img.save(TEX_PATH, "PNG")
with open(MODEL_PATH, "w", encoding="utf-8") as handle:
    json.dump(model, handle, indent="\t", ensure_ascii=False)
    handle.write("\n")

note = """# Duel Disk item model

Anime-inspired KaibaCorp Battle City Duel Disk (vanilla JSON item model).

- Model: `assets/ygomc/models/item/duel_disk.json`
- Texture: `assets/ygomc/textures/item/duel_disk.png` (128x128 atlas)
- Blockbench project (embedded texture): `.agent/blockbench/duel_disk.bbmodel`
- Regenerate model+atlas: `python tools/gen_duel_disk_model.py`
- Regenerate bbmodel: `python tools/export_duel_disk_bbmodel.py`

## UV mapping

Faces use **aspect-aware box UVs** inside named atlas patches (`REGIONS` in
`tools/gen_duel_disk_model.py`). Each face keeps its width/height ratio and is
scaled to fit inside its patch (no full-swatch stamps, no UVs outside the
painted region). `texture_size` stays `[128, 128]`; in-game texture path stays
`ygomc:item/duel_disk`.

## Group tree (Blockbench)

```
root/
  cuff/           # leather mount + buckles + hinges
  blade/
    root_seg/     # blade_root_*
    mid_seg/      # blade_mid_*
    outer_seg/    # blade_outer_*
    zones/        # monster_zone_* + zone_divider_*
    spine/        # blade_center_spine
    tip/          # blade_tip_*
  left_wing/      # left_wing_*
  hub/
    struts/       # hub_left_strut, hub_right_strut
    core/         # hub_inner_core
    ring/         # hub_outer_ring_1..8
    sectors/      # hub_blue_sector_1..4
    bolts/        # hub_bolt_1..4
```

## Blockbench

**How we previewed:** Blender (`tools` / `.agent/previews/render_duel_disk.py`)
loads `duel_disk.json` cubes and applies `textures/item/duel_disk.png` as a
file-path image texture on each face UV. That is **not** Blockbench.

**Why the Java JSON looks untextured in BB:** the model uses
`"0": "ygomc:item/duel_disk"`. Blockbench only resolves that namespace if you
set **File → Preferences → Minecraft assets / resource pack root** (wording
varies by version) to this repo's `common/src/main/resources/assets`. Without
that, the texture slot is empty → pink / black / invisible mesh.

**Open with textures visible immediately:**
`.agent/blockbench/duel_disk.bbmodel` — PNG is embedded as base64, groups under
`root/` preserved. Prefer this file for editing in Blockbench.

**Optional:** after setting the assets folder to `.../resources/assets`, the
Java `duel_disk.json` also resolves `ygomc:` paths.

**After editing in BB:** export / save back to Java Item/Block JSON and **keep**
the `ygomc:item/duel_disk` texture namespace (do not strip it to a bare
`item/duel_disk`, which would resolve as `minecraft:`). Then re-run
`python tools/export_duel_disk_bbmodel.py` if you want an updated embedded
project. Regenerate UVs from the Python generator if you change geometry bulk.

## Vanilla / Blockbench rotation limits

Minecraft (and Blockbench's Java format) only allow element rotations of
**-45, -22.5, 0, 22.5, 45** degrees. Earlier Codex geometry used:

- monster zones ±2..4 and tip ±11 → snapped to **0** (removed)
- hub rings 90/135/180/225/270/315 and blue sectors 90/180/270 → **baked**
  into distinct `from`/`to` AABBs (Y rotation around each origin), with a
  leftover **0** or **45** kept only when legal

That bake keeps an octagonal hub silhouette without illegal JSON.

## Verify in-game

1. `/give @s ygomc:duel_disk`
2. Inventory icon: hub, five wing panels, pointed tip
3. Third-person: cuff along the forearm
4. Blockbench: open `.agent/blockbench/duel_disk.bbmodel` — textured, `root/` tree, no rotation warnings
"""
with open(NOTE_PATH, "w", encoding="utf-8") as handle:
    handle.write(note)

print(f"Wrote {TEX_PATH} ({TEXTURE_SIZE}x{TEXTURE_SIZE})")
print(f"Wrote {MODEL_PATH} ({len(elements)} elements)")
print(f"Wrote {NOTE_PATH}")
