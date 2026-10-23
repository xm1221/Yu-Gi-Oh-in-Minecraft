# ygomc 启动资源同步实现计划

本文只定实现方案。按此文写代码时视为已批准，不必再等确认。本文件不包含可运行的同步实现。

读者是后续在本机改这个仓库的人。规则内核、对局、开包语义都不要另起一套。同步只负责把游戏目录里已经在读的那些文件备齐，然后走现有的打开、重载、校验路径。

本文细化 `.agent/PLAN.md` 里「启动时不会自己下载」那一条，并修正「卡数据用 YGOPRODeck、脚本用 ProjectIgnis」的草案。原因写在第 3 节。

## 1. 目标与非目标

玩家打开整合包或专用服务器时，不再必须先在仓库里跑 `tools/mkdatapack.py`、`tools/mkpics.py`、`tools/mksets.py`，也不必手拷 `script/`。模组在后台拉取并落到版本文件夹的 `ygomc/`，失败时继续用已经在磁盘上的文件。

要自动拿到的四类东西：

| 玩家要的 | 游戏实际读的文件 | 谁用 |
|---|---|---|
| 卡密、攻守、属性、种族、系列码 | `ygomc/datapack/cards.bin` | 专用服、整合服、连到别人服的客户端（本地查卡） |
| 卡名、卡文、效果提示、属性名 | `texts.bin`、`strings.bin` | 同上 |
| 卡片逻辑 | `ygomc/script/`（必须有 `constant.lua`） | 开局的那一侧。专用服必须有；纯客户端可以没有 |
| 卡图 | 按卡密缓存的 JPEG，再按现有档位缩放 | 只给客户端 |
| 卡包 / 盒装 | 一份 Minecraft 数据包，三张动态注册表 | 开包在服务端算，所以专用服和整合服都要有 |

不做：

- 不把 KONAMI 卡图、卡文、`cards.cdb`、生成出来的卡包 JSON 提交进公开仓库，也不打进 jar。
- 不把 GPLv2 的 Lua 脚本打进 jar。脚本与本仓库的 GPL-3.0-or-later 不能一起分发，这一点 `OcgEngine` 的类注释已经写明。运行时由玩家机器自己下载，和现在「用户自己拷脚本」是同一类做法。
- 启动时不下载全库卡图。`.agent/PLAN.md` 已定「启动时不下载全部卡图」。全库大约一万五千张，YGOPRODeck 写明高频拉图会封 IP。
- 不改决斗规则、不把开包结果写进物品、不做异画序号与网络图的对应（百鸽和 YGOPRODeck 都是一卡密一张图，`.agent/PLAN.md` 已说明）。
- 不在本阶段做战利品表、村民交易、NPC 奖励。
- 离线脚本保留，作为断网和排错的另一条路。

## 2. 现在的数据流

游戏目录是 `Platform.getGameFolder()`，也就是版本文件夹或专用服根目录。开发时 NeoForge 是 `neoforge/run/`，Fabric 同理。相对路径不能相对进程的工作目录。

### 2.1 卡表与卡图

`DataPack.NESTED_DIR` 固定为 `ygomc/datapack`。`DataPack.candidates` 只拼这一处，不认系统属性，也不向上找 `local-data/datapack`。

`DataPack.open` 依次打开，缺一记一条 `problems()`，不抛异常：

| 文件 | 魔数 / 版本 | 读取类 | 生成脚本 |
|---|---|---|---|
| `cards.bin` | `YGOMCCD1` / 1，头 20 字节，每条 84 字节 = `u32` 卡密 + 80 字节 `card_data` | `CardDataDb` | `tools/mkdatapack.py` |
| `texts.bin` | `YGOMCTX2` / 2，头 28 字节，索引项 148 字节 | `CardTextDb` | 同上 |
| `strings.bin` | `YGOMCST1` / 1，头 40 字节 | `StringsDb` | 同上，源是 `strings.conf` |
| `pics.bin` | `YGOMCPIC` / 1，头 32 字节 | `CardImageDb` | `tools/mkpics.py` |
| `back.jpg` | 无魔数，与 `pics.bin` 同级 | `DataPack.cardBack` | `mkpics.py` 从 `textures/cover.jpg` 拷贝 |

`cards.bin` 整文件进内存（约 1.2 MB），`Ocg.putCards(codes, blob)` 原样灌进内核。`pics.bin` 只把索引读进内存，JPEG 用 `FileChannel` 按位置读。档位必须与 `CardImageDb` 对齐：0 = FULL 200×290，1 = ICON 64×93，2 = HOVER 400×580（可选，没有就回退 FULL）。

`DataPacks` 是运行期持有者。`Ygomc.init()` 里调用 `DataPacks.init()`，只为了在启动日志里看见缺文件。缺文件是预期情况，不阻断模组加载。`DataPacks.reload()` 会关掉旧实例，下次 `get()` 重新打开。类注释写明：已经解码进显存的卡图不会因此失效，那是 `CardTextures` 的事。同步完成后必须补上这一步，否则换了 `pics.bin` 或散图缓存，画面仍是旧图。

`DataPack.isComplete()` 只要求 `cards.bin`、`texts.bin`、`pics.bin`。`strings.bin` 缺失时对局仍可进行，属性名会退化成「属性 0x…」。专用服可以没有 `pics.bin`，此时 `isComplete()` 为假，但引擎只依赖 `cards.bin`。不要为了专用服去强行把卡图算进「服务器就绪」。

### 2.2 脚本与引擎

`OcgEngine.scriptCandidates` 只认 `<游戏目录>/ygomc/script`。目录里必须有 `constant.lua`，否则 `firstRoot` 认为这不是脚本根。`prepareOrThrow` 的顺序是：`Natives.ensureLoaded` → `Ocg.init(脚本根)` → `DataPacks.get().cardData()` → `Ocg.putCards`。

