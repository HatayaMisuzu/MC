package com.mccompanion.terminal;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.mccompanion.terminal.runtime.RuntimeProfile;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;

/** Read-only, allow-listed projection of Runtime state for the local control UI. */
final class RuntimeSnapshotService {
  private static final ObjectMapper JSON = new ObjectMapper();

  ObjectNode snapshot(RuntimeProfile profile) {
    ObjectNode root = JSON.createObjectNode();
    root.put("instanceId", profile.instanceId());
    root.set("companions", JSON.createArrayNode());
    root.set("tasks", JSON.createArrayNode());
    root.set("events", JSON.createArrayNode());
    Path database = profile.profileDirectory().resolve("companion.db");
    if (!Files.isRegularFile(database)) return root;
    try (Connection connection = DriverManager.getConnection("jdbc:sqlite:" + database)) {
      companions(connection, (ArrayNode) root.get("companions"));
      tasks(connection, (ArrayNode) root.get("tasks"));
      events(connection, (ArrayNode) root.get("events"));
    } catch (Exception failure) {
      root.put("error", "Runtime 状态暂时不可读: " + failure.getClass().getSimpleName());
    }
    return root;
  }

  private static void companions(Connection connection, ArrayNode values) throws Exception {
    try (var statement =
            connection.prepareStatement(
                """
                SELECT c.companion_id,c.display_name,c.status_json,c.last_seen_at,
                       CASE WHEN s.disconnected_at IS NULL THEN 1 ELSE 0 END AS online,
                       l.epoch,l.mode,l.expires_at
                FROM companion c
                LEFT JOIN runtime_session s ON s.session_id=c.session_id
                LEFT JOIN control_lease l ON l.companion_id=c.companion_id
                ORDER BY c.last_seen_at DESC LIMIT 100
                """);
        var rows = statement.executeQuery()) {
      while (rows.next()) {
        ObjectNode value =
            values
                .addObject()
                .put("id", rows.getString("companion_id"))
                .put("displayName", rows.getString("display_name"))
                .put("online", rows.getInt("online") == 1)
                .put("lastSeenAt", rows.getLong("last_seen_at"));
        String status = rows.getString("status_json");
        if (status != null && !status.isBlank()) value.set("status", JSON.readTree(status));
        long epoch = rows.getLong("epoch");
        if (!rows.wasNull()) {
          value
              .put("leaseActive", true)
              .put("controlEpoch", epoch)
              .put("leaseMode", rows.getString("mode"))
              .put("leaseExpiresAt", rows.getLong("expires_at"));
        } else value.put("leaseActive", false);
      }
    }
  }

  private static void tasks(Connection connection, ArrayNode values) throws Exception {
    try (var statement =
            connection.prepareStatement(
                """
                SELECT task_id,companion_id,task_type,state,revision,behavior_id,
                       behavior_revision,control_epoch,reconciliation_required,created_at,updated_at
                FROM task ORDER BY updated_at DESC LIMIT 100
                """);
        var rows = statement.executeQuery()) {
      while (rows.next()) {
        ObjectNode value =
            values
                .addObject()
                .put("taskId", rows.getString("task_id"))
                .put("companionId", rows.getString("companion_id"))
                .put("type", rows.getString("task_type"))
                .put("state", rows.getString("state"))
                .put("revision", rows.getLong("revision"))
                .put("behaviorRevision", rows.getLong("behavior_revision"))
                .put("controlEpoch", rows.getLong("control_epoch"))
                .put("reconciliationRequired", rows.getInt("reconciliation_required") == 1)
                .put("createdAt", rows.getLong("created_at"))
                .put("updatedAt", rows.getLong("updated_at"));
        String behaviorId = rows.getString("behavior_id");
        if (behaviorId != null) value.put("behaviorId", behaviorId);
      }
    }
  }

  private static void events(Connection connection, ArrayNode values) throws Exception {
    try (var statement =
            connection.prepareStatement(
                """
                SELECT seq,task_id,revision,event_type,payload_json,created_at
                FROM task_event ORDER BY seq DESC LIMIT 200
                """);
        var rows = statement.executeQuery()) {
      while (rows.next()) {
        ObjectNode value =
            values
                .addObject()
                .put("sequence", rows.getLong("seq"))
                .put("taskId", rows.getString("task_id"))
                .put("revision", rows.getLong("revision"))
                .put("eventType", rows.getString("event_type"))
                .put("createdAt", rows.getLong("created_at"));
        String payload = rows.getString("payload_json");
        if (payload != null && !payload.isBlank()) value.set("payload", JSON.readTree(payload));
      }
    }
  }
}
