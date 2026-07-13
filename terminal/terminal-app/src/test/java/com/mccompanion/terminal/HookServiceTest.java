package com.mccompanion.terminal;

import static org.junit.jupiter.api.Assertions.*;

import com.mccompanion.terminal.launcher.*;
import java.nio.file.*;
import java.util.*;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;

class HookServiceTest {
  @TempDir Path temp;

  MinecraftInstance instance(Path root) throws Exception {
    Path version = root.resolve(".minecraft/versions/Test");
    Files.createDirectories(version.resolve("PCL"));
    return new MinecraftInstance(
        "id",
        "launcher",
        "Test",
        root.resolve(".minecraft"),
        version,
        version,
        version.resolve("mods"),
        version.resolve("config"),
        version.resolve("logs"),
        "1.21.1",
        LoaderType.FABRIC,
        "1",
        21,
        Optional.empty(),
        InstanceIsolation.VERSION_DIRECTORY,
        DetectionConfidence.HIGH);
  }

  LauncherInstallation launcher(Path root, LauncherType type) throws Exception {
    Path exe = root.resolve(type == LauncherType.PCL2 ? "PCL.exe" : "HMCL.exe");
    Files.createDirectories(exe.getParent());
    Files.createFile(exe);
    Path data = type == LauncherType.PCL2 ? root.resolve("PCL") : root.resolve(".hmcl");
    Files.createDirectories(data);
    return new LauncherInstallation(
        "launcher",
        type,
        "",
        exe,
        data,
        List.of(root.resolve(".minecraft")),
        DetectionConfidence.HIGH,
        Map.of());
  }

  @Test
  void pclSpecialExistingCommandIsIsolated() throws Exception {
    var i = instance(temp);
    var l = launcher(temp, LauncherType.PCL2);
    String command = "echo 中文 & (echo x) ^| find \"x\"";
    Files.writeString(
        i.versionDirectory().resolve("PCL/Setup.ini"), "VersionAdvanceRun:" + command + "\n");
    Path home = temp.resolve("home");
    new HookService().install(i, l, temp.resolve("mcac.cmd"), home);
    Path state = home.resolve("hooks/id");
    assertTrue(
        Files.readString(state.resolve("pcl-prelaunch.cmd")).contains("user-existing-hook.cmd"));
    assertTrue(Files.readString(state.resolve("user-existing-hook.cmd")).contains(command));
    new HookService().remove(i, l, home);
    assertTrue(Files.readString(i.versionDirectory().resolve("PCL/Setup.ini")).contains(command));
  }

  @Test
  void hmclDuplicateSelectedVersionIsBlocked() throws Exception {
    var i = instance(temp);
    var l = launcher(temp, LauncherType.HMCL);
    Files.writeString(
        l.dataDirectory().resolve("hmcl.json"),
        "{\"configurations\":{\"a\":{\"selectedMinecraftVersion\":\"Test\"},\"b\":{\"selectedMinecraftVersion\":\"Test\"}}}");
    assertThrows(
        java.io.IOException.class,
        () -> new HookService().install(i, l, temp.resolve("mcac.cmd"), temp.resolve("home")));
  }

  @Test
  void pclWrapperActuallyRunsBothHooksAndNeverBlocksGame() throws Exception {
    Assumptions.assumeTrue(System.getProperty("os.name").startsWith("Windows"));
    Path special = temp.resolve("中文 space & (paren)");
    Files.createDirectories(special);
    var i = instance(special);
    var l = launcher(special, LauncherType.PCL2);
    Path userMarker = special.resolve("home/hooks/id/user-marker.txt"),
        mcacMarker = special.resolve("home/hooks/id/mcac-marker.txt"),
        fake = special.resolve("fake mcac.cmd");
    Files.writeString(fake, "@echo off\r\necho mcac>mcac-marker.txt\r\nexit /b 5\r\n");
    String command = "echo user>user-marker.txt & (echo x) ^| find \"x\" >nul & exit /b 9";
    Files.writeString(
        i.versionDirectory().resolve("PCL/Setup.ini"), "VersionAdvanceRun:" + command + "\n");
    Path home = special.resolve("home");
    HookService service = new HookService();
    service.install(i, l, fake, home);
    service.install(i, l, fake, home);
    Path wrapper = home.resolve("hooks/id/pcl-prelaunch.cmd");
    Process process =
        new ProcessBuilder("cmd.exe", "/d", "/c", "call pcl-prelaunch.cmd")
            .directory(wrapper.getParent().toFile())
            .redirectErrorStream(true)
            .start();
    assertTrue(process.waitFor(10, java.util.concurrent.TimeUnit.SECONDS), "wrapper timed out");
    String processOutput =
        new String(
            process.getInputStream().readAllBytes(), java.nio.charset.Charset.defaultCharset());
    assertEquals(0, process.exitValue(), processOutput);
    long limit = System.nanoTime() + java.time.Duration.ofSeconds(5).toNanos();
    while (!Files.exists(mcacMarker) && System.nanoTime() < limit) Thread.sleep(25);
    assertTrue(
        Files.exists(userMarker),
        "existing user hook did not execute; wrapper="
            + Files.readString(wrapper)
            + " user="
            + Files.readString(wrapper.resolveSibling("user-existing-hook.cmd")));
    assertTrue(
        Files.exists(mcacMarker),
        "mcac hook did not execute; wrapper=" + Files.readString(wrapper));
    service.remove(i, l, home);
    assertTrue(Files.readString(i.versionDirectory().resolve("PCL/Setup.ini")).contains(command));
  }
}
