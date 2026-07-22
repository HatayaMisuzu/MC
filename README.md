# Minecraft AI Companion 0.3.0

Minecraft AI Companion（MCAC）是面向 PCL2/HMCL 的 Windows 本地 Minecraft 身体与控制中心。
Hermes、DeepSeek 或其他外部 LLM/Agent 是唯一高层决策者；MCAC 提供通用 Minecraft
Tools、上下文、记忆、搜索、安全、持久化、验证、确定性 Task Graph 执行和产品界面。
MCAC 不是内置高层 Agent，也不是隐藏 Planner。

本版本按 Fabric-first RC 交付：自动化验证负责安装、Runtime、Task Graph、Skill Workspace、
诊断和恢复边界；真实付费 Brain 验证与真人启动器/游玩测试明确留给拿到此 RC 后执行。
自动化证据与外部待验证项以 [RC 完成矩阵](docs/RC_COMPLETION_MATRIX.md) 为准。

## 使用发布包

1. 解压 `mcac-release.zip`，不要只单独复制 `mcac.exe`。
2. 双击第一层的 `mcac.exe`。
3. 在浏览器页面中选择自动扫描到的 PCL2/HMCL 实例。
4. 依次完成 Doctor、安装、Runtime、游戏启动、Companion 控制和冒烟测试。

再次双击 `mcac.exe` 会复用已运行的本地后端并重新打开控制页面。服务只监听 `127.0.0.1` 的动态端口；所有 API 需要随机会话 Cookie 与独立 CSRF Token，拒绝跨站、局域网和公网请求。

发布目录结构：

```text
mcac-release/
├─ mcac.exe
├─ mcac-cli.exe
├─ app/
├─ runtime/
├─ web/
├─ artifacts/
├─ legal/
├─ README.txt
├─ KNOWN_LIMITATIONS.md
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
启动器凭据或直接世界/库存编辑。

木材、钻石、熔炼、箱子、防御和建造是通用性验收场景，不对应专用 Java Handler。
未知 Mod 内容通过动态 Registry、通用交互和真实 Observation 支持。

## 支持矩阵

| Minecraft | Loader | Java | 模式 |
|---|---|---:|---|
| 1.21.1 | Fabric | 21 | FULL Runtime Bridge |
| 1.21.1 | NeoForge | 21 | LOCAL_ONLY |
| 1.20.1 | Forge | 17 | LOCAL_ONLY |

Forge/NeoForge 当前没有 Runtime Bridge，界面会明确显示 `LOCAL_ONLY`，不会伪装成完整握手成功。其他版本可扫描和诊断，但安装会被阻止且不会修改实例。

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

当前文档入口：

- [架构与权限边界](docs/ARCHITECTURE.md)
- [当前执行约束](CODEX_EXECUTION.md)
- [RC 完成矩阵](docs/RC_COMPLETION_MATRIX.md)
- [Typed Task Graph DSL](docs/TASK_GRAPH_DSL.md)
- [MCP 协议](docs/MCP_PROTOCOL.md)
- [真实 Brain 与真人试玩指南](docs/LIVE_BRAIN_HUMAN_PLAYTEST.md)
- [已知限制](KNOWN_LIMITATIONS.md)
