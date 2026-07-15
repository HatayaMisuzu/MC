# CODEX_EXECUTION.md
# Minecraft AI Companion 外部 Brain 接入与产品化执行文件

## 0. 执行方式

本次不用 Goal 模式。

- 不创建或更新 Codex Goal UI。
- 不把任务改写成长周期 Goal 卡片。
- 不只输出计划。
- 直接在当前仓库持续实现、测试、提交并推送。
- 本轮完成后允许停止，因为真实模型验收和真人测试被明确排除。

## 1. 不可偏离的项目方向

MCAC 不再继续建设“内置高层 Agent”。

正确架构：

```text
玩家 / 游戏聊天 / Web
        ↓
MCAC Runtime
- 世界 Observation
- Conversation
- Memory
- Search Gateway
- Tool Gateway
- Task 状态
- Safety
- Persistence
- Verification
        ↓
External Brain Bridge
        ↓
Hermes / DeepSeek V4 / 其他已有 LLM 或 Agent
        ↓
通过 MCAC Tools 深度游玩、聊天、搜索和陪伴
```

MCAC 是：

- Minecraft 身体；
- Tool Server；
- 安全边界；
- 会话、状态与记忆存储；
- 任务执行器；
- 世界结果验证器；
- UI、安装和发布载体。

MCAC 不是：

- 第二套高层 Agent；
- 与 Hermes 竞争决策权的 Planner；
- 自己维护长期目标和人格的自治 Agent；
- 用大量规则模拟 LLM 推理的机器人；
- 自修改生产代码的 Agent。

同一时刻只能有一个外部高层 Brain Controller。

## 2. 当前基线

执行前检查 `main` 最新 HEAD。

已知起点：

```text
c026d70 feat: persist natural chat context
```

已完成并保留：

- Capability 真实交集；
- Observation → Replan 基础；
- 真实 Fabric 容器取物；
- Conversation Event；
- WAITING_FOR_USER；
- 用户回答续接；
- 执行中目标修改；
- 缺货部分交付真实 E2E；
- HTML 对话；
- SQLite / Lease / E2E 竞态修复；
- 最近对话持久化；
- Preference Memory 视图；
- `RESPOND + no steps` 不创建 Minecraft Task。

开始时：

1. 拉取最新 main；
2. 检查工作区；
3. 跑基线；
4. 更新执行状态文档；
5. 不重复已有正确实现；
6. 不继续把 `AgentKernel` 扩展成高层自治 Agent。

## 3. External Brain First 重构

### 3.1 Brain Bridge

实现统一外部 Brain 接口，名称可调整：

```java
public interface ExternalBrainAdapter {
    BrainSession openSession(BrainSessionRequest request);
    BrainTurnResult continueTurn(BrainTurnRequest request);
    void cancel(String sessionId, String reason);
    BrainHealth health();
}
```

至少提供：

```text
HermesBrainAdapter
OpenAICompatibleBrainAdapter
```

解释：

- Hermes 作为外部 Agent；
- DeepSeek V4 等模型通过 OpenAI-compatible 适配器接入；
- 两者都是外部 Brain；
- 不实现 `InternalAgentBrain`；
- 不新增第二套自主 Planner。

### 3.2 MCAC 只托管协议和安全循环

允许 MCAC：

- 发送上下文；
- 暴露 Tool Schema；
- 接收 Tool Call；
- 校验权限、风险和参数；
- 执行 Tool；
- 返回 Observation；
- 在同一 Brain Turn 内继续工具调用；
- 达到预算、等待用户、完成或取消时停止；
- 持久化会话和 Tool Call。

禁止 MCAC：

- 自己生成开放式策略；
- 自己决定搜索词；
- 自己模拟用户偏好；
- 自己生成高层自然回复；
- 与 Hermes 同时维护高层 Plan；
- 用固定 Handler 伪装 Agent 智能。

确定性快速通道只保留：

- pause；
- resume；
- cancel；
- emergency stop；
- permission deny；
- safety reflex。

### 3.3 收敛 AgentKernel

将现有 `AgentKernel` 职责收敛为：

```text
Brain Session Coordinator
Tool Call Dispatcher
Task State Coordinator
Observation Relay
Conversation Relay
Budget / Cancel / Timeout
```

