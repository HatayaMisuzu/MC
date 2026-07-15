# Text Companion implementation status

更新时间：2026-07-15

本文件记录本轮 `AGENTS.md` 执行的真实状态。它不是完整验收报告，也不把部分实现写成 0.4.0 发布完成。

## 已实现并有自动化证据

- 文本输入：NFKC 规范化、坐标/数量提示、控制快速通道、规则与模型混合路由。
- 模型边界：纯文本请求，结构化 `AgentDecision`，Capability 白名单、字段/风险/预算校验；模型不能直接执行代码或宣布世界任务成功。
- 持久化：SQLite `agent_plan`、`agent_step`、`memory_fact`；启动时把未完成执行置为 `PAUSED/RECOVERY_REQUIRED`。
- 执行生命周期：可启动、tick、暂停、恢复、取消、超时并产生结构化错误的 Primitive 控制器。
- 玩家入口：HTML Companion 页自然语言输入，以及 Fabric 1.21.1 所有者专用 `/mcac <文本>`；后者带长度和频率限制。
- 世界快照：生命、饥饿、空气、火/岩浆状态、背包数量和空槽、最近动作证据。
- 已验收的真实能力：`NavigateTo`、`FollowOwner`、`DeliverItem`、`EatAndRecover`。交付通过原版掉落/拾取链并验证双方库存差分；进食通过原版 use-item 路径并同时验证食物消耗与饥饿值变化。
- 安全失败：未实现 Capability 进入 `BLOCKED/CAPABILITY_UNAVAILABLE`，不会调用命令、脚本、传送或直接世界/库存写入来伪造成功。
- Capability 可见性：Planner 只接收 Runtime 注册、Fabric 1.21.1 身体声明、正式实现、连接与当前身体状态交集得到的 `AVAILABLE_NOW`；路线图能力不再作为可执行能力暴露。

## 本轮验证结果

- `gradlew check buildPlatforms`：通过。
- Fabric 1.21.1 GameTest：3/3 通过，包含受阻寻路、生命周期、交付与进食世界差分。
- Runtime/Fabric E2E：通过握手、注册、租约、follow、pause、resume、stop 与安全关闭。
- Web 单元测试：5/5 通过。
- Playwright 浏览器 E2E：1/1 通过。
- `forbiddenApiCheck`、`independenceCheck`、`secretCheck`：通过。

## 尚未达到 AGENTS.md 完成标准

以下首批能力仍缺少正式实现和真实 GameTest，因此 0.4.0 Text Companion Beta 不能据此标记为完成：

- 容器定位、打开、存入、取出与多容器重规划；
- 资源发现、采集、矿脉边界、工具选择与掉落验证；
- 配方解析、工作台制作、熔炼、燃料管理与输出验证；
- 探索、战斗、防御主人、撤退和风险策略；
- 完整的歧义澄清、多轮约束修订、动态重规划与长期任务恢复验收；
- Replay Provider、真实模型小规模验收、性能/长稳测试、完整发布包与最终验收报告；
- Forge/NeoForge Runtime Bridge（当前仍为 `LOCAL_ONLY`）。

在这些项目补齐前，仓库版本保持 `0.3.0`，现有实现应视为 Text Companion Beta 的可验证基础切片，而不是最终发布。