`prepare()` 有 `attempted` 标志，失败也只试一次。今天缺脚本时日志是「引擎不可用」，之后即使文件到了，再调 `prepare()` 也不会重试。同步落地之后必须新增一次受控的重新装配（见第 6 节）。对局进行中禁止换卡表或换脚本根：内核在 `Ocg.create` 时把卡表快照进对局，`Ocg.init` 换根会清脚本缓存。

`Ygomc.init()` 在两个平台入口都会跑，专用服也会跑。客户端连别人的服时同样会尝试装配引擎；失败只打日志。对局权威在服务端（`.agent/AGENT.md`）。网上传的是卡密和状态，不是整段卡文，所以连出去的客户端自己也要有 `cards.bin` / `texts.bin` / 卡图，才能在本地画出卡名和卡面。脚本它可以用没有。

### 2.3 卡包

三张动态注册表的键在 `PackRegistries`：`ygomc:card_set`、`ygomc:booster`、`ygomc:booster_box`。JSON 路径是 `data/<命名空间>/ygomc/card_set|booster|booster_box/`。

登记点：

- Fabric：`fabric/.../YgomcFabric.java` 里 `DynamicRegistries.registerSynced`，三条都用同一份 Codec，客户端才能看到包名。
- NeoForge：`neoforge/.../YgomcNeoForge.java` 的 `DataPackRegistryEvent.NewRegistry`。

校验点：

- Fabric：`ServerLifecycleEvents.SERVER_STARTED` 和 `END_DATA_PACK_RELOAD`（仅 `success`）调用 `PackCatalog.validate`。
- NeoForge：`AddReloadListenerEvent` 里调用 `PackCatalog.validate`。这个事件是在登记重载监听器时触发的，实现时要确认此时 `RegistryAccess` 里已经有本世界的数据包。若此时注册表还是空的或是上一份，把校验挪到监听器的 `apply`，或 NeoForge 上等价于「数据包重载完成」的事件。Fabric 那两处已经是对的，不要改坏。

`PackCatalog.validate` 会把不在 `CardDataDb.contains` 里的卡密从**日志用的池**里剔除并记警告。真正开包的 `PackOpening.openPack` 用的是 `def.set().value().toModel()`，**没有**这层剔除。同步之后卡包 JSON 可能比 `cards.bin` 新，未知卡密仍会被抽进物品。P2 必须在开包路径上用同一套过滤，不能只打日志。

jar 里已有 `common/src/main/resources/data/ygomc/ygomc/` 的 `demo` 系列、卡包、盒装。没有 YGOJSON 数据包时，`/ygomc pack give demo` 仍要能用。自动安装的包不要覆盖这个 id。

`tools/mksets.py` 的产物布局（`pack.mcmeta` 的 `pack_format` 是 48，对应 1.21.1）：

```
<输出>/
  pack.mcmeta
  data/ygomc/ygomc/card_set/<id>.json
  data/ygomc/ygomc/booster/<id>.json
  data/ygomc/ygomc/booster_box/<id>.json
```

今天要玩家把 `local-data/sets` 整份拷进 `<存档>/datapacks/ygomc-sets/` 再 `/reload`。同步不应要求每个存档拷一次。生成目录放在 `<游戏目录>/ygomc/sets/`，由平台的数据包查找器挂进每一个世界。已经放在存档 `datapacks/` 里的旧包继续有效，查找器那份与存档那份 id 冲突时，以存档里玩家手改的那份为准（后加载覆盖，或在查找器包的描述里写明优先级；实现时用一次双端启动确认谁覆盖谁，写进代码注释）。

`mksets.py` 现在的下载地址是浮动标签：

```
https://github.com/iconmaster5326/YGOJSON/releases/download/v1/individual.zip
```

缓存落在仓库的 `local-data/ygojson/individual.zip`（已被 `.gitignore`）。游戏内不要再写仓库目录。

抽法规则以 `tools/sets.md` 和 `mksets.py` 为准，移植时逐条对齐，不要重写设计：

- 只收 `locales.jp`。
- 文件名来自日文卡号前缀，slug 后最长 48，重名加 `_2`。
- 罕贵先映射进五档：`common` / `rare` / `super` / `ultra` / `secret`。映射表是 `mksets.py` 的 `RARITY`。表外记警告并当成 `common`。
- `chance` 是「1 比 X」，权重取倒数再通分。
- 名叫 “TCG …” 的九张表：留下罕贵比例，张数收成五张。本来就是五张或一张的不动。
- `preconstructed`：每个稀有度有几张就抽几张，同包不重复。
- 没有抽法：默认五张，按该稀有度的印刷张数加权，不把仅剩的最高罕贵写成必出。系列不足五张则整套给出。
- 没有卡密的印刷丢弃并计数。
- 盒装只收日文、盒内只有一种包、且该包已写成 booster。包数用日文的 `qty`。`mode` 为 `independent`。
- `prints` 只在 `qty != 1` 时写入，权重是张数。
- 显示色今天写死 `#3C5AA0`。见第 7 节。

Codec 现状（改 schema 时必须保持旧 JSON 仍能读）：

- `CardSetDef`：`name`、`code`、可选 `release`、可选 `cards`、可选 `prints`。
- `BoosterDef`：`set`、`display`、`pulls`、可选 `duplicates`（默认 `avoid_in_pack`）、必填 `fallback`。
- `BoosterBoxDef`：`booster`、`packs`（1..1000）、可选 `mode`、可选 `collation`、可选 `extras`、`display`。
- `PackDisplay`：`name`（文本组件）、可选 `color`、可选物品 `rarity`。

### 2.4 启动顺序（今天）

`Ygomc.init()`：组件、方块、物品、创造栏、命令、网络、`DuelConfig`、`DuelRoom`、`DataPacks.init`、`Natives.init`、`OcgEngine.prepare`。

客户端另外：

- Fabric `YgomcFabricClient.onInitializeClient`：`DuelClient`、`CollectionClient`、`PackClient`。
- NeoForge `YgomcNeoForgeClientSetup.onClientSetup`：同上。专用服不会加载这些类。

没有任何 `HttpClient`、`URLConnection` 或启动期下载。

