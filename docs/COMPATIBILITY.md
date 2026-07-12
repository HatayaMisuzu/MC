# Alpha 0.1 兼容性

| 目标 | Java | 最低验收 | Runtime 离线 |
|---|---:|---|---|
| Fabric 1.21.1 | 21 | 主目标：完整生命周期与行为演示 | 支持 |
| NeoForge 1.21.1 | 21 | JAR、Dedicated Server、世界、身体 GameTest | 支持 |
| Forge 1.20.1 | 17 | JAR、Dedicated Server、世界、身体 GameTest | 支持 |

可选兼容通过隔离 entrypoint 加载。目标 Mod 缺失、版本不支持或 adapter 初始化失败时，只关闭 adapter 并在 capability report 中说明原因。

本 Alpha 不承诺 Create、AE2、Mekanism 的深度自动化；这些能力只保留安全扩展边界，不能被标记为已支持。未经真实启动测试的整合包组合一律记为“未验证”。

