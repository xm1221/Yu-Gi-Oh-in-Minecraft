# -*- coding: utf-8 -*-
"""Install duel_disk game assets from the Blockbench project.

Source of truth for geometry/display/hierarchy:
  .agent/blockbench/duel_disk.bbmodel

This script:
  1) paints a dense 128x128 atlas (large material regions only, 2px gutters;
     dark gunmetal blade — never white armor)
  2) remaps every element UV to FULL packed regions
  3) exports EVERY element and EVERY face (CULL_BURIED_FACES=False)
  4) writes CLEAN vanilla item JSON (no groups, no per-element name) —
     Minecraft UV space is 0–16 across the texture, so pixel UVs are scaled
     by 16/128. Pixel-space UVs in game JSON sample the stitched atlas → flower-screen.
  5) re-embeds the same PNG into the bbmodel with pixel UVs (Blockbench space)
"""
from __future__ import annotations

import base64
import json
import math
import re
import shutil
from pathlib import Path

from PIL import Image, ImageDraw

ROOT = Path(__file__).resolve().parents[2]
BB_PATH = ROOT / ".agent" / "blockbench" / "duel_disk.bbmodel"
ASSETS = ROOT / "common" / "src" / "main" / "resources" / "assets" / "ygomc"
MODEL_PATH = ASSETS / "models" / "item" / "duel_disk.json"
TEX_PATH = ASSETS / "textures" / "item" / "duel_disk.png"

# Sync copies so IDE bin / gradle build resources see the fix without a full rebuild.
SYNC_COPIES = [
    ROOT / "common" / "build" / "resources" / "main" / "assets" / "ygomc",
    ROOT / "common" / "bin" / "main" / "assets" / "ygomc",
    ROOT / "neoforge" / "build" / "resources" / "main" / "assets" / "ygomc",
    ROOT / "neoforge" / "bin" / "main" / "assets" / "ygomc",
]

TEXTURE_SIZE = 128
MC_UV_SPAN = 16.0  # vanilla model UV is always 0–16 across the whole texture
LEGAL_ANGLES = (-45.0, -22.5, 0.0, 22.5, 45.0)
OCCLUDE_EPS = 0.05
OCCLUDE_PROBE = 0.01
CULL_BURIED_FACES = False

TRANSPARENT = (0, 0, 0, 0)
BLACK = (17, 21, 28, 255)
BLACK_SOFT = (32, 38, 48, 255)
METAL = (70, 82, 98, 255)
METAL_LIGHT = (116, 132, 151, 255)
# Dark gunmetal for blade plates (classic Battle City — NOT white)
GUNMETAL = (48, 56, 68, 255)
GUNMETAL_LIGHT = (78, 90, 106, 255)
GUNMETAL_DARK = (28, 34, 44, 255)
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

# Large regions only (≥16×16). No 8×8 palette swatches for faces.
# 2px logical gutters between neighbors (paint leaves transparent / dark borders).
REGIONS = {
    "hub_core": (0, 0, 48, 48),              # 48×48
    "blade_plate": (50, 0, 114, 40),         # 64×40 dark gunmetal
    "zone_a": (116, 0, 128, 24),             # 12×24 — widen below
    "zone_b": (116, 26, 128, 50),            # 12×24
    "tip_top": (50, 42, 98, 74),             # 48×32
    "white_panel": (100, 42, 128, 74),       # 28×32
    "cuff_top": (0, 50, 48, 82),             # 48×32
    "cuff_side": (0, 84, 40, 116),           # 40×32
    "metal_panel": (42, 76, 90, 108),        # 48×32
    "edge": (92, 76, 128, 108),              # 36×32 silver edge / rails
    "red_panel": (0, 118, 32, 128),          # 32×10 — too short; fix pack
    "blue_panel": (34, 118, 66, 128),        # 32×10
    "silver_panel": (68, 110, 116, 128),     # 48×18
    "bolt": (118, 110, 128, 128),            # 10×18 — too narrow
}

