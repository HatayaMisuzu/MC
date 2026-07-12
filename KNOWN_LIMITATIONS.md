# Alpha 0.1 已知限制

- Travel 首版只承诺同维度地面行走、一格台阶、普通跳跃、开放地形和简单绕障。
- 搭桥、挖隧道、复杂游泳、跨维度门户路线和通用机器自动化不在冻结验收范围。
- Alpha 0.1 没有发布 Create、AE2、Mekanism adapter；这些 Mod 缺失时主 Mod 不受影响，也不宣称深度生产线支持。
- 外部 Runtime WebSocket 控制只在 Fabric 1.21.1 主目标启用；NeoForge 1.21.1 与 Forge 1.20.1 提供完整离线身体、本地命令与持久化，但 `/companion runtime` 会报告离线。
- Travel 使用有上限的局部避障与 stuck 失败，不是全局最优路径规划器；复杂迷宫可能安全暂停并返回 `STUCK`。
- LLM 只输出高层 Intent/Behavior，不能逐 Tick 控制，也不能绕过 verifier。
- 本 Alpha 没有视觉模型、完整人格、多同伴社会或通用长时自主任务。
- 未在兼容报告中列为已启动验证的整合包组合均视为未验证。
