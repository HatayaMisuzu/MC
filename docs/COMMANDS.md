# 游戏命令

| 命令 | 作用 |
|---|---|
| `/companion create <name>` | 为执行者创建稳定 UUID 的同伴 |
| `/companion remove` | 永久移除自己的同伴注册（需要确认策略） |
| `/companion spawn` | 从 playerdata/项目数据恢复并进入世界 |
| `/companion despawn` | 保存后让同伴休眠 |
| `/companion follow` | 持续跟随在线 owner |
| `/companion come` | 正常行走返回 owner 附近 |
| `/companion goto <x> <y> <z>` | 在同维度正常行走到目标半径 |
| `/companion stop` | 立即取消当前行为和移动输入 |
| `/companion pause` | 暂停并清除当前移动输入 |
| `/companion resume` | 从有效快照继续 |
| `/companion status` | 显示身体、行为、Runtime 和错误状态 |
| `/companion runtime` | 显示/触发 Runtime 重连状态 |
| `/companion debug capability` | OP 查询冻结后的平台能力表 |

owner 只能控制自己的 companion；OP 可以诊断和管理。Dedicated Server 上以 UUID 校验，不以显示名授权。`stop` 和 `pause` 是本地高优先命令，不等待 Runtime 或 LLM。