不得继续新增：

- 内置人格；
- 内置 Goal；
- 自主高层策略；
- 独立于外部 Brain 的开放式意图规划；
- 与 Hermes 竞争的 Replan 决策。

已有 Plan/Step/Persistence 可作为：

- 外部 Brain 决策审计快照；
- Tool 执行状态；
- 恢复与去重；
- 用户可见进度。

## 4. 第一项：完整消息入口

支持：

- 普通聊天；
- 提问；
- 共同思考；
- 搜索请求；
- 行动请求；
- 当前问题回答；
- 目标修改；
- 暂停/继续/取消；
- 追问；
- 记忆查询；
- 新任务。

原则：

1. 明确控制命令走确定性快速通道；
2. WAITING_FOR_USER 的稳定 option id 可确定性解析；
3. 其他模糊自然语言交给外部 Brain；
4. 不建立大量关键词分类表；
5. Brain 可以返回：
   - final response；
   - tool calls；
   - ask user；
   - wait；
   - modify active task；
   - cancel；
6. 普通聊天不得创建 Minecraft Task；
7. 行动必须由外部 Brain 明确调用 Tool；
8. 用户回答关联当前 question/task/session；
9. 普通追问不能被误判为任务修改。

测试：

- “今天有点累”只聊天；
- “我们接下来做什么”共同思考；
- “查一下这个模组支持什么版本”搜索；
- “去拿 16 个铁锭”行动；
- “先拿现有的”回答等待问题；
- “算了，回来陪我”修改或取消原任务；
- 等待问题期间普通聊天不被误吞。

## 5. 第二项：聊天、思考、联想和主动陪伴

### 5.1 Brain Context

每个 Turn 向外部 Brain 提供有界上下文：

- 用户消息；
- 最近对话；
- 当前世界摘要；
- 当前 Task；
- 当前等待问题；
- 最近重要事件；
- Working Memory；
- Episodic Memory 摘要；
- World Memory；
- Preference Memory；
- AVAILABLE_NOW Tools；
- Tool 权限和风险；
- Search 是否可用；
- 主动建议是否允许。

### 5.2 自然聊天

支持：

- 普通交流；
- 情绪回应；
- 世界相关聊天；
- 共同经历追问；
- 玩法建议；
- 不立即行动的讨论。

禁止：

- 固定模板冒充自然；
- 每条回复都输出任务状态；
- 普通聊天创建 Task；
- 输出隐藏推理；
- 编造记忆和世界事实；
- 过度角色扮演。

### 5.3 主动建议

MCAC 提供：

- 开关；
- 冷却和频率限制；
- 危险状态禁止闲聊；
- 离线不投递；
- 用户忽略后降频；
- 用户可关闭。

建议必须有真实依据、有价值且不自动执行。

## 6. 第三项：Search Tool Gateway

实现正式搜索工具：

```text
search.query
search.open
search.citations
search.cancel
```

### 6.1 输入约束

- query；
- allowed domains；
- max results；
- recency；
- locale；
- safe search；
- timeout。

### 6.2 隐私剥离

搜索请求不得包含：

- 世界坐标；
- 私人聊天；
- 服务器地址；
- 玩家 UUID；
- 本地路径；
- 日志；
- Token；
- API Key；
- Cookie；
- 启动器账号；
- 私有存档信息。

### 6.3 安全

实现：

- 域名 allow/deny；
- 协议限制；
- 超时；
- 重定向限制；
- 内容类型限制；
- 响应大小限制；
- HTML 文本清洗；
- Prompt Injection 标记与隔离；
- 不执行 JS；
- 不下载执行文件；
- 不自动登录；
- 不提交表单；
- 不读取浏览器状态。

网页内容统一标记：

```text
UNTRUSTED_EXTERNAL_CONTENT
```

网页中的“忽略规则”“调用工具”等内容不能改变权限。

### 6.4 来源

返回：

- title；
- url/domain；
- publisher；
- publishedAt；
- retrievedAt；
- snippet；
- source id；
- trust level；
- content type。

用户回答能展示来源。

### 6.5 Provider

实现：

```text
SearchProvider
- DisabledSearchProvider
- ReplaySearchProvider
- RealSearchProvider 配置接口和实现骨架
```