### 2.5 离线工具与仓库边界

`tools/README.md`：产物进 `local-data/` 或游戏目录，不进 git。`.gitignore` 已忽略 `local-data/` 和 `*.cdb`。`ygomc/` 在游戏目录，不在仓库里。实现时不要把缓存写到仓库根。

`mkdatapack.py` 读 SQLite 的语句必须原样移植：

```
select id,ot,alias,setcode,type,atk,def,level,race,attribute,category from datas order by id
select id,name,desc,str1..str16 from texts order by id
```

`datas` 与 `texts` 的 id 集合不一致就拒绝写出。`strings.conf` 的解析规则在 `parse_strings_conf`：只认 `!` 开头；`!system` 的 id 是十进制且重复时保留第一个；`!counter` / `!victory` / `!setname` 是十六进制且后者覆盖；`!setname` 在 tab 处截断。`!system` 必须覆盖属性 1010..1016、种族 1020..1045、类型 1050..1075，缺一条就不要替换现有的 `strings.bin`。

Java 侧刻意不在运行时查询 SQLite（`mkdatapack.py` 开头说明了原因：原生 SQLite 体积和打包）。同步可以在工作线程里打开一次 cdb、写成三个 bin、然后关掉。对局路径仍然只读 bin。

## 3. 数据源

草案里「卡数据以 YGOPRODeck 为主、脚本以 ProjectIgnis 为主」不采用。引擎要的是 ygopro 的 `datas` / `texts` 行和 Fluorohydride 系脚本，不是百科 JSON。

### 3.1 卡库与系统文本（主源）

主源：MyCard `ygopro-database` 的简体中文区。

- 仓库：<https://github.com/mycard/ygopro-database>
- 更常更新的 GitLab：<https://code.moenext.com/mycard/ygopro-database> （2026-05 仍有 `locales/zh-CN` 提交；GitHub 镜像可能落后数周）
- 文件（钉死 40 位 commit，不要钉 `master`）：
  - `locales/zh-CN/cards.cdb`
  - `locales/zh-CN/strings.conf`（与 cdb 同一 commit）

URL 模板：

```
https://raw.githubusercontent.com/mycard/ygopro-database/<sha>/locales/zh-CN/cards.cdb
https://raw.githubusercontent.com/mycard/ygopro-database/<sha>/locales/zh-CN/strings.conf
```

GitHub raw 失败时换 GitLab：

```
https://code.moenext.com/mycard/ygopro-database/-/raw/<sha>/locales/zh-CN/cards.cdb
https://code.moenext.com/mycard/ygopro-database/-/raw/<sha>/locales/zh-CN/strings.conf
```

只下这两个文件。不要下整个仓库 zip：里面是十几种语言的 cdb，体积大，而且和本模组的中文界面无关。

选用理由：`cards.cdb` 的列和 `mkdatapack.py` 的 SELECT 一致；卡文是中文；`strings.conf` 的 `!system` 编号就是 `DataPacks.desc` 在 `<= 2047` 时要查的那张表，脚本里的 `SetDescription` / `aux.Stringid` 依赖它。`.agent/PLAN.md` 已经把卡库指向 mycard/ygopro-database。

钉版本：`pins.json` 里写死 sha。更新卡库是改 pin 再发一版模组，不是每次启动跟 `master`。首次实现时由实现者选定当时 `zh-CN` 下能通过第 9 节冒烟的那一笔 commit，把 sha 写进 pin，不要写进本文（本文不假装已经验证过某一个 sha 能下到）。

稳定性：社区库，没有 SLA。两个主机都失败就用本地缓存。不要做第三个匿名网盘。

许可：卡文是 KONAMI 的文本，仓库本身是社区整理的数据库。只缓存到游戏目录。

### 3.2 为什么 YGOPRODeck 不能当卡库

接口指南：<https://ygoprodeck.com/api-guide/>

- 全库：`GET https://db.ygoprodeck.com/api/v7/cardinfo.php`（零参数才返回全部；这是一次大 JSON，要落盘缓存）。
- 按卡密：`?id=46986414`，多个 id 用逗号。
- 限速：20 次/秒，超出后封 IP 一小时。指南要求把响应当地缓存，禁止按帧或按卡反复请求。
- 语言参数只有 `fr`、`de`、`it`、`pt`。没有中文。中文卡名卡文必须来自 MyCard 的 `texts`。
- JSON 里没有内核要的位掩码布局：64 位 `setcode` 拆进 `u16[16]`、`level` 里打包的灵摆刻度、连接怪兽 `defense` 转 `link_marker`、`category`、`ot`。这些转换在 `mkdatapack.py` 的 `CardData` 里，输入必须是 cdb 行。
- `card_sets` 可以当卡包卡密的旁路核对，但不能代替 YGOJSON 的抽法表。YGOPRODeck 的稀有度是 TCG 印刷名，没有 OCG 五张包的槽位。

YGOPRODeck 在本方案里只做两件事：卡图 URL，以及可选的「这个卡密在百科里是否存在」的诊断。不要为了诊断去分页扫全库；若要做，整份 JSON 下一次、按 `id` 建本地集合，并写 ETag。

### 3.3 脚本（主源）

主源：<https://github.com/Fluorohydride/ygopro-scripts> ，GPL-2.0。与本仓库 `natives/vendor/ocgcore`（Fluorohydride，MIT，见 `natives/vendor/VENDOR.md`）是同一套脚本约定。

不要用 ProjectIgnis/CardScripts 或 Fluorohydride 的后继 EDOPro 脚本当主源。那些脚本面向 ProjectIgnis 的 ocgcore，多了本仓库这份内核没有的 API。换脚本等于换内核，超出本计划。

钉死 commit 的 zip（不要用 `archive/refs/heads/master.zip`）：

```
https://github.com/Fluorohydride/ygopro-scripts/archive/<sha>.zip
```

