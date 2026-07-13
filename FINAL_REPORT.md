# Minecraft AI Companion 0.2.1-rc.1 验收报告

日期：2026-07-13

平台：Windows 11

基线：`1b12b7e`
工作分支：`agent/control-terminal-0.2.1-completion`

实现与本地验收提交：`22e0d063f8f4f4083c45a51c41265a4494bc61d1`

状态仅使用：`IMPLEMENTED`、`AUTOMATED_PASS`、`WINDOWS_LIVE_PASS`、`MANUAL_PENDING`、`BLOCKED_BY_EXTERNAL`、`NOT_IMPLEMENTED`。

## 修复与实现

| 项目 | 状态 | 当前证据 |
|---|---|---|
| 完整无参数 TUI | AUTOMATED_PASS | 8 个菜单均执行实际 Service；`tuiIntegrationTest` 从任意工作目录启动并安全退出 |
| 完整 CLI 与退出码 | AUTOMATED_PASS | launcher/instance/doctor/install/runtime/provider/play/connect/test/hook/logs 命令编译并通过共享测试 |
| 根目录首次启动 | WINDOWS_LIVE_PASS | `mcac.cmd`/`mcac.ps1` 首次缺少产物时执行 `stageTerminalAtProjectRoot` |
| 发布包启动器 | WINDOWS_LIVE_PASS | `mcac.exe`、`mcac.cmd`、`mcac.ps1`、`启动终端.cmd` 均实际执行 `--version` |
| Runtime 多实例 | WINDOWS_LIVE_PASS | 跨进程文件锁、真实端口占用检测；8766/8767 同时健康，停止 A 不影响 B |
| Runtime health/identity | WINDOWS_LIVE_PASS | Token 认证 HTTP health 返回版本、协议、profile/instance、端口、PID、启动时间、DB、session 数 |
| Token 稳定与轮换 | AUTOMATED_PASS | ensure 20 次不变；成功轮换；启动失败和重握手失败均恢复旧 Token/配置/Runtime |
| Doctor 动态检查 | AUTOMATED_PASS | runtime profile/port/PID/health/token/protocol/hook/install hash 不再固定 UNKNOWN，包含 evidence 与 repair |
| 真实行为 Smoke 实现 | IMPLEMENTED | 通过认证控制接口执行 STATUS/FOLLOW/PAUSE/RESUME/CANCEL，验证 accepted、lease、epoch、behavior、revision、顺序、超时、错误和最终租约释放 |
| PCL2/HMCL gameDir | AUTOMATED_PASS | 配置、runningDirectory、近期日志上下文、版本、root、Unicode、自定义目录、过期日志与冲突规则 |
| 安装事务 | AUTOMATED_PASS | 精确 Artifact、HIGH confidence、SHA-256、跨进程锁、journal 恢复、原子替换、备份、回滚、repair、受控卸载、manifest v1/v2 |
| Windows Hook | WINDOWS_LIVE_PASS | 原 Hook 与 mcac Hook 实执行；失败不阻塞；重复安装幂等；移除恢复；外部变化拒绝覆盖 |
| Provider | AUTOMATED_PASS | Key 仅来自环境变量；URL/模型/超时和 HTTP 错误分类；非 JSON 与错误信息脱敏 |
| 支持包隐私 | AUTOMATED_PASS | Token、Key、Authorization、query secret、用户路径、IPv4/IPv6、UUID、hostname 二次扫描 |
| GitHub Actions | IMPLEMENTED | PR 快速、Windows 完整、Minecraft 重型三套 workflow；远端结果需推送后确认 |

## Minecraft 与 Runtime 回归

| 命令/目标 | 状态 | 本轮结果 |
|---|---|---|
| `clean test check` | AUTOMATED_PASS | shared compile/unit/parser/installer/secret/forbidden API/independence 全部通过 |
| `buildPlatforms` | AUTOMATED_PASS | Fabric 1.21.1、NeoForge 1.21.1、Forge 1.20.1 JAR 构建通过 |
| `launchTest` | WINDOWS_LIVE_PASS | 三个 Dedicated Server 启动、加载 Mod、正常停止 |
| `gameTest` | WINDOWS_LIVE_PASS | 三 Loader GameTest 全部 required tests passed |
| `runtimeFabricE2E` | WINDOWS_LIVE_PASS | 握手、注册、lease、follow、pause、resume、stop、安全退出通过 |
| `persistenceRestartTest` | WINDOWS_LIVE_PASS | UUID、身体、位置与物品恢复通过，无物品复制证据 |
| `runtimeDisabledLaunchTest` | WINDOWS_LIVE_PASS | enabled=false 不读取 Token、不连接 Runtime，Mod 正常启动 |
| `runtimeMultiProfileTest` | WINDOWS_LIVE_PASS | 两个独立 Runtime identity 同时在线 |
| `verifyTerminalPackage` | WINDOWS_LIVE_PASS | 四个入口实际运行，发布目录敏感文件扫描通过 |

## 真实启动器检查

| Launcher/实例 | 状态 | 结果 |
|---|---|---|
| PCL2 2.13.0.1 / Minecraft 26.2 Vanilla | WINDOWS_LIVE_PASS | 正确扫描；unsupported，安装 `BLOCKED`；未修改实例 |
| HMCL 3.10.3 / 自定义 Forge 1.20.1 | WINDOWS_LIVE_PASS | 正确扫描并解析真实 Minecraft 版本；本轮只读检查 |
| PCL2/HMCL Fabric 1.21.1 GUI | MANUAL_PENDING | 缺少可自动登录并进入世界的测试客户端 |
| Forge/NeoForge GUI 启动 | MANUAL_PENDING | Dedicated Server 与 load/GameTest 已通过，GUI 未冒充成功 |

## 人工待验

- PCL2 Fabric 1.21.1：scan/install/play/handshake/目标实例 `mcac test smoke`。
- HMCL Fabric 1.21.1：scan/install/play/handshake/目标实例 `mcac test smoke`。
- HMCL GUI Hook 安装、启动、移除全过程。
- 真实 OpenAI-compatible 付费端点。

## 产物

- `build/distributions/mcac-release/`
- `build/distributions/mcac-release.zip`
- 发布目录内逐文件哈希：`build/distributions/mcac-release/SHA256SUMS.txt`
- `mcac-release.zip` SHA-256：`38e3b8de4494f625b6b3c35f5bb61b789b8ea5315288741ddb9dfaa6fb3d881c`

报告提交可通过 `git log -1 --oneline` 获取。没有远端 CI 证据前，不把 GitHub Actions 标为 PASS。
