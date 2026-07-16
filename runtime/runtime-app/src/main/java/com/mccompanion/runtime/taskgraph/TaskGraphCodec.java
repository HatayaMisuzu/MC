package com.mccompanion.runtime.taskgraph;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.StreamReadConstraints;
import com.fasterxml.jackson.core.StreamReadFeature;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.dataformat.yaml.YAMLFactory;

import java.nio.charset.StandardCharsets;

/** Bounded parser for declarative JSON/YAML graphs; native executable scripts are intentionally unsupported. */
public final class TaskGraphCodec {
    private static final int MAX_DOCUMENT_BYTES = 2 * 1024 * 1024;
    private static final ObjectMapper YAML = new ObjectMapper(YAMLFactory.builder()
            .streamReadConstraints(StreamReadConstraints.builder().maxNestingDepth(64)
                    .maxStringLength(262_144).maxNumberLength(128).build())
            .enable(StreamReadFeature.STRICT_DUPLICATE_DETECTION).build())
            .enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS)
            .enable(DeserializationFeature.FAIL_ON_READING_DUP_TREE_KEY);

    private TaskGraphCodec() {
    }

    public static JsonNode parse(String document, Format format) {
        if (document == null || document.isBlank()) throw new IllegalArgumentException("task graph is empty");
        if (document.getBytes(StandardCharsets.UTF_8).length > MAX_DOCUMENT_BYTES) {
            throw new IllegalArgumentException("task graph exceeds 2 MiB");
        }
        try {
            return switch (format) {
                case JSON -> com.mccompanion.runtime.json.Json.parse(document);
                case YAML -> YAML.readTree(document);
            };
        } catch (JsonProcessingException failure) {
            throw new IllegalArgumentException("Invalid " + format.name() + " task graph", failure);
        }
    }

    public enum Format { JSON, YAML }
}
