package com.mccompanion.protocol;

import java.util.Objects;

public final class ProtocolNegotiator {
    private final ProtocolVersion supported;

    public ProtocolNegotiator(ProtocolVersion supported) {
        this.supported = Objects.requireNonNull(supported, "supported");
    }

    public ProtocolNegotiationResult negotiate(ProtocolVersion requested) {
        Objects.requireNonNull(requested, "requested");
        if (!supported.product().equals(requested.product())) {
            return new ProtocolNegotiationResult(false, null, ProtocolRejectionReason.WRONG_PRODUCT,
                    "Runtime protocol product is not supported; Minecraft remains available in local-only mode");
        }
        if (supported.major() != requested.major()) {
            return new ProtocolNegotiationResult(false, null, ProtocolRejectionReason.UNSUPPORTED_MAJOR,
                    "Protocol major " + requested.major() + " is incompatible with supported major "
                            + supported.major() + "; Minecraft remains available in local-only mode");
        }
        ProtocolVersion negotiated = supported.negotiate(requested);
        return new ProtocolNegotiationResult(true, negotiated, ProtocolRejectionReason.NONE,
                "Protocol " + negotiated + " accepted");
    }

    public ProtocolVersion supported() {
        return supported;
    }
}
