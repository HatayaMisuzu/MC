package com.mccompanion.terminal;

import com.mccompanion.terminal.diagnostics.DiagnosticEngine;
import com.mccompanion.terminal.diagnostics.DiagnosticResult;
import com.mccompanion.terminal.install.*;
import com.mccompanion.terminal.launcher.LauncherInstallation;
import com.mccompanion.terminal.launcher.MinecraftInstance;
import com.mccompanion.terminal.runtime.PairingService;
import com.mccompanion.terminal.runtime.RuntimeProfile;
import com.mccompanion.terminal.runtime.WindowsRuntimeSupervisor;
import picocli.CommandLine;
import picocli.CommandLine.Command;
import picocli.CommandLine.Option;
import picocli.CommandLine.Parameters;
import java.awt.Desktop;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.concurrent.Callable;

@Command(name = "mcac", mixinStandardHelpOptions = true, version = "mcac 1.0",
        description = "Minecraft AI Companion Control Terminal",
        subcommands = {ControlTerminalMain.LauncherCommand.class, ControlTerminalMain.InstanceCommand.class,
                ControlTerminalMain.DoctorCommand.class, ControlTerminalMain.PlanCommand.class,
                ControlTerminalMain.InstallCommand.class, ControlTerminalMain.RollbackCommand.class,
                ControlTerminalMain.RuntimeCommand.class, ControlTerminalMain.PlayCommand.class,
                ControlTerminalMain.HookCommand.class, ControlTerminalMain.LogsCommand.class})
public final class ControlTerminalMain implements Runnable {
    @Option(names = "--root", description = "PCL2/HMCL root or executable (repeatable)")
    List<Path> suppliedRoots = new ArrayList<>();
    @Option(names = "--verbose-paths", description = "Show complete local paths") boolean verbosePaths;
    final TerminalContext context = new TerminalContext();

    public static void main(String[] args) {
        CommandLine command = new CommandLine(new ControlTerminalMain());
        command.setExecutionExceptionHandler((failure, line, parsed) -> {
            String message = failure.getMessage();
            String prefix = failure instanceof IOException || failure instanceof IllegalArgumentException ? "BLOCKED" : "ERROR";
            line.getErr().println(prefix + ": " + (message == null || message.isBlank() ? failure.getClass().getSimpleName() : message));
            line.getErr().println("No uncommitted file changes were retained.");
            return 1;
        });
        System.exit(command.execute(args));
    }
    @Override public void run() {
        System.out.println("Minecraft AI Companion Control Terminal");
        System.out.println("Use 'mcac --help'. First scan is read-only: mcac --root <launcher-dir> instance scan");
    }
    List<Path> roots() {
        List<Path> values = new ArrayList<>(suppliedRoots);
        String environment = System.getenv("MCAC_SCAN_ROOTS");
        if (environment != null) for (String item : environment.split(java.io.File.pathSeparator)) if (!item.isBlank()) values.add(Path.of(item));
        if (values.isEmpty()) values.add(Path.of("."));
        return values.stream().map(path -> path.toAbsolutePath().normalize()).distinct().toList();
    }
    String display(Path path) {
        if (verbosePaths) return path.toString();
        Path name = path.getFileName();
        Path parent = path.getParent();
        return parent == null || name == null ? "<local>" : "...\\" + parent.getFileName() + "\\" + name;
    }

    @Command(name="launcher", subcommands={LauncherScan.class, LauncherList.class}) static class LauncherCommand implements Runnable {
        @CommandLine.ParentCommand ControlTerminalMain root; public void run(){ new CommandLine(this).usage(System.out); }
    }
    @Command(name="scan", description="Discover PCL2 and HMCL without writing files") static class LauncherScan implements Callable<Integer> {
        @CommandLine.ParentCommand LauncherCommand parent;
        public Integer call(){ return print(parent.root); }
        static int print(ControlTerminalMain root) {
            List<LauncherInstallation> values=root.context.launchers(root.roots());
            if(values.isEmpty()){System.out.println("No supported launcher found.");return 0;}
            for(var v:values) System.out.printf("%s  %s  version=%s  root=%s  confidence=%s%n",v.launcherId(),v.type(),v.detectedVersion(),root.display(v.executable().getParent()),v.confidence());
            return 0;
        }
    }
    @Command(name="list") static class LauncherList extends LauncherScan {}

