# AGENTS.md — Minecraft AI Companion 产品化持续 Goal 执行规则

## 0. 产品身份

本项目不是自然语言遥控器，也不是只会拆解命令的执行机器。

目标是一个真正的 Minecraft AI 陪伴助手。它必须同时具备：

- 自然聊天；
- 思考、判断与联想；
- 结合世界、任务、记忆和用户偏好；
- 自主行动；
- 失败后重新思考；
- 必要时与用户商量；
- 在用户允许时联网搜索和使用外部工具；
- 给出来源、建议和不确定性；
- 安全、诚实、可中断、可恢复；
- 长期陪伴，而不是只在收到命令时工作。

一句话目标：

> 它要像一个会聊天、会思考、会联想、会查资料、会行动、会承认困难并继续陪玩家玩的伙伴，而不是披着 LLM 外壳的脚本执行器。

适用于 `HatayaMisuzu/MC` 全仓库。

## 1. 当前远端基线

启动后先检查最新远端，不重复已完成切片。

当前已知 HEAD：

- `d82e308 彻底消除 Runtime SQLite 与 E2E 时序竞态`

已完成的重要切片：

- `29014d4`：Capability 与连接身体真实能力交集；
- `73c4f85`：Observation 驱动持久 Replan、预算和循环检测；
- `877e790`：真实 Fabric 容器取物；
- `493d884`：Conversation Event、WAITING_FOR_USER、回答续接；
- `08b4344`：缺货对话与部分交付真实 E2E；
- `0c1d5c6`：执行中目标修改；
- `9b02d70`、`d82e308`：租约、SQLite 和 E2E 竞态修复。

当前真正实现的主要身体能力：

- `NavigateTo`
- `FollowOwner`
- `DeliverItem`
- `EatAndRecover`
- `WithdrawFromStorage`

当前状态仍是 `ACTIVE`，不是 `READY_FOR_HUMAN_PRODUCT_TEST`。

## 2. 长期 Goal

持续开发、测试、复盘、修复和优化，直到项目成为：

> 可安装、可升级、可回退、可长期运行、可进行真人测试并具备产品级质量的纯文本 Minecraft AI 陪伴助手。

必须在真实 Fabric 1.21.1 实例中证明：

1. 会自然对话；
2. 会理解复杂目标；
3. 会结合世界、对话、记忆和偏好思考；
4. 会在失败后换方法或和用户商量；
5. 会使用正式 Minecraft Skill 完成基础生存任务；
6. 会在获得授权时使用联网搜索和外部工具；
7. 会区分事实、推测、记忆和搜索结果；
8. 不作弊、不伪完成、不擅自扩大权限；
9. 能稳定长期运行；
10. 有完整安装、发布、日志、支持和回退体验。

如果 Codex 支持 Goal，将 `CODEX_GOAL.md` 设为长期 Goal 并保持激活。

如果没有 Goal UI，则持续使用：

- `CODEX_GOAL.md`
- `docs/GOAL_STATE.md`

作为持久 Goal。

## 3. 优先级

1. 用户信任、陪伴感和自然体验；
2. 思考和判断质量；
3. 真实世界结果；
4. 安全、生存合法性和隐私；
5. 架构质量和可维护性；
6. 测试、性能和真实证据；
7. 功能数、提交数、版本号和形式完成。

不得为第七项牺牲前六项。

## 4. LLM 原生能力不能被阉割

LLM 不能只用于：

- 意图分类；
- 输出固定 JSON；
- 选择 Handler；
- 把用户话语改写成命令；
- 失败时生成模板回复。

必须正确利用：

- 自然对话；
- 上下文理解；
- 目标澄清；
- 多方案比较；
- 关联推理；
- 基于记忆的连续交流；
- 解释和建议；
- 根据 Observation 重新规划；
- 判断是否需要搜索；
- 整合搜索结果；
- 表达不确定性；
- 生成自然回复。

正确分工：

### LLM 负责

