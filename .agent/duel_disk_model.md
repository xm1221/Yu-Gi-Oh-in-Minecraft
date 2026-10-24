# Duel Disk item model

Anime-inspired KaibaCorp Battle City Duel Disk (vanilla JSON item model).

- Model: `common/src/main/resources/assets/ygomc/models/item/duel_disk.json`
- Texture: `common/src/main/resources/assets/ygomc/textures/item/duel_disk.png` (128×128 dense atlas)
- Blockbench project (embedded texture): `.agent/blockbench/duel_disk.bbmodel`

## Source of truth

**Geometry / display / groups** live in `.agent/blockbench/duel_disk.bbmodel` (hand-tuned in Blockbench).

After BB edits, ship into the game with:

```
python .agent/tools/install_duel_disk_from_bbmodel.py
```

That script paints a **dense** 128×128 atlas (regions packed edge-to-edge, no giant unused slabs), remaps UVs onto the existing cubes, writes the Java item JSON + PNG, and re-embeds the same PNG into the bbmodel.

## Tooling (under `.agent/tools/`)

| Script | Role |
|---|---|
| `install_duel_disk_from_bbmodel.py` | **Primary** — BB → game assets + dense atlas + re-embed |
| `export_duel_disk_bbmodel.py` | Game JSON+PNG → bbmodel (recovery / reverse export) |
| `gen_duel_disk_model.py` | **Legacy** scratch generator (overwrites geometry — avoid after BB fine-tune) |
| `render_duel_disk.py` | Blender held/gui previews → `.agent/previews/` |

Datapack / card-art scripts stay in repo-root `tools/` (see `tools/README.md`).

## UV mapping

Faces use aspect-aware UVs inside named atlas patches (`REGIONS` in `install_duel_disk_from_bbmodel.py`). Each face keeps its width/height ratio and is scaled to fit inside its patch. `texture_size` is `[128, 128]`; in-game path stays `ygomc:item/duel_disk`.

## Group tree (Blockbench / Java `groups`)

Hierarchy follows the bbmodel outliner (left wing tip mirrors may sit under `blade/`). Typical tree:

```
root/
  cuff/
  blade/
    root_seg / mid_seg / outer_seg / zones / spine / tip / left_wing
  hub/
    struts / core / ring / sectors
```

## Blockbench

Open `.agent/blockbench/duel_disk.bbmodel` — PNG is embedded as base64. Prefer this file for editing.

After editing in BB, run `install_duel_disk_from_bbmodel.py` (do **not** strip the `ygomc:item/duel_disk` texture namespace when exporting by hand).

## Vanilla rotation limits

Element rotations only **-45, -22.5, 0, 22.5, 45**. Hub rings keep legal 45° Y; other angles were baked into AABBs earlier.

## Verify in-game

1. `/give @s ygomc:duel_disk`
2. Inventory icon: hub, wing panels, pointed tip
3. Third-person: cuff along the forearm
4. Blockbench: open the bbmodel — textured, grouped, no rotation warnings