    @Command(name="instance", subcommands={InstanceScan.class, InstanceList.class, InstanceInspect.class}) static class InstanceCommand implements Runnable {
        @CommandLine.ParentCommand ControlTerminalMain root; public void run(){new CommandLine(this).usage(System.out);}
    }
    @Command(name="scan", description="Discover real isolated game directories") static class InstanceScan implements Callable<Integer> {
        @CommandLine.ParentCommand InstanceCommand parent;
        public Integer call(){return print(parent.root);}
        static int print(ControlTerminalMain root){var values=root.context.instances(root.roots());if(values.isEmpty()){System.out.println("No Minecraft instance found.");return 0;} for(var v:values) printOne(root,v);return 0;}
        static void printOne(ControlTerminalMain root,MinecraftInstance v){System.out.printf("%s  %s  Minecraft %s / %s %s  Java %s  gameDir=%s  confidence=%s%n",v.instanceId(),v.displayName(),v.minecraftVersion(),v.loader(),v.loaderVersion(),v.requiredJavaMajor()==0?"unknown":v.requiredJavaMajor(),root.display(v.gameDirectory()),v.confidence());}
    }
    @Command(name="list") static class InstanceList extends InstanceScan {}
    @Command(name="inspect") static class InstanceInspect implements Callable<Integer>{@CommandLine.ParentCommand InstanceCommand parent;@Parameters(index="0")String instance;public Integer call(){InstanceScan.printOne(parent.root,parent.root.context.requireInstance(parent.root.roots(),instance));return 0;}}

    @Command(name="doctor", description="Run compatibility and safety diagnostics") static class DoctorCommand implements Callable<Integer>{
        @CommandLine.ParentCommand ControlTerminalMain root; @Parameters(index="0",arity="0..1") String instance;
        public Integer call(){List<MinecraftInstance> values=instance==null?root.context.instances(root.roots()):List.of(root.context.requireInstance(root.roots(),instance));int exit=0;for(var value:values){System.out.println(value.displayName()+" ["+value.instanceId()+"]");for(DiagnosticResult r:new DiagnosticEngine().run(value)){System.out.printf("  %-7s %-28s %s%n",r.severity(),r.code(),r.summary());if(r.severity()==DiagnosticResult.Severity.BLOCKED)exit=2;}}return exit;}}

    @Command(name="plan", subcommands=PlanInstall.class) static class PlanCommand implements Runnable{@CommandLine.ParentCommand ControlTerminalMain root;public void run(){new CommandLine(this).usage(System.out);}}
    @Command(name="install") static class PlanInstall implements Callable<Integer>{@CommandLine.ParentCommand PlanCommand parent;@Parameters(index="0")String instance;@Option(names="--artifacts")Path artifacts;public Integer call()throws Exception{MinecraftInstance value=parent.root.context.requireInstance(parent.root.roots(),instance);InstallPlan plan=makePlan(value,artifacts==null?defaultArtifactsRoot():artifacts);printPlan(parent.root,plan);return 0;}}

    @Command(name="install", aliases={"update","repair"}, description="Transactionally install the exact matching Companion JAR") static class InstallCommand implements Callable<Integer>{
        @CommandLine.ParentCommand ControlTerminalMain root;@Parameters(index="0")String instance;@Option(names="--artifacts")Path artifacts;@Option(names="--yes")boolean yes;@Option(names="--dry-run")boolean dryRun;
        public Integer call()throws Exception{MinecraftInstance value=root.context.requireInstance(root.roots(),instance);InstallPlan plan=makePlan(value,artifacts==null?defaultArtifactsRoot():artifacts);printPlan(root,plan);if(dryRun)return 0;if(!yes){System.err.println("No files changed. Re-run with --yes after reviewing the plan.");return 2;}var result=new InstallTransaction().execute(plan);System.out.println("PASS installed sha256="+result.sha256()+" rollback="+result.rollbackId());return 0;}}

    @Command(name="rollback", aliases="uninstall") static class RollbackCommand implements Callable<Integer>{@CommandLine.ParentCommand ControlTerminalMain root;@Parameters(index="0")String instance;@Option(names="--id",required=true)String id;@Option(names="--yes")boolean yes;public Integer call()throws Exception{if(!yes){System.err.println("No files changed. Add --yes to confirm rollback.");return 2;}MinecraftInstance value=root.context.requireInstance(root.roots(),instance);new InstallTransaction().rollback(value.gameDirectory(),id);System.out.println("PASS rollback complete");return 0;}}

