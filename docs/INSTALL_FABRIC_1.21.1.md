# 安装：Fabric 1.21.1

1. 安装 Minecraft 1.21.1、Java 21、与 JAR metadata 匹配的 Fabric Loader 和 Fabric API。
2. 推荐直接在 HTML 控制终端的“安装管理”中生成计划并确认；对应受管产物为 `minecraft-ai-companion-fabric-1.21.1-0.3.0.jar`。
3. 启动客户端世界或 Dedicated Server。首次启动会生成安全默认配置。
4. 日志出现 `Minecraft AI Companion ... LOCAL_ONLY` 即表示 Mod 已在 Runtime 离线模式加载。
5. 进入世界后执行 `/companion create <name>` 和 `/companion status`。

服务端和客户端必须使用相同 Minecraft 主版本；协议不兼容会禁用同伴网络功能，但不应使服务器崩溃。