解压后仓库**根目录就是脚本根**（`constant.lua`、`utility.lua`、`c<卡密>.lua`）。拷进 `ygomc/script/` 时去掉 zip 的顶层目录，不要多套一层。拷贝结束后用 `OcgEngine.firstRoot` 的规则自检：`constant.lua` 必须是普通文件。用临时目录写完再原子替换，避免玩家看到半份脚本。

`strings.conf` 不在这个仓库里。它和 cdb 一起从 MyCard 的 `locales/zh-CN/strings.conf` 来。不要从旧的 `Fluorohydride/ygopro` 单文件地址取，那份会和中文 cdb 脱节。

许可：GPL-2.0 与本模组 GPL-3.0 不兼容，所以脚本不能进 git、不能进 jar、不能进发布附件。下载器只在玩家机器上写游戏目录。日志里打印 commit 短码和「脚本为 GPL-2.0，不随模组分发」。

备用：同一 sha 的 `https://codeload.github.com/Fluorohydride/ygopro-scripts/zip/<sha>`。没有第二上游。ProjectIgnis 只在将来单独升级 ocgcore 时再评估，本计划不实现。

### 3.4 卡图

主源，按卡密，下过一次就不再请求：

```
https://images.ygoprodeck.com/images/cards/<卡密>.jpg
```

指南中的另外两档只作尺寸参考，默认同一次请求不要打两遍：

```
https://images.ygoprodeck.com/images/cards_small/<卡密>.jpg
https://images.ygoprodeck.com/images/cards_cropped/<卡密>.jpg
```

裁切图对不上本模组的整卡框，不用。小图可以将来单独当 ICON，但第一版用一张大图在本地缩成 FULL 和 ICON，少一次 HTTP。

YGOPRODeck 的大图是网页尺寸，不一定是 400×580。缩放规则沿用 `mkpics.py`：按 580/400 的比例，LANCZOS，FULL 200×290 q85，ICON 64×93 q78。默认不生成 HOVER。HOVER 会把全库从约 300 MB 抬到约 1.4 GB，而按需缓存本来就只保留玩家看过的卡。

备用（仅主源 404 或连接失败时，同一卡密再试一次）：

```
https://cdn.233.momobako.com/ygopro/pics/<卡密>.jpg
```

这是 `.agent/PLAN.md` 里写过的地址。它没有公开的速率和保留承诺，只能当备用，不能当唯一来源。两个都失败：该卡密记入负缓存（24 小时内不要重试），渲染继续走 `back.jpg`。

速率：卡图接口和 cardinfo 的 20 次/秒不是同一条，但指南写明拉图过快会封 IP。实现限额：同时最多 2 个连接，启动后的空闲速度不超过每秒 1 张，并且只拉「缓存里没有的卡密」。触发范围按优先级：

1. 玩家正在看的那张（卡册、物品栏、对局悬停）。
2. 当前界面上可见的卡，最多一小批。
3. 不在启动时遍历 `cards.bin` 全表。

全库预取做成显式命令，默认关闭，每秒 1 张、可断点续传，并在命令回显里写明可能被图床封 IP。不要绑到世界加载。

卡背：不从网上下 KONAMI 卡背。继续用模组已有的占位，或沿用玩家自己放的 `back.jpg`。`mkpics.py` 的 `cover.jpg` 路径只留给离线工具。

### 3.5 卡包

主源仍是 YGOJSON，与 `mksets.py` 相同。浮动标签 `v1` 不能当 pin。

```
https://github.com/iconmaster5326/YGOJSON/releases/download/v1/individual.zip
```

`pins.json` 记录期望的 sha256。下载后先校验，不符则删除临时文件、保留上一份 zip。更新卡包是改 pin 里的哈希（以及若上游改了标签，则改 URL 或改成某次 release 的不可变附件）。第一次实现时，用当时这份 zip 的哈希填 pin，并在日志里打印。

仓库说明以 YGOJSON 自己的 LICENSE 为准；生成出的 JSON 含卡号清单，`tools/sets.md` 已规定不入库。游戏目录里的 `ygomc/sets/` 同样不拷回仓库。

没有第二套抽法源。YGOPRODeck 的 `card_sets` 只适合做「YGOJSON 里有卡密但 cdb 没有」的诊断计数，不生成第二份数据包。

## 4. 清单与磁盘布局

### 4.1 模组内的 pin

路径：`common/src/main/resources/assets/ygomc/sync/pins.json`。这是唯一允许进 git 的同步数据，里面只有 URL、commit、哈希、字节上限，没有卡图和卡文。

```json
{
  "schema": 1,
  "database": {
    "sha": "<40 hex>",
    "files": [
      {
        "id": "cards.cdb",
        "urls": [
          "https://raw.githubusercontent.com/mycard/ygopro-database/<sha>/locales/zh-CN/cards.cdb",
          "https://code.moenext.com/mycard/ygopro-database/-/raw/<sha>/locales/zh-CN/cards.cdb"
        ],
        "sha256": "<可空，知道再填>"
      },
      {
        "id": "strings.conf",
        "urls": [
          "https://raw.githubusercontent.com/mycard/ygopro-database/<sha>/locales/zh-CN/strings.conf",
          "https://code.moenext.com/mycard/ygopro-database/-/raw/<sha>/locales/zh-CN/strings.conf"
        ]
      }
    ]
  },
  "scripts": {
    "sha": "<40 hex>",
    "urls": [
      "https://github.com/Fluorohydride/ygopro-scripts/archive/<sha>.zip"
    ]
  },
  "sets": {
    "urls": [
      "https://github.com/iconmaster5326/YGOJSON/releases/download/v1/individual.zip"
    ],
    "sha256": "<64 hex>"
  },
  "pics": {
    "urls": [
      "https://images.ygoprodeck.com/images/cards/{code}.jpg",
      "https://cdn.233.momobako.com/ygopro/pics/{code}.jpg"
    ]
  }
}
```

`{code}` 由代码替换，不要让配置文件做模板引擎。sha256 为空表示只认「这一次 HTTP 成功且文件能被解析」；cdb 若暂时没有官方哈希，用「SQLite 能打开且 datas/texts id 对齐」当校验。脚本 zip 用「解开后存在 `constant.lua`」当校验。卡包 zip 必须有 sha256，因为 `v1` 会动。

