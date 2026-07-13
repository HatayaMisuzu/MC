# Control Terminal 0.2-alpha 最终验收报告

## 版本

- Base commit: `0c53ac1`
- Terminal: `0.2-alpha`
- Runtime/Mod: `0.1.0-alpha`
- OS: Windows 11
- Date: 2026-07-13

状态只使用：`IMPLEMENTED`、`AUTOMATED_PASS`、`WINDOWS_LIVE_PASS`、`MANUAL_PENDING`、`BLOCKED_BY_EXTERNAL`、`NOT_IMPLEMENTED`。

## 已修复问题

| ID | Severity | Root cause | Fix | Regression test | Status |
|---|---|---|---|---|---|
| P1-01 | P1 | 普通 configure 总是生成 Token | `ensureConfigured` 幂等；rotate 独立、原子、双端一致 | 连续 20 次 hash 不变；rotate 双端一致 | AUTOMATED_PASS |
| P1-02 | P1 | 所有实例硬编码 8766 | profile.json 持久化 8766–8866 独立端口 | 双 Runtime 8766/8767；停止 A 不影响 B | WINDOWS_LIVE_PASS |
| P1-03 | P1 | 仅目录启发式判断 gameDir | PCL/HMCL 配置、日志、runningDirectory、显式确认；MEDIUM 阻止安装 | Parser fixture + PCL2 真实实例 | AUTOMATED_PASS |
| P1-04 | P1 | play 打开启动器后立即退出 | 状态机输出并查询 Runtime SQLite 权威 session，等待真实握手 | Runtime E2E；GUI 客户端待验 | MANUAL_PENDING |
| P1-05 | P1 | HMCL Hook 命中首项 | 收集全部匹配，必须恰好一个 | 重名双匹配阻止 | AUTOMATED_PASS |
| P2-01 | P2 | enabled=false 仅记录日志 | 禁用时不调度连接/读取 Token | Fabric Dedicated Server 禁用配置启动 | WINDOWS_LIVE_PASS |
| P2-02 | P2 | 原 Hook 命令直接 call | 原命令隔离到独立 wrapper，主 wrapper 仅引用文件 | 中文及 `&|<>^()` 回归 | AUTOMATED_PASS |
| P2-03 | P2 | 返回首个 JAR | 读取 Mod ID/Loader/MC 范围，多个精确候选阻止 | exact/wrong/multiple fixture | AUTOMATED_PASS |
| P2-04 | P2 | doctor 检查过少 | 补齐 launcher/gameDir/Java/mod/runtime/protocol/hook/crash/disk/manifest/hash | CLI 实测 | AUTOMATED_PASS |
| P2-05 | P2 | 缺 connect/smoke/provider/attach | 完整注册并实现实际查询/测试/配置逻辑 | CLI help、Runtime E2E | AUTOMATED_PASS |
| P2-06 | P2 | 支持包脱敏不完整 | 路径、Token、Bearer、query secret、IP、Authorization 脱敏并二次扫描 | PCL2 真实日志支持包 | WINDOWS_LIVE_PASS |

## 根目录启动

- `mcac.cmd`、`mcac.ps1`、`启动终端.cmd`：WINDOWS_LIVE_PASS
- 任意工作目录、参数转发和退出码：AUTOMATED_PASS
- `stageTerminalAtProjectRoot` 生成忽略的 `mcac-local/`：WINDOWS_LIVE_PASS

## TUI 与 CLI

- 无参数中文数字 TUI、取消和统一退出码：AUTOMATED_PASS
- 规范列出的 launcher/instance/install/runtime/provider/play/attach/connect/test/hook/logs 命令均有实现：IMPLEMENTED

## PCL2 / HMCL

- PCL2 2.13.0.1、26.2 Vanilla：WINDOWS_LIVE_PASS（准确发现、BLOCKED、目录无变化）
- PCL2/HMCL parser、Unicode、多 root、损坏配置、隔离和 Hook：AUTOMATED_PASS
- HMCL GUI：MANUAL_PENDING

## Runtime 多实例

- profile identity、稳定端口、稳定 Token、PID/command/port health：AUTOMATED_PASS
- 两个自包含 Runtime 同时在线，stop A 不影响 B：WINDOWS_LIVE_PASS

## 安装/修复/回滚

- 精确 Artifact、HIGH confidence、锁、SHA-256、原子替换、备份、rollback、repair、uninstall 管理边界：AUTOMATED_PASS
- 正式 26.2 实例未产生 `.mccompanion`：WINDOWS_LIVE_PASS

## Fabric handshake 与 smoke

- Runtime handshake、注册、lease、follow、pause、resume、stop、safe shutdown：AUTOMATED_PASS
- GUI 世界中的 `mcac test smoke`：MANUAL_PENDING

## Forge/NeoForge LOCAL_ONLY

- Dedicated Server launch 与 GameTest：AUTOMATED_PASS
- 明确 LOCAL_ONLY，不报告 Runtime PASS：IMPLEMENTED

## 原项目回归

- `clean test check`：AUTOMATED_PASS
- `buildPlatforms`：AUTOMATED_PASS
- `launchTest` 三 Loader：AUTOMATED_PASS
- `gameTest` 三 Loader：AUTOMATED_PASS
- `runtimeFabricE2E`：AUTOMATED_PASS
- `persistenceRestartTest`：AUTOMATED_PASS
- Fabric stability：AUTOMATED_PASS

## 安全与支持包

- secret/forbidden API/independence checks：AUTOMATED_PASS
- PCL2 真实日志生成支持包并通过二次 privacy scanner：WINDOWS_LIVE_PASS
- 未读取或打包启动器账号文件：IMPLEMENTED

## Windows 干净发布包

- 根目录 `mcac.exe`、内置 Java 21、三 Artifact、legal、README、SHA256SUMS：WINDOWS_LIVE_PASS
- ZIP 解压根直接含 EXE：WINDOWS_LIVE_PASS
- 发布包无 Token/DB/log/account：AUTOMATED_PASS

## 手工验收项

- PCL2/HMCL Fabric 1.21.1 登录并进入图形世界：MANUAL_PENDING
- HMCL GUI Hook 生命周期：MANUAL_PENDING
- OpenAI-compatible Provider 真实付费端点：MANUAL_PENDING

## 产物

- `build/distributions/mcac-release/`
- `build/distributions/mcac-release.zip`
- `build/terminal-test-results/`

具体文件哈希记录在发布包 `SHA256SUMS.txt`。
