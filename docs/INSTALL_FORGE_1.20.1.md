# 安装：Forge 1.20.1

1. 安装 Minecraft 1.20.1、Java 17，以及 JAR metadata 声明范围内的 Forge 47.x。
2. 推荐直接在 HTML 控制终端的“安装管理”中生成计划并确认；对应受管产物为 `minecraft-ai-companion-forge-1.20.1-0.3.0.jar`。
3. 启动世界或 Dedicated Server，确认 Mod 初始化且状态为 `LOCAL_ONLY`（Runtime 未启动时）。
4. 进入世界后执行 `/companion create <name>` 和 `/companion status`。

不要用 Java 8 启动该目标。Runtime 可以使用单独的 Java 21，不要求 Minecraft 与 Runtime 共用进程。
