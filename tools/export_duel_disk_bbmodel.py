# -*- coding: utf-8 -*-
"""Export duel_disk Java item JSON + PNG into a Blockbench .bbmodel with embedded texture."""
from __future__ import annotations

import base64
import json
import uuid
from pathlib import Path

ROOT = Path(r"E:\miemod\ygo\Yu-Gi-Oh in Minecraft")
ASSETS = ROOT / "common" / "src" / "main" / "resources" / "assets" / "ygomc"
MODEL_PATH = ASSETS / "models" / "item" / "duel_disk.json"
TEX_PATH = ASSETS / "textures" / "item" / "duel_disk.png"
OUT_PATH = ROOT / ".agent" / "blockbench" / "duel_disk.bbmodel"


def new_uuid() -> str:
    return str(uuid.uuid4())


def java_rotation_to_bb(rot):
    """Java {angle,axis,origin} -> (origin[3], rotation[3])."""
    if not rot:
        return [8.0, 8.0, 8.0], [0.0, 0.0, 0.0]
    origin = [float(v) for v in rot.get("origin", [8, 8, 8])]
    angle = float(rot.get("angle", 0))
    axis = rot.get("axis", "y")
    rotation = [0.0, 0.0, 0.0]
    if axis == "x":
        rotation[0] = angle
    elif axis == "y":
        rotation[1] = angle
    else:
        rotation[2] = angle
    return origin, rotation


def convert_faces(faces: dict) -> dict:
    out = {}
    for side, face in faces.items():
        uv = [float(v) for v in face["uv"]]
        # Java uses "#0"; bbmodel faces use numeric texture id matching textures[].id
        tex = face.get("texture", "#0")
        if isinstance(tex, str) and tex.startswith("#"):
            tex_id = int(tex[1:])
        else:
            tex_id = int(tex)
        entry = {"uv": uv, "texture": tex_id}
        if "rotation" in face:
            entry["rotation"] = face["rotation"]
        out[side] = entry
    return out


def convert_element(el: dict, color: int = 0) -> dict:
    origin, rotation = java_rotation_to_bb(el.get("rotation"))
    # If no rotation, put origin at box center for nicer BB pivots.
    if "rotation" not in el:
        frm, to = el["from"], el["to"]
        origin = [
            (frm[0] + to[0]) / 2.0,
            (frm[1] + to[1]) / 2.0,
            (frm[2] + to[2]) / 2.0,
        ]
        rotation = [0.0, 0.0, 0.0]
    return {
        "name": el.get("name", "cube"),
        "box_uv": False,
        "rescale": False,
        "locked": False,
        "light_emission": 0,
        "render_order": "default",
        "allow_mirror_modeling": True,
        "from": [float(v) for v in el["from"]],
        "to": [float(v) for v in el["to"]],
        "autouv": 0,
        "color": color,
        "origin": origin,
        "rotation": rotation,
        "faces": convert_faces(el["faces"]),
        "type": "cube",
        "uuid": new_uuid(),
    }


def convert_group(node, element_uuids: list[str], color_fallback: int = 0):
    """Java groups (nested dicts + int indices) -> bbmodel outliner group object."""
    children = []
    for child in node.get("children", []):
        if isinstance(child, int):
            children.append(element_uuids[child])
        elif isinstance(child, dict):
            children.append(convert_group(child, element_uuids, child.get("color", color_fallback)))
        else:
            raise TypeError(f"unexpected group child: {child!r}")
    return {
        "name": node.get("name", "group"),
        "origin": [float(v) for v in node.get("origin", [8, 8, 8])],
        "color": int(node.get("color", color_fallback)),
        "uuid": new_uuid(),
        "export": True,
        "isOpen": True,
        "locked": False,
        "visibility": True,
        "autouv": 0,
        "selected": False,
        "children": children,
    }


def main():
    model = json.loads(MODEL_PATH.read_text(encoding="utf-8"))
    png = TEX_PATH.read_bytes()
    b64 = base64.b64encode(png).decode("ascii")
    tw, th = model.get("texture_size", [128, 128])

    elements = []
    element_uuids = []
    for i, el in enumerate(model["elements"]):
        converted = convert_element(el, color=i % 8)
        elements.append(converted)
        element_uuids.append(converted["uuid"])

    groups = model.get("groups") or []
    if groups:
        outliner = [convert_group(g, element_uuids) for g in groups]
    else:
        outliner = list(element_uuids)

    tex_uuid = new_uuid()
    bb = {
        "meta": {
            "format_version": "4.10",
            "model_format": "java_block",
            "box_uv": False,
        },
        "name": "duel_disk",
        "model_identifier": "",
        "visible_box": [1, 1, 0],
        "variable_placeholders": "",
        "variable_placeholder_buttons": [],
        "timeline_setups": [],
        "unhandled_root_fields": {},
        "resolution": {"width": int(tw), "height": int(th)},
        "elements": elements,
        "outliner": outliner,
        "textures": [
            {
                "path": "",
                "name": "duel_disk.png",
                "folder": "item",
                "namespace": "ygomc",
                "id": "0",
                "group": "",
                "width": int(tw),
                "height": int(th),
                "uv_width": int(tw),
                "uv_height": int(th),
                "particle": True,
                "use_as_default": True,
                "layers_enabled": False,
                "sync_to_project": "",
                "render_mode": "default",
                "render_sides": "auto",
                "frame_time": 1,
                "frame_order_type": "loop",
                "frame_order": "",
                "frame_interpolate": False,
                "visible": True,
                "internal": True,
                "saved": True,
                "uuid": tex_uuid,
                "source": f"data:image/png;base64,{b64}",
            }
        ],
        "display": model.get("display", {}),
    }

    OUT_PATH.parent.mkdir(parents=True, exist_ok=True)
    OUT_PATH.write_text(json.dumps(bb, indent="\t", ensure_ascii=False) + "\n", encoding="utf-8")

    # Verify
    loaded = json.loads(OUT_PATH.read_text(encoding="utf-8"))
    assert loaded["textures"][0]["source"].startswith("data:image/png;base64,")
    assert loaded["textures"][0]["namespace"] == "ygomc"
    assert loaded["textures"][0]["folder"] == "item"
    assert loaded["outliner"] and loaded["outliner"][0]["name"] == "root"
    assert len(loaded["elements"]) == len(model["elements"])
    # Java model must still use ygomc:
    assert model["textures"]["0"] == "ygomc:item/duel_disk"
    print(f"Wrote {OUT_PATH}")
    print(f"  elements={len(loaded['elements'])} root_children={len(loaded['outliner'][0]['children'])}")
    print(f"  embedded_png_bytes={len(png)} bbmodel_bytes={OUT_PATH.stat().st_size}")


if __name__ == "__main__":
    main()
