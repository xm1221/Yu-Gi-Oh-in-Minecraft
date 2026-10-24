# -*- coding: utf-8 -*-
"""Install duel_disk game assets from the Blockbench project.

Source of truth for geometry/display/hierarchy:
  .agent/blockbench/duel_disk.bbmodel

This script:
  1) paints a dense 128x128 atlas (minimal empty waste)
  2) remaps every *visible* element UV to the packed material regions
  3) omits fully buried / zero-area faces (no pointless UV islands)
  4) writes common/.../models/item/duel_disk.json + textures/item/duel_disk.png
  5) re-embeds the same PNG into the bbmodel (keeps user geometry/display/groups)
"""
from __future__ import annotations

import base64
import json
import math
import re
from pathlib import Path

from PIL import Image, ImageDraw

ROOT = Path(__file__).resolve().parents[2]
BB_PATH = ROOT / ".agent" / "blockbench" / "duel_disk.bbmodel"
ASSETS = ROOT / "common" / "src" / "main" / "resources" / "assets" / "ygomc"
MODEL_PATH = ASSETS / "models" / "item" / "duel_disk.json"
TEX_PATH = ASSETS / "textures" / "item" / "duel_disk.png"

TEXTURE_SIZE = 128
LEGAL_ANGLES = (-45.0, -22.5, 0.0, 22.5, 45.0)
OCCLUDE_EPS = 0.05
OCCLUDE_PROBE = 0.01

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

# Dense atlas: every named region is referenced by UVs; packed to fill 128x128.
REGIONS = {
    # 8x8 palette strip
    "black": (0, 0, 8, 8),
    "black_soft": (8, 0, 16, 8),
    "metal": (16, 0, 24, 8),
    "metal_light": (24, 0, 32, 8),
    "silver": (32, 0, 40, 8),
    "silver_dark": (40, 0, 48, 8),
    "white": (48, 0, 56, 8),
    "white_shade": (56, 0, 64, 8),
    "blue": (64, 0, 72, 8),
    "blue_dark": (72, 0, 80, 8),
    "blue_light": (80, 0, 88, 8),
    "red": (88, 0, 96, 8),
    "red_dark": (96, 0, 104, 8),
    "brown": (104, 0, 112, 8),
    "brown_dark": (112, 0, 120, 8),
    "gold": (120, 0, 128, 8),
    # Primary details — sized for real faces, packed edge-to-edge
    "hub_core": (0, 8, 48, 56),          # 48x48
    "blade_top": (48, 8, 112, 40),       # 64x32
    "zone_a": (112, 8, 128, 32),         # 16x24
    "zone_b": (112, 32, 128, 56),        # 16x24
    "tip_top": (48, 40, 96, 72),         # 48x32
    "white_panel": (96, 56, 128, 88),    # 32x32
    "cuff_top": (0, 56, 48, 88),         # 48x32
    "cuff_side": (0, 88, 40, 112),       # 40x24
    "metal_panel": (40, 88, 88, 112),    # 48x24
    "edge": (88, 88, 112, 112),          # 24x24
    "bolt": (112, 88, 128, 104),         # 16x16
    "red_panel": (0, 112, 32, 128),      # 32x16
    "blue_panel": (32, 112, 64, 128),    # 32x16
    "silver_panel": (64, 112, 112, 128), # 48x16
    "metal_light_panel": (112, 104, 128, 128),  # 16x24
}


def validate_regions() -> None:
    grid = [[False] * TEXTURE_SIZE for _ in range(TEXTURE_SIZE)]
    for name, (x0, y0, x1, y1) in REGIONS.items():
        assert 0 <= x0 < x1 <= TEXTURE_SIZE and 0 <= y0 < y1 <= TEXTURE_SIZE, name
        for y in range(y0, y1):
            for x in range(x0, x1):
                if grid[y][x]:
                    raise SystemExit(f"region overlap at {(x, y)} involving {name}")
                grid[y][x] = True


