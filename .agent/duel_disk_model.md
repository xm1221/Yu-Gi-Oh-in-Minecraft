# Duel Disk item model

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