# Re-pack with ALL regions ≥16×16 and 2px gaps.
REGIONS = {
    # Top band
    "hub_core": (0, 0, 48, 48),                 # 48×48
    "blade_plate": (50, 0, 114, 40),            # 64×40 gunmetal
    "zone_a": (50, 42, 82, 66),                 # 32×24
    "zone_b": (84, 42, 116, 66),                # 32×24
    "bolt": (118, 0, 128, 16),                  # will expand — use 16×16 at corner
    # Mid
    "tip_top": (0, 50, 48, 82),                 # 48×32
    "white_panel": (50, 68, 98, 100),           # 48×32
    "cuff_top": (100, 68, 128, 100),            # 28×32 → bump
    # Lower
    "cuff_side": (0, 84, 40, 116),              # 40×32
    "metal_panel": (42, 102, 90, 128),          # 48×26
    "edge": (92, 102, 128, 128),                # 36×26 silver rails
    "red_panel": (0, 118, 20, 128),             # conflict — rebuild cleanly below
}


def _rebuild_regions() -> dict[str, tuple[int, int, int, int]]:
    """Pack ≥16×16 regions with 2px gutters across 128×128."""
    # Manual pack: every region at least 16×16, 2px gap.
    return {
        # y0–47
        "hub_core": (0, 0, 48, 48),              # 48×48
        "blade_plate": (50, 0, 114, 42),         # 64×42 dark blade
        "bolt": (116, 0, 128, 16),               # 12×16 — fix to 16×16:
        # actually place bolt at (112,0)-(128,16) = 16×16, shrink blade
        # redo:
    }


# Final authoritative pack (validated below).
REGIONS = {
    "hub_core": (0, 0, 48, 48),                 # 48×48
    "blade_plate": (50, 0, 110, 42),            # 60×42 gunmetal
    "bolt": (112, 0, 128, 16),                  # 16×16
    "zone_a": (112, 18, 128, 42),               # 16×24
    "tip_top": (0, 50, 48, 82),                 # 48×32
    "zone_b": (50, 44, 82, 68),                 # 32×24
    "white_panel": (84, 44, 128, 76),           # 44×32
    "cuff_top": (50, 70, 98, 102),              # 48×32
    "metal_panel": (100, 78, 128, 110),         # 28×32
    "cuff_side": (0, 84, 48, 116),              # 48×32
    "edge": (50, 104, 98, 128),                 # 48×24 silver rails
    "red_panel": (100, 112, 128, 128),          # 28×16
    "blue_panel": (0, 118, 32, 128),            # 32×10 → expand by shrinking cuff_side
    "silver_panel": (34, 118, 82, 128),         # 48×10 → too short
}

# Clean pack — verified no overlap, all ≥16×16, 2px gaps where adjacent.
REGIONS = {
    "hub_core": (0, 0, 48, 48),                 # 48×48
    "blade_plate": (50, 0, 110, 40),            # 60×40 gunmetal
    "bolt": (112, 0, 128, 16),                  # 16×16
    "zone_a": (112, 18, 128, 40),               # 16×22
    "tip_top": (0, 50, 48, 82),                 # 48×32
    "zone_b": (50, 42, 82, 66),                 # 32×24
    "white_panel": (84, 42, 128, 74),           # 44×32
    "cuff_top": (50, 68, 98, 100),              # 48×32
    "metal_panel": (100, 76, 128, 108),         # 28×32
    "cuff_side": (0, 84, 48, 116),              # 48×32
    "edge": (50, 102, 98, 128),                 # 48×26 silver
    "red_panel": (100, 110, 128, 128),          # 28×18
    "blue_panel": (0, 118, 48, 128),            # 48×10 CONFLICT with cuff_side
    "silver_panel": (50, 102, 98, 128),         # same as edge — merge usage
}

# Absolute final pack: no overlaps, all ≥16×16, 2px gaps.
REGIONS = {
    "hub_core": (0, 0, 48, 48),                 # 48×48
    "blade_plate": (50, 0, 110, 40),            # 60×40 dark gunmetal
    "bolt": (112, 0, 128, 16),                  # 16×16
    "zone_a": (112, 18, 128, 40),               # 16×22
    "tip_top": (0, 50, 48, 82),                 # 48×32
    "zone_b": (50, 42, 82, 66),                 # 32×24
    "white_panel": (84, 42, 128, 74),           # 44×32
    "cuff_top": (50, 76, 98, 108),              # 48×32
    "metal_panel": (100, 76, 128, 108),         # 28×32
    "cuff_side": (0, 84, 48, 110),              # 48×26
    "blue_panel": (0, 112, 48, 128),            # 48×16
    "edge": (50, 110, 98, 128),                 # 48×18 silver rails
    "red_panel": (100, 110, 128, 128),          # 28×18
    "silver_panel": (50, 110, 98, 128),         # alias of edge
}


