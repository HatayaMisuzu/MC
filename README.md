# Minecraft AI Companion Control Terminal 0.2-alpha

统一控制终端负责 PCL2/HMCL 实例发现、兼容诊断、事务安装、独立 Runtime profile、真实握手等待、安全测试、Hook、Provider 配置和脱敏支持包。启动器继续负责账号登录与 Minecraft 启动。

## 直接启动

在仓库根目录双击 `启动终端.cmd`，或运行：

```powershell
.\mcac.cmd
.\mcac.ps1 --help
```

无参数进入中文 TUI。根脚本会优先使用自包含发布版，其次使用本地 staged 版或开发版；不存在时自动构建开发终端。

## 常用命令

```powershell
.\mcac.cmd --root "<PCL2或HMCL目录>" launcher scan
.\mcac.cmd --root "<PCL2或HMCL目录>" instance scan
.\mcac.cmd --root "<PCL2或HMCL目录>" doctor <实例ID>
.\mcac.cmd --root "<PCL2或HMCL目录>" plan install <实例ID>
.\mcac.cmd --root "<PCL2或HMCL目录>" install <实例ID> --yes
.\mcac.cmd --root "<PCL2或HMCL目录>" play <实例ID>
.\mcac.cmd --root "<PCL2或HMCL目录>" test smoke <实例ID>
```

写入操作必须显式 `--yes`。`MEDIUM` gameDir 禁止安装；确认真实目录时使用 `--confirm-game-dir <绝对路径>`，确认只保存在终端 profile。

Runtime 每实例使用稳定 Token 和独立端口（8766–8866）：

```powershell
.\mcac.cmd runtime profiles
.\mcac.cmd --root <目录> runtime start <实例ID>
.\mcac.cmd --root <目录> runtime status <实例ID>
.\mcac.cmd --root <目录> runtime rotate-token <实例ID> --yes
```

Provider API Key 只从指定环境变量读取，不写入 JSON/YAML：

```powershell
.\mcac.cmd --root <目录> provider configure <实例ID> --base-url <URL> --model <模型> --api-key-env MC_COMPANION_API_KEY
.\mcac.cmd --root <目录> provider test <实例ID>
.\mcac.cmd --root <目录> provider disable <实例ID>
```

## 支持矩阵

| Minecraft | Loader | Java | 模式 |
|---|---|---:|---|
| 1.21.1 | Fabric | 21 | FULL Runtime |
| 1.21.1 | NeoForge | 21 | LOCAL_ONLY |
| 1.20.1 | Forge | 17 | LOCAL_ONLY |

其他目标可扫描但禁止安装。Runtime 离线或禁用不会阻止 Mod 本地命令。

## 构建与验证

```powershell
.\gradlew.bat clean test check
.\gradlew.bat buildPlatforms launchTest gameTest
.\gradlew.bat runtimeFabricE2E persistenceRestartTest
.\gradlew.bat verifyTerminalPackage runtimeMultiProfileTest
```

发布产物：

```text
build/distributions/mcac-release/
build/distributions/mcac-release.zip
```

详细说明见 [docs/CONTROL_TERMINAL.md](docs/CONTROL_TERMINAL.md)，验收证据见 [FINAL_REPORT.md](FINAL_REPORT.md)。
