# Known limitations — 0.3.0

- Forge 1.20.1 与 NeoForge 1.21.1 当前没有 Runtime Bridge，只提供本地 Companion 能力；HTML 终端始终显示 `LOCAL_ONLY`。
- 用户提供的 `F:\wodeshijie\ceshi` 当前是 PCL2 的 Vanilla 非支持版本；真实扫描必须阻止安装且不修改实例。
- 用户提供的 HMCL Forge 1.20.1 实例可扫描，但因上述 Bridge 限制只能进入 `LOCAL_ONLY`。
- 自动化使用 PCL2/HMCL 文件夹夹具覆盖浏览器完整管理流程，并使用 Dedicated Server/GameTest 覆盖真实 Mod 加载、握手与行为；登录第三方启动器账号并进入个人存档仍取决于用户已有的启动器会话，测试不会读取或保存账号凭据。
- OpenAI-compatible Provider 的真实付费端点需要用户自行提供环境变量或 Windows Credential Manager 中的 Key；测试不会包含真实密钥或产生外部费用。
- Companion 移动采用安全的局部移动、卡住检测和有限重规划，不等同于 Baritone 的全局寻路。