def validate_regions() -> None:
    grid = [[None] * TEXTURE_SIZE for _ in range(TEXTURE_SIZE)]
    for name, (x0, y0, x1, y1) in REGIONS.items():
        assert 0 <= x0 < x1 <= TEXTURE_SIZE and 0 <= y0 < y1 <= TEXTURE_SIZE, name
        w, h = x1 - x0, y1 - y0
        assert w >= 16 and h >= 16, f"{name} is {w}x{h}, need ≥16×16"
        for y in range(y0, y1):
            for x in range(x0, x1):
                prev = grid[y][x]
                if prev is not None and prev != name:
                    # allow silver_panel to share edge's pixels (alias)
                    if {prev, name} == {"edge", "silver_panel"}:
                        continue
                    raise SystemExit(f"region overlap at {(x, y)}: {prev} vs {name}")
                grid[y][x] = name


def paint_atlas() -> Image.Image:
    validate_regions()
    img = Image.new("RGBA", (TEXTURE_SIZE, TEXTURE_SIZE), TRANSPARENT)
    draw = ImageDraw.Draw(img)

    def fill(region, color, gutter: int = 2):
        x0, y0, x1, y1 = REGIONS[region]
        draw.rectangle(
            (x0 + gutter, y0 + gutter, x1 - 1 - gutter, y1 - 1 - gutter),
            fill=color,
        )

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

    # Hub core — silver rim, blue sectors, red mechanical center
    hx, hy, _, _ = REGIONS["hub_core"]
    fill("hub_core", BLACK, gutter=0)
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

    # Blade — DARK gunmetal (classic), NOT white
    fill("blade_plate", GUNMETAL_DARK, gutter=0)
    x0, y0, x1, y1 = REGIONS["blade_plate"]
    draw.rectangle((x0 + 2, y0 + 2, x1 - 3, y1 - 3), fill=GUNMETAL)
    draw.rectangle((x0 + 3, y0 + 3, x1 - 4, y1 - 4), fill=GUNMETAL_LIGHT)
    draw.rectangle((x0 + 4, y0 + 5, x1 - 5, y1 - 6), fill=GUNMETAL)
    draw.line((x0 + 4, y0 + 4, x1 - 5, y0 + 4), fill=METAL_LIGHT)
    draw.line((x0 + 4, y1 - 5, x1 - 5, y1 - 5), fill=GUNMETAL_DARK)
    for line_y in range(y0 + 7, y1 - 6, 3):
        draw.line((x0 + 5, line_y, x1 - 6, line_y), fill=GUNMETAL_DARK)

    def draw_zone(region, accent):
        fill(region, BLACK, gutter=0)
        x0, y0, x1, y1 = REGIONS[region]
        draw.rectangle((x0 + 2, y0 + 2, x1 - 3, y1 - 3), fill=SILVER_LIGHT)
        draw.rectangle((x0 + 3, y0 + 3, x1 - 4, y1 - 4), fill=BLUE)
        draw.rectangle((x0 + 3, y0 + 3, x1 - 4, y0 + 5), fill=BLUE_LIGHT)
        draw.rectangle((x0 + 3, y1 - 5, x1 - 4, y1 - 4), fill=BLUE_DARK)
        draw.polygon(
            [(x0 + 4, y0 + 6), (x0 + 10, y0 + 4), (x0 + 7, y0 + 12), (x0 + 3, y0 + 13)],
            fill=accent,
        )
        draw.line((x0 + 5, y0 + 14, x0 + 12, y0 + 7), fill=CYAN)
        draw.rectangle((x0 + 7, y0 + 8, x0 + 9, y0 + 10), fill=WHITE)

    draw_zone("zone_a", (76, 145, 221, 255))
    draw_zone("zone_b", (58, 127, 207, 255))

    fill("tip_top", SILVER_DARK, gutter=0)
    x0, y0, x1, y1 = REGIONS["tip_top"]
    draw.polygon(
        [
            (x0 + 2, y0 + 3),
            (x1 - 5, y0 + 3),
            (x1 - 2, y0 + 8),
            (x1 - 2, y1 - 8),
            (x1 - 5, y1 - 3),
            (x0 + 2, y1 - 3),
        ],
        fill=SILVER,
    )
    draw.line((x0 + 3, y0 + 4, x1 - 5, y0 + 4), fill=SILVER_LIGHT)
    draw.line((x0 + 3, y1 - 4, x1 - 5, y1 - 4), fill=SILVER_DARK)
    draw.rectangle((x0 + 5, y0 + 6, x0 + 9, y0 + 11), fill=BLUE_LIGHT)

    fill("bolt", BLACK, gutter=0)
    x0, y0, x1, y1 = REGIONS["bolt"]
    draw.rectangle((x0 + 2, y0 + 2, x1 - 3, y1 - 3), fill=SILVER_LIGHT)
    draw.rectangle((x0 + 3, y0 + 3, x1 - 4, y1 - 4), fill=GOLD)

    fill("cuff_top", BROWN_DARK, gutter=0)
    x0, y0, x1, y1 = REGIONS["cuff_top"]
    draw.rectangle((x0 + 2, y0 + 2, x1 - 3, y1 - 3), fill=BROWN)
    for stitch_y in (y0 + 3, y1 - 4):
        for stitch_x in range(x0 + 3, x1 - 3, 3):
            draw.rectangle((stitch_x, stitch_y, stitch_x + 1, stitch_y), fill=BROWN_LIGHT)
    draw.rectangle((x0 + 7, y0 + 5, x1 - 8, y1 - 6), fill=BROWN_DARK)
    draw.rectangle((x0 + 8, y0 + 6, x1 - 9, y1 - 7), fill=BROWN_LIGHT)

    fill("cuff_side", BROWN_DARK, gutter=0)
    x0, y0, x1, y1 = REGIONS["cuff_side"]
    draw.rectangle((x0 + 2, y0 + 2, x1 - 3, y1 - 3), fill=BROWN)
    draw.line((x0 + 3, y0 + 5, x1 - 4, y0 + 5), fill=BROWN_LIGHT)
    draw.line((x0 + 3, y1 - 6, x1 - 4, y1 - 6), fill=BROWN_DARK)

    fill("metal_panel", METAL, gutter=0)
    x0, y0, x1, y1 = REGIONS["metal_panel"]
    draw.rectangle((x0 + 2, y0 + 2, x1 - 3, y1 - 3), fill=METAL_LIGHT)
    draw.rectangle((x0 + 4, y0 + 4, x1 - 5, y1 - 5), fill=METAL)
    draw.line((x0 + 4, y0 + 5, x1 - 6, y1 - 6), fill=BLACK_SOFT)
    for bx in (x0 + 3, x1 - 5):
        for by in (y0 + 3, y1 - 5):
            draw.rectangle((bx, by, bx + 1, by + 1), fill=SILVER_LIGHT)

    fill("white_panel", SILVER, gutter=0)
    x0, y0, x1, y1 = REGIONS["white_panel"]
    draw.rectangle((x0 + 2, y0 + 2, x1 - 3, y1 - 3), fill=WHITE)
    draw.line((x0 + 3, y0 + 4, x1 - 4, y0 + 4), fill=SILVER_LIGHT)
    draw.line((x0 + 3, y1 - 5, x1 - 4, y1 - 5), fill=SILVER_DARK)

    # edge + silver_panel share rect — paint once
    fill("edge", SILVER_DARK, gutter=0)
    x0, y0, x1, y1 = REGIONS["edge"]
    draw.rectangle((x0 + 2, y0 + 2, x1 - 3, y1 - 3), fill=SILVER)
    draw.rectangle((x0 + 4, y0 + 4, x1 - 5, y1 - 5), fill=METAL_LIGHT)
    draw.line((x0 + 3, y0 + 3, x1 - 4, y0 + 3), fill=SILVER_LIGHT)
    draw.line((x0 + 3, y1 - 4, x1 - 4, y1 - 4), fill=BLACK_SOFT)

    fill("red_panel", RED_DARK, gutter=0)
    x0, y0, x1, y1 = REGIONS["red_panel"]
    draw.rectangle((x0 + 2, y0 + 2, x1 - 3, y1 - 3), fill=RED)
    draw.rectangle((x0 + 4, y0 + 4, x1 - 5, y1 - 5), fill=RED_DARK)
    draw.line((x0 + 4, y0 + 3, x1 - 5, y0 + 3), fill=RED_LIGHT)

    fill("blue_panel", BLUE_DARK, gutter=0)
    x0, y0, x1, y1 = REGIONS["blue_panel"]
    draw.rectangle((x0 + 2, y0 + 2, x1 - 3, y1 - 3), fill=BLUE)
    draw.polygon([(x0 + 3, y0 + 3), (x0 + 12, y0 + 3), (x0 + 7, y0 + 12)], fill=BLUE_LIGHT)
    draw.line((x0 + 4, y1 - 4, x1 - 5, y0 + 5), fill=CYAN)

    return img