### 4.2 游戏目录

全部在 `<游戏目录>/ygomc/` 下。与今天玩家手动摆放的路径兼容。

```
ygomc/
  datapack/
    cards.bin
    texts.bin
    strings.bin
    pics.bin          可没有。离线工具仍会生成它
    back.jpg          可没有
  script/
    constant.lua
    utility.lua
    c*.lua
  sets/               同步生成的数据包根，直接能被查找器挂上
    pack.mcmeta
    data/ygomc/ygomc/...
  cache/
    manifest.json     上次成功应用的版本，见下
    database/
      <sha>/cards.cdb
      <sha>/strings.conf
    scripts/
      <sha>.zip
      <sha>/          解压暂存，替换成功后可删
    sets/
      <sha256前16位>.zip
    pics/
      <卡密>.jpg      原图
      <卡密>.full.jpg 可选，或直接写进稀疏索引
      <卡密>.icon.jpg
      misses.txt      负缓存：卡密、时间、两个 URL 的状态码
    staging/          未完成的下载，启动时清掉超过一天的临时文件
    locks/            防止整合服两个线程同时写 bin
```

`manifest.json`（写在缓存里，不进 git）：

```json
{
  "schema": 1,
  "appliedAt": "2026-10-23T06:00:00Z",
  "pinsSchema": 1,
  "databaseSha": "<sha>",
  "databaseSha256": { "cards.cdb": "<64>", "strings.conf": "<64>" },
  "scriptsSha": "<sha>",
  "setsSha256": "<64>",
  "bins": {
    "cards.bin": "<sha256>",
    "texts.bin": "<sha256>",
    "strings.bin": "<sha256>"
  },
  "cardCount": 0,
  "scriptFiles": 0,
  "sets": { "cardSets": 0, "boosters": 0, "boxes": 0 },
  "degraded": []
}
```

启动时比较 jar 里的 pin 和这份 manifest。sha 和哈希都一致，并且 `datapack` 三个 bin 的现哈希与 manifest 一致，则跳过下载。玩家用离线工具覆盖了 bin 时哈希对不上：若 pin 没变，不要用网络文件盖掉玩家的文件，记一条「本地数据包与清单不一致，保留本地」并跳过数据库步骤。只有 pin 变了，或本地文件缺失、魔数错误，才允许替换。替换写到 `datapack/*.bin.partial`，校验魔数和 id 升序之后再改名。改名失败则留着旧文件。

ETag：对 pin 已固定的 URL，ETag 只是少下字节的优化。pin 未变时可以带 `If-None-Match`。pin 变了必须当新文件下，不能沿用旧 ETag 的正文。响应头存 `ygomc/cache/etag.json`，键是完整 URL。

并发：一个游戏目录一把锁文件。专用服和客户端不会写同一目录。单人档的客户端与整合服是同一个进程，只跑一个 `AssetSync`。

### 4.3 配置

不要塞进 `DuelConfig`。那个类的注释写明它只放规则（超时、效果询问），文件是 `config/ygomc.json`。另开 Cloth Config：`config/ygomc-sync.json`。

| 字段 | 默认 | 含义 |
|---|---|---|
| `enabled` | true | 总开关。false 时行为与今天完全一致 |
| `database` | true | 拉 cdb 并生成三个 bin |
| `scripts` | true | 拉脚本 |
| `sets` | true | 拉 YGOJSON 并生成数据包 |
| `picsOnDemand` | true | 缺图时按卡密拉。专用服忽略 |
| `picsPrefetchAll` | false | 全库预取，只能由命令临时打开 |
| `timeoutSeconds` | 30 | 单次连接 |
| `maxPicBytes` | 2000000 | 单张图上限，防下到网页 |
| `allowReplaceNewerLocal` | false | 见上，默认不盖玩家手摆的较新文件 |

旧配置缺字段时用默认值，和 `DuelConfig` 一样。

## 5. 客户端与服务端的分工

| 步骤 | 专用服 | 整合服（单人） | 连到远程服的客户端 |
|---|---|---|---|
| cdb → 三个 bin | 必须 | 必须 | 必须（本地显示卡名卡文；网上不传全文） |
| 脚本 | 必须，否则开不了局 | 必须 | 可缺。`OcgEngine.prepare` 失败只打日志 |
| 卡包数据包 | 必须，否则只有 jar 内 `demo` | 必须 | 不生成、不安装。包定义由服务器数据包同步 |
| 卡图 | 跳过 | 按需 | 按需 |
| 进度界面 | 没有。日志 + `/ygomc sync status` | 有 | 有，只反映本机的库和卡图 |

专用服不要链接客户端类。进度条放在 `common` 的客户端包 `cn.xm1221.ygomc.common.client`，由现有的 `YgomcFabricClient` / `YgomcNeoForgeClientSetup` 注册。服务端代码只依赖 `AssetSync` 的状态查询。

远程客户端的卡库 pin 与服务器可能不是同一模组版本。卡密对不上时的表现维持现状：界面「说明 N」或卡背。不要在登录时向服务器要整份 cdb（体积和版权都不合适）。服务器已通过动态注册表把卡包定义同步给客户端，这是现成的，不要再同步一份 sets JSON。

## 6. 接到现有启动流程的位置

原则：`Ygomc.init()` 仍然不碰网络。模组构造和注册表登记保持今天的耗时。下载在守护线程。

建议顺序：

1. `Ygomc.init()` 末尾，在现有的 `DataPacks.init()` 和 `OcgEngine.prepare()` **之后**，调用 `AssetSync.schedule()`。此时先用磁盘上已有的文件把日志打出来，和今天一样。线程立刻返回。
2. 工作线程读 pin 和 manifest。需要下载时按「元数据（pin 与 manifest）→ cdb 与 strings.conf → 三个 bin → 脚本 → 卡包 zip → 生成 sets」进行。卡图不在这条流水线里。
3. 数据库或脚本确实换了新文件之后：
   - 若 `DuelRoom` 里没有进行中的对局：`DataPacks.reload()`，再 `OcgEngine.reprepare()`。
   - 若有对局：把新文件留在缓存，manifest 记 `pendingReload: true`，日志说明下一局开始前或 `/ygomc sync apply` 才会换上。不要在对局中途 `Ocg.init`。
