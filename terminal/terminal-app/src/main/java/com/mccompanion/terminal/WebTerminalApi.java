package com.mccompanion.terminal;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.mccompanion.terminal.diagnostics.DiagnosticResult;
import com.mccompanion.terminal.install.InstallPlan;
import com.mccompanion.terminal.install.InstallTransaction;
import com.mccompanion.terminal.launcher.LoaderType;
import com.mccompanion.terminal.launcher.MinecraftInstance;
import com.mccompanion.terminal.runtime.PairingService;
import com.mccompanion.terminal.runtime.RuntimeProfile;
import com.mccompanion.terminal.runtime.WindowsRuntimeSupervisor;
import com.sun.net.httpserver.HttpExchange;
import java.awt.Desktop;
import java.io.IOException;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/** Typed local API backed by the existing Java service layer. */
final class WebTerminalApi {
  private static final ObjectMapper JSON = new ObjectMapper();
  private final ControlTerminalMain root;
  private final OperationManager operations;
  private final RuntimeSnapshotService snapshots = new RuntimeSnapshotService();

  WebTerminalApi(ControlTerminalMain root, OperationManager operations) {
    this.root = root;
    this.operations = operations;
  }

  void handle(HttpExchange exchange) throws IOException {
    String path = exchange.getRequestURI().getPath();
    String method = exchange.getRequestMethod();
    try {
      if ("GET".equals(method) && "/api/status".equals(path))
        send(exchange, 200, statusSnapshot());
      else if ("GET".equals(method) && "/api/launchers".equals(path))
        send(exchange, 200, launchers());
      else if ("GET".equals(method) && "/api/instances".equals(path))
        send(exchange, 200, instances());
      else if ("GET".equals(method) && "/api/install/rollback-points".equals(path))
        send(exchange, 200, rollbackPoints(requiredQuery(exchange, "instanceId")));
      else if ("POST".equals(method) && "/api/doctor".equals(path))
        send(exchange, 200, doctor(body(exchange)));
      else if ("GET".equals(method) && "/api/runtime/profiles".equals(path))
        send(exchange, 200, runtimeProfiles());
      else if ("GET".equals(method) && "/api/runtime/status".equals(path))
        send(exchange, 200, runtimeStatus(requiredQuery(exchange, "instanceId")));
      else if ("GET".equals(method) && "/api/provider/status".equals(path))
        send(exchange, 200, providerStatus(requiredQuery(exchange, "instanceId")));
      else if ("POST".equals(method) && "/api/provider/test".equals(path))
        send(exchange, 200, providerTest(body(exchange)));
      else if ("GET".equals(method) && "/api/search/status".equals(path))
        send(exchange, 200, searchStatus(requiredQuery(exchange, "instanceId")));
      else if ("POST".equals(method) && "/api/search/test".equals(path))
        send(exchange, 200, searchTest(body(exchange)));
      else if ("GET".equals(method) && "/api/search/sessions".equals(path))
        send(exchange, 200, runtimeInspect(exchange, "/search/sessions"));
      else if ("GET".equals(method) && "/api/session/status".equals(path))
        send(exchange, 200, sessionStatus(requiredQuery(exchange, "instanceId")));
      else if ("GET".equals(method) && "/api/companions".equals(path))
        send(exchange, 200, companionSnapshot(requiredQuery(exchange, "instanceId")));
      else if ("GET".equals(method) && "/api/brain/status".equals(path))
        send(exchange, 200, runtimeInspect(exchange, "/brain"));
      else if ("GET".equals(method) && "/api/brain/audit".equals(path))
        send(exchange, 200, runtimeInspect(exchange, "/brain/audit", "companionId"));
      else if ("GET".equals(method) && "/api/memories".equals(path))
        send(exchange, 200, runtimeInspect(exchange, "/memories", "companionId", "kind", "query"));
      else if ("POST".equals(method) && "/api/memories/review".equals(path))
        send(exchange, 200, reviewMemorySuggestion(body(exchange)));
      else if ("GET".equals(method) && "/api/task-graphs".equals(path))
        send(exchange, 200, runtimeInspect(exchange, "/task-graphs", "companionId"));
      else if ("POST".equals(method) && "/api/task-graphs/control".equals(path))
        send(exchange, 200, controlTaskGraph(body(exchange)));
      else if ("GET".equals(method) && "/api/skills".equals(path))
        send(exchange, 200, runtimeInspect(exchange, "/skills", "companionId"));
      else if ("POST".equals(method) && "/api/skills/manage".equals(path))
        send(exchange, 200, manageSkill(body(exchange)));
      else if ("GET".equals(method) && "/api/logs/tail".equals(path))
        send(exchange, 200, logSnapshot(exchange));
      else if ("POST".equals(method) && path.endsWith("/plan"))
        send(exchange, 200, plan(path, body(exchange)));
      else if ("POST".equals(method) && path.endsWith("/execute"))
        send(exchange, 202, execute(body(exchange)));
      else if ("GET".equals(method) && path.startsWith("/api/operations/"))
        send(exchange, 200, operation(path.substring("/api/operations/".length())));
      else sendError(exchange, 404, "NOT_FOUND", "API 路径不存在");
    } catch (IllegalArgumentException failure) {
      sendError(exchange, 400, "INVALID_REQUEST", failure.getMessage());
    } catch (IOException failure) {
      sendError(exchange, 409, "BLOCKED", failure.getMessage());
    } catch (Exception failure) {
      sendError(
          exchange,
          500,
          "INTERNAL_ERROR",
          failure.getMessage() == null ? failure.getClass().getSimpleName() : failure.getMessage());
    }
  }

