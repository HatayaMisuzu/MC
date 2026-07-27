# MCAC 兼容层工程

**英文名称：** MCAC Compatibility Layer Engineering  
**文件性质：** 长期工程规范、架构基线与协议总纲  
**不是：** 一次性执行文件、某个版本的开发任务书、自动写代码工作流  
**版本：** 1.0-draft  
**日期：** 2026-07-26  
**适用项目：** Minecraft AI Companion（MCAC）

---

# 0. 文档定位

本文件用于长期记录并规范“MCAC 兼容层工程”的功能、目标、边界与技术细节。

本工程不是让 MCAC 自己为每一个 Minecraft 版本、Loader、Mod 或整合包编写适配代码。其定位是：

> MCAC 提供开放、稳定、可扩展的兼容层宿主、兼容包协议、运行时管理机制和可选工具参考。  
> 当用户需要兼容某个正式版本、Mod 或整合包时，由项目之外的开发 Agent 或人工开发者，自主分析目标文件，制作、验证、安装、索引和维护兼容包。  
> MCAC 在游戏运行时根据实际实例自动匹配、组合、隔离、加载和调用已经完成的兼容成果。

兼容层工程要把未来的兼容工作从：

```text
重新理解并修改整个 MCAC
→ 反复调试公共代码
→ 容易影响已有版本
```

转变为：

```text
读取统一协议与现有兼容库
→ 分析目标差异
→ 只制作缺失兼容内容
→ 独立验证
→ 安装为可复用兼容包
→ 以后按实例自动调用
```

---

# 1. 核心目标

## 1.1 主要目标

兼容层工程必须能够：

1. 保存不同 Minecraft 版本、Loader、Mod、整合包和本地实例的兼容成果；
2. 根据当前真实实例自动匹配、组合、加载和调用正确的兼容包；
3. 让外部开发 Agent 在不接入 MCAC 游戏运行时的情况下独立制作兼容包；
4. 让外部 Agent 自行完成分析、计划、实现、验证、安装、索引、启用、修改、升级与回滚；
5. 降低每次新增兼容目标的重复劳动；
6. 在提速的同时不降低真实运行、安全、隔离、验证和回滚质量；
7. 让能力普通的 Agent 可以沿清晰黄金路径顺利完成；
8. 让能力强的 Agent 保留实现语言、工具、结构与分析路线的自由；
9. 支持纯声明式包，也支持严格隔离的原生扩展包；
10. 让兼容成果能够复用、组合、替换、禁用、修补、升级和撤销。

## 1.2 目标工作方式

用户提出：

> 兼容某个 Minecraft 版本、某个 Mod 或某个整合包。

外部开发 Agent 应能够：

```text
获取 MCAC 仓库与兼容规范
→ 获取目标实例或目标文件
→ 读取已有兼容库
→ 自主分析真实差异
→ 制订计划
→ 复用已有基础包、Loader 包与 Mod 包
→ 只实现缺失部分
→ 自行建立并运行测试
→ 自行修复
→ 自行安装到兼容层
→ 自行更新索引并启用
→ 启动真实目标实例冒烟验证
→ 输出兼容报告
```

以后启动相同或兼容实例时，MCAC 自动调用已有成果，不需要重新开发。

## 1.3 非目标

本工程不承诺：

- 首次遇到任意版本都无需编程；
- 首次遇到任意 Mod 都能立即完整理解其特殊机制；
- 一个通用 JAR 横跨所有 Minecraft 时代和 Loader；
- 未来正式版本零修改兼容；
- 单独兼容 Mod A 与 Mod B 就自动证明 A+B 整合包可用；
- 运行中的游戏 Agent 可以编译或修改原生兼容模块；
- SDK 是唯一合法实现方式；
- 外部 Agent 自称通过即可获得高信任状态。

---

# 2. 基本原则

## 2.1 MCAC 是兼容宿主，不是兼容开发者

MCAC 负责定义、存储、解析、隔离、加载和运行兼容成果。

MCAC 不负责针对目标版本或 Mod 自动生成适配代码，也不负责在正常游戏过程中启动编译器。

## 2.2 外部 Agent 完成完整闭环

外部开发 Agent负责：

- 目标分析；
- 实现计划；
- 声明规则或原生代码；
- 编译；
- 测试；
- 修复；
- 打包；
- 安装；
- 索引；
- 激活；
- 冒烟验证；
- 修改与升级；
- 最终报告。

MCAC 可以提供确定性管理接口，但不成为这条开发流程的高层控制者。

## 2.3 自由实现，合同验收

外部 Agent 可以自由选择：

- 语言；
- 构建系统；
- 分析工具；
- 是否使用 SDK；
- 代码结构；
- 测试顺序；
- 第三方开源工具。

但最终必须遵守：

- 兼容包协议；
- 能力合同；
- 安全边界；
- 隔离要求；
- 安装与索引事务；
- 版本、依赖和冲突声明；
- 可回滚要求；
- 真实证据要求。

## 2.4 SDK 只是辅助参考