def paint_atlas() -> Image.Image:
    validate_regions()
    img = Image.new("RGBA", (TEXTURE_SIZE, TEXTURE_SIZE), TRANSPARENT)
    draw = ImageDraw.Draw(img)

    def fill(region, color):
        x0, y0, x1, y1 = REGIONS[region]
        draw.rectangle((x0, y0, x1 - 1, y1 - 1), fill=color)

    def local_octagon(region, cx, cy, radius):
        x0, y0, _, _ = REGIONS[region]
        cut = radius * 0.42
        pts = [
            (cx - radius + cut, cy - radius),
            (cx + radius - cut, cy - radius),
            (cx + radius, cy - radius + cut),
            (cx + radius, cy + radius - cut),
            (cx + radius - cut, cy + radius),
            (cx - radius + cut, cy + radius),
            (cx - radius, cy + radius - cut),
            (cx - radius, cy - radius + cut),
        ]
        return [(x0 + x, y0 + y) for x, y in pts]

    swatches = {
        "black": BLACK,
        "black_soft": BLACK_SOFT,
        "metal": METAL,
        "metal_light": METAL_LIGHT,
        "silver": SILVER,
        "silver_dark": SILVER_DARK,
        "white": WHITE,
        "white_shade": WHITE_SHADE,
        "blue": BLUE,
        "blue_dark": BLUE_DARK,
        "blue_light": BLUE_LIGHT,
        "red": RED,
        "red_dark": RED_DARK,
        "brown": BROWN,
        "brown_dark": BROWN_DARK,
        "gold": GOLD,
    }
    for name, color in swatches.items():
        fill(name, color)
        x0, y0, x1, y1 = REGIONS[name]
        draw.line((x0, y0, x1 - 1, y0), fill=tuple(min(255, c + 18) for c in color[:3]) + (255,))
        draw.line((x0, y1 - 1, x1 - 1, y1 - 1), fill=tuple(max(0, c - 20) for c in color[:3]) + (255,))

    # Hub core — silver rim, blue sectors, red mechanical center (old Battle City look)
    hx, hy, _, _ = REGIONS["hub_core"]
    for radius, color in (
        (22, BLACK),
        (20, SILVER_DARK),
        (18, SILVER_LIGHT),
        (16, WHITE),
        (14, BLUE_DARK),
        (11, BLUE),
        (8, RED_DARK),
        (6, RED),
        (3, BLACK),
    ):
        draw.polygon(local_octagon("hub_core", 24, 24, radius), fill=color)
    for angle in (0, 45, 90, 135, 180, 225, 270, 315):
        rad = math.radians(angle)
        inner = (hx + 24 + math.cos(rad) * 7, hy + 24 + math.sin(rad) * 7)
        outer = (hx + 24 + math.cos(rad) * 18, hy + 24 + math.sin(rad) * 18)
        draw.line((inner, outer), fill=WHITE, width=2)
    draw.rectangle((hx + 20, hy + 20, hx + 28, hy + 24), fill=RED_LIGHT)
    draw.rectangle((hx + 22, hy + 22, hx + 26, hy + 28), fill=BLACK_SOFT)
    draw.point((hx + 24, hy + 24), fill=BLUE_LIGHT)

    # Blade plate — white armor with brushed silver (matches pre-dense good look)
    fill("blade_top", SILVER_DARK)
    x0, y0, x1, y1 = REGIONS["blade_top"]
    draw.rectangle((x0 + 1, y0 + 1, x1 - 2, y1 - 2), fill=WHITE)
    draw.rectangle((x0 + 2, y0 + 2, x1 - 3, y1 - 3), fill=WHITE_SHADE)
    draw.rectangle((x0 + 2, y0 + 4, x1 - 3, y1 - 5), fill=WHITE)
    draw.line((x0 + 3, y0 + 3, x1 - 4, y0 + 3), fill=SILVER_LIGHT)
    draw.line((x0 + 3, y1 - 4, x1 - 4, y1 - 4), fill=SILVER)
    for line_y in range(y0 + 5, y1 - 4, 2):
        draw.line((x0 + 4, line_y, x1 - 5, line_y), fill=(228, 235, 242, 255))

    def draw_zone(region, accent):
        fill(region, BLACK)
        x0, y0, x1, y1 = REGIONS[region]
        draw.rectangle((x0 + 1, y0 + 1, x1 - 2, y1 - 2), fill=SILVER_LIGHT)
        draw.rectangle((x0 + 2, y0 + 2, x1 - 3, y1 - 3), fill=BLUE)
        draw.rectangle((x0 + 2, y0 + 2, x1 - 3, y0 + 4), fill=BLUE_LIGHT)
        draw.rectangle((x0 + 2, y1 - 4, x1 - 3, y1 - 3), fill=BLUE_DARK)
        draw.polygon(
            [(x0 + 3, y0 + 4), (x0 + 8, y0 + 2), (x0 + 5, y0 + 9), (x0 + 2, y0 + 10)],
            fill=accent,
        )
        draw.line((x0 + 4, y0 + 11, x0 + 10, y0 + 5), fill=CYAN)
        draw.rectangle((x0 + 6, y0 + 6, x0 + 8, y0 + 8), fill=WHITE)

    draw_zone("zone_a", (76, 145, 221, 255))
    draw_zone("zone_b", (58, 127, 207, 255))

    fill("tip_top", SILVER)
    x0, y0, x1, y1 = REGIONS["tip_top"]
    draw.polygon(
        [
            (x0 + 1, y0 + 2),
            (x1 - 4, y0 + 2),
            (x1 - 1, y0 + 7),
            (x1 - 1, y1 - 7),
            (x1 - 4, y1 - 2),
            (x0 + 1, y1 - 2),
        ],
        fill=WHITE,
    )
    draw.line((x0 + 2, y0 + 3, x1 - 4, y0 + 3), fill=SILVER_LIGHT)
    draw.line((x0 + 2, y1 - 3, x1 - 4, y1 - 3), fill=SILVER_DARK)
    draw.rectangle((x0 + 4, y0 + 5, x0 + 7, y0 + 9), fill=BLUE_LIGHT)

    fill("bolt", BLACK)
    x0, y0, x1, y1 = REGIONS["bolt"]
    draw.rectangle((x0 + 1, y0 + 1, x1 - 2, y1 - 2), fill=SILVER_LIGHT)
    draw.rectangle((x0 + 2, y0 + 2, x1 - 3, y1 - 3), fill=GOLD)

    fill("cuff_top", BROWN_DARK)
    x0, y0, x1, y1 = REGIONS["cuff_top"]
    draw.rectangle((x0 + 1, y0 + 1, x1 - 2, y1 - 2), fill=BROWN)
    for stitch_y in (y0 + 2, y1 - 3):
        for stitch_x in range(x0 + 2, x1 - 2, 3):
            draw.rectangle((stitch_x, stitch_y, stitch_x + 1, stitch_y), fill=BROWN_LIGHT)
    draw.rectangle((x0 + 6, y0 + 4, x1 - 7, y1 - 5), fill=BROWN_DARK)
    draw.rectangle((x0 + 7, y0 + 5, x1 - 8, y1 - 6), fill=BROWN_LIGHT)

    fill("cuff_side", BROWN_DARK)
    x0, y0, x1, y1 = REGIONS["cuff_side"]
    draw.rectangle((x0 + 1, y0 + 2, x1 - 2, y1 - 3), fill=BROWN)
    draw.line((x0 + 2, y0 + 4, x1 - 3, y0 + 4), fill=BROWN_LIGHT)
    draw.line((x0 + 2, y1 - 5, x1 - 3, y1 - 5), fill=BROWN_DARK)

    fill("metal_panel", METAL)
    x0, y0, x1, y1 = REGIONS["metal_panel"]
    draw.rectangle((x0 + 1, y0 + 1, x1 - 2, y1 - 2), fill=METAL_LIGHT)
    draw.rectangle((x0 + 3, y0 + 3, x1 - 4, y1 - 4), fill=METAL)
    draw.line((x0 + 3, y0 + 4, x1 - 5, y1 - 5), fill=BLACK_SOFT)
    for bx in (x0 + 2, x1 - 4):
        for by in (y0 + 2, y1 - 4):
            draw.rectangle((bx, by, bx + 1, by + 1), fill=SILVER_LIGHT)

    fill("white_panel", SILVER)
    x0, y0, x1, y1 = REGIONS["white_panel"]
    draw.rectangle((x0 + 1, y0 + 1, x1 - 2, y1 - 2), fill=WHITE)
    draw.line((x0 + 2, y0 + 3, x1 - 3, y0 + 3), fill=SILVER_LIGHT)
    draw.line((x0 + 2, y1 - 4, x1 - 3, y1 - 4), fill=SILVER_DARK)

    fill("edge", SILVER_DARK)
    x0, y0, x1, y1 = REGIONS["edge"]
    draw.rectangle((x0, y0 + 2, x1 - 1, y1 - 3), fill=SILVER)
    draw.line((x0, y0 + 3, x1 - 1, y0 + 3), fill=SILVER_LIGHT)
    draw.line((x0, y1 - 4, x1 - 1, y1 - 4), fill=BLACK_SOFT)

    fill("red_panel", RED_DARK)
    x0, y0, x1, y1 = REGIONS["red_panel"]
    draw.rectangle((x0 + 1, y0 + 1, x1 - 2, y1 - 2), fill=RED)
    draw.rectangle((x0 + 3, y0 + 3, x1 - 4, y1 - 4), fill=RED_DARK)
    draw.line((x0 + 3, y0 + 3, x1 - 5, y0 + 3), fill=RED_LIGHT)

    fill("blue_panel", BLUE_DARK)
    x0, y0, x1, y1 = REGIONS["blue_panel"]
    draw.rectangle((x0 + 1, y0 + 1, x1 - 2, y1 - 2), fill=BLUE)
    draw.polygon([(x0 + 2, y0 + 2), (x0 + 9, y0 + 2), (x0 + 5, y0 + 10)], fill=BLUE_LIGHT)
    draw.line((x0 + 3, y1 - 4, x1 - 4, y0 + 4), fill=CYAN)

    fill("silver_panel", SILVER_DARK)
    x0, y0, x1, y1 = REGIONS["silver_panel"]
    draw.rectangle((x0 + 1, y0 + 1, x1 - 2, y1 - 2), fill=SILVER)
    draw.rectangle((x0 + 3, y0 + 3, x1 - 4, y1 - 4), fill=METAL_LIGHT)

    fill("metal_light_panel", METAL)
    x0, y0, x1, y1 = REGIONS["metal_light_panel"]
    draw.rectangle((x0 + 1, y0 + 1, x1 - 2, y1 - 2), fill=METAL_LIGHT)
    draw.line((x0 + 2, y0 + 3, x1 - 3, y0 + 3), fill=SILVER_LIGHT)
    draw.line((x0 + 2, y1 - 4, x1 - 3, y1 - 4), fill=BLACK_SOFT)

    return img


