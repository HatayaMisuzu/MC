# Known limitations — 0.2.1-rc.1

- `F:\wodeshijie\ceshi` 中的 PCL2 2.13.0.1 实例是 Minecraft 26.2 Vanilla，不在支持矩阵。真实扫描已正确返回 HIGH，并且安装计划以 `BLOCKED` 退出、没有修改实例。
- `F:\wodeshijie\nizhuanweilai` 中的 HMCL 3.10.3 自定义整合包已真实扫描，并从 Forge 参数解析为 Minecraft 1.20.1；本轮没有在正式实例上安装 Mod 或启动游戏。
- 当前没有可用于自动登录并进入世界的 PCL2/HMCL Fabric 1.21.1 图形客户端副本。因此 GUI `play`、目标实例上的 `mcac test smoke` 与 HMCL GUI Hook 生命周期仍为 `MANUAL_PENDING`。Dedicated Server、GameTest 与 Runtime/Fabric E2E 已自动通过，不能代替这项 GUI 验收。
- Forge 1.20.1 与 NeoForge 1.21.1 仅为 `LOCAL_ONLY`，没有外部 Runtime Bridge；终端不会把它们报告成 FULL 或握手成功。
- OpenAI-compatible Provider 已覆盖 URL、模型、超时、非 JSON、401/403/404/429/5xx 和脱敏错误测试；真实付费端点需要用户提供环境变量 Key，状态为 `MANUAL_PENDING`。
- 同伴行为是局部移动、卡住检测和有限重规划，不是 Baritone 或完整全局寻路。
- GitHub Actions 只有在本分支推送后才能形成远端独立证据；推送前状态必须记为 `MANUAL_PENDING`，不能记为通过。