    @Command(name="runtime", subcommands={RuntimeStatus.class,RuntimeStart.class,RuntimeStop.class,RuntimeRestart.class}) static class RuntimeCommand implements Runnable{@CommandLine.ParentCommand ControlTerminalMain root;public void run(){new CommandLine(this).usage(System.out);}}
    abstract static class RuntimeBase { @CommandLine.ParentCommand RuntimeCommand parent;@Parameters(index="0")String instance;RuntimeProfile profile(MinecraftInstance value){Path home=controlHome().resolve("profiles").resolve(value.instanceId());return new RuntimeProfile(value.instanceId(),home,locateRuntimeScript(),8766);}Path controlHome(){return ControlTerminalMain.controlHome();}MinecraftInstance value(){return parent.root.context.requireInstance(parent.root.roots(),instance);}}
    @Command(name="status") static class RuntimeStatus extends RuntimeBase implements Callable<Integer>{public Integer call(){RuntimeProfile p=profile(value());var s=new WindowsRuntimeSupervisor();System.out.println(s.portOnline(p)?"ONLINE":"OFFLINE");s.activePid(p).ifPresent(pid->System.out.println("pid="+pid));return 0;}}
    @Command(name="start") static class RuntimeStart extends RuntimeBase implements Callable<Integer>{public Integer call()throws Exception{MinecraftInstance v=value();RuntimeProfile p=profile(v);if(!Files.isRegularFile(p.launcherScript()))throw new IOException("Runtime distribution missing; run :runtime:runtime-app:installDist");new PairingService().configure(v,p);new WindowsRuntimeSupervisor().start(p);System.out.println("Runtime start requested for "+v.instanceId());return 0;}}
    @Command(name="stop") static class RuntimeStop extends RuntimeBase implements Callable<Integer>{public Integer call()throws Exception{new WindowsRuntimeSupervisor().stop(profile(value()));System.out.println("Runtime stopped");return 0;}}
    @Command(name="restart") static class RuntimeRestart extends RuntimeBase implements Callable<Integer>{public Integer call()throws Exception{RuntimeProfile p=profile(value());var s=new WindowsRuntimeSupervisor();s.stop(p);s.start(p);System.out.println("Runtime restarted");return 0;}}

    @Command(name="play", description="Start Runtime and open the owning launcher; launcher remains responsible for login") static class PlayCommand implements Callable<Integer>{@CommandLine.ParentCommand ControlTerminalMain root;@Parameters(index="0")String instance;public Integer call()throws Exception{MinecraftInstance value=root.context.requireInstance(root.roots(),instance);LauncherInstallation launcher=root.context.launchers(root.roots()).stream().filter(v->v.launcherId().equals(value.launcherId())).findFirst().orElseThrow();if(value.loader()==com.mccompanion.terminal.launcher.LoaderType.FABRIC&&value.minecraftVersion().equals("1.21.1")){RuntimeProfile profile=new RuntimeProfile(value.instanceId(),controlHome().resolve("profiles").resolve(value.instanceId()),locateRuntimeScript(),8766);if(Files.isRegularFile(profile.launcherScript())){new PairingService().configure(value,profile);new WindowsRuntimeSupervisor().start(profile);System.out.println("Runtime start requested on 127.0.0.1:8766");}else System.out.println("WARNING Runtime distribution not found; Mod remains LOCAL_ONLY");}else System.out.println("Runtime bridge unavailable for this target; using LOCAL_ONLY mode");if(Desktop.isDesktopSupported())Desktop.getDesktop().open(launcher.executable().toFile());else new ProcessBuilder(launcher.executable().toString()).start();System.out.println("Launcher opened. Select "+value.displayName()+"; connection is authoritative only after Mod/Runtime handshake.");return 0;}}

    @Command(name="hook", subcommands={HookInstall.class,HookStatus.class,HookRemove.class}) static class HookCommand implements Runnable{@CommandLine.ParentCommand ControlTerminalMain root;public void run(){new CommandLine(this).usage(System.out);}}
    abstract static class HookBase { @CommandLine.ParentCommand HookCommand parent;@Parameters(index="0")String instance;MinecraftInstance value(){return parent.root.context.requireInstance(parent.root.roots(),instance);}LauncherInstallation launcher(MinecraftInstance value){return parent.root.context.launchers(parent.root.roots()).stream().filter(v->v.launcherId().equals(value.launcherId())).findFirst().orElseThrow();}Path home(){String local=System.getenv("LOCALAPPDATA");return Path.of(local==null?System.getProperty("user.home"):local,"MinecraftAICompanion");}}
    @Command(name="status") static class HookStatus extends HookBase implements Callable<Integer>{public Integer call(){MinecraftInstance v=value();System.out.println(new HookService().status(v,home()));return 0;}}
    @Command(name="install") static class HookInstall extends HookBase implements Callable<Integer>{@Option(names="--yes")boolean yes;@Option(names="--mcac-executable",description="Installed mcac.exe or mcac.bat path")Path executable=Path.of("mcac.exe");public Integer call()throws Exception{MinecraftInstance v=value();LauncherInstallation l=launcher(v);System.out.println("Hook plan: backup and update "+parent.root.display(l.type()==com.mccompanion.terminal.launcher.LauncherType.PCL2?v.versionDirectory().resolve("PCL/Setup.ini"):l.dataDirectory().resolve("hmcl.json")));if(!yes){System.out.println("No files changed. Add --yes to confirm.");return 2;}new HookService().install(v,l,executable,home());System.out.println("PASS hook installed; failure will not block Minecraft startup");return 0;}}
    @Command(name="remove") static class HookRemove extends HookBase implements Callable<Integer>{@Option(names="--yes")boolean yes;public Integer call()throws Exception{if(!yes){System.out.println("No files changed. Add --yes to confirm.");return 2;}MinecraftInstance v=value();new HookService().remove(v,launcher(v),home());System.out.println("PASS hook removed and original config restored");return 0;}}

