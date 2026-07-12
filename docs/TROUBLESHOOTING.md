# 排错

## Mod 没有加载

确认 JAR 与 Minecraft/Loader 目标一致，并使用 Java 21（1.21.1）或 Java 17（Forge 1.20.1）。不要把三个目标 JAR 同时放入一个实例。

## 状态为 LOCAL_ONLY

这是 Runtime 离线时的正常安全状态。本地 create/spawn/follow/goto/stop 仍应可用。若需要 Runtime，确认它监听 `127.0.0.1:8766`，两侧 token 和 `mc-companion/1` 协议一致。

## 协议不兼容

Mod 会拒绝 Runtime 控制并保持本地可用。升级或降级 Runtime/Mod 使协议主版本一致；不要删除世界数据绕过检查。

## goto 失败

`PATH_NOT_FOUND`、`PATH_BLOCKED`、`STUCK`、`TARGET_UNLOADED` 和 `DIMENSION_UNSUPPORTED` 是结构化失败，不会自动传送。移除障碍、选择同维度已加载目标或让同伴回到 owner 附近后重试。

## 无法控制同伴

确认命令执行者是登记的 owner，或拥有 OP 诊断权限。Runtime 命令还需要有效 lease 和匹配 epoch；旧会话的命令会返回 `STALE_EPOCH`。

## Provider 失败

确认 `base_url`、模型名和 API key 环境变量。Runtime 不打印 key；Provider 失败会回退规则模式。查看 `runtime.log` 中脱敏后的状态码和 failure code。

## 报告问题时提供

- Minecraft、Loader、Java、Mod 和 Runtime 版本；
- `latest.log` 与 `runtime.log` 的相关片段（先检查是否含私人信息）；
- `/companion status` 和 capability 输出；
- 可复现步骤；
- 不要提供配对 token、API key 或账号文件。