  ObjectNode statusSnapshot() {
    List<MinecraftInstance> instances = root.context.instances(root.roots());
    ObjectNode value =
        JSON.createObjectNode()
            .put("version", "0.3.0")
            .put("backend", "CONNECTED")
            .put("loopbackOnly", true)
            .put("controlHome", ControlTerminalMain.controlHome().toString())
            .put("launcherCount", root.context.launchers(root.roots()).size())
            .put("instanceCount", instances.size())
            .put("at", Instant.now().toString());
    if (!instances.isEmpty()) {
      MinecraftInstance selected = instances.getFirst();
      value.put("selectedInstanceId", selected.instanceId());
      RuntimeProfile profile = profileIfPresent(selected.instanceId());
      if (profile != null) {
        var health = new WindowsRuntimeSupervisor().status(profile);
        var connection = new ConnectionService().status(profile);
        value
            .put("runtime", health.healthy() ? "ONLINE" : health.pidAlive() ? "FAILED" : "STOPPED")
            .put("mod", connection.connected() ? "CONNECTED" : "WAITING")
            .put("companions", connection.companions())
            .put(
                "mode",
                selected.loader() == LoaderType.FABRIC
                    ? (connection.connected() ? connection.mode() : "SAFE_IDLE")
                    : "LOCAL_ONLY");
      } else {
        value
            .put("runtime", "NOT_CONFIGURED")
            .put("mod", "WAITING")
            .put("companions", 0)
            .put("mode", selected.loader() == LoaderType.FABRIC ? "SAFE_IDLE" : "LOCAL_ONLY");
      }
    }
    return value;
  }

  private ArrayNode launchers() {
    ArrayNode values = JSON.createArrayNode();
    root.context
        .launchers(root.roots())
        .forEach(
            launcher -> {
              ObjectNode value =
                  values
                      .addObject()
                      .put("id", launcher.launcherId())
                      .put("type", launcher.type().name())
                      .put("version", launcher.detectedVersion())
                      .put("executable", launcher.executable().toString())
                      .put("dataDirectory", launcher.dataDirectory().toString())
                      .put("confidence", launcher.confidence().name());
              value.set("evidence", JSON.valueToTree(launcher.evidence()));
            });
    return values;
  }

  private ArrayNode instances() {
    ArrayNode values = JSON.createArrayNode();
    root.context.instances(root.roots()).forEach(instance -> values.add(instance(instance)));
    return values;
  }

  private ObjectNode instance(MinecraftInstance instance) {
    boolean installed = false;
    try {
      installed = new InstallTransaction().verify(instance.gameDirectory());
    } catch (IOException ignored) {
    }
    return JSON.createObjectNode()
        .put("id", instance.instanceId())
        .put("launcherId", instance.launcherId())
        .put("name", instance.displayName())
        .put("minecraftVersion", instance.minecraftVersion())
        .put("loader", instance.loader().name())
        .put("loaderVersion", instance.loaderVersion())
        .put("gameDir", instance.gameDirectory().toString())
        .put("javaRequired", instance.requiredJavaMajor())
        .put("javaConfigured", instance.configuredJava().map(Path::toString).orElse(""))
        .put("confidence", instance.confidence().name())
        .put("isolation", instance.isolation().name())
        .put("compatible", com.mccompanion.terminal.install.InstallPlanner.isSupported(instance))
        .put("installed", installed)
        .put("mode", instance.loader() == LoaderType.FABRIC ? "FULL" : "LOCAL_ONLY");
  }

  private ObjectNode doctor(JsonNode request) throws Exception {
    MinecraftInstance instance = root.instance(required(request, "instanceId"));
    RuntimeProfile profile = root.profile(instance);
    ArrayNode checks = JSON.createArrayNode();
    List<DiagnosticResult> results =
        new TerminalDiagnosticService()
            .run(instance, profile, root.launcher(instance), ControlTerminalMain.controlHome());
    results.forEach(
        check -> {
          ObjectNode value =
              checks
                  .addObject()
                  .put("severity", check.severity().name())
                  .put("code", check.code())
                  .put("summary", check.summary());
          value.set("evidence", JSON.valueToTree(check.evidence()));
          value.set("repairs", JSON.valueToTree(check.repairs()));
          value.put("repairable", !check.repairs().isEmpty());
        });
    boolean blocked = results.stream().anyMatch(value -> value.severity() == DiagnosticResult.Severity.BLOCKED);
    boolean warning = results.stream().anyMatch(value -> value.severity() == DiagnosticResult.Severity.WARNING);
    return JSON.createObjectNode()
        .put("instanceId", instance.instanceId())
        .put("state", blocked ? "BLOCKED" : warning ? "WARNING" : "PASS")
        .set("checks", checks);
  }

