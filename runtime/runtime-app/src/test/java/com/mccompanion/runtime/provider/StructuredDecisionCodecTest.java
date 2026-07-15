package com.mccompanion.runtime.provider;

import com.mccompanion.runtime.agent.DecisionKind;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class StructuredDecisionCodecTest {
    @Test
    void decodesStrictDecisionAndRejectsUnknownFields() throws Exception {
        String valid = """
                {"kind":"ASK_CLARIFICATION","understoodGoal":"去那边看看","constraints":[],
                 "assumptions":[],"steps":[],"reply":"你指的是哪个位置？","reason":"目标不明确"}
                """;
        assertEquals(DecisionKind.ASK_CLARIFICATION, new StructuredDecisionCodec().decode(valid).kind());
        ProviderException rejected = assertThrows(ProviderException.class,
                () -> new StructuredDecisionCodec().decode(valid.replace("\"reason\"", "\"script\"")));
        assertEquals("PROVIDER_INVALID_OUTPUT", rejected.code());
    }

    @Test
    void rejectsArbitraryFieldsInsideStepsBeforeExecution() {
        String invalid = """
                {"kind":"CREATE_PLAN","understoodGoal":"test","constraints":[],"assumptions":[],
                 "steps":[{"goalState":"x","capability":"NavigateTo","parameters":{},
                 "expectedResult":"x","completionCriteria":{},"failurePolicy":"stop",
                 "opportunistic":false,"risk":"LOW","shell":"rm"}],"reply":"","reason":""}
                """;
        assertThrows(ProviderException.class, () -> new StructuredDecisionCodec().decode(invalid));
    }
}
