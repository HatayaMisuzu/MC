package com.mccompanion.runtime.brain;

import com.mccompanion.runtime.agent.AgentContext;
import com.mccompanion.runtime.json.Json;
import com.mccompanion.runtime.tool.ToolCall;
import com.mccompanion.runtime.tool.ToolContext;
import com.mccompanion.runtime.tool.ToolDefinition;
import com.mccompanion.runtime.tool.ToolGateway;
import com.mccompanion.runtime.tool.ToolResult;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;

class ExternalBrainCoordinatorTest {
    @Test
    void replayBrainCanChatWithoutCreatingOrCallingAnyTool() {
        RecordingGateway gateway = new RecordingGateway();
        ReplayBrainAdapter brain = new ReplayBrainAdapter(request ->
                BrainTurnResult.finalResponse("今天就轻松聊会儿，不开始任务。"));
        try (ExternalBrainCoordinator coordinator = new ExternalBrainCoordinator(brain, gateway, 4)) {
            BrainCoordinatorResult result = coordinator.continueTurn("hermes-1", "c1",
                    "今天有点累", context());

            assertEquals(BrainTurnResult.Kind.FINAL_RESPONSE, result.kind());
            assertTrue(result.response().contains("不开始任务"));
            assertTrue(result.toolResults().isEmpty());
            assertTrue(gateway.calls.isEmpty());
            assertEquals("hermes-1", coordinator.activeControllerId());
        }
    }

    @Test
    void coordinatorRelaysToolObservationBackToTheSameExternalBrainTurn() {
        RecordingGateway gateway = new RecordingGateway();
        AtomicInteger turns = new AtomicInteger();
        ReplayBrainAdapter brain = new ReplayBrainAdapter(request -> {
            if (turns.getAndIncrement() == 0) {
                assertTrue(request.toolResults().isEmpty());
                return BrainTurnResult.tools(List.of(new ToolCall("observe-1", "world.observe", Json.object())));
            }
            assertEquals(1, request.toolResults().size());
            assertEquals(18, request.toolResults().getFirst().observation().path("health").asInt());
            assertEquals("", request.userMessage());
            return BrainTurnResult.finalResponse("现在生命值是 18，我建议先休息，不自动行动。");
        });
        try (ExternalBrainCoordinator coordinator = new ExternalBrainCoordinator(brain, gateway, 4)) {
            BrainCoordinatorResult result = coordinator.continueTurn("hermes-1", "c1",
                    "我们现在适合做什么？", context());

            assertEquals(2, turns.get());
            assertEquals(List.of("world.observe"), gateway.calls);
            assertEquals(1, result.toolResults().size());
            assertEquals(BrainTurnResult.Kind.FINAL_RESPONSE, result.kind());
        }
    }

    @Test
    void enforcesSingleControllerAndToolBudgetWithoutInventingFallbackStrategy() {
        RecordingGateway gateway = new RecordingGateway();
        ReplayBrainAdapter brain = new ReplayBrainAdapter(request -> BrainTurnResult.tools(List.of(
                new ToolCall("a", "world.observe", Json.object()),
                new ToolCall("b", "world.observe", Json.object()))));
        try (ExternalBrainCoordinator coordinator = new ExternalBrainCoordinator(brain, gateway, 1)) {
            BrainCoordinatorResult exhausted = coordinator.continueTurn("hermes-1", "c1", "observe", context());
            assertEquals("TOOL_BUDGET_EXHAUSTED", exhausted.code());
            assertTrue(gateway.calls.isEmpty());
            assertThrows(IllegalStateException.class,
                    () -> coordinator.continueTurn("deepseek-2", "c1", "take over", context()));
        }
    }

    private static AgentContext context() {
        return AgentContext.empty("c1", List.of("NavigateTo", "FollowOwner"));
    }

    private static final class RecordingGateway implements ToolGateway {
        private final List<String> calls = new ArrayList<>();

        @Override public List<ToolDefinition> definitions(ToolContext context) {
            return List.of(new ToolDefinition("world.observe", "1.0", "Observe verified state",
                    Json.object().put("type", "object"), "LOW", "READ_WORLD", Duration.ofSeconds(5), true));
        }

        @Override public ToolResult execute(ToolContext context, ToolCall call) {
            calls.add(call.name());
            return new ToolResult(call.callId(), call.name(), true, "OK",
                    Json.object().put("health", 18), true);
        }
    }
}
