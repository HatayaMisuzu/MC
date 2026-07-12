package com.mccompanion.protocol;

import java.util.Objects;
import java.util.Optional;

public record ProtocolNegotiationResult(
        boolean accepted,
        ProtocolVersion negotiatedVersion,
        ProtocolRejectionReason rejectionReason,
        String message) {

    public ProtocolNegotiationResult {
        Objects.requireNonNull(rejectionReason, "rejectionReason");
        message = ProtocolFields.text(message, "message");
        if (accepted) {
            Objects.requireNonNull(negotiatedVersion, "negotiatedVersion");
            if (rejectionReason != ProtocolRejectionReason.NONE) {
                throw new IllegalArgumentException("accepted negotiation cannot have a rejection reason");
            }
        } else {
            if (negotiatedVersion != null || rejectionReason == ProtocolRejectionReason.NONE) {
                throw new IllegalArgumentException("rejected negotiation requires a rejection reason only");
            }
        }
    }

    public Optional<ProtocolVersion> negotiated() {
        return Optional.ofNullable(negotiatedVersion);
    }
}
