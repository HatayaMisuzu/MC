package com.mccompanion.runtime.health;

import com.mccompanion.runtime.json.Json;
import com.mccompanion.runtime.tool.ToolResult;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;

class RuntimeHealthServerTest {
    @Test
    void mapsToolProgressToStandardMcpNotificationWithClientToken() {
        ToolResult progress = new ToolResult("call-1", "movement.navigate", true, "TOOL_PROGRESS",
                Json.object().put("state", "RUNNING").put("taskRevision", 4).put("taskId", "task-1"), false);

        var notification = RuntimeHealthServer.mcpProgress(Json.MAPPER.getNodeFactory().textNode("token-1"), progress);

        assertEquals("2.0", notification.path("jsonrpc").asText());
        assertEquals("notifications/progress", notification.path("method").asText());
        assertEquals("token-1", notification.path("params").path("progressToken").asText());
        assertEquals(4, notification.path("params").path("progress").asInt());
        assertEquals("RUNNING", notification.path("params").path("message").asText());
        assertEquals("task-1", notification.path("params").path("structuredContent")
                .path("observation").path("taskId").asText());
        assertFalse(notification.path("params").path("structuredContent").path("terminal").asBoolean());
    }
}
