# -*- coding: utf-8 -*-
"""Install duel_disk game assets from the Blockbench project.

Source of truth for geometry/display/hierarchy:
  .agent/blockbench/duel_disk.bbmodel

This script:
  1) paints a dense 128x128 atlas (minimal empty waste)
  2) remaps every element UV to the packed material regions
  3) writes common/.../models/item/duel_disk.json + textures/item/duel_disk.png
  4) re-embeds the same PNG into the bbmodel (keeps user geometry/display/groups)
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
    "metal_light_panel": (112, 104, 128, 128),  # 16x24 — map hinges/rails side fallback
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

    # Hub core
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

    # Blade plate
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
    """Return (top, side, bottom) region names from element name."""
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
    if "left_wing_plate" in n or re.search(r"blade_.*_plate", n):
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
        return "metal_panel", "metal", "metal"
    if n.startswith("hub_blue_sector"):
        return "blue_panel", "blue_dark", "blue"
    if n == "hub_inner_core":
        return "hub_core", "red_dark", "black"
    if "spine" in n:
        return "white_panel", "silver_dark", "metal"
    # fallback
    return "metal_panel", "metal", "black"


def build_faces(frm, to, top: str, side: str, bottom: str | None = None) -> dict:
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


def remap_bb_faces(el: dict) -> dict:
    top, side, bottom = materials_for(el.get("name", ""))
    frm, to = el["from"], el["to"]
    java_faces = build_faces(frm, to, top, side, bottom)
    # BB faces use numeric texture id
    out = {}
    for side_name, face in java_faces.items():
        out[side_name] = {"uv": face["uv"], "texture": 0}
    return out


def convert_groups(bb: dict, elements: list[dict]) -> list:
    uuid_to_idx = {el["uuid"]: i for i, el in enumerate(elements)}
    groups_by_uuid = {g["uuid"]: g for g in bb.get("groups") or []}

    def convert_node(node):
        if isinstance(node, str):
            if node not in uuid_to_idx:
                raise KeyError(f"element uuid missing from model: {node}")
            return uuid_to_idx[node]
        meta = groups_by_uuid.get(node.get("uuid"), {})
        children = [convert_node(c) for c in node.get("children") or []]
        return {
            "name": meta.get("name") or node.get("name") or "group",
            "origin": [float(v) for v in (meta.get("origin") or node.get("origin") or [8, 8, 8])],
            "color": int(meta.get("color", node.get("color", 0))),
            "children": children,
        }

    return [convert_node(n) for n in (bb.get("outliner") or [])]


def element_to_java(el: dict) -> dict:
    top, side, bottom = materials_for(el.get("name", ""))
    out = {
        "name": el.get("name", "cube"),
        "from": [round(float(v), 3) for v in el["from"]],
        "to": [round(float(v), 3) for v in el["to"]],
        "faces": build_faces(el["from"], el["to"], top, side, bottom),
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

    java_elements = [element_to_java(el) for el in bb["elements"]]
    groups = convert_groups(bb, bb["elements"])

    model = {
        "credit": "KaibaCorp Battle City Duel Disk — geometry from Blockbench, dense atlas",
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
    png_bytes = TEX_PATH.read_bytes()
    b64 = base64.b64encode(png_bytes).decode("ascii")
    for el in bb["elements"]:
        el["faces"] = remap_bb_faces(el)
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
    print(f"Wrote {MODEL_PATH} elements={len(java_elements)} groups_root={len(groups)}")
    print(f"Updated {BB_PATH}")
    print(f"atlas opaque={opaque}/{total} ({100*opaque/total:.1f}%) UV-covered={covered}/{total} ({100*covered/total:.1f}%)")
    # ensure no illegal rotations
    for el in java_elements:
        rot = el.get("rotation")
        if rot and rot["angle"] not in LEGAL_ANGLES:
            raise SystemExit(f"illegal angle on {el['name']}: {rot}")


if __name__ == "__main__":
    main()