package com.mccompanion.runtime.workspace;

import java.time.Instant;

/** Logical workspace metadata. Physical host paths are intentionally absent. */
public record WorkspaceResource(String logicalPath, long version, String sha256, long sizeBytes,
                                Instant updatedAt) {
}
