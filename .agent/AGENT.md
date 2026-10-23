# 给改这个仓库的人

Minecraft 1.21.1，公共代码在 `common`，Fabric 和 NeoForge 只做各自的入口。规则在 ygopro-core 里，Java 不自己算连锁、伤害和时点。

## 边界

服务端决定对局。客户端只提交选择。超时判负，不替玩家应答。

界面上的句子走语言文件。网上传卡密和状态，不传整段卡文。

卡的身份是 `card_ref` 数据组件，不是一万多个物品，也不是随手写的 NBT。卡包物品上只放「这是哪种包」，不放抽出的结果。

卡图、卡文、脚本不放进 jar。游戏读版本文件夹的 `ygomc/datapack` 和 `ygomc/script`。脚本目录只认 `ygomc/script`，里面要有 `constant.lua`。

自定义的 Brigadier 参数类型必须先注册。没注册的话，指令树发不出去，玩家进世界会被踢，提示是「无效的玩家数据」。

## 目录

源码是 `common`、`fabric`、`neoforge`。`ocg` 是引擎和 JNI，`duel` 是房间和对局，`client` 是界面，`card`、`collection`、`deck`、`pack` 是物品和卡包。`tools` 生成本地数据包和贴图，说明在 `tools/README.md`。

运行时读 `cards.bin`，不读 `cards.cdb`。不另加一套规则引擎，也不用 GeckoLib。
