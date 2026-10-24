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

That script paints a **dense** 128×128 atlas, remaps **every** face UV onto its full material region (1px inset — never aspect-shrink flat faces to sub-pixel UVs), writes the Java item JSON + PNG, and re-embeds the same PNG into the bbmodel. Buried-face cull is **off** by default (CULL_BURIED_FACES=False).

## Material mapping (restored vs dense-atlas regression)

| Part | Atlas regions |
|---|---|
| Blade plates | `blade_top` (white armor) / silver_dark / metal |
| Left wing plate | `white_panel` (not blade_top) |
| Rails / dividers / rib | `white_panel` / `edge` |
| Monster zones / hub blue sectors / wing inset | `zone_*` / `blue_panel` |
| Hub outer ring | odd→`white_panel`, even→`silver` (**not** metal) |
| Hub inner core | `hub_core` (silver rim, blue sectors, red center) |
| Cuff leather / straps | `cuff_top` / `cuff_side` / brown |
| Cuff red pad | `red_panel` |
| Buckle / hinges / struts | `metal_panel` / metal |



## UV / in-game scramble audit (2026-10-24)

**Symptom:** game showed completely scrambled duel-disk textures while Blender/BB previews looked OK.

**Root cause:** spect_uv() aspect-fitted flat faces into tiny UV rects (often &lt;1px tall) inside edge-packed 8×8 palette swatches. Minecraft bilinear filtering and mipmaps bled neighboring materials → rainbow/wrong colors in-game.

**Fix:** map every face to the **full** atlas region with a 1px inset; palette swatches painted with a 1px transparent gutter; install fails if any face UV min-side &lt; 2px (ssert_uvs_sane).

**Checks after install:** 	exture_size [128,128], textures "0" / #0 → ygomc:item/duel_disk, 55 elements / 330 faces, UV min-side ≥ 6 after inset.

## Tooling (under `.agent/tools/`)

| Script | Role |
|---|---|
| `install_duel_disk_from_bbmodel.py` | **Primary** — BB → game assets + dense atlas + full-region UVs + re-embed |
| `export_duel_disk_bbmodel.py` | Game JSON+PNG → bbmodel (recovery / reverse export) |
| `gen_duel_disk_model.py` | **Legacy** scratch generator (overwrites geometry — avoid after BB fine-tune) |
| `render_duel_disk.py` | Blender held/gui previews → `.agent/previews/` |

## UV mapping

Faces use aspect-aware UVs inside named atlas patches (`REGIONS`). `texture_size` is `[128, 128]`; in-game path stays `ygomc:item/duel_disk`.

## Vanilla rotation limits

Element rotations only **-45, -22.5, 0, 22.5, 45**. Hub rings keep legal 45° Y.

## Verify in-game

1. `/give @s ygomc:duel_disk`
2. Inventory icon: hub (white/silver ring + blue sectors + red core), wing panels, pointed tip
3. Third-person: brown cuff + red pad along the forearm
4. Blockbench: open the bbmodel — textured, grouped, no rotation warnings