  private ArrayNode rollbackPoints(String instanceId) throws Exception {
    MinecraftInstance instance = root.instance(instanceId);
    ArrayNode values = JSON.createArrayNode();
    new InstallTransaction().rollbackPoints(instance.gameDirectory()).forEach(values::add);
    return values;
  }

  private ArrayNode runtimeProfiles() {
    ArrayNode values = JSON.createArrayNode();
    Path home = ControlTerminalMain.controlHome().resolve("profiles");
    if (!Files.isDirectory(home)) return values;
    try (var dirs = Files.newDirectoryStream(home, Files::isDirectory)) {
      for (Path directory : dirs) {
        RuntimeProfile profile = profileIfPresent(directory.getFileName().toString());
        if (profile != null) values.add(runtimeStatus(profile));
      }
    } catch (IOException ignored) {
    }
    return values;
  }

  private ObjectNode runtimeStatus(String instanceId) {
    RuntimeProfile profile = profileIfPresent(instanceId);
    if (profile == null)
      return JSON.createObjectNode().put("instanceId", instanceId).put("configured", false);
    return runtimeStatus(profile);
  }

  private ObjectNode runtimeStatus(RuntimeProfile profile) {
    var health = new WindowsRuntimeSupervisor().status(profile);
    return JSON.createObjectNode()
        .put("instanceId", profile.instanceId())
        .put("configured", true)
        .put("port", profile.port())
        .put("healthPort", profile.healthPort())
        .put("pid", health.pid() == null ? -1 : health.pid())
        .put("pidAlive", health.pidAlive())
        .put("healthy", health.healthy())
        .put("identityMatches", health.identityMatches())
        .put("runtimeVersion", text(health.runtimeVersion()))
        .put("protocolVersion", text(health.protocolVersion()))
        .put("sessions", health.sessionCount())
        .put("detail", health.detail());
  }

  private JsonNode providerStatus(String instanceId) throws Exception {
    MinecraftInstance instance = root.instance(instanceId);
    return new ProviderConfigurationService().status(root.profile(instance));
  }

  private ObjectNode providerTest(JsonNode request) throws Exception {
    MinecraftInstance instance = root.instance(required(request, "instanceId"));
    var result = new ProviderConfigurationService().test(root.profile(instance));
    return JSON.createObjectNode()
        .put("success", result.success())
        .put("latencyMillis", result.latencyMillis())
        .put("model", result.model())
        .put("message", result.message());
  }

  private JsonNode searchStatus(String instanceId) throws Exception {
    MinecraftInstance instance = root.instance(instanceId);
    return new SearchConfigurationService().status(root.profile(instance));
  }

  private ObjectNode searchTest(JsonNode request) throws Exception {
    MinecraftInstance instance = root.instance(required(request, "instanceId"));
    var result = new SearchConfigurationService().test(root.profile(instance));
    return JSON.createObjectNode().put("success", result.success())
        .put("networkAttempted", result.networkAttempted()).put("latencyMillis", result.latencyMillis())
        .put("code", result.code()).put("message", result.message());
  }

  private ObjectNode sessionStatus(String instanceId) throws Exception {
    MinecraftInstance instance = root.instance(instanceId);
    if (instance.loader() != LoaderType.FABRIC) {
      return JSON.createObjectNode()
          .put("instanceId", instanceId)
          .put("connected", false)
          .put("mode", "LOCAL_ONLY")
          .put("runtimeHealthy", false)
          .put("sessions", 0)
          .put("companions", 0);
    }
    RuntimeProfile profile = root.profile(instance);
    var status = new ConnectionService().status(profile);
    return JSON.createObjectNode()
        .put("instanceId", instanceId)
        .put("connected", status.connected())
        .put("mode", status.connected() ? status.mode() : "SAFE_IDLE")
        .put("runtimeHealthy", status.runtimeHealthy())
        .put("sessions", status.sessions())
        .put("companions", status.companions());
  }

  ObjectNode companionSnapshot(String instanceId) throws Exception {
    MinecraftInstance instance = root.instance(instanceId);
    if (instance.loader() != LoaderType.FABRIC) {
      return JSON.createObjectNode()
          .put("instanceId", instanceId)
          .put("mode", "LOCAL_ONLY")
          .set("companions", JSON.createArrayNode());
    }
    ObjectNode value = snapshots.snapshot(root.profile(instance));
    var connection = new ConnectionService().status(root.profile(instance));
    value.put("mode", connection.connected() ? connection.mode() : "SAFE_IDLE");
    return value;
  }

  private JsonNode runtimeInspect(HttpExchange exchange, String runtimePath, String... forwarded) throws Exception {
    Map<String, String> query = query(exchange);
    MinecraftInstance instance = root.instance(required(query, "instanceId"));
    StringBuilder path = new StringBuilder(runtimePath);
    boolean first = true;
    for (String name : forwarded) {
      String value = query.get(name);
      if (value == null || value.isBlank()) continue;
      path.append(first ? '?' : '&').append(java.net.URLEncoder.encode(name, StandardCharsets.UTF_8))
          .append('=').append(java.net.URLEncoder.encode(value, StandardCharsets.UTF_8));
      first = false;
    }
    return new RuntimeControlClient().inspect(root.profile(instance), path.toString(), Duration.ofSeconds(8));
  }

