# Minecraft AI Companion Control Terminal v1.0

Minecraft AI Companion 是一个不依赖 Numen 或 Baritone 的服务端同伴项目。v1.0 新增统一控制终端 `mcac`，负责发现 PCL2/HMCL、识别真实游戏目录、兼容诊断、事务安装、Runtime 管理、可回滚 Hook 和脱敏支持包。PCL2/HMCL 仍负责账号登录和 Minecraft 启动。

## 支持矩阵

| Minecraft | Loader | Java | Runtime |
|---|---|---:|---|
| 1.21.1 | Fabric | 21 | 完整握手与控制 |
| 1.21.1 | NeoForge | 21 | `LOCAL_ONLY` |
| 1.20.1 | Forge | 17 | `LOCAL_ONLY` |

终端只会精确匹配上表目标。其他版本可以扫描和诊断，但安装会被阻止。Runtime 不在线时，Mod 仍可加载并提供本地 `/companion` 命令。

## 构建

```powershell
cd D:\工作台\MC
.\gradlew.bat clean test
.\gradlew.bat buildPlatforms
.\gradlew.bat :terminal:terminal-app:installDist
.\gradlew.bat :runtime:runtime-app:installDist
```

生成便携分发目录：

```powershell
.\gradlew.bat packageTerminalZip
```

开发版入口位于 `terminal/terminal-app/build/install/mcac/bin/mcac.bat`。

## 快速使用

先执行只读扫描。启动器不在常用目录时，通过 `--root` 指定启动器所在目录：

```powershell
mcac --root "F:\wodeshijie\ceshi" launcher scan
mcac --root "F:\wodeshijie\ceshi" instance scan
mcac --root "F:\wodeshijie\ceshi" doctor
```

安装默认只显示计划；确认后才写文件：

```powershell
mcac --root <启动器目录> plan install <实例ID>
mcac --root <启动器目录> install <实例ID> --yes
mcac --root <启动器目录> rollback <实例ID> --id <回滚点> --yes
```

开发目录中可用 `--artifacts D:\工作台\MC` 指定构建产物；打包版会自动使用随包产物。安装器只管理清单记录的文件，不删除未知 Mod、存档或启动器配置。

Runtime 与会话：

```powershell
mcac --root <启动器目录> runtime start <实例ID>
mcac --root <启动器目录> runtime status <实例ID>
mcac --root <启动器目录> play <实例ID>
```

可选 Hook 必须显式确认，且启动器运行时禁止写入：

```powershell
mcac --root <启动器目录> hook install <实例ID> --mcac-executable <mcac.exe或mcac.bat> --yes
mcac --root <启动器目录> hook remove <实例ID> --yes
```

生成脱敏支持包：

```powershell
mcac --root <启动器目录> logs collect <实例ID>
```

终端不会读取启动器账号文件、Microsoft/Mojang 登录凭据或聊天记录，也不会修改启动器 EXE、Minecraft 版本 JAR 或 libraries。

详细命令见 [docs/CONTROL_TERMINAL.md](docs/CONTROL_TERMINAL.md)。
