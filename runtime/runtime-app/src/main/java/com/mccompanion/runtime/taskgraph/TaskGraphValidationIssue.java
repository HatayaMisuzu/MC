package com.mccompanion.runtime.taskgraph;

public record TaskGraphValidationIssue(String path, String code, String message) {
}
