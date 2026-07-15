package com.mccompanion.runtime.agent;

import com.mccompanion.runtime.json.Json;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;

/** Stable semantic fingerprint used to stop repeated replan loops. */
final class PlanFingerprint {
    private PlanFingerprint() { }

    static String of(AgentDecision decision) {
        try {
            var semantic = Json.object().put("goal", decision.understoodGoal());
            semantic.set("constraints", Json.MAPPER.valueToTree(decision.constraints()));
            semantic.set("steps", Json.MAPPER.valueToTree(decision.steps()));
            byte[] digest = MessageDigest.getInstance("SHA-256")
                    .digest(Json.write(semantic).getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(digest);
        } catch (NoSuchAlgorithmException impossible) {
            throw new IllegalStateException("SHA-256 is unavailable", impossible);
        }
    }
}
