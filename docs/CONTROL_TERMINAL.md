# `mcac` 统一控制终端

## 安全模型

- 扫描、列表、检查和安装计划均为只读。
- `install`、`rollback`、`hook install`、`hook remove` 必须带 `--yes`。
- 安装使用实例锁、临时文件、SHA-256 校验、备份和清单；失败时恢复已移动文件。
- PCL2/HMCL Hook 写入前备份。撤销时若配置已被用户或启动器继续修改，终端会拒绝覆盖。
- 支持包采用允许列表，只收集诊断摘要、Companion 安装清单、Mod 文件名和 `latest.log`；账号文件不在扫描范围内。

## PCL2

适配器读取启动器旁 `PCL/Setup.ini` 的 `LaunchFolderSelect`。`$` 按 PCL2 规则解析为启动器目录，随后扫描 `versions/<id>/<id>.json`。版本级 `PCL/Setup.ini` 中 `VersionArgumentIndieV2:True` 表示真实 `gameDir` 为版本目录。

Hook 仅修改版本级 `VersionAdvanceRun` 和 `VersionAdvanceRunWait`，实际命令位于控制中心生成的 `.cmd` Wrapper 中，末尾始终返回 0。

## HMCL

适配器读取 `.hmcl/hmcl.json` 中全部 `configurations`，支持相对和绝对 `gameDir`。发现未知字段或某个损坏实例时不会导致全局扫描崩溃。

HMCL Hook 只有在 `selectedMinecraftVersion` 能唯一匹配实例时才可写入；否则提示使用 Guided 模式，避免修改错误配置。

## 兼容诊断

结果级别：

- `PASS`：检查通过。
- `WARNING`：可继续，但能力可能不完整。
- `BLOCKED`：禁止安装或危险写入。
- `UNKNOWN`：证据不足，不猜测。

例如 Minecraft 26.2 / Vanilla 可以被正确发现，但不在项目三目标矩阵内，因此 `target.support` 为 `BLOCKED`，不会选择“看起来接近”的 1.21.1 JAR。

## 文件位置

实例内：

```text
<gameDir>/.mccompanion/
├─ install-manifest.json
├─ install.lock
├─ runtime.token
└─ backups/<rollback-id>/
```

全局控制目录：

```text
%LOCALAPPDATA%/MinecraftAICompanion/
├─ profiles/<instance-id>/
└─ hooks/<instance-id>/
```

Fabric 读取 `<gameDir>/config/minecraft-ai-companion/runtime.json`。JVM 参数仍保留为测试和高级部署的最高优先级覆盖项。