def aspect_uv_px(region: str) -> list[float]:
    """Pixel-space UV covering nearly the full region (2px inset for gutters)."""
    rx0, ry0, rx1, ry1 = REGIONS[region]
    rw = float(rx1 - rx0)
    rh = float(ry1 - ry0)
    inset = 2.0 if rw >= 8.0 and rh >= 8.0 else 0.5
    u0 = rx0 + inset
    v0 = ry0 + inset
    u1 = rx1 - inset
    v1 = ry1 - inset
    if u1 <= u0:
        u0, u1 = float(rx0), float(rx1)
    if v1 <= v0:
        v0, v1 = float(ry0), float(ry1)
    return [u0, v0, u1, v1]


def to_mc_uv(uv_px: list[float]) -> list[float]:
    """Convert Blockbench/pixel UV (0–TEXTURE_SIZE) → vanilla Minecraft UV (0–16)."""
    s = MC_UV_SPAN / float(TEXTURE_SIZE)
    return [round(c * s, 4) for c in uv_px]


def materials_for(name: str) -> tuple[str, str, str]:
    """Return (top, side, bottom) LARGE region names only — never 8×8 swatches."""
    n = name or ""
    m = re.match(r"monster_zone_(\d+)", n)
    if m:
        z = "zone_a" if int(m.group(1)) % 2 else "zone_b"
        return z, "blue_panel", "blue_panel"
    if "zone_divider" in n or n.endswith("_rail") or n.endswith("_rib"):
        return "white_panel", "edge", "silver_panel"
    if "tip" in n:
        return "tip_top", "white_panel", "silver_panel"
    if "left_wing_inset" in n:
        return "blue_panel", "blue_panel", "blue_panel"
    if n.startswith("left_wing_plate"):
        return "white_panel", "silver_panel", "metal_panel"
    if re.search(r"blade_.*_plate", n):
        return "blade_plate", "metal_panel", "metal_panel"
    if n == "cuff_black_core":
        return "metal_panel", "metal_panel", "metal_panel"
    if n in ("cuff_leather_body", "cuff_upper_strap", "cuff_lower_strap"):
        return "cuff_top", "cuff_side", "cuff_side"
    if n == "cuff_silver_buckle":
        return "metal_panel", "metal_panel", "metal_panel"
    if n == "cuff_buckle_inset":
        return "metal_panel", "metal_panel", "metal_panel"
    if n == "cuff_red_pad":
        return "red_panel", "red_panel", "metal_panel"
    if "hinge" in n or "strut" in n:
        return "metal_panel", "metal_panel", "metal_panel"
    if n.startswith("hub_outer_ring"):
        mm = re.search(r"(\d+)$", n)
        idx = int(mm.group(1)) if mm else 1
        if idx % 2:
            return "white_panel", "silver_panel", "metal_panel"
        return "silver_panel", "edge", "metal_panel"
    if n.startswith("hub_blue_sector"):
        return "blue_panel", "blue_panel", "blue_panel"
    if n == "hub_inner_core":
        return "hub_core", "red_panel", "metal_panel"
    if "bolt" in n:
        return "bolt", "metal_panel", "metal_panel"
    if "spine" in n:
        return "white_panel", "silver_panel", "metal_panel"
    return "metal_panel", "metal_panel", "metal_panel"


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