SDK、CLI、模板、分析器和示例是可选工具箱，不是强制开发框架。

只要外部 Agent 能产生符合规范的兼容包，就可以不使用 SDK。

## 2.5 质量不依赖 Agent 聪明程度

质量主要通过以下机制保障：

- 强类型 Schema；
- 明确的中间产物；
- 稳定错误码；
- 生命周期状态；
- 机器可读测试结果；
- 原子安装；
- 隔离加载；
- 权限最小化；
- 哈希和来源记录；
- 自动回滚；
- 真实 Loader 与游戏路径验证。

弱 Agent 有黄金路径，强 Agent 有自由空间。

## 2.6 不以自动化牺牲真实性

以下证据不可互相替代：

- Schema 检查证明格式；
- 单元测试证明局部逻辑；
- Replay/Fixture 证明协议；
- GameTest 或真实服务端证明 Minecraft 代码路径；
- Runtime E2E 证明 MCAC 调用链；
- 真实目标实例证明实际加载；
- 真人或真实高层 Agent 测试证明最终使用体验。

---

# 3. 参与角色与职责

## 3.1 MCAC Core

负责：

- Runtime；
- MCP/Tool Gateway；
- Task Graph；
- Memory；
- Search；
- Skill；
- 权限；
- 审计；
- 安装器与 UI；
- Compatibility Host。

兼容包不得绕过 Tool Gateway 或直接修改 Core 内部数据。

## 3.2 Compatibility Host

兼容层运行宿主，负责：

- 环境指纹；
- 包发现；
- 存储；
- 索引；
- 依赖解析；
- 冲突解析；
- 分层组合；
- 隔离；
- 加载；
- 能力发布；
- 调用路由；
- 健康监测；
- 修改与升级；
- 自动回滚。

## 3.3 外部开发 Agent

例如 Codex 或其他编程 Agent。

它是兼容包开发者，但不是游戏内 Companion Agent。

它可以在用户授权范围内调用兼容管理接口，自行完成安装、索引、激活、修改和回滚。

## 3.4 游戏高层 Agent

例如 Hermes 或其他兼容的外部 Agent。

它通过统一 Tool 使用兼容能力，但不能：

- 编译；
- 修改原生包；
- 写活动索引；
- 提升包信任；
- 绕过隔离；
- 修改 Compatibility Host。

## 3.5 用户

用户确定兼容目标，并可授予可信外部开发 Agent：

- 指定仓库或实例；
- 指定兼容 Store；
- 指定操作范围；
- 指定有效期；
- 指定最大风险等级。

普通兼容包可在预授权范围内由外部 Agent直接完成，不要求用户逐包点击批准。

---

# 4. 总体架构

```text
MCAC Core
│
├─ Compatibility Host
│  ├─ Environment Fingerprinter
│  ├─ Pack Store
│  ├─ Pack Registry
│  ├─ Resolver
│  ├─ Layer Composer
│  ├─ Isolation Manager
│  ├─ Pack Loader
│  ├─ Capability Publisher
│  ├─ Call Router
│  ├─ Health Monitor
│  ├─ Mutation Manager
│  ├─ Transaction Manager
│  └─ Rollback Manager
│
├─ Compatibility Protocol
│  ├─ Package Schema
│  ├─ Matching Schema
│  ├─ Capability Schema
│  ├─ Observation Schema
│  ├─ Action Result Schema
│  ├─ Evidence Schema
│  ├─ Dependency/Conflict Schema
│  ├─ Install/Index Schema
│  └─ Migration Schema
│
├─ Compatibility Store
│  ├─ staging
│  ├─ verified
│  ├─ active
│  ├─ disabled
│  ├─ quarantined
│  ├─ rollback
│  └─ cache
│
├─ Compatibility Management API
│  ├─ inspect
│  ├─ install
│  ├─ index
│  ├─ activate
│  ├─ deactivate
│  ├─ update
│  ├─ rollback
│  ├─ remove
│  └─ diagnose
│
└─ Optional Toolbox
   ├─ SDK
   ├─ CLI
   ├─ Templates
   ├─ Analyzers
   ├─ Test Harness
   └─ Examples
```

---

# 5. 兼容包类型

统一扩展名建议为 `.mcac-compat`。

## 5.1 Minecraft 基础版本包

描述正式 Minecraft 版本本身的差异：

- Java 要求；
- 数据模型时代；
- Registry；
- ItemStack；
- Entity；
- Menu；
- 网络生命周期；
- 世界高度；
- 存档结构；
- 数据包/资源包版本；
- 动作和结果语义。

## 5.2 Loader 包

描述 Fabric、Forge、NeoForge 等 Loader 的：

- 生命周期；
- 事件；
- 网络；
- Registry；
- Capability；
- 服务端玩家对象；
- Mod 元数据；
- 安装结构；
- 原生入口。

## 5.3 Mod 包

描述某个 Mod 新增或改变的：

- Registry 内容；
- 菜单；
- 能量、流体与物品能力；
- 特殊实体；
- 安全规则；
- 成功观察；
- 必要原生扩展。