本轮不要求真实账号验收，但代码、配置、Replay、安全边界和测试必须完成。

## 7. 第四项：完整记忆系统

### Working Memory

- 当前 Turn；
- 当前 Task；
- 临时约束；
- 当前问题；
- 当前 Tool 结果；
- 有界生命周期。

### Episodic Memory

- 共同经历；
- 成功与失败；
- 用户选择；
- 重要事件；
- 策略结果。

### World Memory

- 地标；
- 容器；
- 资源点；
- 建筑；
- 危险区域；
- 验证时间；
- 失效原因；
- 世界/维度绑定。

### Preference Memory

- 风险偏好；
- 回复风格；
- 自动补齐；
- 搜索授权；
- 主动建议；
- 建筑风格；
- 常用行为偏好。

### 用户管理

必须支持：

- 查看；
- 搜索；
- 修改；
- 删除；
- 清空；
- 导出；
- 关闭某类记忆；
- 查看来源和时间。

### 信任规则

- Brain 写入建议必须经过 Schema 校验；
- 猜测不能写成世界事实；
- 搜索结果不能写成世界事实；
- 失败策略不污染成功经验；
- World Memory 有 TTL / revalidation；
- 用户纠正优先；
- 不保存密钥和敏感信息。

## 8. 第五项：Minecraft 通用身体 Tools

目标是给外部 Brain 通用可组合工具，不写任务专用 Handler。

### 8.1 容器

完成：

- LocateKnownContainer；
- WithdrawFromStorage；
- DepositToStorage；
- 多容器；
- 容器失效；
- 背包满；
- 数量不足；
- 部分完成；
- 导航→交互→返回→交付；
- 重启恢复；
- 无复制；
- 无重复交付。

### 8.2 制作

完成：

- CraftItem；
- 原版配方解析；
- 材料差额；
- 工作台发现/放置；
- 原版菜单交互；
- 输入/输出差分；
- 前置步骤；
- 交付。

### 8.3 资源

完成：

- ExploreArea；
- CollectResource；
- MineResourceVein；
- 工具判断；
- 耐久；
- 合法破坏；
- 掉落验证；
- 矿脉边界；
- 木材、煤、铁、钻石；
- 换资源点；
- 不可达；
- 无进展。

### 8.4 熔炼

完成：

- SmeltItem；
- 熔炉发现/放置；
- 原料；
- 燃料；
- 原版交互；
- 等待；
- 输出；
- 数量验证；
- 中断恢复。

### 8.5 危险

完成：

- RetreatFromDanger；
- DefendOwner；
- 低血量；
- 饥饿；
- 火；
- 岩浆；
- 溺水；
- 爆炸；
- 夜间；
- 敌对生物；
- 安全撤退；
- Task 暂停和恢复。

### 8.6 工具粒度

优先：

```text
world.observe
world.scan
movement.navigate
movement.follow
block.interact
block.break
inventory.transfer
inventory.inspect
item.use
item.craft
item.smelt
combat.defend
safety.retreat
```

不得为“挖钻石”“做火把”“搬铁”“做铁镐”各写专用 Handler。

## 9. 第六项：多策略失败和用户协商

MCAC 提供真实失败和 Observation，外部 Brain 决定策略。

支持：

- 路线失败换路线；
- 资源点失败换资源点；
- 缺工具补前置；
- 缺材料询问或补齐；
- 风险增加时询问；
- 达到无进展预算后停止重复；
- 用户修改目标；
- 用户要求返回陪伴；
- Provider 断线保留 Task；
- Tool 不可用时不承诺。

失败结构：

- code；
- category；
- observed facts；
- attempted strategy；
- progress delta；
- retry count；
- risk；
- permissions；
- available alternatives。

Replay E2E：

1. 钻石任务一种方法失败后换策略；
2. 箱子不足后询问；
3. 用户选择部分交付；
4. 用户授权自动补齐；
5. 用户修改数量；
6. 用户取消并要求返回；
7. Tool 不可用时诚实说明；
8. 不提供当前不可执行选项。

## 10. 第七项：统一 Tool Gateway

Tool 类别：

