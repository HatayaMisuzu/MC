package com.mccompanion.terminal.probe;

public final class JavaRequirementResolver {
    private JavaRequirementResolver() {}
    public static int requiredFor(String minecraftVersion) {
        if (minecraftVersion == null) return 0;
        if (minecraftVersion.equals("1.20.1")) return 17;
        if (minecraftVersion.equals("1.21.1")) return 21;
        return 0;
    }
}
