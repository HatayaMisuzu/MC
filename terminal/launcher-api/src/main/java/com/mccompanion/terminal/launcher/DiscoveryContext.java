package com.mccompanion.terminal.launcher;

import java.nio.file.Path;
import java.util.List;

public record DiscoveryContext(List<Path> searchRoots, boolean includeCommonLocations) {
    public DiscoveryContext { searchRoots = List.copyOf(searchRoots); }
}
