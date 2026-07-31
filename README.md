# Minecraft AI Companion 0.3.1

Minecraft AI Companion（MCAC）是面向 PCL2/HMCL 的 Windows 本地 Minecraft 身体与控制中心。
Hermes、DeepSeek 或其他外部 LLM/Agent 是唯一高层决策者；MCAC 提供通用 Minecraft
Tools、上下文、记忆、搜索、安全、持久化、验证、确定性 Task Graph 执行和产品界面。
MCAC 不是内置高层 Agent，也不是隐藏 Planner。

当前产品版本为 0.3.1：自动化产品化基线已冻结（`FROZEN`），并已作为
`mcac-productization-baseline-0.3.1` 标注 tag / GitHub Release 发布。Readiness 标签
`READY_FOR_LIVE_BRAIN_AND_HUMAN_TEST_RC` 表示自动化闭环已完成、正在等待外部证据，它
**不构成** Live-provider 或真人试玩证据：`LIVE_BRAIN_EXTERNAL_VERIFICATION_PENDING` 与
`HUMAN_PLAYTEST_PENDING` 仍然有效。机器可读的当前产品事实位于
[PRODUCT_TRUTH.json](docs/product/PRODUCT_TRUTH.json)，自动化证据与外部待验证项以
[RC 完成矩阵](docs/RC_COMPLETION_MATRIX.md) 为准。

本版本以 Fabric 1.21.1 与 Forge 1.20.1 双 Full Runtime Bridge 为自动化产品目标；
NeoForge 1.21.1 保持 `LOCAL_ONLY`。

## 使用发布包

1. 完整解压 `mcac-release.zip`，不要只单独复制 `mcac.exe`。
2. 启动方式任选其一：
   - PowerShell 中运行 `.\mcac.exe web --open-browser`，明确允许本次启动打开系统默认浏览器；
   - 双击 `启动终端.cmd`，它等价于 `mcac.exe web --open-browser`。
3. 在浏览器页面中选择自动扫描到的 PCL2/HMCL 实例。
4. 依次完成 Doctor、安装、Runtime、游戏启动、Companion 控制和冒烟测试。

直接双击 `mcac.exe`（或运行 `mcac.cmd`、`mcac.ps1`）只会启动或复用本地后端，不再隐式调用
Windows 默认浏览器；需要打开控制页面时明确传入 `--open-browser`，也可以设置
`MCAC_OPEN_BROWSER=true`。开发、测试和无人值守流程应保持默认行为，或设置
`MCAC_NO_BROWSER=true` 作为不可被命令行覆盖的安全开关（对 `启动终端.cmd` 同样生效）。
服务只监听 `127.0.0.1` 的动态端口；所有 API 需要随机会话 Cookie 与独立 CSRF Token，
拒绝跨站、局域网和公网请求。

`mcac-cli.exe`（或带参数调用 `mcac.cmd` / `mcac.ps1`）是自动化 CLI，普通用户不需要它。

发布目录结构：

```text
mcac-release/
├─ mcac.exe / mcac-cli.exe / runtime-app.exe
├─ mcac.cmd / mcac.ps1 / 启动终端.cmd / runtime-app-bundled.cmd
├─ app/                 终端程序与运行库
├─ runtime/             Runtime 服务与运行库
├─ web/                 HTML 控制终端资源
├─ artifacts/           三个 Loader 的 Mod JAR
├─ legal/               LICENSE 与 NOTICE
├─ README.md / README.txt（README.txt 由 README.md 生成）
├─ CHANGELOG.md / KNOWN_LIMITATIONS.md
├─ docs/                当前用户与集成文档（入口见 [docs/INDEX.md](docs/INDEX.md)）
├─ release-manifest.json
├─ sbom.spdx.json
└─ SHA256SUMS.txt
```

ZIP 同目录还会生成 `mcac-release.zip.sha256`，用于在解压前校验整个发布包。

## HTML 控制终端

页面内可完成：

- 总览、PCL2/HMCL 与实例扫描、兼容性判断；
- 安装计划、安装、更新、修复、回滚，以及“保留数据”或“删除 MCAC 数据”两种卸载；
- Doctor → Runtime → 启动器 → Minecraft → Mod 握手生命周期；
- Companion 的 status/follow/come/goto/pause/resume/stop；
- STATUS → FOLLOW → PAUSE → RESUME → STOP 行为冒烟测试；
- Runtime 多 Profile、日志、Token 轮换和认证健康检查；
- rules 或 OpenAI-compatible Provider 配置；
- 动态 Doctor、一键修复、实时日志和脱敏支持包；
- 深色/浅色模式、安全状态、版本、隐私说明和停止后台服务。

