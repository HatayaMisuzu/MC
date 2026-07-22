package com.mccompanion.runtime.memory;

import com.mccompanion.runtime.db.RuntimeDatabase;
import com.mccompanion.runtime.json.Json;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.sql.PreparedStatement;

import static org.junit.jupiter.api.Assertions.*;

class EpisodeCapsuleRepositoryTest {
    @TempDir Path temporary;

    @Test
    void deterministicallyProjectsOnlyBoundedVerifiedEpisodeData() throws Exception {
        try (RuntimeDatabase database = new RuntimeDatabase(temporary.resolve("capsule.db"))) {
            database.initialize();
            seedEpisode(database, "c1", "brain-1");
            try (var connection = database.open()) {
                for (int index = 0; index < 140; index++) insertCall(connection, "brain-1", "bounded-" + index,
                        "world.query", false, "FAILURE_" + index, "{}", 2600 + index);
            }
            EpisodeCapsuleRepository repository = new EpisodeCapsuleRepository(database);

            EpisodeCapsule first = repository.generate("c1", "brain-1", "e93c86b1957159e47c56582824d915922023e1cb");
            EpisodeCapsule repeated = repository.generate("c1", "brain-1", "e93c86b1957159e47c56582824d915922023e1cb");

            assertEquals(first.episodeId(), repeated.episodeId());
            assertEquals(1, repository.list("c1", 20).size());
            assertEquals("minecraft:overworld", first.verifiedLocations().path(0).path("dimension").asText());
            assertEquals("option-safe", first.userConfirmedChoices().path(0).path("option_id").asText());
            assertEquals("TOOL_TIMEOUT", first.failureCategories().path(0).asText());
            assertEquals(32, first.failureCategories().size());
            assertEquals(128, first.evidenceRefs().size());
            String safe = Json.write(Json.MAPPER.valueToTree(first));
            assertTrue(safe.length() <= EpisodeCapsuleRepository.MAX_CAPSULE_CHARS);
            assertFalse(safe.contains("credential-secret-never-appear"));
            assertFalse(safe.contains("full private chat text"));
            assertFalse(safe.contains("search page body"));
            assertThrows(IllegalArgumentException.class,
                    () -> repository.generate("other", "brain-1", "e93c86b"));
        }
    }

    static void seedEpisode(RuntimeDatabase database, String companionId, String sessionId) throws Exception {
        try (var connection = database.open()) {
            try (PreparedStatement statement = connection.prepareStatement("""
                    INSERT INTO brain_session(session_id,controller_id,companion_id,provider,state,last_code,created_at,updated_at)
                    VALUES(?,?,?,'fixture','ACTIVE','WAIT',1000,5000)
                    """)) {
                statement.setString(1, sessionId); statement.setString(2, "controller"); statement.setString(3, companionId);
                statement.executeUpdate();
            }
            insertCall(connection, sessionId, "observe-1", "world.observe", true, "OK",
                    Json.write(Json.object().put("dimension", "minecraft:overworld")
                            .put("x", 1).put("y", 64).put("z", 2)
                            .put("private", "search page body")), 2000);
            insertCall(connection, sessionId, "failed-1", "registry.search", false, "TOOL_TIMEOUT",
                    Json.write(Json.object().put("detail", "bounded")), 2500);
            try (PreparedStatement statement = connection.prepareStatement("""
                    INSERT INTO waiting_question(question_id,plan_id,brain_session_id,task_id,
                    task_graph_execution_id,companion_id,prompt,reason,options_json,free_text_allowed,
                    state,context_json,answer_json,created_at,updated_at,expires_at)
                    VALUES('question-1',NULL,?,NULL,NULL,?,'full private chat text','NEEDS_DIRECTION',
                    '[]',0,'ANSWERED','{}','{"optionId":"option-safe","text":"credential-secret-never-appear"}',
                    3000,4000,NULL)
                    """)) {
                statement.setString(1, sessionId); statement.setString(2, companionId); statement.executeUpdate();
            }
        }
    }

    private static void insertCall(java.sql.Connection connection, String sessionId, String callId,
                                   String tool, boolean success, String code, String observation, long at)
            throws Exception {
        try (PreparedStatement statement = connection.prepareStatement("""
                INSERT INTO brain_tool_call(session_id,call_id,tool_name,arguments_json,success,result_code,
                observation_json,terminal,created_at,state,updated_at,delivered_at)
                VALUES(?,?,?,'{"credential":"credential-secret-never-appear"}',?,?,?,1,?,'SUCCEEDED',?,?)
                """)) {
            statement.setString(1, sessionId); statement.setString(2, callId); statement.setString(3, tool);
            statement.setInt(4, success ? 1 : 0); statement.setString(5, code); statement.setString(6, observation);
            statement.setLong(7, at); statement.setLong(8, at); statement.setLong(9, at); statement.executeUpdate();
        }
    }
}
