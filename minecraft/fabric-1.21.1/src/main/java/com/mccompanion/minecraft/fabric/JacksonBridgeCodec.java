package com.mccompanion.minecraft.fabric;

import com.mccompanion.minecraft.bridge.BridgeCodec;
import com.mccompanion.minecraft.bridge.BridgeValues;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.util.Map;

public final class JacksonBridgeCodec implements BridgeCodec {
    private final ObjectMapper mapper = new ObjectMapper(com.fasterxml.jackson.core.JsonFactory.builder()
            .enable(com.fasterxml.jackson.core.StreamReadFeature.STRICT_DUPLICATE_DETECTION).build())
            .enable(com.fasterxml.jackson.databind.DeserializationFeature.FAIL_ON_TRAILING_TOKENS);
    @Override public Map<String, Object> decode(String text) throws IOException {
        return BridgeValues.freeze(mapper.readValue(text, new TypeReference<Map<String, Object>>() { }));
    }
    @Override public String encode(Map<String, Object> value) throws IOException { return mapper.writeValueAsString(value); }
}