- 理解真正意图；
- 普通聊天；
- 分析目标、约束和偏好；
- 联想相关世界信息；
- 短期规划；
- 失败分析；
- 判断继续、询问、建议、搜索或停止；
- 整合记忆和搜索结果；
- 自然回复。

### 正式代码负责

- Primitive；
- Skill；
- 世界快照；
- 权限；
- 安全；
- Tool 调用；
- Search 请求；
- Schema 校验；
- Task/Plan 状态；
- 持久化；
- 完成验证；
- 性能预算；
- 取消、恢复和审计。

禁止为了稳定而把所有语言理解都退化成正则和专用 Handler。

## 5. 交互模式

### Chat Mode

普通聊天、分享、问问题或寻求建议。

例：

- “今天玩得有点累。”
- “你觉得我们接下来做什么？”
- “你还记得昨天建的家吗？”
- “我想换一种基地风格。”

不应自动创建行动任务。

### Thinking Mode

用户提出开放问题，需要结合世界、资源、风险、记忆和偏好思考。

例：

- “这里适合建基地吗？”
- “想找钻石，但别太冒险。”
- “现在最值得做什么？”

输出建议，不一定行动。

### Action Mode

用户明确要求执行。

需要：

- 目标理解；
- 权限和风险；
- Skill 计划；
- Observation；
- Replan；
- 用户协商；
- 完成验证。

### Search Mode

问题涉及最新版本、模组、服务器规则、公开文档、玩法资料或用户明确要求搜索。

必须调用 Search Tool，不凭模型记忆猜最新信息。

## 6. 联网搜索与工具

### 6.1 何时搜索

允许：

- 用户明确要求查找；
- 涉及最新版本、模组、更新、公开服务器规则、文档或实时信息；
- LLM 明确知道内部知识不足且搜索会提升答案；
- 用户已授权此类自动搜索。

不搜索：

- 纯聊天；
- 本地世界事实；
- 当前游戏状态可直接验证的内容；
- 会泄漏私人世界、坐标、聊天、服务器地址、日志或凭据；
- 用户明确禁止联网。

### 6.2 Search Tool Gateway

实现正式 Search Tool 或 Knowledge Tool。

要求：

- 所有网络请求从 Runtime Tool Gateway 发出；
- 不让模型直接执行 Shell、浏览器脚本或任意 URL；
- 域名策略；
- 超时；
- 结果大小限制；
- 内容清洗；
- Prompt Injection 防护；
- 下载类型限制；
- 不执行网页代码；
- 不自动登录；
- 不读取 Cookie；
- 不上传世界、坐标、聊天、服务器地址、日志或凭据；
- 有调用审计和来源；
- 用户可关闭；
- 管理员可禁用。

### 6.3 搜索结果

结构化返回：

- `query`
- `title`
- `source`
- `publishedAt`
- `retrievedAt`
- `snippet`
- `relevance`
- `trustLevel`
- `contentType`

LLM 回答时必须区分：

- 搜索事实；
- 推断；
- 用户建议；
- 游戏内事实；
- 伙伴记忆。

外部最新信息应附简短来源。

### 6.4 搜索结果不能直接变成行动

搜索只提供知识。

仍需经过：

- 用户权限；
- Capability；
- 风险；
- 世界状态；
- Task Validator；

才能转成 Minecraft 行动。

## 7. 联想和主动建议

伙伴可以进行有限、情境相关的联想。

例：

用户说：

> 我们有点缺铁。

伙伴可以结合：

- 当前库存；
- 已知容器；
- 已知矿洞；
- 工具；
- 食物和火把；
- 最近经历；
- 时间和危险；
- 用户偏好；

给出建议。

但不得：

- 无授权行动；
- 编造世界事实；
- 频繁打断；
- 无限主动发言；
- 过度角色扮演；
- 伪造记忆。

主动建议必须有价值、有频率限制、可关闭。

## 8. 记忆系统

实现并区分：

### Working Memory

当前对话、任务、约束和 Observation。

### Episodic Memory

共同经历、成功、失败、用户选择和重要事件。

### World Memory