- World；
- Movement；
- Inventory；
- Container；
- Crafting；
- Resource；
- Smelting；
- Safety；
- Memory；
- Search；
- Conversation；
- Task。

每个 Tool 包含：

- name/version；
- description；
- input/output Schema；
- Capability 状态；
- risk；
- permission；
- timeout；
- cancel；
- idempotency；
- recovery；
- audit fields；
- error codes。

### 外部协议

优先实现 MCP Server：

- MCAC 作为 MCP Server；
- Hermes 作为 MCP Client；
- 不暴露 Shell/File/任意网络；
- 只暴露 MCAC Tools；
- Tool 调用绑定 companion/session/lease；
- 支持取消、进度和 Observation stream。

同时保留 OpenAI-compatible tool-calling 适配层，供 DeepSeek V4 等模型接入。

## 11. 第八项：UI 和长期可靠性

### UI

HTML Terminal 完成：

- 自然聊天；
- Tool 调用状态；
- Search 来源；
- 当前活动；
- 等待问题；
- 计划与步骤；
- pause/resume/cancel；
- 目标修改；
- Search 开关；
- 主动建议开关；
- Memory 查看/删除；
- Brain Provider 选择；
- Hermes 状态；
- OpenAI-compatible 配置；
- 权限；
- 隐私；
- 日志；
- 支持包；
- Doctor。

游戏内完成：

- 自然聊天；
- 简短 Tool 进度；
- 问题与回答；
- 取消；
- Search 摘要；
- 不刷屏；
- 不输出内部 JSON。

### 可靠性

完成：

- Runtime 重启恢复；
- Minecraft 重连；
- Hermes 重连；
- Provider 超时；
- Search 失败；
- Tool 取消；
- Task/Plan/Conversation/Memory 一致性；
- 幂等；
- 无复制；
- 无重复交付；
- 无幽灵 Task；
- WAL 与数据库恢复；
- 日志轮换；
- 上下文预算；
- Tool/网络调用预算；
- 线程和端口释放；
- 长稳；
- 性能；
- Tick 不阻塞；
- 路径和扫描预算。

## 12. 发布与产品化工程

本轮必须完成。

### 安装与配置

- Windows 安装器或脚本；
- Fabric 1.21.1 检查；
- Mod/Runtime/Web 部署；
- Hermes endpoint；
- OpenAI-compatible Provider；
- Search Provider；
- Credential 存储；
- Search/Memory/主动建议开关；
- 数据目录；
- 端口；
- 日志；
- 防火墙提示；
- Doctor。

### 升级与 Migration

- DB Schema Migration；
- 配置 Migration；
- 兼容检查；
- 升级前备份；
- 升级失败回退；
- 数据保留；
- 不修改用户世界。

### 卸载与回退

- 停止进程；
- 释放端口；
- 删除 Mod/Runtime；
- 保留或删除用户数据选项；
- 恢复配置；
- 无残留锁与服务。

### 发布包

生成：

- Fabric 1.21.1 Mod；
- Runtime；
- Web Terminal；
- 配置示例；
- 安装/卸载脚本；
- Doctor；
- README；
- 隐私和安全说明；
- 已知限制；
- 版本清单；
- SHA-256；
- SBOM/依赖清单；
- License；
- Changelog；
- Support bundle。

### 干净环境自动验证

在临时干净目录执行：

```text
install
→ configure without real credentials
→ start
→ connect Replay Brain
→ automated smoke
→ stop
→ upgrade
→ rollback
→ uninstall
```

## 13. 本轮排除

不要求：

- 真实 DeepSeek V4 API Key；
- 真实 Hermes 长时间在线验收；
- 真实搜索账号验收；
- 真人手动游玩；
- 真人主观陪伴评价；
- Live Provider 成本测试；
- 最终用户反馈。

但必须完成：

- 接口；
- 配置；
- 文档；
- Replay；
- Fake；
- 自动化测试；
- 安全边界；
- 诊断工具；
- 后续测试入口。

本轮完成状态：

```text
READY_FOR_LIVE_BRAIN_AND_HUMAN_TEST
```

不能写成正式产品或真人测试已通过。

## 14. 测试

### 单元

