# MCAC 用户指南（简体中文）

适用版本：0.3.1（自动化基线已冻结并发布；Live Brain 与真人试玩仍待外部验证）

## 开始使用

1. 完整解压 `mcac-release.zip`，不要只复制 `mcac.exe`。
2. 启动方式任选其一：在 PowerShell 中运行 `.\mcac.exe web --open-browser`，明确允许本次启动打开默认浏览器；或双击 `启动终端.cmd`（等价于 `mcac.exe web --open-browser`）。
3. 在“启动器与实例”中选择自动发现的 PCL2 或 HMCL 实例。
4. 先运行“诊断”，再进入“安装管理”审阅并确认安装计划。
5. 在“运行服务”中启动 Runtime；页面应显示真实 PID、端口和认证健康状态。
6. 需要连接游戏时，按“游戏启动”页的预检和启动计划操作。

直接双击或再次启动 `mcac.exe`（或运行 `mcac.cmd` / `mcac.ps1`）只会启动或复用本机终端，
不会隐式打开默认浏览器。只有 `--open-browser` 或 `MCAC_OPEN_BROWSER=true` 会请求 Windows
打开浏览器；`MCAC_NO_BROWSER=true` 可为开发、测试和无人值守流程强制禁用该行为（对
`启动终端.cmd` 同样生效）。服务只监听 `127.0.0.1`，不要把启动 URL、会话 Cookie、CSRF
Token、启动器凭据或 API Key 分享给他人。

## 支持范围

- Fabric 1.21.1 / Java 21：Full Runtime Bridge。
- Forge 1.20.1 / Java 17：Full Runtime Bridge。
- NeoForge 1.21.1 / Java 21：仅 `LOCAL_ONLY`，不会伪装成完整连接。

其他版本可能被扫描，但不代表可安装或可运行。兼容层页面中的 Fixture 证据也不代表
任意第三方 Mod 已兼容。

## 更新、修复与卸载

所有写操作都先显示计划，再要求确认。更新前会创建回滚点；“验证并修复”只处理 MCAC
管理的文件。卸载时：

- “卸载并保留数据”移除托管程序，保留 MCAC Profile；
- “卸载并删除 MCAC 数据”只删除当前实例的 MCAC 数据；
- 世界、账号和无关 Mod 不属于删除范围。

## 外部模型

Hermes、DeepSeek 或其他外部 LLM/Agent 是高层决策者。凭据只能通过环境变量或 Windows
Credential Manager 配置，不要写进仓库、聊天、截图或支持包。Live Hermes 与真人试玩
尚未作为本产品化候选的自动化证据。

## 故障处理

先运行“诊断”，记录技术错误码，再从“日志与支持”生成脱敏支持包。不要手工删除实例
文件来“修复”安装。更多信息见 [故障排除](../TROUBLESHOOTING.md) 和
[已知限制](../../KNOWN_LIMITATIONS.md)。