  private JsonNode reviewMemorySuggestion(JsonNode request) throws Exception {
    String instanceId = required(request, "instanceId");
    String companionId = required(request, "companionId");
    String path = "/memories?companionId=" + java.net.URLEncoder.encode(
        companionId, StandardCharsets.UTF_8);
    ObjectNode bounded = JSON.createObjectNode()
        .put("action", required(request, "action"))
        .put("suggestionId", required(request, "suggestionId"));
    if (request.hasNonNull("reason")) bounded.put("reason", required(request, "reason"));
    return new RuntimeControlClient().manage(
        root.profile(root.instance(instanceId)), path, bounded, Duration.ofSeconds(8));
  }

  private JsonNode controlTaskGraph(JsonNode request) throws Exception {
    String instanceId = required(request, "instanceId");
    String companionId = required(request, "companionId");
    String path = "/task-graphs?companionId=" + java.net.URLEncoder.encode(
        companionId, StandardCharsets.UTF_8);
    ObjectNode bounded = JSON.createObjectNode()
        .put("action", required(request, "action"))
        .put("executionId", required(request, "executionId"));
    return new RuntimeControlClient().manage(
        root.profile(root.instance(instanceId)), path, bounded, Duration.ofSeconds(8));
  }

  private JsonNode manageSkill(JsonNode request) throws Exception {
    String instanceId = required(request, "instanceId");
    ObjectNode bounded = JSON.createObjectNode()
        .put("action", required(request, "action"))
        .put("companionId", required(request, "companionId"));
    if (request.hasNonNull("requestId")) bounded.put("requestId", required(request, "requestId"));
    if (request.hasNonNull("skillId")) bounded.put("skillId", required(request, "skillId"));
    if (request.hasNonNull("format")) bounded.put("format", required(request, "format"));
    if (request.hasNonNull("version")) bounded.put("version", requiredPositiveLong(request, "version"));
    if (request.hasNonNull("reason")) bounded.put("reason", required(request, "reason"));
    return new RuntimeControlClient().manage(
        root.profile(root.instance(instanceId)), "/skills", bounded, Duration.ofSeconds(8));
  }

  ObjectNode logSnapshot(HttpExchange exchange) throws Exception {
    Map<String, String> query = query(exchange);
    MinecraftInstance instance = root.instance(required(query, "instanceId"));
    String kind = query.getOrDefault("kind", "minecraft");
    Path file =
        "runtime".equals(kind)
            ? root.profile(instance).logFile()
            : instance.logsDirectory().resolve("latest.log");
    ArrayNode lines = JSON.createArrayNode();
    if (Files.isRegularFile(file)) {
      List<String> all = Files.readAllLines(file, StandardCharsets.UTF_8);
      all.subList(Math.max(0, all.size() - 500), all.size()).forEach(lines::add);
    }
    return JSON.createObjectNode()
        .put("kind", kind)
        .put("available", Files.isRegularFile(file))
        .set("lines", lines);
  }

  private ObjectNode plan(String path, JsonNode request) throws Exception {
    String category = path.substring("/api/".length(), path.length() - "/plan".length());
    OperationManager.Plan plan =
        switch (category) {
          case "install" -> installPlan(request);
          case "runtime" -> runtimePlan(request);
          case "provider" -> providerPlan(request);
          case "search" -> searchPlan(request);
          case "session" -> sessionPlan(request);
          case "companions" -> companionPlan(request);
          case "agent" -> agentPlan(request);
          case "smoke" -> smokePlan(request);
          case "support-bundle" -> supportPlan(request);
          case "doctor/repair" -> doctorRepairPlan(request);
          default -> throw new IllegalArgumentException("不支持的计划类型: " + category);
        };
    return plan(plan);
  }

