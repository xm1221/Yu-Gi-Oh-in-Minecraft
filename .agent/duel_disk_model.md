# Duel Disk item model

Classic anime **Standard Duel Disk** (Yugi): silver forearm hub + white blade with five blue monster zones.

- Model: `assets/ygomc/models/item/duel_disk.json` — vanilla JSON `elements` (no GeckoLib).
- Texture: `assets/ygomc/textures/item/duel_disk.png` — 64×64 atlas; UVs in vanilla 0–16 space.
- Silhouette: octagonal hub (red center plate, brown deck slot) → long white blade → V-notch tip; five recessed blue zones with tiny red triangle hints.
- Behavior of `DuelDiskItem` unchanged — visuals only.

## Verify in-game
1. `/give @s ygomc:duel_disk` (or creative inventory)
2. Check inventory icon (gui display) shows hub + blade
3. Hold in hand — blade should extend along the arm / outward
