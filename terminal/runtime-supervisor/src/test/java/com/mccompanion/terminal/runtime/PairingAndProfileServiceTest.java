package com.mccompanion.terminal.runtime;

import static org.junit.jupiter.api.Assertions.*;

import com.mccompanion.terminal.launcher.*;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.nio.file.*;
import java.util.Optional;
import java.util.concurrent.*;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;

class PairingAndProfileServiceTest {
  @TempDir Path temp;

  MinecraftInstance instance(String id) throws Exception {
    Path game = temp.resolve("game-" + id);
    Files.createDirectories(game);
    return new MinecraftInstance(
        id,
        "pcl",
        "x",
        temp,
        temp,
        game,
        game.resolve("mods"),
        game.resolve("config"),
        game.resolve("logs"),
        "1.21.1",
        LoaderType.FABRIC,
        "1",
        21,
        Optional.empty(),
        InstanceIsolation.VERSION_DIRECTORY,
        DetectionConfidence.HIGH);
  }

  @Test
  void configureTwentyTimesKeepsToken() throws Exception {
    var i = instance("a");
    var p =
        new RuntimeProfileService(temp.resolve("home"), temp.resolve("runtime.exe")).ensure("a");
    var s = new PairingService();
    s.ensureConfigured(i, p);
    String before = Files.readString(p.profileDirectory().resolve("pairing.token"));
    for (int n = 0; n < 20; n++) s.ensureConfigured(i, p);
    assertEquals(before, Files.readString(p.profileDirectory().resolve("pairing.token")));
    assertTrue(s.tokensMatch(i, p));
  }

  @Test
  void profilesGetStableDistinctPorts() throws Exception {
    var service = new RuntimeProfileService(temp.resolve("home"), temp.resolve("runtime.exe"));
    var a = service.ensure("a");
    var b = service.ensure("b");
    assertEquals(8766, a.port());
    assertEquals(8767, b.port());
    assertEquals(a.port(), service.ensure("a").port());
  }

  @Test
  void rotateChangesBothTogether() throws Exception {
    var i = instance("a");
    var p =
        new RuntimeProfileService(temp.resolve("home"), temp.resolve("runtime.exe")).ensure("a");
    var s = new PairingService();
    s.ensureConfigured(i, p);
    String before = Files.readString(p.profileDirectory().resolve("pairing.token"));
    s.rotate(i, p);
    assertNotEquals(before, Files.readString(p.profileDirectory().resolve("pairing.token")));
    assertTrue(s.tokensMatch(i, p));
  }

  @Test
  void occupiedPortIsSkippedForNewProfile() throws Exception {
    try (ServerSocket occupied = new ServerSocket()) {
      occupied.bind(new InetSocketAddress(InetAddress.getLoopbackAddress(), 8766));
      var profile =
          new RuntimeProfileService(temp.resolve("home"), temp.resolve("runtime.exe")).ensure("a");
      assertEquals(8767, profile.port());
    }
  }

  @Test
  void concurrentAllocatorsNeverChooseSamePort() throws Exception {
    Path home = temp.resolve("home");
    ExecutorService pool = Executors.newFixedThreadPool(2);
    try {
      CountDownLatch ready = new CountDownLatch(2), go = new CountDownLatch(1);
      Callable<RuntimeProfile> first =
          () -> {
            ready.countDown();
            go.await();
            return new RuntimeProfileService(home, temp.resolve("runtime.exe")).ensure("a");
          };
      Callable<RuntimeProfile> second =
          () -> {
            ready.countDown();
            go.await();
            return new RuntimeProfileService(home, temp.resolve("runtime.exe")).ensure("b");
          };
      Future<RuntimeProfile> a = pool.submit(first), b = pool.submit(second);
      assertTrue(ready.await(5, TimeUnit.SECONDS));
      go.countDown();
      assertNotEquals(a.get(10, TimeUnit.SECONDS).port(), b.get(10, TimeUnit.SECONDS).port());
    } finally {
      pool.shutdownNow();
    }
  }
}
