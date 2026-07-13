# Minecraft AI Companion HTML 控制终端设计系统

视觉基准见 `html-terminal-primary.png`。该界面是产品控制中心，不是营销页，也不是 CLI 的帮助页面。

## 信息架构

- 左侧固定导航：总览、启动器与实例、安装管理、游戏启动、Companion、自动测试、Runtime、Provider、Doctor、日志与支持、设置与安全。
- 主工作区：当前实例与主操作、Doctor → 安装检查 → Runtime → Minecraft → Mod 握手状态轨。
- 数据区：实例与运行状态使用表格/列表，避免卡片堆叠。
- 详情区：当前 Runtime、Mod、Provider、任务和连接证据。
- 底部事件流：安装进度、会话、Lease、Behavior、错误和恢复结果。

## 设计令牌

- 深色背景 `#07111d`，表面 `#0d1926`，边框 `#243447`。
- 主要文字 `#edf5ff`，次要文字 `#93a7bd`。
- 主色 `#2f86ff`，成功 `#37c978`，警告 `#f4b942`，失败 `#ef635d`。
- 圆角 8/10/12px；阴影仅用于模态框；图标统一 1.75px 描边。
- 中文界面优先，控制字号 13–14px，正文 14–16px，页面标题 24–28px。

## 交互约束

- 所有写操作先返回可审阅计划，再由确认对话框执行。
- 运行中按钮显示进度并禁用重复提交；完成后显示结构化结果。
- 回滚、卸载、Token 轮换必须使用危险操作确认样式。
- 后端断开时显示全局阻断提示，禁止写操作，绝不保留假成功状态。
- `CONNECTED`、`LOCAL_ONLY`、`FAILED`、`WAITING` 使用一致语义色和文字，不能只靠颜色表达。
- 1366×768 使用紧凑密度；小于 1100px 时详情区下移；4K 保持最大内容宽度和可读行长。

## 组件边界

`AppShell` 只负责整体布局；页面按业务域拆分；`StatusRail`、`DataTable`、`ActionButton`、`ConfirmDialog`、`OperationProgress`、`BackendBanner` 和 `EventStream` 为共享组件。所有业务状态来自后端 API，不在前端复刻 Java Service 逻辑。
