# Minecraft AI Companion 0.2.1-rc.1

Minecraft AI Companion 提供独立 `ServerPlayer` 同伴、Fabric 1.21.1 Runtime 控制，以及面向 PCL2/HMCL 的统一 Windows 控制终端。NeoForge 1.21.1 与 Forge 1.20.1 当前提供完整本地同伴能力，外部 Runtime 控制仅在 Fabric 1.21.1 启用。

## 启动终端

在项目根目录双击 `启动终端.cmd`，或运行：

```powershell
.\mcac.cmd
.\mcac.ps1 --help
```

首次启动会执行 `stageTerminalAtProjectRoot`，生成包含 Terminal、Runtime 和三个 Loader Mod 的 `mcac-local/`。无参数启动进入完整数字 TUI；所有 TUI 操作与 CLI 共用同一业务 Service。

发布包解压后第一层可直接运行：

```text
mcac.exe
mcac.cmd
mcac.ps1
启动终端.cmd
```

## 常用 CLI

```powershell
.\mcac.cmd --root "<PCL2或HMCL目录>" launcher scan
.\mcac.cmd --root "<PCL2或HMCL目录>" instance scan
.\mcac.cmd --root "<PCL2或HMCL目录>" doctor <实例ID>
.\mcac.cmd --root "<PCL2或HMCL目录>" plan install <实例ID>
.\mcac.cmd --root "<PCL2或HMCL目录>" install <实例ID> --yes
.\mcac.cmd --root "<PCL2或HMCL目录>" play <实例ID>
.\mcac.cmd --root "<PCL2或HMCL目录>" test smoke <实例ID>
```

写入操作要求显式确认。只有 `DetectionConfidence.HIGH` 可直接安装；MEDIUM 必须通过 `--confirm-game-dir <绝对路径>` 确认。

Runtime 为每个实例分配稳定独立端口，并使用带 Token 认证的 health/identity 协议：

```powershell
.\mcac.cmd --root <目录> runtime start <实例ID>
.\mcac.cmd --root <目录> runtime status <实例ID>
.\mcac.cmd --root <目录> runtime rotate-token <实例ID> --yes
```

Provider API Key 只从环境变量读取，不写入 JSON/YAML，也不会显示在错误中：

```powershell
.\mcac.cmd --root <目录> provider configure <实例ID> `
  --base-url https://example.com/v1 --model <模型> `
  --api-key-env MC_COMPANION_API_KEY --timeout-seconds 15
.\mcac.cmd --root <目录> provider test <实例ID>
```

## 支持矩阵

| Minecraft | Loader | Java | 控制模式 |
|---|---|---:|---|
| 1.21.1 | Fabric | 21 | FULL Runtime |
| 1.21.1 | NeoForge | 21 | LOCAL_ONLY |
| 1.20.1 | Forge | 17 | LOCAL_ONLY |

其他目标可以扫描和诊断，但安装会以 `BLOCKED` 退出且不修改实例。

同伴移动实现是简单局部移动、卡住检测和有限重规划，不是 Baritone，也不宣称完整全局寻路。

## 构建与验收

```powershell
.\gradlew.bat clean test check
.\gradlew.bat buildPlatforms
.\gradlew.bat launchTest
.\gradlew.bat gameTest
.\gradlew.bat runtimeFabricE2E
.\gradlew.bat persistenceRestartTest
.\gradlew.bat runtimeDisabledLaunchTest
.\gradlew.bat runtimeMultiProfileTest
.\gradlew.bat packageTerminalZip
.\gradlew.bat verifyTerminalPackage
```

产物：

```text
build/distributions/mcac-release/
build/distributions/mcac-release.zip
```

详细验收证据见 [FINAL_REPORT.md](FINAL_REPORT.md)，仍需人工确认的项目见 [KNOWN_LIMITATIONS.md](KNOWN_LIMITATIONS.md)。
