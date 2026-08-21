package com.mccompanion.terminal;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Locale;
import java.util.regex.Pattern;

/** One privacy boundary for terminal UI, diagnostics, and shareable artifacts. */
final class PrivacyFilter {
  enum Policy {
    INTERNAL_RAW,
    LOCAL_DIAGNOSTIC,
    UI_DEFAULT,
    SHAREABLE_BUNDLE
  }

  private static final Pattern SENSITIVE_ASSIGNMENT = Pattern.compile(
      "(?i)([\\\"']?(?:api[_-]?key|access[_-]?token|refresh[_-]?token|token|authorization|secret|password|passwd|cookie|session[_-]?id|account|username|player[_-]?name|instance[_-]?id|installation[_-]?id|profile[_-]?id|companion[_-]?id|brain[_-]?session[_-]?id|controller[_-]?id)[\\\"']?\\s*[:=]\\s*[\\\"']?)([^\\\"',\\s}\\]]+)");
  private static final Pattern ABSOLUTE_PATH = Pattern.compile("(?i)[A-Z]:[\\\\/][^\\r\\n\"']+");
  private static final Pattern POSIX_PATH = Pattern.compile(
      "(?<!\\w)(?<!:(?!/))/(?:Users|home|var|tmp|opt|srv|mnt|etc)/[^\\s\"']+");
  private static final Pattern IPV4 = Pattern.compile(
      "(?<![0-9])(?:[0-9]{1,3}\\.){3}[0-9]{1,3}(?::[0-9]{1,5})?");
  private static final Pattern IPV6 = Pattern.compile(
      "(?i)(?<![0-9a-f])(?:[0-9a-f]{0,4}:){2,7}[0-9a-f]{0,4}(?:%[0-9a-z]+)?(?:\\:[0-9]{1,5})?");
  private static final Pattern UUID = Pattern.compile(
      "(?i)\\b[0-9a-f]{8}-[0-9a-f]{4}-[1-5][0-9a-f]{3}-[89ab][0-9a-f]{3}-[0-9a-f]{12}\\b");
  private static final Pattern HOSTNAME = Pattern.compile(
      "(?i)\\b(?!(?:brain|search|runtime|mcp|protocol|install|launcher|mods|hook|java|loader)\\.)(?:[a-z0-9](?:[a-z0-9-]{0,61}[a-z0-9])?\\.)+(?!(?:jar|log|json|txt|ya?ml)\\b)[a-z]{2,63}(?::[0-9]{1,5})?\\b");
  private static final Pattern EMAIL = Pattern.compile(
      "(?i)\\b[A-Z0-9._%+-]+@[A-Z0-9.-]+\\.[A-Z]{2,63}\\b");
  private static final Pattern JWT = Pattern.compile(
      "\\beyJ[A-Za-z0-9_-]{8,}\\.[A-Za-z0-9_-]{8,}\\.[A-Za-z0-9_-]{8,}\\b");
  private static final ObjectMapper JSON = new ObjectMapper();

  String filter(String text, Policy policy) {
    if (text == null || policy == Policy.INTERNAL_RAW) return text;
    String value = SENSITIVE_ASSIGNMENT.matcher(text).replaceAll("$1<REDACTED>")
        .replaceAll("(?i)Bearer\\s+[^\\s,]+", "Bearer <REDACTED>")
        .replaceAll("(?i)([?&](?:key|token|secret|signature|auth)=)[^&\\s]+", "$1<REDACTED>")
        .replaceAll("(?i)(Authorization\\s*:\\s*)[^\\r\\n]+", "$1<REDACTED>");
    String home = System.getProperty("user.home", "");
    if (!home.isBlank()) value = value.replace(home, "<HOME>");
    String user = System.getProperty("user.name", "");
    if (!user.isBlank()) value = value.replace(user, "<USER>");
    value = EMAIL.matcher(value).replaceAll("<EMAIL>");
    value = JWT.matcher(value).replaceAll("<REDACTED>");
    value = ABSOLUTE_PATH.matcher(value).replaceAll("<PATH>");
    value = POSIX_PATH.matcher(value).replaceAll("<PATH>");
    if (policy == Policy.LOCAL_DIAGNOSTIC) return value;
    boolean shareable = policy == Policy.SHAREABLE_BUNDLE;
    value = replaceIdentifiers(value, IPV4, "IP", shareable);
    value = replaceIdentifiers(value, IPV6, "IP", shareable);
    value = replaceIdentifiers(value, UUID, "ID", shareable);
    value = replaceIdentifiers(value, HOSTNAME, "HOST", shareable);
    return value;
  }

  String filterLogLine(String line, Policy policy) {
    if (line == null) return "";
    String lower = line.toLowerCase(Locale.ROOT);
    if (policy != Policy.INTERNAL_RAW && (lower.contains("[chat]")
        || lower.contains("chat message") || lower.matches(".*<[^>]{1,64}>.*"))) {
      return "<PRIVATE_CHAT_OMITTED>";
    }
    return filter(line, policy);
  }