## 5.4 整合包覆盖包

只记录整合包对已有规则的改变：

- KubeJS；
- CraftTweaker；
- 配方覆盖；
- 阶段系统；
- 跨 Mod 规则；
- 配置；
- 数据包；
- 世界生成；
- 冲突解决。

## 5.5 实例本地包

只适用于一个精确实例，例如私人脚本、本地配置或服务器规则。

## 5.6 修补包

对父包做小型修正，不复制整个父包。

---

# 6. 兼容包结构

```text
pack.mcac-compat/
├─ manifest.yaml
├─ fingerprints/
│  ├─ minecraft.json
│  ├─ loader.json
│  ├─ mods.json
│  ├─ configuration.json
│  └─ content.json
├─ capabilities/
│  ├─ tools.yaml
│  ├─ observations.yaml
│  ├─ actions.yaml
│  ├─ menus.yaml
│  └─ safety.yaml
├─ mappings/
│  ├─ registry.yaml
│  ├─ recipes.yaml
│  ├─ items.yaml
│  ├─ blocks.yaml
│  ├─ entities.yaml
│  └─ components.yaml
├─ overlays/
│  ├─ dependencies.yaml
│  ├─ conflicts.yaml
│  └─ precedence.yaml
├─ native/
│  ├─ modules/
│  └─ native-manifest.yaml
├─ tests/
├─ evidence/
├─ migrations/
├─ LICENSES/
└─ SHA256SUMS.txt
```

纯声明式包可以没有 `native/`。

---

# 7. Manifest 最低要求

```yaml
schemaVersion: mcac-compat/1

pack:
  id: example.create.forge-1.20.1
  version: 1.0.0
  type: mod
  displayName: Create compatibility
  authorType: external-agent
  createdAt: 2026-07-26T00:00:00Z

target:
  minecraft:
    exact: 1.20.1
  loader:
    type: forge
    versionRange: "[47.2,48)"
  mods:
    - id: create
      versionRange: "[6.0,6.1)"

runtime:
  minimumHostVersion: 1
  nativeCode: false
  hotReloadable: true
  restartRequired: false

permissions:
  declared:
    - REGISTRY_READ
    - WORLD_OBSERVE
    - MENU_INSPECT
  forbidden:
    - SHELL
    - ARBITRARY_FILE_ACCESS
    - DIRECT_WORLD_EDIT

dependencies: []
conflicts: []
```

Manifest 只声明请求，不自动获得权限或信任。


# 8. 指纹与匹配

## 8.1 指纹组成

兼容层不能只按版本名称或 Mod ID 匹配。至少可以使用：

- Minecraft 精确版本；
- Loader 类型与版本；
- Java 主版本；
- Mod ID 与版本；
- JAR SHA-256；
- 配置摘要；
- KubeJS/CraftTweaker 等脚本摘要；
- 数据包和资源包摘要；
- 关键 Registry 摘要；
- 整合包清单摘要；
- 兼容包 Schema 版本。

## 8.2 匹配等级

### EXACT_VERIFIED

精确环境已经验证。

### RANGE_VERIFIED

位于明确验证范围内，关键结构未发生变化。

### STRUCTURAL_MATCH

结构相似但未在精确目标运行，仅允许低风险试运行。

### PROVISIONAL

外部 Agent 推测可用但证据不足，不得默认启用高风险动作。

### INCOMPATIBLE

明确不适用。

### UNKNOWN

没有足够信息。

## 8.3 指纹变化

启动时发现活动包和实例不再匹配：

1. 不继续静默使用高风险能力；
2. 将旧包标记为 `STALE`；
3. 尝试匹配其他包；
4. 没有匹配时回退到通用观察能力；
5. 显示能力降级；
6. 保留旧包和回滚记录。

---

# 9. 生命周期

```text
DRAFT
→ STAGING
→ TESTED
→ VERIFIED
→ ACTIVE
→ DISABLED
→ SUPERSEDED
→ REVOKED
→ REMOVED
```

异常状态：

```text
QUARANTINED
FAILED
STALE
```

- `DRAFT`：外部工作区内容；
- `STAGING`：已进入暂存区，尚不发布能力；
- `TESTED`：存在测试记录；
- `VERIFIED`：满足所声明验证等级；
- `ACTIVE`：当前实例实际使用；
- `DISABLED`：保留但不参与解析；
- `SUPERSEDED`：被新版替代，可回滚；
- `REVOKED`：被安全撤销；
- `QUARANTINED`：损坏、越界或异常隔离。

---

# 10. 兼容层管理

## 10.1 管理对象

管理系统覆盖：

- 包及其版本；
- 内容哈希；
- 指纹；
- 依赖与冲突；
- 信任等级；
- 生命周期；
- 当前实例绑定；
- 加载记录；
- 能力来源；
- 调用统计；
- 健康状态；
- 修改历史；
- 测试证据；
- 回滚点；
- 撤销记录。

## 10.2 管理操作

至少提供：

