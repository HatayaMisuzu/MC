package com.mccompanion.runtime.workspace;

import com.mccompanion.runtime.db.RuntimeDatabase;
import com.mccompanion.runtime.json.Json;
import com.mccompanion.runtime.security.Digests;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.*;

class SkillRepositoryTest {
    @TempDir Path temporary;

    @Test
    void requiresExternalApprovalAndSupportsDisableAndApprovedRollback() throws Exception {
        try (RuntimeDatabase database = new RuntimeDatabase(temporary.resolve("lifecycle.db"))) {
            database.initialize();
            SkillRepository repository = new SkillRepository(database);
            SkillVersion first = request(repository, "c1", "safe_skill", "one", "brain-1");
            assertEquals("PENDING_REVIEW", first.status());
            assertNull(first.approvedAt());
            assertEquals(first.requestId(), request(repository, "c1", "safe_skill", "one", "brain-1").requestId());

            SkillVersion activeOne = repository.approve(first.requestId(), "user:owner");
            assertEquals("ACTIVE", activeOne.status());
            assertEquals("user:owner", activeOne.approvedBy());
            assertNotNull(activeOne.approvedAt());

            SkillVersion second = request(repository, "c1", "safe_skill", "two", "brain-2");
            assertEquals(2, second.version());
            SkillVersion activeTwo = repository.approve(second.requestId(), "user:owner");
            assertEquals("ACTIVE", activeTwo.status());
            assertEquals("SUPERSEDED", repository.get(first.requestId()).orElseThrow().status());

            SkillVersion disabled = repository.disable("profile", "c1", "safe_skill",
                    "controller", "unsafe observation");
            assertEquals("DISABLED", disabled.status());
            SkillVersion rolledBack = repository.rollback("profile", "c1", "safe_skill", 1,
                    "controller", "restore known version");
            assertEquals("ACTIVE", rolledBack.status());
            assertEquals(1, rolledBack.version());
            assertEquals("DISABLED", repository.get(second.requestId()).orElseThrow().status());
        }
    }

    @Test
    void cannotRollbackPendingOrCrossCompanionVersion() throws Exception {
        try (RuntimeDatabase database = new RuntimeDatabase(temporary.resolve("isolation.db"))) {
            database.initialize();
            SkillRepository repository = new SkillRepository(database);
            request(repository, "c1", "safe_skill", "one", "brain");
            assertThrows(IllegalArgumentException.class, () -> repository.rollback(
                    "profile", "c1", "safe_skill", 1, "controller", "not approved"));
            assertThrows(IllegalArgumentException.class, () -> repository.rollback(
                    "profile", "c2", "safe_skill", 1, "controller", "cross companion"));
        }
    }

    private static SkillVersion request(SkillRepository repository, String companionId, String skillId,
                                        String document, String brainSession) throws Exception {
        return repository.requestPromotion("profile", companionId, skillId, "yaml", document,
                Digests.sha256(document), Json.MAPPER.createArrayNode(),
                Json.object().put("provider", "test"), Json.object().put("valid", true),
                "controller", brainSession);
    }
}