  private OperationManager.Plan installPlan(JsonNode request) throws Exception {
    String instanceId = required(request, "instanceId");
    String action = required(request, "action").toLowerCase();
    MinecraftInstance instance = root.instance(instanceId);
    InstallTransaction transaction = new InstallTransaction();
    if ("rollback".equals(action)) {
      String rollbackId = required(request, "rollbackId");
      ObjectNode details =
          JSON.createObjectNode()
              .put("summary", "回滚到 " + rollbackId)
              .put("gameDir", instance.gameDirectory().toString())
              .put("rollbackId", rollbackId);
      return operations.create(
          "install",
          action,
          instanceId,
          true,
          details,
          progress -> {
            progress.update(40, "正在验证并恢复回滚点");
            transaction.rollback(instance.gameDirectory(), rollbackId);
            return JSON.createObjectNode().put("rolledBack", true).put("rollbackId", rollbackId);
          });
    }
    if (List.of("uninstall", "uninstall-delete-data").contains(action)) {
      boolean deleteData = "uninstall-delete-data".equals(action);
      ObjectNode details =
          JSON.createObjectNode()
              .put("summary", "仅卸载 mcac 清单管理的文件")
              .put("gameDir", instance.gameDirectory().toString())
              .put("dataPolicy", deleteData ? "DELETE" : "PRESERVE")
              .put("preservesWorldsLaunchersAccountsAndOtherMods", true);
      return operations.create(
          "install",
          action,
          instanceId,
          true,
          details,
          progress -> {
            progress.update(40, "正在验证清单和文件哈希");
            new InstanceUninstallService().uninstall(instance, profileIfPresent(instanceId),
                ControlTerminalMain.controlHome(), deleteData
                    ? InstanceUninstallService.DataPolicy.DELETE
                    : InstanceUninstallService.DataPolicy.PRESERVE);
            return JSON.createObjectNode().put("uninstalled", true)
                .put("dataPolicy", deleteData ? "DELETE" : "PRESERVE");
          });
    }
    if (!List.of("install", "update", "repair").contains(action)) {
      throw new IllegalArgumentException("安装操作不受支持");
    }
    InstallPlan install = new InstallService().plan(instance, ControlTerminalMain.artifacts());
    ObjectNode details =
        JSON.createObjectNode()
            .put("summary", action + " Companion Mod")
            .put("artifact", install.artifact().getFileName().toString())
            .put("destination", install.destination().toString())
            .put("rollbackId", install.rollbackId())
            .put("fabricApiMissing", install.fabricApiMissing());
    ArrayNode replaced = details.putArray("replacedFiles");
    install.replacedFiles().forEach(file -> replaced.add(file.toString()));
    return operations.create(
        "install",
        action,
        instanceId,
        false,
        details,
        progress -> {
          if ("repair".equals(action) && transaction.verify(instance.gameDirectory())) {
            return JSON.createObjectNode().put("verified", true).put("changed", false);
          }
          progress.update(35, "正在备份原文件并写入事务日志");
          var result = new InstallService().install(install);
          progress.update(85, "正在验证安装文件哈希");
          if (!transaction.verify(instance.gameDirectory())) throw new IOException("安装后哈希验证失败");
          return JSON.createObjectNode()
              .put("installedFile", result.installedFile().toString())
              .put("sha256", result.sha256())
              .put("rollbackId", result.rollbackId())
              .put("verified", true);
        });
  }

  private OperationManager.Plan runtimePlan(JsonNode request) throws Exception {
    String instanceId = required(request, "instanceId");
    String action = required(request, "action").toLowerCase();
    if (!List.of("start", "stop", "restart", "rotate-token").contains(action))
      throw new IllegalArgumentException("Runtime 操作不受支持");
    MinecraftInstance instance = root.instance(instanceId);
    boolean dangerous = "rotate-token".equals(action);
    ObjectNode details =
        JSON.createObjectNode()
            .put("summary", "Runtime " + action)
            .put("instance", instance.displayName())
            .put("tokenWillNeverBeReturned", true);
    return operations.create(
        "runtime",
        action,
        instanceId,
        dangerous,
        details,
        progress -> {
          RuntimeProfile profile = root.profile(instance);
          WindowsRuntimeSupervisor supervisor = new WindowsRuntimeSupervisor();
          switch (action) {
            case "start" -> {
              progress.update(30, "正在写入安全 Runtime 配置");
              new PairingService().ensureConfigured(instance, profile);
              supervisor.start(profile);
            }
            case "stop" -> supervisor.stop(profile);
            case "restart" -> {
              supervisor.stop(profile);
              new PairingService().ensureConfigured(instance, profile);
              supervisor.start(profile);
            }
            case "rotate-token" ->
                new TokenRotationService().rotate(instance, profile, Duration.ofSeconds(30));
            default -> throw new IllegalStateException(action);
          }
          progress.update(85, "正在验证 Runtime 身份与健康状态");
          return runtimeStatus(profile);
        });
  }

  private OperationManager.Plan providerPlan(JsonNode request) throws Exception {
    String instanceId = required(request, "instanceId");
    String action = required(request, "action").toLowerCase();
    MinecraftInstance instance = root.instance(instanceId);
    if (!List.of("configure", "disable").contains(action))
      throw new IllegalArgumentException("Provider 操作不受支持");
    ObjectNode details = JSON.createObjectNode().put("summary", "Provider " + action);
    if ("configure".equals(action)) {
      details
          .put("baseUrl", required(request, "baseUrl"))
          .put("model", required(request, "model"))
          .put("apiKeyEnv", required(request, "apiKeyEnv"))
          .put("storesApiKey", false);
    }
    JsonNode copy = request.deepCopy();
    return operations.create(
        "provider",
        action,
        instanceId,
        false,
        details,
        progress -> {
          ProviderConfigurationService provider = new ProviderConfigurationService();
          RuntimeProfile profile = root.profile(instance);
          if ("disable".equals(action)) provider.disable(profile);
          else
            provider.configure(
                profile,
                required(copy, "baseUrl"),
                required(copy, "model"),
                required(copy, "apiKeyEnv"),
                copy.path("timeoutSeconds").asInt(15));
          new PairingService().ensureConfigured(instance, profile);
          return (ObjectNode) provider.status(profile);
        });
  }