def aspect_uv(region: str, face_w: float, face_h: float) -> list[float]:
    rx0, ry0, rx1, ry1 = REGIONS[region]
    rw = float(rx1 - rx0)
    rh = float(ry1 - ry0)
    fw = max(abs(float(face_w)), 0.05)
    fh = max(abs(float(face_h)), 0.05)
    scale = min(rw / fw, rh / fh)
    uw = fw * scale
    uh = fh * scale
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


def materials_for(name: str) -> tuple[str, str, str]:
    """Return (top, side, bottom) region names — restored to pre-dense good mapping.

    Dense-atlas regression had remapped hub_outer_ring → metal_panel (dark grey)
    instead of the old white_panel / silver alternation. left_wing_plate was also
    forced onto blade_top; restore white_panel like the old generator.
    """
    n = name or ""
    m = re.match(r"monster_zone_(\d+)", n)
    if m:
        z = "zone_a" if int(m.group(1)) % 2 else "zone_b"
        return z, "blue_dark", "blue_dark"
    if "zone_divider" in n or n.endswith("_rail") or n.endswith("_rib"):
        return "white_panel", "edge", "silver_dark"
    if "tip" in n:
        return "tip_top", "white_panel", "silver_dark"
    if "left_wing_inset" in n:
        return "blue_panel", "blue_dark", "blue_dark"
    if n.startswith("left_wing_plate"):
        return "white_panel", "silver_dark", "metal"
    if re.search(r"blade_.*_plate", n):
        return "blade_top", "silver_dark", "metal"
    if n == "cuff_black_core":
        return "black", "metal", "black"
    if n in ("cuff_leather_body", "cuff_upper_strap", "cuff_lower_strap"):
        return "cuff_top", "cuff_side", "brown_dark"
    if n == "cuff_silver_buckle":
        return "metal_panel", "metal_light_panel", "metal"
    if n == "cuff_buckle_inset":
        return "black", "black", "black"
    if n == "cuff_red_pad":
        return "red_panel", "red_dark", "black"
    if "hinge" in n or "strut" in n:
        return "metal_panel", "metal", "metal"
    if n.startswith("hub_outer_ring"):
        # Old gen: odd → white_panel, even → silver (NOT metal)
        mm = re.search(r"(\d+)$", n)
        idx = int(mm.group(1)) if mm else 1
        if idx % 2:
            return "white_panel", "silver_dark", "metal"
        return "silver", "silver_dark", "metal"
    if n.startswith("hub_blue_sector"):
        return "blue_panel", "blue_dark", "blue"
    if n == "hub_inner_core":
        return "hub_core", "red_dark", "black"
    if "bolt" in n:
        return "bolt", "metal", "metal"
    if "spine" in n:
        return "white_panel", "silver_dark", "metal"
    return "metal_panel", "metal", "black"


