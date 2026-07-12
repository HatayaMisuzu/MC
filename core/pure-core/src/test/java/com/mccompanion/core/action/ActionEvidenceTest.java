package com.mccompanion.core.action;

import com.mccompanion.core.failure.FailureCode;
import com.mccompanion.core.id.ActionId;
import com.mccompanion.core.id.BehaviorId;
import com.mccompanion.core.id.CompanionId;
import com.mccompanion.core.id.TaskId;
import com.mccompanion.core.id.WorldId;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ActionEvidenceTest {
    private static final Instant START = Instant.parse("2026-07-12T00:00:00Z");
    private static final PositionSnapshot BEFORE = new PositionSnapshot(
            new WorldId("world-1"), "minecraft:overworld", 0, 64, 0);
    private static final InventoryDigest INVENTORY = InventoryDigest.sha256(
            "inventory".getBytes(StandardCharsets.UTF_8), 1, 3);

    @Test
    void builderCreatesImmutableSuccessfulEvidenceAndClosesAfterFinish() {
        ActionEvidenceBuilder builder = builder().metadata("intent", "follow");
        ActionEvidence evidence = builder.finish(12,
                new PositionSnapshot(new WorldId("world-1"), "minecraft:overworld", 1, 64, 0),
                INVENTORY, FailureCode.OK, PlayerActionPath.MOVEMENT_INPUT, false,
                START.plusSeconds(1));

        assertTrue(evidence.success());
        assertFalse(evidence.forbiddenWriteDetected());
        assertEquals(PlayerActionPath.MOVEMENT_INPUT, evidence.playerPathUsed());
        assertEquals("follow", evidence.metadata().get("intent"));
        assertThrows(UnsupportedOperationException.class,
                () -> evidence.metadata().put("other", "value"));
        assertThrows(IllegalStateException.class,
                () -> builder.metadata("other", "value"));
    }

    @Test
    void failedFinishDoesNotSealBuilderAndCanBeCorrected() {
        ActionEvidenceBuilder builder = builder();
        assertThrows(IllegalArgumentException.class, () -> builder.finish(9, BEFORE, INVENTORY,
                FailureCode.OK, PlayerActionPath.NONE, false, START.plusSeconds(1)));

        ActionEvidence corrected = builder.finish(10, BEFORE, INVENTORY,
                FailureCode.OK, PlayerActionPath.NONE, false, START.plusSeconds(1));
        assertTrue(corrected.success());
    }

    @Test
    void forbiddenWriteFlagAndFailureCodeMustAgreeBothWays() {
        assertThrows(IllegalArgumentException.class, () -> builder().finish(10, BEFORE, INVENTORY,
                FailureCode.INTERNAL_ERROR, PlayerActionPath.NONE, true, START.plusSeconds(1)));
        assertThrows(IllegalArgumentException.class, () -> builder().finish(10, BEFORE, INVENTORY,
                FailureCode.FORBIDDEN_WRITE_DETECTED, PlayerActionPath.NONE, false, START.plusSeconds(1)));

        ActionEvidence detected = builder().finish(10, BEFORE, INVENTORY,
                FailureCode.FORBIDDEN_WRITE_DETECTED, PlayerActionPath.NONE, true, START.plusSeconds(1));
        assertFalse(detected.success());
    }

    @Test
    void detectsWorldAndDimensionChanges() {
        ActionEvidence changed = builder().finish(10,
                new PositionSnapshot(new WorldId("world-2"), "minecraft:the_nether", 0, 64, 0),
                INVENTORY, FailureCode.OK, PlayerActionPath.MOVEMENT_INPUT, false, START.plusSeconds(1));
        assertTrue(changed.changedWorld());

        assertThrows(IllegalArgumentException.class, () -> builder().finish(10,
                new PositionSnapshot(new WorldId("world-1"), "minecraft:overworld", 1, 64, 0),
                INVENTORY, FailureCode.OK, PlayerActionPath.NONE, false, START.plusSeconds(1)));
    }

    @Test
    void inventoryDigestAndMetadataValidateAuditShape() {
        InventoryDigest first = InventoryDigest.sha256("same".getBytes(StandardCharsets.UTF_8), 1, 1);
        InventoryDigest second = InventoryDigest.sha256("same".getBytes(StandardCharsets.UTF_8), 1, 1);
        assertEquals(first, second);
        assertThrows(IllegalArgumentException.class,
                () -> new InventoryDigest(InventoryDigest.SHA_256, "0".repeat(64), 2, 1));
        assertThrows(IllegalArgumentException.class, () -> builder().metadata(" ", "value"));
        assertThrows(IllegalArgumentException.class, () -> builder().metadata("key", null));
    }

    @Test
    void directEvidenceRejectsMutableOrInconsistentOutcome() {
        assertThrows(IllegalArgumentException.class, () -> new ActionEvidence(
                ActionId.random(), BehaviorId.random(), TaskId.random(), CompanionId.random(),
                10, 10, BEFORE, BEFORE, INVENTORY, INVENTORY, true, FailureCode.INTERNAL_ERROR,
                PlayerActionPath.NONE, false, START, START, Map.of()));
    }

    private static ActionEvidenceBuilder builder() {
        return ActionEvidenceBuilder.begin(ActionId.random(), BehaviorId.random(), TaskId.random(),
                CompanionId.random(), 10, BEFORE, INVENTORY, START);
    }
}
