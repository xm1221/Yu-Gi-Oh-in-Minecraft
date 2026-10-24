# Duel Disk item model

**KaibaCorp Battle City / Standard Duel Disk** — geometry from user-provided Gemini JSON
(`Downloads/gemini-code-1792809622875.json`, credit: "KaibaCorp Battle City Duel Disk - Custom Minecraft Model").

- Model: `assets/ygomc/models/item/duel_disk.json` — vanilla JSON `elements` (no GeckoLib).
- Texture: `assets/ygomc/textures/item/duel_disk.png` — 64×64 atlas; `texture_size: [64,64]` (Gemini UV pixel space).
- Textures namespaced: `ygomc:item/duel_disk`.
- 12 elements: `core_base`, `lp_counter_housing` (22.5° x), `deck_holder_right`, `graveyard_slot_front`,
  `blade_main_wing`, `blade_left_tip` (-22.5° y), `monster_zone_1..5`, `arm_cuff_mount`.
- Display: thirdperson_righthand arm-mount `[90,0,-90]` / `[-2,-1,2.5]` / `0.85` (kept from Gemini).
- Behavior of `DuelDiskItem` unchanged — visuals only.
- Regen: `python tools/gen_duel_disk_model.py` (reads Gemini source path above).

## Verify in-game
1. `/give @s ygomc:duel_disk` (or creative inventory)
2. Check inventory icon (gui display) shows hub + blade + cuff
3. Hold in hand — blade should extend along the forearm (arm-mount style)