  /**
   * Applies the same classification to structured responses as {@link #filter(String, Policy)}
   * applies to text. Identifier-looking field names are not allowed to bypass the boundary merely
   * because a Runtime response added a new JSON field.
   */
  JsonNode sanitizeJson(JsonNode value, Policy policy) {
    if (value == null || policy == Policy.INTERNAL_RAW) return value == null ? null : value.deepCopy();
    if (value.isTextual()) {
      String text = filter(value.asText(), policy);
      if ((policy == Policy.UI_DEFAULT || policy == Policy.SHAREABLE_BUNDLE)
          && isLikelyOpaqueIdentifier(text)) {
        text = policy == Policy.SHAREABLE_BUNDLE ? "<ID>" : pseudonymLabel("ID", text);
      }
      return JSON.getNodeFactory().textNode(text);
    }
    if (value.isArray()) {
      ArrayNode copy = JSON.createArrayNode();
      value.forEach(item -> copy.add(sanitizeJson(item, policy)));
      return copy;
    }
    if (!value.isObject()) return value.deepCopy();
    ObjectNode copy = JSON.createObjectNode();
    value.fields().forEachRemaining(entry -> {
      String key = entry.getKey();
      JsonNode child = entry.getValue();
      String kind = identifierKind(key);
      if (kind != null && (policy == Policy.UI_DEFAULT || policy == Policy.SHAREABLE_BUNDLE)) {
        String replacement = "REDACTED".equals(kind) ? "<REDACTED>"
            : policy == Policy.SHAREABLE_BUNDLE ? "<" + kind + ">"
            : pseudonymLabel(kind, child.asText(""));
        copy.set(key, JSON.getNodeFactory().textNode(replacement));
      } else {
        copy.set(key, sanitizeJson(child, policy));
      }
    });
    return copy;
  }

  boolean containsShareablePrivateData(String text) {
    if (text == null) return false;
    var secrets = SENSITIVE_ASSIGNMENT.matcher(text);
    while (secrets.find()) if (!"<REDACTED>".equals(secrets.group(2))) return true;
    return ABSOLUTE_PATH.matcher(text).find() || POSIX_PATH.matcher(text).find()
        || IPV4.matcher(text).find() || IPV6.matcher(text).find() || UUID.matcher(text).find()
        || EMAIL.matcher(text).find() || JWT.matcher(text).find() || HOSTNAME.matcher(text).find();
  }

  String pseudonymLabel(String kind, String value) {
    if (value == null || value.isBlank()) return kind;
    return kind + "-" + pseudonym(value);
  }

  private static String replaceIdentifiers(String value, Pattern pattern, String kind, boolean shareable) {
    var matcher = pattern.matcher(value);
    var result = new StringBuffer();
    while (matcher.find()) {
      String replacement = shareable ? "<" + kind + ">" : "<" + kind + ":" + pseudonym(matcher.group()) + ">";
      matcher.appendReplacement(result, java.util.regex.Matcher.quoteReplacement(replacement));
    }
    matcher.appendTail(result);
    return result.toString();
  }

  private static String identifierKind(String key) {
    if (key == null || key.isBlank()) return null;
    String normalized = key.replaceAll("[^A-Za-z0-9]", "").toLowerCase(Locale.ROOT);
    if (normalized.equals("id") || isKnownIdField(normalized)) return kindFromKey(normalized, "ID");
    if (normalized.contains("token") || normalized.contains("secret")
        || normalized.contains("password") || normalized.contains("cookie")
        || normalized.contains("credential") || normalized.contains("authorization")) {
      return "REDACTED";
    }
    if (normalized.contains("instance") || normalized.contains("companion")
        || normalized.contains("controller") || normalized.contains("profile")
        || normalized.contains("installation") || normalized.contains("launcher")) return "ID";
    if (normalized.contains("lease") || normalized.contains("epoch")
        || normalized.contains("revision") || normalized.contains("timestamp")
        || normalized.endsWith("at") || normalized.equals("pid")
        || normalized.equals("port") || normalized.endsWith("port")) return "LOCAL";
    if (normalized.contains("handle") || normalized.contains("opaque")
        || normalized.contains("reference") || normalized.equals("raw")) return "ID";
    return null;
  }

  private static String kindFromKey(String normalized, String fallback) {
    if (normalized.contains("companion")) return "Companion";
    if (normalized.contains("instance")) return "Instance";
    if (normalized.contains("task")) return "Task";
    if (normalized.contains("execution")) return "Execution";
    if (normalized.contains("event")) return "Event";
    if (normalized.contains("question")) return "Question";
    if (normalized.contains("plan")) return "Plan";
    if (normalized.contains("behavior")) return "Behavior";
    if (normalized.contains("session")) return "Session";
    if (normalized.contains("controller")) return "Controller";
    if (normalized.contains("lease")) return "Lease";
    if (normalized.contains("call")) return "Call";
    if (normalized.contains("operation")) return "Operation";
    if (normalized.contains("profile")) return "Profile";
    if (normalized.contains("launcher")) return "Launcher";
    return fallback;
  }

  private static boolean isKnownIdField(String normalized) {
    return java.util.List.of("instance", "launcher", "profile", "installation", "companion",
        "controller", "session", "task", "execution", "event", "question", "plan",
        "behavior", "lease", "call", "operation", "memory", "suggestion", "skill",
        "trial", "request", "admission", "pack", "step", "revision", "correlation").stream()
        .anyMatch(prefix -> normalized.equals(prefix + "id"));
  }

  private static boolean isLikelyOpaqueIdentifier(String value) {
    return value != null && value.matches(
        "(?i)(?:companion|instance|execution|task|event|question|plan|behavior|session|controller|"
            + "lease|call|operation|skill|memory|suggestion|request|correlation)[-_][a-z0-9][a-z0-9._:-]{2,}");
  }

  private static String pseudonym(String value) {
    try {
      byte[] digest = MessageDigest.getInstance("SHA-256")
          .digest(value.toLowerCase(Locale.ROOT).getBytes(StandardCharsets.UTF_8));
      return java.util.HexFormat.of().formatHex(digest, 0, 4);
    } catch (NoSuchAlgorithmException impossible) {
      throw new IllegalStateException(impossible);
    }
  }
}