```text
list
inspect
diagnose
install
index
record-evidence
activate
deactivate
update
patch
rollback
remove
quarantine
revoke
restore
export
```

这些操作均可由外部开发 Agent 调用。

## 10.3 外部 Agent 管理范围

在用户授予的作用域内，外部 Agent 可以：

- 安装到 staging；
- 写入真实测试记录；
- 更新候选索引；
- 激活普通兼容包；
- 运行冒烟测试；
- 失败后回滚；
- 修改自己维护的包；
- 禁用或替换自己安装的版本。

不能：

- 修改 MCAC Core；
- 伪造测试记录；
- 提升到超出授权的信任等级；
- 绕过原子事务；
- 直接编辑活动索引；
- 删除其他活动包而不保留回滚；
- 解除安全撤销；
- 扩大 Runtime 权限。

## 10.4 API 与物理存储分离

外部 Agent 应通过管理 API、CLI 或符合相同事务合同的客户端操作兼容层。

活动索引、状态数据库和回滚记录不得通过简单复制或文本编辑直接修改。

---

# 11. Store 与索引

## 11.1 Store 结构

```text
compatibility-store/
├─ staging/
├─ verified/
├─ active/
├─ disabled/
├─ quarantined/
├─ rollback/
├─ cache/
├─ metadata/
├─ index/
└─ journal/
```

## 11.2 内容寻址

包内容按 SHA-256 或等价强哈希寻址。

Pack ID 和版本用于管理，内容哈希用于确认真实内容。

同一 ID、同一版本但内容不同，应视为冲突并拒绝覆盖。

## 11.3 索引字段

索引至少记录：

- Pack ID；
- 版本；
- 内容哈希；
- 类型；
- 目标；
- 匹配规则；
- 状态；
- 信任等级；
- 依赖；
- 冲突；
- 作者类型；
- 安装时间；
- 实例绑定；
- 上一版本；
- 回滚位置；
- 最近加载结果；
- 最近测试结果。

## 11.4 原子索引事务

安装、更新、激活与回滚必须：

```text
创建事务
→ 校验包
→ 写入不可变内容存储
→ 生成候选索引
→ 校验候选组合
→ 原子切换索引
→ 记录事务与回滚点
→ 失败恢复旧索引
```

不得出现包和索引处于不同版本的混合状态。

---

# 12. 隔离工程

## 12.1 隔离目标

单个兼容包的错误不得：

- 破坏 MCAC Core；
- 影响其他实例；
- 控制错误 Companion；
- 读取凭据；
- 任意访问文件；
- 直接修改存档；
- 修改其他包；
- 绕过 Tool Gateway；
- 禁用审计；
- 在禁用后继续执行。

## 12.2 隔离维度

### 实例隔离

包只在匹配实例范围生效。

### Profile 隔离

不同 MCAC Profile 的私有状态不共享。

### Companion 隔离

所有运行时调用绑定正确 Companion。

### Loader 隔离

Fabric、Forge、NeoForge 原生模块不能混载。

### 版本隔离

原生模块只在声明版本范围加载。

### 权限隔离

包只能使用已声明并被允许的能力。

### 数据隔离

包状态写入独立逻辑命名空间。

### 类加载或进程隔离

原生模块优先采用独立 ClassLoader、模块边界或独立进程。

## 12.3 声明式包

声明式包只能影响：

- 能力映射；
- 数据转换；
- 菜单语义；
- 规则；
- 验证条件；
- 安全提示；
- 路由元数据。

不能执行任意代码。

## 12.4 原生包

原生包必须：

- 声明入口；
- 声明目标；
- 声明权限；
- 声明依赖；
- 使用受限 Host API；
- 禁止 Shell；
- 禁止任意文件系统；
- 禁止直接数据库访问；
- 禁止加载未声明本地库；
- 禁止未授权网络；
- 禁止直接修改世界；
- 支持安全停用；
- 异常时可被隔离。

## 12.5 失败隔离

若包发生：

- 重复异常；
- 非法返回；
- 长时间超时；
- 未声明调用；
- 哈希变化；
- 原生模块崩溃；
- 内存或线程失控；

Host 应：

1. 停止新调用；
2. 取消或隔离活动调用；
3. 降级相关能力；
4. 保持 Core 与其他包运行；
5. 记录诊断；
6. 进入 `DISABLED` 或 `QUARANTINED`；
7. 必要时自动回滚。

---

# 13. 依赖、分层与冲突

## 13.1 默认分层顺序

```text
实例本地包
→ 整合包覆盖包
→ 精确 Mod 包
→ Mod 版本范围包
→ Loader 包
→ Minecraft 基础版本包
→ MCAC 通用后备能力
```

## 13.2 依赖类型

- required；
- optional；
- alternative；
- conflict；
- replaces；
- extends；
- patches。

必需依赖缺失时，包不得激活。

循环依赖在安装或索引阶段拒绝。

## 13.3 冲突来源

- 多个包声明同一工具；
- 不同菜单语义；
- 整合包脚本覆盖 Mod 默认规则；
- 同一 Mod 多个包；
- 原生依赖冲突；
- 配置指纹变化；
- 不同安全行为。