- External Brain adapters；
- Tool Schema/permission；
- Chat 不创建 Task；
- Brain tool call；
- WAITING answer；
- Goal modification；
- Search sanitization/Prompt Injection；
- Memory；
- World fact invalidation；
- Minecraft Tools；
- Migration；
- Installer；
- Doctor。

### 集成

- Hermes Replay Adapter；
- OpenAI-compatible Replay Adapter；
- Brain → Tool → Observation → Brain；
- Chat → final response；
- Search → citations；
- Search disabled；
- Memory CRUD；
- Runtime restart；
- Hermes reconnect；
- Provider timeout；
- Tool cancel；
- upgrade/rollback。

### Fabric GameTest

- 容器；
- Deposit；
- 制作；
- 采集；
- 熔炼；
- 钻石替代策略；
- 危险；
- 中断；
- 恢复；
- 无复制；
- 无作弊。

### Browser E2E

- Chat；
- Search/citations；
- Brain Provider；
- Hermes status；
- permissions；
- Memory controls；
- Task；
- Questions；
- Goal modification；
- logs；
- Doctor；
- support bundle。

### 全量

- `gradlew check buildPlatforms`
- Runtime/Fabric E2E
- GameTest
- Playwright
- persistence
- migration tests
- installer tests
- package verification
- forbidden API
- independence
- secret scan
- privacy scan
- search policy tests
- release smoke
- long-run
- remote CI

Replay 不能写成 Live。

## 15. 安全与非作弊

禁止：

- 传送、飞行、无敌；
- 生成物品；
- 直接改库存；
- setBlock 生产路径；
- 绕过配方；
- 作弊命令；
- 伪造 Observation/成功；
- 任意 Shell；
- 任意文件；
- 任意 URL；
- Cookie/启动器凭据；
- 上传世界、坐标、聊天、日志、服务器地址；
- 自修改生产代码；
- Hermes 获得开发机权限；
- 网页 Prompt 覆盖系统规则。

MCAC Tool 是唯一游戏行动入口。

## 16. 提交纪律

每个稳定切片：

```text
审计
→ 实现
→ 测试
→ 体验/安全/隐私/性能复盘
→ 修复
→ commit
→ push
→ 核对远端和 CI
→ 下一切片
```

禁止：

- 一次巨大提交；
- 只更新文档；
- 只新增接口；
- 只加 Mock；
- 降低断言；
- 扩大超时掩盖竞态；
- 跳过 CI；
- `[skip ci]`；
- force push；
- 把 LOCAL_PASS 写成 REMOTE_PASS；
- 把 Replay 写成 Live；
- 宣布真人体验通过。

## 17. 完成门槛

只有全部满足，才报告：

```text
READY_FOR_LIVE_BRAIN_AND_HUMAN_TEST
```

同时必须标记：

```text
LIVE_BRAIN_EXTERNAL_VERIFICATION_PENDING
HUMAN_PLAYTEST_PENDING
```

要求：

- Hermes adapter；
- OpenAI-compatible adapter；
- 无内置高层 Agent；
- MCP 或等价标准 Tool Protocol；
- Chat/Think/Search/Action/Answer/Goal modification；
- 四类 Memory；
- Search citations 和安全；
- 容器/Deposit/Craft/Collect/Mine/Smelt；
- 钻石替代策略；
- Retreat/Defend；
- UI；
- Installer/Doctor；
- Migration/Upgrade/Rollback/Uninstall；
- Release package/Support bundle；
- 全量自动化；
- 干净环境 smoke；
- 长稳和性能；
- 远程 CI；
- 发布包 SHA 与远端 SHA 对齐；
- 工作区干净。

## 18. 最终报告

仅报告：

1. 最新远端 SHA；
2. 完成切片；
3. 测试命令和结果；
4. 发布包路径和 SHA；
5. 安装/升级/回退/卸载验证；
6. Hermes/OpenAI-compatible 接入说明；
7. Search/Memory/Privacy 配置；
8. 已知限制；
9. `LIVE_BRAIN_EXTERNAL_VERIFICATION_PENDING`；
10. `HUMAN_PLAYTEST_PENDING`；
11. `READY_FOR_LIVE_BRAIN_AND_HUMAN_TEST`。

不得声称真实模型效果或真人体验已通过。

