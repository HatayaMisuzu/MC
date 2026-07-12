package com.mccompanion.core.id;

import java.util.UUID;

public sealed interface UuidIdentifier permits ActionId, BehaviorId, CommandId, CompanionId, LeaseId, OwnerId,
        SessionId, TaskId {
    UUID value();

    default String canonical() {
        return value().toString();
    }
}
