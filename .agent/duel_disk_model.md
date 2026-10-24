# Duel Disk item model

Anime-inspired KaibaCorp Battle City Duel Disk, rebuilt from the supplied GIF.

- Model: `assets/ygomc/models/item/duel_disk.json` (vanilla JSON, no GeckoLib).
- Texture: `assets/ygomc/textures/item/duel_disk.png` (128x128 atlas).
- Silhouette: octagonal blue/silver hub, red mechanical core, leather forearm cuff,
  white shoulder wing, five blue monster-zone insets, silver dividers, and a pointed
  outer spearhead.
- Display transforms keep the disk readable in the GUI and align the cuff with the
  right/left forearm in third person.
- Behavior of `DuelDiskItem` is unchanged; this is a visual-only resource update.
- Regenerate with `python tools/gen_duel_disk_model.py`.

## Verify in-game

1. `/give @s ygomc:duel_disk`
2. Check the inventory icon shows the hub, five wing panels, and pointed tip.
3. Hold it in both hands and confirm the cuff aligns with the forearm.
4. Inspect the hub from the side; the layered ring and red core should remain legible.
