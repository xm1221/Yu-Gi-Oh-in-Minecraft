# ygomc 现状与计划

Minecraft 1.21.1，Architectury（Fabric 与 NeoForge 共用 `common`）。规则是 ygopro-core，新大师规则。许可 GPL-3.0-or-later。卡图、卡文、卡片脚本不放进 jar。

原生库有 Windows x64、Linux x64、macOS x64、macOS arm64。没有 Linux arm64。

## 现在能用

对局在服务端跑。`/ygomc duel` 对人机，`invite` / `accept` 开双人。超时默认 100 秒判负，不替玩家应答。决斗盘右键回到当前界面。界面自己画：操作挂在卡上，阶段条只是邀请，句子走语言文件。

全卡池一个 `card` 物品。`card_ref` 记卡密、罕贵（普通、稀有、超稀、极稀、秘稀）和异画序号。卡名、卡文、卡图读版本文件夹的 `ygomc/datapack`。脚本读 `ygomc/script`。

卡册、创造卡册、末影卡册、卡组盒、末影卡组盒都能用。组卡用点击。卡组在 `ygomc/decks`。

卡包右键开，潜行开一叠。结果在服务端算，不写在物品上。卡先进背包，再打开翻牌界面；关掉界面不收回。盒装卡包右键拆成包，潜行则直接开成卡。配好的盒用存档密钥决定每一包的抽法，同一包不能开第二次。命令是 `/ygomc pack give`、`box`、`odds`、`simulate`。不写命名空间时当作 `ygomc:`。

卡包是三张数据包注册表：`card_set`、`booster`、`booster_box`。`tools/mksets.py` 只收日文系列。有抽法表的用表；表是 TCG 九张的，收成五张。预组开出全套。没有表的，一包五张，按各罕贵的印刷张数抽。没有卡密的印刷不进池。

物品、决斗台、场地格子、组卡界面、开包界面和闪面层由 `tools/mkplaceholdertextures.py` 生成。手里的立体卡还没有闪面。

卡密、卡文、卡图、脚本怎么生成，见 `tools/README.md`。卡包见 `tools/sets.md`。

## 还没做

没有抽法表的包，概率只是印刷张数的比例，不是实卡封入率。

百鸽和 YGOPRODeck 都是一个卡密一张图，填不上异画序号。

决斗台能放，右键还不能开局。

卡包不会出现在箱子、村民交易或赢 NPC 的奖励里。

启动时不会自己下载脚本、卡库或卡图。

## 接下来

1. 脚本用 Fluorohydride/ygopro-scripts，卡密和卡文用 mycard/ygopro-database。缺了或校验变了，再下到 `ygomc/script` 和 `ygomc/datapack`。失败就用已经有的。卡图不全量下。缺一张时，按卡密向 `https://cdn.233.momobako.com/ygopro/pics/{卡密}.jpg` 补一张。
2. 战利品表和村民交易投放卡包。赢 NPC 的奖励等 NPC 做了再接。
3. 异画先不做。`variant` 留着。

## 不做

不把抽卡结果写进物品。开包界面不提供留下或丢弃。不做拆卡、合成和交易所。不用字段编号当收录关系。启动时不下载全部卡图。
