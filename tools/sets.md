# 卡包数据包

`tools/mksets.py` 把 [YGOJSON](https://github.com/iconmaster5326/YGOJSON) 里的 OCG 系列收成一份 Minecraft 数据包。模组启动后读取里面的三张表：系列收录、卡包抽法、盒装卡包。

产物在 `local-data/sets/`，被 git 忽略。包名和卡号清单不进仓库。模组 jar 里另有一个 `ygomc:demo`，没有这份数据包也能试开包。

## 生成

需要 Python 3，不需要额外的库。在仓库根目录执行：

```
python tools/mksets.py --from ygojson
```

第一次会下载 `individual.zip`（约几十到上百 MB），存到 `local-data/ygojson/individual.zip`。已经下过就直接用本地文件。换一份 zip：

```
python tools/mksets.py --from ygojson --zip D:\path\individual.zip
```

换输出目录：

```
python tools/mksets.py --from ygojson --out D:\path\sets
```

跑完会打印四行数字：系列、写成卡包的数量、盒装、以及「有收录但没有抽法」的系列数。最后如果出现未识别的罕贵名字，那些卡已经按普通卡计入，名字列在同一行里。

## 产物

```
local-data/sets/
  pack.mcmeta
  data/ygomc/ygomc/
    card_set/<id>.json    这个系列收录了哪些卡、各是什么罕贵
    booster/<id>.json     怎么抽。没有抽法数据的系列不会有这个文件
    booster_box/<id>.json 盒装：几包、对应哪个卡包
```

只收带日文地区（`locales.jp`）的系列。文件名来自卡号前缀，例如 `LOB-` 变成 `lob`；对不上时用英文名，再不行就用 YGOJSON 的 id。重名时在后面加 `_2`、`_3`。

罕贵先归进模组的五档再写进 JSON：普通、稀有、超稀、极稀、秘稀。平行、金闪、碎钻这些细档的对照表在 `mksets.py` 的 `RARITY`。YGOJSON 里一张卡印了多张（`qty` 不是 1）时，写入 `prints`，权重就是张数。

一包几张写在抽法的 `qty` 上。`chance` 是「1 比 X」，脚本把倒数通分成整数权重。

日文系列上经常挂的是名叫 “TCG …” 的九张表（7 张普通加 2 张更高）。那是 TCG 的包规格，不是 OCG 的。脚本留下里面的罕贵比例，把张数收成五张：普通那一档被削短，稀有和更高的卡位不动。决斗包、剧场版这些本来就是五张或一张的，不改。

源数据里标了 `preconstructed` 的是预组：每个稀有度有几张，一包就开出几张，同包不重复，等于把这一套都给你。

剩下没有抽法的系列仍然生成卡包。五张，每一张按「这个稀有度实际有多少张印刷」来抽，不把剩下的最高罕贵写成必出。整个系列不足五张时，一包开出全部。源数据里没有卡号的印刷（新卡常常还没有密码）进不了池，日志里会单列丢掉的张数。

盒装只收日文地区那一条内容：盒内只有一种包，而且那种包已经写成 booster。同一商品在欧美是 36 包、在日文是 15 包时，用日文的包数。装法是每包独立抽，不写死一盒里有几张秘稀。

## 装进游戏

把 `local-data/sets` 整个目录复制到**存档**的数据包目录，不要放进模组的 `mods`，也不要和 `ygomc/datapack`（卡图卡文）混在一起：

```
<存档>/datapacks/ygomc-sets/
```

目录里要能直接看到 `pack.mcmeta`。进游戏后执行 `/reload`。日志里会有一行「套包：系列 …，卡包 …，盒装 …」。卡号不在 `ygomc/datapack` 的卡片数据库里时会被剔出卡池，并计为警告。

然后：

```
/ygomc pack give <id>
/ygomc pack box <id>
/ygomc pack odds <id>
```

`<id>` 是文件名。不写命名空间时默认是 `ygomc`，所以 `sod_jp` 和 `ygomc:sod_jp` 是同一个包。`give`、`box`、`simulate` 需要权限 2。右键开包；盒装卡包右键拆成包，潜行右键直接开成卡。


## 包面图

`locales.jp.image`（YGOJSON / Yugipedia 产品图 URL）会写入 booster 的 `display.face`。
游戏开始时客户端尽量一次下载到 `ygomc/cache/pack_faces/`；缺图时开包界面再按需补。
不入库、不进 jar。罕贵仍按 YGOJSON 的 rarity 映射到 common/rare/super/ultra/secret，不编造比例。