FACE_AXES = {
    "east": (0, 1),
    "west": (0, -1),
    "up": (1, 1),
    "down": (1, -1),
    "south": (2, 1),
    "north": (2, -1),
}


def _norm_box(el: dict) -> dict:
    f = [float(v) for v in el["from"]]
    t = [float(v) for v in el["to"]]
    frm = [min(f[0], t[0]), min(f[1], t[1]), min(f[2], t[2])]
    to = [max(f[0], t[0]), max(f[1], t[1]), max(f[2], t[2])]
    rot = el.get("rotation") or [0, 0, 0]
    has_rot = any(abs(float(a)) > 1e-6 for a in rot)
    return {"frm": frm, "to": to, "has_rot": has_rot, "name": el.get("name", "")}


def _point_in(box: dict, p: list[float]) -> bool:
    return (
        box["frm"][0] - OCCLUDE_EPS <= p[0] <= box["to"][0] + OCCLUDE_EPS
        and box["frm"][1] - OCCLUDE_EPS <= p[1] <= box["to"][1] + OCCLUDE_EPS
        and box["frm"][2] - OCCLUDE_EPS <= p[2] <= box["to"][2] + OCCLUDE_EPS
    )


def _face_samples(box: dict, face: str) -> list[list[float]]:
    axis, direction = FACE_AXES[face]
    pos = box["to"][axis] if direction > 0 else box["frm"][axis]
    axes = [0, 1, 2]
    axes.remove(axis)
    u, v = axes
    u0, u1 = box["frm"][u], box["to"][u]
    v0, v1 = box["frm"][v], box["to"][v]
    inset_u = min(0.05, (u1 - u0) * 0.2) if u1 > u0 else 0.0
    inset_v = min(0.05, (v1 - v0) * 0.2) if v1 > v0 else 0.0
    us = [u0 + inset_u, (u0 + u1) / 2, u1 - inset_u] if u1 - u0 > 1e-6 else [u0]
    vs = [v0 + inset_v, (v0 + v1) / 2, v1 - inset_v] if v1 - v0 > 1e-6 else [v0]
    pts = []
    for uu in us:
        for vv in vs:
            p = [0.0, 0.0, 0.0]
            p[axis] = pos + direction * OCCLUDE_PROBE
            p[u] = uu
            p[v] = vv
            pts.append(p)
    return pts