  private OperationManager.Plan searchPlan(JsonNode request) throws Exception {
    String instanceId = required(request, "instanceId");
    String action = required(request, "action").toLowerCase();
    if (!List.of("configure", "disable").contains(action))
      throw new IllegalArgumentException("Search action is not supported");
    MinecraftInstance instance = root.instance(instanceId);
    ObjectNode details = JSON.createObjectNode().put("summary", "Search " + action)
        .put("storesToken", false);
    if ("configure".equals(action)) details.put("endpoint", required(request, "endpoint"))
        .put("tokenEnv", required(request, "tokenEnv"));
    JsonNode copy = request.deepCopy();
    return operations.create("search", action, instanceId, false, details, progress -> {
      SearchConfigurationService search = new SearchConfigurationService();
      RuntimeProfile profile = root.profile(instance);
      if ("disable".equals(action)) search.disable(profile);
      else search.configure(profile, required(copy, "endpoint"), required(copy, "tokenEnv"),
          copy.path("timeoutSeconds").asInt(15), textList(copy.path("allowedDomains")),
          textList(copy.path("deniedDomains")));
      new PairingService().ensureConfigured(instance, profile);
      return (ObjectNode) search.status(profile);
    });
  }

  private OperationManager.Plan sessionPlan(JsonNode request) throws Exception {
    String instanceId = required(request, "instanceId");
    String action = request.path("action").asText("play").toLowerCase();
    MinecraftInstance instance = root.instance(instanceId);
    ObjectNode details =
        JSON.createObjectNode()
            .put("summary", action.equals("attach") ? "附加到当前会话" : "启动游戏并等待 Mod 握手")
            .put("launcher", root.launcher(instance).type().name())
            .put("mode", instance.loader() == LoaderType.FABRIC ? "FULL" : "LOCAL_ONLY");
    int waitSeconds = Math.max(5, Math.min(300, request.path("waitSeconds").asInt(90)));
    return operations.create(
        "session",
        action,
        instanceId,
        false,
        details,
        progress -> {
          RuntimeProfile profile = root.profile(instance);
          if ("attach".equals(action)) return sessionStatus(instanceId);
          progress.update(15, "正在执行 Doctor");
          List<DiagnosticResult> checks =
              new TerminalDiagnosticService()
                  .run(instance, profile, root.launcher(instance), ControlTerminalMain.controlHome());
          boolean blocked =
              checks.stream().anyMatch(value -> value.severity() == DiagnosticResult.Severity.BLOCKED);
          if (blocked) throw new IOException("Doctor 检测到阻断项，请先修复");
          if (!new InstallTransaction().verify(instance.gameDirectory()))
            throw new IOException("未检测到经过验证的 Companion 安装，请先完成安装");
          if (instance.loader() == LoaderType.FABRIC) {
            progress.update(35, "正在启动 Runtime");
            new PairingService().ensureConfigured(instance, profile);
            new WindowsRuntimeSupervisor().start(profile);
          }
          progress.update(50, "正在打开启动器");
          Path executable = root.launcher(instance).executable();
          if (Desktop.isDesktopSupported()) Desktop.getDesktop().open(executable.toFile());
          else new ProcessBuilder(executable.toString()).start();
          if (instance.loader() != LoaderType.FABRIC) {
            return JSON.createObjectNode()
                .put("state", "LOCAL_ONLY")
                .put("message", "启动器已打开；该 Loader 当前没有 Runtime Bridge");
          }
          progress.update(60, "等待 Minecraft 和 Mod 握手");
          var status =
              new ConnectionService().waitForHandshake(profile, Duration.ofSeconds(waitSeconds));
          if (!status.connected()) throw new IOException("等待 Mod 握手超时");
          return sessionStatus(instanceId);
        });
  }

  private OperationManager.Plan companionPlan(JsonNode request) throws Exception {
    String instanceId = required(request, "instanceId");
    String companionId = required(request, "companionId");
    String action = required(request, "action").toLowerCase();
    MinecraftInstance instance = root.instance(instanceId);
    if (instance.loader() != LoaderType.FABRIC) throw new IOException("LOCAL_ONLY 不支持 Companion 远程控制");
    Map<String, String> types =
        Map.of(
            "status", "STATUS",
            "follow", "FOLLOW",
            "come", "RETURN",
            "goto", "TRAVEL",
            "pause", "STOP",
            "resume", "STOP",
            "stop", "STOP");
    String type = types.get(action);
    if (type == null) throw new IllegalArgumentException("Companion 命令不受支持");
    ObjectNode arguments = JSON.createObjectNode();
    if (List.of("pause", "resume", "stop").contains(action))
      arguments.put("action", action.equals("stop") ? "cancel" : action);
    if ("goto".equals(action)) {
      arguments
          .put("x", requiredNumber(request, "x"))
          .put("y", requiredNumber(request, "y"))
          .put("z", requiredNumber(request, "z"));
    }
    ObjectNode details =
        JSON.createObjectNode()
            .put("summary", "向 " + companionId + " 发送 " + action)
            .put("command", action)
            .put("companionId", companionId);
    return operations.create(
        "companions",
        action,
        instanceId,
        action.equals("stop"),
        details,
        progress -> {
          progress.update(40, "正在发送经过身份验证的 Runtime 命令");
          JsonNode result =
              new RuntimeControlClient()
                  .execute(
                      root.profile(instance),
                      "web-" + UUID.randomUUID(),
                      companionId,
                      type,
                      arguments,
                      Duration.ofSeconds(8));
          if (!result.path("accepted").asBoolean())
            throw new IOException("命令被拒绝: " + result.path("code").asText("UNKNOWN"));
          return result;
        });
  }

