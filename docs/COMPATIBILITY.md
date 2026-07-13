# Alpha 0.1 兼容性

| 目标 | Java | 最低验收 | Runtime 离线 |
|---|---:|---|---|
| Fabric 1.21.1 | 21 | 主目标：生命周期、行为、重启恢复、Runtime E2E | 支持，含外部控制 |
| NeoForge 1.21.1 | 21 | JAR、Dedicated Server、世界、身体/背包/死亡 GameTest | 支持，仅本地控制 |
| Forge 1.20.1 | 17 | JAR、Dedicated Server、世界、身体/背包/死亡 GameTest | 支持，仅本地控制 |

本 Alpha 没有发布 Create、AE2、Mekanism adapter，也不直接引用这些可选 Mod 的类；因此它们缺失时不会影响主 Mod 加载。深度自动化不标记为支持。未经真实启动测试的整合包组合一律记为“未验证”。

## 已实际启动的生产 JAR 组合

| 环境 | 结果 | 验证内容 |
|---|---|---|
| 全新 Fabric Server 1.21.1 + Fabric API 0.116.13 | PASS | Loader 安装、生产 JAR 加载、世界 Ready、保存、正常停止 |
| 上述环境 + Lithium（Modrinth 返回的最新 1.21.1 Fabric release） | PASS | 两个 Mod 同时加载、世界 Ready、保存、正常停止 |

这两项使用 `tools/fabric-clean-install.ps1` 创建全新实例，而非 Loom 开发类路径。GUI 玩家客户端需要微软账号和人工视觉操作，本自动验收环境未执行，因此不标记为已验证。
