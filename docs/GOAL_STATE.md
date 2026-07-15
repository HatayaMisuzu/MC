# MCAC Goal state

更新时间：2026-07-15

## 当前长期 Goal

持续完善 Minecraft AI Companion，直到它成为可在隔离 Fabric 1.21.1 实例中进行真人测试的纯文本 LLM AI 伙伴候选版本。它必须会理解、判断、行动、观察、修正和诚实反馈，而不是固定宏、任务专用脚本集合或只会输出计划的聊天层。

状态：`ACTIVE`。只有 `F:\AGENTS.md` 第 14 节全部满足时才允许结束。

## 基线

- 本地与远端 `main`：`b7d14ea0d3d24cc4637520012ba51c850e9157dc`。
- 工作区：第一轮开始时干净。
- 最近远端检查：PR fast checks、Windows terminal validation、Minecraft heavy validation 均为 `REMOTE_PASS`。
- 当前有真实世界证据的能力：`NavigateTo`、`FollowOwner`、`DeliverItem`、`EatAndRecover`。

## 第一轮审计发现

- Planner 上下文直接使用 `CapabilityRegistry.standard().names()`，暴露了尚无 Fabric 正式实现的路线图能力。
- Fabric hello 声明了未作为可调用 Skill 实现的 `RetreatFromDanger`，却没有声明已实现的 `DeliverItem` 与 `EatAndRecover`。
- `AgentKernel` 会保存终态 Observation，但失败/阻塞后只停在终态，不会调用 Planner 形成计划修订。
- 测试根目录现有实例为 PCL2 Vanilla 26.2 / Java 25，不兼容 Fabric 1.21.1，必须保留并另建隔离实例。

## 当前轮目标

1. 已建立 Runtime 注册、Fabric 身体声明、正式实现、连接和当前世界状态的 Capability 交集。
2. Planner 现在只接收 `AVAILABLE_NOW`；其他能力保留 `DECLARED`、`IMPLEMENTED`、`CONNECTED`、`TEMPORARILY_BLOCKED` 或 `UNSUPPORTED` 及原因。
3. Fabric 不再声明不可直接调用的 `RetreatFromDanger`，并补充声明已实现的 `DeliverItem` 与 `EatAndRecover`。
4. 下一切片实现 Observation → Replan 的持久计划修订、预算与循环检测。

## 第一切片测试证据

- `gradlew :runtime:runtime-app:test`：`LOCAL_PASS`。
- `gradlew build-fabric`：`LOCAL_PASS`。
- `gradlew runtimeFabricE2E`：`LOCAL_PASS`，覆盖真实握手、注册、租约和行为控制。
- `gradlew check buildPlatforms`：`LOCAL_PASS`，包括单元、Web、非作弊、独立性、秘密扫描和三 Loader 构建。
- 远端状态：等待本切片提交后验证，不能写为 `REMOTE_PASS`。

## 出口门槛

当前不是 `READY_FOR_HUMAN_TEST`。容器闭环、制作、资源/熔炼、重规划、目标修改、隔离实例冒烟、长稳、发布资产与真人测试材料仍未完成。
