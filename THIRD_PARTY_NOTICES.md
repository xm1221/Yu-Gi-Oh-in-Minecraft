# 第三方组件与许可

本项目整体以 **GNU GPL v3** 发布，全文见 [`LICENSE`](LICENSE)。

下面逐项说明本仓库包含或依赖的第三方组件、各自的许可、以及为什么可以这样组合。
**这份文件是许可判断的依据，改动依赖时请同步更新。**

---

## 1. 随仓库分发的组件

### 1.1 ocgcore —— 游戏王规则引擎

| 项 | 内容 |
|---|---|
| 位置 | `natives/vendor/ocgcore/`（源码）、`natives/vendor/ocgcore/build/`（生成的工程） |
| 上游 | `ygopro-core`（ygocore 项目的内核部分） |
| 许可 | **MIT** |
| 版权 | Copyright (c) 2015 Fluorohydride |

MIT 与 GPLv3 兼容：MIT 允许再许可，把 MIT 代码并入 GPLv3 作品是明确可行的。
我们保留原始许可声明（见 `natives/vendor/ocgcore/LICENSE`）。

**本仓库对上游内核做过一处修改**（便于在无窗口环境下运行，不改变任何规则行为）：

- `duel.cpp`：`_set_error_mode(_OUT_TO_MSGBOX)` → `_set_error_mode(_OUT_TO_STDERR)`

### 1.2 Lua 5.4.8

| 项 | 内容 |
|---|---|
| 位置 | `natives/vendor/ocgcore/lua/`（与内核一起编译，静态链接） |
| 许可 | **MIT**（原文已补到 `natives/vendor/ocgcore/lua/LICENSE`） |
| 版权 | Copyright (C) 1994-2025 Lua.org, PUC-Rio |
| 版本 | 5.4.8 |

ocgcore 的卡牌脚本是 Lua 写的，所以 Lua 解释器是硬依赖。
以静态方式链进 `ocgcore.dll`。

### 1.3 ygomc 自己的原生桥

| 项 | 内容 |
|---|---|
| 位置 | `natives/jni/ygomc_ocg.cpp` |
| 许可 | **GPLv3**（本项目） |

只做 Java ↔ 内核的函数转发，**不含任何游戏规则逻辑**。
与内核分成两个 DLL 编译，许可边界清晰：
`ocgcore.dll`（MIT）与 `ygomc_ocg.dll`（GPLv3）。

### 1.4 YgoDuelingMod —— 设计参考

| 项 | 内容 |
|---|---|
| 上游 | YgoDuelingMod，作者 CAS_ual_TY |
| 许可 | **GPLv3** |
| 版权 | Copyright (C) CAS_ual_TY |

同为 GPLv3，因此**代码可以直接复用**，无需额外授权。
凡复用其代码之处，须在源码头保留其版权声明。

> 注意：YgoDuelingMod 是 Minecraft **1.16/1.18** 世代的模组，与本项目的 1.21.1
> 在 GUI / 网络 API 上不同代。可复用其**设计与数据模型**，
> 具体实现需按 1.21 的 `GuiGraphics` / `CustomPacketPayload` 重写。

---

## 2. 只在开发期读取、**不随仓库分发**的组件

以下内容存在于本机（`D:\MyCardLibrary\ygopro\`）供运行期读取，
**不进入本仓库，也不打进任何发布包**。

### 2.1 卡牌脚本 `script/*.lua`

| 项 | 内容 |
|---|---|
| 许可 | **GPLv2** |
| 来源 | ygopro 客户端的 `script/` 目录，13,576 个文件，约 32 MB |

**这一条对数据包形态有实际约束，务必注意：**

- GPLv2-**only** 与 GPLv3 对「组合成一个作品」是不兼容的。
  把脚本打包进本项目的 jar，等于把 GPLv2-only 代码并入 GPLv3 作品，**不可以**。
- 正确做法是**运行期从用户自己的 ygopro 安装目录读取**——两者只是各自独立的程序
  在运行期交换数据，属于聚合而非组合，没有许可问题。
- 若日后要做「一键数据包」，也必须让脚本**独立于模组 jar 分发**
  （用户自备、或单独下载），且不得改变其许可声明。

### 2.2 卡图 `pics/*.jpg`

| 项 | 内容 |
|---|---|
| 版权 | **KONAMI**，保留所有权利 |
| 规格 | 15,357 张，约 1.23 GB |

卡图的版权不属于任何开源许可。**只读取本地已有文件或由用户自行下载，
绝不随包分发。**

### 2.3 卡牌文本 `cards.cdb`

卡名、卡文等文本内容的版权属 **KONAMI**。
与卡图同理：只从本地读取，不随包分发。

### 2.4 `lflist.conf` / `strings.conf`

卡表（禁限卡表）与字符串常量配置，随 ygopro 客户端分发，同属上述数据。
本项目**只读取**。

### 2.5 WindBot

| 项 | 内容 |
|---|---|
| 位置 | `D:\MyCardLibrary\ygopro\WindBot\WindBot.exe`（2.49 MB） |
| 许可 | MIT（上游 IceYGO/WindBot） |

目前只作为「NPC 对手」的候选方案记录在案，**尚未使用**。
若日后通过子进程 + ygopro 协议对接，属于独立程序间的进程间通信，
不构成组合作品；但仍会在本节登记其许可与版权。

---

## 3. 版权归属小结

| 内容 | 版权 | 是否随仓库分发 |
|---|---|---|
| 本项目代码（Java + JNI 桥） | 本项目贡献者，GPLv3 | 是 |
| ocgcore 内核 | Fluorohydride 等，MIT | 是（`natives/vendor/`） |
| Lua 5.4.8 | Lua.org / PUC-Rio，MIT | 是（同上） |
| 卡牌脚本 | ygopro 项目，GPLv2 | **否**（运行期本地读取） |
| 卡图 | KONAMI | **否** |
| 卡名 / 卡文 | KONAMI | **否** |
| 禁限卡表、字符串常量 | 随 ygopro 客户端 | **否** |

---

## 4. 交付物形态与许可的对应关系

本项目交付两件东西，**两者的许可性质不同，不要混为一谈**：

1. **模组本体**（jar / 各平台产物）—— GPLv3。
   内含本项目代码 + MIT 的 ocgcore + MIT 的 Lua。

2. **数据包**（卡表、脚本、卡图）—— **不是本项目授权的对象**。
   卡图与卡文的版权属 KONAMI，脚本的许可属 ygopro 项目（GPLv2）。
   本项目只提供**读取能力**，不提供这些内容本身。

因此数据包必须与模组 jar 分开分发，且不宜由本项目代为打包。
