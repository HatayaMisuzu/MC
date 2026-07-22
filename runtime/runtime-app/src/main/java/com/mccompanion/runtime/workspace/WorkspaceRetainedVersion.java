package com.mccompanion.runtime.workspace;

/** Retained logical workspace version metadata. Physical backup paths are never exposed. */
public record WorkspaceRetainedVersion(long version, String sha256, long sizeBytes) {
}
