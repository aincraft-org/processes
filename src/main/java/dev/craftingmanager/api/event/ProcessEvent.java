package dev.craftingmanager.api.event;

import dev.craftingmanager.api.Domain.BlockKey;
import org.bukkit.event.Event;

import java.util.Objects;
import java.util.UUID;

/**
 * Shared process-usage event. Listen to this type for every start/finish,
 * or to {@link PreProcessEvent} to cancel a start.
 */
public abstract class ProcessEvent extends Event {
    private final UUID instanceId;
    private final BlockKey block;
    private final String processId;
    private final UUID owner;

    protected ProcessEvent(UUID instanceId, BlockKey block, String processId, UUID owner) {
        this.instanceId = instanceId;
        this.block = Objects.requireNonNull(block, "block");
        if (processId == null || processId.isBlank()) throw new IllegalArgumentException("processId is required");
        this.processId = processId;
        this.owner = Objects.requireNonNull(owner, "owner");
    }

    /** Null on {@link PreProcessEvent} before an instance exists. */
    public UUID instanceId() { return instanceId; }
    public BlockKey block() { return block; }
    public String processId() { return processId; }
    public UUID owner() { return owner; }
}
