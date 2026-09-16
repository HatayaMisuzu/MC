package com.mccompanion.terminal.probe;

public final class JavaRequirementResolver {
    private JavaRequirementResolver() {}
    public static int requiredFor(String minecraftVersion) {
        return com.mccompanion.protocol.target.TargetCatalog.bundled().targets().stream()
                .filter(target -> target.minecraftVersion().equals(minecraftVersion))
                .mapToInt(com.mccompanion.protocol.target.TargetDescriptor::javaMinimum).max().orElse(0);
    }
}
