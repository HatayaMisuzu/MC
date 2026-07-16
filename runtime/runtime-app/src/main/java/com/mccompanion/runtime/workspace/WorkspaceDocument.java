package com.mccompanion.runtime.workspace;

/** Bounded UTF-8 workspace document plus integrity metadata. */
public record WorkspaceDocument(WorkspaceResource resource, String content) {
}
