# ADR-002 — 纯文本 Companion 的规则、模型、Skill 与验证分层

状态：Superseded

由 [../ARCHITECTURE.md](../ARCHITECTURE.md) 与
[../TASK_GRAPH_DSL.md](../TASK_GRAPH_DSL.md) 取代。

本 ADR 记录早期内置 `AgentDecision`/短计划架构，保留用于迁移审计。它不再授权 MCAC
维护高层 Planner，也不代表当前产品方向。当前唯一高层决策者是外部 Brain；现有
`AgentKernel`/短计划代码属于待迁移兼容层，不应继续扩展为内部 Agent。

## 背景

0.3.0 的自然语言路径只把文本映射成 FOLLOW、TRAVEL、RETURN、STOP、STATUS 五种 Intent。它能安全控制身体，但模型本质上仍是分类器，不能结合库存、世界状态、约束与失败观察形成短计划。

## 决策

输入管线固定为：

```text
NFKC/单位/坐标规范化
→ 停止、暂停、继续、取消、状态与明确移动的快速通道
→ 仅作候选的实体/意图提示
→ 有限且区分已验证事实的 AgentContext
→ 纯文本模型输出 AgentDecision
→ JSON、字段、语义、Capability、风险与预算校验
→ 持久化短计划
→ 一次执行一个通用 Capability
→ 真实 Observation
→ 确定性验证后继续、阻塞或重规划
```

模型只能选择注册的通用 Capability，不能输出脚本、命令、任意代码、直接世界写入或最终成功状态。`COMPLETE_CANDIDATE` 只是请求验证；成功由位置、库存、容器、制作、熔炼或交付证据决定。

计划与步骤写入 SQLite。运行中的步骤在 Runtime 重启后变为 `PAUSED/RECOVERY_REQUIRED`；推断不能覆盖已验证记忆；过期世界事实不会进入新上下文。Provider 受并发、每分钟调用、输出 token、超时、有限重试和中断预算约束。

Fabric 是首要完整身体。浏览器和所有者专用 `/mcac <文本>` 都进入同一个 Runtime 管线。游戏文本、告示牌、书本、物品名和其他玩家聊天始终是非可信数据，不能成为系统指令。

## 生存合法性

正式执行只允许经过测试、可取消、逐 tick 的能力。禁止传送、飞行、`setBlock`、生成物品、直接修改玩家/容器库存、绕过配方和测试后门。尚未实现的 Capability 必须进入 `BLOCKED/CAPABILITY_UNAVAILABLE`，不能回退到作弊或伪成功。

## 当前实施边界

本 ADR 定义的是必须保持的架构约束，不等于全部生存能力已经验收。只有真实 GameTest/E2E 覆盖并由世界证据验证的能力才能在最终报告中标为 PASS；其余项目必须保留为未通过，不得用本 ADR 代替实现。