    @Command(name="logs", subcommands=LogsCollect.class) static class LogsCommand implements Runnable{@CommandLine.ParentCommand ControlTerminalMain root;public void run(){new CommandLine(this).usage(System.out);}}
    @Command(name="collect", description="Create an allow-listed, redacted support ZIP") static class LogsCollect implements Callable<Integer>{@CommandLine.ParentCommand LogsCommand parent;@Parameters(index="0")String instance;@Option(names="--output")Path output;public Integer call()throws Exception{MinecraftInstance value=parent.root.context.requireInstance(parent.root.roots(),instance);Path target=output==null?Path.of("mcac-support-"+value.instanceId()+".zip").toAbsolutePath():output.toAbsolutePath();new SupportBundleService().collect(value,target);System.out.println("Support bundle: "+target);return 0;}}

    static InstallPlan makePlan(MinecraftInstance instance,Path artifacts)throws Exception{if(!InstallPlanner.isSupported(instance))throw new IOException("Unsupported target: Minecraft "+instance.minecraftVersion()+" / "+instance.loader()+". Supported: Fabric 1.21.1, NeoForge 1.21.1, Forge 1.20.1.");Path artifact=new ArtifactResolver().resolve(instance,artifacts.toAbsolutePath().normalize()).orElseThrow(()->new IOException("No exact artifact for "+instance.minecraftVersion()+" / "+instance.loader()));return new InstallPlanner().plan(instance,artifact);}
    static void printPlan(ControlTerminalMain root,InstallPlan plan){System.out.println("Install plan (no changes yet)");System.out.println("  instance: "+plan.instance().displayName()+" ["+plan.instance().minecraftVersion()+" / "+plan.instance().loader()+"]");System.out.println("  gameDir: "+root.display(plan.instance().gameDirectory()));System.out.println("  add: "+plan.artifact().getFileName()+" -> mods/");for(Path old:plan.replacedFiles())System.out.println("  backup: "+old.getFileName());if(plan.fabricApiMissing())System.out.println("  WARNING: Fabric API is missing; automatic dependency download is disabled.");System.out.println("  rollback: "+plan.rollbackId());}
    static Path controlHome(){String local=System.getenv("LOCALAPPDATA");return Path.of(local==null?System.getProperty("user.home"):local,"MinecraftAICompanion");}
    static Path locateRuntimeScript(){Path project=Path.of("").toAbsolutePath();Path development=project.resolve("runtime/runtime-app/build/install/runtime-app/bin/runtime-app.bat");if(Files.isRegularFile(development))return development;try{Path jar=Path.of(ControlTerminalMain.class.getProtectionDomain().getCodeSource().getLocation().toURI());Path app=Files.isDirectory(jar)?jar:jar.getParent();Path image=app.getParent();Path runtimeExe=image.resolve("runtime-app.exe");if(Files.isRegularFile(runtimeExe))return runtimeExe;Path bundled=image.resolve("runtime-app-bundled.cmd");if(Files.isRegularFile(bundled))return bundled;Path terminal=jar.getParent().getParent();Path sibling=terminal.getParent().resolve("runtime-app/bin/runtime-app.bat");if(Files.isRegularFile(sibling))return sibling;}catch(Exception ignored){}return project.resolve("runtime-app/bin/runtime-app.bat");}
    static Path defaultArtifactsRoot(){Path project=Path.of("").toAbsolutePath();if(Files.isDirectory(project.resolve("minecraft")))return project;try{Path jar=Path.of(ControlTerminalMain.class.getProtectionDomain().getCodeSource().getLocation().toURI());Path app=Files.isDirectory(jar)?jar:jar.getParent();Path image=app.getParent();if(Files.isDirectory(image.resolve("artifacts")))return image.resolve("artifacts");Path root=jar.getParent().getParent().getParent();if(Files.isDirectory(root.resolve("artifacts")))return root.resolve("artifacts");}catch(Exception ignored){}return project.resolve("artifacts");}
}
