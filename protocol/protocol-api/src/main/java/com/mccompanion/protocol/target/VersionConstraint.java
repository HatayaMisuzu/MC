package com.mccompanion.protocol.target;

import java.math.BigInteger;
import java.util.Arrays;
import java.util.regex.Pattern;

/** Bounded numeric release constraints used by the trusted target catalog and native metadata.
 * Supports exact releases, comparator conjunctions/disjunctions and Maven intervals.
 * Unknown qualifiers/syntax are rejected, never guessed to be a nearby supported release.
 */
public final class VersionConstraint {
    private static final Pattern VERSION = Pattern.compile("[0-9]{1,10}(?:\\.[0-9]{1,10}){0,5}(?:\\+[A-Za-z0-9._-]{1,64})?");
    private VersionConstraint() { }

    public static boolean matches(String constraint, String version) {
        if (constraint == null || version == null || constraint.length() > 512 || !VERSION.matcher(version).matches()) return false;
        try {
            String[] alternatives = constraint.strip().split("\\s*\\|\\|\\s*", -1);
            if (alternatives.length > 16) return false;
            // Validate every alternative, including ones following an already matching arm.
            boolean matched = false;
            for (String alternative : alternatives) matched |= arm(alternative, version);
            return matched;
        } catch (IllegalArgumentException invalid) { return false; }
    }

    private static boolean arm(String expression, String version) {
        if (expression.startsWith("[") || expression.startsWith("(")) {
            if (!(expression.endsWith("]") || expression.endsWith(")"))) throw new IllegalArgumentException("interval");
            String[] bounds = expression.substring(1, expression.length() - 1).split(",", -1);
            if (bounds.length == 1 && expression.startsWith("[") && expression.endsWith("]")) return compare(version, bounds[0]) == 0;
            if (bounds.length != 2 || bounds[0].isBlank() && bounds[1].isBlank()) throw new IllegalArgumentException("interval");
            int lower = bounds[0].isBlank() ? 1 : compare(version, bounds[0].strip());
            int upper = bounds[1].isBlank() ? -1 : compare(version, bounds[1].strip());
            if (!bounds[0].isBlank() && !bounds[1].isBlank() && compare(bounds[0].strip(), bounds[1].strip()) > 0) throw new IllegalArgumentException("reversed interval");
            return (lower > 0 || lower == 0 && expression.startsWith("["))
                    && (upper < 0 || upper == 0 && expression.endsWith("]"));
        }
        String[] clauses = expression.split("\\s+", -1);
        if (clauses.length > 16) throw new IllegalArgumentException("clauses");
        boolean matched = true;
        for (String clause : clauses) {
            var match = Pattern.compile("(>=|<=|>|<|=)?(.+)").matcher(clause);
            if (!match.matches()) throw new IllegalArgumentException("constraint");
            int comparison = compare(version, match.group(2));
            String operator = match.group(1) == null ? "=" : match.group(1);
            matched &= switch (operator) {
                case ">=" -> comparison >= 0;
                case "<=" -> comparison <= 0;
                case ">" -> comparison > 0;
                case "<" -> comparison < 0;
                default -> comparison == 0;
            };
        }
        return matched;
    }

    public static int compare(String left, String right) {
        if (!VERSION.matcher(left).matches() || !VERSION.matcher(right).matches()) throw new IllegalArgumentException("Unsupported release version");
        var a = parts(left); var b = parts(right);
        for (int index = 0; index < Math.max(a.length, b.length); index++) {
            int difference = (index < a.length ? a[index] : BigInteger.ZERO)
                    .compareTo(index < b.length ? b[index] : BigInteger.ZERO);
            if (difference != 0) return difference;
        }
        return 0;
    }
    private static BigInteger[] parts(String value) {
        return Arrays.stream(value.split("\\+", 2)[0].split("\\.")).map(BigInteger::new).toArray(BigInteger[]::new);
    }
}
