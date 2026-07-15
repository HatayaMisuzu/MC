# Human-test instance audit

审计时间：2026-07-15  
授权测试根目录：`F:\wodeshijie\ceshi`  
审计方式：只读；未启动游戏、未写入配置、未读取账号凭据。

## 识别结果

- 启动器：Plain Craft Launcher 2（PCL2）。
- 游戏根目录：`F:\wodeshijie\ceshi\.minecraft`。
- 唯一识别实例：`versions\26.2`。
- Minecraft：`26.2`，Vanilla release，主类 `net.minecraft.client.main.Main`。
- Java 要求：25。
- Loader：未发现 Fabric、Forge 或 NeoForge。
- Mod：未发现 `mods` 目录或 Fabric API/MCAC 文件。
- 存档：未发现 `saves` 目录。
- MCAC：未发现已有安装、配置或日志。
- 运行状态：未发现从该根目录启动的 Java/PCL/Minecraft 进程。
- 启动器配置包含客户端 token 字段，但审计没有读取或记录其值；未发现认证数据库字段。

## 关键只读指纹

| 文件 | SHA-256 |
|---|---|
| `.minecraft\versions\26.2\26.2.json` | `0198AB96613A199D97C75627E1291C28D259EC8DDDE4571CB6151B47D3756A7B` |
| `.minecraft\launcher_profiles.json` | `2BC5FBDF4DE47C4E6ADE3F44D4A1A91614B42550E355258672445F353EB77BC7` |
| `.minecraft\PCL.ini` | `DFD41F6924EDF6E04316FD60F0CC593CFE47EBF5D3C068F5CEB19EFCB6D821B7` |

## 兼容性与风险

现有实例不是受支持的 Fabric 1.21.1 专用测试实例。原地降级、迁移或写入 Mod 会破坏现有启动器配置并违反测试边界，因此禁止修改。

## 隔离修改方案

后续需要真实实例时，只创建：

`F:\wodeshijie\ceshi\mcac-human-test-fabric-1.21.1`

该目录必须拥有独立的游戏目录、Fabric Loader、Fabric API、MCAC Mod、Runtime 配置、日志和测试世界；不得复用或修改 `versions\26.2`、个人存档或账号数据。创建前记录文件清单与哈希，创建后提供停止、卸载和删除隔离实例的回退入口。

当前阶段未执行任何实例修改。