地标、容器、资源点、建筑、危险区、验证时间和失效状态。

### Preference Memory

回复风格、风险偏好、自动补齐、搜索授权、主动建议、建筑风格等。

要求：

- 只保存有用信息；
- 有来源和时间；
- 可失效；
- 可查看；
- 可修改；
- 可删除；
- 不把猜测写成事实；
- 不把搜索结果写成世界事实；
- 不保存密钥。

## 9. 失败与陪伴决策

保留并完善：

- `ALTERNATIVE_STRATEGY_AVAILABLE`
- `PREREQUISITE_MISSING`
- `RESOURCE_SHORTAGE`
- `USER_CHOICE_REQUIRED`
- `AUTHORIZATION_REQUIRED`
- `INFORMATION_MISSING`
- `UNSUPPORTED_CAPABILITY`
- `SAFETY_BLOCKED`
- `EXTERNAL_SERVICE_UNAVAILABLE`
- `NO_PROGRESS`

原则：

- 小的低风险变化自主完成；
- 明显增加时间、风险或资源时先告知；
- 改变目标、使用稀有资源、破坏建筑、进入高风险区或使用他人资源必须等待；
- 不机械重试；
- 不机械询问；
- 不机械报错；
- 不提供当前身体无法执行的选项。

Conversation options 必须来自：

```text
AVAILABLE_NOW Capability
+ 可正式解锁的前置步骤
+ 当前权限
+ 当前世界状态
```

## 10. 消息路由

必须区分：

- `CHAT`
- `QUESTION`
- `THINK_WITH_USER`
- `SEARCH_REQUEST`
- `ACTION_REQUEST`
- `WAITING_ANSWER`
- `GOAL_MODIFICATION`
- `CONTROL`
- `NEW_TASK`
- `FOLLOW_UP`
- `MEMORY_QUERY`

规则和关键词只能作为高置信辅助。

模糊自然语言必须交给上下文感知 LLM。

## 11. Tool 系统

实现统一 Tool Gateway。

第一批 Tool：

- `MinecraftWorldTool`
- `MemoryTool`
- `SearchTool`
- `ConversationTool`
- `TaskTool`

要求：

- 参数 Schema；
- 权限；
- 风险；
- 超时；
- 取消；
- 输出大小限制；
- 审计；
- 错误分类；
- 不允许任意 Shell；
- 不允许任意文件系统；
- 不允许任意 URL；
- 不允许读取密钥；
- 不允许上传私人内容。

所有 Tool 输出返回 LLM 前都视为不可信数据并清洗。

## 12. 未完成工程主线

### P0 认知与对话

- Chat Mode；
- Chat/Action/Search/Goal Modification 分类；
- 最近对话；
- 记忆；
- 用户偏好；
- 主动建议；
- LLM 原生自然回复；
- Search Tool；
- 来源；
- 搜索权限和隐私；
- Prompt Injection 防护；
- 不提供当前不能执行的选项。

### P1 容器

- LocateKnownContainer；
- 多容器；
- Withdraw；
- Deposit；
- 导航→打开→取物→返回→交付；
- 部分结果；
- 容器移走；
- 背包满；
- 恢复；
- 计划级测试。

### P2 制作

- 配方；
- 材料差额；
- 工作台；
- 制作；
- 前置任务；
- 部分完成；
- 交付；
- 验证。

### P3 资源

- ExploreArea；
- CollectResource；
- MineResourceVein；
- 工具选择；
- 耐久；
- 资源搜索；
- 掉落验证；
- 矿脉边界；
- 木材、煤、铁、钻石代表任务；
- 换路线和换矿点。

### P4 熔炼

- 熔炉；
- 燃料；
- 输入；
- 等待；
- 输出；
- 数量验证；
- 缺材料时 Replan 或协商。

### P5 危险

- RetreatFromDanger；
- DefendOwner；
- 生命、饥饿、火、岩浆、溺水、爆炸和夜间；
- 安全 Reflex；
- 任务中断与恢复。

### P6 长期可靠性

