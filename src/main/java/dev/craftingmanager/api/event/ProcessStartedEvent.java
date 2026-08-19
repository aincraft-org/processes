package dev.craftingmanager.api.event;

import dev.craftingmanager.api.Domain.BlockKey;
import org.bukkit.event.Event;
import org.bukkit.event.HandlerList;
import java.util.UUID;

/** Fired after a process instance is created and inputs are reserved. */
public final class ProcessStartedEvent extends Event {
    private static final HandlerList HANDLERS = new HandlerList();

    private final UUID instanceId;
    private final BlockKey block;
    private final String processId;
    private final UUID owner;

    public ProcessStartedEvent(UUID instanceId, BlockKey block, String processId, UUID owner) {
        this.instanceId = instanceId;
        this.block = block;
        this.processId = processId;
        this.owner = owner;
    }

    public UUID instanceId() { return instanceId; }
    public BlockKey block() { return block; }
    public String processId() { return processId; }
    public UUID owner() { return owner; }

    @Override public HandlerList getHandlers() { return HANDLERS; }
    public static HandlerList getHandlerList() { return HANDLERS; }
}