def build_faces_px(frm, to, top: str, side: str, bottom: str | None, keep: set[str] | None) -> dict:
    """Build faces with pixel-space UVs (for bbmodel / sanity checks)."""
    bottom = bottom or side
    all_faces = {
        "north": {"uv": aspect_uv_px(side), "texture": "#0"},
        "east": {"uv": aspect_uv_px(side), "texture": "#0"},
        "south": {"uv": aspect_uv_px(side), "texture": "#0"},
        "west": {"uv": aspect_uv_px(side), "texture": "#0"},
        "up": {"uv": aspect_uv_px(top), "texture": "#0"},
        "down": {"uv": aspect_uv_px(bottom), "texture": "#0"},
    }
    if keep is None:
        return all_faces
    return {k: v for k, v in all_faces.items() if k in keep}


def faces_to_mc(faces_px: dict) -> dict:
    """Scale pixel UVs → vanilla 0–16 for the game JSON."""
    out = {}
    for side_name, face in faces_px.items():
        out[side_name] = {
            "uv": to_mc_uv(face["uv"]),
            "texture": face["texture"],
        }
    return out


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
    """Blockbench faces: pixel UV + texture id 0 (integer)."""
    top, side, bottom = materials_for(el.get("name", ""))
    java_faces = build_faces_px(el["from"], el["to"], top, side, bottom, keep)
    out = {}
    for side_name, face in java_faces.items():
        out[side_name] = {"uv": [round(c, 3) for c in face["uv"]], "texture": 0}
    return out


