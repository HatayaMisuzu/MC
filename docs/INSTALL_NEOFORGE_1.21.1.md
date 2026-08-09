# 安装：NeoForge 1.21.1

1. 安装 Minecraft 1.21.1、Java 21，以及 JAR metadata 声明范围内的 NeoForge 21.1.x。
2. 推荐直接在 HTML 控制终端的“安装管理”中生成计划并确认；对应受管产物为 `minecraft-ai-companion-neoforge-1.21.1-0.3.1.jar`。
3. 启动世界或 Dedicated Server，确认初始化日志与 `LOCAL_ONLY` 状态。
4. 进入世界后执行 `/companion create <name>` 和 `/companion status`。

NeoForge 仅提供 `LOCAL_ONLY / OFFLINE_LOCAL_CONTROL`。`/companion runtime` 只报告本地伙伴
Registry 状态（其中 `runtime=OFFLINE`），用于诊断本地身体是否就绪；它不会连接 Runtime 或
External Brain Bridge。智能文本入口 `/mcac <request>` 在此模式下不可用。

Runtime、模型 API 和任何可选兼容 Mod 都不是启动硬依赖。