4. sets 生成好之后，若服务器已起来，触发一次数据包重载（`MinecraftServer.reloadResources`），让 `PackCatalog.validate` 再跑。服务器还没起来则什么都不用做，查找器会在第一次建世界时看见目录。
5. 卡图请求从 `CardTextures` 的未命中发出，不从 `Ygomc.init` 发出。解码上传仍只在渲染线程。下载在工作线程，写成缓存文件后让渲染线程再读。

`OcgEngine` 要补的能力（实现时改这个类，不要另写一个引擎入口）：

- `reprepare()`：仅当没有进行中的对局。清 `attempted`，关旧的脚本根状态，再走 `prepare()`。失败时保留上一份成功状态（今天失败会把 `ready` 留在 false；重试失败不能把已经能打的引擎打坏）。实现时把「成功结果」和「最近一次尝试」拆开。
- `prepare()` 的一次性语义对「启动时文件还没有」是错的，但不要改成无限重试。只允许同步线程和命令再试一次。

`CardTextures`：加 `invalidate()`，在 `DataPacks.reload()` 之后由客户端调用，清掉 LRU 和待释放的 DynamicTexture。下载完成的那一张只让对应 `Key(code, tier)` 失效。

进度：

- 状态机：`IDLE`、`CHECKING`、`DOWNLOADING`、`CONVERTING`、`APPLYING`、`READY`、`DEGRADED`、`FAILED`。
- 字段：当前步骤名、已完成字节、总字节（未知则为 -1）、最近一条人话、是否在用离线缓存。
- 客户端：标题画面或加入世界后的一条可关提示，不要阻塞「单人游戏」按钮。已有完整本地文件时不要弹。
- 文案进 `zh_cn.json` / `en_us.json`，不要在逻辑里拼中文句子给玩家。日志可以是中文，与现有 `ygomc/datapack` 日志一致。
- 命令挂在现有 `YgomcCommand`：
  - `/ygomc sync status`：谁都能看。
  - `/ygomc sync run`：权限 2，再跑一遍（仍尊重「不盖手改文件」，除非配置允许）。
  - `/ygomc sync apply`：权限 2，把 pending 的 bin/脚本在无对局时换上。

平台数据包查找器（P2）：

- NeoForge：模组总线 `AddPackFindersEvent`，`PackType.SERVER_DATA`，目录 `<game>/ygomc/sets`，包 id 建议 `ygomc_sets`，默认识别为已启用。
- Fabric：用 Fabric API 里登记服务端数据包的公开入口（1.21.1 的 resource loader）。不要复制 NeoForge 的事件类名到 Fabric。查找的目录和包 id 与 NeoForge 相同。
- 两种平台都不要在模组构造器里做磁盘 IO 以外的网络。

`PackOpening`：抽卡前用 `CardDataDb.contains` 滤掉未知卡密。滤完某稀有度为空时走 `Booster` 已有的 fallback，不要把 0 号卡发出去。`PackCatalog.validate` 继续打警告，便于对照日志。

## 7. 卡包配置要改什么

生成器从 `mksets.py` 移植到 Java（`SetsGenerator`），专用服不依赖本机 Python。Python 脚本保留，两边对同一份小夹具必须得到同一组 JSON（键顺序可以不同，语义相同）。

### 7.1 可选字段 `odds_origin`

加在 `BoosterDef` 上，`optionalFieldOf`，缺省当作 `unspecified`。旧的 `demo.json` 和玩家手写包不用改。

| 值 | 含义 |
|---|---|
| `sheet` | 来自 YGOJSON distributions，且张数没被改 |
| `sheet_adapted` | 来自抽法表，但是 TCG 九张收成了五张 |
| `preconstructed` | 预组，开出该稀有度全部印刷 |
| `print_weight` | 没有抽法，五张按印刷张数 |
| `unspecified` | 旧文件 |

`/ygomc pack odds` 在 `print_weight` 和 `sheet_adapted` 时多一句语言文件里的说明：这不是实卡封入率。不要假装精确。

### 7.2 显示名

继续用 `PackDisplay.name` 的文本组件。生成器写 `{"text":"<日文名>"}`，与现在 `mksets.py` 一致。另加可选字段 `CardSetDef` 已有的 `code`（卡号前缀）即可，不必再加英文名列。颜色：按卡号前缀哈希进一组固定的深色（写死在生成器里的十几色），保证同一 id 永远同一色，且不要用白色当默认（染色层会看不出来）。`demo` 仍是 `#3C5AA0`。

### 7.3 校验与热重载

- 生成时丢弃没有卡密的印刷（与脚本一致）。
- 安装后 `PackCatalog.validate` 再丢弃不在 `cards.bin` 里的卡密。两边的计数都写进 manifest 的 `degraded` 或日志：`noPassword`、`notInCdb`。
- 某包滤完为空：不要写这个 booster 文件，避免 Codec 因「系列没有任何卡」直接让整份数据包加载失败（`CardSetDef` 构造函数会抛）。
- 热重载：sets 走 `/reload` 或同步结束时的 `reloadResources`。bin 和脚本走第 6 节的 `reload` + `reprepare`，不走 Minecraft 的 `/reload`（那不会重新打开 `FileChannel`）。
- `fallback` 维持必填。生成器永远写 `["secret","ultra","super","rare","common"]`。不要把 Codec 改成可空，否则手写坏包会拖到开包才炸。`BoosterDef` 的紧凑构造函数已经在加载期检查抽法。

### 7.4 不改的抽法语义

`print_weight` 不是实卡概率，这一点保持 `.agent/PLAN.md` 的结论。本计划只让它被看见（`odds_origin`），不引入新的猜测封入率。

