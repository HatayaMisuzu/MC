# MCAC Goal state

更新时间：2026-07-15

## 当前长期 Goal

持续将 Minecraft AI Companion 完善为可在隔离 Fabric 1.21.1 实例中进行真人测试的纯文本陪伴型 AI 助手候选版本。它必须理解真实目标，结合世界、任务、对话、记忆和偏好判断何时行动、换方法、补前置、给建议或等待用户，并把回答续接到原任务；不得作弊、伪成功或擅自扩大目标。

状态：`ACTIVE`。权威文件为仓库根 `AGENTS.md` 与 `CODEX_GOAL.md`；只有新版 `AGENTS.md` 第 18 节全部满足时才允许结束。

## 基线

- 本地与远端 `main`：`877e790954a99a962cf951d901c85ae5f984cccd`。
- 新执行包基线已核验：`29014d4` 动态 Capability、`73c4f85` Observation→Replan、`877e790` 真实容器取物均存在，不重复实现。
- 当前工作区包含新版根 Goal 文件，以及被中断的目标修改排队草稿；草稿须按 P0 的 Conversation/WAITING 语义整合后才可提交。
- 当前有真实世界证据的能力：`NavigateTo`、`FollowOwner`、`DeliverItem`、`EatAndRecover`、`WithdrawFromStorage`。
- 当前最高优先级：失败分类、Conversation Event、持久 `WAITING_FOR_USER`、回答关联与原计划修订、目标修改。

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

## Companion Conversation / WAITING_FOR_USER 切片（2026-07-15）

- 新增失败分类信任边界：能从 Fabric `BLOCKED` Observation 的 snapshot 还原具体失败码，并区分缺货、能力缺失、路径失败、前置不足、安全阻断、外部服务等类别；“不够就告诉我”和“自动补齐”具有不同自主边界。
- Conversation Event、等待问题、稳定 option id、回答与游戏投递状态均持久化；Runtime 重启后仍可恢复等待，离线消息在 Companion 重连后补投。
- 后台 Replan 可进入持久 `WAITING_FOR_USER`，问题同时出现在游戏内和 HTML；HTML 选项与游戏内自然语言回答都通过同一消息分类入口关联原 question/plan。
- 用户选择部分交付时保留原 `planId`，只修订未完成步骤的数量；6/16 场景变为“取 6 → 返回/交付 6”，不会创建竞争计划或再次调用 Provider。
- 用户明确修改目标时，旧行为先取消、旧未执行步骤明确标为 superseded，再激活同一 plan 的新语义修订；不会把目标修改误吞为缺货回答。
- Fabric 缺货 Observation 新增结构化 `failureCode/item/requested/available`，并在 STARTED 时登记观测状态，避免快速暂停发生在首次状态轮询前而漏发 BLOCKED。
- 当前本地证据：Runtime 全量 75 项、terminal 测试、Web 7 项、Fabric 编译、隔离 Fabric 1.21.1 GameTest 4/4，以及 Runtime↔Fabric 缺货对话纵向 E2E 通过；Playwright 浏览器 E2E 仍待后续验证。
- Goal 保持 `ACTIVE`；该切片不是 `READY_FOR_HUMAN_TEST`，制作、采集、熔炼、钻石替代策略、隔离真人实例和长稳等门槛仍未完成。

### 缺货对话真实纵向证据

- `runtimeFabricE2E` 使用明确标注的本地 Replay Provider（不记作 Live）在隔离 Fabric GameTest 世界生成“导航到箱子→取 16→返回→交付”的计划。
- 世界中的真实箱子只有 6 个铁锭；第一次取物保持零库存变化并上报 `requested=16/available=6`，Runtime 将问题持久化并通过 Conversation Event 投递到游戏。
- 回答 `deliver_partial` 后保持同一 `planId`；旧 BLOCKED task 先经真实取消终态，再激活“取 6→返回→交付 6”，避免 `TASK_ALREADY_ACTIVE` 竞态。
- 真实取物进入主背包后，DeliverItem 通过原版 `InventoryMenu` PICKUP 交互整理到快捷栏，再通过原版丢出/拾取交付；不直接编辑库存。
- GameTest 最终断言：主人铁锭 `+6`、箱子 `0`、伙伴铁锭 `0`，从而同时证明部分交付、无重复取物和无复制。
- E2E 证据落盘到 `build/e2e-runtime/evidence/shortage-conversation.json` 及配套 Runtime/Fabric/Replay 日志；Replay 明确不是 Live Provider。

## Observation → Replan 切片（2026-07-15）

- `agent_plan` 现在持久化语义修订号、模型调用预算、无进展计数与计划指纹；`agent_plan_revision` 保存每次触发 Observation、失败码、决定和结果。
- `AgentKernel` 将 Fabric 任务的 `FAILED/BLOCKED` 终态连同真实 Observation 转成计划 `BLOCKED`，并在专用可取消执行器中调用 Provider 重规划，不阻塞协议回调线程。
- 每个计划最多预占 3 次重规划调用；预占在外部调用前原子持久化。相同计划指纹被拒绝为 `REPLAN_LOOP_DETECTED`，旧的未执行步骤会被明确标成 `SUPERSEDED_BY_REPLAN`。
- Replan 只接受 `REPLAN/ASK_CLARIFICATION/REPORT_BLOCKED/PAUSE/CANCEL`，不能把失败 Observation 偷换成无关的新任务，也不能把失败写成成功。
- 本切片本地证据：仓储状态机测试、Planner Replan 测试、Kernel→Provider 异步集成测试，以及 `gradlew check buildPlatforms runtimeFabricE2E` 均为 `LOCAL_PASS`。
- Goal 仍为 `ACTIVE`；下一切片是已知容器取物→返回→交付闭环及不足、不可达、目标修改时重规划，尚未达到 `READY_FOR_HUMAN_TEST`。

## 已知容器与真实取物切片（2026-07-15）

- Fabric 身体新增正式 `WithdrawFromStorage`：必须给出维度与方块坐标、必须在真实交互距离内、必须由假玩家打开原版容器菜单。
- 物品通过原版菜单 `PICKUP`/右键放置交互逐个精确转移，不直接编辑容器或身体库存；成功同时验证容器减少量和身体库存增加量。
- 不足、容器缺失、距离不可达、世界变化、库存已满和菜单事务失败均安全暂停并返回结构化失败码，不会伪报成功。
- Fabric 只上报假玩家视线实际命中的附近容器类型与坐标，不读取箱内物品；Runtime 将这些坐标持久化为 verified world memory，供后续 Planner 的 `knownContainers` 使用。
- 本切片证据：Runtime 单元测试、Fabric 编译、4 项真实 Fabric GameTest、Runtime↔Fabric E2E 均为 `LOCAL_PASS`。
- 仍未完成：目标修改的排队重规划，以及端到端“导航到已知容器→取物→返回→交付”计划级验证；Goal 保持 `ACTIVE`。