def element_to_java(el: dict, keep: set[str]) -> dict | None:
    """Clean vanilla element: from/to/faces[/rotation] only — no name."""
    if not keep:
        return None
    top, side, bottom = materials_for(el.get("name", ""))
    faces_px = build_faces_px(el["from"], el["to"], top, side, bottom, keep)
    out = {
        "from": [round(float(v), 3) for v in el["from"]],
        "to": [round(float(v), 3) for v in el["to"]],
        "faces": faces_to_mc(faces_px),
    }
    rot = bb_rotation_to_java(el)
    if rot:
        out["rotation"] = rot
    return out


def assert_uvs_sane_px(model_elements_names: list[tuple[str, dict]], min_side_px: float = 8.0) -> None:
    """Fail if any pixel UV (before MC scale) is too small."""
    bad = []
    for name, faces_px in model_elements_names:
        for face, fd in faces_px.items():
            u0, v0, u1, v1 = fd["uv"]
            w, h = abs(u1 - u0), abs(v1 - v0)
            if min(w, h) < min_side_px:
                bad.append((name, face, w, h, fd["uv"]))
    if bad:
        preview = ", ".join(f"{n}.{f}={w:.3f}x{h:.3f}" for n, f, w, h, _ in bad[:8])
        raise RuntimeError(
            f"{len(bad)} face UVs smaller than {min_side_px}px: {preview}"
        )


def assert_mc_uvs_in_range(elements: list[dict]) -> None:
    """Game JSON UVs must sit in ~0–16 (vanilla)."""
    bad = []
    for i, el in enumerate(elements):
        for face, fd in el.get("faces", {}).items():
            for c in fd["uv"]:
                if c < -0.01 or c > 16.01:
                    bad.append((i, face, fd["uv"]))
                    break
    if bad:
        raise RuntimeError(f"{len(bad)} faces have UV outside 0–16 (flower-screen risk): {bad[:5]}")


def sample_blade_is_dark(img: Image.Image) -> None:
    x0, y0, x1, y1 = REGIONS["blade_plate"]
    px = img.load()
    cx, cy = (x0 + x1) // 2, (y0 + y1) // 2
    r, g, b, a = px[cx, cy]
    luma = 0.299 * r + 0.587 * g + 0.114 * b
    if luma > 120:
        raise RuntimeError(f"blade_plate center too bright luma={luma:.1f} rgb={(r,g,b)} (want dark gunmetal)")


def atlas_stats(img: Image.Image, model_elements: list[dict]) -> tuple[int, int]:
    px = img.load()
    opaque = sum(1 for y in range(TEXTURE_SIZE) for x in range(TEXTURE_SIZE) if px[x, y][3] > 10)
    covered = set()
    s = float(TEXTURE_SIZE) / MC_UV_SPAN
    for el in model_elements:
        for face in el["faces"].values():
            # convert MC UV back to pixels for coverage estimate
            u0, v0, u1, v1 = [c * s for c in face["uv"]]
            x0, x1 = int(min(u0, u1)), int(max(u0, u1))
            y0, y1 = int(min(v0, v1)), int(max(v0, v1))
            for y in range(y0, max(y1, y0 + 1)):
                for x in range(x0, max(x1, x0 + 1)):
                    if 0 <= x < TEXTURE_SIZE and 0 <= y < TEXTURE_SIZE:
                        covered.add((x, y))
    return opaque, len(covered)