## 8. 从离线工具迁移

| 今天 | 同步之后 |
|---|---|
| `python tools/mkdatapack.py [cdb] [out] [strings.conf]` | 仍可用。产物放到 `ygomc/datapack/`。pin 未变时同步不覆盖 |
| `python tools/mkpics.py` | 仍可用。`pics.bin` 仍被 `CardImageDb` 首先读取。按需 JPEG 只填补 bin 里没有的卡密 |
| 手拷 `script/` | 仍可用。目录里已有 `constant.lua` 且 pin 的脚本 sha 与 manifest 一致则不替换。没有 manifest 但哨兵文件在：视为玩家自备，不下载覆盖 |
| `python tools/mksets.py --from ygojson` 再拷进存档 | 仍可用。存档 `datapacks/ygomc-sets` 优先级高于自动挂载的 `ygomc/sets` |
| 什么都没有 | 后台下载。完成前对局和开包保持今天的失败方式（日志 + 缺数据提示），不卡住标题画面 |

`pics.bin` 与散图的查找顺序：`CardImageDb` 命中且该档 `length != 0` 用 bin；否则读 `ygomc/cache/pics/<code>.icon.jpg` 或 `.full.jpg`；都没有则排队下载并暂用卡背。不要为了第一张散图就重写几百 MB 的 `pics.bin`。全库打包仍是离线工具的职责。

Java 转换器与 Python 脚本分叉的防护：在 `tools/` 或测试里留一份极小的 cdb、strings.conf、YGOJSON 夹具（卡文用虚构句子，不要用真实卡文）。Java 测试和一条 Python 调用对比 bin 的魔数、记录数和 JSON 的 `odds_origin`、槽位张数。夹具进 git。真实 cdb 不进 git。

## 9. 分阶段

每阶段都可以单独合进 `main` 并玩。后一阶段不要重做前一阶段的文件布局。

### P0 清单、下载器、钩子

- `pins.json`、`config/ygomc-sync.json`、`AssetSync.schedule()`、状态查询、`/ygomc sync status`。
- HTTP：超时、User-Agent `ygomc`、最多跟随 2 次跳转、只允许 https、原子写入、失败删临时文件、ETag 缓存。
- 不改 bin、不改脚本、不改卡包。`enabled=false` 时连线程都不起。
- 成功标准：无网络时游戏与今天相同；有网络时日志出现「清单一致，跳过」或「将在 P1 下载」这种状态，且启动不被阻塞。单测覆盖哈希不符、半截文件、404。

### P1 卡库与脚本

- 下载 cdb 与 strings.conf，移植 `mkdatapack.py` 写出三个 bin。
- 下载脚本 zip，落到 `ygomc/script`。
- `DataPacks.reload` + `OcgEngine.reprepare`，尊重对局中的延迟。
- 专用服不拉图。
- 成功标准：清空 `ygomc/datapack` 和 `ygomc/script` 后，启动专用服，日志最终出现「引擎就绪：N 张卡」且 N 与 cdb 行数一致。断网再启动，用缓存仍能就绪。把 cdb 换成截断文件时保留上一份 bin。`/ygomc selftest` 能打完一局（用现有内置卡组；卡组若引用了不在库里的卡密，先换一个夹具卡组，不要改规则）。

### P2 按需卡图与卡包自动安装

- `CardTextures` 未命中时拉图、缩放、写缓存、只失效那一张。
- 移植 `mksets.py`，写出 `ygomc/sets`，双端查找器挂上，重载后 `PackCatalog` 日志出现非 demo 的系列数。
- `PackOpening` 滤未知卡密。
- 成功标准：新存档不手拷 datapacks，`/ygomc pack give <某个日文系列 id>` 能开出卡，卡密都在 `cards.bin` 里。卡册里一张 bin 没有的卡先显示卡背，随后变成卡面，且第二次不再打图床（看日志或本地文件时间戳）。专用服进程不出现 `images.ygoprodeck.com` 的连接。

### P3 卡包可读性、失败转移、增量

- `odds_origin`、稳定的包颜色、`pack odds` 的说明。
- URL 按 pin 的顺序失败转移（GitHub raw → GitLab，图床主 → 233）。
- ETag / 304。负缓存。`/ygomc sync run`。
- 可选的慢速全库预取命令，默认关。
- 成功标准：主 URL 用测试夹具返回 500 时走到备用；`print_weight` 的包在 `odds` 里能看出不是实卡概率；重复启动在 pin 不变且文件都在时零大文件下载（允许一次 HEAD 或 304）。

## 10. 测试

不在 CI 里访问 YGOPRODeck、GitHub 或 233。CI 只跑本地夹具和假 HTTP。

- 假 `HttpServer` 或接口注入：200、304、404、截断正文、sha256 不符、超大 `Content-Length`。
- `CdbToBins` 对夹具 cdb 的字节与 `mkdatapack.py` 一致（至少魔数、版本、张数、第一张与最后一张卡密）。
- `strings.conf` 夹具覆盖：十进制 system、十六进制 counter、setname 的 tab、重复 system id 保留第一个。
- `SetsGenerator` 夹具覆盖：无抽法、TCG 九张、预组、无卡密印刷、盒装多包种被丢弃。
- `OcgEngine.reprepare` 用现有的离线测试方式（不要在单测里调 `Platform.getGameFolder()`，这个调用离开 Minecraft 会断言失败，`DataPacks` 的注释写过）。
- 双端手工：Fabric 与 NeoForge 各开一次专用服和一次单人。确认查找器包出现在 `/datapack list`，`demo` 仍在。
- 卡图手工：飞行模式创造里打开卡册，翻到未缓存卡密，确认不掉到每秒数张以上的请求。

真实网络的冒烟由实现者在本机做一次，把 pin 的 sha、zip 的 sha256、卡张数写进提交说明。不要把冒烟下载的 cdb、脚本、zip、jpg 留在工作区。

## 11. 风险

