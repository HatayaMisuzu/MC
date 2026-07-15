package com.mccompanion.runtime.execution;

import com.fasterxml.jackson.databind.JsonNode;
import com.mccompanion.runtime.json.Json;

public record PrimitiveObservation(boolean progressed, boolean complete, String failureCode, JsonNode evidence) {
    public PrimitiveObservation {
        failureCode = failureCode == null ? "" : failureCode;
        evidence = evidence == null ? Json.object() : evidence.deepCopy();
    }
    public static PrimitiveObservation progress(JsonNode evidence) { return new PrimitiveObservation(true, false, "", evidence); }
    public static PrimitiveObservation waiting(JsonNode evidence) { return new PrimitiveObservation(false, false, "", evidence); }
    public static PrimitiveObservation complete(JsonNode evidence) { return new PrimitiveObservation(true, true, "", evidence); }
    public static PrimitiveObservation failed(String code, JsonNode evidence) { return new PrimitiveObservation(false, false, code, evidence); }
}
