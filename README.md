# Minecraft AI Companion Alpha 0.1

Minecraft AI Companion 是一个不依赖 Numen 或 Baritone 的服务端同伴项目。每个同伴拥有独立、稳定的 UUID 和真实 `ServerPlayer` 身体；本地 Mod 负责实时行为、安全与正常玩家动作，外部 JVM Runtime 负责任务、租约、持久事件和可选模型解析。

## 支持矩阵

| Minecraft | Loader | Java | 产物目录 |
|---|---|---:|---|
| 1.21.1 | Fabric | 21 | `minecraft/fabric-1.21.1/build/libs` |
| 1.21.1 | NeoForge | 21 | `minecraft/neoforge-1.21.1/build/libs` |
| 1.20.1 | Forge | 17 | `minecraft/forge-1.20.1/build/libs` |

三个目标各自使用隔离的官方构建管线，避免 Loader、映射和 Java 版本相互污染。Runtime 未运行、未配置 API Key 或未安装可选兼容 Mod 时，Mod 仍应以 `LOCAL_ONLY` 模式加载并提供本地命令。

## 最短构建流程（Windows）

```powershell
cd D:\工作台\MC
.\gradlew.bat clean build
.\gradlew.bat test
.\gradlew.bat launchTest
.\gradlew.bat packageAlpha
```

Gradle toolchain 会为 1.21.1 和 Runtime 选择 Java 21，为 Forge 1.20.1 选择 Java 17。无需反复修改系统 `JAVA_HOME`。

## 最短体验流程

1. 按目标版本安装对应 Loader 和本项目 JAR。
2. 启动世界或 Dedicated Server。
3. 执行 `/companion create Numenless`。
4. 使用 `/companion follow`、`/companion goto <x> <y> <z>` 和 `/companion stop`。
5. 可选：按 [Runtime 设置](docs/RUNTIME_SETUP.md) 启动外部 Runtime，再用 CLI 发送中文规则命令。

详细说明见 [命令](docs/COMMANDS.md)、[架构](docs/ARCHITECTURE.md)、[兼容性](docs/COMPATIBILITY.md) 与 [排错](docs/TROUBLESHOOTING.md)。

## 安全边界

- 正常任务不会用传送、命令执行、直接方块写入或直接背包/容器注入完成目标。
- 高层行为只能经 `PlayerActionGateway` 产生真实玩家路径动作。
- owner UUID 和 control lease epoch 同时约束控制权限。
- 配对 token 与 Provider API key 不进入日志；API key 只从环境变量读取。
- `tools/forbidden-api-check.ps1` 和 `tools/independence-check.ps1` 是发布门禁。

## 数据位置

- Minecraft 原生 playerdata：背包、装备、生命、饥饿、经验、位置和效果。
- 世界侧项目数据：同伴 ID、owner、显示名、策略、schema version 与恢复元数据。
- Runtime SQLite：session、companion、lease、task、task event、behavior run 与 action evidence，使用 WAL。

不要把测试世界、token、API key、账号文件或交付 ZIP 放入源码仓库。