## 13.4 冲突解决

1. 精确实例包优先；
2. 整合包包优先于普通 Mod 包；
3. 精确哈希优先于版本范围；
4. 明确 patch/replaces 优先；
5. 已验证包优先于 provisional；
6. 更严格安全规则优先；
7. 无明确规则时不得静默选择；
8. 高风险能力冲突时禁用能力；
9. 外部 Agent 可制作显式冲突解决包。

每次解析必须记录最终选择与原因。

---

# 14. 加载工程

## 14.1 加载阶段

```text
DISCOVER
→ FINGERPRINT
→ RESOLVE
→ COMPOSE
→ PREFLIGHT
→ LOAD
→ REGISTER
→ HEALTH_CHECK
→ PUBLISH
```

### DISCOVER

发现 Store 中的包，不执行原生代码。

### FINGERPRINT

读取当前实例的 Minecraft、Loader、Java、Mod、配置、脚本与数据包信息。

### RESOLVE

确定候选包、依赖、优先级与冲突。

### COMPOSE

生成本实例的有效兼容视图，不修改原包。

### PREFLIGHT

检查：

- 哈希；
- Schema；
- 依赖；
- 权限；
- Loader；
- Java；
- 冲突；
- 原生入口；
- 撤销；
- 重启要求。

### LOAD

先加载声明式数据，再加载必要原生模块。

### REGISTER

注册到 Host 内部命名空间，尚不对高层 Agent 发布。

### HEALTH_CHECK

运行有界健康探针。

### PUBLISH

仅发布通过健康检查的能力。

## 14.2 延迟加载

允许按能力延迟加载，但首次调用前必须完成相同 preflight。

高风险调用过程中不得临时加载未经检查的模块。

## 14.3 热加载

仅 `hotReloadable=true` 且证明活动调用、状态迁移和卸载安全的包可以热加载。

原生包默认要求 Runtime 或游戏重启。

---

# 15. 能力发布与调用

## 15.1 统一工具合同

高层 Agent 只看到稳定语义，例如：

```text
registry.search
registry.describe
recipe.query
world.observe
movement.goto
block.interact
inventory.transfer
menu.inspect
menu.click
item.use
entity.interact
```

兼容包不能把 Loader 私有类直接暴露出去。

## 15.2 调用链

```text
高层 Agent Tool Call
→ Tool Gateway
→ 权限检查
→ Compatibility Call Router
→ 当前实例有效能力
→ 兼容包组合实现
→ Minecraft Body
→ 真实观察
→ 标准化结果
→ Evidence
→ 高层 Agent
```

## 15.3 调用绑定

每个调用至少绑定：

- Profile；
- 实例；
- Loader；
- Companion；
- Brain session；
- Controller；
- Tool；
- Pack ID/版本/哈希；
- Call ID；
- 权限；
- 超时；
- 取消 Token。

## 15.4 多包组合调用

一个工具可以由：

```text
Minecraft 基础 Codec
+ Loader 行为实现
+ Mod 语义
+ 整合包覆盖
```

共同形成。调用 Trace 必须指出每一层来源。

## 15.5 标准结果

兼容实现不能只返回布尔值。

```json
{
  "status": "SUCCEEDED",
  "effectConfidence": "VERIFIED",
  "observations": [],
  "evidence": [],
  "packTrace": [],
  "warnings": []
}
```

标准状态：

- SUCCEEDED；
- FAILED；
- PARTIAL；
- UNCERTAIN_EFFECT；
- UNSUPPORTED；
- CANCELLED；
- TIMED_OUT；
- RECONCILIATION_REQUIRED；
- CAPABILITY_UNAVAILABLE。

## 15.6 不确定副作用

非幂等操作无法确认结果时：

- 不自动重试；
- 返回 `UNCERTAIN_EFFECT` 或 `RECONCILIATION_REQUIRED`；
- 提供真实回看信息；
- 由高层 Agent 或用户决定下一步。


# 16. 修改、升级与回滚

## 16.1 活动包不可原地修改

所有修改必须产生：

- 新版本；
- 或显式 patch 包。

这样才能保持可审计和可回滚。

## 16.2 修改类型

- Manifest 修正；
- 映射更新；
- 能力增加；
- 菜单语义修正；
- 安全规则收紧；
- 原生模块替换；
- 依赖升级；
- 目标范围改变；
- 测试与证据补充。

## 16.3 外部 Agent 修改闭环

```text
读取当前活动包
→ 创建工作副本
→ 修改
→ 产生新版本或 patch
→ 自行验证
→ 安装到 staging
→ 更新候选索引
→ 原子切换
→ 冒烟测试
→ 保留旧版回滚
```

## 16.4 原生模块修改

必须：

- 重新构建；
- 更新哈希；
- 更新依赖清单；
- 重新运行安全与真实 Loader 测试；
- 默认重启；
- 不热替换正在执行的代码。

## 16.5 包升级

升级可以是：

