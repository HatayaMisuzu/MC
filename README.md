# Minecraft AI Companion 0.3.0

Minecraft AI Companion 是面向 PCL2/HMCL 的 Windows 本地控制中心。普通用户只需双击 `mcac.exe`：程序会在随机 Loopback 端口启动后端并自动打开中文 HTML 图形界面；不需要安装 Java、Node.js 或 Gradle，也不需要编辑 JSON/YAML。

## 使用发布包

1. 解压 `mcac-release.zip`，不要只单独复制 `mcac.exe`。
2. 双击第一层的 `mcac.exe`。
3. 在浏览器页面中选择自动扫描到的 PCL2/HMCL 实例。
4. 依次完成 Doctor、安装、Runtime、游戏启动、Companion 控制和冒烟测试。

再次双击 `mcac.exe` 会复用已运行的本地后端并重新打开控制页面。服务只监听 `127.0.0.1` 的动态端口；所有 API 需要随机会话 Cookie 与独立 CSRF Token，拒绝跨站、局域网和公网请求。

发布目录结构：

```text
mcac-release/
├─ mcac.exe
├─ mcac-cli.exe
├─ app/
├─ runtime/
├─ web/
├─ artifacts/
├─ legal/
├─ README.txt
└─ SHA256SUMS.txt
```

## HTML 控制终端

页面内可完成：

- 总览、PCL2/HMCL 与实例扫描、兼容性判断；
- 安装计划、安装、更新、修复、回滚和安全卸载；
- Doctor → Runtime → 启动器 → Minecraft → Mod 握手生命周期；
- Companion 的 status/follow/come/goto/pause/resume/stop；
- STATUS → FOLLOW → PAUSE → RESUME → STOP 行为冒烟测试；
- Runtime 多 Profile、日志、Token 轮换和认证健康检查；
- rules 或 OpenAI-compatible Provider 配置；
- 动态 Doctor、一键修复、实时日志和脱敏支持包；
- 深色/浅色模式、安全状态、版本、隐私说明和停止后台服务。

所有写操作都经过“生成计划 → 用户确认 → 执行 → 结构化进度 → 验证 → 失败回滚”。前端不直接读取 SQLite、Token 或 Minecraft 文件。

## 支持矩阵

| Minecraft | Loader | Java | 模式 |
|---|---|---:|---|
| 1.21.1 | Fabric | 21 | FULL Runtime Bridge |
| 1.21.1 | NeoForge | 21 | LOCAL_ONLY |
| 1.20.1 | Forge | 17 | LOCAL_ONLY |

Forge/NeoForge 当前没有 Runtime Bridge，界面会明确显示 `LOCAL_ONLY`，不会伪装成完整握手成功。其他版本可扫描和诊断，但安装会被阻止且不会修改实例。

Provider API Key 只能来自环境变量或 Windows Credential Manager；普通配置文件只保存环境变量名称，不保存 Key 值。

## 开发与自动化

CLI 仅为测试和自动化保留，使用 `mcac-cli.exe` 或带参数调用 `mcac.cmd`。普通用户不需要命令行。

```powershell
.\gradlew.bat clean check buildPlatforms
.\gradlew.bat runtimeFabricE2E persistenceRestartTest
.\gradlew.bat verifyTerminalPackage htmlTerminalStartTest
cd terminal\web-ui
npm run e2e
```

产物位于：

```text
build/distributions/mcac-release/
build/distributions/mcac-release.zip
```

验收证据见 [FINAL_REPORT.md](FINAL_REPORT.md)，仍需外部环境的项目见 [KNOWN_LIMITATIONS.md](KNOWN_LIMITATIONS.md)。
