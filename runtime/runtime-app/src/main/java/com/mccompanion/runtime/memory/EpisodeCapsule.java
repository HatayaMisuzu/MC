package com.mccompanion.runtime.memory;

import com.fasterxml.jackson.databind.JsonNode;

import java.time.Instant;

/** Bounded deterministic summary derived only from durable verified Runtime records. */
public record EpisodeCapsule(String episodeId, String companionId, String brainSessionId,
                             Instant startedAt, Instant endedAt, JsonNode taskSummaries,
                             JsonNode verifiedWorldChanges, JsonNode verifiedInventoryChanges,
                             JsonNode verifiedLocations, JsonNode askUserDecisions,
                             JsonNode userConfirmedChoices, JsonNode failureCategories,
                             JsonNode evidenceRefs, String sourceSha, Instant createdAt) { }
