# Minecraft AI Companion 0.3.0 验收报告

日期：2026-07-13

平台：Windows 11

工作分支：`agent/control-terminal-0.2.1-completion`

目标 PR：#2

本报告只记录已经实际执行的检查。实现提交 `a1f3a55` 已通过 PR #2 的 Linux shared checks 与 Windows Chromium/干净发布包远程门禁。

## 产品结果

| 项目 | 状态 | 证据 |
|---|---|---|
| 默认入口 | LOCAL_PASS | 发布包 `mcac.exe` 无参数启动本地 HTML 终端；CLI 仅由 `mcac-cli.exe` 或显式参数进入 |
| 本地服务安全 | LOCAL_PASS | 仅监听 `127.0.0.1` 动态端口；随机 Bootstrap/Session/CSRF Token；校验 Host、Origin、Fetch Metadata；拒绝非 Loopback |
| 单实例复用 | LOCAL_PASS | 第二次启动复用已有后端并打开新的 Bootstrap URL，不重复占用端口 |
| HTML 功能闭环 | LOCAL_PASS | 扫描、计划、安装、修复、Runtime、连接、Doctor、日志、支持包、回滚、重装、卸载均由 Playwright 在真实浏览器中完成 |
| 写操作事务 | LOCAL_PASS | 所有 Web 写入口执行“计划 → 确认 → 进度 → 验证”；失败路径调用已有 Service 回滚能力 |
| 前后端分层 | LOCAL_PASS | React 不读取 SQLite、Token 或 Minecraft 文件；所有状态和写入均经过 Java API/Service |
| 实时通道 | LOCAL_PASS | 状态、操作和行为事件使用 SSE；Runtime/Minecraft 日志使用独立 SSE 流 |
| 视觉与响应式 | LOCAL_PASS | 中文桌面控制中心、深浅主题、1366×768 与 3840×2160 检查无横向溢出；后端断开有显式横幅 |
| 发布包 | LOCAL_PASS | 自带 Java Runtime、Web 产物和 Mod 工件；任意工作目录启动，无需 Node.js、Gradle 或系统 Java |
| GitHub Actions PR 门禁 | REMOTE_PASS | `PR fast checks` run 29230795783 与 `Windows terminal validation` run 29230795729 均成功 |
| GitHub Actions 主分支重型回归 | MERGE_GATE_PENDING | `Minecraft heavy validation` 只允许在 `main`、定时或手动运行；合并后自动触发并继续监控 |

## 自动化和真实运行证据

| 检查 | 结果 |
|---|---|
| `gradlew check` | PASS：Java 单元/集成测试、3 个 React 组件测试、秘密扫描、Forbidden API、独立性检查 |
| `npm run e2e` | PASS：Chromium 完成普通用户管理闭环，不只是检查标题或按钮存在 |
| `runtimeFabricE2E` | PASS：真实 Fabric GameTest + Runtime WebSocket 握手、注册、Lease、FOLLOW、PAUSE、RESUME、STOP、最终 CANCELLED |
| `persistenceRestartTest` | PASS：停止/重启后恢复 Companion UUID、身体和物品，无复制 |
| `runtimeDisabledLaunchTest` | PASS：Runtime 禁用时 Mod 正常加载并保持本地能力 |
| `runtimeMultiProfileTest` | PASS：8766/8767 两个认证 Runtime 同时健康，停止 A 不影响 B |
| `gameTest launchTest` | PASS：Fabric、Forge、NeoForge 的 GameTest/专用服务器真实启动回归 |
| `verify-terminal-package.ps1` | PASS：结构、逐文件 SHA-256、ZIP 内容、CLI 维护入口 |
| `html-terminal-start-test.ps1` | PASS：从任意工作目录启动真实 `mcac.exe`、动态端口、第二实例复用、认证 API、内嵌 HTML |
| 入口测试 | PASS：项目根入口、发布包入口、维护 TUI 均从任意工作目录运行 |
| 手工浏览器 QA | PASS：安装 SHA/回滚点、Runtime 身份、SAFE_IDLE、21 项动态 Doctor（UNKNOWN=0）、日志 SSE、脱敏支持包、回滚/重装/卸载 |

## 真实启动器只读扫描

| 来源 | 结果 |
|---|---|
| `F:\wodeshijie\ceshi` | PCL2 正式版 2.13.0.1；Minecraft 26.2 Vanilla；HIGH 置信度；不支持安装且未修改实例 |
| `F:\wodeshijie\nizhuanweilai` | HMCL 3.10.3；Minecraft 1.20.1 Forge；Java 17；HIGH 置信度；因无 Bridge 显示 `LOCAL_ONLY` |

自动化 PCL2 Fabric 1.21.1 夹具覆盖浏览器内的扫描、安装、修复、回滚和卸载；真实 Fabric Mod 与 Runtime 行为由独立 GameTest E2E 覆盖。测试不读取第三方启动器账号，也不会替用户登录个人存档。

## 发布产物

- 目录：`build/distributions/mcac-release/`
- ZIP：`build/distributions/mcac-release.zip`
- ZIP 大小：76,409,347 字节
- ZIP SHA-256：`099ebe1a5a96aa30d1a51e786318d5b773014a56ec4cbe86ff021823db490d8a`
- 发布目录内含逐文件 `SHA256SUMS.txt`

## 已知限制

- Forge 1.20.1 与 NeoForge 1.21.1 尚无 Runtime Bridge，只能 `LOCAL_ONLY`，界面不会显示完整连接成功。
- OpenAI-compatible Provider 的真实付费端点需要用户通过环境变量或 Windows Credential Manager 提供 Key；仓库和普通配置不保存 Key。
- 第三方启动器账号登录和个人存档进入依赖用户已有会话；自动化不接触账号凭据。
- Companion 使用安全局部移动、卡住检测和有限重规划，不提供 Baritone 式全局寻路。

更多说明见 `KNOWN_LIMITATIONS.md`。PR 远程证据：

- <https://github.com/HatayaMisuzu/MC/actions/runs/29230795783>
- <https://github.com/HatayaMisuzu/MC/actions/runs/29230795729>

合并到 `main` 后必须等待自动触发的 Minecraft 重型回归完成；若失败则继续修复，不把“已合并”等同于“已验收”。
