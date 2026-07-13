package com.mccompanion.terminal;

import com.mccompanion.terminal.install.InstallPlan;
import com.mccompanion.terminal.install.InstallTransaction;
import com.mccompanion.terminal.launcher.LoaderType;
import com.mccompanion.terminal.launcher.MinecraftInstance;
import com.mccompanion.terminal.runtime.PairingService;
import com.mccompanion.terminal.runtime.RuntimeProfile;
import com.mccompanion.terminal.runtime.WindowsRuntimeSupervisor;

import java.awt.Desktop;
import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;

/** Plain, non-ANSI interactive workflow that delegates to the same services as CLI commands. */
final class InteractiveTerminal {
    private final ControlTerminalMain root;
    private final BufferedReader input;
    private final PrintStream output;

    InteractiveTerminal(ControlTerminalMain root, InputStream input, PrintStream output) {
        this.root = root;
        this.input = new BufferedReader(new InputStreamReader(input, StandardCharsets.UTF_8));
        this.output = output;
    }

    void run() throws IOException {
        while (true) {
            output.println("""

                    Minecraft AI Companion Control Terminal

                    [1] 扫描启动器与实例
                    [2] 检查实例
                    [3] 安装或更新 Companion
                    [4] 启动游戏会话
                    [5] Runtime 与模型配置
                    [6] 连接与冒烟测试
                    [7] 日志与支持包
                    [8] 回滚或卸载
                    [0] 退出
                    """);
            String choice = ask("请选择: ");
            if (choice == null || choice.equals("0")) return;
            try {
                switch (choice) {
                    case "1" -> scan();
                    case "2" -> inspect();
                    case "3" -> install();
                    case "4" -> play();
                    case "5" -> runtime();
                    case "6" -> smoke();
                    case "7" -> logs();
                    case "8" -> remove();
                    default -> output.println("无效选择；输入 0 可安全退出。");
                }
            } catch (Exception failure) {
                output.println("操作未完成: " + safeMessage(failure));
            }
        }
    }

    private void scan() {
        output.println("启动器:");
        ControlTerminalMain.LauncherScan.print(root);
        output.println("实例:");
        ControlTerminalMain.InstanceScan.print(root);
    }

    private void inspect() throws Exception {
        MinecraftInstance instance = selectInstance();
        if (instance != null) root.printDoctor(instance);
    }

    private void install() throws Exception {
        MinecraftInstance instance = selectInstance();
        if (instance == null) return;
        if (instance.confidence() != com.mccompanion.terminal.launcher.DetectionConfidence.HIGH) {
            String value = ask("检测置信度不是 HIGH。请输入已确认的绝对 gameDir（0 取消）: ");
            if (cancelled(value)) return;
            instance = root.confirm(instance, Path.of(value));
        }
        InstallPlan plan = new InstallService().plan(instance, ControlTerminalMain.artifacts());
        showPlan(plan);
        if (!confirm("执行安装/更新？[y/N]: ")) return;
        InstallTransaction.Result result = new InstallService().install(plan);
        output.println("安装完成，SHA-256=" + result.sha256() + "，回滚点=" + result.rollbackId());
    }

    private void play() throws Exception {
        MinecraftInstance instance = selectInstance();
        if (instance == null) return;
        if (root.printDoctor(instance) == ControlTerminalMain.BLOCKED) return;
        RuntimeProfile profile = root.profile(instance);
        if (instance.loader() == LoaderType.FABRIC) {
            new PairingService().ensureConfigured(instance, profile);
            new WindowsRuntimeSupervisor().start(profile);
            output.println("Runtime 已通过身份健康检查，端口 " + profile.port());
        } else {
            output.println("当前 Loader 使用 LOCAL_ONLY 模式；将验证 Mod 加载日志。");
        }
        open(root.launcher(instance).executable());
        output.println("启动器已打开，等待游戏握手……");
        if (instance.loader() == LoaderType.FABRIC) {
            var state = new ConnectionService().waitForHandshake(profile, Duration.ofSeconds(90));
            output.println(state.connected() ? "握手成功。" : "等待超时；可进入菜单 6 重试连接测试。");
        }
    }

    private void runtime() throws Exception {
        MinecraftInstance instance = selectInstance();
        if (instance == null) return;
        RuntimeProfile profile = root.profile(instance);
        output.println("[1] 状态 [2] 启动 [3] 停止 [4] 重启 [5] 轮换 Token [6] Provider 状态 [7] 配置 Provider [8] 禁用 Provider [0] 返回");
        String choice = ask("请选择: ");
        if (cancelled(choice)) return;
        WindowsRuntimeSupervisor supervisor = new WindowsRuntimeSupervisor();
        switch (choice) {
            case "1" -> output.println(supervisor.status(profile));
            case "2" -> { new PairingService().ensureConfigured(instance, profile); supervisor.start(profile); output.println("Runtime 已启动。"); }
            case "3" -> { supervisor.stop(profile); output.println("Runtime 已停止。"); }
            case "4" -> { supervisor.stop(profile); new PairingService().ensureConfigured(instance, profile); supervisor.start(profile); output.println("Runtime 已重启。"); }
            case "5" -> { if (confirm("轮换失败时会自动回滚。继续？[y/N]: ")) { new TokenRotationService().rotate(instance, profile, Duration.ofSeconds(90)); output.println("Token 轮换与重连验证完成。"); } }
            case "6" -> output.println(new ProviderConfigurationService().status(profile).toPrettyString());
            case "7" -> configureProvider(profile);
            case "8" -> { if (confirm("禁用外部 Provider？[y/N]: ")) new ProviderConfigurationService().disable(profile); }
            default -> output.println("无效选择。");
        }
    }

