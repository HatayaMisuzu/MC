# Companion Runtime 设置

Runtime 是独立 Java 21 应用。它提供本地 WebSocket、SQLite WAL、控制租约、任务事件、规则解析、CLI 和可选 OpenAI-compatible Provider。Mod 不依赖 Runtime 才能加载。

## 启动

解压 Runtime 分发包后：

```powershell
.\bin\runtime-app.bat --config .\config\runtime.yml
```

默认仅监听 `127.0.0.1:8766`。首次启动会在 `data/` 生成配对 token 文件；不要复制到聊天、日志或源码仓库。

## 无模型规则模式

默认 `provider.mode: rules`，不需要 API key，支持：

- `跟着我`
- `停止`
- `继续`
- `到我这里来`
- `去 x y z`
- `你在哪里`
- `当前状态`
- `取消任务`

## OpenAI-compatible 模式

Provider 配置只保存公开信息：

```yaml
provider:
  mode: openai-compatible
  base_url: https://api.deepseek.com
  api_key_env: MC_COMPANION_API_KEY
  model: deepseek-v4-flash
  timeout_seconds: 60
```

在启动 Runtime 的进程环境中设置 `MC_COMPANION_API_KEY`。不要把 key 写进 YAML。Provider 超时、连接失败或返回无效 JSON 时，Runtime 会拒绝不安全输出并回退规则模式。

## 停止

在 CLI 输入 `quit`，或向进程发送正常终止信号。Runtime 会停止接收命令、落盘未完成事件、关闭 WebSocket/SQLite 和工作线程后退出。