- Runtime 重启；
- 游戏重连；
- Lease；
- Provider 故障；
- Task/Plan/Conversation 一致性；
- 无复制；
- 无重复交付；
- 长稳；
- 性能；
- 日志限速；
- 上下文预算。

### P7 产品化

- 安装器；
- Doctor；
- 配置；
- Provider 设置；
- Search 设置；
- 权限设置；
- 用户数据管理；
- 卸载；
- 回退；
- 发布包；
- 版本升级；
- Migration；
- 支持包；
- 崩溃恢复；
- README；
- 隐私说明；
- 已知限制。

### P8 真人测试

测试根目录：

```text
F:\wodeshijie\ceshi
```

禁止修改现有 Vanilla 26.2。

创建隔离实例：

```text
F:\wodeshijie\ceshi\mcac-human-test-fabric-1.21.1
```

准备：

- 独立 Fabric；
- 测试世界；
- Mod；
- Runtime；
- HTML；
- 启动/停止；
- 卸载/回退；
- 测试场景；
- 日志；
- 反馈模板；
- 真人测试指南。

## 13. 代表体验

### 纯聊天

用户：

> 今天有点累，不太想冒险。

自然回应并给低压力建议，不创建危险任务。

### 联想建议

用户：

> 我们接下来做什么比较好？

结合世界、库存、记忆和风险给一到三个建议。

### 搜索

用户：

> 查一下这个 Fabric 模组现在是否支持 1.21.1。

调用 Search Tool，给来源和不确定性，不凭记忆猜。

### 缺货

用户：

> 去箱子拿 16 个铁锭，不够就告诉我。

只有 6 个时报告事实、给真实可执行选项、等待回答并续接原任务。

### 钻石

用户：

> 帮我找些钻石，但别太冒险。

检查工具、食物、火把和风险；失败后换方法；风险明显增加时商量；最终用真实掉落验证。

### 聊天中断任务

执行中：

> 先别忙了，回来陪我聊会儿。

安全停止、返回/跟随并进入 Chat Mode。

## 14. 测试矩阵

### 单元

- Chat/Action/Search 分类；
- Search 权限；
- 搜索结果清洗；
- 来源；
- Tool Schema；
- Prompt Injection；
- 记忆；
- 偏好；
- 主动建议频率；
- 失败分类；
- 回答续接；
- Capability；
- Plan；
- Validator。

### 集成

- Chat → 回复；
- Search → Tool → 来源回复；
- Search 失败；
- Conversation → Memory；
- Action → Plan → Skill；
- Observation → Replan；
- Replan → 对话；
- 用户回答 → 同 plan；
- Runtime 重启；
- 离线消息；
- Provider/Tool 超时；
- 数据删除。

### GameTest

- 容器；
- 制作；
- 采集；
- 熔炼；
- 钻石策略；
- 危险；
- 中断；
- 恢复；
- 无复制；
- 无作弊。

### 浏览器

- Chat；
- Search；
- 来源展示；
- 任务；
- 选项；
- 等待；
- 目标修改；
- 记忆设置；
- 搜索权限；
- 日志和支持包。

### 全量

- `gradlew check buildPlatforms`
- Runtime/Fabric E2E
- GameTest
- Playwright
- persistence
- package verification
- forbidden API
- independence
- secret scan
- privacy scan
- search policy tests
- remote CI

## 15. 安全、隐私与非作弊

禁止：

- 传送；
- 飞行；
- 无敌；
- 生成物品；
- 直接改库存；
- 绕过配方；
- 作弊命令；
- 伪造 Observation；
- 伪造成功；
- 自动上传世界或聊天；
- 自动登录网站；
- 读取 Cookie；
- 读取启动器凭据；
- 将坐标、服务器地址和聊天发给搜索服务；
- 执行网页代码；
- 让网页指令覆盖系统规则。

用户必须能：

- 关闭搜索；
- 关闭主动建议；
- 查看/删除记忆；
- 导出数据；
- 清理日志；
- 更换 Provider；
- 卸载并回退。

## 16. API 与 Provider

