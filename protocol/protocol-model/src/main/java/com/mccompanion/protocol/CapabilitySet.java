package com.mccompanion.protocol;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonValue;

import java.util.Collection;
import java.util.Collections;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.TreeMap;

public final class CapabilitySet {
    private static final CapabilitySet EMPTY = new CapabilitySet(Map.of());
    private final Map<String, CapabilityDescriptor> capabilities;

    @JsonCreator(mode = JsonCreator.Mode.DELEGATING)
    public CapabilitySet(Map<String, CapabilityDescriptor> capabilities) {
        Objects.requireNonNull(capabilities, "capabilities");
        TreeMap<String, CapabilityDescriptor> copy = new TreeMap<>();
        capabilities.forEach((name, descriptor) -> copy.put(
                ProtocolFields.identifier(name, "capability name"),
                Objects.requireNonNull(descriptor, "capability descriptor")));
        this.capabilities = Collections.unmodifiableMap(copy);
    }

    public static CapabilitySet empty() {
        return EMPTY;
    }

    public static Builder builder() {
        return new Builder();
    }

    @JsonValue
    public Map<String, CapabilityDescriptor> asMap() {
        return capabilities;
    }

    public Optional<CapabilityDescriptor> find(String name) {
        return Optional.ofNullable(capabilities.get(name));
    }

    public boolean isAvailable(String name) {
        return find(name).map(CapabilityDescriptor::available).orElse(false);
    }

    public Collection<String> names() {
        return capabilities.keySet();
    }

    public int size() {
        return capabilities.size();
    }

    @Override
    public boolean equals(Object other) {
        return other instanceof CapabilitySet that && capabilities.equals(that.capabilities);
    }

    @Override
    public int hashCode() {
        return capabilities.hashCode();
    }

    @Override
    public String toString() {
        return "CapabilitySet" + capabilities;
    }

    public static final class Builder {
        private final Map<String, CapabilityDescriptor> values = new TreeMap<>();

        public Builder put(String name, CapabilityDescriptor descriptor) {
            String checkedName = ProtocolFields.identifier(name, "capability name");
            Objects.requireNonNull(descriptor, "descriptor");
            if (values.putIfAbsent(checkedName, descriptor) != null) {
                throw new IllegalArgumentException("duplicate capability: " + name);
            }
            return this;
        }

        public Builder available(String name, String version) {
            return put(name, CapabilityDescriptor.available(version));
        }

        public Builder unavailable(String name, String reason) {
            return put(name, CapabilityDescriptor.unavailable(reason));
        }

        public CapabilitySet build() {
            return values.isEmpty() ? CapabilitySet.empty() : new CapabilitySet(values);
        }
    }
}
