package com.mccompanion.terminal;

import java.io.IOException;
import java.nio.file.FileVisitResult;
import java.nio.file.FileVisitor;
import java.nio.file.FileVisitOption;
import java.nio.file.FileSystem;
import java.nio.file.FileSystems;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.BasicFileAttributes;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;

/** Bounded Windows scan for portable PCL2/HMCL roots without reading account data. */
final class LauncherRootDiscoveryService {
  private static final Set<String> SKIP =
      Set.of(
          "windows",
          "program files",
          "program files (x86)",
          "$recycle.bin",
          "system volume information",
          "node_modules",
          ".git",
          ".gradle");
  private static volatile Cache cache = new Cache(Instant.EPOCH, List.of());

  List<Path> discover() {
    Cache value = cache;
    if (value.createdAt().plus(Duration.ofSeconds(30)).isAfter(Instant.now())) return value.roots();
    synchronized (LauncherRootDiscoveryService.class) {
      value = cache;
      if (value.createdAt().plus(Duration.ofSeconds(30)).isAfter(Instant.now())) return value.roots();
      List<Path> found = scan();
      cache = new Cache(Instant.now(), found);
      return found;
    }
  }

  void invalidate() {
    cache = new Cache(Instant.EPOCH, List.of());
  }

  private List<Path> scan() {
    Set<Path> found = new HashSet<>();
    List<Root> starts = new ArrayList<>();
    Path cwd = Path.of("").toAbsolutePath().normalize();
    starts.add(new Root(cwd, 2));
    Path home = Path.of(System.getProperty("user.home")).toAbsolutePath().normalize();
    starts.add(new Root(home, 5));
    if (isWindows()) {
      FileSystem fileSystem = FileSystems.getDefault();
      for (Path root : fileSystem.getRootDirectories()) {
        String drive = root.toString().toUpperCase(Locale.ROOT);
        if (!drive.startsWith(home.getRoot() == null ? "C:" : home.getRoot().toString().toUpperCase(Locale.ROOT))) {
          starts.add(new Root(root, 4));
        }
      }
    }
    Instant deadline = Instant.now().plusSeconds(8);
    AtomicInteger visited = new AtomicInteger();
    for (Root start : starts) {
      if (Instant.now().isAfter(deadline) || visited.get() > 60_000 || !Files.isDirectory(start.path())) break;
      try {
        Files.walkFileTree(
            start.path(),
            Set.of(),
            start.depth(),
            new FileVisitor<>() {
              @Override
              public FileVisitResult preVisitDirectory(Path directory, BasicFileAttributes attributes) {
                if (Instant.now().isAfter(deadline) || visited.incrementAndGet() > 60_000)
                  return FileVisitResult.TERMINATE;
                if (!directory.equals(start.path())
                    && SKIP.contains(directory.getFileName().toString().toLowerCase(Locale.ROOT)))
                  return FileVisitResult.SKIP_SUBTREE;
                if (Files.isRegularFile(directory.resolve("PCL/Setup.ini"))
                    || Files.isRegularFile(directory.resolve(".hmcl/hmcl.json"))) {
                  found.add(directory.toAbsolutePath().normalize());
                  return FileVisitResult.SKIP_SUBTREE;
                }
                return FileVisitResult.CONTINUE;
              }

              @Override
              public FileVisitResult visitFile(Path file, BasicFileAttributes attributes) {
                return FileVisitResult.CONTINUE;
              }

              @Override
              public FileVisitResult visitFileFailed(Path file, IOException failure) {
                return FileVisitResult.CONTINUE;
              }

              @Override
              public FileVisitResult postVisitDirectory(Path directory, IOException failure) {
                return FileVisitResult.CONTINUE;
              }
            });
      } catch (IOException ignored) {
      }
    }
    if (Files.isRegularFile(cwd.resolve("PCL/Setup.ini"))
        || Files.isRegularFile(cwd.resolve(".hmcl/hmcl.json"))) found.add(cwd);
    return found.stream().sorted().toList();
  }

  private static boolean isWindows() {
    return System.getProperty("os.name", "").toLowerCase(Locale.ROOT).contains("windows");
  }

  private record Root(Path path, int depth) {}

  private record Cache(Instant createdAt, List<Path> roots) {
    Cache {
      roots = List.copyOf(roots);
    }
  }
}
