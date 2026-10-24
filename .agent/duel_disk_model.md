# Duel Disk item model

Anime-inspired KaibaCorp Battle City Duel Disk (vanilla JSON item model).

- Model: `assets/ygomc/models/item/duel_disk.json`
- Texture: `assets/ygomc/textures/item/duel_disk.png` (128x128 atlas)
- Regenerate: `python tools/gen_duel_disk_model.py`

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

Groups are written in Blockbench Java Block/Item format (`groups` array with
nested objects + element indices). Open the JSON in Blockbench as a **Java
Block/Item** model. Texture path stays `ygomc:item/duel_disk` for in-game use;
in Blockbench, File → Import → Texture and point at
`assets/ygomc/textures/item/duel_disk.png` (or set the resource pack root).

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
4. Blockbench: open model → Outliner shows `root/` tree; no rotation warnings
