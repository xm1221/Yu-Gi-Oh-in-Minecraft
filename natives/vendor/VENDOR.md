# natives/vendor —— 第三方源码的来源与改动记录

本目录下的代码**不是本项目的代码**，是从上游 vendored 进来的第三方源码。
每个组件的许可原文都在它自己的目录里。**改动它们时请在这里登记**，
否则将来重新 vendor 时会丢掉我们的补丁。

---

## 目录结构

```
natives/vendor/ocgcore/          ocgcore 规则引擎（MIT）
    ├── *.cpp / *.h              内核源码
    ├── LICENSE                  MIT 原文
    ├── lua/                     Lua 5.4.8（MIT，静态链接进内核）
    │   └── LICENSE              Lua 的 MIT 原文（上游包里没有，本项目补的）
    └── build/                   premake5 生成的 VS 工程（可重新生成，不必手改）
natives/jni/                     本项目自己的 JNI 桥（GPLv3，不属于 vendor）
```

## ocgcore

| 项 | 内容 |
|---|---|
| 上游 | `ygopro-core`（ygocore 项目的内核） |
| 本机来源 | `E:\miemod\ygo\libs\ygopro-core\` |
| 许可 | MIT，Copyright (c) 2015 Fluorohydride |
| 规则版本 | 大师规则 2020（`CURRENT_RULE = 5`） |
| 文件数 | 35 个 `.cpp`/`.h`/`.lua` |

> 上游的具体提交号没有记录（当时是从上述目录整体复制的，该目录不是 git 检出）。
> 若要精确对齐上游版本，需要重新从 ygopro-core 取一份带版本信息的副本再逐文件比对。

### 本项目对内核的改动

一共**只有一处**，目的是让引擎能在没有窗口的环境（服务端 / 测试夹具）里运行。
**不涉及任何规则行为。**

| 文件 | 行 | 改动 |
|---|---|---|
| `duel.cpp` | 26 | `_set_error_mode(_OUT_TO_MSGBOX)` → `_set_error_mode(_OUT_TO_STDERR)` |

原因：Windows CRT 默认在断言失败时弹**模态消息框**，那会让一个没有桌面的进程
永久挂住。改成写 stderr，失败时至少能留下线索而不是静默卡死。

### 构建

工程文件由 premake5 生成，不必手改：

```powershell
# 工程文件不随仓库走（.gitignore 里挡掉了 build/），需要先用 premake5 生成
$pm = ".agent\toolchain\premake-5\premake5.exe"     # 注意：在 .agent 下，不在 natives 下
Push-Location natives\vendor\ocgcore
& "..\..\..\$pm" vs2022 --file=dll.lua
Pop-Location

E:\VS\MSBuild\Current\Bin\MSBuild.exe natives\vendor\ocgcore\build\ocgcoredll.sln `
    /m /nologo /v:minimal /t:Build `
    '/p:Configuration=Release;Platform=x64;PlatformToolset=v145'
```

**`PlatformToolset=v145` 必须显式给**：生成的工程写死了 `v143`，
而本机只装了 MSVC 14.50（对应 v145），不给就会报找不到工具集。

产物：`build/bin/x64/Release/ocgcore.dll`（约 1.61 MB），
只导入 `KERNEL32.dll`（静态 CRT + 静态 Lua），**不依赖 VC++ 运行库**。

## Lua

| 项 | 内容 |
|---|---|
| 版本 | **5.4.8**（`lua/src/lua.h` 里 `LUA_VERSION_*` 三段确认） |
| 许可 | MIT，Copyright (C) 1994-2025 Lua.org, PUC-Rio |
| 改动 | **无** |

上游发行包里只有 `lua/src/lua.h` 末尾的版权声明，没有独立的 LICENSE 文件。
MIT 要求「在副本中保留版权声明」——`lua.h` 里那一份是随源码走的，
但为了让许可信息在不读源码时也可见，本项目另外补了 `lua/LICENSE`，**内容照抄 `lua.h` 末尾原文**。

只编译 `lua/src/`，`lua/doc/`、`lua/Makefile` 保留不动（重新 vendor 时可整体覆盖）。

## 构建工具链

构建期用到的两个归档目前放在 **`.agent/toolchain/`**（`.agent/` 不入版本控制）：

| 文件 | 大小 | 用途 |
|---|---|---|
| `lua-5.4.8.tar.gz` | 374,332 B | vendored Lua 的来源归档 |
| `premake5.zip` | 611,781 B | premake5 发行包 |
| `premake-5/premake5.exe` | 1,567,744 B | 展开后的可执行文件（生成 VS 工程用） |

两者都是上游标准发行物，SHA-256 已核验。

> **已知缺口**：这两个归档放在被忽略的 `.agent/` 下，所以**从仓库全新 clone 出来
> 无法独立完成原生构建**（Lua 源码本身已在 `ocgcore/lua/` 里随仓库走，
> 缺的只是 premake5 这个生成器）。
> 补齐方式是加一个下载 + 校验脚本，而不是把二进制提交进仓库。**尚未做。**
