package dev.craftingmanager.api;

import java.util.UUID;

import static dev.craftingmanager.api.Domain.BlockKey;

/** Who used which process on which block. `instanceId` is null until start succeeds. */
public record ProcessUsage(UUID instanceId, BlockKey block, String processId, UUID owner) {
    public ProcessUsage {
        if (block == null) throw new IllegalArgumentException("block is required");
        if (processId == null || processId.isBlank()) throw new IllegalArgumentException("processId is required");
        if (owner == null) throw new IllegalArgumentException("owner is required");
    }
}
