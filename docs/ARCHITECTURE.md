# 架构

```text
Companion Runtime (Java 21)
  task / event / lease / SQLite / rules / optional provider
                    |
          authenticated WebSocket
                    |
Minecraft loader module
  commands / lifecycle / bridge / platform hooks
                    |
Minecraft version body
  CompanionPlayer / FakeConnection / persistence
                    |
Behavior + Reflex + Pathing
                    |
PlayerActionGateway + ActionEvidence
                    |
Vanilla/Mod player code paths
```

## 不变量

- `pure-core` 不引用 `net.minecraft` 或 Loader API。
- Runtime 不引用 Minecraft 类。
- 三个 Loader 工程只声明其真实 Minecraft、Loader 和 Java 范围。
- 路径 worker 只读取主线程创建的 immutable `NavSnapshot`。
- 异步结果返回主线程时重新校验 world、dimension、lease epoch、behavior revision、companion 和 owner。
- 正常任务改变世界的唯一入口是 `PlayerActionGateway`。
- spawn/respawn、迁移和测试夹具例外隔离并审计。

## 启动顺序

```text
版本/Java 检查 → Loader 初始化 → Body Core → 数据迁移
→ 可选兼容发现 → capability freeze → Runtime pairing
```

Runtime 失败只能令状态降级为 `LOCAL_ONLY`/`SAFE_IDLE`，不能阻止 Mod 加载。

## 行为状态

`CREATED → STARTING → RUNNING ↔ WAITING/PAUSED → COMPLETED/FAILED/CANCELLED`

每个行为支持 start、tick、pause、resume、cancel、事件处理和序列化快照。租约过期、Runtime 断线、owner 下线或高优先反射会停止输入并进入安全状态。

