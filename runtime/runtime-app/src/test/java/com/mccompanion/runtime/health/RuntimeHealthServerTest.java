package com.mccompanion.runtime.health;

import com.mccompanion.runtime.json.Json;
import com.mccompanion.runtime.tool.ToolResult;
import org.junit.jupiter.api.Test;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

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

    @Test
    void managementPoolsAreBoundedAndLongMcpCategoriesReserveControlCapacity() throws Exception {
        assertTrue(RuntimeHealthServer.SYNCHRONOUS_MCP_LIMIT
                        + RuntimeHealthServer.STREAMING_MCP_LIMIT
                        < RuntimeHealthServer.MANAGEMENT_THREADS,
                "long MCP work must leave management threads available for health and cancellation");
        assertTrue(RuntimeHealthServer.MANAGEMENT_QUEUE_CAPACITY > 0);
        assertTrue(RuntimeHealthServer.PLANNING_QUEUE_CAPACITY > 0);

        CountDownLatch entered = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        var executor = RuntimeHealthServer.boundedPool(1, 1, "bounded-management-test");
        try {
            executor.execute(() -> {
                entered.countDown();
                try {
                    release.await();
                } catch (InterruptedException interrupted) {
                    Thread.currentThread().interrupt();
                }
            });
            assertTrue(entered.await(2, TimeUnit.SECONDS));
            executor.execute(() -> { });
            assertThrows(RejectedExecutionException.class, () -> executor.execute(() -> { }));
        } finally {
            release.countDown();
            executor.shutdownNow();
            assertTrue(executor.awaitTermination(2, TimeUnit.SECONDS));
        }
    }
}