- 兼容升级；
- 破坏性升级；
- 安全修复；
- 适用范围扩大；
- 适用范围缩小。

## 16.6 Schema 迁移

兼容包协议必须显式版本化：

```text
mcac-compat/1
mcac-compat/2
```

Host 可以：

- 读取支持范围内的旧 Schema；
- 使用确定性迁移器生成新表示；
- 保留原包；
- 记录迁移。

无法可靠迁移时拒绝加载，不猜测。

## 16.7 自动回滚

出现以下情况可以自动回滚：

- 新版本无法加载；
- 健康检查失败；
- 关键能力注册失败；
- 冒烟测试失败；
- 调用连续异常；
- 原生模块崩溃；
- 指纹错误；
- 新版索引不一致。

自动回滚后应恢复上一有效组合，而不是只禁用全部兼容能力。

---

# 17. 安装、索引与激活

## 17.1 外部 Agent 主导

外部 Agent 在授权范围内可以自行完成：

- 安装；
- 证据写入；
- 索引；
- 激活；
- 冒烟测试；
- 回滚。

MCAC 提供受限、原子、可审计入口。

## 17.2 安装事务

```text
BEGIN
→ 检查授权
→ 校验包结构和哈希
→ 检查目标与 Host API
→ 检查依赖与冲突
→ 写入 staging
→ 生成候选索引
→ 基础加载检查
→ 原子提交
→ 保存回滚点
END
```

## 17.3 激活事务

激活必须：

1. 绑定明确实例；
2. 生成新的有效包组合；
3. 停止受影响的新调用；
4. 等待或取消旧调用；
5. 加载新组合；
6. 健康检查；
7. 发布能力；
8. 失败恢复旧组合。

## 17.4 预授权模式

用户可以授权：

```text
外部 Agent 身份
+ 指定实例或 Store
+ 允许操作
+ 有效期限
+ 最大风险等级
```

普通声明式包可以在预授权内直接完成。

包含原生代码、权限扩大或跨实例变化时，可以由用户策略要求额外确认。

---

# 18. 验证记录与信任模型

## 18.1 外部 Agent 自行验证

外部 Agent 自主选择验证工具和过程。

SDK 只是可选辅助。

## 18.2 信任不能由自述决定

包的状态由可核验记录和用户策略决定，而不是因为 Agent 声称“通过”。

## 18.3 验证等级

### SCHEMA_VALID

格式与 Schema 有效。

### STATIC_ANALYZED

完成静态分析。

### ISOLATION_TESTED

权限与隔离通过。

### LOADER_TESTED

真实 Loader 启动通过。

### RUNTIME_E2E_TESTED

通过 MCAC Runtime 调用。

### LIVE_INSTANCE_TESTED

在真实目标实例验证。

### HUMAN_ACCEPTED

真人确认最终体验。

包可以只有部分等级，不得统一包装成“完全验证”。

## 18.4 标准证据

证据至少可以记录：

- 目标指纹；
- 源文件哈希；
- 构建环境；
- 测试命令；
- 测试结果；
- Loader 日志摘要；
- Runtime 调用摘要；
- 失败与限制；
- 时间；
- 工具版本；
- 是否真实实例；
- 是否使用 Mock；
- 作者或 Agent 类型。

外部 Agent 可使用自己的测试框架，只要结果能够映射到标准 Evidence Schema。

---

# 19. 弱 Agent 黄金路径

为了让能力普通的 Agent 也能完成，工程提供非强制黄金路径：

```text
1. 读取工程总纲
2. 读取目标 Profile
3. 读取兼容包协议
4. 查看最相似的现有包
5. 生成 GAP_REPORT
6. 选择包类型
7. 复制最近模板
8. 只处理 GAP_REPORT 缺口
9. 检查格式、依赖和权限
10. 运行真实 Loader 测试
11. 运行 Runtime 调用测试
12. 修复 blocker
13. 生成证据
14. 安装到 staging
15. 更新候选索引
16. 激活
17. 冒烟测试
18. 失败回滚
19. 输出 FINAL_REPORT
```

该顺序是建议，不是协议强制。

## 19.1 建议中间产物

```text
STATE.json
TARGET_PROFILE.json
GAP_REPORT.json
DECISIONS.json
BUILD_REPORT.json
TEST_REPORT.json
INSTALL_REPORT.json
FINAL_REPORT.json
```

## 19.2 稳定错误码

至少包括：

```text
PACK_SCHEMA_INVALID
TARGET_FINGERPRINT_MISMATCH
DEPENDENCY_MISSING
DEPENDENCY_CYCLE
PACK_CONFLICT
PERMISSION_UNDECLARED
NATIVE_MODULE_REJECTED
NATIVE_MODULE_LOAD_FAILED
LOADER_BOOT_FAILED
REGISTRY_PROBE_FAILED
CAPABILITY_REGISTRATION_FAILED
TOOL_RESULT_INVALID
TOOL_EFFECT_UNVERIFIED
NON_IDEMPOTENT_REPLAY_RISK
INDEX_TRANSACTION_FAILED
ACTIVATION_HEALTH_FAILED
ROLLBACK_FAILED
PACK_HASH_MISMATCH
PACK_REVOKED
PACK_STALE
```

