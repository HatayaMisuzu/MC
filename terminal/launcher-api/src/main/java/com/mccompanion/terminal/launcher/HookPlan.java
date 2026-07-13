package com.mccompanion.terminal.launcher;

import java.nio.file.Path;
import java.util.List;

public record HookPlan(String instanceId, List<FileChange> changes, String summary) {
    public HookPlan { changes = List.copyOf(changes); }
    public record FileChange(Path target, byte[] content, boolean restoreOnRemove) {
        public FileChange { content = content.clone(); }
        @Override public byte[] content() { return content.clone(); }
    }
}