def _face_area(box: dict, face: str) -> float:
    axis, _ = FACE_AXES[face]
    axes = [0, 1, 2]
    axes.remove(axis)
    return (box["to"][axes[0]] - box["frm"][axes[0]]) * (box["to"][axes[1]] - box["frm"][axes[1]])


def compute_visible_faces(elements: list[dict]) -> list[set[str]]:
    """Per-element set of face names that are not zero-area and not fully buried."""
    boxes = [_norm_box(el) for el in elements]
    visible: list[set[str]] = []
    for i, box in enumerate(boxes):
        keep: set[str] = set()
        for face in FACE_AXES:
            if _face_area(box, face) < 1e-6:
                continue
            if box["has_rot"]:
                keep.add(face)
                continue
            samples = _face_samples(box, face)
            buried = False
            for j, other in enumerate(boxes):
                if i == j or other["has_rot"]:
                    continue
                if samples and all(_point_in(other, p) for p in samples):
                    buried = True
                    break
            if not buried:
                keep.add(face)
        visible.append(keep)
    return visible


def build_faces(frm, to, top: str, side: str, bottom: str | None, keep: set[str] | None) -> dict:
    bottom = bottom or side
    dx = abs(to[0] - frm[0])
    dy = abs(to[1] - frm[1])
    dz = abs(to[2] - frm[2])
    all_faces = {
        "north": {"uv": aspect_uv(side, dx, dy), "texture": "#0"},
        "east": {"uv": aspect_uv(side, dz, dy), "texture": "#0"},
        "south": {"uv": aspect_uv(side, dx, dy), "texture": "#0"},
        "west": {"uv": aspect_uv(side, dz, dy), "texture": "#0"},
        "up": {"uv": aspect_uv(top, dx, dz), "texture": "#0"},
        "down": {"uv": aspect_uv(bottom, dx, dz), "texture": "#0"},
    }
    if keep is None:
        return all_faces
    return {k: v for k, v in all_faces.items() if k in keep}


