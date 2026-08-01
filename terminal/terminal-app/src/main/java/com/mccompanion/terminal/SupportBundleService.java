package com.mccompanion.terminal;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.mccompanion.terminal.diagnostics.BoundedTextReader;
import com.mccompanion.terminal.diagnostics.DiagnosticEngine;
import com.mccompanion.terminal.diagnostics.DiagnosticResult;
import com.mccompanion.terminal.launcher.MinecraftInstance;
import com.mccompanion.terminal.runtime.RuntimeProfile;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;
import java.util.zip.ZipOutputStream;

/** Creates a bounded, allow-listed, redacted, verified, and atomically published support archive. */
final class SupportBundleService {
  private static final ObjectMapper JSON = new ObjectMapper();
  private static final PrivacyFilter PRIVACY = new PrivacyFilter();
  private static final int MAX_SOURCE_BYTES = 16 * 1024 * 1024;
  private static final long MAX_ARCHIVE_UNCOMPRESSED_BYTES = 64L * 1024 * 1024;
  private static final Set<String> REQUIRED_ENTRIES = Set.of(
      "summary.txt", "reproduction-steps.txt", "mods.txt", "bundle-manifest.json");
  private final ArchiveHook archiveHook;

  SupportBundleService() {
    this(ignored -> { });
  }

  SupportBundleService(ArchiveHook archiveHook) {
    this.archiveHook = java.util.Objects.requireNonNull(archiveHook, "archiveHook");
  }

  Path collect(MinecraftInstance instance, Path output) throws IOException {
    return collect(instance, null, List.of(), output);
  }

  Path collect(MinecraftInstance instance, RuntimeProfile profile, Path output) throws IOException {
    return collect(instance, profile, List.of(), output);
  }

