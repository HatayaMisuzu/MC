# Known limitations — 0.2-alpha

- PCL2 2.13.0.1 + Minecraft 26.2 Vanilla 已完成真实扫描与无修改阻止验证，但 26.2 不在项目支持矩阵，不能安装 Companion。
- 当前没有可登录的 PCL2/HMCL Fabric 1.21.1 图形客户端测试副本，因此 GUI 进入世界、`play` 面板和终端交互 smoke 标记为 `MANUAL_PENDING`；Dedicated Server、GameTest 和 Runtime E2E 已自动通过。
- HMCL 3.10.3 的解析和 Hook 使用脱敏 fixture 自动测试；本轮没有 HMCL EXE 测试副本，因此 HMCL GUI 打开为 `MANUAL_PENDING`。
- Forge 1.20.1 与 NeoForge 1.21.1 没有 Runtime Bridge，只提供 `LOCAL_ONLY`；不会伪装为 FULL 或握手通过。
- Provider rules 模式已验证。OpenAI-compatible 网络测试需要用户提供环境变量中的 API Key 和可访问端点，本轮状态为 `MANUAL_PENDING`。
- `test smoke` 会验证静态状态、Runtime 健康、真实握手和同伴注册；行为命令链由真实 `runtimeFabricE2E` 覆盖。图形客户端中的终端触发仍需人工检查点。
