# .agent/tools — Duel Disk authoring

`.agent/` is git-tracked on purpose. Duel-disk scripts live here; datapack/card scripts stay in repo-root `tools/`.

| Script | Purpose |
|---|---|
| `install_duel_disk_from_bbmodel.py` | **Primary.** Dense atlas + UV remap + write game JSON/PNG + re-embed bbmodel |
| `export_duel_disk_bbmodel.py` | Reverse: game assets → bbmodel |
| `gen_duel_disk_model.py` | Legacy full rebuild (overwrites geometry) |
| `render_duel_disk.py` | Blender previews into `.agent/previews/` |

See `../duel_disk_model.md`.