def sync_to_build_dirs() -> list[str]:
    """Copy model+png into gradle/IDE resource outputs so runClient sees them."""
    updated = []
    for base in SYNC_COPIES:
        if not base.parent.exists() and not base.exists():
            # only sync into dirs that already exist (don't invent neo trees)
            continue
        model_dst = base / "models" / "item" / "duel_disk.json"
        tex_dst = base / "textures" / "item" / "duel_disk.png"
        model_dst.parent.mkdir(parents=True, exist_ok=True)
        tex_dst.parent.mkdir(parents=True, exist_ok=True)
        shutil.copy2(MODEL_PATH, model_dst)
        shutil.copy2(TEX_PATH, tex_dst)
        updated.append(str(model_dst))
        updated.append(str(tex_dst))
    return updated


def main() -> None:
    bb = json.loads(BB_PATH.read_text(encoding="utf-8"))
    assert bb.get("resolution", {}).get("width") == TEXTURE_SIZE

    img = paint_atlas()
    sample_blade_is_dark(img)
    TEX_PATH.parent.mkdir(parents=True, exist_ok=True)
    img.save(TEX_PATH)

    all_faces = set(FACE_AXES)
    if CULL_BURIED_FACES:
        visible = compute_visible_faces(bb["elements"])
    else:
        visible = [set(all_faces) for _ in bb["elements"]]

    culled_faces = 0
    dropped_elements = 0
    java_elements = []
    px_for_assert: list[tuple[str, dict]] = []
    for i, el in enumerate(bb["elements"]):
        keep = visible[i]
        culled_faces += 6 - len(keep)
        name = el.get("name", f"el{i}")
        top, side, bottom = materials_for(name)
        faces_px = build_faces_px(el["from"], el["to"], top, side, bottom, keep)
        px_for_assert.append((name, faces_px))
        java = element_to_java(el, keep)
        if java is None:
            dropped_elements += 1
            continue
        java_elements.append(java)

    assert_uvs_sane_px(px_for_assert, min_side_px=8.0)
    assert_mc_uvs_in_range(java_elements)

    # CLEAN vanilla item JSON — no groups, no texture_size, no element names.
    # groups/name/texture_size stay in the .bbmodel only.
    model = {
        "credit": "KaibaCorp Battle City Duel Disk — clean vanilla UV 0-16",
        "textures": {
            "0": "ygomc:item/duel_disk",
            "particle": "ygomc:item/duel_disk",
        },
        "elements": java_elements,
        "display": bb.get("display") or {},
    }

    # Hard checks for flower-screen causes
    raw = json.dumps(model)
    if '"groups"' in raw:
        raise RuntimeError("game JSON must not contain groups")
    if any("name" in el for el in java_elements):
        raise RuntimeError("game JSON elements must not contain name")

    MODEL_PATH.parent.mkdir(parents=True, exist_ok=True)
    MODEL_PATH.write_text(json.dumps(model, indent=2, ensure_ascii=False) + "\n", encoding="utf-8")

    # Remap UVs inside bbmodel + re-embed PNG (pixel space for Blockbench).
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

    synced = sync_to_build_dirs()

    opaque, covered = atlas_stats(img, java_elements)
    total = TEXTURE_SIZE * TEXTURE_SIZE
    print(f"Wrote {TEX_PATH}")
    print(f"Wrote {MODEL_PATH} elements={len(java_elements)} (dropped={dropped_elements})")
    print(f"Updated {BB_PATH}")
    print(f"culled_faces={culled_faces} (of {len(bb['elements'])*6}) CULL_BURIED_FACES={CULL_BURIED_FACES}")
    print(f"atlas opaque={opaque}/{total} ({100*opaque/total:.1f}%) UV-covered={covered}/{total} ({100*covered/total:.1f}%)")
    print(f"UV space: pixel→MC scale {MC_UV_SPAN}/{TEXTURE_SIZE} (fixes atlas flower-screen)")
    print(f"synced {len(synced)} build/bin copies")
    for sample in (
        "hub_outer_ring_1",
        "hub_outer_ring_2",
        "left_wing_plate",
        "cuff_red_pad",
        "cuff_leather_body",
        "blade_root_plate",
    ):
        print(f"  materials {sample}: {materials_for(sample)}")
    # show one MC UV sample
    print(f"  sample MC UV: {java_elements[0]['faces']['north']['uv']}")
    for el in java_elements:
        rot = el.get("rotation")
        if rot and rot["angle"] not in LEGAL_ANGLES:
            raise SystemExit(f"illegal angle: {rot}")


if __name__ == "__main__":
    main()
