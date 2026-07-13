# Minecraft AI Companion 统一控制终端 v1.0 实施报告

日期：2026-07-13

## 已完成

- 新增独立 `terminal` 架构：Launcher API、通用版本探测、PCL2、HMCL、Mod 元数据检查、事务安装、诊断、Runtime Supervisor 和终端应用。
- `mcac` 支持启动器/实例扫描、实例检查、doctor、安装计划、安装、更新/修复别名、回滚/卸载别名、Runtime start/stop/restart/status、Guided play、PCL2/HMCL Hook 和脱敏支持包。
- PCL2 支持 `$` 相对根目录、版本 JSON、`VersionArgumentIndieV2` 版本隔离、Unicode 实例名和损坏配置容错。
- HMCL 支持多个 configurations、相对/绝对 gameDir、未知字段和损坏 JSON 容错。
- 版本 JSON 支持安全 `inheritsFrom` 解析、循环/深度/大小/路径穿越限制及 Fabric/Forge/NeoForge 检测。
- 安装器执行精确目标匹配、只读计划、显式确认、实例锁、备份、临时复制、SHA-256、原子替换、清单和失败回滚；不会删除未知 Mod。
- Fabric Runtime Bridge 新增实例 `runtime.json`，JVM 属性仍为最高优先级；握手携带 installation/instance/launcher 标识（旧 Runtime 可忽略扩展字段）。
- Runtime 使用 per-instance profile/token/config/PID/log；Token 不进入命令行或日志。
- Hook 使用 Wrapper，写前备份，启动器运行时拒绝修改，失败始终不阻止 Minecraft；撤销前校验配置 checksum。
- `jpackage --win-console` 生成 `mcac-windows-x64.exe` 和内置 Java 21 Runtime；同一 JRE 启动外部 Runtime。

## 自动化验证

- `gradlew test check`：通过。
- 禁止 API、独立性和 secret 检查：通过。
- 三目标 `buildPlatforms`：通过。
- Fabric 1.21.1、Forge 1.20.1、NeoForge 1.21.1 独立服务端 `launchTest`：全部通过，Mod 加载且 Runtime 离线不影响启动。
- Fabric Runtime E2E：握手、同伴注册、租约、follow、pause、resume、stop、安全关闭全部通过。
- PCL2/HMCL parser 和事务安装/回滚单元测试：通过。
- Windows jpackage 应用镜像：EXE 可运行，内置 Runtime 为 Java 21。

## 真实 PCL2 实例验证

测试根目录：`F:\wodeshijie\ceshi`（报告不记录账号文件或凭据）。

发现结果：

```text
PCL2 正式版 2.13.0.1 (406)
Minecraft 26.2 / Vanilla
Java 25
gameDir=.minecraft/versions/26.2
版本隔离置信度=HIGH
```

由于 Minecraft 26.2 / Vanilla 不在三目标矩阵内：

- doctor 返回 `BLOCKED target.support`；
- 安装命令返回简洁 `BLOCKED`；
- 测试前后实例文件清单一致；
- 未创建 `.mccompanion`；
- 未修改启动器、版本 JAR、mods、config 或存档。

## 交付位置

- 自包含应用镜像：`build/distributions/mcac-windows-x64/`
- ZIP 分发包：`build/distributions/mcac-windows-x64.zip`
- 主程序：`build/distributions/mcac-windows-x64/mcac-windows-x64.exe`
- 使用说明：`README.md` 与 `docs/CONTROL_TERMINAL.md`