所有写操作都经过“生成计划 → 用户确认 → 执行 → 结构化进度 → 验证 → 失败回滚”。前端不直接读取 SQLite、Token 或 Minecraft 文件。

现有 HTML 终端将继续扩展 Task Graph、Skills、Workspace 和权限视图，不会另起一套控制产品。

## 架构与权限

```text
Task Graph Runtime = deterministic orchestration
External Brain = reasoning and planning
```

运行时外部 Brain 只能通过受限 Tool Gateway、逻辑 Workspace 和声明式 JSON/YAML
Task Graph/Skill 工作。它不能访问 Shell、Git、Gradle、任意文件系统、生产源码、Cookie、
启动器凭据或直接世界/库存编辑。External Brain 失败时返回有界失败并保留可恢复状态，
Runtime 不会回退到内部 Agent/Planner；未配置或不可用 Brain 时自然语言复杂请求
fail closed，本地显式控制命令（如 follow/come/stop/status）仍可独立工作。

木材、钻石、熔炼、箱子、防御和建造是通用性验收场景，不对应专用 Java Handler。
未知 Mod 内容通过动态 Registry、通用交互和真实 Observation 支持。

## 支持矩阵

| Minecraft | Loader | Java | 模式 |
|---|---|---:|---|
| 1.21.1 | Fabric | 21 | FULL Runtime Bridge |
| 1.21.1 | NeoForge | 21 | LOCAL_ONLY |
| 1.20.1 | Forge | 17 | FULL Runtime Bridge |

Fabric 1.21.1 与 Forge 1.20.1 使用完整 Runtime Bridge；NeoForge 1.21.1 会明确显示
`LOCAL_ONLY`，不会伪装成完整握手成功。其他版本可扫描和诊断，但安装会被阻止且不会修改实例。

Provider API Key 只能来自环境变量或 Windows Credential Manager；普通配置文件只保存环境变量名称，不保存 Key 值。

## 开发与自动化

CLI 仅为测试和自动化保留，使用 `mcac-cli.exe` 或带参数调用 `mcac.cmd`。普通用户不需要命令行。

```powershell
.\gradlew.bat clean check buildPlatforms
.\gradlew.bat runtimeFabricE2E persistenceRestartTest
.\gradlew.bat verifyTerminalPackage htmlTerminalStartTest
.\gradlew.bat releaseGoldenPathTest
cd terminal\web-ui
npm run e2e
```

产物位于：

```text
build/distributions/mcac-release/
build/distributions/mcac-release.zip
```

## 文档入口

普通用户与运维：

- [文档导航](docs/INDEX.md)
- [当前产品状态与支持矩阵](docs/PRODUCT_STATUS.md)
- [中文用户指南](docs/user/USER_GUIDE.zh-CN.md)
- [English user guide](docs/user/USER_GUIDE.en-US.md)
- [安装：Fabric 1.21.1](docs/INSTALL_FABRIC_1.21.1.md) · [安装：Forge 1.20.1](docs/INSTALL_FORGE_1.20.1.md) · [安装：NeoForge 1.21.1](docs/INSTALL_NEOFORGE_1.21.1.md)
- [游戏命令](docs/COMMANDS.md)
- [故障排除](docs/TROUBLESHOOTING.md)
- [已知限制](KNOWN_LIMITATIONS.md)

开发者与外部 Brain 集成：

- [架构与权限边界](docs/ARCHITECTURE.md)
- [开发者入口](docs/developer/README.md)
- [Typed Task Graph DSL](docs/TASK_GRAPH_DSL.md)
- [MCP 协议](docs/MCP_PROTOCOL.md)
- [兼容层工程](docs/compatibility/MCAC_COMPATIBILITY_LAYER_ENGINEERING.md)
- [真实 Brain 与真人试玩指南](docs/LIVE_BRAIN_HUMAN_PLAYTEST.md)

机器可读事实与自动化证据：

- [PRODUCT_TRUTH.json](docs/product/PRODUCT_TRUTH.json)
- [Brain Adapter capability matrix](docs/product/BRAIN_ADAPTER_CAPABILITIES.json)
- [RC 完成矩阵](docs/RC_COMPLETION_MATRIX.md)

仓库内部执行规则（`AGENTS.md`、`CODEX_EXECUTION.md` 等）、历史审计与执行跟踪仅存在于源码
仓库，不随产品发布包提供；它们不属于当前产品状态来源。
