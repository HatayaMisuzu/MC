# Known limitations

- 安装目标严格限定为 Fabric 1.21.1、NeoForge 1.21.1 和 Forge 1.20.1。其他 Minecraft/Loader 组合可以扫描，但会被诊断和安装器阻止。
- Forge 1.20.1 与 NeoForge 1.21.1 当前提供本地同伴身体和本地命令，尚未实现 Runtime WebSocket Bridge，终端会显示 `LOCAL_ONLY`。
- Fabric 自动依赖下载默认关闭。缺少 Fabric API 时只给出警告，终端不会从未知来源静默下载。
- `play` 只打开目标启动器并等待用户正常登录/点击启动；不会自动操作 PCL2/HMCL UI。
- HMCL Hook 仅在 `selectedMinecraftVersion` 可唯一匹配时写入；证据不足会退回 Guided 模式。
- 本次提供的真实测试实例是 PCL2 2.13.0.1 + Minecraft 26.2 Vanilla，不属于项目支持矩阵，因此只执行了只读发现、诊断和错误安装保护测试，没有向该实例安装 Mod 或启动游戏。
