package com.mccompanion.minecraft.bridge;

import java.util.Objects;
import java.util.function.Function;

/** Shared field selection for the Runtime START_BEHAVIOR wire contract. */
public final class RuntimeCommandArguments {
  private RuntimeCommandArguments() { }

  public static <T> T skillParameters(Function<String, T> fieldReader) {
    return Objects.requireNonNull(fieldReader, "fieldReader").apply("parameters");
  }
}
