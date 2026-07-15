# CODEX_GOAL.md — Minecraft AI Companion 产品化长期 Goal

## Goal

持续把 Minecraft AI Companion 完善为真正可产品化、可真人测试的纯文本 AI 陪伴助手。

它必须同时具备：

- 自然聊天；
- 思考、判断和联想；
- 结合世界、任务、记忆和用户偏好；
- 自主行动；
- 失败后换方法；
- 必要时和用户商量；
- 普通聊天与行动任务的区分；
- 联网搜索和工具调用；
- 来源与不确定性；
- 安全、隐私、可取消、可恢复；
- 完整基础生存能力；
- 安装、升级、卸载、回退和支持体验。

它不能退化成：

- 固定关键词机器人；
- JSON 生成器；
- 命令翻译器；
- 任务专用 Handler 集合；
- 只会执行、不聊天的工具人；
- 只会聊天、不能行动的聊天机器人。

## 当前基线

最新已知远端：

`d82e308`

已完成：

- 动态 Capability 可见性；
- Observation → Replan；
- 容器真实取物；
- Conversation Event；
- WAITING_FOR_USER；
- 用户回答续接；
- 目标修改；
- 缺货部分交付真实 E2E；
- HTML 对话；
- SQLite/E2E 竞态修复。

仍缺：

- 真正 Chat Mode；
- LLM 原生自然对话；
- 联想与主动建议；
- 最近对话和长期记忆；
- Search Tool；
- 来源；
- 搜索权限和隐私；
- LocateKnownContainer；
- Deposit；
- 制作；
- 采集；
- 熔炼；
- 钻石换策略；
- 危险；
- 长稳；
- 产品化发布；
- 隔离真人测试实例；
- Live Provider 验收。

## 第一阶段

完成认知与工具层：

```text
用户消息
→ Chat / Think / Search / Action / Answer / Goal Modification 分类
→ LLM 结合世界、记忆和偏好思考
→ 必要时调用安全 Search Tool
→ 产生自然回复、建议或行动计划
→ 来源和权限校验
```

必须证明：

1. 普通聊天不会创建任务。
2. 模糊建议会结合世界和记忆。
3. 最新模组/版本问题会调用 Search Tool 并给来源。
4. 搜索不会泄漏本地世界、坐标、聊天或凭据。
5. 搜索网页不能 Prompt Injection。
6. 用户可关闭搜索和主动建议。
7. LLM 不只是固定 JSON 和模板。

## 第二阶段

继续完整行动主线：

1. LocateKnownContainer；
2. 多容器；
3. Deposit；
4. 容器完整链；
5. CraftItem；
6. CollectResource；
7. MineResourceVein；
8. SmeltItem；
9. 钻石代表任务；
10. RetreatFromDanger；
11. DefendOwner；
12. 记忆、偏好和世界事实失效；
13. 长稳和性能。

## 第三阶段

产品化：

1. 隔离 Fabric 1.21.1 真人实例；
2. 安装/卸载；
3. Provider/Search 配置；
4. 隐私和数据管理；
5. 日志与支持包；
6. 发布包；
7. Migration；
8. 自动化实机冒烟；
9. 真人测试指南和反馈；
10. Live Provider 验收；
11. 达到 `READY_FOR_HUMAN_PRODUCT_TEST`。

## 持续循环

```text
审计
→ 实现
→ 自动化测试
→ 真人体验审查
→ 安全/隐私/性能审查
→ 修复
→ commit
→ push
→ CI
→ 下一轮
```

一次 commit、测试通过、版本提升或 Goal UI 完成都不代表长期 Goal 结束。

## 出口

仅在 `AGENTS.md` 第 17 节全部满足时结束。

无 Live Provider 时只能报告：

`READY_FOR_HUMAN_PRODUCT_TEST_EXCEPT_LIVE_PROVIDER`
