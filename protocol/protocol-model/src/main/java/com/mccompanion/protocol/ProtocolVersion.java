package com.mccompanion.protocol;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonValue;

import java.util.Objects;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public record ProtocolVersion(String product, int major, int minor) implements Comparable<ProtocolVersion> {
    public static final String PRODUCT = "mc-companion";
    public static final ProtocolVersion CURRENT = new ProtocolVersion(PRODUCT, 1, 0);
    private static final Pattern FORMAT = Pattern.compile("([a-z][a-z0-9-]{1,63})/([0-9]{1,5})(?:\\.([0-9]{1,5}))?");
    private static final int MAX_COMPONENT = 65_535;

    public ProtocolVersion {
        Objects.requireNonNull(product, "product");
        if (!product.matches("[a-z][a-z0-9-]{1,63}")) {
            throw new IllegalArgumentException("product is not a valid protocol product name");
        }
        if (major < 0 || major > MAX_COMPONENT || minor < 0 || minor > MAX_COMPONENT) {
            throw new IllegalArgumentException("protocol components must be between 0 and " + MAX_COMPONENT);
        }
    }

    @JsonCreator(mode = JsonCreator.Mode.DELEGATING)
    public static ProtocolVersion parse(String value) {
        Objects.requireNonNull(value, "value");
        Matcher matcher = FORMAT.matcher(value);
        if (!matcher.matches()) {
            throw new IllegalArgumentException("protocol version must use product/major[.minor] syntax");
        }
        int major = Integer.parseInt(matcher.group(2));
        int minor = matcher.group(3) == null ? 0 : Integer.parseInt(matcher.group(3));
        return new ProtocolVersion(matcher.group(1), major, minor);
    }

    public boolean isCompatibleWith(ProtocolVersion other) {
        return other != null && product.equals(other.product) && major == other.major;
    }

    public ProtocolVersion negotiate(ProtocolVersion other) {
        if (!isCompatibleWith(other)) {
            throw new IllegalArgumentException("incompatible protocol versions");
        }
        return new ProtocolVersion(product, major, Math.min(minor, other.minor));
    }

    @Override
    public int compareTo(ProtocolVersion other) {
        Objects.requireNonNull(other, "other");
        int productComparison = product.compareTo(other.product);
        if (productComparison != 0) {
            return productComparison;
        }
        int majorComparison = Integer.compare(major, other.major);
        return majorComparison != 0 ? majorComparison : Integer.compare(minor, other.minor);
    }

    @Override
    @JsonValue
    public String toString() {
        return product + "/" + major + (minor == 0 ? "" : "." + minor);
    }
}
