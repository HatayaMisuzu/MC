package com.mccompanion.runtime.input;

import java.text.Normalizer;
import java.util.Locale;
import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Normalizes syntax and extracts safe scalar hints; it does not decide complex intent. */
public final class TextNormalizer {
    private static final Pattern COORDINATES = Pattern.compile("(?<!\\d)([+-]?\\d{1,8})\\s*[,， ]\\s*([+-]?\\d{1,5})\\s*[,， ]\\s*([+-]?\\d{1,8})(?!\\d)");
    private static final Pattern ARABIC_QUANTITY = Pattern.compile("(?<![\\d+-])(\\d{1,4})(?:\\s*(?:个|块|根|颗|锭|把|组))?");
    private static final java.util.List<GameUnit> GAME_UNITS = java.util.List.of(
            new GameUnit("四分之一组", 16), new GameUnit("半组", 32), new GameUnit("一组", 64));

    public NormalizedInput normalize(String value) {
        String original = value == null ? "" : value;
        String text = Normalizer.normalize(original, Normalizer.Form.NFKC)
                .replace('\u3000', ' ').replaceAll("\\s+", " ").strip();
        if (text.length() > 4096) throw new IllegalArgumentException("Input exceeds 4096 characters");
        String normalized = text.toLowerCase(Locale.ROOT);
        Optional<NormalizedInput.Coordinates> coordinates = coordinates(normalized);
        Optional<Integer> quantity = quantity(normalized, coordinates.isPresent());
        return new NormalizedInput(original, normalized, quantity, coordinates);
    }

    private static Optional<NormalizedInput.Coordinates> coordinates(String text) {
        Matcher matcher = COORDINATES.matcher(text);
        if (!matcher.find()) return Optional.empty();
        int x = Integer.parseInt(matcher.group(1));
        int y = Integer.parseInt(matcher.group(2));
        int z = Integer.parseInt(matcher.group(3));
        if (Math.abs((long) x) > 30_000_000 || Math.abs((long) z) > 30_000_000 || y < -2048 || y > 2048) {
            return Optional.empty();
        }
        return Optional.of(new NormalizedInput.Coordinates(x, y, z));
    }

    private static Optional<Integer> quantity(String text, boolean hasCoordinates) {
        for (var unit : GAME_UNITS) if (text.contains(unit.text())) return Optional.of(unit.quantity());
        if (hasCoordinates) return Optional.empty();
        Matcher matcher = ARABIC_QUANTITY.matcher(text);
        if (!matcher.find()) return Optional.empty();
        int value = Integer.parseInt(matcher.group(1));
        return value > 0 ? Optional.of(value) : Optional.empty();
    }

    private record GameUnit(String text, int quantity) { }
}