错误应包含：

- 位置；
- 原因；
- 严重度；
- 是否阻塞；
- 修复建议；
- 相关包；
- 相关目标。

---

# 20. 强 Agent 自由空间

强 Agent 可以：

- 不使用 SDK；
- 自建分析器；
- 自建构建系统；
- 直接生成协议文件；
- 自建测试；
- 使用多版本构建工具；
- 编写复杂原生模块；
- 同时维护多个包；
- 自动生成差异补丁；
- 自行调用安装和索引 API。

但不能：

- 绕过安全；
- 直接修改活动包；
- 伪造状态；
- 直接写活动索引；
- 省略回滚；
- 把模拟测试标成真实实例；
- 使用未声明权限；
- 静默加载不匹配代码。

---

# 21. 管理 UI 与可观测性

UI 是用户管理入口，但外部 Agent 不依赖 UI 才能完成流程。

## 21.1 UI 应显示

- 当前实例指纹；
- 当前加载包；
- 每个包的版本、来源和状态；
- 能力来源；
- 冲突与抑制；
- 被禁用能力；
- 健康状态；
- 最近调用；
- 最近失败；
- 可用更新；
- 回滚点；
- 信任等级；
- 证据摘要；
- 原生代码提示；
- Stale/失配提示。

## 21.2 调用追踪

每次兼容调用能够查看：

```text
Tool
→ Minecraft 基础包
→ Loader 包
→ Mod 包
→ 整合包覆盖
→ 最终实现
```

Trace 不暴露凭据和完整私人数据。

## 21.3 健康指标

- 已加载包数；
- 活动能力数；
- 加载失败；
- 调用失败；
- 超时；
- 隔离事件；
- 自动回滚；
- Stale 包；
- 冲突；
- 原生模块资源状态。

---

# 22. 删除、禁用与清理

## 22.1 禁用

停止参与解析，但保留文件、证据和回滚。

## 22.2 移除

只能移除未被活动组合引用的包。

## 22.3 清理缓存

缓存可重建，不影响不可变包和索引。

## 22.4 旧版本保留

至少保留：

- 当前版本；
- 上一可用回滚版本；
- 安全撤销记录；
- 活动实例依赖的版本。

## 22.5 实例删除

删除实例数据时，不默认删除共享兼容包。

实例本地包可以单独选择删除。

---

# 23. 安全模型

## 23.1 来源等级

兼容包可能来自：

- 项目官方；
- 用户自己的外部 Agent；
- 第三方开发者；
- 社区；
- 未知来源。

来源不等于安全，仍需哈希、权限和隔离。

## 23.2 原生包视为代码

原生兼容包风险等同 Mod 或插件代码。

必须可查看：

- 作者；
- 来源；
- 哈希；
- 权限；
- 目标；
- 验证状态；
- 是否联网；
- 是否包含本地库。

## 23.3 绝对禁止

兼容包不得：

- 读取 API Key；
- 访问启动器账号；
- 执行 Shell；
- 任意访问文件；
- 直接编辑世界存档；
- 修改 MCAC 数据库；
- 绕过 Companion 身份；
- 绕过用户取消；
- 创建隐藏网络服务；
- 下载并执行未声明代码；
- 修改其他包；
- 禁用审计。

## 23.4 撤销机制

可以按：

- Pack ID；
- 版本；
- 哈希；
- 签名；
- 原生模块；

进行撤销。

撤销优先于活动索引。

---

# 24. 性能要求

兼容层不能让无关包拖慢正常游戏。

要求：

- 启动时预计算有效组合；
- 调用时不重新扫描整个 Store；
- 按实例缓存 Resolver 结果；
- 包元数据与大内容分离；
- 声明式规则编译为只读内部表示；
- 原生模块有资源预算；
- 健康检查有界；
- 更新和索引不阻塞取消与安全操作；
- 热路径 Trace 简洁；
- 详细证据异步写入。

---

# 25. 兼容层自身演进

## 25.1 Host API 版本

原生模块必须声明支持的 Host API 范围。

## 25.2 包 Schema 版本

包格式独立版本化。

## 25.3 能力版本

工具语义单独版本化，避免包格式变化破坏所有工具。

## 25.4 向后兼容

优先通过确定性迁移支持旧包，而不是永久保留所有旧实现。

## 25.5 破坏性变化

必须提供：

- 迁移说明；
- 示例或工具；
- 兼容期；
- 明确拒绝；
- 不静默误读。

---

# 26. 工程目录建议