def snap_angle(angle: float) -> float:
    return min(LEGAL_ANGLES, key=lambda a: abs(a - angle))


def bb_rotation_to_java(el: dict) -> dict | None:
    rot = el.get("rotation") or [0, 0, 0]
    origin = [float(v) for v in (el.get("origin") or [8, 8, 8])]
    axes = [(i, float(rot[i])) for i in range(3) if abs(float(rot[i])) > 1e-6]
    if not axes:
        return None
    axis_i, angle = max(axes, key=lambda t: abs(t[1]))
    angle = snap_angle(angle)
    if abs(angle) < 1e-9:
        return None
    return {"angle": angle, "axis": "xyz"[axis_i], "origin": [round(v, 3) for v in origin]}


def remap_bb_faces(el: dict, keep: set[str]) -> dict:
    top, side, bottom = materials_for(el.get("name", ""))
    java_faces = build_faces(el["from"], el["to"], top, side, bottom, keep)
    out = {}
    for side_name, face in java_faces.items():
        out[side_name] = {"uv": face["uv"], "texture": 0}
    return out


def convert_groups(bb: dict, elements: list[dict], keep_indices: set[int]) -> list:
    """Convert outliner → Java groups, remapping element indices after drops."""
    old_to_new = {}
    new_i = 0
    for old_i, el in enumerate(elements):
        if old_i in keep_indices:
            old_to_new[el["uuid"]] = new_i
            new_i += 1
    groups_by_uuid = {g["uuid"]: g for g in bb.get("groups") or []}

    def convert_node(node):
        if isinstance(node, str):
            if node not in old_to_new:
                return None  # dropped invisible element
            return old_to_new[node]
        meta = groups_by_uuid.get(node.get("uuid"), {})
        children = []
        for c in node.get("children") or []:
            converted = convert_node(c)
            if converted is not None:
                children.append(converted)
        if not children and not meta.get("name"):
            return None
        return {
            "name": meta.get("name") or node.get("name") or "group",
            "origin": [float(v) for v in (meta.get("origin") or node.get("origin") or [8, 8, 8])],
            "color": int(meta.get("color", node.get("color", 0))),
            "children": children,
        }

    out = []
    for n in bb.get("outliner") or []:
        converted = convert_node(n)
        if converted is not None:
            out.append(converted)
    return out


def element_to_java(el: dict, keep: set[str]) -> dict | None:
    if not keep:
        return None
    top, side, bottom = materials_for(el.get("name", ""))
    out = {
        "name": el.get("name", "cube"),
        "from": [round(float(v), 3) for v in el["from"]],
        "to": [round(float(v), 3) for v in el["to"]],
        "faces": build_faces(el["from"], el["to"], top, side, bottom, keep),
    }
    rot = bb_rotation_to_java(el)
    if rot:
        out["rotation"] = rot
    return out


def atlas_stats(img: Image.Image, model_elements: list[dict]) -> tuple[int, int]:
    px = img.load()
    opaque = sum(1 for y in range(TEXTURE_SIZE) for x in range(TEXTURE_SIZE) if px[x, y][3] > 10)
    covered = set()
    for el in model_elements:
        for face in el["faces"].values():
            u0, v0, u1, v1 = face["uv"]
            x0, x1 = int(min(u0, u1)), int(max(u0, u1))
            y0, y1 = int(min(v0, v1)), int(max(v0, v1))
            for y in range(y0, max(y1, y0 + 1)):
                for x in range(x0, max(x1, x0 + 1)):
                    if 0 <= x < TEXTURE_SIZE and 0 <= y < TEXTURE_SIZE:
                        covered.add((x, y))
    return opaque, len(covered)


