# Companion Runtime 设置

Runtime 是独立 Java 21 应用。它提供本地 WebSocket、SQLite WAL、控制租约、任务事件、
External Brain Bridge、受限 Tool Gateway、MCP、记忆、搜索和确定性执行基础。Mod 不依赖
Runtime 才能加载。Runtime 不是内置高层 Agent；高层推理和规划属于外部 Brain。

## 启动

解压 Runtime 分发包后：

```powershell
.\bin\runtime-app.bat --config .\config\runtime.yml
```

默认仅监听 `127.0.0.1:8766`。首次启动会在 `data/` 生成配对 token 文件；不要复制到聊天、日志或源码仓库。

Fabric 1.21.1 主目标启用 Runtime 控制时，在服务器关闭状态下把该 token 文件复制为：

```text
<Minecraft 服务端>/config/minecraft-ai-companion/runtime.token
```

然后先启动 Runtime，再启动 Minecraft 服务端。`/companion runtime` 显示 `runtime=ONLINE` 即握手完成。默认地址是 `ws://127.0.0.1:8766`；仅在高级部署中用 JVM 参数 `-Dmccompanion.runtime.url=...` 和 `-Dmccompanion.runtime.tokenFile=...` 覆盖。NeoForge/Forge Alpha 产物目前提供完整离线身体与本地命令，但不启用外部 Runtime 控制。

Runtime CLI 支持 `list`，以及 `follow/status/return/goto/stop/pause/resume <companion-id>`。单同伴测试或演示可用 `first` 代替 UUID，例如 `follow first`。

## 兼容规则模式

旧的 `provider.mode: rules` 不需要 API key，只提供明确控制命令的兼容快速通道：

- `跟着我`
- `停止`
- `继续`
- `到我这里来`
- `去 x y z`
- `你在哪里`
- `当前状态`
- `取消任务`

它不是高层 Planner，也不是最终 External Brain 路径。

## External Brain / OpenAI-compatible 模式

Provider 配置只保存公开信息：

```yaml
provider:
  mode: openai-compatible
  base_url: https://api.deepseek.com
  api_key_env: MC_COMPANION_API_KEY
  model: deepseek-v4-flash
  timeout_seconds: 60
```

在启动 Runtime 的进程环境中设置 `MC_COMPANION_API_KEY`。不要把 key 写进 YAML。Provider
超时、连接失败或返回无效 Tool Call 时，Runtime 会安全失败并保留可恢复状态；不得由
内部 Planner 接管开放式目标。

运行时 Brain 无 Shell、Git、Gradle、任意文件、生产源码或任意网络权限。联网搜索必须
通过单独授权的 Search Gateway。

## 停止

在 CLI 输入 `quit`，或向进程发送正常终止信号。Runtime 会停止接收命令、落盘未完成事件、关闭 WebSocket/SQLite 和工作线程后退出。