```text
compatibility-engineering/
├─ README.md
├─ specification/
│  ├─ PACK_SPEC.md
│  ├─ CAPABILITY_SPEC.md
│  ├─ OBSERVATION_SPEC.md
│  ├─ ACTION_RESULT_SPEC.md
│  ├─ EVIDENCE_SPEC.md
│  ├─ INSTALL_INDEX_SPEC.md
│  ├─ ISOLATION_SPEC.md
│  └─ MIGRATION_SPEC.md
├─ schemas/
├─ host/
│  ├─ resolver/
│  ├─ store/
│  ├─ loader/
│  ├─ isolation/
│  ├─ router/
│  ├─ mutation/
│  └─ management/
├─ compatibility-packs/
│  ├─ minecraft/
│  ├─ loaders/
│  ├─ mods/
│  ├─ modpacks/
│  ├─ instances/
│  └─ patches/
├─ optional-toolbox/
│  ├─ sdk/
│  ├─ cli/
│  ├─ templates/
│  ├─ analyzers/
│  └─ examples/
├─ conformance-reference/
├─ error-catalog/
└─ docs/
```

---

# 27. 初始建设范围

第一阶段无需马上兼容大量版本，但必须建立：

1. 包协议；
2. Store；
3. 索引；
4. 指纹；
5. Resolver；
6. 分层组合；
7. 冲突处理；
8. 声明式加载；
9. 原生模块隔离接口；
10. 调用路由；
11. 生命周期；
12. 安装、升级和回滚事务；
13. 管理 API；
14. 外部 Agent 授权；
15. 机器可读错误；
16. 可选模板；
17. UI 管理入口；
18. 调用 Trace。

现有 Fabric 1.21.1 与计划中的 Forge 1.20.1 可作为首批真实兼容包，验证：

- 两个 Loader/版本能由同一 Host 管理；
- 高层 Tool 合同稳定；
- 包可以独立升级和回滚；
- 外部 Agent 无需修改 Core 即可新增兼容成果。

---

# 28. 工程验收标准

## 28.1 管理

- 可安装、索引、激活、禁用、升级、回滚和移除；
- 所有操作可由外部 Agent 调用；
- 不要求逐包手动导入；
- 管理作用域可限制。

## 28.2 隔离

- 错误包不破坏 Core；
- 不跨实例、Profile 和 Companion；
- 无任意文件、Shell 和凭据访问；
- 禁用后不再接收调用；
- 原生异常可隔离。

## 28.3 加载

- 按真实指纹解析；
- 依赖与冲突明确；
- 组合结果可重复生成；
- 加载失败诚实降级；
- 不匹配包不加载；
- 能力来源可审计。

## 28.4 调用

- 高层 Agent 使用统一 Tool；
- 调用可追踪到具体包；
- 权限与取消正确；
- 不确定副作用不重复；
- 结果标准化。

## 28.5 修改

- 活动包不可原地修改；
- 修改产生新版本或 patch；
- 新版失败可回滚；
- 修改历史可查看；
- 原生修改重新验证。

## 28.6 外部 Agent 体验

- 普通 Agent 能沿黄金路径完成简单声明式包；
- 强 Agent 可不使用 SDK 完成同样成果；
- 两者生成的包都能被 Host 解析；
- 错误结果有机器可读提示；
- 中断后可以继续；
- 机械工作可自动化；
- 不需要重新理解整个 MCAC。

## 28.7 质量

- 不把静态分析当真实运行；
- 不把 Fixture 当真人或真实实例；
- 不伪造信任；
- 安装和索引原子；
- 哈希一致；
- 支持回滚；
- 缺少能力时明确 unsupported；
- 不损失 Core 现有安全与可靠性。

---

# 29. 最终定义

“MCAC 兼容层工程”定义为：

> 一套用于保存、管理、隔离、组合、加载、调用、修改、升级和回滚 Minecraft 版本、Loader、Mod、整合包及实例兼容成果的开放工程体系；同时提供面向外部开发 Agent 和人工开发者的兼容包协议、能力合同、管理接口、证据格式和可选辅助工具，使外部开发者能够独立完成兼容包的分析、实现、验证、安装、索引与维护，而不需要反复修改 MCAC Core。

核心原则：

```text
MCAC 提供稳定宿主与协议
外部 Agent 自主生产并维护兼容成果
SDK 只辅助，不限制
实现自由，合同严格
质量由真实验证、隔离、事务与回滚保障
兼容成果按实例动态组合并随时调用
```

---

# 30. 后续子规范

本文件是总工程规范。实施时可拆分：

1. `MCAC_COMPAT_PACK_SPEC.md`
2. `MCAC_COMPAT_HOST_ARCHITECTURE.md`
3. `MCAC_COMPAT_ISOLATION_SPEC.md`
4. `MCAC_COMPAT_RESOLUTION_RULES.md`
5. `MCAC_COMPAT_MANAGEMENT_API.md`
6. `MCAC_COMPAT_NATIVE_EXTENSION_SPEC.md`
7. `MCAC_COMPAT_EXTERNAL_AGENT_GUIDE.md`
8. `MCAC_COMPAT_EVIDENCE_SCHEMA.md`
9. `MCAC_COMPAT_ERROR_CATALOG.md`
10. `MCAC_COMPAT_SECURITY_MODEL.md`

这些子文件必须服从本工程总纲确定的职责边界。