def main() -> None:
    bb = json.loads(BB_PATH.read_text(encoding="utf-8"))
    assert bb.get("resolution", {}).get("width") == TEXTURE_SIZE

    img = paint_atlas()
    TEX_PATH.parent.mkdir(parents=True, exist_ok=True)
    img.save(TEX_PATH)

    visible = compute_visible_faces(bb["elements"])
    culled_faces = 0
    dropped_elements = 0
    java_elements = []
    keep_indices: set[int] = set()
    for i, el in enumerate(bb["elements"]):
        keep = visible[i]
        culled_faces += 6 - len(keep)
        java = element_to_java(el, keep)
        if java is None:
            dropped_elements += 1
            continue
        keep_indices.add(i)
        java_elements.append(java)

    groups = convert_groups(bb, bb["elements"], keep_indices)

    model = {
        "credit": "KaibaCorp Battle City Duel Disk — geometry from Blockbench, dense atlas, culled hidden faces",
        "texture_size": [TEXTURE_SIZE, TEXTURE_SIZE],
        "textures": {
            "0": "ygomc:item/duel_disk",
            "particle": "ygomc:item/duel_disk",
        },
        "elements": java_elements,
        "groups": groups,
        "display": bb.get("display") or {},
    }
    MODEL_PATH.parent.mkdir(parents=True, exist_ok=True)
    MODEL_PATH.write_text(json.dumps(model, indent=2, ensure_ascii=False) + "\n", encoding="utf-8")

    # Remap UVs inside bbmodel + re-embed PNG; keep geometry/display/groups/outliner.
    # Culled faces are omitted from the cube (Blockbench: face disabled).
    png_bytes = TEX_PATH.read_bytes()
    b64 = base64.b64encode(png_bytes).decode("ascii")
    for i, el in enumerate(bb["elements"]):
        el["faces"] = remap_bb_faces(el, visible[i])
        el["autouv"] = 0
        el["box_uv"] = False
    if not bb.get("textures"):
        bb["textures"] = [{}]
    tex = bb["textures"][0]
    tex.update(
        {
            "name": "duel_disk.png",
            "folder": "item",
            "namespace": "ygomc",
            "id": str(tex.get("id", "0")),
            "width": TEXTURE_SIZE,
            "height": TEXTURE_SIZE,
            "uv_width": TEXTURE_SIZE,
            "uv_height": TEXTURE_SIZE,
            "particle": True,
            "internal": True,
            "saved": True,
            "source": f"data:image/png;base64,{b64}",
        }
    )
    bb["resolution"] = {"width": TEXTURE_SIZE, "height": TEXTURE_SIZE}
    BB_PATH.write_text(json.dumps(bb, indent="\t", ensure_ascii=False) + "\n", encoding="utf-8")

    opaque, covered = atlas_stats(img, java_elements)
    total = TEXTURE_SIZE * TEXTURE_SIZE
    print(f"Wrote {TEX_PATH}")
    print(f"Wrote {MODEL_PATH} elements={len(java_elements)} (dropped_fully_hidden={dropped_elements}) groups_root={len(groups)}")
    print(f"Updated {BB_PATH}")
    print(f"culled_faces={culled_faces} (of {len(bb['elements'])*6})")
    print(f"atlas opaque={opaque}/{total} ({100*opaque/total:.1f}%) UV-covered={covered}/{total} ({100*covered/total:.1f}%)")
    # sample material assignments for key parts
    for sample in ("hub_outer_ring_1", "hub_outer_ring_2", "left_wing_plate", "cuff_red_pad", "cuff_leather_body", "blade_root_plate"):
        print(f"  materials {sample}: {materials_for(sample)}")
    for el in java_elements:
        rot = el.get("rotation")
        if rot and rot["angle"] not in LEGAL_ANGLES:
            raise SystemExit(f"illegal angle on {el['name']}: {rot}")


if __name__ == "__main__":
    main()