| 风险 | 处理 |
|---|---|
| KONAMI 卡图与卡文版权 | 不进 git、不进 jar、不进 CI 缓存上传。`.gitignore` 已挡 `*.cdb` 和 `local-data/`。再挡仓库内意外出现的 `ygomc/` 没有必要（那不是仓库路径），但代码审查时拒绝任何 `src/main/resources` 下的 jpg/cdb/lua 卡脚本 |
| 脚本 GPL-2.0 与模组 GPL-3.0 | 只在运行时下载到游戏目录。发布说明沿用 README 的「不随模组分发」 |
| YGOPRODeck 封 IP | 不在启动时全库拉图；cardinfo 即使要用也是一份缓存；图床并发 2、按需、负缓存 |
| `v1` 标签被上游替换 | 卡包 zip 以 sha256 为准，不符就用旧 zip |
| GitHub 镜像落后于 MyCard GitLab | pin 同时写两个 URL，同一 sha |
| cdb 与脚本不是同一天的数据 | 新卡会没有脚本或没有行。允许。`Ocg` 对缺脚本的卡维持内核现状。日志打两个 sha，方便对照。不要为了对齐去跟 `master` |
| sqlite-jdbc 体积 | 只在转换时加载 `org.xerial:sqlite-jdbc`。不要在对局代码里引用它。若 jar 因此涨到不可接受，转换器留在 P1 的独立 source set，仍打进同一个模组，但确认原生库只解压当前平台。不要退回到要求玩家安装 Python |
| 半截文件被当成卡库 | 临时名 + 魔数/id 集合检查 + 原子改名。启动时删过期 staging |
| 对局中途换脚本 | `reprepare` 看到有对局就推迟 |
| NeoForge 校验时机偏早 | P2 手工看「套包：系列 N」不是 0。不对就挪事件 |
| 数据包 id 与 `demo` 冲突 | 生成器禁止 id `demo` |
| 磁盘 | 三个 bin 大约数 MB 到十余 MB；脚本 zip 数十 MB；卡包 zip 数十到上百 MB；按需图每张几十 KB。在 `degraded` 日志里打总字节。不要默认下 HOVER 全库 |
| 专用服被误拉图 | `picsOnDemand` 在 `Dist.DEDICATED_SERVER` / 无客户端环境为强制关 |

## 12. 建议改动的文件

实现时按这个清单加类，命名可以微调，职责不要并成一个上帝类。

公共（`common/src/main/java/cn/xm1221/ygomc/common/sync/`）：

- `AssetSync`：调度、状态机、与对局互斥。
- `SyncPins`：读 jar 里的 `pins.json`。
- `SyncManifest`：读写真 `ygomc/cache/manifest.json`。
- `SyncConfig`：Cloth，`ygomc-sync`。
- `HttpFetch`：ETag、sha256、原子写、URL 顺序失败转移。
- `CacheLayout`：上面的路径常量，全部从 `Platform.getGameFolder()` 拼接。单测里允许传入假的游戏目录，不要在这个类里调 Platform。
- `CdbToBins`：移植 `mkdatapack.py`。
- `ScriptInstaller`：解压 zip，核对 `constant.lua`，原子替换 `ygomc/script`。
- `SetsGenerator`：移植 `mksets.py`。
- `PicCache`：按卡密存 JPEG 与两档缩放。缩放可用 `ImageIO`，与 `CardTextures` 已有的 JPEG 路径一致。不要把缩放放进渲染线程。

要改的现有类：

- `Ygomc`：`init` 末尾 `AssetSync.schedule()`。
- `OcgEngine`：`reprepare`，失败不摧毁上一份成功状态。
- `DataPacks`：reload 之后给客户端一个钩子（或由 `AssetSync` 在客户端直接调 `CardTextures.invalidate`）。
- `CardTextures`：bin 未命中时问 `PicCache`，再排队下载。
- `CardImageDb`：可以不改。散图不要塞进它的随机访问格式里。
- `PackOpening`：未知卡密过滤。
- `BoosterDef`：可选 `odds_origin`。
- `YgomcCommand` / `PackCommands`：三条 sync 子命令；`odds` 多一句来源。
- `fabric/.../YgomcFabric.java`：数据包查找器。
- `fabric/.../client/YgomcFabricClient.java`：进度订阅。
- `neoforge/.../YgomcNeoForge.java`：`AddPackFindersEvent`。
- `neoforge/.../client/YgomcNeoForgeClientSetup.java`：进度订阅。
- 语言文件：`assets/ygomc/lang/zh_cn.json`、`en_us.json`。

保留不动的工具：`tools/mkdatapack.py`、`tools/mkpics.py`、`tools/mksets.py`、`tools/sets.md`。在 `tools/README.md` 加一小节指向本文，说明游戏会自己做同样的事，脚本仍是离线路径。这一小段说明可以和 P0 一起改，它不是功能代码。

测试：

- `common/src/test/java/.../sync/` 夹具与 `HttpFetch`、`CdbToBins`、`SetsGenerator`。
- 夹具目录 `common/src/test/resources/sync/`。虚构卡文。

依赖：`org.xerial:sqlite-jdbc`，标在 `common`。版本用实现时 Maven Central 上的当前稳定版，写进 `gradle/libs.versions.toml`（若仓库已有版本目录则跟着现有方式）。不要为 HTTP 再引 OkHttp；JDK `java.net.http.HttpClient` 够用。

## 13. 实现时的顺序建议

1. P0 的 pin、配置、状态、假 HTTP 测试。
2. 选定并写死 database sha、scripts sha、sets sha256，本机冒烟一次，再写 P1。
3. P1 用清空的游戏目录起专用服，看到引擎就绪。
4. P2 先做卡包查找器（不依赖图床），再做按需卡图。
5. P3 的失败转移用假 HTTP 测完再接到真 URL 顺序上。

每一阶段都保持：断网可玩（只要磁盘上曾经成功过，或玩家按今天的办法放了文件）、仓库里没有卡图和 cdb、`demo` 包还能开。