  private OperationManager.Plan agentPlan(JsonNode request) throws Exception {
    String instanceId = required(request, "instanceId");
    String companionId = required(request, "companionId");
    String text = request.path("text").asText("").strip();
    if (text.isEmpty() || text.length() > 4096) throw new IllegalArgumentException("请输入 1..4096 字符的伙伴请求");
    MinecraftInstance instance = root.instance(instanceId);
    if (instance.loader() != LoaderType.FABRIC) throw new IOException("LOCAL_ONLY 不支持智能伙伴任务");
    ObjectNode details = JSON.createObjectNode().put("summary", "让伙伴理解并处理自然语言目标")
        .put("companionId", companionId).put("request", text).put("modelMayBeCalled", true)
        .put("completionRequiresWorldEvidence", true);
    return operations.create(
        "agent", "request", instanceId, false, details,
        progress -> {
          progress.update(20, "正在规范化输入并构建有限世界上下文");
          JsonNode result = new RuntimeControlClient().brain(root.profile(instance), "runtime-primary",
              companionId, text, Duration.ofSeconds(65));
          progress.update(85, "正在校验外部大脑响应、工具权限和观察结果");
          if (!result.path("accepted").asBoolean())
            throw new IOException(result.path("message").asText("伙伴请求被安全拒绝"));
          return result;
        });
  }

  private OperationManager.Plan smokePlan(JsonNode request) throws Exception {
    String instanceId = required(request, "instanceId");
    MinecraftInstance instance = root.instance(instanceId);
    return operations.create(
        "smoke",
        "run",
        instanceId,
        false,
        JSON.createObjectNode()
            .put("summary", "执行 STATUS → FOLLOW → PAUSE → RESUME → STOP")
            .put("verifies", "task, lease, epoch, behavior, revision, event order, safe state"),
        progress -> {
          progress.update(20, "正在验证握手与 Companion");
          var result = new SmokeTestService().run(instance, root.profile(instance));
          ObjectNode value =
              JSON.createObjectNode()
                  .put("success", result.success())
                  .put("manualRequired", result.manualRequired())
                  .put("summary", result.summary());
          if (!result.success()) throw new IOException(result.summary());
          return value;
        });
  }

  private OperationManager.Plan supportPlan(JsonNode request) throws Exception {
    String instanceId = required(request, "instanceId");
    MinecraftInstance instance = root.instance(instanceId);
    Path output =
        ControlTerminalMain.controlHome()
            .resolve("support")
            .resolve("mcac-support-" + instanceId + '-' + System.currentTimeMillis() + ".zip");
    return operations.create(
        "support-bundle",
        "collect",
        instanceId,
        false,
        JSON.createObjectNode()
            .put("summary", "收集并脱敏支持包")
            .put("output", output.toString())
            .put("redaction", "secret, token, key, password, account, email, JWT, IP, path, UUID, hostname"),
        progress -> {
          progress.update(45, "正在收集允许列表中的诊断证据");
          RuntimeProfile profile = profileIfPresent(instanceId);
          List<DiagnosticResult> doctor = profile == null ? List.of() : new TerminalDiagnosticService()
              .run(instance, profile, root.launcher(instance), ControlTerminalMain.controlHome());
          new SupportBundleService().collect(instance, profile, doctor, output);
          return JSON.createObjectNode()
              .put("created", true)
              .put("output", output.toString())
              .put("size", Files.size(output));
        });
  }

  private OperationManager.Plan doctorRepairPlan(JsonNode request) throws Exception {
    String code = required(request, "code");
    ObjectNode routed = JSON.createObjectNode().put("instanceId", required(request, "instanceId"));
    if (code.startsWith("runtime.") || code.equals("protocol.compatible")) {
      routed.put("action", code.equals("runtime.token_match") ? "rotate-token" : "restart");
      return runtimePlan(routed);
    }
    if (code.startsWith("install.") || code.startsWith("mods.")) {
      routed.put("action", "repair");
      return installPlan(routed);
    }
    throw new IOException("该检查项需要用户在启动器中修复，不能由 mcac 安全自动修改");
  }

  private ObjectNode execute(JsonNode request) {
    return operation(
        operations.execute(required(request, "planId"), required(request, "confirmation")));
  }

  private ObjectNode operation(String id) {
    return operation(operations.requireOperation(id));
  }

