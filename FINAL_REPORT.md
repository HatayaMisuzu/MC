# Minecraft AI Companion Alpha 0.1 最终验收报告

验收日期：2026-07-13（Asia/Shanghai）  
源码目录：`D:\工作台\MC`  
总体结论：**PASS（冻结的 Alpha 0.1 服务端范围）**

## 结论摘要

- PASS：Fabric 1.21.1、NeoForge 1.21.1、Forge 1.20.1 均生成真实发布 JAR，并通过 Dedicated Server Ready/世界创建/保存/正常停止。
- PASS：三个 Loader 均以真实 `ServerPlayer` 身体完成创建、移动、背包、死亡掉落、恢复和移除 GameTest；Fabric 另验证 follow/pause/resume/stop、封闭障碍的有界重规划与 `STUCK` 安全暂停。
- PASS：Fabric 与独立 Runtime 完成 WebSocket 握手、同伴注册、租约、follow、pause、resume、stop、断线安全停止；E2E 对协议状态拒绝和 Runtime `SEVERE` 为零容忍。
- PASS：跨服务器重启恢复 UUID、身体和钻石背包；FakeConnection 长时测试保持零保留包。
- PASS：从发布 JAR 在全新 Fabric Server 实例安装并运行；另在同一干净实例加入 Lithium 后通过兼容启动。
- PASS：禁止作弊 API、Numen/Baritone 独立性、密钥扫描、许可文件和源码来源检查。
- 已知 P0/P1：**0**。

## 实现范围

同伴具有稳定 UUID、owner ACL、原生 playerdata 与世界 SavedData；身体继承 `ServerPlayer`，通过同步丢弃出站包的 FakeConnection 工作。行为通过 `PlayerActionGateway` 注入真实玩家行走/跳跃输入，支持 follow、goto、stop、pause、resume，并用有限次数局部重规划、五分钟超时和结构化失败安全停止。Runtime 提供本地 WebSocket、配对 token、控制租约、SQLite WAL、TaskEvent、幂等命令、断线 reconciliation 和规则/可选 OpenAI-compatible Provider。

## 目标版本

| Minecraft | Loader | Loader 版本 | Java | 外部 Runtime |
|---|---|---:|---:|---|
| 1.21.1 | Fabric | 0.19.3 | 21 | PASS |
| 1.21.1 | NeoForge | 21.1.235 | 21 | 不在 Alpha 冻结范围；本地控制 PASS |
| 1.20.1 | Forge | 47.4.10 | 17 | 不在 Alpha 冻结范围；本地控制 PASS |

## 自动化与真实运行证据

| 项目 | 结果 | 证据目录 |
|---|---|---|
| pure-core / protocol / Runtime 单元与集成测试（70 项） | PASS | `test-results/core`, `test-results/protocol`, `test-results/runtime` |
| 三 Loader GameTest | PASS | `test-results/*` |
| 三 Loader Dedicated Server | PASS | `launch-logs/*` |
| Runtime/Fabric E2E | PASS | `traces/runtime-fabric-e2e` |
| Fabric 服务器重启持久化 | PASS | `traces/persistence-restart` |
| 2,400 游戏 tick 稳定性/FakeConnection | PASS | `traces/stability` |
| 干净生产 JAR + Fabric API 安装 | PASS | `compatibility/clean-install/fabric-1.21.1` |
| 从交付目录取出 JAR 后重新干净安装 | PASS | `compatibility/clean-install/delivery-fabric-1.21.1` |
| 干净生产 JAR + Lithium | PASS | `compatibility/clean-install/fabric-1.21.1-lithium` |
| 禁止 API、独立性、密钥和依赖审计 | PASS | `traces/release-audit` |

## 审查中发现并修复的问题

1. P0：发布 Fabric JAR 未携带 RuntimeBridge 所需 Jackson，开发环境能启动而干净生产安装在 Ready 后崩溃。根因是仅有开发类路径依赖；修复为 Loom nested JAR，并以全新 Fabric Server 实例回归。
2. P1：stop 后 Runtime 快照可能保留非零 behavior revision 却没有 behaviorId，导致 `CompanionStatus` 被拒绝。修复快照不变量，并让 E2E 对协议拒绝直接失败。
3. P1：碰撞微抖会被旧逻辑误认为路径进展，可能永不触发 STUCK。改为只有实际缩短目标距离才算进展；封闭障碍真实 GameTest 验证三次重规划后安全暂停。
4. P3：禁止 API 扫描会扫描隔离测试夹具造成误报。修正生产源码边界，测试夹具仍由 GameTest 证据约束。

## 已知限制与未宣称内容

- Travel 是有界局部避障，不是 A* 全局路径规划；复杂迷宫会安全返回 `STUCK`。
- Create、AE2、Mekanism adapter 未发布，因而只验证“adapter 缺失不影响主 Mod”，不宣称机器自动化兼容。
- 外部 Runtime 桥接仅在 Fabric 主目标交付；NeoForge/Forge 的身体、本地命令和持久化可用。
- 自动验收覆盖真实无界面 Dedicated Server；未执行需要微软账号和人工视觉操作的 GUI 玩家客户端会话。
- DeepSeek 配置支持 OpenAI-compatible endpoint/model；本轮未把用户测试密钥写盘，也未在报告或日志中保存密钥。离线 rules Provider 与受控 HTTP stub 测试均通过。

这些是已记录的 Alpha 范围边界，未发现会阻止已声明功能使用的 P0/P1。

## 安装

1. 选择与 Minecraft/Loader 完全匹配的 `jars/<target>/` JAR。
2. Fabric 1.21.1 同时安装 Fabric API 0.116.13+1.21.1；1.21.1 使用 Java 21，Forge 1.20.1 使用 Java 17。
3. 启动世界后执行 `/companion create Numenless`，再用 `/companion follow`、`/companion goto <x> <y> <z>`、`/companion pause|resume|stop`。
4. 可选 Runtime：解压 `runtime/`，按 `install/RUNTIME_SETUP.md` 设置；API Key 只放环境变量 `MC_COMPANION_API_KEY`。

## 复现命令

```powershell
cd D:\工作台\MC
.\gradlew.bat clean build test check
.\gradlew.bat launchTest gameTest
.\tools\fabric-persistence-restart.ps1
.\tools\runtime-fabric-e2e.ps1
.\tools\fabric-stability.ps1
.\tools\fabric-clean-install.ps1
.\tools\fabric-clean-install.ps1 -WithLithium
.\tools\package-alpha.ps1
```

## Git 提交

交付时以 `git log --oneline` 和 `traces/release-audit/git-log.txt` 为准；工作树必须为空。源码归档由已提交的 `HEAD` 生成。
