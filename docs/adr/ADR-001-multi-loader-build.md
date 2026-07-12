# ADR-001: Isolated loader builds with a shared semantic core

## Status

Accepted for Alpha 0.1.

## Decision

Keep protocol and pure core in the root build. Build Fabric 1.21.1, NeoForge
1.21.1, and Forge 1.20.1 in isolated loader workspaces, orchestrated by the
root Gradle entry points.

## Reasons

- Each loader plugin can use its supported Gradle and mapping pipeline.
- Minecraft 1.21.1 code targets Java 21 while Forge 1.20.1 targets Java 17.
- Loader and Minecraft types cannot leak into `pure-core` or the Runtime.
- Each published JAR has exact metadata and an independently testable server.

## Independence

Numen and Baritone are research references only. They are not dependencies,
metadata requirements, reflected runtime types, or installation prerequisites.