  private ObjectNode operation(OperationManager.Operation value) {
    ObjectNode result =
        JSON.createObjectNode()
            .put("id", value.id())
            .put("category", value.category())
            .put("action", value.action())
            .put("instanceId", value.instanceId())
            .put("state", value.state())
            .put("progress", value.progress())
            .put("message", value.message())
            .put("startedAt", value.startedAt().toString());
    if (value.result() != null) result.set("result", value.result());
    if (value.error() != null) result.put("error", value.error());
    if (value.finishedAt() != null) result.put("finishedAt", value.finishedAt().toString());
    return result;
  }

  private ObjectNode plan(OperationManager.Plan value) {
    return JSON.createObjectNode()
        .put("planId", value.id())
        .put("category", value.category())
        .put("action", value.action())
        .put("instanceId", value.instanceId())
        .put("dangerous", value.dangerous())
        .put("expiresAt", value.expiresAt().toString())
        .set("details", value.details());
  }

  private RuntimeProfile profileIfPresent(String instanceId) {
    try {
      Path directory = ControlTerminalMain.controlHome().resolve("profiles").resolve(instanceId);
      Path identity = directory.resolve("profile.json");
      if (!Files.isRegularFile(identity)) return null;
      JsonNode node = JSON.readTree(identity.toFile());
      if (!instanceId.equals(node.path("instanceId").asText())) return null;
      int port = node.path("port").asInt(-1);
      int health = node.path("healthPort").asInt(port + 10_000);
      if (port < 8766 || port > 8866 || health != port + 10_000) return null;
      return new RuntimeProfile(instanceId, directory, ControlTerminalMain.locateRuntime(), port, health);
    } catch (Exception ignored) {
      return null;
    }
  }

  private static JsonNode body(HttpExchange exchange) throws IOException {
    byte[] bytes = exchange.getRequestBody().readNBytes(1_048_577);
    if (bytes.length > 1_048_576) throw new IllegalArgumentException("请求体过大");
    if (bytes.length == 0) return JSON.createObjectNode();
    JsonNode value = JSON.readTree(bytes);
    if (!value.isObject()) throw new IllegalArgumentException("请求必须是 JSON 对象");
    return value;
  }

  static void send(HttpExchange exchange, int status, JsonNode value) throws IOException {
    byte[] bytes = JSON.writeValueAsBytes(value);
    exchange.getResponseHeaders().set("Content-Type", "application/json; charset=utf-8");
    exchange.getResponseHeaders().set("Cache-Control", "no-store");
    exchange.sendResponseHeaders(status, bytes.length);
    exchange.getResponseBody().write(bytes);
    exchange.close();
  }

  static void sendError(HttpExchange exchange, int status, String code, String message)
      throws IOException {
    send(
        exchange,
        status,
        JSON.createObjectNode()
            .put("ok", false)
            .put("code", code)
            .put("message", message == null ? code : message));
  }

  private static Map<String, String> query(HttpExchange exchange) {
    Map<String, String> values = new HashMap<>();
    String raw = exchange.getRequestURI().getRawQuery();
    if (raw == null || raw.isBlank()) return values;
    for (String pair : raw.split("&")) {
      int equals = pair.indexOf('=');
      String key = equals < 0 ? pair : pair.substring(0, equals);
      String value = equals < 0 ? "" : pair.substring(equals + 1);
      values.put(decode(key), decode(value));
    }
    return values;
  }

  private static String requiredQuery(HttpExchange exchange, String key) {
    return required(query(exchange), key);
  }

  private static String required(JsonNode node, String key) {
    String value = node.path(key).asText("").trim();
    if (value.isBlank() || value.length() > 2048)
      throw new IllegalArgumentException(key + " 为必填项");
    return value;
  }

  private static String required(Map<String, String> values, String key) {
    String value = values.get(key);
    if (value == null || value.isBlank() || value.length() > 2048)
      throw new IllegalArgumentException(key + " 为必填项");
    return value;
  }

  private static double requiredNumber(JsonNode node, String key) {
    if (!node.has(key) || !node.path(key).isNumber())
      throw new IllegalArgumentException(key + " 必须是数字");
    double value = node.path(key).asDouble();
    if (!Double.isFinite(value) || Math.abs(value) > 30_000_000)
      throw new IllegalArgumentException(key + " 超出安全范围");
    return value;
  }

  private static long requiredPositiveLong(JsonNode node, String key) {
    JsonNode value = node.path(key);
    if (!value.isIntegralNumber() || !value.canConvertToLong() || value.asLong() < 1)
      throw new IllegalArgumentException(key + " must be a positive integer");
    return value.asLong();
  }

  private static List<String> textList(JsonNode node) {
    if (!node.isArray()) return List.of();
    java.util.ArrayList<String> values = new java.util.ArrayList<>();
    node.forEach(value -> values.add(value.asText("")));
    return List.copyOf(values);
  }

  private static String decode(String value) {
    return URLDecoder.decode(value, StandardCharsets.UTF_8);
  }

  private static String text(String value) {
    return value == null ? "" : value;
  }
}
