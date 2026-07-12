# Alpha 0.1 兼容性

| 目标 | Java | 最低验收 | Runtime 离线 |
|---|---:|---|---|
| Fabric 1.21.1 | 21 | 主目标：生命周期、行为、重启恢复、Runtime E2E | 支持，含外部控制 |
| NeoForge 1.21.1 | 21 | JAR、Dedicated Server、世界、身体/背包/死亡 GameTest | 支持，仅本地控制 |
| Forge 1.20.1 | 17 | JAR、Dedicated Server、世界、身体/背包/死亡 GameTest | 支持，仅本地控制 |

本 Alpha 没有发布 Create、AE2、Mekanism adapter，也不直接引用这些可选 Mod 的类；因此它们缺失时不会影响主 Mod 加载。深度自动化不标记为支持。未经真实启动测试的整合包组合一律记为“未验证”。