- 不自行创建 API Key；
- 不把 ChatGPT 订阅当 API；
- 使用环境变量或 Windows Credential Manager；
- 支持 OpenAI-compatible 文本网关；
- Provider 和 Search Provider 分开配置；
- 无 Key 时继续 Replay、Fake 和确定性开发；
- Replay 不等于 Live；
- Live 测试覆盖聊天、建议、搜索、缺货、目标修改、多步骤行动和失败重规划。

## 17. 产品化出口门槛

只有全部满足后才能报告：

`READY_FOR_HUMAN_PRODUCT_TEST`

### 陪伴

- 自然聊天；
- 不把聊天误当任务；
- 会思考和给建议；
- 会联想世界和共同记忆；
- 会在需要时搜索；
- 搜索有来源；
- 区分事实和推断；
- 等待并续接用户回答；
- 修改目标；
- 不机械刷屏。

### 行动

- 容器完整链；
- Deposit；
- 制作；
- 资源；
- 熔炼；
- 钻石代表任务；
- 危险；
- 暂停、继续、取消；
- 重启恢复；
- 无进展处理。

### 产品

- 隔离真人实例；
- 安装和卸载；
- 配置；
- Provider/Search 设置；
- 日志和支持包；
- 隐私设置；
- 回退；
- 发布包；
- Migration；
- 文档。

### 证据

- 单元、集成、GameTest、浏览器和发布测试；
- 真人实例自动冒烟；
- 长稳和性能；
- 无作弊；
- 秘密扫描；
- 隐私扫描；
- Search 安全测试；
- 远程 CI；
- 发布包与远端 SHA 一致；
- 工作区干净。

无 Live Provider 时只能报告：

`READY_FOR_HUMAN_PRODUCT_TEST_EXCEPT_LIVE_PROVIDER`

## 18. 持续循环

反复执行：

```text
检查远端和工作区
→ 读取 Goal 与状态
→ 跑基线
→ 找最高价值产品缺口
→ 实现完整纵向切片
→ 自动化测试
→ 真人体验审查
→ 安全/隐私/性能审查
→ 修复
→ commit
→ push
→ 核对 CI
→ 更新 GOAL_STATE
→ 下一轮
```

每轮都问：

- 它像伙伴还是像工具？
- LLM 是否只剩 JSON 生成器？
- 它能聊天吗？
- 它会联想和建议吗？
- 它知道什么时候搜索吗？
- 搜索是否安全、有来源？
- 用户回答能改变原任务吗？
- 是否提供身体做不到的选项？
- 哪个缺口最阻碍产品化？

## 19. 防偷懒与防伪完成

以下不算完成：

- 只写 prompt；
- 只新增 Tool 接口；
- 只新增 Search 按钮；
- 只返回搜索模拟数据；
- 只支持固定问句；
- 只用关键词区分聊天和任务；
- 只实现聊天、不完成行动；
- 只实现行动、不支持聊天；
- 搜索没有来源和隐私；
- 只做 Mock/Replay；
- 只通过单元测试；
- commit/push；
- 工作区干净；
- Goal UI 完成；
- 版本号提升；
- 文档声称产品化；
- 降低测试；
- 跳过 CI；
- 作弊；
- 伪成功。

## 20. 开始执行

1. 检查远端 HEAD 是否为 `d82e308` 或后继提交。
2. 核对现有陪伴 P0，不重复实现。
3. 更新 `docs/GOAL_STATE.md`。
4. 下一优先级：
   - Chat / Action / Search / Goal Modification 路由；
   - 最近对话与记忆；
   - Search Tool Gateway；
   - 搜索权限、来源和隐私；
   - 不提供当前不可执行选项；
   - LocateKnownContainer；
   - Deposit；
   - 容器完整链。
5. 继续制作、资源、熔炼、危险、可靠性和产品化。
6. 在测试根目录创建隔离实例，不修改 Vanilla 26.2。
7. 每个稳定切片 commit 并 push。
8. 第 17 节未全部满足前不得结束长期 Goal。
