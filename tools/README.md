# tools

这些脚本只在本机跑，用来从 ygopro 目录和 YGOJSON 做出游戏要读的文件。产物都落在 `local-data/` 或直接覆盖同名贴图，不进 git。卡图、卡文、脚本的版权属于 KONAMI 和各脚本作者，不随模组分发。

游戏启动后也会自己做同样的事：按 `docs/ASSET_SYNC_PLAN.md` 把卡库、脚本和卡包下到版本文件夹的 `ygomc/`。这些脚本仍然是断网和排错的另一条路。pin 没变、而且目录里已经有玩家放好的文件时，同步不会盖掉它们。卡图、`cards.cdb` 和 Lua 脚本仍然不进 git、不进 jar。

本地 ygopro 目录默认是 `D:\MyCardLibrary\ygopro`。游戏认的是版本文件夹里的这三处：

| 游戏里的路径 | 谁生成 |
|---|---|
| `ygomc/datapack/cards.bin`、`texts.bin`、`strings.bin` | `mkdatapack.py` |
| `ygomc/datapack/pics.bin`、`back.jpg` | `mkpics.py` |
| `ygomc/script/` | 从 ygopro 的 `script/` 原样拷过去，没有单独的打包脚本 |
| `<存档>/datapacks/ygomc-sets/` | `mksets.py`，说明见 [sets.md](sets.md) |

## mkdatapack.py

把 `cards.cdb` 收成三份定长文件，游戏启动时整表读进内存，不再依赖 SQLite。

```
python tools/mkdatapack.py
python tools/mkdatapack.py D:\MyCardLibrary\ygopro\cards.cdb local-data\datapack
```

不写参数时，卡库用上面的默认 cdb，`strings.conf` 取 cdb 同目录，产物在 `local-data/datapack/`。

| 文件 | 内容 |
|---|---|
| `cards.bin` | 卡密和卡数据。引擎用的 80 字节结构，和 ygopro 的 DataManager 对齐 |
| `texts.bin` | 卡名、卡文、效果里的提示句 |
| `strings.bin` | 属性、种族、类型、系列名，来自 `strings.conf` |

`datas` 和 `texts` 的卡密对不上时脚本直接停，不会写出半份。

## mkpics.py

把 `pics/` 里以卡密命名的 jpg 打成一个 `pics.bin`，再把 `textures/cover.jpg` 抄成同目录的 `back.jpg`（卡背）。

```
python tools/mkpics.py
python tools/mkpics.py D:\MyCardLibrary\ygopro\pics local-data\datapack
```

三档图叠在同一个文件里：对局用的 200×290、物品栏用的 64×93、悬停大图用的 400×580。悬停档体积大，旧数据包可以只有前两档。缺图的卡密会列出来，游戏里回退到卡背。

## mksets.py

从 YGOJSON 生成卡包数据包（系列、抽法、盒装）。一包几张、没有抽法时怎么处理，写在 [sets.md](sets.md)。

```
python tools/mksets.py --from ygojson
```

没有卡密的印刷进不了卡池。卡密以 `cards.cdb` 为准，所以要先更新 `cards.bin`，卡包里才能开出新卡。

## mkplaceholdertextures.py

重画模组自带的物品、决斗台、决斗场地和组卡界面贴图，直接写进 `common/src/main/resources`。卡面不在这里。

```
python tools/mkplaceholdertextures.py
```

随机种子是固定的，重跑结果不变。需要 Pillow（`pip install Pillow`）。`mkpics.py` 也需要 Pillow。

## Duel Disk 模型 / 贴图

决斗盘的 Blockbench 工程、密集图集安装和预览脚本在 **.agent/tools/**（.agent/ 已纳入 git）：

- 主流程：python .agent/tools/install_duel_disk_from_bbmodel.py
- 说明：.agent/duel_disk_model.md

本目录的 mk* 脚本只管卡包数据 / 卡图 / 占位贴图，不管决斗盘网格。