    private void configureProvider(RuntimeProfile profile) throws Exception {
        String url = ask("OpenAI-compatible Base URL（0 取消）: ");
        if (cancelled(url)) return;
        String model = ask("模型名: ");
        String environment = ask("API Key 环境变量名（默认 MC_COMPANION_API_KEY）: ");
        if (environment == null || environment.isBlank()) environment = "MC_COMPANION_API_KEY";
        new ProviderConfigurationService().configure(profile, url, model, environment);
        output.println("配置已保存；API Key 未写入配置文件。");
        if (confirm("立即测试 Provider？[y/N]: ")) output.println(new ProviderConfigurationService().test(profile));
    }

    private void smoke() throws Exception {
        MinecraftInstance instance = selectInstance();
        if (instance == null) return;
        RuntimeProfile profile = root.profile(instance);
        var state = new ConnectionService().status(profile);
        output.println("Runtime/连接状态: " + state);
        SmokeTestService.Result result = new SmokeTestService().run(instance, profile);
        output.println(result.summary());
    }

    private void logs() throws Exception {
        MinecraftInstance instance = selectInstance();
        if (instance == null) return;
        output.println("[1] 查看 Minecraft 日志 [2] 查看 Runtime 日志 [3] 生成支持包 [0] 返回");
        String choice = ask("请选择: ");
        if (cancelled(choice)) return;
        switch (choice) {
            case "1" -> ControlTerminalMain.tail(instance.logsDirectory().resolve("latest.log"));
            case "2" -> ControlTerminalMain.tail(root.profile(instance).logFile());
            case "3" -> {
                String name = "mcac-support-" + instance.instanceId() + ".zip";
                Path target = Path.of(name).toAbsolutePath();
                new SupportBundleService().collect(instance, target);
                output.println("支持包已生成: " + target);
            }
            default -> output.println("无效选择。");
        }
    }

    private void remove() throws Exception {
        MinecraftInstance instance = selectInstance();
        if (instance == null) return;
        InstallTransaction transaction = new InstallTransaction();
        output.println("[1] 列出并回滚 [2] 卸载 Companion [0] 返回");
        String choice = ask("请选择: ");
        if (cancelled(choice)) return;
        if (choice.equals("1")) {
            List<String> points = transaction.rollbackPoints(instance.gameDirectory());
            if (points.isEmpty()) { output.println("没有可用回滚点。"); return; }
            for (int index = 0; index < points.size(); index++) output.printf("[%d] %s%n", index + 1, points.get(index));
            int selected = number(ask("选择回滚点（0 取消）: "), points.size());
            if (selected < 0 || !confirm("执行回滚？[y/N]: ")) return;
            transaction.rollback(instance.gameDirectory(), points.get(selected));
            output.println("回滚完成。");
        } else if (choice.equals("2") && confirm("仅删除 mcac 管理的 Mod 文件，继续？[y/N]: ")) {
            transaction.uninstall(instance.gameDirectory());
            output.println("卸载完成；存档和其他 Mod 未修改。");
        }
    }

    private MinecraftInstance selectInstance() throws IOException {
        List<MinecraftInstance> values = root.context.instances(root.roots());
        if (values.isEmpty()) { output.println("未发现实例。可先在菜单 1 扫描，或用 instance add 添加根目录。"); return null; }
        for (int index = 0; index < values.size(); index++) {
            MinecraftInstance value = values.get(index);
            output.printf("[%d] %s | Minecraft %s | %s | gameDir=%s | %s%n",
                    index + 1, value.displayName(), value.minecraftVersion(), value.loader(),
                    root.display(value.gameDirectory()), value.confidence());
        }
        int selected = number(ask("选择实例（0 取消）: "), values.size());
        return selected < 0 ? null : values.get(selected);
    }

    private void showPlan(InstallPlan plan) {
        output.println("安装计划（尚未写入）:");
        output.println("  实例: " + plan.instance().displayName());
        output.println("  gameDir: " + plan.instance().gameDirectory());
        output.println("  Artifact: " + plan.artifact().getFileName());
        output.println("  回滚点: " + plan.rollbackId());
        if (plan.fabricApiMissing()) output.println("  警告: Fabric API 缺失");
    }

    private String ask(String prompt) throws IOException {
        output.print(prompt);
        output.flush();
        String value = input.readLine();
        return value == null ? null : value.strip();
    }

    private boolean confirm(String prompt) throws IOException {
        String value = ask(prompt);
        return value != null && (value.equalsIgnoreCase("y") || value.equalsIgnoreCase("yes") || value.equals("是"));
    }

    private static int number(String value, int size) {
        if (cancelled(value)) return -1;
        try { int selected = Integer.parseInt(value); return selected >= 1 && selected <= size ? selected - 1 : -1; }
        catch (NumberFormatException invalid) { return -1; }
    }

    private static boolean cancelled(String value) { return value == null || value.isBlank() || value.equals("0"); }
    private static String safeMessage(Exception failure) {
        return failure.getMessage() == null ? failure.getClass().getSimpleName() : failure.getMessage();
    }

    private static void open(Path executable) throws IOException {
        if (Desktop.isDesktopSupported()) Desktop.getDesktop().open(executable.toFile());
        else new ProcessBuilder(executable.toString()).start();
    }
}