  Path collect(MinecraftInstance instance, RuntimeProfile profile, List<DiagnosticResult> doctor,
               Path output) throws IOException {
    Path target = output.toAbsolutePath().normalize();
    Files.createDirectories(target.getParent());
    Path part = target.resolveSibling(target.getFileName() + "." + UUID.randomUUID() + ".part");
    try {
      writeArchive(instance, profile, doctor, part);
      archiveHook.afterArchiveWritten(part);
      verifySanitized(part);
      try {
        Files.move(part, target, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
      } catch (AtomicMoveNotSupportedException unsupported) {
        throw new IOException("SUPPORT_BUNDLE_ATOMIC_MOVE_UNAVAILABLE", unsupported);
      }
      return target;
    } finally {
      Files.deleteIfExists(part);
    }
  }

  private static void writeArchive(MinecraftInstance instance, RuntimeProfile profile,
                                   List<DiagnosticResult> doctor, Path part) throws IOException {
    ArrayNode sources = JSON.createArrayNode();
    try (ZipOutputStream zip = new ZipOutputStream(Files.newOutputStream(part,
        StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE))) {
      StringBuilder summary = new StringBuilder("Minecraft=").append(instance.minecraftVersion()).append('\n')
          .append("Loader=").append(instance.loader()).append(' ').append(instance.loaderVersion()).append('\n')
          .append("Java=").append(instance.requiredJavaMajor()).append('\n')
          .append("Instance=<INSTANCE>\n");
      new DiagnosticEngine().run(instance).forEach(result -> summary.append(result.severity()).append(' ')
          .append(result.code()).append(' ').append(result.summary()).append('\n'));
      add(zip, "summary.txt", shareable(summary.toString()));

      Path installManifest = instance.gameDirectory().resolve(".mccompanion/install-manifest.json");
      BoundedTextReader.Result installText = readSource(installManifest, "install-manifest", sources);
      if (installText != null) add(zip, "install-manifest.json", shareable(installText.text()));

      Path latest = instance.logsDirectory().resolve("latest.log");
      BoundedTextReader.Result minecraftLog = readSource(latest, "minecraft-latest-log", sources);
      if (minecraftLog != null) {
        add(zip, "minecraft-errors.log", shareable(safeErrorExcerpt(minecraftLog.text())));
      }

      if (profile != null) {
        String runtimeSummary = "Profile=<PROFILE>\nConfigured=" + Files.isDirectory(profile.profileDirectory())
            + "\nPort=" + profile.port() + "\nHealthPort=" + profile.healthPort() + "\n";
        add(zip, "runtime-summary.txt", shareable(runtimeSummary));
        add(zip, "safe-config-summary.txt", shareable(configurationSummary(profile)));
        BoundedTextReader.Result runtimeLog = readSource(profile.logFile(), "runtime-log", sources);
        if (runtimeLog != null) {
          add(zip, "runtime-errors.log", shareable(safeErrorExcerpt(runtimeLog.text())));
        }
      }

      if (!doctor.isEmpty()) {
        StringBuilder checks = new StringBuilder();
        doctor.forEach(result -> checks.append(result.severity()).append(' ').append(result.code())
            .append(' ').append(result.summary()).append(' ').append(result.evidence()).append('\n'));
        add(zip, "doctor.txt", shareable(checks.toString()));
      }

      add(zip, "reproduction-steps.txt", "1. Open the MCAC control terminal.\n"
          + "2. Select the affected instance and run Doctor.\n"
          + "3. Repeat the failed confirmed operation.\n"
          + "4. Record the visible operation reason code and approximate time.\n");
      StringBuilder mods = new StringBuilder();
      if (Files.isDirectory(instance.modsDirectory())) {
        try (var files = Files.newDirectoryStream(instance.modsDirectory(), "*.jar")) {
          for (Path file : files) mods.append(file.getFileName()).append('\n');
        }
      }
      add(zip, "mods.txt", shareable(mods.toString()));

      ObjectNode manifest = JSON.createObjectNode()
          .put("format", "mcac-support-bundle/2")
          .put("privacyPolicy", PrivacyFilter.Policy.SHAREABLE_BUNDLE.name())
          .put("atomicPublication", true)
          .put("maxSourceBytes", MAX_SOURCE_BYTES);
      manifest.set("sources", sources);
      add(zip, "bundle-manifest.json", JSON.writerWithDefaultPrettyPrinter().writeValueAsString(manifest));
    }
  }

  private static BoundedTextReader.Result readSource(Path path, String logicalName, ArrayNode sources)
      throws IOException {
    if (!Files.isRegularFile(path)) return null;
    BoundedTextReader.Result result = BoundedTextReader.readTail(path, MAX_SOURCE_BYTES);
    sources.addObject()
        .put("name", logicalName)
        .put("charset", result.charset())
        .put("replacementCount", result.replacementCount())
        .put("truncated", result.truncated())
        .put("bytesRead", result.bytesRead())
        .put("sourceBytes", result.sourceBytes());
    return result;
  }

  private static String configurationSummary(RuntimeProfile profile) throws IOException {
    var brain = new BrainConfigurationService().status(profile);
    var search = new SearchConfigurationService().status(profile);
    String brainEnvironment = brain.path("tokenEnv").asText("");
    String searchEnvironment = search.path("tokenEnv").asText("");
    return "BrainMode=" + brain.path("mode").asText("disabled") + '\n'
        + "BrainModel=" + brain.path("model").asText("disabled") + '\n'
        + "BrainCredentialReference=" + (brainEnvironment.isBlank() ? "not-required" : brainEnvironment) + '\n'
        + "BrainCredentialPresent=" + (!brainEnvironment.isBlank() && present(brainEnvironment)) + '\n'
        + "SearchMode=" + search.path("mode").asText("disabled") + '\n'
        + "SearchCredentialReference=" + (searchEnvironment.isBlank() ? "not-required" : searchEnvironment) + '\n'
        + "SearchCredentialPresent=" + (!searchEnvironment.isBlank() && present(searchEnvironment)) + '\n';
  }

  private static boolean present(String environment) {
    String value = System.getenv(environment);
    return value != null && !value.isBlank();
  }

  private static String safeErrorExcerpt(String text) {
    String[] lines = text.split("\\R");
    StringBuilder result = new StringBuilder();
    int included = 0;
    for (int index = Math.max(0, lines.length - 2_000); index < lines.length && included < 200; index++) {
      String line = lines[index];
      String lower = line.toLowerCase(java.util.Locale.ROOT);
      boolean error = lower.contains("error") || lower.contains("warn") || lower.contains("exception")
          || lower.contains("caused by") || lower.matches("^\\s*at\\s+.*");
      boolean privateChat = lower.contains("[chat]") || lower.contains("chat message")
          || lower.matches(".*<[^>]{1,64}>.*");
      if (error && !privateChat) {
        result.append(line, 0, Math.min(line.length(), 4_096)).append('\n');
        included++;
      }
    }
    return "ErrorLines=" + included + '\n' + result;
  }

  private static void verifySanitized(Path output) throws IOException {
    Set<String> names = new HashSet<>();
    long total = 0;
    try (ZipFile zip = new ZipFile(output.toFile())) {
      var entries = zip.entries();
      while (entries.hasMoreElements()) {
        var entry = entries.nextElement();
        if (entry.isDirectory()) continue;
        if (!names.add(entry.getName())) throw new IOException("SUPPORT_BUNDLE_DUPLICATE_ENTRY");
        if (entry.getName().contains("..") || entry.getName().startsWith("/")) {
          throw new IOException("SUPPORT_BUNDLE_UNSAFE_ENTRY");
        }
        byte[] bytes = zip.getInputStream(entry).readAllBytes();
        total += bytes.length;
        if (total > MAX_ARCHIVE_UNCOMPRESSED_BYTES) throw new IOException("SUPPORT_BUNDLE_TOO_LARGE");
        String text = new String(bytes, StandardCharsets.UTF_8);
        if (PRIVACY.containsShareablePrivateData(text)) {
          throw new IOException("SUPPORT_BUNDLE_PRIVACY_SCAN_FAILED:" + entry.getName());
        }
      }
    }
    if (!names.containsAll(REQUIRED_ENTRIES)) throw new IOException("SUPPORT_BUNDLE_REQUIRED_ENTRY_MISSING");
  }

  private static String shareable(String text) {
    return PRIVACY.filter(text, PrivacyFilter.Policy.SHAREABLE_BUNDLE);
  }

  private static void add(ZipOutputStream zip, String name, String content) throws IOException {
    zip.putNextEntry(new ZipEntry(name));
    zip.write(content.getBytes(StandardCharsets.UTF_8));
    zip.closeEntry();
  }

  @FunctionalInterface
  interface ArchiveHook {
    void afterArchiveWritten(Path part) throws IOException;
  }
}
